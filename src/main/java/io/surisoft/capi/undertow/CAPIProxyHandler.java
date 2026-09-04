package io.surisoft.capi.undertow;

import io.surisoft.capi.exception.HttpErrorHandler;
import io.surisoft.capi.utils.Constants;
import io.undertow.UndertowLogger;
import io.undertow.UndertowMessages;
import io.undertow.attribute.ExchangeAttribute;
import io.undertow.client.*;
import io.undertow.client.http2.Http2ClientConnection;
import io.undertow.connector.ByteBufferPool;
import io.undertow.io.IoCallback;
import io.undertow.io.Sender;
import io.undertow.predicate.IdempotentPredicate;
import io.undertow.predicate.Predicate;
import io.undertow.server.*;
import io.undertow.server.handlers.ResponseCodeHandler;
import io.undertow.server.handlers.proxy.LoadBalancingProxyClient;
import io.undertow.server.handlers.proxy.ProxyCallback;
import io.undertow.server.handlers.proxy.ProxyClient;
import io.undertow.server.handlers.proxy.ProxyConnection;
import io.undertow.server.protocol.http.HttpAttachments;
import io.undertow.server.protocol.http.HttpContinue;
import io.undertow.util.*;
import org.jboss.logging.Logger;
import org.xnio.*;
import org.xnio.channels.StreamSinkChannel;

import javax.net.ssl.SSLPeerUnverifiedException;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.channels.Channel;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static io.undertow.client.http2.Http2ClearClientProvider.createSettingsFrame;

public final class CAPIProxyHandler implements HttpHandler {

    private static final Logger log = Logger.getLogger(CAPIProxyHandler.class);
    private static final int DEFAULT_MAX_RETRY_ATTEMPTS = Integer.getInteger("io.undertow.server.handlers.proxy.maxRetries", 1);
    private static final AttachmentKey<ProxyConnection> CONNECTION = AttachmentKey.create(ProxyConnection.class);
    private static final AttachmentKey<HttpServerExchange> EXCHANGE = AttachmentKey.create(HttpServerExchange.class);
    private static final AttachmentKey<XnioExecutor.Key> TIMEOUT_KEY = AttachmentKey.create(XnioExecutor.Key.class);
    public static final AttachmentKey<String> REVERSE_PROXY_HOST = AttachmentKey.create(String.class);
    public static final AttachmentKey<String> REVERSE_PROXY_PREFIX = AttachmentKey.create(String.class);
    public static final AttachmentKey<String> REVERSE_PROXY_PROTO = AttachmentKey.create(String.class);

    /**
     * Headers filtered before proxying to the backend, similar to Camel's DefaultHeaderFilterStrategy.
     * Hop-by-hop headers (RFC 7230 §6.1) and X-Forwarded-* headers that CAPIProxyHandler sets explicitly.
     */
    private static final Set<HttpString> FILTERED_HEADERS = Set.of(
            // Hop-by-hop (RFC 7230 §6.1) — Connection and Upgrade are NOT filtered
            // because they are required for WebSocket handshake proxying.
            Headers.KEEP_ALIVE,
            Headers.PROXY_AUTHENTICATE,
            Headers.PROXY_AUTHORIZATION,
            Headers.TE,
            Headers.TRAILER,
            Headers.TRANSFER_ENCODING,
            // X-Forwarded-* — set explicitly by ProxyAction below
            Headers.X_FORWARDED_FOR,
            Headers.X_FORWARDED_PROTO,
            Headers.X_FORWARDED_HOST,
            Headers.X_FORWARDED_PORT,
            Headers.X_FORWARDED_SERVER,
            HttpString.tryFromString(Constants.X_FORWARDED_PREFIX),
            // Expect is handled separately by HttpContinue
            Headers.EXPECT
    );

    /**
     * CAPI-internal headers stripped from backend responses to prevent leaking to clients.
     * Separate from FILTERED_HEADERS because these must pass through on the request path
     * (e.g. Authorization must reach the backend).
     */
    private static final Set<HttpString> FILTERED_RESPONSE_HEADERS = Set.of(
            Headers.AUTHORIZATION,
            HttpString.tryFromString(Constants.CAPI_GROUP_HEADER),
            HttpString.tryFromString(Constants.CAPI_SHOULD_THROTTLE),
            HttpString.tryFromString(Constants.CAPI_THROTTLE_DURATION_MILLI),
            HttpString.tryFromString(Constants.CAPI_META_THROTTLE_DURATION),
            HttpString.tryFromString(Constants.CAPI_META_THROTTLE_TOTAL_CALLS_ALLOWED),
            HttpString.tryFromString(Constants.CAPI_META_THROTTLE_CURRENT_CALL_NUMBER),
            HttpString.tryFromString(Constants.CAPI_META_THROTTLE_CONSUMER_KEY)
    );

    private final CAPILoadBalancerProxyClient proxyClient;
    private final int maxRequestTime;

    /**
     * Map of additional headers to add to the request.
     */
    private final Map<HttpString, ExchangeAttribute> requestHeaders = new CopyOnWriteMap<>();
    private final HttpHandler next;
    private final int maxConnectionRetries;
    private final int connectTimeout;
    private final Predicate idempotentRequestPredicate;
    private final HttpErrorHandler errorHandler;

