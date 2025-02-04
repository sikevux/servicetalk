/*
 * Copyright © 2019 Apple Inc. and the ServiceTalk project authors
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
package io.servicetalk.http.api;

import io.servicetalk.concurrent.BlockingIterable;

import static io.servicetalk.concurrent.api.internal.BlockingUtils.blockingInvocation;
import static io.servicetalk.http.api.HttpContextKeys.HTTP_EXECUTION_STRATEGY_KEY;
import static io.servicetalk.http.api.HttpExecutionStrategies.defaultStrategy;
import static io.servicetalk.http.api.RequestResponseFactories.toAggregated;
import static io.servicetalk.http.api.StreamingHttpConnectionToBlockingHttpConnection.DEFAULT_BLOCKING_CONNECTION_STRATEGY;
import static java.util.Objects.requireNonNull;

final class StreamingHttpClientToBlockingHttpClient implements BlockingHttpClient {
    private final StreamingHttpClient client;
    private final HttpExecutionStrategy strategy;
    private final HttpExecutionContext context;
    private final HttpRequestResponseFactory reqRespFactory;

    StreamingHttpClientToBlockingHttpClient(final StreamingHttpClient client, final HttpExecutionStrategy strategy) {
        this.strategy = defaultStrategy() == strategy ?
                DEFAULT_BLOCKING_CONNECTION_STRATEGY : strategy;
        this.client = client;
        context = new DelegatingHttpExecutionContext(client.executionContext()) {
            @Override
            public HttpExecutionStrategy executionStrategy() {
                return StreamingHttpClientToBlockingHttpClient.this.strategy;
            }
        };
        reqRespFactory = toAggregated(client);
    }

    @Override
    public ReservedBlockingHttpConnection reserveConnection(final HttpRequestMetaData metaData) throws Exception {
        metaData.context().putIfAbsent(HTTP_EXECUTION_STRATEGY_KEY, strategy);
        return blockingInvocation(client.reserveConnection(metaData)
                .map(c -> new ReservedStreamingHttpConnectionToReservedBlockingHttpConnection(c, this.strategy,
                        reqRespFactory)));
    }

    @Override
    public StreamingHttpClient asStreamingClient() {
        return client;
    }

    @Override
    public HttpResponse request(final HttpRequest request) throws Exception {
        request.context().putIfAbsent(HTTP_EXECUTION_STRATEGY_KEY, strategy);
        return BlockingRequestUtils.request(client, request);
    }

    @Override
    public HttpExecutionContext executionContext() {
        return context;
    }

    @Override
    public HttpResponseFactory httpResponseFactory() {
        return reqRespFactory;
    }

    @Override
    public void close() throws Exception {
        client.close();
    }

    @Override
    public void closeGracefully() throws Exception {
        client.closeGracefully();
    }

    @Override
    public HttpRequest newRequest(final HttpRequestMethod method, final String requestTarget) {
        return reqRespFactory.newRequest(method, requestTarget);
    }

    static final class ReservedStreamingHttpConnectionToReservedBlockingHttpConnection implements
                                                                                       ReservedBlockingHttpConnection {
        private final ReservedStreamingHttpConnection connection;
        private final HttpExecutionStrategy strategy;
        private final HttpConnectionContext context;
        private final HttpExecutionContext executionContext;
        private final HttpRequestResponseFactory reqRespFactory;

        ReservedStreamingHttpConnectionToReservedBlockingHttpConnection(
                final ReservedStreamingHttpConnection connection, final HttpExecutionStrategy strategy) {
            this(connection, defaultStrategy() == strategy ? DEFAULT_BLOCKING_CONNECTION_STRATEGY : strategy,
                    toAggregated(connection));
        }

        ReservedStreamingHttpConnectionToReservedBlockingHttpConnection(
                final ReservedStreamingHttpConnection connection,
                final HttpExecutionStrategy strategy,
                final HttpRequestResponseFactory reqRespFactory) {

            this.strategy = strategy;
            this.connection = requireNonNull(connection);
            final HttpConnectionContext originalCtx = connection.connectionContext();
            executionContext = new DelegatingHttpExecutionContext(connection.executionContext()) {
                @Override
                public HttpExecutionStrategy executionStrategy() {
                    return strategy;
                }
            };
            context = new DelegatingHttpConnectionContext(originalCtx) {
                @Override
                public HttpExecutionContext executionContext() {
                    return executionContext;
                }
            };
            this.reqRespFactory = requireNonNull(reqRespFactory);
        }

        @Override
        public void release() throws Exception {
            blockingInvocation(connection.releaseAsync());
        }

        @Override
        public ReservedStreamingHttpConnection asStreamingConnection() {
            return connection;
        }

        @Override
        public HttpConnectionContext connectionContext() {
            return context;
        }

        @Override
        public <T> BlockingIterable<? extends T> transportEventIterable(final HttpEventKey<T> eventKey) {
            return connection.transportEventStream(eventKey).toIterable();
        }

        @Override
        public HttpResponse request(final HttpRequest request) throws Exception {
            return BlockingRequestUtils.request(connection, request);
        }

        @Override
        public HttpExecutionContext executionContext() {
            return executionContext;
        }

        @Override
        public HttpResponseFactory httpResponseFactory() {
            return reqRespFactory;
        }

        @Override
        public void close() throws Exception {
            connection.close();
        }

        @Override
        public void closeGracefully() throws Exception {
            connection.closeGracefully();
        }

        @Override
        public HttpRequest newRequest(final HttpRequestMethod method, final String requestTarget) {
            return reqRespFactory.newRequest(method, requestTarget);
        }
    }
}
