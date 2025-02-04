/*
 * Copyright © 2018, 2020 Apple Inc. and the ServiceTalk project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.servicetalk.http.netty;

import io.servicetalk.client.api.ConnectionFactory;
import io.servicetalk.client.api.ConnectionFactoryFilter;
import io.servicetalk.client.api.ConsumableEvent;
import io.servicetalk.client.api.internal.ReservableRequestConcurrencyController;
import io.servicetalk.concurrent.api.Completable;
import io.servicetalk.concurrent.api.ListenableAsyncCloseable;
import io.servicetalk.concurrent.api.Publisher;
import io.servicetalk.concurrent.api.Single;
import io.servicetalk.context.api.ContextMap;
import io.servicetalk.http.api.FilterableStreamingHttpConnection;
import io.servicetalk.http.api.FilterableStreamingHttpLoadBalancedConnection;
import io.servicetalk.http.api.HttpExecutionContext;
import io.servicetalk.http.api.HttpExecutionStrategies;
import io.servicetalk.http.api.HttpExecutionStrategy;
import io.servicetalk.http.api.HttpProtocolVersion;
import io.servicetalk.http.api.StreamingHttpConnectionFilterFactory;
import io.servicetalk.http.api.StreamingHttpRequestResponseFactory;
import io.servicetalk.transport.api.ConnectExecutionStrategy;
import io.servicetalk.transport.api.ConnectionContext;
import io.servicetalk.transport.api.ExecutionStrategy;
import io.servicetalk.transport.api.IoThreadFactory;
import io.servicetalk.transport.api.TransportObserver;
import io.servicetalk.transport.netty.internal.NettyConnectionContext;
import io.servicetalk.transport.netty.internal.NoopTransportObserver;

import java.util.function.Function;
import javax.annotation.Nullable;

import static io.servicetalk.concurrent.api.AsyncCloseables.emptyAsyncCloseable;
import static io.servicetalk.http.api.HttpEventKey.MAX_CONCURRENCY;
import static io.servicetalk.transport.api.TransportObservers.asSafeObserver;
import static java.util.Objects.requireNonNull;

abstract class AbstractLBHttpConnectionFactory<ResolvedAddress>
        implements ConnectionFactory<ResolvedAddress, LoadBalancedStreamingHttpConnection> {

    @Nullable
    final StreamingHttpConnectionFilterFactory connectionFilterFunction;
    final ReadOnlyHttpClientConfig config;
    final HttpExecutionContext executionContext;
    final Function<HttpProtocolVersion, StreamingHttpRequestResponseFactory> reqRespFactoryFunc;
    /**
     * Computed execution strategy of the connection factory.
     */
    final ExecutionStrategy connectStrategy;
    final ConnectionFactory<ResolvedAddress, FilterableStreamingHttpConnection> filterableConnectionFactory;
    private final Function<FilterableStreamingHttpConnection,
            FilterableStreamingHttpLoadBalancedConnection> protocolBinding;

    AbstractLBHttpConnectionFactory(
            final ReadOnlyHttpClientConfig config, final HttpExecutionContext executionContext,
            final Function<HttpProtocolVersion, StreamingHttpRequestResponseFactory> reqRespFactoryFunc,
            final ExecutionStrategy connectStrategy,
            final ConnectionFactoryFilter<ResolvedAddress, FilterableStreamingHttpConnection> connectionFactoryFilter,
            @Nullable final StreamingHttpConnectionFilterFactory connectionFilterFunction,
            final Function<FilterableStreamingHttpConnection, FilterableStreamingHttpLoadBalancedConnection>
                    protocolBinding) {
        this.connectionFilterFunction = connectionFilterFunction;
        this.config = requireNonNull(config);
        this.executionContext = requireNonNull(executionContext);
        this.reqRespFactoryFunc = requireNonNull(reqRespFactoryFunc);
        this.connectStrategy = connectStrategy;
        filterableConnectionFactory = connectionFactoryFilter.create(
                // provide the supplier of connections.
                new ConnectionFactory<ResolvedAddress, FilterableStreamingHttpConnection>() {
                    private final ListenableAsyncCloseable close = emptyAsyncCloseable();

                    @Override
                    public Single<FilterableStreamingHttpConnection> newConnection(
                            final ResolvedAddress ra, @Nullable final ContextMap context,
                            @Nullable final TransportObserver observer) {
                        Single<FilterableStreamingHttpConnection> connection =
                                newFilterableConnection(ra, observer == null ? NoopTransportObserver.INSTANCE :
                                asSafeObserver(observer));
                        return connectStrategy instanceof ConnectExecutionStrategy &&
                                ((ConnectExecutionStrategy) connectStrategy).isConnectOffloaded() ?
                                connection.publishOn(executionContext.executor(),
                                        IoThreadFactory.IoThread::currentThreadIsIoThread) :
                                connection;
                    }

                    @Override
                    public Completable onClose() {
                        return close.onClose();
                    }

                    @Override
                    public Completable closeAsync() {
                        return close.closeAsync();
                    }

                    @Override
                    public Completable closeAsyncGracefully() {
                        return close.closeAsyncGracefully();
                    }
                });
        this.protocolBinding = protocolBinding;
    }

    @Override
    public final Single<LoadBalancedStreamingHttpConnection> newConnection(
            final ResolvedAddress resolvedAddress, @Nullable final ContextMap context,
            @Nullable final TransportObserver observer) {
        return filterableConnectionFactory.newConnection(resolvedAddress, context, observer)
                .map(conn -> {
                    FilterableStreamingHttpConnection filteredConnection =
                            connectionFilterFunction != null ? connectionFilterFunction.create(conn) : conn;
                    ConnectionContext ctx = filteredConnection.connectionContext();
                    Completable onClosing;
                    if (ctx instanceof NettyConnectionContext) {
                        // bondolo - I don't believe this is necessary, always the same as filteredConnection.onClose()
                        // perhaps this is only needed for testing where the mock doesn't implement onClose()?
                        onClosing = ((NettyConnectionContext) ctx).onClosing();
                    } else {
                        onClosing = filteredConnection.onClose();
                    }
                    final Publisher<? extends ConsumableEvent<Integer>> maxConcurrency =
                            filteredConnection.transportEventStream(MAX_CONCURRENCY);
                    final ReservableRequestConcurrencyController concurrencyController =
                            newConcurrencyController(maxConcurrency, onClosing);
                    return new LoadBalancedStreamingHttpConnection(protocolBinding.apply(filteredConnection),
                            concurrencyController, connectStrategy instanceof HttpExecutionStrategy ?
                                    (HttpExecutionStrategy) connectStrategy : HttpExecutionStrategies.offloadNone());
                });
    }

    /**
     * The ultimate source of connections before filtering.
     *
     * @param resolvedAddress address of the connection
     * @param observer Observer on the connection state.
     * @return the connection instance.
     */
    abstract Single<FilterableStreamingHttpConnection> newFilterableConnection(
            ResolvedAddress resolvedAddress, TransportObserver observer);

    abstract ReservableRequestConcurrencyController newConcurrencyController(
            Publisher<? extends ConsumableEvent<Integer>> maxConcurrency, Completable onClosing);

    @Override
    public final Completable onClose() {
        return filterableConnectionFactory.onClose();
    }

    @Override
    public final Completable closeAsync() {
        return filterableConnectionFactory.closeAsync();
    }

    @Override
    public final Completable closeAsyncGracefully() {
        return filterableConnectionFactory.closeAsyncGracefully();
    }
}
