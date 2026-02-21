package io.surisoft.capi.undertow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.schema.*;
import io.surisoft.capi.service.LocalMcpSessionStore;
import io.surisoft.capi.service.McpSessionStore;
import io.surisoft.capi.service.McpToolRegistry;
import io.surisoft.capi.utils.HttpUtils;
import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.surisoft.capi.service.OpaService;
import io.undertow.Undertow;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class McpGatewayTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private McpGateway mcpGateway;
    private Cache<String, Service> serviceCache;
    private Cache<String, McpSession> sessionCache;
    private int port;

    @BeforeEach
    void setUp() {
        port = 19200 + (int) (Math.random() * 100);
        serviceCache = new Cache2kBuilder<String, Service>() {}
                .name("testMcpGw-svc-" + System.currentTimeMillis())
                .eternal(true)
                .entryCapacity(100)
                .storeByReference(true)
                .build();

        sessionCache = new Cache2kBuilder<String, McpSession>() {}
                .name("testMcpGw-sess-" + System.currentTimeMillis())
                .expireAfterWrite(1800000, TimeUnit.MILLISECONDS)
                .entryCapacity(100)
                .storeByReference(true)
                .build();

        McpToolRegistry registry = new McpToolRegistry(serviceCache);
        McpSessionStore sessionStore = new LocalMcpSessionStore(sessionCache);

        CAPIConfiguration config = new CAPIConfiguration();
        CAPIConfiguration.Mcp mcp = new CAPIConfiguration.Mcp();
        mcp.setEnabled(true);
        mcp.setPort(port);
        mcp.setSessionTtl(1800000);
        mcp.setToolCallTimeout(30000);
        config.setMcp(mcp);
        config.setVersion("1.0.0-test");

        // OAuth2 disabled for tests
        CAPIConfiguration.Oauth2 oauth2 = new CAPIConfiguration.Oauth2();
        oauth2.setEnabled(false);
        config.setOauth2(oauth2);

        HttpUtils httpUtils = new HttpUtils(null, null);

        mcpGateway = new McpGateway(
                port, null, registry, httpUtils, null,
                HttpClient.newHttpClient(), sessionStore, config
        );
        mcpGateway.start();
    }

    @AfterEach
    void tearDown() {
        if (mcpGateway != null) {
            mcpGateway.stop();
        }
        serviceCache.close();
        sessionCache.close();
    }

    @Test
    void healthEndpoint_returnsUp() throws Exception {
        HttpResponse<String> response = sendGet("/mcp/health");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("UP"));
    }

    @Test
    void getRequest_returns405() throws Exception {
        HttpResponse<String> response = sendGet("/mcp");
        assertEquals(405, response.statusCode());
    }

    @Test
    void invalidJson_returnsParseError() throws Exception {
        HttpResponse<String> response = sendPost("/mcp", "not json");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("-32700"));
    }

    @Test
    void invalidJsonRpcVersion_returnsError() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("jsonrpc", "1.0", "method", "ping", "id", 1));
        HttpResponse<String> response = sendPost("/mcp", body);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("-32600"));
    }

    @Test
    void missingMethod_returnsError() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1));
        HttpResponse<String> response = sendPost("/mcp", body);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("-32600"));
    }

    @Test
    void unknownMethod_returnsMethodNotFound() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "unknown/thing", "id", 1));
        HttpResponse<String> response = sendPost("/mcp", body);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("-32601"));
    }

    @Test
    void ping_returnsSuccess() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "ping", "id", 1));
        HttpResponse<String> response = sendPost("/mcp", body);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"result\""));
        assertFalse(response.body().contains("\"error\":{"));
    }

    @Test
    void initialize_returnsSessionAndCapabilities() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "initialize", "id", 1));
        HttpResponse<String> response = sendPost("/mcp", body);
        assertEquals(200, response.statusCode());

        String responseBody = response.body();
        assertTrue(responseBody.contains("protocolVersion"));
        assertTrue(responseBody.contains("capabilities"));
        assertTrue(responseBody.contains("serverInfo"));
        assertTrue(responseBody.contains("CAPI MCP Gateway"));

        String sessionId = response.headers().firstValue("Mcp-Session-Id").orElse(null);
        assertNotNull(sessionId);
        assertFalse(sessionId.isEmpty());
    }

    @Test
    void toolsList_withoutSession_returnsError() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "tools/list", "id", 2));
        HttpResponse<String> response = sendPost("/mcp", body);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Mcp-Session-Id"));
    }

    @Test
    void toolsList_withValidSession_returnsTools() throws Exception {
        // Initialize first
        String initBody = objectMapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "initialize", "id", 1));
        HttpResponse<String> initResponse = sendPost("/mcp", initBody);
        String sessionId = initResponse.headers().firstValue("Mcp-Session-Id").orElseThrow();

        // Add an MCP-enabled service
        Service service = new Service();
        service.setId("test-svc");
        service.setName("test-svc");
        ServiceMeta meta = new ServiceMeta();
        meta.handleUnknown("mcp.enabled", "true");
        meta.handleUnknown("mcp.tools", "hello");
        meta.handleUnknown("mcp.tools.hello.description", "Says hello");
        service.setServiceMeta(meta);
        serviceCache.put("test-svc", service);

        // List tools
        String listBody = objectMapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "tools/list", "id", 2));
        HttpResponse<String> response = sendPostWithSession("/mcp", listBody, sessionId);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("hello"));
        assertTrue(response.body().contains("Says hello"));
    }

    @Test
    void toolsList_withExpiredSession_returnsError() throws Exception {
        // Manually create an expired session
        McpSession expired = new McpSession("expired-sess", "client", 1);
        expired.setLastAccessedAt(System.currentTimeMillis() - 1000);
        sessionCache.put("expired-sess", expired);

        String body = objectMapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "tools/list", "id", 2));
        HttpResponse<String> response = sendPostWithSession("/mcp", body, "expired-sess");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("expired"));
    }

    @Test
    void toolsCall_missingParams_returnsError() throws Exception {
        String sessionId = initializeSession();

        String body = objectMapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "tools/call", "id", 3));
        HttpResponse<String> response = sendPostWithSession("/mcp", body, sessionId);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("-32602"));
    }

    @Test
    void toolsCall_toolNotFound_returnsError() throws Exception {
        String sessionId = initializeSession();

        String body = objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "method", "tools/call", "id", 3,
                "params", Map.of("name", "nonexistent", "arguments", Map.of())
        ));
        HttpResponse<String> response = sendPostWithSession("/mcp", body, sessionId);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Tool not found"));
    }

    @Test
    void toolsCall_withStringParams_returnsInvalidParams() throws Exception {
        String sessionId = initializeSession();
        // params is a string, not a map - should trigger ClassCastException path
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"id\":4,\"params\":\"bad\"}";
        HttpResponse<String> response = sendPostWithSession("/mcp", body, sessionId);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("-32602"));
    }

    @Test
    void toolsCall_withMissingToolName_returnsError() throws Exception {
        String sessionId = initializeSession();
        String body = objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "method", "tools/call", "id", 5,
                "params", Map.of("arguments", Map.of())
        ));
        HttpResponse<String> response = sendPostWithSession("/mcp", body, sessionId);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Missing tool name"));
    }

    @Test
    void toolsCall_toolWithNoBackend_returnsError() throws Exception {
        String sessionId = initializeSession();

        // Service with MCP enabled but no mappings
        Service service = new Service();
        service.setId("no-backend");
        service.setName("no-backend");
        ServiceMeta meta = new ServiceMeta();
        meta.handleUnknown("mcp.enabled", "true");
        meta.handleUnknown("mcp.tools", "empty");
        service.setServiceMeta(meta);
        serviceCache.put("no-backend", service);

        String body = objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "method", "tools/call", "id", 6,
                "params", Map.of("name", "empty", "arguments", Map.of())
        ));
        HttpResponse<String> response = sendPostWithSession("/mcp", body, sessionId);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("No backend available"));
    }

    @Test
    void toolsCall_backendReturnsSuccess() throws Exception {
        // Start a mock backend
        int backendPort = 19500 + (int) (Math.random() * 100);
        Undertow backend = Undertow.builder()
                .addHttpListener(backendPort, "0.0.0.0")
                .setHandler(exchange -> {
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    exchange.setStatusCode(StatusCodes.OK);
                    exchange.getResponseSender().send("{\"greeting\":\"hello world\"}");
                }).build();
        backend.start();

        try {
            String sessionId = initializeSession();

            Service service = createMcpServiceWithBackend("backend-svc", "greet", "localhost", backendPort);
            serviceCache.put("backend-svc", service);

            String body = objectMapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0", "method", "tools/call", "id", 7,
                    "params", Map.of("name", "greet", "arguments", Map.of("name", "world"))
            ));
            HttpResponse<String> response = sendPostWithSession("/mcp", body, sessionId);
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("hello world"));
            assertTrue(response.body().contains("content"));
        } finally {
            backend.stop();
        }
    }

    @Test
    void toolsCall_backendReturns500_returnsError() throws Exception {
        int backendPort = 19600 + (int) (Math.random() * 100);
        Undertow backend = Undertow.builder()
                .addHttpListener(backendPort, "0.0.0.0")
                .setHandler(exchange -> {
                    exchange.setStatusCode(500);
                    exchange.getResponseSender().send("Internal Server Error");
                }).build();
        backend.start();

        try {
            String sessionId = initializeSession();

            Service service = createMcpServiceWithBackend("err-svc", "fail", "localhost", backendPort);
            serviceCache.put("err-svc", service);

            String body = objectMapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0", "method", "tools/call", "id", 8,
                    "params", Map.of("name", "fail", "arguments", Map.of())
            ));
            HttpResponse<String> response = sendPostWithSession("/mcp", body, sessionId);
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("Backend returned status 500"));
        } finally {
            backend.stop();
        }
    }

    @Test
    void toolsCall_backendConnectionRefused_returnsError() throws Exception {
        String sessionId = initializeSession();

        // Point to a port where nothing is listening
        Service service = createMcpServiceWithBackend("dead-svc", "dead", "localhost", 19999);
        serviceCache.put("dead-svc", service);

        String body = objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "method", "tools/call", "id", 9,
                "params", Map.of("name", "dead", "arguments", Map.of())
        ));
        HttpResponse<String> response = sendPostWithSession("/mcp", body, sessionId);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Backend connection failed"));
    }

    @Test
    void toolsList_withBadInputSchemaJson_fallsBackToDefault() throws Exception {
        String sessionId = initializeSession();

        Service service = new Service();
        service.setId("bad-schema-svc");
        service.setName("bad-schema-svc");
        ServiceMeta meta = new ServiceMeta();
        meta.handleUnknown("mcp.enabled", "true");
        meta.handleUnknown("mcp.tools", "broken");
        meta.handleUnknown("mcp.tools.broken.inputSchema", "not-valid-json{{{");
        service.setServiceMeta(meta);
        serviceCache.put("bad-schema-svc", service);

        String listBody = objectMapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "tools/list", "id", 10));
        HttpResponse<String> response = sendPostWithSession("/mcp", listBody, sessionId);
        assertEquals(200, response.statusCode());
        // Should fall back to {"type":"object"}
        assertTrue(response.body().contains("broken"));
        assertTrue(response.body().contains("object"));
    }

    @Test
    void toolsCall_withEmptySessionHeader_returnsError() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "tools/call", "id", 11,
                "params", Map.of("name", "test")));
        HttpResponse<String> response = sendPostWithSession("/mcp", body, "");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Mcp-Session-Id"));
    }

    @Test
    void toolsCall_withNonexistentSession_returnsError() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "tools/call", "id", 12,
                "params", Map.of("name", "test")));
        HttpResponse<String> response = sendPostWithSession("/mcp", body, "does-not-exist");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Session expired or not found"));
    }

    @Test
    void initialize_withOauth2Enabled_noAuthHeader_returnsUnauthorized() throws Exception {
        // Create a gateway with OAuth2 enabled
        int oauthPort = 19700 + (int) (Math.random() * 100);
        Cache<String, Service> oauthSvcCache = new Cache2kBuilder<String, Service>() {}
                .name("testOauthSvc-" + System.currentTimeMillis())
                .eternal(true).entryCapacity(100).storeByReference(true).build();
        Cache<String, McpSession> oauthSessCache = new Cache2kBuilder<String, McpSession>() {}
                .name("testOauthSess-" + System.currentTimeMillis())
                .expireAfterWrite(60000, TimeUnit.MILLISECONDS).entryCapacity(100).storeByReference(true).build();

        CAPIConfiguration oauthConfig = new CAPIConfiguration();
        CAPIConfiguration.Mcp mcp = new CAPIConfiguration.Mcp();
        mcp.setEnabled(true);
        mcp.setPort(oauthPort);
        mcp.setSessionTtl(60000);
        mcp.setToolCallTimeout(5000);
        oauthConfig.setMcp(mcp);
        oauthConfig.setVersion("1.0.0-test");
        CAPIConfiguration.Oauth2 oauth2 = new CAPIConfiguration.Oauth2();
        oauth2.setEnabled(true);
        oauthConfig.setOauth2(oauth2);

        McpGateway oauthGw = new McpGateway(oauthPort, null,
                new McpToolRegistry(oauthSvcCache), new HttpUtils(null, null),
                null, HttpClient.newHttpClient(), new LocalMcpSessionStore(oauthSessCache), oauthConfig);
        oauthGw.start();

        try {
            HttpClient client = HttpClient.newHttpClient();
            String body = objectMapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "initialize", "id", 1));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + oauthPort + "/mcp"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(401, response.statusCode());
            assertTrue(response.body().contains("Authorization required"));
        } finally {
            oauthGw.stop();
            oauthSvcCache.close();
            oauthSessCache.close();
        }
    }

    @Test
    void toolsCall_withNullArguments_sendsEmptyBody() throws Exception {
        int backendPort = 19800 + (int) (Math.random() * 100);
        Undertow backend = Undertow.builder()
                .addHttpListener(backendPort, "0.0.0.0")
                .setHandler(exchange -> {
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    exchange.setStatusCode(StatusCodes.OK);
                    exchange.getResponseSender().send("{\"ok\":true}");
                }).build();
        backend.start();

        try {
            String sessionId = initializeSession();
            Service service = createMcpServiceWithBackend("null-args-svc", "noargs", "localhost", backendPort);
            serviceCache.put("null-args-svc", service);

            // params with name but no arguments key
            String body = objectMapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0", "method", "tools/call", "id", 13,
                    "params", Map.of("name", "noargs")
            ));
            HttpResponse<String> response = sendPostWithSession("/mcp", body, sessionId);
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("ok"));
        } finally {
            backend.stop();
        }
    }

    @Test
    void toolsList_emptyCache_returnsEmptyToolsList() throws Exception {
        String sessionId = initializeSession();
        String body = objectMapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "tools/list", "id", 14));
        HttpResponse<String> response = sendPostWithSession("/mcp", body, sessionId);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"tools\":[]"));
    }

    @Test
    void stop_whenServerNull_doesNotThrow() {
        McpGateway gw = new McpGateway(19399, null, new McpToolRegistry(serviceCache),
                new HttpUtils(null, null), null, HttpClient.newHttpClient(),
                new LocalMcpSessionStore(sessionCache), new CAPIConfiguration());
        assertDoesNotThrow(gw::stop);
    }

    @Test
    void close_delegatesToStop() {
        McpGateway gw = new McpGateway(19398, null, new McpToolRegistry(serviceCache),
                new HttpUtils(null, null), null, HttpClient.newHttpClient(),
                new LocalMcpSessionStore(sessionCache), new CAPIConfiguration());
        assertDoesNotThrow(gw::close);
    }

    @Test
    void getToolRegistry_returnsRegistry() {
        assertNotNull(mcpGateway.getToolRegistry());
    }

    @Test
    void getSessionStore_returnsStore() {
        assertNotNull(mcpGateway.getSessionStore());
    }

    private String initializeSession() throws Exception {
        String initBody = objectMapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "initialize", "id", 1));
        HttpResponse<String> initResponse = sendPost("/mcp", initBody);
        return initResponse.headers().firstValue("Mcp-Session-Id").orElseThrow();
    }

    private HttpResponse<String> sendGet(String path) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendPost(String path, String body) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendPostWithSession(String path, String body, String sessionId) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Mcp-Session-Id", sessionId)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private Service createMcpServiceWithBackend(String id, String toolName, String hostname, int backendPort) {
        Service service = new Service();
        service.setId(id);
        service.setName(id);
        ServiceMeta meta = new ServiceMeta();
        meta.handleUnknown("mcp.enabled", "true");
        meta.handleUnknown("mcp.tools", toolName);
        meta.setScheme("http");
        service.setServiceMeta(meta);

        Mapping mapping = new Mapping();
        mapping.setHostname(hostname);
        mapping.setPort(backendPort);
        mapping.setRootContext("/");
        service.setMappingList(Set.of(mapping));

        return service;
    }
}
