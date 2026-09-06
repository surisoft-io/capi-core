package io.surisoft.capi.undertow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.schema.Mapping;
import io.surisoft.capi.schema.McpSession;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import io.surisoft.capi.service.LocalMcpSessionStore;
import io.surisoft.capi.service.McpBackendLoadBalancer;
import io.surisoft.capi.service.McpSessionStore;
import io.surisoft.capi.service.McpToolRegistry;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.HttpUtils;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The 2026-07-28 protocol surface, and the guarantee that 2025-03-26 clients are unaffected.
 *
 * <p>CAPI serves both revisions at once. Nearly every test here has a mirror-image assertion:
 * the new behaviour appears when a client declares the new version, and is <em>absent</em>
 * otherwise. That pairing is the point — the migration is only safe if the legacy path is
 * byte-for-byte what it was.
 */
class McpProtocol2026Test {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String V_NEW = Constants.MCP_PROTOCOL_VERSION_CURRENT;   // 2026-07-28
    private static final String V_OLD = Constants.MCP_PROTOCOL_VERSION;           // 2025-03-26

    private McpGateway mcpGateway;
    private Cache<String, Service> serviceCache;
    private Cache<String, McpSession> sessionCache;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        port = findFreePort();
        serviceCache = new Cache2kBuilder<String, Service>() {}
                .name("mcp2026-svc-" + System.nanoTime()).eternal(true)
                .entryCapacity(100).storeByReference(true).build();
        sessionCache = new Cache2kBuilder<String, McpSession>() {}
                .name("mcp2026-sess-" + System.nanoTime())
                .expireAfterWrite(1800000, TimeUnit.MILLISECONDS)
                .entryCapacity(100).storeByReference(true).build();

        McpToolRegistry registry = new McpToolRegistry(serviceCache);
        McpSessionStore sessionStore = new LocalMcpSessionStore(sessionCache);

        CAPIConfiguration config = new CAPIConfiguration();
        CAPIConfiguration.Mcp mcp = new CAPIConfiguration.Mcp();
        mcp.setEnabled(true);
        mcp.setPort(port);
        mcp.setSessionTtl(1800000);
        mcp.setToolCallTimeout(30000);
        config.setMcp(mcp);
        config.setVersion("2.22-test");
        config.setConsulCatalogDiscoverInterval(20000);

        CAPIConfiguration.Oauth2 oauth2 = new CAPIConfiguration.Oauth2();
        oauth2.setEnabled(false);
        config.setOauth2(oauth2);

