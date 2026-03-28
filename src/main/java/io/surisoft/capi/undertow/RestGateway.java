package io.surisoft.capi.undertow;

import io.surisoft.capi.exception.AuthorizationException;
import io.surisoft.capi.exception.HttpErrorHandler;
import io.surisoft.capi.processor.ThrottleProcessor;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.surisoft.capi.schema.OpaResult;
import io.surisoft.capi.schema.ApiKeyEntry;
import io.surisoft.capi.schema.ApiKeyStoreEntry;
import io.surisoft.capi.schema.RestClient;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.service.OpaService;
import io.surisoft.capi.service.OpaWasmService;
import io.surisoft.capi.tracer.CapiTracer;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.HttpUtils;
import io.surisoft.capi.utils.WebsocketUtils;
import io.undertow.Undertow;
import io.undertow.util.SameThreadExecutor;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderValues;
import io.undertow.util.HttpString;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.Nullable;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.cache2k.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RestGateway {
    private static final Logger log = LoggerFactory.getLogger(RestGateway.class);
    private static final Logger ACCESS_LOG = LoggerFactory.getLogger("capi.access");

    private final int port;
    private final int ioThreads;
    private final String contextPath;
    private final Map<String, RestClient> restClientMap;
    private final HttpUtils httpUtils;
    private final Cache<String, Service> serviceCache;
    private final List<String> accessControlAllowHeaders;
    private final Map<String, String> managedHeaders;
    private final String oauth2CookieName;
    private final SSLContext sslContext;

    @Nullable
    private OpaService opaService;
    @Nullable
    private OpaWasmService opaWasmService;
    @Nullable
    private ThrottleProcessor throttleProcessor;
    @Nullable
    private Cache<String, ApiKeyStoreEntry> apiKeyCache;
    @Nullable
    private CapiTracer capiTracer;
    @Nullable
    private WebsocketUtils websocketUtils;
    @Nullable
    private String reverseProxyHost;
    @Nullable
    private MeterRegistry meterRegistry;

    private final HttpErrorHandler httpErrorHandler;
    private Undertow server;

    public RestGateway(int port,
                       int ioThreads,
                       String contextPath,
                       Map<String, RestClient> restClientMap,
                       HttpUtils httpUtils,
                       Cache<String, Service> serviceCache,
                       SSLContext sslContext,
                       List<String> accessControlAllowHeaders,
                       String oauth2CookieName) {
        this.port = port;
        this.ioThreads = ioThreads;
        this.contextPath = contextPath;
        this.restClientMap = restClientMap;
        this.httpUtils = httpUtils;
        this.serviceCache = serviceCache;
        this.sslContext = sslContext;
        this.accessControlAllowHeaders = accessControlAllowHeaders;
        this.oauth2CookieName = oauth2CookieName;

        managedHeaders = new java.util.HashMap<>(Constants.CAPI_CORS_MANAGED_HEADERS);
        managedHeaders.put("Access-Control-Allow-Headers", StringUtils.join(accessControlAllowHeaders, ","));

        httpErrorHandler = new HttpErrorHandler(httpUtils);
    }

    public void setOpaService(@Nullable OpaService opaService) {
        this.opaService = opaService;
    }

    public void setThrottleProcessor(@Nullable ThrottleProcessor throttleProcessor) {
        this.throttleProcessor = throttleProcessor;
    }

    public void setApiKeyCache(@Nullable Cache<String, ApiKeyStoreEntry> apiKeyCache) {
        this.apiKeyCache = apiKeyCache;
    }

    public void setRestTracer(@Nullable CapiTracer capiTracer) {
        this.capiTracer = capiTracer;
    }

    public void setWebsocketUtils(@Nullable WebsocketUtils websocketUtils) {
        this.websocketUtils = websocketUtils;
    }

    public void setReverseProxyHost(@Nullable String reverseProxyHost) {
        this.reverseProxyHost = reverseProxyHost;
    }

    public void setMeterRegistry(@Nullable MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void setOpaWasmService(@Nullable OpaWasmService opaWasmService) {
        this.opaWasmService = opaWasmService;
    }

    public void runProxy() {
        Undertow.Builder builder = Undertow.builder()
                .setIoThreads(ioThreads);

        if (sslContext != null) {
            builder.addHttpsListener(port, Constants.UNDERTOW_LISTENING_ADDRESS, sslContext);
        } else {
            builder.addHttpListener(port, Constants.UNDERTOW_LISTENING_ADDRESS);
        }

        builder.setHandler(this::handleRequest);
        server = builder.build();
        server.start();
        log.info("REST Gateway started on port {} (ioThreads={})", port, ioThreads);
    }

    public void stop() {
        if (server != null) {
            log.info("Stopping REST Gateway on port {}", port);
            server.stop();
        }
    }

    private void handleRequest(HttpServerExchange exchange) {
        long startNanos = System.nanoTime();
        try {
            String requestPath = exchange.getRequestPath();

            // Strip BlueCoat header
            if (exchange.getRequestHeaders().contains(Constants.BLUECOAT_HEADER)) {
                exchange.getRequestHeaders().remove(Constants.BLUECOAT_HEADER);
            }

            // Access log — fires after the full proxy round-trip completes
            exchange.addExchangeCompleteListener((ex, nextListener) -> {
                long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                String originalIp = resolveClientIp(ex);
                ACCESS_LOG.info("{} {} {} {}ms {}",
                        ex.getRequestMethod(),
                        ex.getRequestPath(),
                        ex.getStatusCode(),
                        durationMs,
                        originalIp);
                nextListener.proceed();
            });

            // Add CORS headers to all responses (not just OPTIONS)
            addCorsHeaders(exchange);

            // Health check
            if (requestPath.equals("/health")) {
                exchange.setStatusCode(HttpServletResponse.SC_OK);
                exchange.getResponseHeaders().put(new HttpString("Content-Type"), "application/json");
                exchange.getResponseSender().send("{\"status\":\"UP\"}");
                return;
            }

            // OPTIONS handling for CORS
            if (exchange.getRequestMethod().equals(HttpString.tryFromString(Constants.OPTIONS_METHODS_VALUE))) {
                handleOptions(exchange);
                return;
            }

            // Reject event-stream requests on REST gateway
            if (isEventStream(exchange)) {
                httpErrorHandler.sendError(exchange, 400, "Event Stream not allowed in this route, contact your System Administrator");
                return;
            }

            // Parse service ID from path: /api/serviceName/group/...
            String restClientId = extractServiceId(requestPath);
            if (restClientId == null || !restClientMap.containsKey(restClientId)) {
                httpErrorHandler.sendError(exchange, 404, "The requested route was not found, please try again later on.", contextPath);
                return;
            }

            RestClient restClient = restClientMap.get(restClientId);

            // Record metrics via completion listener
            if (meterRegistry != null) {
                final String metricServiceId = httpUtils.contextToRole(restClient.getServiceId());
                exchange.addExchangeCompleteListener((ex, nextListener) -> {
                    String statusGroup = String.valueOf(ex.getStatusCode() / 100) + "xx";
                    Counter.builder("capi_requests_total")
                            .tag("service", metricServiceId)
                            .tag("method", ex.getRequestMethod().toString())
                            .tag("status", String.valueOf(ex.getStatusCode()))
                            .tag("status_group", statusGroup)
                            .register(meterRegistry)
                            .increment();
                    long durationNanos = System.nanoTime() - startNanos;
                    Timer.builder("capi_request_duration")
                            .tag("service", metricServiceId)
                            .tag("method", ex.getRequestMethod().toString())
                            .register(meterRegistry)
                            .record(durationNanos, TimeUnit.NANOSECONDS);
                    nextListener.proceed();
                });
            }

            // Reverse proxy headers (set as attachments so CAPIProxyHandler applies them on the outbound request)
            if (reverseProxyHost != null) {
                String prefix = (contextPath != null ? contextPath : "") + restClient.getServiceId();
                exchange.putAttachment(CAPIProxyHandler.REVERSE_PROXY_HOST, reverseProxyHost);
                exchange.putAttachment(CAPIProxyHandler.REVERSE_PROXY_PREFIX, prefix);
            }

            // Set attachments early for error handler (before auth checks may reject)
            String method = exchange.getRequestMethod().toString().toLowerCase();
            String serviceId = httpUtils.contextToRole(restClient.getServiceId());
            exchange.putAttachment(HttpErrorHandler.REQUEST_URI_KEY, requestPath);
            exchange.putAttachment(HttpErrorHandler.ROUTE_ID_KEY, serviceId + ":" + method);

            // Start trace span early so traceID is available in error responses
            if (capiTracer != null) {
                capiTracer.traceRequest(exchange, restClient);
            }

            // --- Pre-proxy checks (all synchronous, fast, no I/O to backends) ---

            // 0. OpenAPI operation validation
            if (restClient.getOpenAPI() != null) {
                String forwardPath = normalizePathForForwarding(restClient, requestPath);
                String openApiResult = checkOpenApi(exchange, restClient, forwardPath);
                if (openApiResult != null) {
                    httpErrorHandler.sendError(exchange, openApiResult.startsWith("Call not allowed") ? 400 : 401, openApiResult);
                    return;
                }
            }

            // 1. API Key check
            boolean apiKeyAuthenticated = false;
            if (restClient.isApiKeyEnabled()) {
                String apiKeyResult = checkApiKey(exchange, restClient);
                if (apiKeyResult != null) {
                    httpErrorHandler.sendError(exchange, 403, apiKeyResult);
                    return;
                }
                // checkApiKey returns null for two reasons:
                // (a) API key was valid — Authorization header removed (line 468)
                // (b) Bearer token detected — fell through for OAuth2 to handle
                // If the header was removed, the API key path succeeded.
                apiKeyAuthenticated = !exchange.getRequestHeaders().contains(Constants.AUTHORIZATION_HEADER);
            }

            // 2. OAuth2 / Subscription check
            if (restClient.isSecured() && !apiKeyAuthenticated) {
                String authResult = checkAuthorization(exchange, restClient);
                if (authResult != null) {
                    httpErrorHandler.sendError(exchange, 403, authResult);
                    return;
                }
            }

            // 3. OPA policy check
            if (restClient.getOpaRego() != null && opaService != null) {
                try {
                    String accessToken = httpUtils.processAuthorizationAccessToken(exchange);
                    if (accessToken == null) {
                        httpErrorHandler.sendError(exchange, 403, "No authorization header provided");
                        return;
                    }

                    // Prefer Wasm (in-process, microseconds) over HTTP (network round-trip)
                    if (opaWasmService != null && opaWasmService.isReady(restClient.getOpaRego())) {
                        // Verify JWT signature before trusting decoded claims
                        try {
                            httpUtils.authorizeRequest(accessToken);
                        } catch (AuthorizationException e) {
                            httpErrorHandler.sendError(exchange, 403, "Invalid token signature");
                            return;
                        }
                        OpaResult opaResult = opaWasmService.evaluate(restClient.getOpaRego(), accessToken, true);
                        if (opaResult == null || !opaResult.isAllowed()) {
                            httpErrorHandler.sendError(exchange, 403, "Access denied by policy");
                            return;
                        }
                        // Wasm passed — fall through to throttle check and proxy below
                    } else {
                        // Fallback: async HTTP call to OPA server
                        final RestClient fc = restClient;
                        final String fcId = restClientId;
                        exchange.dispatch(SameThreadExecutor.INSTANCE, () -> {
                            opaService.callOpaAsync(fc.getOpaRego(), accessToken, true)
                                    .thenAccept(opaResult -> {
                                        exchange.dispatch(SameThreadExecutor.INSTANCE, () -> {
                                            try {
                                                if (opaResult == null || !opaResult.isAllowed()) {
                                                    httpErrorHandler.sendError(exchange, 403, "Access denied by policy");
                                                    return;
                                                }
                                                if (fc.isThrottle() && throttleProcessor != null) {
                                                    Service svc = serviceCache.get(httpUtils.contextToRole(fcId));
                                                    if (svc != null && !throttleProcessor.canContinue(svc, null, false, -1, -1)) {
                                                        httpErrorHandler.sendError(exchange, 429, "Too Many requests");
                                                        return;
                                                    }
                                                }
                                                httpUtils.propagateAuthorization(exchange);
                                                if (fc.isKeepGroup()) {
                                                    exchange.getRequestHeaders().put(HttpString.tryFromString(Constants.CAPI_GROUP_HEADER), fc.getServiceId());
                                                }
                                                String fwdPath = normalizePathForForwarding(fc, exchange.getRequestURI());
                                                exchange.setRequestURI(fwdPath);
                                                exchange.setRelativePath(fwdPath);
                                                proxyWithTracing(exchange, fc, exchange.getRequestPath());
                                            } catch (Exception e) {
                                                log.error("OPA proxy error: {}", e.getMessage(), e);
                                                if (!exchange.isResponseStarted()) httpErrorHandler.sendError(exchange, 502, "Gateway error");
                                            }
                                        });
                                    })
                                    .exceptionally(ex -> {
                                        exchange.dispatch(SameThreadExecutor.INSTANCE, () -> {
                                            log.error("OPA async call failed: {}", ex.getMessage(), ex);
                                            if (!exchange.isResponseStarted()) {
                                                httpErrorHandler.sendError(exchange, 502, "Policy evaluation failed");
                                            }
                                        });
                                        return null;
                                    });
                        });
                        return;
                    }
                } catch (AuthorizationException e) {
                    httpErrorHandler.sendError(exchange, 403, e.getMessage());
                    return;
                }
            }

            // 4. Throttle check
            if (restClient.isThrottle() && throttleProcessor != null) {
                Service service = serviceCache.get(httpUtils.contextToRole(restClientId));
                if (service != null && !throttleProcessor.canContinue(service, null, false, -1, -1)) {
                    httpErrorHandler.sendError(exchange, 429, "Too Many requests");
                    return;
                }
            }

            // Propagate authorization to backend
            httpUtils.propagateAuthorization(exchange);

            // --- Async proxy handoff ---
            if (restClient.isKeepGroup()) {
                exchange.getRequestHeaders().put(HttpString.tryFromString(Constants.CAPI_GROUP_HEADER), restClient.getServiceId());
            }
            String forwardPath = normalizePathForForwarding(restClient, exchange.getRequestURI());
            exchange.setRequestURI(forwardPath);
            exchange.setRelativePath(forwardPath);
            proxyWithTracing(exchange, restClient, requestPath);

        } catch (Exception e) {
            log.error("Error handling REST request {}: {}", exchange.getRequestPath(), e.getMessage(), e);
            if (!exchange.isResponseStarted()) {
                httpErrorHandler.sendError(exchange, 502, "Gateway error");
            }
        }
    }

    private void proxyWithTracing(HttpServerExchange exchange, RestClient restClient, String requestPath) throws Exception {
        // Tracing and attachments already set before auth checks
        restClient.getHttpHandler().handleRequest(exchange);
    }

    /**
     * Extract service ID from request path.
     * Path format: /api/serviceName/group/... → /serviceName/group
     */
    private String extractServiceId(String requestPath) {
        // Strip the context path prefix (e.g., "/api")
        String pathWithoutContext = requestPath;
        if (contextPath != null && requestPath.startsWith(contextPath)) {
            pathWithoutContext = requestPath.substring(contextPath.length());
        }

        String[] parts = pathWithoutContext.split("/");
        // Need at least /serviceName/group
        if (parts.length < 3) {
            return null;
        }
        return "/" + parts[1] + "/" + parts[2];
    }

    /**
     * Normalize the path for forwarding to the backend.
     * Strips the context path and service ID, keeps the remaining path.
     */
    private String normalizePathForForwarding(RestClient restClient, String requestPath) {
        // Strip context path
        String path = requestPath;
        if (contextPath != null && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        // Strip service ID
        path = path.replaceFirst(restClient.getServiceId(), "");

        // Prepend root context if set
        if (restClient.getRootContext() != null && !restClient.getRootContext().isEmpty()
                && !restClient.getRootContext().equals("/")) {
            return restClient.getRootContext() + path;
        }
        return path.isEmpty() ? "/" : path;
    }

    private String checkApiKey(HttpServerExchange exchange, RestClient restClient) {
        HeaderValues authHeader = exchange.getRequestHeaders().get(Constants.AUTHORIZATION_HEADER);
        if (authHeader == null || authHeader.isEmpty()) {
            return "API key required";
        }
        String authorization = authHeader.getFirst();
        if (!authorization.startsWith(Constants.API_KEY_SCHEME_PREFIX)) {
            if (!restClient.isSecured()) {
                return "API key required";
            }
            return null; // Fall through to OAuth2
        }
        String rawApiKey = authorization.substring(Constants.API_KEY_SCHEME_PREFIX.length());
        if (apiKeyCache == null) {
            return "API key store not configured";
        }
        String serviceId = httpUtils.contextToRole(restClient.getServiceId());
        ApiKeyStoreEntry storeEntry = apiKeyCache.get(serviceId);
        if (storeEntry == null) {
            return "Invalid API key";
        }
        String keyHash = HttpUtils.hashApiKey(rawApiKey);
        ApiKeyEntry apiKeyEntry = storeEntry.getKeysByHash().get(keyHash);
        if (apiKeyEntry == null || !apiKeyEntry.isEnabled()) {
            return "Invalid API key";
        }

        // Check per-key throttle
        if (apiKeyEntry.getThrottleTotalCalls() > 0 && apiKeyEntry.getThrottleDuration() > 0 && throttleProcessor != null) {
            Service service = serviceCache.get(serviceId);
            if (service != null) {
                String consumerKey = "apikey:" + keyHash;
                if (!throttleProcessor.canContinue(service, consumerKey, true,
                        apiKeyEntry.getThrottleTotalCalls(), apiKeyEntry.getThrottleDuration())) {
                    return "Too Many requests";
                }
            }
        }
        // Remove auth header before proxying
        exchange.getRequestHeaders().remove(Constants.AUTHORIZATION_HEADER);
        return null; // Authorized
    }

    private String checkAuthorization(HttpServerExchange exchange, RestClient restClient) {
        try {
            String accessToken = httpUtils.processAuthorizationAccessToken(exchange);
            if (accessToken == null) {
                return "No authorization header provided";
            }
            // Check subscription group
            if (restClient.getSubscriptionGroup() != null) {
                if (!httpUtils.isAuthorized(accessToken, restClient.getSubscriptionGroup())) {
                    return "Not subscribed";
                }
            }
            return null; // Authorized
        } catch (AuthorizationException e) {
            return e.getMessage();
        }
    }

    private String checkOpenApi(HttpServerExchange exchange, RestClient restClient, String forwardPath) {
        OpenAPI openAPI = restClient.getOpenAPI();
        String callingMethod = exchange.getRequestMethod().toString().toLowerCase();

        for (String path : openAPI.getPaths().keySet()) {
            if (isOpenApiPathMatch(forwardPath, path)) {
                Operation operation = switch (callingMethod) {
                    case "get" -> openAPI.getPaths().get(path).getGet();
                    case "post" -> openAPI.getPaths().get(path).getPost();
                    case "put" -> openAPI.getPaths().get(path).getPut();
                    case "patch" -> openAPI.getPaths().get(path).getPatch();
                    case "delete" -> openAPI.getPaths().get(path).getDelete();
                    default -> null;
                };
                if (operation != null) {
                    if (operation.getSecurity() != null && !operation.getSecurity().isEmpty()) {
                        try {
                            String accessToken = httpUtils.processAuthorizationAccessToken(exchange);
                            if (accessToken == null) {
                                return "No authorization provided";
                            }
                            String serviceKey = httpUtils.contextToRole(restClient.getServiceId());
                            Service service = serviceCache.get(serviceKey);
                            if (service != null) {
                                if (!httpUtils.isAuthorized(accessToken, serviceKey, service, opaService)) {
                                    return "Invalid authentication";
                                }
                            } else {
                                return "Call not allowed";
                            }
                        } catch (Exception e) {
                            return "Invalid authorization provided";
                        }
                    }
                    return null; // Operation found, no security or authorized
                }
            }
        }
        return "Call not allowed"; // No matching operation found
    }

    private boolean isOpenApiPathMatch(String requestPath, String definedPath) {
        String req = requestPath.replaceAll("^/+|/+$", "");
        String def = definedPath.replaceAll("^/+|/+$", "");

        String[] reqSegments = req.split("/");
        String[] defSegments = def.split("/");

        if (reqSegments.length != defSegments.length) return false;

        for (int i = 0; i < reqSegments.length; i++) {
            if (!defSegments[i].equals(reqSegments[i]) && !defSegments[i].matches("\\{.*\\}")) {
                return false;
            }
        }
        return true;
    }

    private void handleOptions(HttpServerExchange exchange) {
        if (websocketUtils != null) {
            websocketUtils.handleOptionsRequest(exchange, accessControlAllowHeaders, managedHeaders, oauth2CookieName);
        } else {
            exchange.setStatusCode(HttpServletResponse.SC_ACCEPTED);
            exchange.endExchange();
        }
    }

    private boolean isEventStream(HttpServerExchange exchange) {
        HeaderValues contentType = exchange.getRequestHeaders().get("Content-Type");
        if (contentType == null) contentType = exchange.getRequestHeaders().get("content-type");
        if (contentType != null && contentType.getFirst().equalsIgnoreCase("text/event-stream")) {
            return true;
        }
        HeaderValues accept = exchange.getRequestHeaders().get("Accept");
        if (accept == null) accept = exchange.getRequestHeaders().get("accept");
        return accept != null && accept.getFirst().equalsIgnoreCase("text/event-stream");
    }

    private void addCorsHeaders(HttpServerExchange exchange) {
        HeaderValues originHeader = exchange.getRequestHeaders().get("Origin");
        if (originHeader != null && !originHeader.isEmpty()) {
            String origin = originHeader.getFirst().replaceAll("(\r\n|\n)", "");
            exchange.getResponseHeaders().put(HttpString.tryFromString(Constants.ACCESS_CONTROL_ALLOW_ORIGIN), origin);
            exchange.getResponseHeaders().put(HttpString.tryFromString(Constants.ACCESS_CONTROL_ALLOW_CREDENTIALS), "true");
        }
    }

    private String resolveClientIp(HttpServerExchange exchange) {
        if (exchange.getRequestHeaders().contains("X-Forwarded-For")) {
            return exchange.getRequestHeaders().get("X-Forwarded-For").getFirst();
        }
        java.net.InetAddress addr = exchange.getSourceAddress().getAddress();
        return addr != null ? addr.getHostAddress() : "unknown";
    }
}