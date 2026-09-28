package io.surisoft.capi.undertow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.exception.AuthorizationException;
import io.surisoft.capi.metrics.*;
import io.surisoft.capi.schema.*;
import io.surisoft.capi.service.CapiTrustManager;
import io.surisoft.capi.service.consul.ConsulCatalogService;
import jakarta.annotation.Nullable;
import io.surisoft.capi.service.ConsulStore;
import io.surisoft.capi.service.McpSessionStore;
import io.surisoft.capi.service.McpToolRegistry;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.HttpUtils;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.PathHandler;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import org.cache2k.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.util.*;
import java.util.stream.Collectors;

public class AdminGateway implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AdminGateway.class);
    private final int port;
    private final PrometheusMeterRegistry prometheusRegistry;
    private final CAPIConfiguration capiConfiguration;
    private Undertow server;
    private final Cache<String, Service> serviceCache;
    private final SSLContext sslContext;
    ObjectMapper objectMapper = new ObjectMapper();
    private static final ObjectMapper INVALID_SVC_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    /**
     * Paths reachable without a token when the listener is protected. Only the health probe:
     * a liveness/readiness check has no way to carry a bearer token, and a 401 there would take
     * the instance out of rotation. Everything else — metrics included — needs the group.
     */
    private static final Set<String> AUTH_EXEMPT_PATHS = Set.of("/info/health");
    private final CapiTrustManager capiTrustManager;
    private Map<String, WebsocketClient> websocketClients;
    private Map<String, RestClient> restClients;
    private McpToolRegistry mcpToolRegistry;
    private McpSessionStore mcpSessionStore;
    private ConsulStore consulStore;
    private final Map<String, InvalidService> invalidServices;
    private ConsulCatalogService consulCatalogService;
    private boolean matchOpenApiSpecEnabled;
    private io.surisoft.capi.observability.JvmObservability jvmObservability;
    private final boolean endpointProtected;
    private final String allowedGroup;
    private final HttpUtils httpUtils;

    public AdminGateway(int port, PrometheusMeterRegistry prometheusRegistry, CAPIConfiguration capiConfiguration, Cache<String, Service> serviceCache, SSLContext sslContext, CapiTrustManager capiTrustManager, Map<String, InvalidService> invalidServices, boolean endpointProtected, String allowedGroup, HttpUtils httpUtils) {
        this.port = port;
        this.prometheusRegistry = prometheusRegistry;
        this.capiConfiguration = capiConfiguration;
        this.serviceCache = serviceCache;
        this.sslContext = sslContext;
        this.capiTrustManager = capiTrustManager;
        this.invalidServices = invalidServices;
        this.endpointProtected = endpointProtected;
        this.allowedGroup = allowedGroup;
        this.httpUtils = httpUtils;
    }

    public void start() {
        PathHandler pathHandler = new PathHandler()
                .addExactPath("/info", this::handleInfo)
                .addExactPath("/info/metrics", this::handleMetrics)
                .addExactPath("/info/health", this::handleHealth)
                .addExactPath("/info/capi", this::handleCapiInfo)
                .addExactPath("/info/routes", this::handleRoutesInfo)
                .addPrefixPath("/info/routes/", this::handleRouteById)
                .addPrefixPath("/info/openapi/", this::handleOpenApi)
                .addExactPath("/info/truststore", this::handleTruststore)
                .addPrefixPath("/info/truststore/", this::handleTruststoreAlias)
                .addExactPath("/info/wsroutes", this::handleWsRoutes)
                .addExactPath("/info/mcp", this::handleMcpInfo)
                .addExactPath("/info/mcp/tools", this::handleMcpTools)
                .addExactPath("/info/mcp/sessions", this::handleMcpSessions)
                .addExactPath("/info/invalid-services", this::handleInvalidServices)
                .addExactPath("/info/spec-compliance", this::handleSpecCompliance)
                .addExactPath("/info/jvm/profile", this::handleJvmProfile);


        Undertow.Builder builder = Undertow.builder();
        if(sslContext != null) {
            builder.addHttpsListener(port, "0.0.0.0", sslContext);
        } else {
            builder.addHttpListener(port, "0.0.0.0");
        }
        server = builder.setHandler(withAuthorization(pathHandler)).build();
        server.start();
        if(endpointProtected) {
            log.info("Admin Gateway started on port {} (protected, group: {})", port, allowedGroup);
        } else {
            log.info("Admin Gateway started on port {}", port);
        }
    }

    public void stop() {
        if(server != null) {
            log.info("Stopping Admin Gateway on port {}", port);
            server.stop();
        }
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * Fronts every admin endpoint with the group check when {@code admin.protected} is on, so no
     * individual handler can forget it. Returns the handler untouched when protection is off.
     */
    HttpHandler withAuthorization(HttpHandler next) {
        if(!endpointProtected) {
            return next;
        }
        if(httpUtils == null) {
            throw new IllegalStateException("Admin Gateway is protected but no HttpUtils was provided, refusing to start unprotected");
        }
        if(allowedGroup == null || allowedGroup.isBlank()) {
            log.warn("Admin Gateway is protected but admin.group is not set — every request will be rejected");
        }
        if(capiConfiguration.getOauth2() == null || !capiConfiguration.getOauth2().isEnabled()) {
            log.warn("Admin Gateway is protected but oauth2 is disabled — no token can be validated, every request will be rejected");
        }
        return exchange -> {
            if(AUTH_EXEMPT_PATHS.contains(exchange.getRequestPath())) {
                next.handleRequest(exchange);
                return;
            }
            String accessToken;
            try {
                accessToken = httpUtils.processAuthorizationAccessToken(exchange);
            } catch (AuthorizationException e) {
                log.debug("Admin request to {} rejected: {}", exchange.getRequestPath(), e.getMessage());
                accessToken = null;
            }
            if(accessToken == null) {
                sendAuthError(exchange, StatusCodes.UNAUTHORIZED, "Invalid authentication");
                return;
            }
            if(!httpUtils.isAuthorized(accessToken, allowedGroup)) {
                sendAuthError(exchange, StatusCodes.FORBIDDEN, "Not authorized");
                return;
            }
            next.handleRequest(exchange);
        };
    }

    private void sendAuthError(HttpServerExchange exchange, int statusCode, String message) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.setStatusCode(statusCode);
        try {
            exchange.getResponseSender().send(objectMapper.writeValueAsString(buildError(statusCode, message)));
        } catch (JsonProcessingException e) {
            exchange.getResponseSender().send("{\"" + Constants.ERROR_MESSAGE + "\":\"" + message + "\"}");
        }
    }

    private void handleInfo(HttpServerExchange exchange) {
        try {
            String scheme = sslContext != null ? "https" : "http";
            String host = exchange.getHostAndPort();
            String baseUrl = scheme + "://" + host;

            Map<String, Object> links = new LinkedHashMap<>();
            links.put("metrics", Map.of("href", baseUrl + "/info/metrics"));
            links.put("health", Map.of("href", baseUrl + "/info/health"));
            links.put("capi", Map.of("href", baseUrl + "/info/capi"));
            links.put("routes", Map.of("href", baseUrl + "/info/routes"));
            links.put("openapi", Map.of("href", baseUrl + "/info/openapi/{serviceId}"));
            links.put("truststore", Map.of("href", baseUrl + "/info/truststore"));
            links.put("wsroutes", Map.of("href", baseUrl + "/info/wsroutes"));
            links.put("mcp", Map.of("href", baseUrl + "/info/mcp"));
            links.put("mcp-tools", Map.of("href", baseUrl + "/info/mcp/tools"));
            links.put("mcp-sessions", Map.of("href", baseUrl + "/info/mcp/sessions"));
            links.put("invalid-services", Map.of("href", baseUrl + "/info/invalid-services"));
            links.put("spec-compliance", Map.of("href", baseUrl + "/info/spec-compliance"));
            links.put("jvm-profile", Map.of("href", baseUrl + "/info/jvm/profile?seconds=60&limit=25"));

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("_links", links);

            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            exchange.setStatusCode(StatusCodes.OK);
            exchange.getResponseSender().send(objectMapper.writeValueAsString(response));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private void handleMetrics(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain; version=0.0.4; charset=utf-8");
        exchange.setStatusCode(StatusCodes.OK);
        exchange.getResponseSender().send(prometheusRegistry.scrape());
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

    private void handleCapiInfo(HttpServerExchange exchange) {
        try {
            Info info = new Info(capiConfiguration, restClients != null ? restClients.size() : 0, invalidServices.size());
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            exchange.setStatusCode(StatusCodes.OK);
            exchange.getResponseSender().send(objectMapper.writeValueAsString(info.getInfo()));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private void handleRouteById(HttpServerExchange exchange) {
        try {
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            String serviceId = exchange.getRelativePath().substring(1);
            if(serviceId.isEmpty()) {
                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                exchange.getResponseSender().send(objectMapper.writeValueAsString(buildError(StatusCodes.NOT_FOUND, "Service not found")));
                return;
            }

            Service service = serviceCache.get(serviceId);
            if(service == null) {
                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                exchange.getResponseSender().send(objectMapper.writeValueAsString(buildError(StatusCodes.NOT_FOUND, "Service not found")));
                return;
            } else {
                exchange.setStatusCode(StatusCodes.OK);
                exchange.getResponseSender().send(objectMapper.writeValueAsString(service));
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private void handleRoutesInfo(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        try {
            if (restClients != null && !restClients.isEmpty()) {
                List<Map<String, Object>> routeInfoList = new ArrayList<>();
                for (Map.Entry<String, RestClient> entry : restClients.entrySet()) {
                    Map<String, Object> routeInfo = new LinkedHashMap<>();
                    routeInfo.put("id", entry.getKey().startsWith("/") ? entry.getKey().substring(1).replace("/", ":") : entry.getKey());
                    routeInfo.put("status", "Started");
                    routeInfo.put("endpoints", entry.getValue().getMappingList().stream()
                            .map(m -> m.getHostname() + ":" + m.getPort())
                            .collect(Collectors.toList()));
                    routeInfoList.add(routeInfo);
                }
                exchange.setStatusCode(StatusCodes.OK);
                exchange.getResponseSender().send(objectMapper.writeValueAsString(routeInfoList));
            } else {
                exchange.setStatusCode(StatusCodes.OK);
                exchange.getResponseSender().send("[]");
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private void handleOpenApi(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        try {
            String serviceId = exchange.getRelativePath().substring(1);
            if(serviceId.isEmpty()) {
                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                exchange.getResponseSender().send(objectMapper.writeValueAsString(buildError(StatusCodes.NOT_FOUND, "Service not found")));
                return;
            }
            OpenAPIDefinition openAPIDefinition = new OpenAPIDefinition(serviceCache, capiConfiguration.getPublicEndpoint());
            Service service = openAPIDefinition.getCachedService(serviceId);
            if(service == null) {
                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                exchange.getResponseSender().send(objectMapper.writeValueAsString(buildError(StatusCodes.NOT_FOUND, "Service not found")));
                return;
            }
            Object openApiObject = openAPIDefinition.getCacheOpenApiDefinition(service, serviceId);
            if(openApiObject == null) {
                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                exchange.getResponseSender().send(objectMapper.writeValueAsString(buildError(StatusCodes.NOT_FOUND, "Open API not found for given Service")));
                return;
            }
            exchange.getResponseSender().send(objectMapper.writeValueAsString(openApiObject));
        }catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private void handleTruststore(HttpServerExchange exchange) {
        String method = exchange.getRequestMethod().toString();
        if ("PUT".equals(method)) {
            handleTruststorePut(exchange);
        } else if ("GET".equals(method) || "HEAD".equals(method)) {
            handleTruststoreGet(exchange);
        } else if ("DELETE".equals(method)) {
            // A bare DELETE has no alias to act on; removal is per certificate.
            sendMethodNotAllowed(exchange, "GET, PUT", "DELETE requires an alias: DELETE /info/truststore/{alias}");
        } else {
            sendMethodNotAllowed(exchange, "GET, PUT", "Method " + method + " not allowed on /info/truststore");
        }
    }

    private void handleTruststoreAlias(HttpServerExchange exchange) {
        String method = exchange.getRequestMethod().toString();
        if ("DELETE".equals(method)) {
            handleTruststoreDelete(exchange);
        } else {
            sendMethodNotAllowed(exchange, "DELETE", "Method " + method + " not allowed on /info/truststore/{alias}");
        }
    }

    private void handleTruststoreGet(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        try {
            if(capiConfiguration.getTrustStore().isEnabled() && capiTrustManager != null) {
                Truststore truststore = new Truststore(true, capiTrustManager);
                exchange.setStatusCode(StatusCodes.OK);
                exchange.getResponseSender().send(objectMapper.writeValueAsString(truststore.getTruststore()));
            } else {
                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                exchange.getResponseSender().send(objectMapper.writeValueAsString(buildError(StatusCodes.NOT_FOUND, "Trust Store not enabled")));
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Refuses a mutating admin call when the listener has no authentication in front of it.
     *
     * <p>Adding a certificate here changes what CAPI trusts for every backend TLS connection, and
     * {@code ConsulStore} pushes the result to Consul KV so every instance in the cluster reloads
     * it. On an unauthenticated listener that is a fleet-wide man-in-the-middle primitive, so the
     * write verbs stay closed even when the operator has deliberately set {@code admin.protected:
     * false}. Reads are unaffected.
     *
     * @return true when the call may proceed
     */
    private boolean requireProtectedListener(HttpServerExchange exchange) {
        if (endpointProtected) {
            return true;
        }
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.setStatusCode(StatusCodes.FORBIDDEN);
        try {
            exchange.getResponseSender().send(objectMapper.writeValueAsString(buildError(
                    StatusCodes.FORBIDDEN,
                    "Trust store changes require an authenticated admin listener (set admin.protected: true)")));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        return false;
    }

    private void handleTruststorePut(HttpServerExchange exchange) {
        if (!requireProtectedListener(exchange)) {
            return;
        }
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        try {
            if (!capiConfiguration.getTrustStore().isEnabled()) {
                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                exchange.getResponseSender().send(objectMapper.writeValueAsString(buildError(StatusCodes.NOT_FOUND, "Trust Store not enabled")));
                return;
            }
            if (consulStore == null) {
                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                exchange.getResponseSender().send(objectMapper.writeValueAsString(buildError(StatusCodes.NOT_FOUND, "Consul KV Store not configured")));
                return;
            }
            // Undertow requires dispatching to a worker thread to read the request body
            exchange.dispatch(() -> {
                try {
                    exchange.startBlocking();
                    String pem = new String(exchange.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    if (pem.isBlank() || !pem.contains("BEGIN CERTIFICATE")) {
                        exchange.setStatusCode(StatusCodes.BAD_REQUEST);
                        exchange.getResponseSender().send(objectMapper.writeValueAsString(buildError(StatusCodes.BAD_REQUEST, "Request body must be a PEM-encoded certificate")));
                        return;
                    }
                    String error = consulStore.addCertificate(pem);
                    if (error != null) {
                        exchange.setStatusCode(StatusCodes.CONFLICT);
                        exchange.getResponseSender().send(objectMapper.writeValueAsString(buildError(StatusCodes.CONFLICT, error)));
                    } else {
                        exchange.setStatusCode(StatusCodes.OK);
                        exchange.getResponseSender().send(objectMapper.writeValueAsString(Map.of("message", "Certificate added to trust store")));
                    }
                } catch (Exception e) {
                    log.error("Error processing trust store PUT: {}", e.getMessage(), e);
                    try {
                        exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
                        exchange.getResponseSender().send(objectMapper.writeValueAsString(buildError(StatusCodes.INTERNAL_SERVER_ERROR, e.getMessage())));
                    } catch (JsonProcessingException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void handleTruststoreDelete(HttpServerExchange exchange) {
        if (!requireProtectedListener(exchange)) {
            return;
        }
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        try {
            if (!capiConfiguration.getTrustStore().isEnabled()) {
                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                exchange.getResponseSender().send(objectMapper.writeValueAsString(buildError(StatusCodes.NOT_FOUND, "Trust Store not enabled")));
                return;
            }
            if (consulStore == null) {
                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                exchange.getResponseSender().send(objectMapper.writeValueAsString(buildError(StatusCodes.NOT_FOUND, "Consul KV Store not configured")));
                return;
            }
            String relativePath = exchange.getRelativePath();
            String alias = relativePath.length() > 1 ? relativePath.substring(1) : "";
            if (alias.isBlank()) {
                exchange.setStatusCode(StatusCodes.BAD_REQUEST);
                exchange.getResponseSender().send(objectMapper.writeValueAsString(buildError(StatusCodes.BAD_REQUEST, "Alias is required: DELETE /info/truststore/{alias}")));
                return;
            }
            // removeCertificate talks to Consul over blocking HTTP, so it must not run on an I/O thread
            exchange.dispatch(() -> {
                try {
                    ConsulStore.TrustStoreResult result = consulStore.removeCertificate(alias);
                    switch (result.outcome()) {
                        case SUCCESS -> {
                            exchange.setStatusCode(StatusCodes.OK);
                            exchange.getResponseSender().send(objectMapper.writeValueAsString(Map.of("message", result.message())));
                        }
                        case NOT_FOUND -> {
                            exchange.setStatusCode(StatusCodes.NOT_FOUND);
                            exchange.getResponseSender().send(objectMapper.writeValueAsString(buildError(StatusCodes.NOT_FOUND, result.message())));
                        }
                        default -> {
                            exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
                            exchange.getResponseSender().send(objectMapper.writeValueAsString(buildError(StatusCodes.INTERNAL_SERVER_ERROR, result.message())));
                        }
                    }
                } catch (Exception e) {
                    log.error("Error processing trust store DELETE: {}", e.getMessage(), e);
                    try {
                        exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
                        exchange.getResponseSender().send(objectMapper.writeValueAsString(buildError(StatusCodes.INTERNAL_SERVER_ERROR, e.getMessage())));
                    } catch (JsonProcessingException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void sendMethodNotAllowed(HttpServerExchange exchange, String allowed, String message) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.getResponseHeaders().put(Headers.ALLOW, allowed);
        exchange.setStatusCode(StatusCodes.METHOD_NOT_ALLOWED);
        try {
            exchange.getResponseSender().send(objectMapper.writeValueAsString(buildError(StatusCodes.METHOD_NOT_ALLOWED, message)));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private void handleWsRoutes(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        try {
            if(websocketClients != null && capiConfiguration.getWebsocket().isEnabled()) {
                WSRoutes wsRoutes = new WSRoutes(websocketClients);
                exchange.setStatusCode(StatusCodes.OK);
                exchange.getResponseSender().send(objectMapper.writeValueAsString(wsRoutes.getAllWebsocketRoutesInfo()));
            } else {
                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                exchange.getResponseSender().send(objectMapper.writeValueAsString(buildError(StatusCodes.NOT_FOUND, "Websocket Gateway not enabled")));
            }
            return;
        }catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public Map<String, Object> buildError(int statusCode, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put(Constants.ERROR_MESSAGE, message);
        error.put(Constants.ERROR_CODE, statusCode);
        return error;
    }

    public void setWebsocketClients(Map<String, WebsocketClient> websocketClients) {
        this.websocketClients = websocketClients;
    }

    public void setRestClients(Map<String, RestClient> restClients) {
        this.restClients = restClients;
    }

    public void setMcpToolRegistry(McpToolRegistry mcpToolRegistry) {
        this.mcpToolRegistry = mcpToolRegistry;
    }

    public void setMcpSessionStore(McpSessionStore mcpSessionStore) {
        this.mcpSessionStore = mcpSessionStore;
    }

    public void setConsulStore(ConsulStore consulStore) {
        this.consulStore = consulStore;
    }

    private void handleMcpInfo(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        try {
            boolean enabled = capiConfiguration.getMcp() != null && capiConfiguration.getMcp().isEnabled();
            int mcpPort = enabled ? capiConfiguration.getMcp().getPort() : 0;
            int toolCount = mcpToolRegistry != null ? mcpToolRegistry.getAllTools().size() : 0;
            int sessionCount = mcpSessionStore != null ? mcpSessionStore.size() : 0;

            Map<String, Object> info = new LinkedHashMap<>();
            info.put("enabled", enabled);
            info.put("port", mcpPort);
            info.put("toolCount", toolCount);
            info.put("activeSessionCount", sessionCount);

            // Feature flags surfaced to the UI / operators.
            boolean genAiTracing = enabled
                    && capiConfiguration.getMcp().getObservability() != null
                    && capiConfiguration.getMcp().getObservability().getGenAi() != null
                    && capiConfiguration.getMcp().getObservability().getGenAi().isEnabled();
            String signingMode = (enabled && capiConfiguration.getMcp().getSigning() != null)
                    ? capiConfiguration.getMcp().getSigning().getMode() : "off";
            Map<String, Object> features = new LinkedHashMap<>();
            features.put("genAiTracing", genAiTracing);
            features.put("signingMode", signingMode);
            features.put("openApiPromotion", true);
            info.put("features", features);

            exchange.setStatusCode(StatusCodes.OK);
            exchange.getResponseSender().send(objectMapper.writeValueAsString(info));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private void handleMcpTools(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        try {
            if (mcpToolRegistry == null) {
                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                exchange.getResponseSender().send(objectMapper.writeValueAsString(buildError(StatusCodes.NOT_FOUND, "MCP Gateway not enabled")));
                return;
            }
            List<McpTool> tools = mcpToolRegistry.getAllTools();
            exchange.setStatusCode(StatusCodes.OK);
            exchange.getResponseSender().send(objectMapper.writeValueAsString(tools));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private void handleMcpSessions(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        try {
            if (mcpSessionStore == null) {
                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                exchange.getResponseSender().send(objectMapper.writeValueAsString(buildError(StatusCodes.NOT_FOUND, "MCP Gateway not enabled")));
                return;
            }
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("activeSessionCount", mcpSessionStore.size());
            exchange.setStatusCode(StatusCodes.OK);
            exchange.getResponseSender().send(objectMapper.writeValueAsString(info));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private void handleInvalidServices(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        try {
            List<InvalidService> sorted = invalidServices.values().stream()
                    .sorted(Comparator.comparing(InvalidService::serviceId))
                    .toList();
            exchange.setStatusCode(StatusCodes.OK);
            exchange.getResponseSender().send(INVALID_SVC_MAPPER.writeValueAsString(sorted));
        } catch (JsonProcessingException e) {
            log.warn("Error serializing invalid services: {}", e.getMessage(), e);
            exchange.getResponseSender().send("[]");
        }

    }

    /**
     * Source of the spec-identity verdicts, and whether they are being enforced. Both are needed for
     * {@code /info/spec-compliance} to be actionable: the counts say whether the estate is ready, the
     * flag says whether the switch is already on.
     */
    public void setSpecComplianceSource(@Nullable ConsulCatalogService consulCatalogService, boolean matchOpenApiSpecEnabled) {
        this.consulCatalogService = consulCatalogService;
        this.matchOpenApiSpecEnabled = matchOpenApiSpecEnabled;
    }

    /**
     * Publishes where every discovered service stands against the spec-identity rule, so an operator
     * can watch {@code nonCompliant} drain to zero before turning {@code matchOpenApiSpec} on.
     * Verdicts are recorded whether or not enforcement is active.
     */
    private void handleSpecCompliance(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        try {
            if (consulCatalogService == null) {
                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                exchange.getResponseSender().send(objectMapper.writeValueAsString(
                        buildError(StatusCodes.NOT_FOUND, "Consul discovery not enabled")));
                return;
            }
            Collection<ConsulCatalogService.SpecCompliance> all = consulCatalogService.getSpecCompliance().values();
            Map<ConsulCatalogService.Verdict, List<ConsulCatalogService.SpecCompliance>> byVerdict =
                    all.stream().collect(Collectors.groupingBy(ConsulCatalogService.SpecCompliance::verdict));

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("enforcing", matchOpenApiSpecEnabled);
            for (ConsulCatalogService.Verdict v : ConsulCatalogService.Verdict.values()) {
                summary.put(camel(v), byVerdict.getOrDefault(v, List.of()).size());
            }
            // Ready when nothing would be blocked by turning the switch on.
            summary.put("safeToEnable", byVerdict.getOrDefault(ConsulCatalogService.Verdict.NON_COMPLIANT, List.of()).isEmpty());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("summary", summary);
            for (ConsulCatalogService.Verdict v : ConsulCatalogService.Verdict.values()) {
                body.put(camel(v), byVerdict.getOrDefault(v, List.of()).stream()
                        .sorted(Comparator.comparing(ConsulCatalogService.SpecCompliance::serviceId))
                        .toList());
            }
            exchange.setStatusCode(StatusCodes.OK);
            exchange.getResponseSender().send(INVALID_SVC_MAPPER.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            log.warn("Error serializing spec compliance: {}", e.getMessage(), e);
            exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
            exchange.getResponseSender().send("{}");
        }
    }

    /** NON_COMPLIANT -> nonCompliant, so the JSON keys read like the rest of the admin API. */
    private static String camel(ConsulCatalogService.Verdict verdict) {
        String[] parts = verdict.name().toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        return sb.toString();
    }

    public void setJvmObservability(io.surisoft.capi.observability.JvmObservability jvmObservability) {
        this.jvmObservability = jvmObservability;
    }

    private void handleJvmProfile(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        try {
            if (jvmObservability == null || !jvmObservability.isEnabled()) {
                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                exchange.getResponseSender().send(objectMapper.writeValueAsString(
                        buildError(StatusCodes.NOT_FOUND, "JVM observability not enabled")));
                return;
            }
            int seconds = parseIntParam(exchange, "seconds", 60, 1, 3600);
            int limit = parseIntParam(exchange, "limit", 25, 1, 500);
            List<io.surisoft.capi.observability.ProfileSampler.TopFrame> top =
                    jvmObservability.getProfileSampler().topByCpu(java.time.Duration.ofSeconds(seconds), limit);
            exchange.setStatusCode(StatusCodes.OK);
            exchange.getResponseSender().send(objectMapper.writeValueAsString(top));
        } catch (JsonProcessingException e) {
            log.warn("Error serializing JVM profile: {}", e.getMessage(), e);
            exchange.getResponseSender().send("[]");
        }
    }

    private static int parseIntParam(HttpServerExchange exchange, String name, int defaultValue, int min, int max) {
        java.util.Deque<String> values = exchange.getQueryParameters().get(name);
        if (values == null || values.isEmpty()) return defaultValue;
        try {
            int v = Integer.parseInt(values.getFirst());
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException nfe) {
            return defaultValue;
        }
    }
}