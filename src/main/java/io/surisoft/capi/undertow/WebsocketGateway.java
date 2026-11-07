package io.surisoft.capi.undertow;

import io.surisoft.capi.exception.CapiUndertowException;
import io.surisoft.capi.exception.AuthorizationException;
import io.surisoft.capi.oidc.AccessGate;
import io.surisoft.capi.service.OpaWasmService;
import io.surisoft.capi.utils.HttpUtils;
import io.surisoft.capi.schema.WebsocketClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.CorsPolicy;
import io.surisoft.capi.utils.PathBlocklist;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderValues;
import jakarta.annotation.Nullable;
import io.surisoft.capi.utils.ErrorMessage;
import io.surisoft.capi.utils.WebsocketUtils;
import io.undertow.Undertow;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderValues;
import io.undertow.util.HttpString;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.util.List;
import java.util.Map;

public class WebsocketGateway {
    private static final Logger log = LoggerFactory.getLogger(WebsocketGateway.class);
    private static final Logger ACCESS_LOG = LoggerFactory.getLogger("capi.access");
    private final int port;
    private final int ioThreads;
    private final Map<String, WebsocketClient> webSocketClients;
    private HttpUtils httpUtils;
    private OpaWasmService opaWasmService;
    private final WebsocketUtils websocketUtils;
    private final SSLContext sslContext;
    //private final Optional<CapiUndertowTracer> capiUndertowTracer;
    private final List<String> accessControlAllowHeaders;
    private final Map<String, String> managedHeaders;
    private final String oauth2CookieName;
    private Undertow server;
    private boolean enforceOriginCheck;
    private CorsPolicy corsPolicy = CorsPolicy.denyAll();
    private MeterRegistry meterRegistry;
    private PathBlocklist pathBlocklist = PathBlocklist.EMPTY;

    public WebsocketGateway(int port,
                            int ioThreads,
                            Map<String, WebsocketClient> webSocketClients,
                            WebsocketUtils websocketUtils,
                            SSLContext sslContext,
                            //Optional<CapiUndertowTracer> capiUndertowTracer,
                            List<String> accessControlAllowHeaders,
                            String oauth2CookieName) {
        this.port = port;
        this.ioThreads = ioThreads;
        this.webSocketClients = webSocketClients;
        this.websocketUtils = websocketUtils;
        this.sslContext = sslContext;
        //this.capiUndertowTracer = capiUndertowTracer;
        this.accessControlAllowHeaders = accessControlAllowHeaders;
        this.oauth2CookieName = oauth2CookieName;

        managedHeaders = new java.util.HashMap<>(Constants.CAPI_CORS_MANAGED_HEADERS);
        managedHeaders.put("Access-Control-Allow-Headers", StringUtils.join(accessControlAllowHeaders, ","));

    }

