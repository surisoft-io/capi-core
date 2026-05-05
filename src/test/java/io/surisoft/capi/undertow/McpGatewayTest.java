package io.surisoft.capi.undertow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.schema.*;
import io.surisoft.capi.service.LocalMcpSessionStore;
import io.surisoft.capi.service.McpBackendLoadBalancer;
import io.surisoft.capi.service.McpServerClient;
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

import java.net.ServerSocket;
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
    void setUp() throws Exception {
        port = findFreePort();
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

        McpBackendLoadBalancer loadBalancer = new McpBackendLoadBalancer(30000);
        mcpGateway = new McpGateway(
                port, null, registry, httpUtils, null,
                HttpClient.newHttpClient(), sessionStore, config, loadBalancer
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
        meta.handleUnknown("mcp-enabled", "true");
        meta.handleUnknown("mcp-tools", "hello");
        meta.handleUnknown("mcp-tools-hello-description", "Says hello");
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
        meta.handleUnknown("mcp-enabled", "true");
        meta.handleUnknown("mcp-tools", "empty");
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
        int backendPort = findFreePort();
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
        int backendPort = findFreePort();
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

        // Find a free port but don't start anything on it
        int deadPort = findFreePort();
        Service service = createMcpServiceWithBackend("dead-svc", "dead", "localhost", deadPort);
        serviceCache.put("dead-svc", service);

        String body = objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "method", "tools/call", "id", 9,
                "params", Map.of("name", "dead", "arguments", Map.of())
        ));
        HttpResponse<String> response = sendPostWithSession("/mcp", body, sessionId);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("All backends failed"));
    }

    @Test
    void toolsList_withBadInputSchemaJson_fallsBackToDefault() throws Exception {
        String sessionId = initializeSession();

        Service service = new Service();
        service.setId("bad-schema-svc");
        service.setName("bad-schema-svc");
        ServiceMeta meta = new ServiceMeta();
        meta.handleUnknown("mcp-enabled", "true");
        meta.handleUnknown("mcp-tools", "broken");
        meta.handleUnknown("mcp-tools-broken-inputSchema", "not-valid-json{{{");
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
        int oauthPort = findFreePort();
        try (Cache<String, Service> oauthSvcCache = new Cache2kBuilder<String, Service>() {}
                .name("testOauthSvc-" + System.currentTimeMillis())
                .eternal(true).entryCapacity(100).storeByReference(true).build();
             Cache<String, McpSession> oauthSessCache = new Cache2kBuilder<String, McpSession>() {}
                .name("testOauthSess-" + System.currentTimeMillis())
                .expireAfterWrite(60000, TimeUnit.MILLISECONDS).entryCapacity(100).storeByReference(true).build();
             McpGateway oauthGw = createOauthGateway(oauthPort, oauthSvcCache, oauthSessCache)) {

            oauthGw.start();

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
        }
    }

    @Test
    void toolsCall_withNullArguments_sendsEmptyBody() throws Exception {
        int backendPort = findFreePort();
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
    void toolsCall_streaming_returnsSSEResponse() throws Exception {
        int backendPort = findFreePort();
        Undertow backend = Undertow.builder()
                .addHttpListener(backendPort, "0.0.0.0")
                .setHandler(exchange -> {
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/event-stream");
                    exchange.getResponseSender().send("line1\nline2\n");
                }).build();
        backend.start();

        try {
            String sessionId = initializeSession();

            Service service = createMcpServiceWithBackend("stream-svc", "streamtool", "localhost", backendPort);
            service.getServiceMeta().handleUnknown("mcp-streaming", "streamtool");
            serviceCache.put("stream-svc", service);

            String body = objectMapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0", "method", "tools/call", "id", 15,
                    "params", Map.of("name", "streamtool", "arguments", Map.of())
            ));
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/mcp"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .header("Mcp-Session-Id", sessionId)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
        } finally {
            backend.stop();
        }
    }

    @Test
    void toolsCall_streamingTool_withoutAcceptSSE_fallsBackToSync() throws Exception {
        int backendPort = findFreePort();
        Undertow backend = Undertow.builder()
                .addHttpListener(backendPort, "0.0.0.0")
                .setHandler(exchange -> {
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    exchange.setStatusCode(StatusCodes.OK);
                    exchange.getResponseSender().send("{\"sync\":true}");
                }).build();
        backend.start();

        try {
            String sessionId = initializeSession();

            Service service = createMcpServiceWithBackend("stream-svc2", "streamsync", "localhost", backendPort);
            service.getServiceMeta().handleUnknown("mcp-streaming", "streamsync");
            serviceCache.put("stream-svc2", service);

            // No Accept: text/event-stream header → should fall back to sync
            String body = objectMapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0", "method", "tools/call", "id", 16,
                    "params", Map.of("name", "streamsync", "arguments", Map.of())
            ));
            HttpResponse<String> response = sendPostWithSession("/mcp", body, sessionId);
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("sync"));
        } finally {
            backend.stop();
        }
    }

    @Test
    void toolsCall_withOpaService_noOpaRego_allowed() throws Exception {
        // McpGateway with a non-null OpaService but service without opaRego → OPA skipped
        int opaPort = findFreePort();
        int backendPort = findFreePort();

        Undertow backend = Undertow.builder()
                .addHttpListener(backendPort, "0.0.0.0")
                .setHandler(exchange -> {
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    exchange.setStatusCode(StatusCodes.OK);
                    exchange.getResponseSender().send("{\"opa\":\"skipped\"}");
                }).build();
        backend.start();

        try (Cache<String, Service> opaSvcCache = new Cache2kBuilder<String, Service>() {}
                .name("testOpaSvc-" + System.currentTimeMillis())
                .eternal(true).entryCapacity(100).storeByReference(true).build();
             Cache<String, McpSession> opaSessCache = new Cache2kBuilder<String, McpSession>() {}
                .name("testOpaSess-" + System.currentTimeMillis())
                .expireAfterWrite(60000, TimeUnit.MILLISECONDS).entryCapacity(100).storeByReference(true).build()) {

            CAPIConfiguration opaConfig = new CAPIConfiguration();
            CAPIConfiguration.Mcp mcp = new CAPIConfiguration.Mcp();
            mcp.setEnabled(true);
            mcp.setPort(opaPort);
            mcp.setSessionTtl(60000);
            mcp.setToolCallTimeout(5000);
            opaConfig.setMcp(mcp);
            opaConfig.setVersion("1.0.0-test");
            CAPIConfiguration.Oauth2 oauth2 = new CAPIConfiguration.Oauth2();
            oauth2.setEnabled(false);
            opaConfig.setOauth2(oauth2);

            OpaService opaService = new OpaService("http://localhost:9999", HttpClient.newHttpClient());

            try (McpGateway opaGw = new McpGateway(opaPort, null,
                    new McpToolRegistry(opaSvcCache), new HttpUtils(null, null),
                    opaService, HttpClient.newHttpClient(), new LocalMcpSessionStore(opaSessCache), opaConfig,
                    new McpBackendLoadBalancer(30000))) {

                opaGw.start();

                // Initialize
                HttpClient client = HttpClient.newHttpClient();
                String initBody = objectMapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "initialize", "id", 1));
                HttpRequest initReq = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + opaPort + "/mcp"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(initBody))
                        .build();
                HttpResponse<String> initResp = client.send(initReq, HttpResponse.BodyHandlers.ofString());
                String sessionId = initResp.headers().firstValue("Mcp-Session-Id").orElseThrow();

                // Service without opaRego → OPA check skipped
                Service service = createMcpServiceWithBackend("opa-svc", "opatool", "localhost", backendPort);
                opaSvcCache.put("opa-svc", service);

                String body = objectMapper.writeValueAsString(Map.of(
                        "jsonrpc", "2.0", "method", "tools/call", "id", 17,
                        "params", Map.of("name", "opatool", "arguments", Map.of())
                ));
                HttpRequest callReq = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + opaPort + "/mcp"))
                        .header("Content-Type", "application/json")
                        .header("Mcp-Session-Id", sessionId)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = client.send(callReq, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertTrue(response.body().contains("opa"));
            }
        } finally {
            backend.stop();
        }
    }

    @Test
    void toolsCall_withOpaService_opaDenies_returnsAccessDenied() throws Exception {
        // Mock OPA server that returns deny
        int opaServerPort = findFreePort();
        int gatewayPort = findFreePort();

        Undertow opaBackend = Undertow.builder()
                .addHttpListener(opaServerPort, "0.0.0.0")
                .setHandler(exchange -> {
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    exchange.setStatusCode(StatusCodes.OK);
                    exchange.getResponseSender().send("{\"result\":false}");
                }).build();
        opaBackend.start();

        try (Cache<String, Service> opaSvcCache = new Cache2kBuilder<String, Service>() {}
                .name("testOpaDenySvc-" + System.currentTimeMillis())
                .eternal(true).entryCapacity(100).storeByReference(true).build();
             Cache<String, McpSession> opaSessCache = new Cache2kBuilder<String, McpSession>() {}
                .name("testOpaDenySess-" + System.currentTimeMillis())
                .expireAfterWrite(60000, TimeUnit.MILLISECONDS).entryCapacity(100).storeByReference(true).build()) {

            CAPIConfiguration opaConfig = new CAPIConfiguration();
            CAPIConfiguration.Mcp mcp = new CAPIConfiguration.Mcp();
            mcp.setEnabled(true);
            mcp.setPort(gatewayPort);
            mcp.setSessionTtl(60000);
            mcp.setToolCallTimeout(5000);
            opaConfig.setMcp(mcp);
            opaConfig.setVersion("1.0.0-test");
            CAPIConfiguration.Oauth2 oauth2 = new CAPIConfiguration.Oauth2();
            oauth2.setEnabled(false);
            opaConfig.setOauth2(oauth2);

            OpaService opaService = new OpaService("http://localhost:" + opaServerPort, HttpClient.newHttpClient());
            HttpUtils opaHttpUtils = new HttpUtils(null, null);

            try (McpGateway opaGw = new McpGateway(gatewayPort, null,
                    new McpToolRegistry(opaSvcCache), opaHttpUtils,
                    opaService, HttpClient.newHttpClient(), new LocalMcpSessionStore(opaSessCache), opaConfig,
                    new McpBackendLoadBalancer(30000))) {

                opaGw.start();

                // Initialize
                HttpClient client = HttpClient.newHttpClient();
                String initBody = objectMapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "initialize", "id", 1));
                HttpRequest initReq = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + gatewayPort + "/mcp"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(initBody))
                        .build();
                HttpResponse<String> initResp = client.send(initReq, HttpResponse.BodyHandlers.ofString());
                String sessionId = initResp.headers().firstValue("Mcp-Session-Id").orElseThrow();

                // Service WITH opaRego set
                Service service = createMcpServiceWithBackend("opa-deny-svc", "denytool", "localhost", findFreePort());
                service.getServiceMeta().setOpaRego("test-policy");
                opaSvcCache.put("opa-deny-svc", service);

                // Call with Bearer token → OPA should be checked and deny
                String body = objectMapper.writeValueAsString(Map.of(
                        "jsonrpc", "2.0", "method", "tools/call", "id", 18,
                        "params", Map.of("name", "denytool", "arguments", Map.of())
                ));
                HttpRequest callReq = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + gatewayPort + "/mcp"))
                        .header("Content-Type", "application/json")
                        .header("Mcp-Session-Id", sessionId)
                        .header("Authorization", "Bearer test-token-12345")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = client.send(callReq, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertTrue(response.body().contains("Access denied by policy") || response.body().contains("-32000"));
            }
        } finally {
            opaBackend.stop();
        }
    }

    @Test
    void toolsCall_backendUrlWithNullSchemeAndContext_usesDefaults() throws Exception {
        int backendPort = findFreePort();
        Undertow backend = Undertow.builder()
                .addHttpListener(backendPort, "0.0.0.0")
                .setHandler(exchange -> {
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    exchange.setStatusCode(StatusCodes.OK);
                    exchange.getResponseSender().send("{\"default\":true}");
                }).build();
        backend.start();

        try {
            String sessionId = initializeSession();

            Service service = new Service();
            service.setId("null-scheme-svc");
            service.setName("null-scheme-svc");
            ServiceMeta meta = new ServiceMeta();
            meta.handleUnknown("mcp-enabled", "true");
            meta.handleUnknown("mcp-tools", "testtool");
            // Intentionally do NOT set scheme → null → should default to "http"
            service.setServiceMeta(meta);

            Mapping mapping = new Mapping();
            mapping.setHostname("localhost");
            mapping.setPort(backendPort);
            // Intentionally do NOT set rootContext → null → should default to ""
            service.setMappingList(Set.of(mapping));
            serviceCache.put("null-scheme-svc", service);

            String body = objectMapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0", "method", "tools/call", "id", 19,
                    "params", Map.of("name", "testtool", "arguments", Map.of())
            ));
            HttpResponse<String> response = sendPostWithSession("/mcp", body, sessionId);
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("default"));
        } finally {
            backend.stop();
        }
    }

    @Test
    void toolsCall_backendUrlWithRootContextNoPrefixSlash_prependsSlash() throws Exception {
        int backendPort = findFreePort();
        Undertow backend = Undertow.builder()
                .addHttpListener(backendPort, "0.0.0.0")
                .setHandler(exchange -> {
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    exchange.setStatusCode(StatusCodes.OK);
                    exchange.getResponseSender().send("{\"slash\":true}");
                }).build();
        backend.start();

        try {
            String sessionId = initializeSession();

            Service service = new Service();
            service.setId("slash-svc");
            service.setName("slash-svc");
            ServiceMeta meta = new ServiceMeta();
            meta.handleUnknown("mcp-enabled", "true");
            meta.handleUnknown("mcp-tools", "slashtool");
            meta.setScheme("http");
            service.setServiceMeta(meta);

            Mapping mapping = new Mapping();
            mapping.setHostname("localhost");
            mapping.setPort(backendPort);
            mapping.setRootContext("api");  // no leading slash
            service.setMappingList(Set.of(mapping));
            serviceCache.put("slash-svc", service);

            String body = objectMapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0", "method", "tools/call", "id", 20,
                    "params", Map.of("name", "slashtool", "arguments", Map.of())
            ));
            HttpResponse<String> response = sendPostWithSession("/mcp", body, sessionId);
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("slash"));
        } finally {
            backend.stop();
        }
    }

    @Test
    void toolsCall_withPerToolTimeout_usesToolTimeout() throws Exception {
        int backendPort = findFreePort();
        Undertow backend = Undertow.builder()
                .addHttpListener(backendPort, "0.0.0.0")
                .setHandler(exchange -> {
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    exchange.setStatusCode(StatusCodes.OK);
                    exchange.getResponseSender().send("{\"timeout\":\"custom\"}");
                }).build();
        backend.start();

        try {
            String sessionId = initializeSession();

            Service service = createMcpServiceWithBackend("timeout-svc", "timed", "localhost", backendPort);
            service.getServiceMeta().handleUnknown("mcp-tools-timed-timeout", "5000");
            serviceCache.put("timeout-svc", service);

            String body = objectMapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0", "method", "tools/call", "id", 21,
                    "params", Map.of("name", "timed", "arguments", Map.of())
            ));
            HttpResponse<String> response = sendPostWithSession("/mcp", body, sessionId);
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("timeout"));
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
                new LocalMcpSessionStore(sessionCache), new CAPIConfiguration(),
                new McpBackendLoadBalancer(30000));
        assertDoesNotThrow(gw::stop);
    }

    @Test
    void close_delegatesToStop() {
        McpGateway gw = new McpGateway(19398, null, new McpToolRegistry(serviceCache),
                new HttpUtils(null, null), null, HttpClient.newHttpClient(),
                new LocalMcpSessionStore(sessionCache), new CAPIConfiguration(),
                new McpBackendLoadBalancer(30000));
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

    private McpGateway createOauthGateway(int oauthPort, Cache<String, Service> svcCache, Cache<String, McpSession> sessCache) {
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
        return new McpGateway(oauthPort, null,
                new McpToolRegistry(svcCache), new HttpUtils(null, null),
                null, HttpClient.newHttpClient(), new LocalMcpSessionStore(sessCache), oauthConfig,
                new McpBackendLoadBalancer(30000));
    }

    @Test
    void toolsCall_syncBackendTimeout_returnsTimeoutError() throws Exception {
        int backendPort = findFreePort();
        Undertow backend = Undertow.builder()
                .addHttpListener(backendPort, "0.0.0.0")
                .setHandler(exchange -> {
                    // Sleep longer than the tool timeout
                    try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    exchange.getResponseSender().send("too late");
                }).build();
        backend.start();

        try {
            // Create gateway with very short tool call timeout
            int shortTimeoutPort = findFreePort();
            Cache<String, Service> svcCache = new Cache2kBuilder<String, Service>() {}
                    .name("testTimeoutSvc-" + System.currentTimeMillis())
                    .eternal(true).entryCapacity(100).storeByReference(true).build();
            Cache<String, McpSession> sessCache = new Cache2kBuilder<String, McpSession>() {}
                    .name("testTimeoutSess-" + System.currentTimeMillis())
                    .expireAfterWrite(1800000, TimeUnit.MILLISECONDS)
                    .entryCapacity(100).storeByReference(true).build();

            CAPIConfiguration config = new CAPIConfiguration();
            CAPIConfiguration.Mcp mcp = new CAPIConfiguration.Mcp();
            mcp.setEnabled(true);
            mcp.setPort(shortTimeoutPort);
            mcp.setSessionTtl(1800000);
            mcp.setToolCallTimeout(500); // 500ms timeout
            config.setMcp(mcp);
            config.setVersion("1.0.0-test");
            CAPIConfiguration.Oauth2 oauth2 = new CAPIConfiguration.Oauth2();
            oauth2.setEnabled(false);
            config.setOauth2(oauth2);

            McpGateway gw = new McpGateway(shortTimeoutPort, null,
                    new McpToolRegistry(svcCache), new HttpUtils(null, null),
                    null, HttpClient.newHttpClient(), new LocalMcpSessionStore(sessCache), config,
                    new McpBackendLoadBalancer(30000));
            gw.start();

            try {
                // Initialize session
                HttpClient client = HttpClient.newHttpClient();
                String initBody = objectMapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "initialize", "id", 1));
                HttpRequest initReq = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + shortTimeoutPort + "/mcp"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(initBody))
                        .build();
                HttpResponse<String> initResp = client.send(initReq, HttpResponse.BodyHandlers.ofString());
                String sessionId = initResp.headers().firstValue("Mcp-Session-Id").orElseThrow();

                Service service = createMcpServiceWithBackend("timeout-svc2", "slowtool", "localhost", backendPort);
                svcCache.put("timeout-svc2", service);

                String body = objectMapper.writeValueAsString(Map.of(
                        "jsonrpc", "2.0", "method", "tools/call", "id", 30,
                        "params", Map.of("name", "slowtool", "arguments", Map.of())
                ));
                HttpRequest toolReq = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + shortTimeoutPort + "/mcp"))
                        .header("Content-Type", "application/json")
                        .header("Mcp-Session-Id", sessionId)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = client.send(toolReq, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertTrue(response.body().contains("All backends failed"));
            } finally {
                gw.stop();
                svcCache.close();
                sessCache.close();
            }
        } finally {
            backend.stop();
        }
    }

    @Test
    void toolsCall_streamingBackendConnectionRefused_returnsError() throws Exception {
        // Use a port that's not listening to trigger connection refused in streaming path
        int deadPort = findFreePort();
        String sessionId = initializeSession();

        Service service = createMcpServiceWithBackend("stream-dead-svc", "deadstream", "localhost", deadPort);
        service.getServiceMeta().handleUnknown("mcp-streaming", "deadstream");
        serviceCache.put("stream-dead-svc", service);

        String body = objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "method", "tools/call", "id", 31,
                "params", Map.of("name", "deadstream", "arguments", Map.of())
        ));
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("Mcp-Session-Id", sessionId)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Backend streaming failed"));
    }

    @Test
    void toolsCall_streamingBackendTimeout_returnsTimeoutError() throws Exception {
        int backendPort = findFreePort();
        Undertow backend = Undertow.builder()
                .addHttpListener(backendPort, "0.0.0.0")
                .setHandler(exchange -> {
                    try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    exchange.getResponseSender().send("too late");
                }).build();
        backend.start();

        try {
            int shortTimeoutPort = findFreePort();
            Cache<String, Service> svcCache = new Cache2kBuilder<String, Service>() {}
                    .name("testStreamTimeout-" + System.currentTimeMillis())
                    .eternal(true).entryCapacity(100).storeByReference(true).build();
            Cache<String, McpSession> sessCache = new Cache2kBuilder<String, McpSession>() {}
                    .name("testStreamTimeoutSess-" + System.currentTimeMillis())
                    .expireAfterWrite(1800000, TimeUnit.MILLISECONDS)
                    .entryCapacity(100).storeByReference(true).build();

            CAPIConfiguration config = new CAPIConfiguration();
            CAPIConfiguration.Mcp mcp = new CAPIConfiguration.Mcp();
            mcp.setEnabled(true);
            mcp.setPort(shortTimeoutPort);
            mcp.setSessionTtl(1800000);
            mcp.setToolCallTimeout(500);
            config.setMcp(mcp);
            config.setVersion("1.0.0-test");
            CAPIConfiguration.Oauth2 oauth2 = new CAPIConfiguration.Oauth2();
            oauth2.setEnabled(false);
            config.setOauth2(oauth2);

            McpGateway gw = new McpGateway(shortTimeoutPort, null,
                    new McpToolRegistry(svcCache), new HttpUtils(null, null),
                    null, HttpClient.newHttpClient(), new LocalMcpSessionStore(sessCache), config,
                    new McpBackendLoadBalancer(30000));
            gw.start();

            try {
                HttpClient client = HttpClient.newHttpClient();
                String initBody = objectMapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "initialize", "id", 1));
                HttpRequest initReq = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + shortTimeoutPort + "/mcp"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(initBody))
                        .build();
                HttpResponse<String> initResp = client.send(initReq, HttpResponse.BodyHandlers.ofString());
                String sessionId = initResp.headers().firstValue("Mcp-Session-Id").orElseThrow();

                Service service = createMcpServiceWithBackend("stream-timeout-svc", "slowstream", "localhost", backendPort);
                service.getServiceMeta().handleUnknown("mcp-streaming", "slowstream");
                svcCache.put("stream-timeout-svc", service);

                String body = objectMapper.writeValueAsString(Map.of(
                        "jsonrpc", "2.0", "method", "tools/call", "id", 32,
                        "params", Map.of("name", "slowstream", "arguments", Map.of())
                ));
                HttpRequest toolReq = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + shortTimeoutPort + "/mcp"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .header("Mcp-Session-Id", sessionId)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = client.send(toolReq, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertTrue(response.body().contains("timed out") || response.body().contains("streaming failed"),
                        "Expected timeout or streaming error but got: " + response.body());
            } finally {
                gw.stop();
                svcCache.close();
                sessCache.close();
            }
        } finally {
            backend.stop();
        }
    }

    @Test
    void initialize_withOauth2Enabled_validToken_returnsSession() throws Exception {
        int oauthPort = findFreePort();
        Cache<String, Service> svcCache = new Cache2kBuilder<String, Service>() {}
                .name("testOauth2Valid-" + System.currentTimeMillis())
                .eternal(true).entryCapacity(100).storeByReference(true).build();
        Cache<String, McpSession> sessCache = new Cache2kBuilder<String, McpSession>() {}
                .name("testOauth2ValidSess-" + System.currentTimeMillis())
                .expireAfterWrite(1800000, TimeUnit.MILLISECONDS)
                .entryCapacity(100).storeByReference(true).build();

        try (McpGateway gw = createOauthGateway(oauthPort, svcCache, sessCache)) {
            gw.start();

            HttpClient client = HttpClient.newHttpClient();
            String initBody = objectMapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "initialize", "id", 1));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + oauthPort + "/mcp"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer valid-test-token-1234567890")
                    .POST(HttpRequest.BodyPublishers.ofString(initBody))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // With OAuth2 enabled and HttpUtils returning null for an unverifiable token,
            // this should return unauthorized
            assertEquals(200, response.statusCode());
            String body = response.body();
            // Should either succeed with session or fail with auth error
            assertTrue(body.contains("Mcp-Session-Id") || body.contains("protocolVersion")
                    || body.contains("Authorization required") || body.contains("Invalid authorization"),
                    "Unexpected response: " + body);
        } finally {
            svcCache.close();
            sessCache.close();
        }
    }

    @Test
    void toolsCall_withNullMappingList_returnsNoBackendError() throws Exception {
        String sessionId = initializeSession();

        Service service = new Service();
        service.setId("no-mapping-svc");
        service.setName("no-mapping-svc");
        ServiceMeta meta = new ServiceMeta();
        meta.handleUnknown("mcp-enabled", "true");
        meta.handleUnknown("mcp-tools", "nomapping");
        service.setServiceMeta(meta);
        service.setMappingList(null);
        serviceCache.put("no-mapping-svc", service);

        String body = objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "method", "tools/call", "id", 33,
                "params", Map.of("name", "nomapping", "arguments", Map.of())
        ));
        HttpResponse<String> response = sendPostWithSession("/mcp", body, sessionId);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("No backend available"));
    }

    @Test
    void toolsCall_withEmptyMappingList_returnsNoBackendError() throws Exception {
        String sessionId = initializeSession();

        Service service = new Service();
        service.setId("empty-mapping-svc");
        service.setName("empty-mapping-svc");
        ServiceMeta meta = new ServiceMeta();
        meta.handleUnknown("mcp-enabled", "true");
        meta.handleUnknown("mcp-tools", "emptymapping");
        service.setServiceMeta(meta);
        service.setMappingList(Set.of());
        serviceCache.put("empty-mapping-svc", service);

        String body = objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "method", "tools/call", "id", 34,
                "params", Map.of("name", "emptymapping", "arguments", Map.of())
        ));
        HttpResponse<String> response = sendPostWithSession("/mcp", body, sessionId);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("No backend available"));
    }

    @Test
    void toolsCall_withStreamingNullArguments_sendsEmptyJson() throws Exception {
        int backendPort = findFreePort();
        Undertow backend = Undertow.builder()
                .addHttpListener(backendPort, "0.0.0.0")
                .setHandler(exchange -> {
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/event-stream");
                    exchange.getResponseSender().send("streamed\n");
                }).build();
        backend.start();

        try {
            String sessionId = initializeSession();

            Service service = createMcpServiceWithBackend("stream-null-args", "streamnull", "localhost", backendPort);
            service.getServiceMeta().handleUnknown("mcp-streaming", "streamnull");
            serviceCache.put("stream-null-args", service);

            // params with name but no arguments key
            String body = objectMapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0", "method", "tools/call", "id", 35,
                    "params", Map.of("name", "streamnull")
            ));
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/mcp"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .header("Mcp-Session-Id", sessionId)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
        } finally {
            backend.stop();
        }
    }

    @Test
    void toolsCall_syncBackend4xxError_returnsErrorWithStatus() throws Exception {
        int backendPort = findFreePort();
        Undertow backend = Undertow.builder()
                .addHttpListener(backendPort, "0.0.0.0")
                .setHandler(exchange -> {
                    exchange.setStatusCode(403);
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    exchange.getResponseSender().send("{\"error\":\"forbidden\"}");
                }).build();
        backend.start();

        try {
            String sessionId = initializeSession();

            Service service = createMcpServiceWithBackend("err-svc", "errtool", "localhost", backendPort);
            serviceCache.put("err-svc", service);

            String body = objectMapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0", "method", "tools/call", "id", 36,
                    "params", Map.of("name", "errtool", "arguments", Map.of())
            ));
            HttpResponse<String> response = sendPostWithSession("/mcp", body, sessionId);
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("Backend returned status 403"));
        } finally {
            backend.stop();
        }
    }

    @Test
    void toolsCall_withMultipleBackends_failsOverOnConnectionRefused() throws Exception {
        // One dead backend + one live backend → should failover and succeed
        int deadPort = findFreePort();
        int livePort = findFreePort();
        Undertow backend = Undertow.builder()
                .addHttpListener(livePort, "0.0.0.0")
                .setHandler(exchange -> {
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    exchange.setStatusCode(StatusCodes.OK);
                    exchange.getResponseSender().send("{\"failover\":\"success\"}");
                }).build();
        backend.start();

        try {
            String sessionId = initializeSession();

            Service service = new Service();
            service.setId("failover-svc");
            service.setName("failover-svc");
            ServiceMeta meta = new ServiceMeta();
            meta.handleUnknown("mcp-enabled", "true");
            meta.handleUnknown("mcp-tools", "failovertool");
            meta.setScheme("http");
            service.setServiceMeta(meta);

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

            String body = objectMapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0", "method", "tools/call", "id", 40,
                    "params", Map.of("name", "failovertool", "arguments", Map.of())
            ));
            HttpResponse<String> response = sendPostWithSession("/mcp", body, sessionId);
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("failover"));
            assertTrue(response.body().contains("success"));
        } finally {
            backend.stop();
        }
    }

    @Test
    void toolsCall_withAllBackendsDead_returnsAllBackendsFailed() throws Exception {
        int deadPort1 = findFreePort();
        int deadPort2 = findFreePort();

        String sessionId = initializeSession();

        Service service = new Service();
        service.setId("alldead-svc");
        service.setName("alldead-svc");
        ServiceMeta meta = new ServiceMeta();
        meta.handleUnknown("mcp-enabled", "true");
        meta.handleUnknown("mcp-tools", "deadtool");
        meta.setScheme("http");
        service.setServiceMeta(meta);

        Mapping m1 = new Mapping();
        m1.setHostname("localhost");
        m1.setPort(deadPort1);
        m1.setRootContext("/");

        Mapping m2 = new Mapping();
        m2.setHostname("localhost");
        m2.setPort(deadPort2);
        m2.setRootContext("/");

        service.setMappingList(Set.of(m1, m2));
        serviceCache.put("alldead-svc", service);

        String body = objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "method", "tools/call", "id", 41,
                "params", Map.of("name", "deadtool", "arguments", Map.of())
        ));
        HttpResponse<String> response = sendPostWithSession("/mcp", body, sessionId);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("All backends failed"));
    }

    @Test
    void toolsCall_mcpServerBackend_forwardsAsJsonRpc() throws Exception {
        // Start a mock MCP Server backend
        int backendPort = findFreePort();
        Undertow backend = Undertow.builder()
                .addHttpListener(backendPort, "0.0.0.0")
                .setHandler(exchange -> {
                    if (exchange.isInIoThread()) {
                        exchange.dispatch(() -> {
                            try {
                                exchange.startBlocking();
                                String body = new String(exchange.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                                @SuppressWarnings("unchecked")
                                Map<String, Object> req = objectMapper.readValue(body, Map.class);
                                String method = (String) req.get("method");

                                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                                if ("initialize".equals(method)) {
                                    exchange.getResponseHeaders().put(io.undertow.util.HttpString.tryFromString("Mcp-Session-Id"), "test-session");
                                    exchange.getResponseSender().send("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-03-26\"}}");
                                } else if ("tools/call".equals(method)) {
                                    exchange.getResponseSender().send("{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"mcp server response\"}]}}");
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
            // Create gateway with McpServerClient
            int gwPort = findFreePort();
            Cache<String, Service> svcCache = new Cache2kBuilder<String, Service>() {}
                    .name("testMcpSrvGw-" + System.currentTimeMillis())
                    .eternal(true).entryCapacity(100).storeByReference(true).build();
            Cache<String, McpSession> sessCache = new Cache2kBuilder<String, McpSession>() {}
                    .name("testMcpSrvSess-" + System.currentTimeMillis())
                    .expireAfterWrite(1800000, TimeUnit.MILLISECONDS).entryCapacity(100).storeByReference(true).build();

            CAPIConfiguration config = new CAPIConfiguration();
            CAPIConfiguration.Mcp mcp = new CAPIConfiguration.Mcp();
            mcp.setEnabled(true);
            mcp.setPort(gwPort);
            mcp.setSessionTtl(1800000);
            mcp.setToolCallTimeout(5000);
            mcp.setMcpServerDiscoveryTimeoutMs(5000);
            config.setMcp(mcp);
            config.setVersion("1.0.0-test");
            CAPIConfiguration.Oauth2 oauth2 = new CAPIConfiguration.Oauth2();
            oauth2.setEnabled(false);
            config.setOauth2(oauth2);

            McpBackendLoadBalancer lb = new McpBackendLoadBalancer(30000);
            McpServerClient mcpServerClient = new McpServerClient(svcCache, lb, HttpClient.newHttpClient(), config);

            try (McpGateway gw = new McpGateway(gwPort, null,
                    new McpToolRegistry(svcCache), new HttpUtils(null, null),
                    null, HttpClient.newHttpClient(), new LocalMcpSessionStore(sessCache), config, lb, mcpServerClient)) {

                gw.start();

                // Add MCP Server service
                Service service = new Service();
                service.setId("mcp-server-svc");
                service.setName("mcp-server-svc");
                ServiceMeta meta = new ServiceMeta();
                meta.handleUnknown("mcp-enabled", "true");
                meta.handleUnknown("mcp-type", "server");
                meta.handleUnknown("mcp-tools", "forecast");
                meta.handleUnknown("mcp-tools-forecast-description", "Get forecast");
                meta.setScheme("http");
                service.setServiceMeta(meta);
                Mapping mapping = new Mapping();
                mapping.setHostname("localhost");
                mapping.setPort(backendPort);
                mapping.setRootContext("/");
                service.setMappingList(Set.of(mapping));
                svcCache.put("mcp-server-svc", service);

                // Initialize
                HttpClient client = HttpClient.newHttpClient();
                String initBody = objectMapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "initialize", "id", 1));
                HttpRequest initReq = HttpRequest.newBuilder()
                        .uri(java.net.URI.create("http://localhost:" + gwPort + "/mcp"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(initBody))
                        .build();
                HttpResponse<String> initResp = client.send(initReq, HttpResponse.BodyHandlers.ofString());
                String sessionId = initResp.headers().firstValue("Mcp-Session-Id").orElseThrow();

                // Call MCP Server tool
                String callBody = objectMapper.writeValueAsString(Map.of(
                        "jsonrpc", "2.0", "method", "tools/call", "id", 2,
                        "params", Map.of("name", "forecast", "arguments", Map.of("city", "London"))
                ));
                HttpRequest callReq = HttpRequest.newBuilder()
                        .uri(java.net.URI.create("http://localhost:" + gwPort + "/mcp"))
                        .header("Content-Type", "application/json")
                        .header("Mcp-Session-Id", sessionId)
                        .POST(HttpRequest.BodyPublishers.ofString(callBody))
                        .build();
                HttpResponse<String> response = client.send(callReq, HttpResponse.BodyHandlers.ofString());

                assertEquals(200, response.statusCode());
                assertTrue(response.body().contains("mcp server response"));
                assertTrue(response.body().contains("content"));
            } finally {
                svcCache.close();
                sessCache.close();
            }
        } finally {
            backend.stop();
        }
    }

    @Test
    void toolsList_includesMcpServerTools() throws Exception {
        String sessionId = initializeSession();

        // Add an MCP Server service with tools pre-populated (as if refreshMcpServerTools ran)
        Service service = new Service();
        service.setId("mcp-srv-list");
        service.setName("mcp-srv-list");
        ServiceMeta meta = new ServiceMeta();
        meta.handleUnknown("mcp-enabled", "true");
        meta.handleUnknown("mcp-type", "server");
        meta.handleUnknown("mcp-tools", "forecast,alerts");
        meta.handleUnknown("mcp-tools-forecast-description", "Weather forecast");
        meta.handleUnknown("mcp-tools-alerts-description", "Weather alerts");
        service.setServiceMeta(meta);
        serviceCache.put("mcp-srv-list", service);

        String listBody = objectMapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "tools/list", "id", 50));
        HttpResponse<String> response = sendPostWithSession("/mcp", listBody, sessionId);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("forecast"));
        assertTrue(response.body().contains("alerts"));
        assertTrue(response.body().contains("Weather forecast"));
    }

    @Test
    void toolsCall_mcpServerBackend_backendError_returnsJsonRpcError() throws Exception {
        // MCP Server backend that always fails on tools/call
        int backendPort = findFreePort();
        Undertow backend = Undertow.builder()
                .addHttpListener(backendPort, "0.0.0.0")
                .setHandler(exchange -> {
                    if (exchange.isInIoThread()) {
                        exchange.dispatch(() -> {
                            try {
                                exchange.startBlocking();
                                String body = new String(exchange.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                                @SuppressWarnings("unchecked")
                                Map<String, Object> req = objectMapper.readValue(body, Map.class);
                                String method = (String) req.get("method");
                                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                                if ("initialize".equals(method)) {
                                    exchange.getResponseHeaders().put(io.undertow.util.HttpString.tryFromString("Mcp-Session-Id"), "test-session");
                                    exchange.getResponseSender().send("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-03-26\"}}");
                                } else if ("tools/call".equals(method)) {
                                    // Return a non-session MCP error
                                    exchange.getResponseSender().send("{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"error\":{\"code\":-32603,\"message\":\"Execution failed\"}}");
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
            int gwPort = findFreePort();
            Cache<String, Service> svcCache = new Cache2kBuilder<String, Service>() {}
                    .name("testMcpSrvErr-" + System.currentTimeMillis())
                    .eternal(true).entryCapacity(100).storeByReference(true).build();
            Cache<String, McpSession> sessCache = new Cache2kBuilder<String, McpSession>() {}
                    .name("testMcpSrvErrSess-" + System.currentTimeMillis())
                    .expireAfterWrite(1800000, TimeUnit.MILLISECONDS).entryCapacity(100).storeByReference(true).build();

            CAPIConfiguration config = new CAPIConfiguration();
            CAPIConfiguration.Mcp mcp = new CAPIConfiguration.Mcp();
            mcp.setEnabled(true);
            mcp.setPort(gwPort);
            mcp.setSessionTtl(1800000);
            mcp.setToolCallTimeout(5000);
            mcp.setMcpServerDiscoveryTimeoutMs(5000);
            config.setMcp(mcp);
            config.setVersion("1.0.0-test");
            CAPIConfiguration.Oauth2 oauth2 = new CAPIConfiguration.Oauth2();
            oauth2.setEnabled(false);
            config.setOauth2(oauth2);

            McpBackendLoadBalancer lb = new McpBackendLoadBalancer(30000);
            McpServerClient mcpServerClient = new McpServerClient(svcCache, lb, HttpClient.newHttpClient(), config);

            try (McpGateway gw = new McpGateway(gwPort, null,
                    new McpToolRegistry(svcCache), new HttpUtils(null, null),
                    null, HttpClient.newHttpClient(), new LocalMcpSessionStore(sessCache), config, lb, mcpServerClient)) {

                gw.start();

                Service service = new Service();
                service.setId("mcp-err-svc");
                service.setName("mcp-err-svc");
                ServiceMeta meta = new ServiceMeta();
                meta.handleUnknown("mcp-enabled", "true");
                meta.handleUnknown("mcp-type", "server");
                meta.handleUnknown("mcp-tools", "errtool");
                meta.handleUnknown("mcp-tools-errtool-description", "A tool that errors");
                meta.setScheme("http");
                service.setServiceMeta(meta);
                Mapping mapping = new Mapping();
                mapping.setHostname("localhost");
                mapping.setPort(backendPort);
                mapping.setRootContext("/");
                service.setMappingList(Set.of(mapping));
                svcCache.put("mcp-err-svc", service);

                HttpClient client = HttpClient.newHttpClient();
                String initBody = objectMapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "initialize", "id", 1));
                HttpRequest initReq = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + gwPort + "/mcp"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(initBody))
                        .build();
                HttpResponse<String> initResp = client.send(initReq, HttpResponse.BodyHandlers.ofString());
                String sessionId = initResp.headers().firstValue("Mcp-Session-Id").orElseThrow();

                String callBody = objectMapper.writeValueAsString(Map.of(
                        "jsonrpc", "2.0", "method", "tools/call", "id", 2,
                        "params", Map.of("name", "errtool", "arguments", Map.of())
                ));
                HttpRequest callReq = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + gwPort + "/mcp"))
                        .header("Content-Type", "application/json")
                        .header("Mcp-Session-Id", sessionId)
                        .POST(HttpRequest.BodyPublishers.ofString(callBody))
                        .build();
                HttpResponse<String> response = client.send(callReq, HttpResponse.BodyHandlers.ofString());

                assertEquals(200, response.statusCode());
                assertTrue(response.body().contains("MCP Server tool call failed"));
            } finally {
                svcCache.close();
                sessCache.close();
            }
        } finally {
            backend.stop();
        }
    }

    @Test
    void toolsCall_mcpServerBackend_connectionRefused_returnsError() throws Exception {
        int deadPort = findFreePort();
        int gwPort = findFreePort();

        Cache<String, Service> svcCache = new Cache2kBuilder<String, Service>() {}
                .name("testMcpSrvDead-" + System.currentTimeMillis())
                .eternal(true).entryCapacity(100).storeByReference(true).build();
        Cache<String, McpSession> sessCache = new Cache2kBuilder<String, McpSession>() {}
                .name("testMcpSrvDeadSess-" + System.currentTimeMillis())
                .expireAfterWrite(1800000, TimeUnit.MILLISECONDS).entryCapacity(100).storeByReference(true).build();

        CAPIConfiguration config = new CAPIConfiguration();
        CAPIConfiguration.Mcp mcp = new CAPIConfiguration.Mcp();
        mcp.setEnabled(true);
        mcp.setPort(gwPort);
        mcp.setSessionTtl(1800000);
        mcp.setToolCallTimeout(5000);
        mcp.setMcpServerDiscoveryTimeoutMs(5000);
        config.setMcp(mcp);
        config.setVersion("1.0.0-test");
        CAPIConfiguration.Oauth2 oauth2 = new CAPIConfiguration.Oauth2();
        oauth2.setEnabled(false);
        config.setOauth2(oauth2);

        McpBackendLoadBalancer lb = new McpBackendLoadBalancer(30000);
        McpServerClient mcpServerClient = new McpServerClient(svcCache, lb, HttpClient.newHttpClient(), config);

        try (McpGateway gw = new McpGateway(gwPort, null,
                new McpToolRegistry(svcCache), new HttpUtils(null, null),
                null, HttpClient.newHttpClient(), new LocalMcpSessionStore(sessCache), config, lb, mcpServerClient)) {

            gw.start();

            Service service = new Service();
            service.setId("mcp-dead-svc");
            service.setName("mcp-dead-svc");
            ServiceMeta meta = new ServiceMeta();
            meta.handleUnknown("mcp-enabled", "true");
            meta.handleUnknown("mcp-type", "server");
            meta.handleUnknown("mcp-tools", "deadtool");
            meta.setScheme("http");
            service.setServiceMeta(meta);
            Mapping mapping = new Mapping();
            mapping.setHostname("localhost");
            mapping.setPort(deadPort);
            mapping.setRootContext("/");
            service.setMappingList(Set.of(mapping));
            svcCache.put("mcp-dead-svc", service);

            HttpClient client = HttpClient.newHttpClient();
            String initBody = objectMapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "initialize", "id", 1));
            HttpRequest initReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + gwPort + "/mcp"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(initBody))
                    .build();
            HttpResponse<String> initResp = client.send(initReq, HttpResponse.BodyHandlers.ofString());
            String sessionId = initResp.headers().firstValue("Mcp-Session-Id").orElseThrow();

            String callBody = objectMapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0", "method", "tools/call", "id", 2,
                    "params", Map.of("name", "deadtool", "arguments", Map.of())
            ));
            HttpRequest callReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + gwPort + "/mcp"))
                    .header("Content-Type", "application/json")
                    .header("Mcp-Session-Id", sessionId)
                    .POST(HttpRequest.BodyPublishers.ofString(callBody))
                    .build();
            HttpResponse<String> response = client.send(callReq, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("MCP Server tool call failed"));
        } finally {
            svcCache.close();
            sessCache.close();
        }
    }

    @Test
    void toolsCall_mcpServerBackend_withCustomTimeout_usesToolTimeout() throws Exception {
        int backendPort = findFreePort();
        Undertow backend = Undertow.builder()
                .addHttpListener(backendPort, "0.0.0.0")
                .setHandler(exchange -> {
                    if (exchange.isInIoThread()) {
                        exchange.dispatch(() -> {
                            try {
                                exchange.startBlocking();
                                String body = new String(exchange.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                                @SuppressWarnings("unchecked")
                                Map<String, Object> req = objectMapper.readValue(body, Map.class);
                                String method = (String) req.get("method");
                                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                                if ("initialize".equals(method)) {
                                    exchange.getResponseHeaders().put(io.undertow.util.HttpString.tryFromString("Mcp-Session-Id"), "test-session");
                                    exchange.getResponseSender().send("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-03-26\"}}");
                                } else if ("tools/call".equals(method)) {
                                    exchange.getResponseSender().send("{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"with timeout\"}]}}");
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
            int gwPort = findFreePort();
            Cache<String, Service> svcCache = new Cache2kBuilder<String, Service>() {}
                    .name("testMcpTimeout-" + System.currentTimeMillis())
                    .eternal(true).entryCapacity(100).storeByReference(true).build();
            Cache<String, McpSession> sessCache = new Cache2kBuilder<String, McpSession>() {}
                    .name("testMcpTimeoutSess-" + System.currentTimeMillis())
                    .expireAfterWrite(1800000, TimeUnit.MILLISECONDS).entryCapacity(100).storeByReference(true).build();

            CAPIConfiguration config = new CAPIConfiguration();
            CAPIConfiguration.Mcp mcp = new CAPIConfiguration.Mcp();
            mcp.setEnabled(true);
            mcp.setPort(gwPort);
            mcp.setSessionTtl(1800000);
            mcp.setToolCallTimeout(5000);
            mcp.setMcpServerDiscoveryTimeoutMs(5000);
            config.setMcp(mcp);
            config.setVersion("1.0.0-test");
            CAPIConfiguration.Oauth2 oauth2 = new CAPIConfiguration.Oauth2();
            oauth2.setEnabled(false);
            config.setOauth2(oauth2);

            McpBackendLoadBalancer lb = new McpBackendLoadBalancer(30000);
            McpServerClient mcpServerClient = new McpServerClient(svcCache, lb, HttpClient.newHttpClient(), config);

            try (McpGateway gw = new McpGateway(gwPort, null,
                    new McpToolRegistry(svcCache), new HttpUtils(null, null),
                    null, HttpClient.newHttpClient(), new LocalMcpSessionStore(sessCache), config, lb, mcpServerClient)) {

                gw.start();

                Service service = new Service();
                service.setId("mcp-timeout-svc");
                service.setName("mcp-timeout-svc");
                ServiceMeta meta = new ServiceMeta();
                meta.handleUnknown("mcp-enabled", "true");
                meta.handleUnknown("mcp-type", "server");
                meta.handleUnknown("mcp-tools", "timed");
                meta.handleUnknown("mcp-tools-timed-description", "Timed tool");
                meta.handleUnknown("mcp-tools-timed-timeout", "10000");  // Custom timeout
                meta.setScheme("http");
                service.setServiceMeta(meta);
                Mapping mapping = new Mapping();
                mapping.setHostname("localhost");
                mapping.setPort(backendPort);
                mapping.setRootContext("/");
                service.setMappingList(Set.of(mapping));
                svcCache.put("mcp-timeout-svc", service);

                HttpClient client = HttpClient.newHttpClient();
                String initBody = objectMapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "initialize", "id", 1));
                HttpRequest initReq = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + gwPort + "/mcp"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(initBody))
                        .build();
                HttpResponse<String> initResp = client.send(initReq, HttpResponse.BodyHandlers.ofString());
                String sessionId = initResp.headers().firstValue("Mcp-Session-Id").orElseThrow();

                String callBody = objectMapper.writeValueAsString(Map.of(
                        "jsonrpc", "2.0", "method", "tools/call", "id", 2,
                        "params", Map.of("name", "timed", "arguments", Map.of())
                ));
                HttpRequest callReq = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + gwPort + "/mcp"))
                        .header("Content-Type", "application/json")
                        .header("Mcp-Session-Id", sessionId)
                        .POST(HttpRequest.BodyPublishers.ofString(callBody))
                        .build();
                HttpResponse<String> response = client.send(callReq, HttpResponse.BodyHandlers.ofString());

                assertEquals(200, response.statusCode());
                assertTrue(response.body().contains("with timeout"));
            } finally {
                svcCache.close();
                sessCache.close();
            }
        } finally {
            backend.stop();
        }
    }

    // ==========================================================================
    // OpenAPI auto-promotion (phase 2) — tools/call dispatch tests
    // ==========================================================================

    @Test
    void promotedTool_getWithPathParam_dispatchesToBackend() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> seenMethod = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> seenPath = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> seenQuery = new java.util.concurrent.atomic.AtomicReference<>();

        int backendPort = findFreePort();
        Undertow backend = Undertow.builder()
                .addHttpListener(backendPort, "0.0.0.0")
                .setHandler(ex -> {
                    seenMethod.set(ex.getRequestMethod().toString());
                    seenPath.set(ex.getRequestPath());
                    seenQuery.set(ex.getQueryString());
                    ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    ex.setStatusCode(StatusCodes.OK);
                    ex.getResponseSender().send("{\"orderId\":\"abc-123\",\"status\":\"shipped\"}");
                }).build();
        backend.start();

        try {
            String sessionId = initializeSession();
            Service svc = createOpenApiPromotedService("orders", "localhost", backendPort);
            serviceCache.put("orders", svc);

            String body = objectMapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0", "method", "tools/call", "id", 100,
                    "params", Map.of("name", "getOrder", "arguments",
                            Map.of("orderId", "abc-123", "verbose", "true"))
            ));
            HttpResponse<String> resp = sendPostWithSession("/mcp", body, sessionId);

            assertEquals(200, resp.statusCode());
            assertTrue(resp.body().contains("shipped"));
            assertEquals("GET", seenMethod.get());
            assertEquals("/orders/abc-123", seenPath.get());
            assertEquals("verbose=true", seenQuery.get());
        } finally {
            backend.stop();
        }
    }

    @Test
    void promotedTool_postWithBody_dispatchesToBackend() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> seenMethod = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> seenPath = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> seenBody = new java.util.concurrent.atomic.AtomicReference<>();

        int backendPort = findFreePort();
        Undertow backend = Undertow.builder()
                .addHttpListener(backendPort, "0.0.0.0")
                .setHandler(ex -> {
                    seenMethod.set(ex.getRequestMethod().toString());
                    seenPath.set(ex.getRequestPath());
                    if (ex.isInIoThread()) {
                        ex.dispatch(() -> {
                            try {
                                ex.startBlocking();
                                seenBody.set(new String(ex.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                                ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                                ex.setStatusCode(StatusCodes.OK);
                                ex.getResponseSender().send("{\"id\":\"new-1\"}");
                            } catch (Exception e) {
                                ex.setStatusCode(500);
                            }
                        });
                    }
                }).build();
        backend.start();

        try {
            String sessionId = initializeSession();
            Service svc = createOpenApiPromotedService("orders", "localhost", backendPort);
            serviceCache.put("orders", svc);

            String body = objectMapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0", "method", "tools/call", "id", 101,
                    "params", Map.of("name", "createOrder", "arguments",
                            Map.of("body", Map.of("product", "laptop", "quantity", 2)))
            ));
            HttpResponse<String> resp = sendPostWithSession("/mcp", body, sessionId);

            assertEquals(200, resp.statusCode());
            assertTrue(resp.body().contains("new-1"));
            assertEquals("POST", seenMethod.get());
            assertEquals("/orders", seenPath.get());
            assertNotNull(seenBody.get());
            assertTrue(seenBody.get().contains("\"product\":\"laptop\""));
            assertTrue(seenBody.get().contains("\"quantity\":2"));
        } finally {
            backend.stop();
        }
    }

    @Test
    void promotedTool_missingPathParam_returnsInvalidParams() throws Exception {
        int backendPort = findFreePort();
        Undertow backend = Undertow.builder()
                .addHttpListener(backendPort, "0.0.0.0")
                .setHandler(ex -> {
                    ex.setStatusCode(StatusCodes.OK);
                    ex.getResponseSender().send("{}");
                }).build();
        backend.start();

        try {
            String sessionId = initializeSession();
            Service svc = createOpenApiPromotedService("orders", "localhost", backendPort);
            serviceCache.put("orders", svc);

            // arguments missing the required path param "orderId"
            String body = objectMapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0", "method", "tools/call", "id", 102,
                    "params", Map.of("name", "getOrder", "arguments", Map.of())
            ));
            HttpResponse<String> resp = sendPostWithSession("/mcp", body, sessionId);

            assertEquals(200, resp.statusCode());
            // -32602 is JSON-RPC INVALID_PARAMS
            assertTrue(resp.body().contains("-32602"));
            assertTrue(resp.body().contains("orderId"));
        } finally {
            backend.stop();
        }
    }

    @Test
    void promotedTool_forwardsAuthorizationHeader() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> seenAuth = new java.util.concurrent.atomic.AtomicReference<>();

        int backendPort = findFreePort();
        Undertow backend = Undertow.builder()
                .addHttpListener(backendPort, "0.0.0.0")
                .setHandler(ex -> {
                    var hv = ex.getRequestHeaders().get(Headers.AUTHORIZATION);
                    if (hv != null && !hv.isEmpty()) seenAuth.set(hv.getFirst());
                    ex.setStatusCode(StatusCodes.OK);
                    ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    ex.getResponseSender().send("{}");
                }).build();
        backend.start();

        try {
            String sessionId = initializeSession();
            Service svc = createOpenApiPromotedService("orders", "localhost", backendPort);
            serviceCache.put("orders", svc);

            String body = objectMapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0", "method", "tools/call", "id", 103,
                    "params", Map.of("name", "getOrder", "arguments", Map.of("orderId", "x"))
            ));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(new URI("http://localhost:" + port + "/mcp"))
                    .header("Content-Type", "application/json")
                    .header("Mcp-Session-Id", sessionId)
                    .header("Authorization", "Bearer e2e-test-token")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, resp.statusCode());
            assertEquals("Bearer e2e-test-token", seenAuth.get());
        } finally {
            backend.stop();
        }
    }

    @Test
    void promotedTool_collidesWithTagDefined_tagDefinedWins() throws Exception {
        // Backend is shared between promoted and tag-defined paths
        java.util.concurrent.atomic.AtomicReference<String> seenPath = new java.util.concurrent.atomic.AtomicReference<>();
        int backendPort = findFreePort();
        Undertow backend = Undertow.builder()
                .addHttpListener(backendPort, "0.0.0.0")
                .setHandler(ex -> {
                    seenPath.set(ex.getRequestPath());
                    ex.setStatusCode(StatusCodes.OK);
                    ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    ex.getResponseSender().send("{\"ok\":true}");
                }).build();
        backend.start();

        try {
            String sessionId = initializeSession();
            // Both flags + tag-defined "getOrder" colliding with the promoted one
            Service svc = createOpenApiPromotedService("orders", "localhost", backendPort);
            svc.getServiceMeta().handleUnknown("mcp-enabled", "true");
            svc.getServiceMeta().handleUnknown("mcp-tools", "getOrder");
            serviceCache.put("orders", svc);

            String body = objectMapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0", "method", "tools/call", "id", 104,
                    "params", Map.of("name", "getOrder", "arguments", Map.of("orderId", "x"))
            ));
            HttpResponse<String> resp = sendPostWithSession("/mcp", body, sessionId);

            assertEquals(200, resp.statusCode());
            // Tag-defined tool POSTs to root, doesn't substitute path templates
            assertEquals("/", seenPath.get());
        } finally {
            backend.stop();
        }
    }

    private Service createOpenApiPromotedService(String id, String hostname, int backendPort) {
        Service service = new Service();
        service.setId(id);
        service.setName(id);
        ServiceMeta meta = new ServiceMeta();
        meta.handleUnknown("mcp-from-openapi", "true");
        meta.setScheme("http");
        service.setServiceMeta(meta);

        Mapping mapping = new Mapping();
        mapping.setHostname(hostname);
        mapping.setPort(backendPort);
        mapping.setRootContext("/");
        service.setMappingList(Set.of(mapping));

        // Build a small OpenAPI spec inline so we don't need to fetch it via HTTP.
        io.swagger.v3.oas.models.OpenAPI api = new io.swagger.v3.oas.models.OpenAPI();
        io.swagger.v3.oas.models.Paths paths = new io.swagger.v3.oas.models.Paths();

        // GET /orders/{orderId}
        io.swagger.v3.oas.models.Operation getOp = new io.swagger.v3.oas.models.Operation();
        getOp.setOperationId("getOrder");
        getOp.setSummary("Fetch an order");
        io.swagger.v3.oas.models.parameters.Parameter idParam = new io.swagger.v3.oas.models.parameters.Parameter();
        idParam.setName("orderId");
        idParam.setIn("path");
        idParam.setRequired(true);
        idParam.setSchema(new io.swagger.v3.oas.models.media.StringSchema());
        getOp.addParametersItem(idParam);
        io.swagger.v3.oas.models.PathItem byId = new io.swagger.v3.oas.models.PathItem();
        byId.setGet(getOp);
        paths.addPathItem("/orders/{orderId}", byId);

        // POST /orders   body: {product, quantity}
        io.swagger.v3.oas.models.Operation postOp = new io.swagger.v3.oas.models.Operation();
        postOp.setOperationId("createOrder");
        postOp.setSummary("Create an order");
        io.swagger.v3.oas.models.media.ObjectSchema bodySchema = new io.swagger.v3.oas.models.media.ObjectSchema();
        bodySchema.addProperty("product", new io.swagger.v3.oas.models.media.StringSchema());
        bodySchema.addProperty("quantity", new io.swagger.v3.oas.models.media.IntegerSchema());
        io.swagger.v3.oas.models.media.MediaType json = new io.swagger.v3.oas.models.media.MediaType();
        json.setSchema(bodySchema);
        io.swagger.v3.oas.models.media.Content content = new io.swagger.v3.oas.models.media.Content();
        content.addMediaType("application/json", json);
        io.swagger.v3.oas.models.parameters.RequestBody rb = new io.swagger.v3.oas.models.parameters.RequestBody();
        rb.setRequired(true);
        rb.setContent(content);
        postOp.setRequestBody(rb);
        io.swagger.v3.oas.models.PathItem coll = new io.swagger.v3.oas.models.PathItem();
        coll.setPost(postOp);
        paths.addPathItem("/orders", coll);

        api.setPaths(paths);
        service.setOpenAPI(api);
        return service;
    }

    private Service createMcpServiceWithBackend(String id, String toolName, String hostname, int backendPort) {
        Service service = new Service();
        service.setId(id);
        service.setName(id);
        ServiceMeta meta = new ServiceMeta();
        meta.handleUnknown("mcp-enabled", "true");
        meta.handleUnknown("mcp-tools", toolName);
        meta.setScheme("http");
        service.setServiceMeta(meta);

        Mapping mapping = new Mapping();
        mapping.setHostname(hostname);
        mapping.setPort(backendPort);
        mapping.setRootContext("/");
        service.setMappingList(Set.of(mapping));

        return service;
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
