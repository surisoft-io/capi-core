package io.surisoft.capi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.utils.Constants;
import org.cache2k.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class McpServerClient {

    private static final Logger log = LoggerFactory.getLogger(McpServerClient.class);
    private static final String APPLICATION_JSON = "application/json";

    private final Cache<String, Service> serviceCache;
    private final McpBackendLoadBalancer loadBalancer;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final CAPIConfiguration configuration;
    private final ConcurrentHashMap<String, McpBackendSession> backendSessions = new ConcurrentHashMap<>();

    public McpServerClient(Cache<String, Service> serviceCache,
                           McpBackendLoadBalancer loadBalancer,
                           HttpClient httpClient,
                           CAPIConfiguration configuration) {
        this.serviceCache = serviceCache;
        this.loadBalancer = loadBalancer;
        this.httpClient = httpClient;
        this.configuration = configuration;
        this.objectMapper = new ObjectMapper();
    }

    static class McpBackendSession {
        String sessionId;
        String backendUrl;
        long createdAt;
        boolean initialized;

        McpBackendSession(String backendUrl) {
            this.backendUrl = backendUrl;
            this.createdAt = System.currentTimeMillis();
        }
    }

    public static boolean isMcpServerBackend(Service service) {
        if (service.getServiceMeta() == null) {
            return false;
        }
        Map<String, String> props = service.getServiceMeta().getUnknownProperties();
        if (props == null) {
            return false;
        }
        return "true".equalsIgnoreCase(props.get(Constants.MCP_META_ENABLED))
                && "server".equalsIgnoreCase(props.get(Constants.MCP_META_TYPE));
    }

    @SuppressWarnings("unchecked")
    public void refreshMcpServerTools() {
        Set<String> activeServiceIds = new HashSet<>();

        for (Service service : serviceCache.asMap().values()) {
            if (!isMcpServerBackend(service)) {
                continue;
            }

            String serviceId = service.getId();
            activeServiceIds.add(serviceId);

            List<String> backendUrls = loadBalancer.getOrderedBackendUrls(service);
            if (backendUrls.isEmpty()) {
                log.warn("MCP Server service {} has no backend URLs", serviceId);
                continue;
            }

            int timeout = getDiscoveryTimeout();

            for (String backendUrl : backendUrls) {
                try {
                    McpBackendSession session = backendSessions.get(serviceId);

                    // Initialize if needed
                    if (session == null || !session.initialized || !backendUrl.equals(session.backendUrl)) {
                        session = initializeBackendSession(backendUrl, timeout);
                        if (session == null) {
                            loadBalancer.reportFailure(backendUrl);
                            continue;
                        }
                        backendSessions.put(serviceId, session);
                    }

                    // List tools
                    List<Map<String, Object>> tools = listToolsFromBackend(backendUrl, session, timeout);
                    if (tools != null) {
                        populateToolMetadata(service, tools);
                        loadBalancer.reportSuccess(backendUrl);
                        log.info("Discovered {} tools from MCP Server backend {} for service {}", tools.size(), backendUrl, serviceId);
                        break; // Success, no need to try other backends
                    } else {
                        loadBalancer.reportFailure(backendUrl);
                    }
                } catch (Exception e) {
                    log.warn("Failed to discover tools from MCP Server backend {} for service {}: {}", backendUrl, serviceId, e.getMessage());
                    loadBalancer.reportFailure(backendUrl);
                }
            }
        }

        // Clean up stale sessions for services no longer in cache
        backendSessions.keySet().removeIf(id -> !activeServiceIds.contains(id));
    }

    @SuppressWarnings("unchecked")
    public Object forwardToolCall(Service service, String toolName, Object arguments, int timeout) throws Exception {
        Map<String, String> props = service.getServiceMeta().getUnknownProperties();

        // Strip prefix
        String backendToolName = toolName;
        String prefix = props.getOrDefault(Constants.MCP_META_TOOL_PREFIX, "");
        if (!prefix.isEmpty() && backendToolName.startsWith(prefix + "_")) {
            backendToolName = backendToolName.substring(prefix.length() + 1);
        }

        // Build JSON-RPC envelope
        Map<String, Object> jsonRpcRequest = new LinkedHashMap<>();
        jsonRpcRequest.put("jsonrpc", "2.0");
        jsonRpcRequest.put("method", "tools/call");
        jsonRpcRequest.put("id", UUID.randomUUID().toString());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", backendToolName);
        params.put("arguments", arguments != null ? arguments : Map.of());
        jsonRpcRequest.put("params", params);

        String requestBody = objectMapper.writeValueAsString(jsonRpcRequest);
        String serviceId = service.getId();

        List<String> backendUrls = loadBalancer.getOrderedBackendUrls(service);
        if (backendUrls.isEmpty()) {
            throw new RuntimeException("No backend URLs available for service: " + serviceId);
        }

        Exception lastException = null;
        for (String backendUrl : backendUrls) {
            try {
                McpBackendSession session = backendSessions.get(serviceId);

                // Ensure session exists
                if (session == null || !session.initialized) {
                    session = initializeBackendSession(backendUrl, timeout);
                    if (session == null) {
                        loadBalancer.reportFailure(backendUrl);
                        lastException = new RuntimeException("Failed to initialize MCP session with backend: " + backendUrl);
                        continue;
                    }
                    backendSessions.put(serviceId, session);
                }

                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(new URI(backendUrl))
                        .header("Content-Type", APPLICATION_JSON)
                        .timeout(Duration.ofMillis(timeout))
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody));

                if (session.sessionId != null) {
                    requestBuilder.header(Constants.MCP_SESSION_HEADER, session.sessionId);
                }

                HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
                loadBalancer.reportSuccess(backendUrl);

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    Map<String, Object> jsonRpcResponse = objectMapper.readValue(response.body(), Map.class);

                    // Check for session errors (might need re-init)
                    if (jsonRpcResponse.containsKey("error")) {
                        Map<String, Object> error = (Map<String, Object>) jsonRpcResponse.get("error");
                        int errorCode = error.get("code") instanceof Number ? ((Number) error.get("code")).intValue() : 0;
                        // Session-related errors: try re-init once
                        if (errorCode == Constants.JSONRPC_INVALID_REQUEST) {
                            log.info("Session error from backend {}, re-initializing", backendUrl);
                            session = initializeBackendSession(backendUrl, timeout);
                            if (session != null) {
                                backendSessions.put(serviceId, session);
                                // Retry the call once
                                requestBuilder = HttpRequest.newBuilder()
                                        .uri(new URI(backendUrl))
                                        .header("Content-Type", APPLICATION_JSON)
                                        .timeout(Duration.ofMillis(timeout))
                                        .POST(HttpRequest.BodyPublishers.ofString(requestBody));
                                if (session.sessionId != null) {
                                    requestBuilder.header(Constants.MCP_SESSION_HEADER, session.sessionId);
                                }
                                response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
                                jsonRpcResponse = objectMapper.readValue(response.body(), Map.class);
                            }
                        }
                    }

                    // Return the result object as-is (already MCP-formatted)
                    if (jsonRpcResponse.containsKey("result")) {
                        return jsonRpcResponse.get("result");
                    } else if (jsonRpcResponse.containsKey("error")) {
                        throw new RuntimeException("Backend MCP error: " + objectMapper.writeValueAsString(jsonRpcResponse.get("error")));
                    }
                    return jsonRpcResponse;
                } else {
                    throw new RuntimeException("Backend returned status " + response.statusCode() + ": " + response.body());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (java.io.IOException e) {
                log.warn("Backend {} failed for MCP tool call {}: {}", backendUrl, toolName, e.getMessage());
                loadBalancer.reportFailure(backendUrl);
                lastException = e;
            } catch (Exception e) {
                log.warn("Error calling MCP backend {} for tool {}: {}", backendUrl, toolName, e.getMessage());
                loadBalancer.reportFailure(backendUrl);
                lastException = e;
            }
        }

        throw lastException != null ? lastException : new RuntimeException("All backends failed for tool: " + toolName);
    }

    @SuppressWarnings("unchecked")
    McpBackendSession initializeBackendSession(String backendUrl, int timeout) {
        try {
            Map<String, Object> initRequest = new LinkedHashMap<>();
            initRequest.put("jsonrpc", "2.0");
            initRequest.put("method", "initialize");
            initRequest.put("id", 1);
            Map<String, Object> initParams = new LinkedHashMap<>();
            initParams.put("protocolVersion", Constants.MCP_PROTOCOL_VERSION);
            initParams.put("capabilities", Map.of());
            initParams.put("clientInfo", Map.of("name", Constants.MCP_SERVER_NAME, "version", configuration.getVersion()));
            initRequest.put("params", initParams);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(new URI(backendUrl))
                    .header("Content-Type", APPLICATION_JSON)
                    .timeout(Duration.ofMillis(timeout))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(initRequest)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                McpBackendSession session = new McpBackendSession(backendUrl);

                // Extract Mcp-Session-Id from response header
                String sessionId = response.headers().firstValue(Constants.MCP_SESSION_HEADER).orElse(null);
                session.sessionId = sessionId;
                session.initialized = true;

                return session;
            } else {
                log.warn("MCP Server initialization failed at {}: status {}", backendUrl, response.statusCode());
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while initializing MCP session with backend {}", backendUrl);
            return null;
        } catch (Exception e) {
            log.warn("Failed to initialize MCP session with backend {}: {}", backendUrl, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> listToolsFromBackend(String backendUrl, McpBackendSession session, int timeout) {
        try {
            Map<String, Object> listRequest = new LinkedHashMap<>();
            listRequest.put("jsonrpc", "2.0");
            listRequest.put("method", "tools/list");
            listRequest.put("id", 2);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(new URI(backendUrl))
                    .header("Content-Type", APPLICATION_JSON)
                    .timeout(Duration.ofMillis(timeout))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(listRequest)));

            if (session.sessionId != null) {
                requestBuilder.header(Constants.MCP_SESSION_HEADER, session.sessionId);
            }

            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                Map<String, Object> jsonRpcResponse = objectMapper.readValue(response.body(), Map.class);
                if (jsonRpcResponse.containsKey("result")) {
                    Map<String, Object> result = (Map<String, Object>) jsonRpcResponse.get("result");
                    if (result.containsKey("tools")) {
                        return (List<Map<String, Object>>) result.get("tools");
                    }
                }
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while listing tools from MCP backend {}", backendUrl);
            return null;
        } catch (Exception e) {
            log.warn("Failed to list tools from MCP backend {}: {}", backendUrl, e.getMessage());
            return null;
        }
    }

    public void populateToolMetadata(Service service, List<Map<String, Object>> tools) {
        Map<String, String> props = service.getServiceMeta().getUnknownProperties();

        // Clear old tool keys to handle tool additions/removals
        props.entrySet().removeIf(entry -> {
            String key = entry.getKey();
            return key.equals(Constants.MCP_META_TOOLS)
                    || (key.startsWith(Constants.MCP_META_PREFIX + "tools-") && (key.endsWith("-description") || key.endsWith("-inputSchema") || key.endsWith("-timeout")));
        });

        if (tools.isEmpty()) {
            props.remove(Constants.MCP_META_TOOLS);
            return;
        }

        List<String> toolNames = new ArrayList<>();
        for (Map<String, Object> tool : tools) {
            String name = (String) tool.get("name");
            if (name == null || name.isEmpty()) {
                continue;
            }
            toolNames.add(name);

            String description = (String) tool.get("description");
            if (description != null) {
                props.put(Constants.MCP_META_PREFIX + "tools-" + name + "-description", description);
            }

            Object inputSchema = tool.get("inputSchema");
            if (inputSchema != null) {
                try {
                    String schemaJson = objectMapper.writeValueAsString(inputSchema);
                    props.put(Constants.MCP_META_PREFIX + "tools-" + name + "-inputSchema", schemaJson);
                } catch (JsonProcessingException e) {
                    log.warn("Failed to serialize inputSchema for tool {}: {}", name, e.getMessage());
                }
            }
        }

        props.put(Constants.MCP_META_TOOLS, String.join(",", toolNames));
    }

    private int getDiscoveryTimeout() {
        if (configuration.getMcp() != null) {
            return configuration.getMcp().getMcpServerDiscoveryTimeoutMs();
        }
        return 10000;
    }

    // Visible for testing
    ConcurrentHashMap<String, McpBackendSession> getBackendSessions() {
        return backendSessions;
    }
}