    public CAPIProxyHandler(Builder builder, HttpErrorHandler httpErrorHandler) {
        this.proxyClient = builder.proxyClient;
        this.maxRequestTime = builder.maxRequestTime;
        this.next = builder.next;
        this.maxConnectionRetries = builder.maxConnectionRetries;
        this.connectTimeout = builder.connectTimeout;
        this.idempotentRequestPredicate = builder.idempotentRequestPredicate;
        requestHeaders.putAll(builder.requestHeaders);
        this.errorHandler = httpErrorHandler;
    }

    public void handleRequest(final HttpServerExchange exchange) throws Exception {
        final LoadBalancingProxyClient.ProxyTarget target = proxyClient.findTarget(exchange);

        if (target == null) {
            log.debugf("No proxy target for request to %s", exchange.getRequestURL());
            next.handleRequest(exchange);
            return;
        }
        if(exchange.isResponseStarted()) {
            //we can't proxy a request that has already started, this is basically a server configuration error
            UndertowLogger.REQUEST_LOGGER.cannotProxyStartedRequest(exchange);
            sendProxyError(exchange, StatusCodes.INTERNAL_SERVER_ERROR, "Proxy configuration error");
            return;
        }
        final long timeout = maxRequestTime > 0 ? System.currentTimeMillis() + maxRequestTime : 0;
        int maxRetries = maxConnectionRetries;
        if(target instanceof ProxyClient.MaxRetriesProxyTarget) {
            maxRetries = Math.max(maxRetries, ((ProxyClient.MaxRetriesProxyTarget) target).getMaxRetries());
        }

        final ProxyClientHandler clientHandler = new ProxyClientHandler(exchange, target, timeout, maxRetries, idempotentRequestPredicate);
        if (timeout > 0) {
            final XnioExecutor.Key key = WorkerUtils.executeAfter(exchange.getIoThread(), () -> clientHandler.cancel(exchange), maxRequestTime, TimeUnit.MILLISECONDS);
            exchange.putAttachment(TIMEOUT_KEY, key);
            exchange.addExchangeCompleteListener((exchange1, nextListener) -> {
                key.remove();
                nextListener.proceed();
            });
        }
        exchange.dispatch(exchange.isInIoThread() ? SameThreadExecutor.INSTANCE : exchange.getIoThread(), clientHandler);
    }

    static void copyHeaders(final HttpServerExchange exchange, final HeaderMap to, final HeaderMap from) {
        long f = from.fastIterateNonEmpty();
        HeaderValues values;
        while (f != -1L) {
            values = from.fiCurrent(f);
            HttpString headerName = values.getHeaderName();
            if(!to.contains(headerName) && !FILTERED_HEADERS.contains(headerName)) {
                if (headerName.toString().equals("HTTP2-Settings")) {
                    final OptionMap options = exchange.getConnection().getUndertowOptions();
                    final ByteBufferPool bufferPool = exchange.getConnection().getByteBufferPool();
                    to.put(new HttpString("HTTP2-Settings"), createSettingsFrame(options, bufferPool));
                } else {
                    //don't over write existing headers, normally the map will be empty, if it is not we assume it is not for a reason
                    to.putAll(headerName, values);
                }
            }
            f = from.fiNextNonEmpty(f);
        }
    }

    public ProxyClient getProxyClient() {
        return proxyClient;
    }

    @Override
    public String toString() {
        List<ProxyClient.ProxyTarget> proxyTargets = proxyClient.getAllTargets();
        if (proxyTargets.isEmpty()){
            return "ProxyHandler - "+proxyClient.getClass().getSimpleName();
        }
        if(proxyTargets.size()==1){
            return "reverse-proxy( '" + proxyTargets.get(0).toString() + "' )";
        } else {
            String outputResult = "reverse-proxy( { '" + proxyTargets.stream().map(Object::toString).collect(Collectors.joining("', '")) + "' }";
            return outputResult+" )";
        }
    }

    private final class ProxyClientHandler implements ProxyCallback<ProxyConnection>, Runnable {

        private int tries;

        private final long timeout;
        private final int maxRetryAttempts;
        private final HttpServerExchange exchange;
        private final Predicate idempotentPredicate;
        private ProxyClient.ProxyTarget target;
        private String selectedHost;


        ProxyClientHandler(HttpServerExchange exchange, ProxyClient.ProxyTarget target, long timeout, int maxRetryAttempts, Predicate idempotentPredicate) {
            this.exchange = exchange;
            this.timeout = timeout;
            this.maxRetryAttempts = maxRetryAttempts;
            this.target = target;
            this.idempotentPredicate = idempotentPredicate;
        }

        @Override
        public void run() {
            beginConnect(-1);
            selectedHost = CAPILoadBalancerProxyClient.getSelectedHost(exchange);
        }