        mcpGateway = new McpGateway(port, null, registry, new HttpUtils(null, null), null,
                HttpClient.newHttpClient(), sessionStore, config, new McpBackendLoadBalancer(30000));
        mcpGateway.start();
    }

    @AfterEach
    void tearDown() {
        if (mcpGateway != null) mcpGateway.stop();
        serviceCache.close();
        sessionCache.close();
    }

    // === server/discover — the one MUST we previously failed ===

    @Test
    @SuppressWarnings("unchecked")
    void serverDiscover_advertisesBothRevisions_newestFirst() throws Exception {
        Map<String, Object> result = resultOf(post(rpc("server/discover", null), null, null));

        List<String> versions = (List<String>) result.get("protocolVersions");
        assertEquals(List.of(V_NEW, V_OLD), versions, "newest first, so a client picks it by default");
        assertNotNull(result.get("capabilities"));
        assertEquals("2.22-test", ((Map<String, Object>) result.get("serverInfo")).get("version"));
    }

    @Test
    void serverDiscover_needsNoSessionAndNoHandshake() throws Exception {
        // The whole point: a client can learn our versions before committing to one.
        assertEquals(200, post(rpc("server/discover", null), null, null).statusCode());
    }

    // === version negotiation ===

    @Test
    void protocolVersion_takenFromMetaKey() throws Exception {
        Map<String, Object> result = resultOf(post(rpcWithMeta("server/discover", null, V_NEW), null, null));
        assertEquals(Constants.MCP_RESULT_TYPE_COMPLETE, result.get(Constants.MCP_RESULT_TYPE));
    }

    @Test
    void protocolVersion_takenFromHeaderWhenMetaAbsent() throws Exception {
        Map<String, Object> result = resultOf(post(rpc("server/discover", null), null, V_NEW));
        assertEquals(Constants.MCP_RESULT_TYPE_COMPLETE, result.get(Constants.MCP_RESULT_TYPE));
    }

    @Test
    void protocolVersion_metaWinsOverHeader() throws Exception {
        // _meta is the more specific declaration, so it must beat the transport header.
        Map<String, Object> result = resultOf(post(rpcWithMeta("server/discover", null, V_OLD), null, V_NEW));
        assertNull(result.get(Constants.MCP_RESULT_TYPE), "should have been treated as the legacy revision");
    }

    @Test
    void protocolVersion_defaultsToLegacyWhenUndeclared() throws Exception {
        Map<String, Object> result = resultOf(post(rpc("server/discover", null), null, null));
        assertNull(result.get(Constants.MCP_RESULT_TYPE), "a silent client must keep today's behaviour");
    }

    @Test
    @SuppressWarnings("unchecked")
    void unsupportedProtocolVersion_isRejectedAndListsWhatWeSupport() throws Exception {
        HttpResponse<String> response = post(rpc("tools/list", null), null, "1999-01-01");
        Map<String, Object> error = (Map<String, Object>) body(response).get("error");

        assertEquals(Constants.JSONRPC_UNSUPPORTED_PROTOCOL_VERSION, ((Number) error.get("code")).intValue());
        assertEquals(List.of(V_NEW, V_OLD), ((Map<String, Object>) error.get("data")).get("supported"));
    }

    // === stateless vs handshake ===

    @Test
    void statelessClient_callsToolsListWithNoSession() throws Exception {
        HttpResponse<String> response = post(rpcWithMeta("tools/list", null, V_NEW), null, null);
        assertNotNull(resultOf(response).get("tools"), "no initialize, no session, still served");
    }

    @Test
    @SuppressWarnings("unchecked")
    void legacyClient_stillRequiresASession() throws Exception {
        Map<String, Object> error = (Map<String, Object>) body(post(rpc("tools/list", null), null, null)).get("error");
        assertEquals(Constants.JSONRPC_INVALID_REQUEST, ((Number) error.get("code")).intValue());
        assertTrue(((String) error.get("message")).contains(Constants.MCP_SESSION_HEADER));
    }

    @Test
    void legacyClient_handshakeStillWorksEndToEnd() throws Exception {
        String sessionId = post(rpc("initialize", null), null, null)
                .headers().firstValue(Constants.MCP_SESSION_HEADER).orElseThrow();
        assertNotNull(resultOf(post(rpc("tools/list", null), sessionId, null)).get("tools"));
    }

    // === result shape ===

    @Test
    @SuppressWarnings("unchecked")
    void statelessResults_carryResultTypeAndServerInfo() throws Exception {
        Map<String, Object> result = resultOf(post(rpcWithMeta("tools/list", null, V_NEW), null, null));

        assertEquals(Constants.MCP_RESULT_TYPE_COMPLETE, result.get(Constants.MCP_RESULT_TYPE));
        Map<String, Object> meta = (Map<String, Object>) result.get("_meta");
        assertNotNull(meta.get(Constants.MCP_META_KEY_SERVER_INFO));
    }

    @Test
    void legacyResults_areUnchanged() throws Exception {
        String sessionId = post(rpc("initialize", null), null, null)
                .headers().firstValue(Constants.MCP_SESSION_HEADER).orElseThrow();
        Map<String, Object> result = resultOf(post(rpc("tools/list", null), sessionId, null));

        assertNull(result.get(Constants.MCP_RESULT_TYPE));
        assertNull(result.get("_meta"));
        assertNull(result.get(Constants.MCP_CACHE_TTL_MS));
    }

    @Test
    void listResults_areCacheableAndScopedPrivate() throws Exception {
        Map<String, Object> result = resultOf(post(rpcWithMeta("tools/list", null, V_NEW), null, null));

        assertEquals(20000, ((Number) result.get(Constants.MCP_CACHE_TTL_MS)).longValue(),
                "ttl should track the discovery interval — that bounds how fast the answer can change");
        assertEquals(Constants.MCP_CACHE_SCOPE_PRIVATE, result.get(Constants.MCP_CACHE_SCOPE),
                "listings are OPA-filtered per caller; public caching would leak inventory");
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolsList_isOrderedDeterministically() throws Exception {
        serviceCache.put("svc-z", mcpService("svc-z", "zebra"));
        serviceCache.put("svc-a", mcpService("svc-a", "aardvark"));
        serviceCache.put("svc-m", mcpService("svc-m", "manatee"));

        List<Map<String, Object>> tools =
                (List<Map<String, Object>>) resultOf(post(rpcWithMeta("tools/list", null, V_NEW), null, null)).get("tools");
        List<String> names = tools.stream().map(t -> (String) t.get("name")).toList();

        assertEquals(names.stream().sorted().toList(), names, "clients cache this listing; order must be stable");
    }

    // === standard headers ===

    @Test
    @SuppressWarnings("unchecked")
    void mcpMethodHeader_disagreeingWithTheBody_isRefused() throws Exception {
        HttpResponse<String> response = postWithHeaders(rpc("tools/list", null),
                Map.of(Constants.MCP_METHOD_HEADER, "tools/call"));
        Map<String, Object> error = (Map<String, Object>) body(response).get("error");

        assertEquals(Constants.JSONRPC_HEADER_MISMATCH, ((Number) error.get("code")).intValue(),
                "an intermediary routed on the header but the body says otherwise — refuse, don't guess");
    }

    @Test
    void mcpMethodHeader_agreeingWithTheBody_passes() throws Exception {
        HttpResponse<String> response = postWithHeaders(rpc("server/discover", null),
                Map.of(Constants.MCP_METHOD_HEADER, "server/discover"));
        assertNull(body(response).get("error"));
    }

    @Test
    void standardHeaders_areOptional_soLegacyClientsAreUnaffected() throws Exception {
        assertNull(body(post(rpc("server/discover", null), null, null)).get("error"));
    }

    // === RFC 9728 authorization discovery ===

    @Test
    @SuppressWarnings("unchecked")
    void protectedResourceMetadata_isServedUnauthenticated() throws Exception {
        HttpResponse<String> response = get(Constants.MCP_PROTECTED_RESOURCE_PATH);
        assertEquals(200, response.statusCode());

        Map<String, Object> doc = objectMapper.readValue(response.body(), Map.class);
        assertTrue(((String) doc.get("resource")).endsWith("/mcp"));
        assertEquals(List.of("header"), doc.get("bearer_methods_supported"));
        assertNotNull(doc.get("authorization_servers"));
    }

    @Test
    void deriveIssuer_trimsTheUsualJwksSuffixes() {
        assertEquals("https://idp/realms/capi",
                McpGateway.deriveIssuer("https://idp/realms/capi/protocol/openid-connect/certs"));
        assertEquals("https://idp",
                McpGateway.deriveIssuer("https://idp/.well-known/jwks.json"));
        assertEquals("https://idp/oauth2/default",
                McpGateway.deriveIssuer("https://idp/oauth2/default/v1/keys"));
        assertEquals("https://idp/unknown-layout",
                McpGateway.deriveIssuer("https://idp/unknown-layout"),
                "an unrecognised layout is returned as-is rather than mangled");
        assertNull(McpGateway.deriveIssuer(null));
    }

    // === version helpers ===

    @Test
    void supportedVersions_areExactlyTheTwoWeServe() {
        assertTrue(McpGateway.isSupportedProtocolVersion(V_NEW));
        assertTrue(McpGateway.isSupportedProtocolVersion(V_OLD));
        assertFalse(McpGateway.isSupportedProtocolVersion("2025-06-18"),
                "we deliberately skipped the intermediate revisions");
        assertFalse(McpGateway.isSupportedProtocolVersion(null));
    }

    @Test
    void onlyTheCurrentRevisionIsStateless() {
        assertTrue(McpGateway.isStateless(V_NEW));
        assertFalse(McpGateway.isStateless(V_OLD));
        assertFalse(McpGateway.isStateless(null));
    }

    // === helpers ===

    private static Service mcpService(String id, String toolName) {
        Service service = new Service();
        service.setId(id);
        service.setName(id);
        ServiceMeta meta = new ServiceMeta();
        meta.handleUnknown("mcp-enabled", "true");
        meta.handleUnknown("mcp-tools", toolName);
        meta.setScheme("http");
        service.setServiceMeta(meta);
        Mapping mapping = new Mapping();
        mapping.setHostname("localhost");
        mapping.setPort(1);
        mapping.setRootContext("/");
        service.setMappingList(Set.of(mapping));
        return service;
    }

    private static String rpc(String method, Object params) throws Exception {
        return objectMapper.writeValueAsString(params == null
                ? Map.of("jsonrpc", "2.0", "method", method, "id", 1)
                : Map.of("jsonrpc", "2.0", "method", method, "id", 1, "params", params));
    }

    private static String rpcWithMeta(String method, Object params, String version) throws Exception {
        Map<String, Object> req = new java.util.LinkedHashMap<>();
        req.put("jsonrpc", "2.0");
        req.put("method", method);
        req.put("id", 1);
        if (params != null) req.put("params", params);
        req.put("_meta", Map.of(Constants.MCP_META_KEY_PROTOCOL_VERSION, version));
        return objectMapper.writeValueAsString(req);
    }

    private HttpResponse<String> post(String body, String sessionId, String versionHeader) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (sessionId != null) b.header(Constants.MCP_SESSION_HEADER, sessionId);
        if (versionHeader != null) b.header(Constants.MCP_PROTOCOL_VERSION_HEADER, versionHeader);
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postWithHeaders(String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(b::header);
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(HttpResponse<String> response) throws Exception {
        return objectMapper.readValue(response.body(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resultOf(HttpResponse<String> response) throws Exception {
        Object result = body(response).get("result");
        assertNotNull(result, "expected a JSON-RPC result but got: " + response.body());
        return (Map<String, Object>) result;
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
