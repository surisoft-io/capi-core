package io.surisoft.capi.undertow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.surisoft.capi.exception.AuthorizationException;
import io.surisoft.capi.exception.HttpErrorHandler;
import io.surisoft.capi.metrics.OpenAPIDefinition;
import io.surisoft.capi.processor.ThrottleProcessor;
import io.surisoft.capi.service.consul.ConsulCatalogService;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.surisoft.capi.schema.OpaResult;
import io.surisoft.capi.schema.ApiKeyEntry;
import io.surisoft.capi.schema.ApiKeyStoreEntry;
import io.surisoft.capi.schema.RestClient;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import io.surisoft.capi.service.OpaWasmService;
import io.surisoft.capi.service.RestClientSnapshot;
import io.surisoft.capi.tracer.CapiTracer;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.HttpUtils;
import io.surisoft.capi.utils.WebsocketUtils;
import io.undertow.Undertow;
import io.undertow.server.handlers.Cookie;
import io.undertow.util.*;
import io.undertow.server.HttpServerExchange;
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
import java.util.regex.Pattern;

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
    private String publicEndpointScheme;
    @Nullable
    private MeterRegistry meterRegistry;
    @Nullable
    private OpenAPIDefinition openAPIDefinition;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpErrorHandler httpErrorHandler;
    private Undertow server;

    /** Atomically-published route snapshot. Initialized from the constructor map so
     *  tests that pre-populate restClientMap and never wire a separate snapshot still
     *  see their routes. Production overrides via setRestClientSnapshot(). */
    private volatile RestClientSnapshot restClientSnapshot;

    private static final String DEFINITIONS_OPENAPI_PREFIX = "/definitions/openapi/";
    private static final Pattern SLASH_TRIM = Pattern.compile("^/+|/+$");

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

        // Initialize the snapshot from whatever the live map currently contains.
        // This keeps existing tests (which pre-populate restClientMap then construct
        // the gateway) working unchanged, and gives us a safe empty snapshot in prod
        // until the first Consul cycle publishes one.
        RestClientSnapshot initial = new RestClientSnapshot();
        initial.publish(restClientMap);
        this.restClientSnapshot = initial;
    }

    /** Production wiring: replace the constructor-initialized snapshot with the
     *  shared instance the Consul cycle publishes to. */
    public void setRestClientSnapshot(@Nullable RestClientSnapshot restClientSnapshot) {
        if (restClientSnapshot != null) {
            this.restClientSnapshot = restClientSnapshot;
        }
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

    public void setPublicEndpointScheme(@Nullable String publicEndpointScheme) {
        this.publicEndpointScheme = publicEndpointScheme;
    }

    public void setPublicEndpoint(@Nullable String publicEndpoint) {
        if (publicEndpoint != null && !publicEndpoint.isEmpty()) {
            this.openAPIDefinition = new OpenAPIDefinition(serviceCache, publicEndpoint);
        }
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
                try {
                    //We dont want to log health calls
                    if(!requestPath.equals(Constants.CAPI_HEALTH_PATH)) {
                        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                        String originalIp = resolveClientIp(ex);
                        ACCESS_LOG.info("{} {} {} {}ms {}",
                                ex.getRequestMethod(),
                                ex.getRequestPath(),
                                ex.getStatusCode(),
                                durationMs,
                                originalIp);
                    }
                } finally {
                    // MUST always proceed: proceed() drives the completion-listener chain whose
                    // terminal listener is Undertow's connection cleanup. Skipping it (e.g. on the
                    // health path, or if the body above throws) stalls the chain, so the channel is
                    // never closed and the socket FD leaks. Confirmed root cause of the FD leak.
                    nextListener.proceed();
                }
            });

            // Add CORS headers to all responses (not just OPTIONS)
            addCorsHeaders(exchange);

            // No aviao - needs review
            /* We want to provide a way for CAPI to set a cookie for human/browser legit request
            *  Services will need to request this feature via ServiceMeta.browser-session-enabled
            *  If a valid origin is found in the request, then CAPI will create a session signature valid for
            *  ServiceMeta.browser-session-duration and set a cookie with that expiration. for CAPI own domain (RPM)
            *  and Service context path.
            *  The browser will then send the cookie for every service call: ex: capi/service/dev/get
            *  If this feature is enable for the given service, CAPI will not allow any calls without the session header.
            *  CAPI will never control if a session request is coming from a human //Check if its possible to control, via form hidden field maybe
            *  The browser should only POST to CAPI /session endpoint if a human is actually holding the browser session.
            */
            if (requestPath.equals("/session")) {
                handleSession(exchange, requestPath);
                return;
            }

            // Health check
            if (requestPath.equals(Constants.CAPI_HEALTH_PATH)) {
                handleHealth(exchange);
                return;
            }

            // OPTIONS handling for CORS
            if (exchange.getRequestMethod().equals(HttpString.tryFromString(Constants.OPTIONS_METHODS_VALUE))) {
                handleOptions(exchange);
                return;
            }

            // OpenAPI definition endpoint — outside the routing context path, no per-route lookup
            if (requestPath.startsWith(DEFINITIONS_OPENAPI_PREFIX)) {
                handleOpenApiDefinition(exchange, requestPath.substring(DEFINITIONS_OPENAPI_PREFIX.length()));
                return;
            }

            // Reject event-stream requests on REST gateway
            if (isEventStream(exchange)) {
                httpErrorHandler.sendError(exchange, 400, "Event Stream not allowed in this route, contact your System Administrator");
                return;
            }

            // Parse service ID from path: /api/serviceName/group/...
            String restClientId = extractServiceId(requestPath);
            Map<String, RestClient> activeRoutes = restClientSnapshot.current();
            RestClient restClient = (restClientId == null) ? null : activeRoutes.get(restClientId);
            if (restClient == null) {
                httpErrorHandler.sendError(exchange, 404, "The requested route was not found, please try again later on.", contextPath);
                return;
            }

            // Record metrics via completion listener
            if (meterRegistry != null) {
                final String metricServiceId = httpUtils.contextToRole(restClient.getServiceId());
                exchange.addExchangeCompleteListener((ex, nextListener) -> {
                    try {
                        String statusGroup = (ex.getStatusCode() / 100) + "xx";
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
                    } finally {
                        // Always proceed — an exception in the metrics code above must not stall
                        // the completion chain and leak the connection (see the access-log listener).
                        nextListener.proceed();
                    }
                });
            }

            // Reverse proxy headers (set as attachments so CAPIProxyHandler applies them on the outbound request)
            if (publicEndpointScheme != null) {
                exchange.putAttachment(CAPIProxyHandler.REVERSE_PROXY_PROTO, publicEndpointScheme);
            }
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

            // 0. OpenAPI operation validation — match against the API-facing path (root context
            // stripped); the spec's paths are relative to servers.url, not the backend root context.
            if (restClient.getOpenAPI() != null) {
                String apiPath = stripToApiPath(restClient, requestPath);
                String openApiResult = checkOpenApi(exchange, restClient, apiPath);
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

            // 3. OPA policy check
            if (restClient.getOpaRego() != null) {
                try {
                    String accessToken = httpUtils.processAuthorizationAccessToken(exchange);
                    if (accessToken == null) {
                        httpErrorHandler.sendError(exchange, 403, "No authorization header provided");
                        return;
                    }

                    if (opaWasmService == null || !opaWasmService.isReady()) {
                        // Pool not loaded yet (cold start, bundle server unreachable,
                        // bundle load threw). Transient — tell well-behaved clients to retry.
                        exchange.getResponseHeaders().put(Headers.RETRY_AFTER, "1");
                        httpErrorHandler.sendError(exchange, 503, "Policy engine not ready");
                        return;
                    }
                    if (!opaWasmService.hasPolicy(restClient.getOpaRego())) {
                        // Service references a policy the bundle doesn't declare — config error,
                        // not transient. Reject with a distinct status so retries don't paper over it.
                        httpErrorHandler.sendError(exchange, 403,
                                "Unknown policy: " + restClient.getOpaRego());
                        return;
                    }
                    // Verify JWT signature before trusting decoded claims
                    try {
                        httpUtils.authorizeRequest(accessToken);
                    } catch (AuthorizationException e) {
                        httpErrorHandler.sendError(exchange, 403, "Invalid token signature");
                        return;
                    }
                    OpaResult opaResult = opaWasmService.evaluate(restClient.getCanonicalServiceId(), restClient.getOpaRego(), accessToken, true);
                    if (opaResult == null || !opaResult.isAllowed()) {
                        httpErrorHandler.sendError(exchange, 403, "Access denied by policy");
                        return;
                    }
                } catch (AuthorizationException e) {
                    httpErrorHandler.sendError(exchange, 403, e.getMessage());
                    return;
                }
            }

            // 2. OAuth2 / Subscription check
            if (restClient.isSecured() && !apiKeyAuthenticated) {
                String authResult = checkAuthorization(exchange, restClient);
                if (authResult != null) {
                    httpErrorHandler.sendError(exchange, 403, authResult);
                    return;
                }
            }

            // 4. Throttle check
            if (restClient.isThrottle() && throttleProcessor != null) {
                Service service = serviceCache.get(restClient.getCanonicalServiceId());
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
     * The request path as the OpenAPI spec sees it: CAPI context path and the service ID removed,
     * but WITHOUT the backend root context. The spec's paths (relative to {@code servers.url}) do
     * not include the backend root context, so request validation must match against this, not the
     * backend-forwarding path. Returns "/" when the call targets the service root.
     */
    private String stripToApiPath(RestClient restClient, String requestPath) {
        // Strip context path
        String path = requestPath;
        if (contextPath != null && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        // Strip service ID
        path = path.replaceFirst(restClient.getServiceId(), "");
        return path.isEmpty() ? "/" : path;
    }

    /**
     * Normalize the path for forwarding to the backend: the API path (see {@link #stripToApiPath})
     * with the backend root context prepended when one is configured.
     */
    private String normalizePathForForwarding(RestClient restClient, String requestPath) {
        String path = stripToApiPath(restClient, requestPath);
        // Prepend root context if set
        if (restClient.getRootContext() != null && !restClient.getRootContext().isEmpty()
                && !restClient.getRootContext().equals("/")) {
            // stripToApiPath returns "/" for the service root; don't emit "<root>/".
            return path.equals("/") ? restClient.getRootContext() : restClient.getRootContext() + path;
        }
        return path;
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
            Service service = serviceCache.get(restClient.getCanonicalServiceId());
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

    private void handleOpenApiDefinition(HttpServerExchange exchange, String serviceId) {
        if (!exchange.getRequestMethod().equals(Methods.GET)) {
            httpErrorHandler.sendError(exchange, 405, "Method Not Allowed");
            return;
        }
        if (serviceId.isEmpty() || serviceId.contains("/") || openAPIDefinition == null) {
            httpErrorHandler.sendError(exchange, 404, "Not Found");
            return;
        }
        Service service = serviceCache.get(serviceId);
        ServiceMeta meta = service != null ? service.getServiceMeta() : null;
        if (meta == null || !meta.isExposeOpenApiDefinition()) {
            httpErrorHandler.sendError(exchange, 404, "Not Found");
            return;
        }
        if (meta.isSecureOpenApiDefinition()) {
            String accessToken;
            try {
                accessToken = httpUtils.processAuthorizationAccessToken(exchange);
            } catch (AuthorizationException e) {
                httpErrorHandler.sendError(exchange, 404, "Not Found");
                return;
            }
            if (accessToken == null || !httpUtils.isAuthorized(accessToken, meta.getSubscriptionGroup())) {
                httpErrorHandler.sendError(exchange, 404, "Not Found");
                return;
            }
        }
        Map<String, Object> definition = openAPIDefinition.getCacheOpenApiDefinition(service, serviceId);
        if (definition == null) {
            httpErrorHandler.sendError(exchange, 404, "Not Found");
            return;
        }
        try {
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            exchange.setStatusCode(StatusCodes.OK);
            exchange.getResponseSender().send(objectMapper.writeValueAsString(definition));
        } catch (JsonProcessingException e) {
            httpErrorHandler.sendError(exchange, 404, "Not Found");
        }
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

    private String  checkOpenApi(HttpServerExchange exchange, RestClient restClient, String forwardPath) {
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
                            String serviceKey = restClient.getCanonicalServiceId();
                            Service service = serviceCache.get(serviceKey);
                            if (service != null) {
                                if (!httpUtils.isAuthorized(accessToken, serviceKey, service, opaWasmService)) {
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
        String req = SLASH_TRIM.matcher(requestPath).replaceAll("");
        String def = SLASH_TRIM.matcher(definedPath).replaceAll("");

        String[] reqSegments = req.split("/");
        String[] defSegments = def.split("/");

        if (reqSegments.length != defSegments.length) return false;

        for (int i = 0; i < reqSegments.length; i++) {
            String defSeg = defSegments[i];
            if (defSeg.equals(reqSegments[i])) continue;
            boolean isPathParam = defSeg.length() >= 2
                    && defSeg.charAt(0) == '{'
                    && defSeg.charAt(defSeg.length() - 1) == '}';
            if (!isPathParam) return false;
        }
        return true;
    }

    private void handleHealth(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        if(ConsulCatalogService.isConnectedToConsul()) {
            exchange.setStatusCode(StatusCodes.OK);
            exchange.getResponseSender().send("{\"status\":\"UP\"}");
        } else {
            exchange.setStatusCode(StatusCodes.SERVICE_UNAVAILABLE);
            exchange.getResponseSender().send("{\"status\":\"DOWN\"}");
        }
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
            String raw = originHeader.getFirst();
            // Strip CR/LF (CRLF-injection defense). Almost every Origin header is
            // already clean, so short-circuit before allocating.
            String origin = (raw.indexOf('\n') < 0 && raw.indexOf('\r') < 0)
                    ? raw
                    : raw.replace("\r", "").replace("\n", "");
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

    //No aviao - needs review
    private void handleSession(HttpServerExchange exchange, String requestPath) {
        //need service authorized hosts
        String origin = exchange.getRequestHeaders().getFirst("Origin");

        if(!exchange.getRequestMethod().equals(HttpString.tryFromString("POST"))) {
            httpErrorHandler.sendError(exchange, StatusCodes.METHOD_NOT_ALLOWED, "Bad request on.", contextPath);
            return;
        }

        //Here i will need to get the form (maybe url enconded) with the following:
        // 1 - The service id (in context path string representation)
        // 2 - Some form field that may identify the request as a valid human request

        //dummy string;
        String dummyServiceId = "dddd";
        String restClientId = extractServiceId(dummyServiceId);
        Map<String, RestClient> activeRoutes = restClientSnapshot.current();
        RestClient restClient = (restClientId == null) ? null : activeRoutes.get(restClientId);
        if (restClient == null) {
            httpErrorHandler.sendError(exchange, StatusCodes.NOT_FOUND, "The requested route was not found, please try again later on.", dummyServiceId);
            return;
        }

        // After having the service object we will check if this service supports sessions
        // starting with a dummy
        boolean sessionEnabled = false;
        if(sessionEnabled) {
            // check if a list of allowed web applications is present
            // if yes, the origin of this request will need to match of the allowed from the given list
        } else {
            httpErrorHandler.sendError(exchange, StatusCodes.BAD_REQUEST, "Session support not available for .", dummyServiceId);
            return;
        }

        //if cookie exists, we extend the session
        Cookie capiSessionCookie = exchange.getRequestCookie("CAPI-SESSION");
        if(capiSessionCookie != null) {
            exchange.setStatusCode(StatusCodes.CREATED);
            //capiSessionCookie.setMaxAge()
            exchange.getResponseSender().send("a success message");
        } else {
            //set the cookie logic
            exchange.setStatusCode(StatusCodes.CREATED);
            exchange.getResponseSender().send("a success message");
        }
    }
}