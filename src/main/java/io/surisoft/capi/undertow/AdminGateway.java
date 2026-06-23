package io.surisoft.capi.undertow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.metrics.*;
import io.surisoft.capi.schema.*;
import io.surisoft.capi.service.CapiTrustManager;
import io.surisoft.capi.service.consul.ConsulCatalogService;
import io.surisoft.capi.service.ConsulStore;
import io.surisoft.capi.service.McpSessionStore;
import io.surisoft.capi.service.McpToolRegistry;
import io.surisoft.capi.utils.Constants;
import io.undertow.Undertow;
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
    private final CapiTrustManager capiTrustManager;
    private Map<String, WebsocketClient> websocketClients;
    private Map<String, RestClient> restClients;
    private McpToolRegistry mcpToolRegistry;
    private McpSessionStore mcpSessionStore;
    private ConsulStore consulStore;
    private final Map<String, InvalidService> invalidServices;
    private io.surisoft.capi.observability.JvmObservability jvmObservability;

    public AdminGateway(int port, PrometheusMeterRegistry prometheusRegistry, CAPIConfiguration capiConfiguration, Cache<String, Service> serviceCache, SSLContext sslContext, CapiTrustManager capiTrustManager, Map<String, InvalidService> invalidServices) {
        this.port = port;
        this.prometheusRegistry = prometheusRegistry;
        this.capiConfiguration = capiConfiguration;
        this.serviceCache = serviceCache;
        this.sslContext = sslContext;
        this.capiTrustManager = capiTrustManager;
        this.invalidServices = invalidServices;
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
                .addExactPath("/info/wsroutes", this::handleWsRoutes)
                .addExactPath("/info/mcp", this::handleMcpInfo)
                .addExactPath("/info/mcp/tools", this::handleMcpTools)
                .addExactPath("/info/mcp/sessions", this::handleMcpSessions)
                .addExactPath("/info/invalid-services", this::handleInvalidServices)
                .addExactPath("/info/jvm/profile", this::handleJvmProfile);


        Undertow.Builder builder = Undertow.builder();
        if(sslContext != null) {
            builder.addHttpsListener(port, "0.0.0.0", sslContext);
        } else {
            builder.addHttpListener(port, "0.0.0.0");
        }
        server = builder.setHandler(pathHandler).build();
        server.start();
        log.info("Admin Gateway started on port {}", port);
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
        } else {
            handleTruststoreGet(exchange);
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

    private void handleTruststorePut(HttpServerExchange exchange) {
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