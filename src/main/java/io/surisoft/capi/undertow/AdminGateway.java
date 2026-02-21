package io.surisoft.capi.undertow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.metrics.*;
import io.surisoft.capi.schema.McpTool;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.WebsocketClient;
import io.surisoft.capi.service.CapiTrustManager;
import io.surisoft.capi.service.ConsulNodeDiscovery;
import io.surisoft.capi.service.McpSessionStore;
import io.surisoft.capi.service.McpToolRegistry;
import io.surisoft.capi.utils.Constants;
import io.undertow.Undertow;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.PathHandler;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import org.apache.camel.CamelContext;
import org.apache.camel.util.json.JsonObject;
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
    private final CamelContext camelContext;
    private Undertow server;
    private final Cache<String, Service> serviceCache;
    private final SSLContext sslContext;
    ObjectMapper objectMapper = new ObjectMapper();
    private final CapiTrustManager capiTrustManager;
    private Map<String, WebsocketClient> websocketClients;
    private McpToolRegistry mcpToolRegistry;
    private McpSessionStore mcpSessionStore;

    public AdminGateway(int port, PrometheusMeterRegistry prometheusRegistry, CAPIConfiguration capiConfiguration, CamelContext camelContext, Cache<String, Service> serviceCache, SSLContext sslContext, CapiTrustManager capiTrustManager) {
        this.port = port;
        this.prometheusRegistry = prometheusRegistry;
        this.capiConfiguration = capiConfiguration;
        this.camelContext = camelContext;
        this.serviceCache = serviceCache;
        this.sslContext = sslContext;
        this.capiTrustManager = capiTrustManager;
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
                .addExactPath("/info/mcp/sessions", this::handleMcpSessions);


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
        if(ConsulNodeDiscovery.isConnectedToConsul()) {
            exchange.setStatusCode(StatusCodes.OK);
            exchange.getResponseSender().send("{\"status\":\"UP\"}");
        } else {
            exchange.setStatusCode(StatusCodes.SERVICE_UNAVAILABLE);
            exchange.getResponseSender().send("{\"status\":\"DOWN\"}");
        }
    }

    private void handleCapiInfo(HttpServerExchange exchange) {
        try {
            Info info = new Info(capiConfiguration, camelContext);
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

            Routes routes = new Routes(camelContext, serviceCache);
            Service service = routes.getCachedService(serviceId);
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
        try {
            Routes routes = new Routes(camelContext, serviceCache);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            exchange.setStatusCode(StatusCodes.OK);
            exchange.getResponseSender().send(objectMapper.writeValueAsString(routes.getAllRoutesInfo()));
        }catch (JsonProcessingException e) {
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
            JsonObject openApiObject = openAPIDefinition.getCacheOpenApiDefinition(service, objectMapper, serviceId);
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
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        try {
            if(capiConfiguration.getTrustStore().isEnabled() && capiTrustManager != null) {
                Truststore truststore = new Truststore(true, capiTrustManager);
                exchange.setStatusCode(StatusCodes.OK);
                exchange.getResponseSender().send(objectMapper.writeValueAsString(truststore.getTruststore()));
                return;
            } else {
                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                exchange.getResponseSender().send(objectMapper.writeValueAsString(buildError(StatusCodes.NOT_FOUND, "Trust Store not enabled")));
                return;
            }
        }catch (JsonProcessingException e) {
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

    public JsonObject buildError(int statusCode, String message) {
        JsonObject jsonObject = new JsonObject();
        jsonObject.put(Constants.ERROR_MESSAGE, message);
        jsonObject.put(Constants.ERROR_CODE, statusCode);
        return jsonObject;
    }

    public void setWebsocketClients(Map<String, WebsocketClient> websocketClients) {
        this.websocketClients = websocketClients;
    }

    public void setMcpToolRegistry(McpToolRegistry mcpToolRegistry) {
        this.mcpToolRegistry = mcpToolRegistry;
    }

    public void setMcpSessionStore(McpSessionStore mcpSessionStore) {
        this.mcpSessionStore = mcpSessionStore;
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
}