        /**
         * Start one attempt to acquire a backend connection, guarded by a watchdog.
         *
         * <p>Undertow bounds nothing here. {@code ProxyConnectionPool.connect} applies its timeout
         * argument only to requests it <em>queues</em> when the pool is full; when it opens a new
         * connection it calls {@code client.connect} with no timer, and XNIO's connect is purely
         * selector-driven. A backend that refuses the port answers with an RST, so {@link #failed}
         * fires at once and failover already works — but one that is merely unreachable
         * (powered-off node, dropped SYN, firewall/security-group black hole) answers with silence.
         * Nothing ever fails, so the retry below is never reached and the exchange stalls until the
         * maxRequestTime watchdog returns 504, with a healthy sibling host sitting idle.
         *
         * <p>The watchdog turns that silence into an ordinary connection failure, which the retry
         * path already knows how to handle.
         */
        private void beginConnect(long poolTimeoutMs) {
            ConnectAttempt attempt = new ConnectAttempt(this, exchange);
            attempt.arm(connectTimeout);
            try {
                proxyClient.getConnection(target, exchange, attempt, poolTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (RuntimeException e) {
                // getConnection threw rather than calling back: settle the attempt so the watchdog
                // cannot fire later against an attempt that is already over.
                if (attempt.settle()) {
                    exchange.putAttachment(CAPILoadBalancerProxyClient.CONNECTION_ERROR_KEY, e);
                    failed(exchange);
                }
            }
        }

        @Override
        public void completed(final HttpServerExchange exchange, final ProxyConnection connection) {
            exchange.putAttachment(CONNECTION, connection);
            exchange.dispatch(SameThreadExecutor.INSTANCE, new ProxyAction(connection, exchange, requestHeaders, exchange.isRequestComplete() ? this : null, idempotentPredicate, selectedHost));
        }

        @Override
        public void failed(final HttpServerExchange exchange) {
            final long time = System.currentTimeMillis();
            if (tries++ < maxRetryAttempts) {
                if (timeout > 0 && time > timeout) {
                    cancel(exchange);
                } else {
                    target = proxyClient.findTarget(exchange);
                    if (target != null) {
                        final long remaining = timeout > 0 ? timeout - time : -1;
                        beginConnect(remaining);
                    } else {
                        couldNotResolveBackend(exchange); // The context was registered when we started, so return 503
                    }
                }
            } else {
                couldNotResolveBackend(exchange);
            }
        }

        @Override
        public void queuedRequestFailed(HttpServerExchange exchange) {
            failed(exchange);
        }

        @Override
        public void couldNotResolveBackend(HttpServerExchange exchange) {
            if (exchange.isResponseStarted()) {
                IoUtils.safeClose(exchange.getConnection());
            } else {
                Throwable connectionError = exchange.getAttachment(CAPILoadBalancerProxyClient.CONNECTION_ERROR_KEY);
                String message;
                if (connectionError != null && isSslException(connectionError)) {
                    message = Constants.ERROR_SERVICE_CERTIFICATE;
                } else {
                    message = Constants.ERROR_NO_SERVER_AVAILABLE;
                }
                CAPIProxyHandler.this.sendProxyError(exchange, StatusCodes.SERVICE_UNAVAILABLE, message);
            }
        }

        void cancel(final HttpServerExchange exchange) {
            //NOTE: this method is called only in context of timeouts.
            final ProxyConnection connectionAttachment = exchange.getAttachment(CONNECTION);
            if (connectionAttachment != null) {
                ClientConnection clientConnection = connectionAttachment.getConnection();
                UndertowLogger.PROXY_REQUEST_LOGGER.timingOutRequest(clientConnection.getPeerAddress() + "" + exchange.getRequestURI());
                IoUtils.safeClose(clientConnection);
            } else {
                UndertowLogger.PROXY_REQUEST_LOGGER.timingOutRequest(exchange.getRequestURI());
            }
            if (exchange.isResponseStarted()) {
                IoUtils.safeClose(exchange.getConnection());
            } else {
                CAPIProxyHandler.this.sendProxyError(exchange, StatusCodes.GATEWAY_TIME_OUT, Constants.ERROR_REMOTE_SERVER_TIMEOUT);
            }
        }

    }

    private record ProxyAction(ProxyConnection clientConnection, HttpServerExchange exchange,
                               Map<HttpString, ExchangeAttribute> requestHeaders, ProxyClientHandler proxyClientHandler,
                               Predicate idempotentPredicate, String selectedHostname) implements Runnable {

        @Override
            public void run() {
                final ClientRequest request = new ClientRequest();

                if(selectedHostname != null) {
                    request.getRequestHeaders().put(Headers.HOST, selectedHostname);
                } else {
                    String selectedHost = exchange.getRequestHeaders().getFirst("CapiSelectedHost");
                    request.getRequestHeaders().put(Headers.HOST, selectedHost);
                }
                exchange.getRequestHeaders().remove("CapiSelectedHost");

                String targetURI = exchange.getRequestURI();
                if (exchange.isHostIncludedInRequestURI()) {
                    int uriPart = targetURI.indexOf("//");
                    if (uriPart != -1) {
                        uriPart = targetURI.indexOf("/", uriPart + 2);
                        if (uriPart != -1) {
                            targetURI = targetURI.substring(uriPart);
                        }
                    }
                }

                if (!exchange.getResolvedPath().isEmpty() && targetURI.startsWith(exchange.getResolvedPath())) {
                    targetURI = targetURI.substring(exchange.getResolvedPath().length());
                }

                StringBuilder requestURI = new StringBuilder();
                if (!clientConnection.getTargetPath().isEmpty()
                        && (!clientConnection.getTargetPath().equals("/") || targetURI.isEmpty())) {
                    requestURI.append(clientConnection.getTargetPath());
                }
                requestURI.append(targetURI);

                String qs = exchange.getQueryString();
                if (qs != null && !qs.isEmpty()) {
                    requestURI.append('?');
                    requestURI.append(qs);
                }
                request.setPath(requestURI.toString())
                        .setMethod(exchange.getRequestMethod());
                final HeaderMap inboundRequestHeaders = exchange.getRequestHeaders();
                final HeaderMap outboundRequestHeaders = request.getRequestHeaders();
                copyHeaders(exchange, outboundRequestHeaders, inboundRequestHeaders);

                if (!exchange.isPersistent()) {
                    //just because the client side is non-persistent
                    //we don't want to close the connection to the backend
                    outboundRequestHeaders.put(Headers.CONNECTION, "keep-alive");
                }
                if ("h2c".equals(exchange.getRequestHeaders().getFirst(Headers.UPGRADE))) {
                    //we don't allow h2c upgrade requests to be passed through to the backend
                    exchange.getRequestHeaders().remove(Headers.UPGRADE);
                    outboundRequestHeaders.put(Headers.CONNECTION, "keep-alive");
                }

                for (Map.Entry<HttpString, ExchangeAttribute> entry : requestHeaders.entrySet()) {
                    String headerValue = entry.getValue().readAttribute(exchange);
                    if (headerValue == null || headerValue.isEmpty()) {
                        outboundRequestHeaders.remove(entry.getKey());
                    } else {
                        outboundRequestHeaders.put(entry.getKey(), headerValue.replace('\n', ' '));
                    }
                }
                final String remoteHost;
                final InetSocketAddress address = exchange.getSourceAddress();
                if (address != null) {
                    remoteHost = address.getHostString();
                    if (!address.isUnresolved()) {
                        request.putAttachment(ProxiedRequestAttachments.REMOTE_ADDRESS, address.getAddress().getHostAddress());
                    }
                } else {
                    //should never happen, unless this is some form of mock request
                    remoteHost = "localhost";
                }

                request.putAttachment(ProxiedRequestAttachments.REMOTE_HOST, remoteHost);

                // X-Forwarded-For: append client IP to chain
                String existing = request.getRequestHeaders().getFirst(Headers.X_FORWARDED_FOR);
                if (existing != null && !existing.isEmpty()) {
                    request.getRequestHeaders().put(Headers.X_FORWARDED_FOR, existing + "," + remoteHost);
                } else {
                    request.getRequestHeaders().put(Headers.X_FORWARDED_FOR, remoteHost);
                }

                //if we don't support push set a header saying so
                //this is non standard, and a problem with the HTTP2 spec, but they did not want to listen
                if (!exchange.getConnection().isPushSupported() && clientConnection.getConnection().isPushSupported()) {
                    request.getRequestHeaders().put(Headers.X_DISABLE_PUSH, "true");
                }

                // X-Forwarded-Proto: prefer publicEndpoint scheme attachment when CAPI is fronted
                // by a TLS-terminating reverse proxy (otherwise exchange.getRequestScheme() reflects
                // the plain-HTTP hop from the edge to CAPI and backends would build http:// redirects).
                final String rpProto = exchange.getAttachment(REVERSE_PROXY_PROTO);
                final String proto = rpProto != null
                        ? rpProto
                        : (exchange.getRequestScheme().equals("https") ? "https" : "http");
                request.getRequestHeaders().put(Headers.X_FORWARDED_PROTO, proto);
                request.putAttachment(ProxiedRequestAttachments.IS_SSL, proto.equals("https"));

                // X-Forwarded-Host and X-Forwarded-Prefix (reverse proxy attachments take priority)
                String rpHost = exchange.getAttachment(REVERSE_PROXY_HOST);
                if (rpHost != null) {
                    request.getRequestHeaders().put(Headers.X_FORWARDED_HOST, rpHost);
                } else {
                    final String hostName = exchange.getHostName();
                    if (hostName != null) {
                        request.getRequestHeaders().put(Headers.X_FORWARDED_HOST, NetworkUtils.formatPossibleIpv6Address(hostName));
                    }
                }
                String rpPrefix = exchange.getAttachment(REVERSE_PROXY_PREFIX);
                if (rpPrefix != null) {
                    request.getRequestHeaders().put(HttpString.tryFromString(Constants.X_FORWARDED_PREFIX), rpPrefix);
                }

                // Internal attachments (no headers sent to backend)
                request.putAttachment(ProxiedRequestAttachments.SERVER_NAME, exchange.getHostName());
                request.putAttachment(ProxiedRequestAttachments.SERVER_PORT, exchange.getHostPort());

                SSLSessionInfo sslSessionInfo = exchange.getConnection().getSslSessionInfo();
                if (sslSessionInfo != null) {
                    Certificate[] peerCertificates;
                    try {
                        peerCertificates = sslSessionInfo.getPeerCertificates();
                        if (peerCertificates.length > 0) {
                            request.putAttachment(ProxiedRequestAttachments.SSL_CERT, Certificates.toPem(peerCertificates[0]));
                        }
                    } catch (SSLPeerUnverifiedException | CertificateEncodingException | RenegotiationRequiredException e) {
                        //ignore
                    }
                    request.putAttachment(ProxiedRequestAttachments.SSL_CYPHER, sslSessionInfo.getCipherSuite());
                    request.putAttachment(ProxiedRequestAttachments.SSL_SESSION_ID, sslSessionInfo.getSessionId());
                    request.putAttachment(ProxiedRequestAttachments.SSL_KEY_SIZE, sslSessionInfo.getKeySize());
                }

                if (log.isDebugEnabled()) {
                    request.getRequestHeaders().getHeaderNames().forEach(k -> {
                        log.debug("Request Header: " + k + " = " + exchange.getRequestHeaders().getFirst(k));
                    });
                    exchange.getRequestHeaders().getHeaderNames().forEach(k -> {
                        log.debug("EXCHANGE Header: " + k + " = " + exchange.getRequestHeaders().getFirst(k));
                    });
                    log.debugf("Sending request %s to target %s for exchange %s", request, clientConnection.getConnection().getPeerAddress(), exchange);
                }
                //handle content
                //if the frontend is HTTP/2 then we may need to add a Transfer-Encoding header, to indicate to the backend
                //that there is content
                if (!request.getRequestHeaders().contains(Headers.TRANSFER_ENCODING) && !request.getRequestHeaders().contains(Headers.CONTENT_LENGTH)) {
                    if (!exchange.isRequestComplete()) {
                        request.getRequestHeaders().put(Headers.TRANSFER_ENCODING, Headers.CHUNKED.toString());
                    }
                }

                //https://www.rfc-editor.org/rfc/rfc9113#name-compressing-the-cookie-head
                if (!Cookies.isCrumbsAssemplyDisabled() && !(clientConnection.getConnection() instanceof Http2ClientConnection)) {
                    Cookies.assembleCrumbs(outboundRequestHeaders);
                }
                clientConnection.getConnection().sendRequest(request, new ClientCallback<>() {
                    @Override
                    public void completed(final ClientExchange result) {

                        if (log.isDebugEnabled()) {
                            log.debugf("Sent request %s to target %s for exchange %s", request, remoteHost, exchange);
                        }
                        result.putAttachment(EXCHANGE, exchange);

                        boolean requiresContinueResponse = HttpContinue.requiresContinueResponse(exchange);
                        if (requiresContinueResponse) {
                            result.setContinueHandler(new ContinueNotification() {
                                @Override
                                public void handleContinue(final ClientExchange clientExchange) {
                                    if (log.isDebugEnabled()) {
                                        log.debugf("Received continue response to request %s to target %s for exchange %s", request, clientConnection.getConnection().getPeerAddress(), exchange);
                                    }
                                    HttpContinue.sendContinueResponse(exchange, new IoCallback() {
                                        @Override
                                        public void onComplete(final HttpServerExchange exchange, final Sender sender) {
                                            //don't care
                                            clientExchange.getResponse().getResponseHeaders().getHeaderNames().forEach(k -> {
                                                log.debugf("Response Header: %s = %s", k, exchange.getResponseHeaders().getFirst(k));
                                            });
                                        }

                                        @Override
                                        public void onException(final HttpServerExchange exchange, final Sender sender, final IOException exception) {
                                            IoUtils.safeClose(clientConnection.getConnection());
                                            exchange.endExchange();
                                            UndertowLogger.REQUEST_IO_LOGGER.ioException(exception);
                                        }
                                    });
                                }
                            });
                        }

                        //handle server push
                        if (exchange.getConnection().isPushSupported() && result.getConnection().isPushSupported()) {
                            result.setPushHandler(new PushCallback() {
                                @Override
                                public boolean handlePush(ClientExchange originalRequest, final ClientExchange pushedRequest) {

                                    if (log.isDebugEnabled()) {
                                        log.debugf("Sending push request %s received from %s to target %s for exchange %s", pushedRequest.getRequest(), request, remoteHost, exchange);
                                    }
                                    final ClientRequest request = pushedRequest.getRequest();
                                    exchange.getConnection().pushResource(request.getPath(), request.getMethod(), request.getRequestHeaders(), new HttpHandler() {
                                        @Override
                                        public void handleRequest(final HttpServerExchange exchange) throws Exception {
                                            String path = request.getPath();
                                            int i = path.indexOf("?");
                                            if (i > 0) {
                                                path = path.substring(0, i);
                                            }

                                            exchange.dispatch(SameThreadExecutor.INSTANCE, new ProxyAction(new ProxyConnection(pushedRequest.getConnection(), path), exchange, requestHeaders, null, idempotentPredicate, selectedHostname));
                                        }
                                    });
                                    return true;
                                }
                            });
                        }


                        result.setResponseListener(new ResponseCallback(exchange, proxyClientHandler, idempotentPredicate));
                        final IoExceptionHandler handler = new IoExceptionHandler(exchange, clientConnection.getConnection());
                        if (requiresContinueResponse) {
                            try {
                                if (!result.getRequestChannel().flush()) {
                                    result.getRequestChannel().getWriteSetter().set(ChannelListeners.flushingChannelListener(new ChannelListener<StreamSinkChannel>() {
                                        @Override
                                        public void handleEvent(StreamSinkChannel channel) {
                                            Transfer.initiateTransfer(exchange.getRequestChannel(), result.getRequestChannel(), ChannelListeners.closingChannelListener(), new HTTPTrailerChannelListener(exchange, result, exchange, proxyClientHandler, idempotentPredicate), handler, handler, exchange.getConnection().getByteBufferPool());

                                        }
                                    }, handler));
                                    result.getRequestChannel().resumeWrites();
                                    return;
                                }
                            } catch (IOException e) {
                                handler.handleException(result.getRequestChannel(), e);
                            }
                        }
                        HTTPTrailerChannelListener trailerListener = new HTTPTrailerChannelListener(exchange, result, exchange, proxyClientHandler, idempotentPredicate);
                        if (!exchange.isRequestComplete()) {
                            Transfer.initiateTransfer(exchange.getRequestChannel(), result.getRequestChannel(), ChannelListeners.closingChannelListener(), trailerListener, handler, handler, exchange.getConnection().getByteBufferPool());
                        } else {
                            trailerListener.handleEvent(result.getRequestChannel());
                        }

                    }

                    @Override
                    public void failed(IOException e) {
                        handleFailure(exchange, proxyClientHandler, idempotentPredicate, e);
                    }
                });


            }
        }

    static void handleFailure(HttpServerExchange exchange, ProxyClientHandler proxyClientHandler, Predicate idempotentRequestPredicate, IOException e) {
        // Store the actual exception so error handler can determine the cause (SSL, timeout, etc.)
        exchange.putAttachment(CAPILoadBalancerProxyClient.CONNECTION_ERROR_KEY, e);
        UndertowLogger.PROXY_REQUEST_LOGGER.proxyRequestFailed(exchange.getRequestURI(), e);
        if(exchange.isResponseStarted()) {
            IoUtils.safeClose(exchange.getConnection());
        } else if(idempotentRequestPredicate.resolve(exchange) && proxyClientHandler != null) {
            proxyClientHandler.failed(exchange);
        } else {
            exchange.setStatusCode(StatusCodes.SERVICE_UNAVAILABLE);
            exchange.endExchange();
        }
    }

    /**
     * One attempt to acquire a backend connection, with a deadline.
     *
     * <p>Wraps the {@link ProxyClientHandler} for a single {@code getConnection} call so a late
     * result can be told apart from a live one. Exactly one of {@code completed}/{@code failed}/
     * {@code couldNotResolveBackend}/{@code queuedRequestFailed}/timeout wins; the rest are dropped.
     *
     * <p>Two things make that necessary. First, once the watchdog has failed the attempt over, the
     * original connect is still in flight — if it later succeeds, delegating would dispatch a second
     * {@code ProxyAction} for an exchange the retry already owns. Second, that late connection
     * belongs to nobody, so it is closed here rather than left to leak an FD.
     *
     * <p>Everything runs on the exchange's XNIO I/O thread: {@code getConnection} and its callbacks
     * are invoked there, and {@code WorkerUtils.executeAfter} schedules onto the same thread. The
     * {@code settled} flag is therefore thread-confined and needs no synchronisation, matching how
     * {@code ProxyClientHandler.tries} is already handled.
     */
    private final class ConnectAttempt implements ProxyCallback<ProxyConnection> {

        private final ProxyClientHandler delegate;
        private final HttpServerExchange exchange;
        private XnioExecutor.Key watchdogKey;
        private boolean settled;

        private ConnectAttempt(ProxyClientHandler delegate, HttpServerExchange exchange) {
            this.delegate = delegate;
            this.exchange = exchange;
        }

        /** Arms the deadline. {@code timeoutMs <= 0} leaves the attempt unbounded (previous behaviour). */
        private void arm(int timeoutMs) {
            if (timeoutMs <= 0 || settled) {
                return;
            }
            watchdogKey = WorkerUtils.executeAfter(exchange.getIoThread(),
                    () -> onTimeout(timeoutMs), timeoutMs, TimeUnit.MILLISECONDS);
        }

        /** Marks this attempt finished and cancels the deadline. False if something already won. */
        private boolean settle() {
            if (settled) {
                return false;
            }
            settled = true;
            if (watchdogKey != null) {
                watchdogKey.remove();
                watchdogKey = null;
            }
            return true;
        }

        private void onTimeout(int timeoutMs) {
            watchdogKey = null;
            if (!settle()) {
                return;
            }
            String host = CAPILoadBalancerProxyClient.getSelectedHost(exchange);
            log.debugf("Connect to backend %s did not complete within %d ms; failing over", host, timeoutMs);
            // Take it out of rotation for a while. Undertow only does this for hosts that fail a
            // connect outright; without it the silent host stays in round-robin and every request
            // pays this timeout before failing over.
            proxyClient.penaliseHost(exchange.getAttachment(CAPILoadBalancerProxyClient.SELECTED_HOST_URI_KEY));
            exchange.putAttachment(CAPILoadBalancerProxyClient.CONNECTION_ERROR_KEY,
                    new SocketTimeoutException(
                            "Timed out connecting to backend " + host + " after " + timeoutMs + " ms"));
            // Hand it to the ordinary failure path so retry/failover applies unchanged.
            delegate.failed(exchange);
        }

        @Override
        public void completed(HttpServerExchange exchange, ProxyConnection result) {
            if (!settle()) {
                // The watchdog already failed this attempt over and another host is handling the
                // exchange. Nobody will ever use this connection, so close it instead of leaking it.
                IoUtils.safeClose(result.getConnection());
                return;
            }
            delegate.completed(exchange, result);
        }

        @Override
        public void failed(HttpServerExchange exchange) {
            if (settle()) {
                delegate.failed(exchange);
            }
        }

        @Override
        public void couldNotResolveBackend(HttpServerExchange exchange) {
            if (settle()) {
                delegate.couldNotResolveBackend(exchange);
            }
        }

        @Override
        public void queuedRequestFailed(HttpServerExchange exchange) {
            if (settle()) {
                delegate.queuedRequestFailed(exchange);
            }
        }
    }

    private static final class ResponseCallback implements ClientCallback<ClientExchange> {

        private final HttpServerExchange exchange;
        private final ProxyClientHandler proxyClientHandler;
        private final Predicate idempotentPredicate;

        private ResponseCallback(HttpServerExchange exchange, ProxyClientHandler proxyClientHandler, Predicate idempotentPredicate) {
            this.exchange = exchange;
            this.proxyClientHandler = proxyClientHandler;
            this.idempotentPredicate = idempotentPredicate;
        }

        @Override
        public void completed(final ClientExchange result) {

            final ClientResponse response = result.getResponse();

            if(log.isDebugEnabled()) {
                log.debugf("Received response %s for request %s for exchange %s", response, result.getRequest(), exchange);
            }
            final HeaderMap inboundResponseHeaders = response.getResponseHeaders();
            final HeaderMap outboundResponseHeaders = exchange.getResponseHeaders();
            exchange.setStatusCode(response.getResponseCode());
            copyHeaders(exchange, outboundResponseHeaders, inboundResponseHeaders);

            // Strip reverse-proxy headers — only meant for CAPI→backend, not for the client
            outboundResponseHeaders.remove(Constants.X_FORWARDED_HOST);
            outboundResponseHeaders.remove(Constants.X_FORWARDED_PREFIX);

            // Strip CAPI-internal headers if echoed back by the backend
            for (HttpString header : FILTERED_RESPONSE_HEADERS) {
                outboundResponseHeaders.remove(header);
            }

            //https://www.rfc-editor.org/rfc/rfc9113#name-compressing-the-cookie-head
            //NOTE: this will be required if this is passed into app
            if(!Cookies.isCrumbsAssemplyDisabled() && !exchange.getProtocol().equals(Protocols.HTTP_2_0)) {
                Cookies.assembleCrumbs(outboundResponseHeaders);
            }

            if (exchange.isUpgrade()) {

                exchange.upgradeChannel(new HttpUpgradeListener() {
                    @Override
                    public void handleUpgrade(StreamConnection streamConnection, HttpServerExchange exchange) {

                        if(log.isDebugEnabled()) {
                            log.debugf("Upgraded request %s to for exchange %s", result.getRequest(), exchange);
                        }
                        StreamConnection clientChannel = null;
                        try {
                            clientChannel = result.getConnection().performUpgrade();

                            final ClosingExceptionHandler handler = new ClosingExceptionHandler(streamConnection, clientChannel);
                            Transfer.initiateTransfer(clientChannel.getSourceChannel(), streamConnection.getSinkChannel(), ChannelListeners.closingChannelListener(), ChannelListeners.writeShutdownChannelListener(ChannelListeners.<StreamSinkChannel>flushingChannelListener(ChannelListeners.closingChannelListener(), ChannelListeners.closingChannelExceptionHandler()), ChannelListeners.closingChannelExceptionHandler()), handler, handler, result.getConnection().getBufferPool());
                            Transfer.initiateTransfer(streamConnection.getSourceChannel(), clientChannel.getSinkChannel(), ChannelListeners.closingChannelListener(), ChannelListeners.writeShutdownChannelListener(ChannelListeners.<StreamSinkChannel>flushingChannelListener(ChannelListeners.closingChannelListener(), ChannelListeners.closingChannelExceptionHandler()), ChannelListeners.closingChannelExceptionHandler()), handler, handler, result.getConnection().getBufferPool());

                        } catch (IOException e) {
                            IoUtils.safeClose(streamConnection, clientChannel);
                        }
                    }
                });
            }
            final IoExceptionHandler handler = new IoExceptionHandler(exchange, result.getConnection());
            Transfer.initiateTransfer(result.getResponseChannel(), exchange.getResponseChannel(), ChannelListeners.closingChannelListener(), new HTTPTrailerChannelListener(result, exchange, exchange, proxyClientHandler, idempotentPredicate), handler, handler, exchange.getConnection().getByteBufferPool());
        }

        @Override
        public void failed(IOException e) {
            handleFailure(exchange, proxyClientHandler, idempotentPredicate, e);
        }
    }

    private static final class HTTPTrailerChannelListener implements ChannelListener<StreamSinkChannel> {

        private final Attachable source;
        private final Attachable target;
        private final HttpServerExchange exchange;
        private final ProxyClientHandler proxyClientHandler;
        private final Predicate idempotentPredicate;

        private HTTPTrailerChannelListener(final Attachable source, final Attachable target, HttpServerExchange exchange, ProxyClientHandler proxyClientHandler, Predicate idempotentPredicate) {
            this.source = source;
            this.target = target;
            this.exchange = exchange;
            this.proxyClientHandler = proxyClientHandler;
            this.idempotentPredicate = idempotentPredicate;
        }

        @Override
        public void handleEvent(final StreamSinkChannel channel) {
            HeaderMap trailers = source.getAttachment(HttpAttachments.REQUEST_TRAILERS);
            if (trailers != null) {
                target.putAttachment(HttpAttachments.RESPONSE_TRAILERS, trailers);
            }
            try {
                channel.shutdownWrites();
                if (!channel.flush()) {
                    channel.getWriteSetter().set(ChannelListeners.flushingChannelListener(new ChannelListener<StreamSinkChannel>() {
                        @Override
                        public void handleEvent(StreamSinkChannel channel) {
                            channel.suspendWrites();
                            channel.getWriteSetter().set(null);
                        }
                    }, ChannelListeners.closingChannelExceptionHandler()));
                    channel.resumeWrites();
                } else {
                    channel.getWriteSetter().set(null);
                    channel.shutdownWrites();
                }
            } catch (IOException e) {
                handleFailure(exchange, proxyClientHandler, idempotentPredicate, e);
            } catch (Exception e) {
                handleFailure(exchange, proxyClientHandler, idempotentPredicate, new IOException(e));
            }

        }
    }

    private static final class IoExceptionHandler implements ChannelExceptionHandler<Channel> {

        private final HttpServerExchange exchange;
        private final ClientConnection clientConnection;

        private IoExceptionHandler(HttpServerExchange exchange, ClientConnection clientConnection) {
            this.exchange = exchange;
            this.clientConnection = clientConnection;
        }

        @Override
        public void handleException(Channel channel, IOException exception) {
            IoUtils.safeClose(channel);
            IoUtils.safeClose(clientConnection);
            if (exchange.isResponseStarted()) {
                UndertowLogger.REQUEST_IO_LOGGER.debug("Exception reading from target server", exception);
                if (!exchange.isResponseStarted()) {
                    exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
                    exchange.endExchange();
                } else {
                    IoUtils.safeClose(exchange.getConnection());
                }
            } else {
                UndertowLogger.REQUEST_IO_LOGGER.ioException(exception);
                exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
                exchange.endExchange();
            }
        }
    }

    private record ClosingExceptionHandler(Closeable... toClose) implements ChannelExceptionHandler<Channel> {


        @Override
            public void handleException(Channel channel, IOException exception) {
                IoUtils.safeClose(channel);
                IoUtils.safeClose(toClose);
            }
        }

    private static boolean isSslException(Throwable t) {
        while (t != null) {
            if (t instanceof javax.net.ssl.SSLException || t instanceof javax.net.ssl.SSLHandshakeException) {
                return true;
            }
            // ClosedChannelException from SslConduit indicates SSL handshake failure
            for (StackTraceElement element : t.getStackTrace()) {
                if (element.getClassName().contains("SslConduit")) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }

    void sendProxyError(HttpServerExchange exchange, int statusCode, String message) {
        if (errorHandler != null && !exchange.isResponseStarted()) {
            errorHandler.sendError(exchange, statusCode, message);
        } else {
            exchange.setStatusCode(statusCode);
            exchange.endExchange();
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private CAPILoadBalancerProxyClient proxyClient;
        private int maxRequestTime = -1;
        private int connectTimeout = CAPILoadBalancerProxyClient.PoolSettings.DEFAULT_CONNECT_TIMEOUT_MS;
        private final Map<HttpString, ExchangeAttribute> requestHeaders = new CopyOnWriteMap<>();
        private HttpHandler next = ResponseCodeHandler.HANDLE_404;
        private final int maxConnectionRetries = DEFAULT_MAX_RETRY_ATTEMPTS;
        private final Predicate idempotentRequestPredicate = IdempotentPredicate.INSTANCE;
        private HttpErrorHandler httpErrorHandler;

        Builder() {}

        public Builder setProxyClient(CAPILoadBalancerProxyClient proxyClient) {
            if(proxyClient == null) {
                throw UndertowMessages.MESSAGES.argumentCannotBeNull("proxyClient");
            }
            this.proxyClient = proxyClient;
            return this;
        }

        /**
         * Bounds how long one attempt to acquire a backend connection may hang before the host is
         * abandoned and the retry path tries the next one. 0 disables the watchdog.
         */
        public Builder setConnectTimeout(int connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder setMaxRequestTime(int maxRequestTime) {
            this.maxRequestTime = maxRequestTime;
            return this;
        }

        public Builder setNext(HttpHandler next) {
            this.next = next;
            return this;
        }

        public Builder setHttpErrorHandler(HttpErrorHandler httpErrorHandler) {
            this.httpErrorHandler = httpErrorHandler;
            return this;
        }

        public CAPIProxyHandler build() {
            return new CAPIProxyHandler(this, httpErrorHandler);
        }
    }
}