    public void runProxy() {

        Undertow.Builder builder = Undertow.builder()
                .setIoThreads(ioThreads);

        if(sslContext != null) {
            builder.addHttpsListener(port, Constants.UNDERTOW_LISTENING_ADDRESS, sslContext);
        } else {
            builder.addHttpListener(port, Constants.UNDERTOW_LISTENING_ADDRESS);
        }

        builder
                .setHandler(httpServerExchange -> {
                    long startNanos = System.nanoTime();
                    try {
                        String requestPath = httpServerExchange.getRequestPath();
                        String webClientId = websocketUtils.getWebclientId(requestPath);

                        if(httpServerExchange.getRequestHeaders().contains(Constants.BLUECOAT_HEADER)) {
                            httpServerExchange.getRequestHeaders().remove(Constants.BLUECOAT_HEADER);
                        }

                        String blockedBy = requestPath.equals(Constants.CAPI_HEALTH_PATH) ? null : pathBlocklist.match(httpServerExchange);
                        if(requestPath.equals(Constants.CAPI_HEALTH_PATH)) {
                            httpServerExchange.setStatusCode(HttpServletResponse.SC_OK);
                            httpServerExchange.endExchange();
                        } else if(blockedBy != null) {
                            // Same answer as an unknown route (capi.blockedPaths).
                            PathBlocklist.count(meterRegistry, "websocket", blockedBy, webClientId != null && webSocketClients.containsKey(webClientId) ? webClientId : null);
                            log.debug("Blocked by capi.blockedPaths rule {}: {}", blockedBy, requestPath);
                            httpServerExchange.setStatusCode(Constants.NOT_FOUND_CODE);
                            httpServerExchange.endExchange();
                        } else {
                            if(webClientId != null && webSocketClients.containsKey(webClientId)) { //webSocketClients.containsKey(webClientId)
                                WebsocketClient websocketClient = webSocketClients.get(webClientId);
                                if(httpServerExchange.getRequestMethod().equals(HttpString.tryFromString(Constants.OPTIONS_METHODS_VALUE))) {
                                    websocketUtils.handleOptionsRequest(httpServerExchange, accessControlAllowHeaders, managedHeaders, oauth2CookieName, websocketClient.getCorsPolicy());
                                } else {
                                    if (!originAllowed(httpServerExchange, websocketClient)) {
                                        httpServerExchange.setStatusCode(Constants.FORBIDDEN_CODE);
                                        httpServerExchange.endExchange();
                                        return;
                                    }
                                    if (httpServerExchange.getProtocol().equals(Constants.PROTOCOL_HTTP)) {
                                        int refusal = authorize(websocketClient, httpServerExchange);
                                        if (refusal == 0) {
                                            log.debug(ErrorMessage.IS_AUTHORIZED, httpServerExchange.getRequestPath());
                                            httpServerExchange.setRequestURI(websocketUtils.normalizePathForForwarding(websocketClient, requestPath));
                                            httpServerExchange.setRelativePath(websocketUtils.normalizePathForForwarding(websocketClient, requestPath));
                                            websocketClient.getHttpHandler().handleRequest(httpServerExchange);
                                        } else {
                                            log.debug(ErrorMessage.IS_NOT_AUTHORIZED, httpServerExchange.getRequestPath());
                                            httpServerExchange.setStatusCode(refusal);
                                            httpServerExchange.endExchange();
                                        }
                                    }
                                }
                            } else {
                                log.debug(ErrorMessage.IS_NOT_PRESENT, httpServerExchange.getRequestPath());
                                httpServerExchange.setStatusCode(Constants.NOT_FOUND_CODE);
                                httpServerExchange.endExchange();
                            }
                        }
                    } finally {
                        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                        String originalIp = "unknown";
                        java.net.InetAddress addr = httpServerExchange.getSourceAddress().getAddress();
                        if(addr != null) {
                            originalIp = addr.getHostAddress();
                        }
                        if(httpServerExchange.getRequestHeaders().contains("X-Forwarded-For")) {
                            originalIp = httpServerExchange.getRequestHeaders().get("X-Forwarded-For").getFirst();
                        }
                        ACCESS_LOG.info("{} {} {} {}ms {}",
                                httpServerExchange.getRequestMethod(),
                                httpServerExchange.getRequestPath(),
                                httpServerExchange.getStatusCode(),
                                durationMs,
                                originalIp);
                    }

                });
        server = builder.build();
        server.start();
    }

    /**
     * Whether this upgrade may proceed on the strength of its {@code Origin}.
     *
     * <p>Guards cross-site WebSocket hijacking: a page the victim happens to visit can open a socket
     * to this gateway, and without a check the connection is made in the victim's network position —
     * which matters most for services that need no subscription, since those are reachable with no
     * credential at all.
     *
     * <p><strong>No {@code Origin} means allowed.</strong> Browsers always send one on a handshake,
     * so its absence identifies a non-browser client, and those are not what this attack uses.
     * Refusing them would break every server-to-server WebSocket client for no security gain.
     *
     * <p>Off by default and counted rather than enforced, because refusing an upgrade breaks a
     * client outright. Watch {@code capi_websocket_origin_total{action="observed"}} first.
     */
    private boolean originAllowed(HttpServerExchange exchange, WebsocketClient websocketClient) {
        HeaderValues originHeader = exchange.getRequestHeaders().get("Origin");
        if (originHeader == null || originHeader.isEmpty()) {
            return true;
        }
        String origin = originHeader.getFirst();
        CorsPolicy policy = websocketClient.getCorsPolicy() != null ? websocketClient.getCorsPolicy() : corsPolicy;
        if (policy.resolveAllowOrigin(origin) != null) {
            return true;
        }
        if (meterRegistry != null) {
            Counter.builder("capi_websocket_origin_total")
                    .tag("service", websocketClient.getServiceId() != null ? websocketClient.getServiceId() : "unknown")
                    .tag("action", enforceOriginCheck ? "rejected" : "observed")
                    .register(meterRegistry)
                    .increment();
        }
        if (!enforceOriginCheck) {
            log.debug("WebSocket upgrade from unlisted origin {} for {} (not enforced)", origin, websocketClient.getServiceId());
            return true;
        }
        log.warn("Refused WebSocket upgrade from unlisted origin {} for {}", origin, websocketClient.getServiceId());
        return false;
    }

