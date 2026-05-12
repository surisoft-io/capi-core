package io.surisoft.capi.undertow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.schema.Mapping;
import io.surisoft.capi.schema.McpSession;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import io.surisoft.capi.service.LocalMcpSessionStore;
import io.surisoft.capi.service.McpBackendLoadBalancer;
import io.surisoft.capi.service.McpPromptRegistry;
import io.surisoft.capi.service.McpResourceRegistry;
import io.surisoft.capi.service.McpServerClient;
import io.surisoft.capi.service.McpSessionStore;
import io.surisoft.capi.service.McpToolRegistry;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.HttpUtils;
import io.undertow.Undertow;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.StatusCodes;
import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the MCP resources/* and prompts/* passthrough.
 * Spins up a fake upstream MCP server that speaks JSON-RPC over the
 * Streamable HTTP transport and exercises CAPI's gateway against it.
 */
class McpGatewayResourcesPromptsTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private McpGateway mcpGateway;
    private Undertow upstream;
    private Cache<String, Service> serviceCache;
    private Cache<String, McpSession> sessionCache;
    private int gatewayPort;

    @BeforeEach
    void setUp() throws Exception {
        gatewayPort = freePort();
        int upstreamPort = freePort();

        upstream = startUpstream(upstreamPort);

        serviceCache = new Cache2kBuilder<String, Service>() {}
                .name("rp-svc-" + System.nanoTime())
                .eternal(true).entryCapacity(100).storeByReference(true).build();
        sessionCache = new Cache2kBuilder<String, McpSession>() {}
                .name("rp-sess-" + System.nanoTime())
                .expireAfterWrite(1800000, TimeUnit.MILLISECONDS)
                .entryCapacity(100).storeByReference(true).build();

        Service mathService = new Service();
        mathService.setId("math-mcp");
        mathService.setName("math-mcp");
        ServiceMeta meta = new ServiceMeta();
        meta.handleUnknown(Constants.MCP_META_ENABLED, "true");
        meta.handleUnknown(Constants.MCP_META_TYPE, "server");
        mathService.setServiceMeta(meta);
        Mapping mapping = new Mapping();
        mapping.setHostname("localhost");
        mapping.setPort(upstreamPort);
        mapping.setRootContext("/");
        mathService.setMappingList(Set.of(mapping));
        serviceCache.put("math-mcp", mathService);

        CAPIConfiguration config = new CAPIConfiguration();
        CAPIConfiguration.Mcp mcp = new CAPIConfiguration.Mcp();
        mcp.setEnabled(true);
        mcp.setPort(gatewayPort);
        mcp.setSessionTtl(1800000);
        mcp.setToolCallTimeout(5000);
        mcp.setMcpServerDiscoveryTimeoutMs(5000);
        config.setMcp(mcp);
        config.setVersion("test");
        CAPIConfiguration.Oauth2 oauth2 = new CAPIConfiguration.Oauth2();
        oauth2.setEnabled(false);
        config.setOauth2(oauth2);

        McpToolRegistry toolRegistry = new McpToolRegistry(serviceCache);
        McpSessionStore sessionStore = new LocalMcpSessionStore(sessionCache);
        McpBackendLoadBalancer lb = new McpBackendLoadBalancer(30000);
        HttpClient httpClient = HttpClient.newHttpClient();
        McpServerClient mcpServerClient = new McpServerClient(serviceCache, lb, httpClient, config);

        mcpGateway = new McpGateway(gatewayPort, null, toolRegistry, new HttpUtils(null, null),
                null, httpClient, sessionStore, config, lb, mcpServerClient);

        McpResourceRegistry resourceReg = new McpResourceRegistry(serviceCache, mcpServerClient);
        resourceReg.setDefaultTimeoutMs(5000);
        McpPromptRegistry promptReg = new McpPromptRegistry(serviceCache, mcpServerClient);
        promptReg.setDefaultTimeoutMs(5000);
        mcpGateway.setResourceRegistry(resourceReg);
        mcpGateway.setPromptRegistry(promptReg);

        mcpGateway.start();
    }

    @AfterEach
    void tearDown() {
        if (mcpGateway != null) mcpGateway.stop();
        if (upstream != null) upstream.stop();
        serviceCache.close();
        sessionCache.close();
    }

    @Test
    void initialize_advertisesResourcesAndPromptsCapabilities() throws Exception {
        var resp = sendRpc("initialize", null, null);
        assertEquals(200, resp.statusCode());
        var body = objectMapper.readValue(resp.body(), Map.class);
        var result = (Map<?, ?>) body.get("result");
        var caps = (Map<?, ?>) result.get("capabilities");
        assertNotNull(caps.get("resources"));
        assertNotNull(caps.get("prompts"));
    }

    @Test
    void resourcesList_aggregatesAndNamespaces() throws Exception {
        String sid = initSession();
        var resp = sendRpc("resources/list", null, sid);
        assertEquals(200, resp.statusCode());
        var body = objectMapper.readValue(resp.body(), Map.class);
        var result = (Map<?, ?>) body.get("result");
        var resources = (java.util.List<Map<String, Object>>) result.get("resources");
        assertEquals(2, resources.size());
        assertEquals("math-mcp:file:///docs/algebra.md", resources.get(0).get("uri"));
        assertEquals("math-mcp:function://reference", resources.get(1).get("uri"));
        assertEquals("Algebra docs", resources.get(0).get("name"));
    }

    @Test
    void resourcesRead_routesByNamespacePrefix() throws Exception {
        String sid = initSession();
        var resp = sendRpc("resources/read",
                Map.of("uri", "math-mcp:file:///docs/algebra.md"), sid);
        assertEquals(200, resp.statusCode());
        var body = objectMapper.readValue(resp.body(), Map.class);
        var result = (Map<?, ?>) body.get("result");
        assertNotNull(result.get("contents"));
        var contents = (java.util.List<Map<String, Object>>) result.get("contents");
        assertEquals("file:///docs/algebra.md", contents.get(0).get("uri"));
        assertTrue(((String) contents.get(0).get("text")).contains("polynomial"));
    }

    @Test
    void resourcesRead_unknownService_returnsInvalidParams() throws Exception {
        String sid = initSession();
        var resp = sendRpc("resources/read", Map.of("uri", "ghost:file://x"), sid);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("-32602"));
        assertTrue(resp.body().contains("Resource not found"));
    }

    @Test
    void resourcesRead_missingUriParam_returnsInvalidParams() throws Exception {
        String sid = initSession();
        var resp = sendRpc("resources/read", Map.of(), sid);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("-32602"));
        assertTrue(resp.body().contains("Missing 'uri'"));
    }

    @Test
    void promptsList_aggregatesAndNamespaces() throws Exception {
        String sid = initSession();
        var resp = sendRpc("prompts/list", null, sid);
        assertEquals(200, resp.statusCode());
        var body = objectMapper.readValue(resp.body(), Map.class);
        var result = (Map<?, ?>) body.get("result");
        var prompts = (java.util.List<Map<String, Object>>) result.get("prompts");
        assertEquals(1, prompts.size());
        assertEquals("math-mcp_explain_calculation", prompts.get(0).get("name"));
    }

    @Test
    void promptsGet_routesByNamespace() throws Exception {
        String sid = initSession();
        var resp = sendRpc("prompts/get",
                Map.of("name", "math-mcp_explain_calculation",
                       "arguments", Map.of("expression", "sqrt(144)")), sid);
        assertEquals(200, resp.statusCode());
        var body = objectMapper.readValue(resp.body(), Map.class);
        var result = (Map<?, ?>) body.get("result");
        var messages = (java.util.List<Map<String, Object>>) result.get("messages");
        assertEquals(1, messages.size());
        assertTrue(((String) ((Map<?, ?>) messages.get(0).get("content")).get("text")).contains("sqrt(144)"));
    }

    @Test
    void promptsGet_unknownName_returnsInvalidParams() throws Exception {
        String sid = initSession();
        var resp = sendRpc("prompts/get", Map.of("name", "nope_anything"), sid);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("-32602"));
        assertTrue(resp.body().contains("Prompt not found"));
    }

    // --- helpers -----------------------------------------------------------

    private String initSession() throws Exception {
        var resp = sendRpc("initialize", null, null);
        assertEquals(200, resp.statusCode());
        return resp.headers().firstValue("Mcp-Session-Id").orElseThrow();
    }

    private HttpResponse<String> sendRpc(String method, Object params, String sessionId) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("method", method);
        body.put("id", 1);
        if (params != null) body.put("params", params);
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + gatewayPort + "/mcp"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        if (sessionId != null) b.header("Mcp-Session-Id", sessionId);
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Fake upstream MCP server. Speaks just enough JSON-RPC to handle
     * initialize / resources/list / resources/read / prompts/list / prompts/get.
     */
    private Undertow startUpstream(int port) {
        AtomicInteger sessionCounter = new AtomicInteger();
        Undertow u = Undertow.builder()
                .addHttpListener(port, "0.0.0.0")
                .setHandler(ex -> {
                    if (ex.isInIoThread()) {
                        ex.dispatch(() -> handleUpstream(ex, sessionCounter));
                    }
                }).build();
        u.start();
        return u;
    }

    @SuppressWarnings("unchecked")
    private void handleUpstream(io.undertow.server.HttpServerExchange ex, AtomicInteger sessionCounter) {
        try {
            ex.startBlocking();
            String body = new String(ex.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            Map<String, Object> req = objectMapper.readValue(body, Map.class);
            String method = (String) req.get("method");
            Object id = req.get("id");

            ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");

            Map<String, Object> resp = new java.util.LinkedHashMap<>();
            resp.put("jsonrpc", "2.0");
            resp.put("id", id);

            switch (method) {
                case "initialize": {
                    String sid = "upstream-session-" + sessionCounter.incrementAndGet();
                    ex.getResponseHeaders().put(HttpString.tryFromString("Mcp-Session-Id"), sid);
                    resp.put("result", Map.of("protocolVersion", "2025-03-26",
                            "serverInfo", Map.of("name", "fake-math", "version", "1")));
                    break;
                }
                case "resources/list": {
                    resp.put("result", Map.of("resources", java.util.List.of(
                            Map.of("uri", "file:///docs/algebra.md", "name", "Algebra docs", "mimeType", "text/markdown"),
                            Map.of("uri", "function://reference", "name", "Function reference")
                    )));
                    break;
                }
                case "resources/read": {
                    Map<String, Object> params = (Map<String, Object>) req.get("params");
                    String uri = (String) params.get("uri");
                    resp.put("result", Map.of("contents", java.util.List.of(
                            Map.of("uri", uri, "mimeType", "text/markdown",
                                    "text", "Sample text about polynomial expressions for " + uri)
                    )));
                    break;
                }
                case "prompts/list": {
                    resp.put("result", Map.of("prompts", java.util.List.of(
                            Map.of("name", "explain_calculation",
                                    "description", "Explain a calculation step by step",
                                    "arguments", java.util.List.of(
                                            Map.of("name", "expression", "required", true)))
                    )));
                    break;
                }
                case "prompts/get": {
                    Map<String, Object> params = (Map<String, Object>) req.get("params");
                    Map<String, Object> args = (Map<String, Object>) params.get("arguments");
                    String expr = args != null ? String.valueOf(args.get("expression")) : "";
                    resp.put("result", Map.of("messages", java.util.List.of(
                            Map.of("role", "user", "content",
                                    Map.of("type", "text", "text", "Explain how to compute " + expr))
                    )));
                    break;
                }
                default:
                    resp.put("error", Map.of("code", -32601, "message", "Method not found: " + method));
            }

            ex.setStatusCode(StatusCodes.OK);
            ex.getResponseSender().send(objectMapper.writeValueAsString(resp));
        } catch (Exception e) {
            ex.setStatusCode(500);
            try {
                ex.getResponseSender().send("{\"error\":\"" + e.getMessage() + "\"}");
            } catch (Exception ignored) { /* ignore */ }
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    void disabledRegistries_resourcesListReturnsEmpty() throws Exception {
        // Replace the gateway with one that has no registries set.
        mcpGateway.stop();
        int port = freePort();
        var config = new CAPIConfiguration();
        var mcp = new CAPIConfiguration.Mcp();
        mcp.setEnabled(true); mcp.setPort(port); mcp.setSessionTtl(1800000); mcp.setToolCallTimeout(5000);
        config.setMcp(mcp); config.setVersion("test");
        var oa = new CAPIConfiguration.Oauth2(); oa.setEnabled(false); config.setOauth2(oa);
        var lb = new McpBackendLoadBalancer(30000);
        var gw = new McpGateway(port, null, new McpToolRegistry(serviceCache),
                new HttpUtils(null, null), null,
                HttpClient.newHttpClient(), new LocalMcpSessionStore(sessionCache), config, lb);
        gw.start();
        try {
            // initialize without going via main fixture
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/mcp"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"id\":1}"));
            var initResp = HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
            String sid = initResp.headers().firstValue("Mcp-Session-Id").orElseThrow();
            // capabilities should NOT include resources/prompts
            var initBody = objectMapper.readValue(initResp.body(), Map.class);
            var caps = (Map<?, ?>) ((Map<?, ?>) initBody.get("result")).get("capabilities");
            assertFalse(caps.containsKey("resources"));
            assertFalse(caps.containsKey("prompts"));

            // resources/list still answers (returns empty) for clients that try it
            HttpRequest.Builder lb2 = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/mcp"))
                    .header("Content-Type", "application/json")
                    .header("Mcp-Session-Id", sid)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"jsonrpc\":\"2.0\",\"method\":\"resources/list\",\"id\":2}"));
            var resp = HttpClient.newHttpClient().send(lb2.build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode());
            assertTrue(resp.body().contains("\"resources\":[]"));
        } finally {
            gw.stop();
        }
    }
}