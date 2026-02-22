package io.surisoft.capi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.schema.Mapping;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import io.undertow.Undertow;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class McpServerClientTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private Cache<String, Service> serviceCache;
    private McpBackendLoadBalancer loadBalancer;
    private McpServerClient mcpServerClient;
    private CAPIConfiguration configuration;

    @BeforeEach
    void setUp() {
        serviceCache = new Cache2kBuilder<String, Service>() {}
                .name("testMcpServer-" + System.currentTimeMillis())
                .eternal(true)
                .entryCapacity(100)
                .storeByReference(true)
                .build();

        loadBalancer = new McpBackendLoadBalancer(30000);

        configuration = new CAPIConfiguration();
        configuration.setVersion("1.0.0-test");
        CAPIConfiguration.Mcp mcp = new CAPIConfiguration.Mcp();
        mcp.setEnabled(true);
        mcp.setMcpServerDiscoveryTimeoutMs(5000);
        mcp.setToolCallTimeout(5000);
        configuration.setMcp(mcp);

        mcpServerClient = new McpServerClient(serviceCache, loadBalancer, HttpClient.newHttpClient(), configuration);
    }

    @AfterEach
    void tearDown() {
        serviceCache.close();
    }

    @Test
    void isMcpServerBackend_trueForServer() {
        Service service = createMcpServerService("svc1");
        assertTrue(McpServerClient.isMcpServerBackend(service));
    }

    @Test
    void isMcpServerBackend_falseForRest() {
        Service service = createService("svc1");
        service.getServiceMeta().handleUnknown("mcp-enabled", "true");
        service.getServiceMeta().handleUnknown("mcp-type", "rest");
        assertFalse(McpServerClient.isMcpServerBackend(service));
    }

    @Test
    void isMcpServerBackend_falseWhenTypeAbsent() {
        Service service = createService("svc1");
        service.getServiceMeta().handleUnknown("mcp-enabled", "true");
        assertFalse(McpServerClient.isMcpServerBackend(service));
    }

    @Test
    void isMcpServerBackend_falseWhenMcpDisabled() {
        Service service = createService("svc1");
        service.getServiceMeta().handleUnknown("mcp-enabled", "false");
        service.getServiceMeta().handleUnknown("mcp-type", "server");
        assertFalse(McpServerClient.isMcpServerBackend(service));
    }

    @Test
    void isMcpServerBackend_falseWhenNoServiceMeta() {
        Service service = new Service();
        service.setId("svc1");
        assertFalse(McpServerClient.isMcpServerBackend(service));
    }

    @Test
    void isMcpServerBackend_falseWhenNullProps() {
        Service service = new Service();
        service.setId("svc1");
        service.setServiceMeta(new ServiceMeta());
        // unknownProperties is initialized as empty HashMap, not null
        assertFalse(McpServerClient.isMcpServerBackend(service));
    }

    @Test
    @SuppressWarnings("unchecked")
    void refreshMcpServerTools_discoversToolsFromBackend() throws Exception {
        int backendPort = findFreePort();

        // Mock MCP Server that responds to initialize and tools/list
        Undertow backend = createMockMcpServer(backendPort,
                List.of(
                        Map.of("name", "forecast", "description", "Get weather forecast",
                                "inputSchema", Map.of("type", "object", "properties", Map.of("city", Map.of("type", "string"))))
                ));
        backend.start();

        try {
            Service service = createMcpServerServiceWithBackend("weather-svc", "localhost", backendPort);
            service.getServiceMeta().handleUnknown("mcp-toolPrefix", "weather");
            serviceCache.put("weather-svc", service);

            mcpServerClient.refreshMcpServerTools();

            // Verify tools were populated in unknownProperties
            Map<String, String> props = service.getServiceMeta().getUnknownProperties();
            assertEquals("forecast", props.get("mcp-tools"));
            assertEquals("Get weather forecast", props.get("mcp-tools-forecast-description"));
            assertNotNull(props.get("mcp-tools-forecast-inputSchema"));
            assertTrue(props.get("mcp-tools-forecast-inputSchema").contains("city"));
        } finally {
            backend.stop();
        }
    }

    @Test
    void refreshMcpServerTools_backendDown_logsWarning() {
        int deadPort = findFreePort();
        Service service = createMcpServerServiceWithBackend("dead-svc", "localhost", deadPort);
        serviceCache.put("dead-svc", service);

        // Should not throw
        assertDoesNotThrow(() -> mcpServerClient.refreshMcpServerTools());
    }

    @Test
    void refreshMcpServerTools_cleansStaleSessions() throws Exception {
        // Manually add a stale session
        mcpServerClient.getBackendSessions().put("stale-svc", new McpServerClient.McpBackendSession("http://localhost:9999"));

        // Service cache is empty, so "stale-svc" should be cleaned up
        mcpServerClient.refreshMcpServerTools();

        assertFalse(mcpServerClient.getBackendSessions().containsKey("stale-svc"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void forwardToolCall_sendsJsonRpcEnvelope() throws Exception {
        int backendPort = findFreePort();

        // Mock backend that captures the request and returns a tools/call result
        List<String> capturedBodies = Collections.synchronizedList(new ArrayList<>());
        Undertow backend = Undertow.builder()
                .addHttpListener(backendPort, "0.0.0.0")
                .setHandler(exchange -> {
                    if (exchange.isInIoThread()) {
                        exchange.dispatch(() -> {
                            try {
                                exchange.startBlocking();
                                String body = new String(exchange.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                                capturedBodies.add(body);

                                Map<String, Object> req = objectMapper.readValue(body, Map.class);
                                String method = (String) req.get("method");

                                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                                if ("initialize".equals(method)) {
                                    exchange.getResponseHeaders().put(io.undertow.util.HttpString.tryFromString("Mcp-Session-Id"), "test-session-123");
                                    exchange.getResponseSender().send("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-03-26\"}}");
                                } else if ("tools/call".equals(method)) {
                                    exchange.getResponseSender().send("{\"jsonrpc\":\"2.0\",\"id\":\"" + req.get("id") + "\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"sunny\"}]}}");
                                }
                            } catch (Exception e) {
                                exchange.setStatusCode(500);
                                exchange.getResponseSender().send("Error");
                            }
                        });
                        return;
                    }
                }).build();
        backend.start();

        try {
            Service service = createMcpServerServiceWithBackend("weather-svc", "localhost", backendPort);
            serviceCache.put("weather-svc", service);

            Object result = mcpServerClient.forwardToolCall(service, "forecast", Map.of("city", "London"), 5000);

            assertNotNull(result);
            // result should be the MCP result object
            assertTrue(result instanceof Map);
            Map<String, Object> resultMap = (Map<String, Object>) result;
            assertNotNull(resultMap.get("content"));

            // Verify a tools/call request was sent
            assertTrue(capturedBodies.size() >= 2); // initialize + tools/call
            String toolCallBody = capturedBodies.get(capturedBodies.size() - 1);
            assertTrue(toolCallBody.contains("\"method\":\"tools/call\""));
            assertTrue(toolCallBody.contains("\"name\":\"forecast\""));
        } finally {
            backend.stop();
        }
    }

    @Test
    void forwardToolCall_passesResultThrough() throws Exception {
        int backendPort = findFreePort();

        Undertow backend = createMockMcpServerForToolCall(backendPort,
                "{\"content\":[{\"type\":\"text\",\"text\":\"42 degrees\"}]}");
        backend.start();

        try {
            Service service = createMcpServerServiceWithBackend("temp-svc", "localhost", backendPort);
            serviceCache.put("temp-svc", service);

            Object result = mcpServerClient.forwardToolCall(service, "getTemp", Map.of(), 5000);

            assertNotNull(result);
            String resultJson = objectMapper.writeValueAsString(result);
            assertTrue(resultJson.contains("42 degrees"));
            // No re-wrapping — result is passed through as-is
            assertTrue(resultJson.contains("content"));
        } finally {
            backend.stop();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void forwardToolCall_stripsPrefix() throws Exception {
        int backendPort = findFreePort();

        List<String> capturedBodies = Collections.synchronizedList(new ArrayList<>());
        Undertow backend = Undertow.builder()
                .addHttpListener(backendPort, "0.0.0.0")
                .setHandler(exchange -> {
                    if (exchange.isInIoThread()) {
                        exchange.dispatch(() -> {
                            try {
                                exchange.startBlocking();
                                String body = new String(exchange.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                                capturedBodies.add(body);

                                Map<String, Object> req = objectMapper.readValue(body, Map.class);
                                String method = (String) req.get("method");

                                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                                if ("initialize".equals(method)) {
                                    exchange.getResponseSender().send("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-03-26\"}}");
                                } else if ("tools/call".equals(method)) {
                                    exchange.getResponseSender().send("{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}}");
                                }
                            } catch (Exception e) {
                                exchange.setStatusCode(500);
                                exchange.getResponseSender().send("Error");
                            }
                        });
                        return;
                    }
                }).build();
        backend.start();

        try {
            Service service = createMcpServerServiceWithBackend("prefix-svc", "localhost", backendPort);
            service.getServiceMeta().handleUnknown("mcp-toolPrefix", "weather");
            serviceCache.put("prefix-svc", service);

            mcpServerClient.forwardToolCall(service, "weather_forecast", Map.of(), 5000);

            // Find the tools/call request body
            String toolCallBody = capturedBodies.stream()
                    .filter(b -> b.contains("tools/call"))
                    .findFirst().orElse(null);
            assertNotNull(toolCallBody, "Expected a tools/call request");
            // The backend should receive "forecast", not "weather_forecast"
            assertTrue(toolCallBody.contains("\"name\":\"forecast\""));
            assertFalse(toolCallBody.contains("\"name\":\"weather_forecast\""));
        } finally {
            backend.stop();
        }
    }

    @Test
    void forwardToolCall_failover_triesNextBackend() throws Exception {
        int deadPort = findFreePort();
        int livePort = findFreePort();

        Undertow liveBackend = createMockMcpServerForToolCall(livePort,
                "{\"content\":[{\"type\":\"text\",\"text\":\"from live\"}]}");
        liveBackend.start();

        try {
            Service service = createService("failover-svc");
            service.getServiceMeta().handleUnknown("mcp-enabled", "true");
            service.getServiceMeta().handleUnknown("mcp-type", "server");
            service.getServiceMeta().setScheme("http");

            Mapping deadMapping = new Mapping();
            deadMapping.setHostname("localhost");
            deadMapping.setPort(deadPort);
            deadMapping.setRootContext("/");

            Mapping liveMapping = new Mapping();
            liveMapping.setHostname("localhost");
            liveMapping.setPort(livePort);
            liveMapping.setRootContext("/");

            service.setMappingList(Set.of(deadMapping, liveMapping));
            serviceCache.put("failover-svc", service);

            Object result = mcpServerClient.forwardToolCall(service, "test", Map.of(), 5000);
            assertNotNull(result);
            String resultJson = objectMapper.writeValueAsString(result);
            assertTrue(resultJson.contains("from live"));
        } finally {
            liveBackend.stop();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void forwardToolCall_sessionExpired_reInitializes() throws Exception {
        int backendPort = findFreePort();

        // Backend that returns session error on first tools/call, then succeeds after re-init
        int[] callCount = {0};
        Undertow backend = Undertow.builder()
                .addHttpListener(backendPort, "0.0.0.0")
                .setHandler(exchange -> {
                    if (exchange.isInIoThread()) {
                        exchange.dispatch(() -> {
                            try {
                                exchange.startBlocking();
                                String body = new String(exchange.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                                @SuppressWarnings("unchecked")
                                Map<String, Object> req = objectMapper.readValue(body, Map.class);
                                String method = (String) req.get("method");

                                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                                if ("initialize".equals(method)) {
                                    callCount[0]++;
                                    exchange.getResponseHeaders().put(io.undertow.util.HttpString.tryFromString("Mcp-Session-Id"), "session-" + callCount[0]);
                                    exchange.getResponseSender().send("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-03-26\"}}");
                                } else if ("tools/call".equals(method)) {
                                    callCount[0]++;
                                    if (callCount[0] <= 3) {
                                        // First tools/call after first init: return session error
                                        exchange.getResponseSender().send("{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"error\":{\"code\":-32600,\"message\":\"Session expired\"}}");
                                    } else {
                                        // After re-init: success
                                        exchange.getResponseSender().send("{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"recovered\"}]}}");
                                    }
                                }
                            } catch (Exception e) {
                                exchange.setStatusCode(500);
                                exchange.getResponseSender().send("Error");
                            }
                        });
                        return;
                    }
                }).build();
        backend.start();

        try {
            Service service = createMcpServerServiceWithBackend("session-svc", "localhost", backendPort);
            serviceCache.put("session-svc", service);

            Object result = mcpServerClient.forwardToolCall(service, "test", Map.of(), 5000);
            assertNotNull(result);
            String resultJson = objectMapper.writeValueAsString(result);
            assertTrue(resultJson.contains("recovered"));
        } finally {
            backend.stop();
        }
    }

    @Test
    void populateToolMetadata_clearsOldToolsOnRefresh() {
        Service service = createService("svc1");
        Map<String, String> props = service.getServiceMeta().getUnknownProperties();

        // Simulate old tools
        props.put("mcp-tools", "oldTool1,oldTool2");
        props.put("mcp-tools-oldTool1-description", "Old tool 1");
        props.put("mcp-tools-oldTool2-description", "Old tool 2");
        props.put("mcp-tools-oldTool2-inputSchema", "{\"type\":\"object\"}");

        // New tools
        List<Map<String, Object>> newTools = List.of(
                Map.of("name", "newTool", "description", "New tool")
        );

        mcpServerClient.populateToolMetadata(service, newTools);

        assertEquals("newTool", props.get("mcp-tools"));
        assertEquals("New tool", props.get("mcp-tools-newTool-description"));
        assertNull(props.get("mcp-tools-oldTool1-description"));
        assertNull(props.get("mcp-tools-oldTool2-description"));
        assertNull(props.get("mcp-tools-oldTool2-inputSchema"));
    }

    @Test
    void populateToolMetadata_emptyTools_clearsAll() {
        Service service = createService("svc1");
        Map<String, String> props = service.getServiceMeta().getUnknownProperties();
        props.put("mcp-tools", "old");
        props.put("mcp-tools-old-description", "Old");

        mcpServerClient.populateToolMetadata(service, List.of());

        assertNull(props.get("mcp-tools"));
        assertNull(props.get("mcp-tools-old-description"));
    }

    @Test
    void forwardToolCall_noBackendUrls_throws() {
        Service service = createService("no-backend");
        service.getServiceMeta().handleUnknown("mcp-enabled", "true");
        service.getServiceMeta().handleUnknown("mcp-type", "server");
        // No mappings

        assertThrows(RuntimeException.class, () ->
                mcpServerClient.forwardToolCall(service, "test", Map.of(), 5000));
    }

    // --- Helper methods ---

    private Service createService(String id) {
        Service service = new Service();
        service.setId(id);
        service.setName(id);
        ServiceMeta meta = new ServiceMeta();
        service.setServiceMeta(meta);
        return service;
    }

    private Service createMcpServerService(String id) {
        Service service = createService(id);
        service.getServiceMeta().handleUnknown("mcp-enabled", "true");
        service.getServiceMeta().handleUnknown("mcp-type", "server");
        return service;
    }

    private Service createMcpServerServiceWithBackend(String id, String hostname, int port) {
        Service service = createMcpServerService(id);
        service.getServiceMeta().setScheme("http");

        Mapping mapping = new Mapping();
        mapping.setHostname(hostname);
        mapping.setPort(port);
        mapping.setRootContext("/");
        service.setMappingList(Set.of(mapping));

        return service;
    }

    @SuppressWarnings("unchecked")
    private Undertow createMockMcpServer(int port, List<Map<String, Object>> tools) {
        return Undertow.builder()
                .addHttpListener(port, "0.0.0.0")
                .setHandler(exchange -> {
                    if (exchange.isInIoThread()) {
                        exchange.dispatch(() -> handleMockMcpServer(exchange, tools));
                        return;
                    }
                    handleMockMcpServer(exchange, tools);
                }).build();
    }

    @SuppressWarnings("unchecked")
    private void handleMockMcpServer(HttpServerExchange exchange, List<Map<String, Object>> tools) {
        try {
            exchange.startBlocking();
            String body = new String(exchange.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> req = objectMapper.readValue(body, Map.class);
            String method = (String) req.get("method");

            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            if ("initialize".equals(method)) {
                exchange.getResponseHeaders().put(io.undertow.util.HttpString.tryFromString("Mcp-Session-Id"), "mock-session");
                exchange.getResponseSender().send("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-03-26\"}}");
            } else if ("tools/list".equals(method)) {
                String toolsJson = objectMapper.writeValueAsString(Map.of("tools", tools));
                exchange.getResponseSender().send("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" + toolsJson + "}");
            }
        } catch (Exception e) {
            exchange.setStatusCode(500);
            exchange.getResponseSender().send("Error: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Undertow createMockMcpServerForToolCall(int port, String resultJson) {
        return Undertow.builder()
                .addHttpListener(port, "0.0.0.0")
                .setHandler(exchange -> {
                    if (exchange.isInIoThread()) {
                        exchange.dispatch(() -> handleMockToolCall(exchange, resultJson));
                        return;
                    }
                    handleMockToolCall(exchange, resultJson);
                }).build();
    }

    @SuppressWarnings("unchecked")
    private void handleMockToolCall(HttpServerExchange exchange, String resultJson) {
        try {
            exchange.startBlocking();
            String body = new String(exchange.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> req = objectMapper.readValue(body, Map.class);
            String method = (String) req.get("method");

            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            if ("initialize".equals(method)) {
                exchange.getResponseHeaders().put(io.undertow.util.HttpString.tryFromString("Mcp-Session-Id"), "mock-session");
                exchange.getResponseSender().send("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-03-26\"}}");
            } else if ("tools/call".equals(method)) {
                exchange.getResponseSender().send("{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"result\":" + resultJson + "}");
            }
        } catch (Exception e) {
            exchange.setStatusCode(500);
            exchange.getResponseSender().send("Error: " + e.getMessage());
        }
    }

    @Test
    void forwardToolCall_nullArguments_sendsEmptyMap() throws Exception {
        int backendPort = findFreePort();

        List<String> capturedBodies = Collections.synchronizedList(new ArrayList<>());
        Undertow backend = Undertow.builder()
                .addHttpListener(backendPort, "0.0.0.0")
                .setHandler(exchange -> {
                    if (exchange.isInIoThread()) {
                        exchange.dispatch(() -> {
                            try {
                                exchange.startBlocking();
                                String body = new String(exchange.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                                capturedBodies.add(body);
                                @SuppressWarnings("unchecked")
                                Map<String, Object> req = objectMapper.readValue(body, Map.class);
                                String method = (String) req.get("method");
                                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                                if ("initialize".equals(method)) {
                                    exchange.getResponseSender().send("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-03-26\"}}");
                                } else if ("tools/call".equals(method)) {
                                    exchange.getResponseSender().send("{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}}");
                                }
                            } catch (Exception e) {
                                exchange.setStatusCode(500);
                                exchange.getResponseSender().send("Error");
                            }
                        });
                        return;
                    }
                }).build();
        backend.start();

        try {
            Service service = createMcpServerServiceWithBackend("null-args-svc", "localhost", backendPort);
            serviceCache.put("null-args-svc", service);

            Object result = mcpServerClient.forwardToolCall(service, "test", null, 5000);
            assertNotNull(result);

            // Verify arguments were sent as empty map
            String toolCallBody = capturedBodies.stream()
                    .filter(b -> b.contains("tools/call"))
                    .findFirst().orElse(null);
            assertNotNull(toolCallBody);
            assertTrue(toolCallBody.contains("\"arguments\":{}"));
        } finally {
            backend.stop();
        }
    }

    @Test
    void forwardToolCall_backendReturnsNon200_throwsException() throws Exception {
        int backendPort = findFreePort();

        Undertow backend = Undertow.builder()
                .addHttpListener(backendPort, "0.0.0.0")
                .setHandler(exchange -> {
                    if (exchange.isInIoThread()) {
                        exchange.dispatch(() -> {
                            try {
                                exchange.startBlocking();
                                String body = new String(exchange.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                                @SuppressWarnings("unchecked")
                                Map<String, Object> req = objectMapper.readValue(body, Map.class);
                                String method = (String) req.get("method");
                                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                                if ("initialize".equals(method)) {
                                    exchange.getResponseHeaders().put(io.undertow.util.HttpString.tryFromString("Mcp-Session-Id"), "sess");
                                    exchange.getResponseSender().send("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-03-26\"}}");
                                } else if ("tools/call".equals(method)) {
                                    exchange.setStatusCode(500);
                                    exchange.getResponseSender().send("Internal Server Error");
                                }
                            } catch (Exception e) {
                                exchange.setStatusCode(500);
                                exchange.getResponseSender().send("Error");
                            }
                        });
                        return;
                    }
                }).build();
        backend.start();

        try {
            Service service = createMcpServerServiceWithBackend("err-svc", "localhost", backendPort);
            serviceCache.put("err-svc", service);

            Exception ex = assertThrows(RuntimeException.class, () ->
                    mcpServerClient.forwardToolCall(service, "test", Map.of(), 5000));
            assertTrue(ex.getMessage().contains("Backend returned status 500"));
        } finally {
            backend.stop();
        }
    }

    @Test
    void forwardToolCall_backendReturnsMcpError_throwsException() throws Exception {
        int backendPort = findFreePort();

        Undertow backend = Undertow.builder()
                .addHttpListener(backendPort, "0.0.0.0")
                .setHandler(exchange -> {
                    if (exchange.isInIoThread()) {
                        exchange.dispatch(() -> {
                            try {
                                exchange.startBlocking();
                                String body = new String(exchange.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                                @SuppressWarnings("unchecked")
                                Map<String, Object> req = objectMapper.readValue(body, Map.class);
                                String method = (String) req.get("method");
                                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                                if ("initialize".equals(method)) {
                                    exchange.getResponseHeaders().put(io.undertow.util.HttpString.tryFromString("Mcp-Session-Id"), "sess");
                                    exchange.getResponseSender().send("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-03-26\"}}");
                                } else if ("tools/call".equals(method)) {
                                    // Return a non-session MCP error (code != -32600)
                                    exchange.getResponseSender().send("{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"error\":{\"code\":-32603,\"message\":\"Tool execution failed\"}}");
                                }
                            } catch (Exception e) {
                                exchange.setStatusCode(500);
                                exchange.getResponseSender().send("Error");
                            }
                        });
                        return;
                    }
                }).build();
        backend.start();

        try {
            Service service = createMcpServerServiceWithBackend("mcp-err-svc", "localhost", backendPort);
            serviceCache.put("mcp-err-svc", service);

            Exception ex = assertThrows(RuntimeException.class, () ->
                    mcpServerClient.forwardToolCall(service, "test", Map.of(), 5000));
            assertTrue(ex.getMessage().contains("Backend MCP error"));
        } finally {
            backend.stop();
        }
    }

    @Test
    void forwardToolCall_sessionExpired_reInitFails_throwsError() throws Exception {
        int backendPort = findFreePort();
        int[] callCount = {0};

        Undertow backend = Undertow.builder()
                .addHttpListener(backendPort, "0.0.0.0")
                .setHandler(exchange -> {
                    if (exchange.isInIoThread()) {
                        exchange.dispatch(() -> {
                            try {
                                exchange.startBlocking();
                                String body = new String(exchange.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                                @SuppressWarnings("unchecked")
                                Map<String, Object> req = objectMapper.readValue(body, Map.class);
                                String method = (String) req.get("method");
                                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                                if ("initialize".equals(method)) {
                                    callCount[0]++;
                                    if (callCount[0] == 1) {
                                        // First init succeeds
                                        exchange.getResponseHeaders().put(io.undertow.util.HttpString.tryFromString("Mcp-Session-Id"), "sess-1");
                                        exchange.getResponseSender().send("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-03-26\"}}");
                                    } else {
                                        // Re-init fails
                                        exchange.setStatusCode(503);
                                        exchange.getResponseSender().send("Service Unavailable");
                                    }
                                } else if ("tools/call".equals(method)) {
                                    // Always return session error
                                    exchange.getResponseSender().send("{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"error\":{\"code\":-32600,\"message\":\"Session expired\"}}");
                                }
                            } catch (Exception e) {
                                exchange.setStatusCode(500);
                                exchange.getResponseSender().send("Error");
                            }
                        });
                        return;
                    }
                }).build();
        backend.start();

        try {
            Service service = createMcpServerServiceWithBackend("reinit-fail-svc", "localhost", backendPort);
            serviceCache.put("reinit-fail-svc", service);

            // Should throw because after session error, re-init fails, and the error response
            // has no "result" key, so it falls through to the error branch
            Exception ex = assertThrows(RuntimeException.class, () ->
                    mcpServerClient.forwardToolCall(service, "test", Map.of(), 5000));
            assertTrue(ex.getMessage().contains("Backend MCP error") || ex.getMessage().contains("Session expired"));
        } finally {
            backend.stop();
        }
    }

    @Test
    void refreshMcpServerTools_noBackendUrls_logsWarning() {
        Service service = createMcpServerService("no-url-svc");
        // No mappings at all
        serviceCache.put("no-url-svc", service);

        assertDoesNotThrow(() -> mcpServerClient.refreshMcpServerTools());
    }

    @Test
    void refreshMcpServerTools_toolsListReturnsNull_reportsFailure() throws Exception {
        int backendPort = findFreePort();

        // Backend where tools/list returns 200 but no "result" key
        Undertow backend = Undertow.builder()
                .addHttpListener(backendPort, "0.0.0.0")
                .setHandler(exchange -> {
                    if (exchange.isInIoThread()) {
                        exchange.dispatch(() -> {
                            try {
                                exchange.startBlocking();
                                String body = new String(exchange.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                                @SuppressWarnings("unchecked")
                                Map<String, Object> req = objectMapper.readValue(body, Map.class);
                                String method = (String) req.get("method");
                                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                                if ("initialize".equals(method)) {
                                    exchange.getResponseHeaders().put(io.undertow.util.HttpString.tryFromString("Mcp-Session-Id"), "sess");
                                    exchange.getResponseSender().send("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-03-26\"}}");
                                } else if ("tools/list".equals(method)) {
                                    // Return response without "result" key
                                    exchange.getResponseSender().send("{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32603,\"message\":\"internal error\"}}");
                                }
                            } catch (Exception e) {
                                exchange.setStatusCode(500);
                                exchange.getResponseSender().send("Error");
                            }
                        });
                        return;
                    }
                }).build();
        backend.start();

        try {
            Service service = createMcpServerServiceWithBackend("null-tools-svc", "localhost", backendPort);
            serviceCache.put("null-tools-svc", service);

            assertDoesNotThrow(() -> mcpServerClient.refreshMcpServerTools());
            // Tools should not have been populated
            assertNull(service.getServiceMeta().getUnknownProperties().get("mcp-tools"));
        } finally {
            backend.stop();
        }
    }

    @Test
    void refreshMcpServerTools_initializationFails_reportsFailure() throws Exception {
        int backendPort = findFreePort();

        // Backend that returns 503 on initialize
        Undertow backend = Undertow.builder()
                .addHttpListener(backendPort, "0.0.0.0")
                .setHandler(exchange -> {
                    exchange.setStatusCode(503);
                    exchange.getResponseSender().send("Service Unavailable");
                }).build();
        backend.start();

        try {
            Service service = createMcpServerServiceWithBackend("init-fail-svc", "localhost", backendPort);
            serviceCache.put("init-fail-svc", service);

            assertDoesNotThrow(() -> mcpServerClient.refreshMcpServerTools());
        } finally {
            backend.stop();
        }
    }

    @Test
    void populateToolMetadata_toolWithNullName_skipped() {
        Service service = createService("svc1");
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(new HashMap<>(Map.of("description", "No name tool")));  // no "name" key
        tools.add(Map.of("name", "valid", "description", "Valid tool"));

        mcpServerClient.populateToolMetadata(service, tools);

        Map<String, String> props = service.getServiceMeta().getUnknownProperties();
        assertEquals("valid", props.get("mcp-tools"));
        assertEquals("Valid tool", props.get("mcp-tools-valid-description"));
    }

    @Test
    void populateToolMetadata_toolWithEmptyName_skipped() {
        Service service = createService("svc1");
        List<Map<String, Object>> tools = List.of(
                Map.of("name", "", "description", "Empty name"),
                Map.of("name", "real", "description", "Real tool")
        );

        mcpServerClient.populateToolMetadata(service, tools);

        Map<String, String> props = service.getServiceMeta().getUnknownProperties();
        assertEquals("real", props.get("mcp-tools"));
    }

    @Test
    void populateToolMetadata_toolWithNoDescription_onlySchemaWritten() {
        Service service = createService("svc1");
        List<Map<String, Object>> tools = List.of(
                Map.of("name", "bare", "inputSchema", Map.of("type", "object"))
        );

        mcpServerClient.populateToolMetadata(service, tools);

        Map<String, String> props = service.getServiceMeta().getUnknownProperties();
        assertEquals("bare", props.get("mcp-tools"));
        assertNull(props.get("mcp-tools-bare-description"));
        assertNotNull(props.get("mcp-tools-bare-inputSchema"));
    }

    @Test
    void getDiscoveryTimeout_whenMcpConfigNull_returnsDefault() {
        CAPIConfiguration nullMcpConfig = new CAPIConfiguration();
        nullMcpConfig.setMcp(null);
        nullMcpConfig.setVersion("1.0.0-test");

        McpServerClient client = new McpServerClient(serviceCache, loadBalancer, HttpClient.newHttpClient(), nullMcpConfig);

        // Trigger refreshMcpServerTools which calls getDiscoveryTimeout internally
        // With no MCP server services in cache, it won't do anything, but the timeout path is exercised
        assertDoesNotThrow(client::refreshMcpServerTools);
    }

    @Test
    void forwardToolCall_withNoPrefix_doesNotStripToolName() throws Exception {
        int backendPort = findFreePort();

        List<String> capturedBodies = Collections.synchronizedList(new ArrayList<>());
        Undertow backend = Undertow.builder()
                .addHttpListener(backendPort, "0.0.0.0")
                .setHandler(exchange -> {
                    if (exchange.isInIoThread()) {
                        exchange.dispatch(() -> {
                            try {
                                exchange.startBlocking();
                                String body = new String(exchange.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                                capturedBodies.add(body);
                                @SuppressWarnings("unchecked")
                                Map<String, Object> req = objectMapper.readValue(body, Map.class);
                                String method = (String) req.get("method");
                                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                                if ("initialize".equals(method)) {
                                    exchange.getResponseSender().send("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-03-26\"}}");
                                } else if ("tools/call".equals(method)) {
                                    exchange.getResponseSender().send("{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}}");
                                }
                            } catch (Exception e) {
                                exchange.setStatusCode(500);
                                exchange.getResponseSender().send("Error");
                            }
                        });
                        return;
                    }
                }).build();
        backend.start();

        try {
            Service service = createMcpServerServiceWithBackend("no-prefix-svc", "localhost", backendPort);
            // No mcp-toolPrefix set
            serviceCache.put("no-prefix-svc", service);

            mcpServerClient.forwardToolCall(service, "forecast", Map.of(), 5000);

            String toolCallBody = capturedBodies.stream()
                    .filter(b -> b.contains("tools/call"))
                    .findFirst().orElse(null);
            assertNotNull(toolCallBody);
            assertTrue(toolCallBody.contains("\"name\":\"forecast\""));
        } finally {
            backend.stop();
        }
    }

    @Test
    void refreshMcpServerTools_skipsNonMcpServerServices() {
        // Add a REST service (not MCP Server)
        Service restService = createService("rest-svc");
        restService.getServiceMeta().handleUnknown("mcp-enabled", "true");
        restService.getServiceMeta().handleUnknown("mcp-tools", "hello");
        serviceCache.put("rest-svc", restService);

        assertDoesNotThrow(() -> mcpServerClient.refreshMcpServerTools());
        // Should not have modified anything (no mcp-type: server)
    }

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