    /** Origins allowed to open a WebSocket. Shares the CORS allowlist. */
    /** Token verification for protected services. Absent = every protected upgrade is refused. */
    public void setHttpUtils(@Nullable HttpUtils httpUtils) {
        this.httpUtils = httpUtils;
    }

    /** OPA engine for services with {@code opa-rego}. Absent = those services are refused. */
    public void setOpaWasmService(@Nullable OpaWasmService opaWasmService) {
        this.opaWasmService = opaWasmService;
    }

    /**
     * The shared access gate ({@link AccessGate}) at the upgrade request. Returns 0 to proceed, or
     * the status to refuse with: 401 no or invalid token, 403 refused, 503 policy engine not ready.
     *
     * <p>Browsers cannot set headers on a WebSocket upgrade, so the {@code access_token} query
     * parameter is always accepted here, and stripped before the upgrade is forwarded.
     */
    int authorize(WebsocketClient websocketClient, HttpServerExchange exchange) {
        if (!AccessGate.requiresAuthorization(websocketClient.requiresSubscription(), false, false,
                websocketClient.getOpaRego())) {
            return 0;
        }
        String accessToken = null;
        if (httpUtils != null) {
            try {
                accessToken = httpUtils.processAuthorizationAccessToken(exchange);
            } catch (AuthorizationException e) {
                return Constants.UNAUTHORIZED_CODE;
            }
        }
        java.util.Deque<String> queryToken = exchange.getQueryParameters().get(Constants.AUTHORIZATION_REQUEST_PARAMETER);
        if (queryToken != null && !queryToken.isEmpty()) {
            if (accessToken == null) {
                accessToken = queryToken.getFirst();
            }
            removeTokenFromQuery(exchange);
        }
        AccessGate.Decision decision = new AccessGate(httpUtils, opaWasmService).authorize(
                websocketClient.getCanonicalServiceId(), websocketClient.getSubscriptionRole(),
                websocketClient.getOpaRego(), accessToken);
        return switch (decision.outcome()) {
            case ALLOWED -> 0;
            case UNAUTHENTICATED -> Constants.UNAUTHORIZED_CODE;
            case FORBIDDEN -> Constants.FORBIDDEN_CODE;
            case POLICY_UNAVAILABLE -> 503;
        };
    }

    private static void removeTokenFromQuery(HttpServerExchange exchange) {
        StringBuilder query = new StringBuilder();
        exchange.getQueryParameters().forEach((key, values) -> {
            if (!key.equals(Constants.AUTHORIZATION_REQUEST_PARAMETER)) {
                for (String value : values) {
                    if (!query.isEmpty()) {
                        query.append('&');
                    }
                    query.append(java.net.URLEncoder.encode(key, java.nio.charset.StandardCharsets.UTF_8))
                            .append('=')
                            .append(java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        });
        exchange.getQueryParameters().remove(Constants.AUTHORIZATION_REQUEST_PARAMETER);
        exchange.setQueryString(query.toString());
    }

    public void setCorsPolicy(@Nullable CorsPolicy corsPolicy) {
        this.corsPolicy = corsPolicy != null ? corsPolicy : CorsPolicy.denyAll();
    }

    /** {@code capi.websocket.enforceOriginCheck} — off means count only. */
    public void setEnforceOriginCheck(boolean enforceOriginCheck) {
        this.enforceOriginCheck = enforceOriginCheck;
    }

    /** The emergency blocklist ({@code capi.blockedPaths}). */
    public void setPathBlocklist(@Nullable PathBlocklist pathBlocklist) {
        this.pathBlocklist = pathBlocklist != null ? pathBlocklist : PathBlocklist.EMPTY;
    }

    public void setMeterRegistry(@Nullable MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void stop() {
        if(server != null) {
            log.info("Stopping WebSocket Gateway on port {}", port);
            server.stop();
        }
    }

}