package io.surisoft.capi.undertow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.surisoft.capi.TestPorts;
import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.exception.AuthorizationException;
import io.surisoft.capi.service.admin.AdminFanOut;
import io.surisoft.capi.service.admin.AdminMcpTools;
import io.surisoft.capi.service.admin.AdminNode;
import io.surisoft.capi.service.admin.AdminNodeDirectory;
import io.surisoft.capi.utils.HttpUtils;
import io.undertow.server.HttpServerExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End to end over real sockets: the admin MCP listener, a fake Consul, and fake peer admin APIs.
 * Topology: this node (self), peer B (healthy, slightly different), peer C (registered but down),
 * and a node of ANOTHER cluster on the same Consul that must be ignored.
 */
class AdminMcpGatewayTest {

    private static final String GOOD = "good-token";
    private static final String OUTSIDER = "outsider-token";
    private static final String GROUP = "capi-admins";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long NOW = 1_800_000_000_000L;
    private static final long DAY = 86_400_000L;

    private final List<HttpServer> servers = new ArrayList<>();
    private final Map<String, List<String>> authSeenByNode = new ConcurrentHashMap<>();
    private AdminMcpGateway gateway;
    private int mcpPort;
    private int selfPort;
    private int peerBPort;
    private int deadPort;
    private int consulPort;
    private volatile String consulBody;
    private volatile long peerBDelayMs;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
        selfPort = startAdmin("self", "2.25", List.of(
                route("orders:v1", "10.0.0.1:8080"),
                route("billing:v1", "10.0.0.2:8080")),
                "[]",
                "[{\"alias\":\"old-ca\",\"subjectDN\":\"CN=old,O=Example\",\"issuerDN\":\"CN=root\",\"notAfter\":" + (NOW - DAY) + ",\"addedAt\":" + (NOW - 700 * DAY) + "},"
                        + "{\"alias\":\"soon\",\"subjectDN\":\"CN=soon\",\"issuerDN\":\"CN=root\",\"notAfter\":" + (NOW + 10 * DAY) + ",\"dnsNames\":[\"api.example.test\"]},"
                        + "{\"alias\":\"fine\",\"subjectDN\":\"CN=fine\",\"issuerDN\":\"CN=root\",\"notAfter\":" + (NOW + 400 * DAY) + "},"
                        // Same CN as old-ca, different O: a rotation, added two days ago.
                        + "{\"alias\":\"old-ca-2026\",\"subjectDN\":\"CN=old,O=Example 2026\",\"issuerDN\":\"CN=root2\",\"notAfter\":" + (NOW + 300 * DAY) + ",\"addedAt\":" + (NOW - 2 * DAY) + "},"
                        // Different CN, same DNS name as soon: a replacement only SAN matching finds.
                        + "{\"alias\":\"api-renewed\",\"subjectDN\":\"CN=api-gw\",\"issuerDN\":\"CN=root2\",\"notAfter\":" + (NOW + 200 * DAY) + ",\"dnsNames\":[\"API.example.test\"]},"
                        + "{\"alias\":\"orphan\",\"subjectDN\":\"CN=orphan\",\"issuerDN\":\"CN=root\",\"notAfter\":" + (NOW - 5 * DAY) + "}]");
        peerBPort = startAdmin("peer-b", "2.24.2", List.of(
                route("orders:v1", "10.0.0.9:8080")),
                "[{\"serviceId\":\"billing:v1\",\"group\":\"v1\",\"reason\":\"OPENAPI_FETCH_FAILED\",\"detail\":\"connect refused\",\"detectedAt\":\"2026-11-04T10:00:00Z\"}]",
                "[{\"alias\":\"soon\",\"subjectDN\":\"CN=soon\",\"issuerDN\":\"CN=root\",\"notAfter\":" + (NOW + 10 * DAY) + "}]");
        deadPort = TestPorts.next(); // nothing listens here

        consulPort = TestPorts.next();
        consulBody = "["
                + entry("capi-admin-prod-self", selfPort, "prod")
                + "," + entry("capi-admin-prod-b", peerBPort, "prod")
                + "," + entry("capi-admin-prod-c", deadPort, "prod")
                + "," + entry("capi-admin-staging-x", peerBPort, "staging")
                + "]";
        HttpServer consul = HttpServer.create(new InetSocketAddress("127.0.0.1", consulPort), 0);
        consul.createContext("/v1/health/service/capi-admin", ex -> respond(ex, 200, consulBody));
        consul.start();
        servers.add(consul);

        startGateway(4);
    }

    private void startGateway(int maxConcurrentCalls) throws AuthorizationException {
        startGateway(maxConcurrentCalls, null);
    }

    private void startGateway(int maxConcurrentCalls, String authorizationServer) throws AuthorizationException {
        CAPIConfiguration configuration = new CAPIConfiguration();
        configuration.setVersion("2.25");
        CAPIConfiguration.Admin admin = new CAPIConfiguration.Admin();
        admin.setProtected(true);
        admin.setGroup(GROUP);
        admin.getMcp().setEnabled(true);
        admin.getMcp().setNodeTimeoutMs(1500);
        admin.getMcp().setMaxConcurrentCalls(maxConcurrentCalls);
        admin.getMcp().setAuthorizationServer(authorizationServer);
        configuration.setAdmin(admin);

        CAPIConfiguration.HostConfig host = new CAPIConfiguration.HostConfig();
        host.setEndpoint("http://127.0.0.1:" + consulPort);

        HttpUtils httpUtils = mock(HttpUtils.class);
        when(httpUtils.processAuthorizationAccessToken(any(HttpServerExchange.class))).thenAnswer(inv -> {
            HttpServerExchange ex = inv.getArgument(0);
            String h = ex.getRequestHeaders().getFirst("Authorization");
            return h != null && h.startsWith("Bearer ") ? h.substring(7) : null;
        });
        when(httpUtils.isAuthorized(anyString(), anyString())).thenAnswer(inv ->
                (GOOD.equals(inv.getArgument(0)) || inv.<String>getArgument(0).startsWith("ey"))
                        && GROUP.equals(inv.getArgument(1)));

        Duration timeout = Duration.ofMillis(1500);
        AdminNode self = new AdminNode("capi-admin-prod-self", "127.0.0.1", selfPort, "http", true);
        AdminNodeDirectory directory = new AdminNodeDirectory(host, () -> client, "prod", self, timeout);
        // Clock pinned so the truststore window is deterministic.
        AdminMcpTools tools = new AdminMcpTools(directory, new AdminFanOut(() -> client, timeout), () -> NOW);

        if (gateway != null) gateway.stop();
        mcpPort = TestPorts.next();
        gateway = new AdminMcpGateway(mcpPort, null, tools, httpUtils, GROUP, configuration);
        gateway.start();
    }

    @AfterEach
    void tearDown() {
        if (gateway != null) gateway.stop();
        servers.forEach(s -> s.stop(0));
    }

    // ------------------------------------------------------------------ protocol + auth

    @Test
    void noToken_is401WithResourceMetadataPointer() throws Exception {
        HttpResponse<String> r = post(null, rpc("tools/list", Map.of()));
        assertEquals(401, r.statusCode());
        String challenge = r.headers().firstValue("WWW-Authenticate").orElse("");
        assertTrue(challenge.contains("resource_metadata=") && challenge.contains("/.well-known/oauth-protected-resource"), challenge);
    }

    @Test
    void tokenOutsideAdminGroup_is403() throws Exception {
        assertEquals(403, post(OUTSIDER, rpc("tools/list", Map.of())).statusCode());
    }

    @Test
    void protectedResourceMetadata_isServedWithoutToken() throws Exception {
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + mcpPort + "/.well-known/oauth-protected-resource")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        assertTrue(JSON.readTree(r.body()).path("resource").asText().endsWith("/mcp"));
    }

    @Test
    void get_isRejected() throws Exception {
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + mcpPort + "/mcp"))
                .header("Authorization", "Bearer " + GOOD).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(405, r.statusCode());
    }

    @Test
    void initialize_negotiatesVersionAndMintsNoSession() throws Exception {
        HttpResponse<String> r = post(GOOD, rpc("initialize", Map.of("protocolVersion", "2025-03-26")));
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("Mcp-Session-Id").isEmpty(), "stateless: no session id");
        assertEquals("2025-03-26", JSON.readTree(r.body()).path("result").path("protocolVersion").asText());

        // What Claude Code 2.1.x sends after its server/discover probe.
        JsonNode cc = JSON.readTree(post(GOOD, rpc("initialize", Map.of("protocolVersion", "2025-11-25"))).body());
        assertEquals("2025-11-25", cc.path("result").path("protocolVersion").asText());

        // Unknown -> newest HANDSHAKE revision; never 2026-07-28, which has no initialize.
        JsonNode unknown = JSON.readTree(post(GOOD, rpc("initialize", Map.of("protocolVersion", "1999-01-01"))).body());
        assertEquals("2025-11-25", unknown.path("result").path("protocolVersion").asText());
        JsonNode asks2026 = JSON.readTree(post(GOOD, rpc("initialize", Map.of("protocolVersion", "2026-07-28"))).body());
        assertNotEquals("2026-07-28", asks2026.path("result").path("protocolVersion").asText());
    }

    private HttpResponse<String> post2026(String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + mcpPort + "/mcp"))
                .header("Authorization", "Bearer " + GOOD).header("Content-Type", "application/json")
                .header("MCP-Protocol-Version", "2026-07-28")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void revision2026_resultsCarryResultTypeAndServerInfo() throws Exception {
        // Claude Code's probe: without these fields it rejects server/discover and falls back.
        JsonNode discover = JSON.readTree(post2026(rpc("server/discover", Map.of())).body()).path("result");
        assertEquals("complete", discover.path("resultType").asText());
        assertEquals("capi-admin", discover.path("_meta").path("io.modelcontextprotocol/serverInfo").path("name").asText());

        JsonNode list = JSON.readTree(post2026(rpc("tools/list", Map.of())).body()).path("result");
        assertEquals("complete", list.path("resultType").asText());
        assertEquals("private", list.path("cacheScope").asText());
        assertTrue(list.path("ttlMs").asLong() > 0);

        JsonNode called = JSON.readTree(post2026(call(AdminMcpTools.LIST_NODES, Map.of())).body()).path("result");
        assertEquals("complete", called.path("resultType").asText());
        assertEquals(3, called.path("structuredContent").path("nodes").path("total").asInt());
    }

    @Test
    void handshakeRevisions_seePlainResults() throws Exception {
        JsonNode list = result(post(GOOD, rpc("tools/list", Map.of())));
        assertFalse(list.has("resultType"));
        assertFalse(list.has("_meta"));
    }

    @Test
    void notification_is202WithNoBody() throws Exception {
        HttpResponse<String> r = post(GOOD, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertEquals(202, r.statusCode());
        assertTrue(r.body().isEmpty());
    }

    @Test
    void unsupportedDeclaredVersion_isRejected() throws Exception {
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + mcpPort + "/mcp"))
                .header("Authorization", "Bearer " + GOOD)
                .header("MCP-Protocol-Version", "1999-01-01")
                .POST(HttpRequest.BodyPublishers.ofString(rpc("tools/list", Map.of()))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(-32022, JSON.readTree(r.body()).path("error").path("code").asInt());
    }

    @Test
    void toolsList_offersTwelveReadOnlyTools() throws Exception {
        JsonNode tools = result(post(GOOD, rpc("tools/list", Map.of()))).path("tools");
        assertEquals(12, tools.size());
        for (JsonNode t : tools) {
            assertTrue(t.path("annotations").path("readOnlyHint").asBoolean(), t.path("name").asText());
        }
    }

    @Test
    void unknownTool_isInvalidParams() throws Exception {
        JsonNode r = JSON.readTree(post(GOOD, call("capi_delete_everything", Map.of())).body());
        assertEquals(-32602, r.path("error").path("code").asInt());
    }

    @Test
    void missingRequiredArgument_isInvalidParams() throws Exception {
        JsonNode r = JSON.readTree(post(GOOD, call(AdminMcpTools.EXPLAIN_SERVICE, Map.of())).body());
        assertEquals(-32602, r.path("error").path("code").asInt());
    }

    // ------------------------------------------------------------------ authorizationServer

    /** Unsigned JWT: the signature is the mocked isAuthorized's job; this only carries iss. */
    private static String jwt(String issuer) {
        return new com.nimbusds.jwt.PlainJWT(new com.nimbusds.jwt.JWTClaimsSet.Builder()
                .issuer(issuer).claim("subscriptions", List.of(GROUP)).build()).serialize();
    }

    @Test
    void authorizationServer_acceptsOnlyItsOwnIssuer() throws Exception {
        startGateway(4, "https://idp.example.com/realms/ops/");   // trailing slash is normalised
        String list = rpc("tools/list", Map.of());
        assertEquals(200, post(jwt("https://idp.example.com/realms/ops"), list).statusCode());
        assertEquals(200, post(jwt("https://idp.example.com/realms/ops/"), list).statusCode());
        HttpResponse<String> other = post(jwt("https://partner-idp.example.com/realms/x"), list);
        assertEquals(403, other.statusCode(), "another trusted issuer must not authorize agents");
        assertTrue(other.body().contains("authorization server"), other.body());
        assertEquals(403, post(jwt(null), list).statusCode(), "no iss claim -> refused");
    }

    @Test
    void authorizationServer_isTheOnlyOneAdvertised() throws Exception {
        startGateway(4, "https://idp.example.com/realms/ops");
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + mcpPort + "/.well-known/oauth-protected-resource")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode servers = JSON.readTree(r.body()).path("authorization_servers");
        assertEquals(1, servers.size());
        assertEquals("https://idp.example.com/realms/ops", servers.get(0).asText());
    }

    @Test
    void noAuthorizationServer_keepsAcceptingAnyTrustedIssuer() throws Exception {
        assertEquals(200, post(jwt("https://partner-idp.example.com/realms/x"), rpc("tools/list", Map.of())).statusCode());
    }

    // ------------------------------------------------------------------ tools

    @Test
    void listNodes_reportsThisClusterOnly_andTheDeadNode() throws Exception {
        JsonNode s = structured(post(GOOD, call(AdminMcpTools.LIST_NODES, Map.of())));
        assertEquals(3, s.path("nodes").path("total").asInt(), "self + B + C; the staging node is filtered out");
        assertEquals(2, s.path("nodes").path("answered").asInt());
        assertEquals("capi-admin-prod-c", s.path("nodes").path("unreachable").get(0).path("node").asText());
        assertTrue(s.path("mixedVersions").asBoolean());
        assertTrue(s.path("nodeDetails").get(0).path("node").asText().endsWith("(this node)"));
    }

    @Test
    void callerTokenIsForwardedToEveryPeer() throws Exception {
        post(GOOD, call(AdminMcpTools.LIST_NODES, Map.of()));
        assertTrue(authSeenByNode.get("self").contains("Bearer " + GOOD));
        assertTrue(authSeenByNode.get("peer-b").contains("Bearer " + GOOD));
    }

    @Test
    void checkAlignment_reportsEachDimension_andOnlyTheDifferences() throws Exception {
        JsonNode s = structured(post(GOOD, call(AdminMcpTools.CHECK_ALIGNMENT, Map.of())));
        assertFalse(s.path("aligned").asBoolean());
        JsonNode checks = s.path("checks");

        assertFalse(checks.path("capiVersion").path("aligned").asBoolean());
        assertEquals(2, checks.path("capiVersion").path("byValue").size());

        assertTrue(checks.path("jwksEndpoints").path("aligned").asBoolean(), "same endpoints, different order = aligned");
        assertEquals(2, checks.path("jwksEndpoints").path("value").size());

        // Same config, different keys actually loaded: exactly the drift config alone cannot show.
        assertFalse(checks.path("jwksKeysLoaded").path("aligned").asBoolean());
        assertTrue(s.path("warnings").get(0).asText().contains("loaded NO keys from https://idp/b/certs"), s.toString());

        assertTrue(checks.path("consulHosts").path("aligned").asBoolean());

        // peer-b predates serviceCount: unknown, not a difference — and with only one node left to
        // compare, the check cannot claim alignment either way.
        JsonNode counts = checks.path("serviceCounts");
        assertTrue(counts.path("aligned").isNull(), counts.toString());
        assertEquals("1 of 3", counts.path("nodesCompared").asText());
        assertEquals("capi-admin-prod-b", counts.path("unknownOn").get(0).asText());
        assertEquals(2, counts.path("value").path("services").asInt());

        JsonNode rest = checks.path("restServices");
        assertFalse(rest.path("aligned").asBoolean());
        assertEquals(1, rest.path("differenceCount").asInt());
        assertEquals("billing:v1", rest.path("differences").get(0).path("id").asText());
        assertEquals("capi-admin-prod-b", rest.path("differences").get(0).path("missingOn").get(0).asText());
        assertEquals(2, rest.path("countByNode").path("capi-admin-prod-self (this node)").asInt());
    }

    /**
     * Seen live after a Keycloak key rotation: the entry node had the new key, its peers did not, so
     * they 403 the forwarded token. The answer must say so — and must not call a one-node comparison
     * "aligned".
     */
    @Test
    void peersRejectingAnAcceptedToken_isExplained_andNothingClaimsAlignment() throws Exception {
        // JWS header {"alg":"RS256","kid":"rotated-key"}: accepted here (mock), rejected by the peers.
        String rotated = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"alg\":\"RS256\",\"kid\":\"rotated-key\"}".getBytes()) + ".e30.c2ln";
        HttpResponse<String> r = post(rotated, call(AdminMcpTools.CHECK_ALIGNMENT, Map.of()));
        JsonNode s = structured(r);
        String hint = s.path("tokenRejectedByPeers").asText();
        assertTrue(hint.contains("capi-admin-prod-b") && hint.contains("kid rotated-key"), s.toString());
        assertFalse(s.path("aligned").asBoolean());
        JsonNode version = s.path("checks").path("capiVersion");
        assertTrue(version.path("aligned").isNull(), "one answering node cannot be 'aligned': " + version);
        assertEquals("1 of 3", version.path("nodesCompared").asText());
        assertTrue(s.path("checks").path("restServices").path("aligned").isNull());
    }

    @Test
    void compareRoutes_returnsOnlyDifferences() throws Exception {
        JsonNode s = structured(post(GOOD, call(AdminMcpTools.COMPARE_ROUTES, Map.of())));
        assertEquals(2, s.path("routesCompared").asInt());
        assertEquals(2, s.path("differenceCount").asInt());
        JsonNode billing = s.path("differences").get(0);
        assertEquals("billing:v1", billing.path("id").asText());
        assertEquals("capi-admin-prod-b", billing.path("missingOn").get(0).asText());
        JsonNode orders = s.path("differences").get(1);
        assertEquals("orders:v1", orders.path("id").asText());
        assertTrue(orders.has("endpointsByNode"));
    }

    @Test
    void explainService_showsWhyNodesDisagree() throws Exception {
        JsonNode s = structured(post(GOOD, call(AdminMcpTools.EXPLAIN_SERVICE, Map.of("serviceId", "billing:v1"))));
        assertFalse(s.path("consistentAcrossNodes").asBoolean());
        JsonNode self = s.path("perNode").get(0);
        assertTrue(self.path("routed").asBoolean());
        assertEquals("10.0.0.2:8080", self.path("endpoints").get(0).asText());
        JsonNode b = s.path("perNode").get(1);
        assertFalse(b.path("routed").asBoolean());
        assertEquals("OPENAPI_FETCH_FAILED", b.path("invalid").path("reason").asText());
        assertEquals("nonCompliant", self.path("specCompliance").asText());
    }

    @Test
    void invalidServices_areMergedWithReportingNodes() throws Exception {
        JsonNode s = structured(post(GOOD, call(AdminMcpTools.INVALID_SERVICES, Map.of())));
        assertEquals(1, s.path("count").asInt());
        JsonNode e = s.path("invalidServices").get(0);
        assertEquals("billing:v1", e.path("serviceId").asText());
        assertFalse(e.path("onAllAnsweringNodes").asBoolean());
    }

    @Test
    void truststoreExpiring_listsExpiredAndSoon_withNodes() throws Exception {
        JsonNode s = structured(post(GOOD, call(AdminMcpTools.TRUSTSTORE_EXPIRING, Map.of("days", 30))));
        assertEquals(3, s.path("count").asInt());
        JsonNode first = s.path("certificates").get(0);
        assertEquals("orphan", first.path("alias").asText());
        assertTrue(first.path("expired").asBoolean());
        JsonNode soon = s.path("certificates").get(2);
        assertEquals("soon", soon.path("alias").asText());
        assertEquals(10, soon.path("daysLeft").asInt());
        assertEquals(2, soon.path("nodes").size(), "held by self and B");
    }

    @Test
    void truststoreExpiring_saysWhichCertificatesAreAlreadyReplaced() throws Exception {
        JsonNode s = structured(post(GOOD, call(AdminMcpTools.TRUSTSTORE_EXPIRING, Map.of("days", 30, "recentDays", 7))));
        assertEquals(2, s.path("replaced").asInt());
        assertEquals(1, s.path("notReplaced").asInt());
        Map<String, JsonNode> byAlias = new java.util.HashMap<>();
        s.path("certificates").forEach(c -> byAlias.put(c.path("alias").asText(), c));

        JsonNode oldCa = byAlias.get("old-ca");
        assertEquals("replaced", oldCa.path("status").asText());
        assertEquals("old-ca-2026", oldCa.path("replacedBy").get(0).path("alias").asText(), "same CN, different O");
        assertEquals("old", oldCa.path("replacedBy").get(0).path("sharedNames").get(0).asText());

        JsonNode soon = byAlias.get("soon");
        assertEquals("replaced", soon.path("status").asText());
        assertEquals("api-renewed", soon.path("replacedBy").get(0).path("alias").asText(), "matched on the DNS SAN, case-insensitive");

        assertEquals("NOT replaced", byAlias.get("orphan").path("status").asText());
        assertEquals(0, byAlias.get("orphan").path("replacedBy").size());

        // Recently added = date the alias entered the store, not the certificate's notBefore.
        assertEquals(1, s.path("recentlyAdded").size());
        assertEquals("old-ca-2026", s.path("recentlyAdded").get(0).path("alias").asText());
        assertTrue(s.has("note"), "entries without a creation date are called out");
    }

    @Test
    void truststoreFind_searchesValidAndExpiredByAnyName() throws Exception {
        JsonNode s = structured(post(GOOD, call(AdminMcpTools.TRUSTSTORE_FIND, Map.of("query", "CN=OLD"))));
        assertEquals(2, s.path("count").asInt(), "expired old-ca and valid old-ca-2026");
        JsonNode byDns = structured(post(GOOD, call(AdminMcpTools.TRUSTSTORE_FIND, Map.of("query", "api.example"))));
        assertEquals(2, byDns.path("count").asInt(), "soon (on self and B) and api-renewed");
        JsonNode missing = JSON.readTree(post(GOOD, call(AdminMcpTools.TRUSTSTORE_FIND, Map.of())).body());
        assertEquals(-32602, missing.path("error").path("code").asInt());
    }

    @Test
    void traffic_clusterTotals_imbalance_topLists_andSpikes() throws Exception {
        JsonNode s = structured(post(GOOD, call(AdminMcpTools.TRAFFIC, Map.of("minutes", 15))));
        assertEquals(1000, s.path("cluster").path("requests").asLong());
        assertEquals(40, s.path("cluster").path("5xx").asLong());
        assertEquals(0.9, s.path("perNode").get(0).path("loadShare").asDouble(), "self takes 90%");
        assertEquals(2, s.path("loadImbalance").size(), "90% vs 10% on two nodes: both far from 50%");
        assertEquals("billing:v1", s.path("topBy5xx").get(0).path("service").asText());
        assertEquals("billing:v1", s.path("topByLatency").get(0).path("service").asText());
        assertEquals("orders:v1", s.path("topByTraffic").get(0).path("service").asText());
        assertEquals(900, s.path("topByTraffic").get(0).path("requests").asLong(), "merged across nodes");
        JsonNode spike = s.path("errorSpikes").get(0);
        assertEquals("T4", spike.path("at").asText());
        assertEquals(39, spike.path("5xx").asLong());
        assertEquals(1, s.path("nodes").path("unreachable").size(), "the dead node is still reported");
    }

    @Test
    void serviceHealth_listsNodesWithNoTrafficForIt() throws Exception {
        JsonNode s = structured(post(GOOD, call(AdminMcpTools.SERVICE_HEALTH, Map.of("serviceId", "billing:v1"))));
        assertEquals(100, s.path("requests").asLong());
        assertEquals(0.4, s.path("errorRatio5xx").asDouble());
        assertEquals("capi-admin-prod-b", s.path("noTrafficOn").get(0).asText());
        assertTrue(s.has("noTrafficHint"));
    }

    @Test
    void nodeHealth_flagsOutliersAgainstTheOtherNodes_andGrowth() throws Exception {
        JsonNode s = structured(post(GOOD, call(AdminMcpTools.NODE_HEALTH, Map.of())));
        assertFalse(s.path("allHealthy").asBoolean());
        JsonNode self = s.path("perNode").get(0);
        JsonNode b = s.path("perNode").get(1);
        assertTrue(self.path("healthy").asBoolean(), self.toString());
        assertFalse(b.path("healthy").asBoolean());
        String flags = b.path("flags").toString();
        assertTrue(flags.contains("threads 200 vs 40"), flags);
        assertTrue(flags.contains("file descriptors grew 100 -> 400"), flags);
    }

    @Test
    void securityEvents_perNodeAndClusterTotals() throws Exception {
        JsonNode s = structured(post(GOOD, call(AdminMcpTools.SECURITY_EVENTS, Map.of())));
        assertEquals(8, s.path("total").asLong());
        assertEquals(3, s.path("cluster").path("capi_dot_segment_requests_total{action=rejected}").asLong());
        assertEquals(5, s.path("cluster").path("capi_blocked_requests_total").asLong());
    }

    @Test
    void specComplianceSummary_isCountsPerNode() throws Exception {
        JsonNode s = structured(post(GOOD, call(AdminMcpTools.SPEC_COMPLIANCE_SUMMARY, Map.of())));
        assertEquals(2, s.path("perNode").size());
        assertEquals(1, s.path("perNode").get(0).path("summary").path("nonCompliant").asInt());
    }

    @Test
    void consulDown_stillAnswersForThisNode_andSaysDiscoveryFailed() throws Exception {
        servers.stream().filter(s -> s.getAddress().getPort() == consulPort).forEach(s -> s.stop(0));
        JsonNode s = structured(post(GOOD, call(AdminMcpTools.LIST_NODES, Map.of())));
        assertEquals(1, s.path("nodes").path("total").asInt());
        assertTrue(s.path("discoveryErrors").size() > 0);
    }

    @Test
    void slowPeer_isAnsweredAsynchronously_andConcurrencyIsCapped() throws Exception {
        startGateway(1);
        peerBDelayMs = 700;
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<HttpResponse<String>> first = CompletableFuture.supplyAsync(() -> {
            started.countDown();
            try {
                return post(GOOD, call(AdminMcpTools.LIST_NODES, Map.of()));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        started.await();
        Thread.sleep(200); // let the first call take the only permit
        JsonNode busy = JSON.readTree(post(GOOD, call(AdminMcpTools.LIST_NODES, Map.of())).body());
        assertEquals(-32000, busy.path("error").path("code").asInt(), busy.toString());

        JsonNode s = structured(first.get(5, TimeUnit.SECONDS));
        assertEquals(2, s.path("nodes").path("answered").asInt());
        // The permit came back: a later call is served.
        assertFalse(structured(post(GOOD, call(AdminMcpTools.LIST_NODES, Map.of()))).isMissingNode());
    }

    // ------------------------------------------------------------------ fixtures

    private int startAdmin(String name, String version, List<String> routes, String invalid, String truststore) throws IOException {
        int port = TestPorts.next();
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        s.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        authSeenByNode.put(name, new CopyOnWriteArrayList<>());
        s.createContext("/", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            authSeenByNode.get(name).add(String.valueOf(auth));
            // "self" is the node that accepted the caller, so it accepts what the gateway accepted
            // (GOOD, or a JWT); the peers only know GOOD — like nodes missing a rotated signing key.
            boolean accepted = ("Bearer " + GOOD).equals(auth)
                    || ("self".equals(name) && auth != null && auth.startsWith("Bearer ey"));
            if (!accepted) {
                respond(ex, 403, "{}");
                return;
            }
            if ("peer-b".equals(name) && peerBDelayMs > 0) {
                try {
                    Thread.sleep(peerBDelayMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            String path = ex.getRequestURI().getPath();
            switch (path) {
                case "/info/capi" -> respond(ex, 200, "{\"capiVersion\":\"" + version + "\",\"uptime\":\"PT1H\",\"totalRoutes\":"
                        + routes.size() + ",\"invalidServiceCount\":0,\"oauth2Enabled\":true,"
                        // JWKS endpoints listed in a different order per node: must still compare equal.
                        + ("self".equals(name)
                            ? "\"oauth2Endpoint\":\"https://idp/a/certs,https://idp/b/certs\","
                              + "\"jwksKeyIds\":{\"https://idp/b/certs\":[\"k3\"],\"https://idp/a/certs\":[\"k2\",\"k1\"]},"
                              + "\"serviceCount\":2,\"websocketRoutes\":0,\"webdavRoutes\":0,"
                            : "\"oauth2Endpoint\":\"https://idp/b/certs,https://idp/a/certs\","
                              + "\"jwksKeyIds\":{\"https://idp/a/certs\":[\"k1\",\"k2\"],\"https://idp/b/certs\":[]},")
                        + "\"consulHosts\":[\"http://consul-1:8500\",\"http://consul-2:8500\"]}");
                case "/info/health" -> respond(ex, 200, "{\"status\":\"UP\"}");
                case "/info/routes" -> respond(ex, 200, "[" + String.join(",", routes) + "]");
                case "/info/invalid-services" -> respond(ex, 200, invalid);
                case "/info/truststore" -> respond(ex, 200, truststore);
                case "/info/metrics/history" -> respond(ex, 200, "self".equals(name)
                        // Busy node: billing is failing, with a 5xx burst in one interval.
                        ? "{\"coveredSeconds\":900,\"totals\":{\"requests\":900,\"requestsPerSecond\":1.0,\"4xx\":10,\"5xx\":40,"
                          + "\"errorRatio5xx\":0.0444,\"avgLatencyMs\":20.0,\"maxLatencyMsSampled\":300.0},"
                          + "\"services\":[{\"service\":\"orders:v1\",\"requests\":800,\"4xx\":10,\"5xx\":0,\"avgLatencyMs\":10.0},"
                          + "{\"service\":\"billing:v1\",\"requests\":100,\"4xx\":0,\"5xx\":40,\"avgLatencyMs\":100.0}],"
                          + "\"series\":[{\"t\":\"T1\",\"5xx\":0},{\"t\":\"T2\",\"5xx\":1},{\"t\":\"T3\",\"5xx\":0},{\"t\":\"T4\",\"5xx\":39},{\"t\":\"T5\",\"5xx\":0}],"
                          + "\"security\":{\"capi_dot_segment_requests_total{action=rejected}\":3},"
                          + "\"jvm\":{\"latest\":{\"threads\":40,\"heapUsedPct\":50.0,\"openFds\":100,\"maxFds\":10000,\"processCpu\":0.1},"
                          + "\"window\":{\"openFdsFirst\":100,\"openFdsLast\":100,\"threadsFirst\":40,\"threadsLast\":40}}}"
                        // Quiet node: little traffic, no billing at all, and leaking threads + FDs.
                        : "{\"coveredSeconds\":900,\"totals\":{\"requests\":100,\"requestsPerSecond\":0.11,\"4xx\":0,\"5xx\":0,"
                          + "\"errorRatio5xx\":0.0,\"avgLatencyMs\":10.0,\"maxLatencyMsSampled\":20.0},"
                          + "\"services\":[{\"service\":\"orders:v1\",\"requests\":100,\"4xx\":0,\"5xx\":0,\"avgLatencyMs\":10.0}],"
                          + "\"series\":[],\"security\":{\"capi_blocked_requests_total\":5},"
                          + "\"jvm\":{\"latest\":{\"threads\":200,\"heapUsedPct\":60.0,\"openFds\":400,\"maxFds\":10000,\"processCpu\":0.2},"
                          + "\"window\":{\"openFdsFirst\":100,\"openFdsLast\":400,\"threadsFirst\":190,\"threadsLast\":200}}}");
                case "/info/spec-compliance" -> respond(ex, 200,
                        "{\"summary\":{\"enforcing\":false,\"compliant\":1,\"nonCompliant\":1},"
                                + "\"compliant\":[{\"serviceId\":\"orders:v1\"}],\"nonCompliant\":[{\"serviceId\":\"billing:v1\"}]}");
                default -> {
                    if (path.startsWith("/info/routes/")) {
                        String id = path.substring("/info/routes/".length());
                        String match = routes.stream().filter(r -> r.contains("\"" + id + "\"")).findFirst().orElse(null);
                        if (match == null) {
                            respond(ex, 404, "{\"errorMessage\":\"Service not found\"}");
                        } else {
                            JsonNode r = JSON.readTree(match);
                            String ep = r.path("endpoints").get(0).asText();
                            respond(ex, 200, "{\"id\":\"" + id + "\",\"mappingList\":[{\"hostname\":\""
                                    + ep.split(":")[0] + "\",\"port\":" + ep.split(":")[1] + "}],\"serviceMeta\":{\"secured\":true}}");
                        }
                    } else {
                        respond(ex, 404, "{}");
                    }
                }
            }
        });
        s.start();
        servers.add(s);
        return port;
    }

    private static String route(String id, String endpoint) {
        return "{\"id\":\"" + id + "\",\"status\":\"Started\",\"endpoints\":[\"" + endpoint + "\"]}";
    }

    private static String entry(String id, int port, String cluster) {
        return "{\"Node\":{\"Address\":\"127.0.0.1\"},\"Service\":{\"ID\":\"" + id + "\",\"Address\":\"127.0.0.1\",\"Port\":" + port
                + ",\"Meta\":{\"capi-cluster\":\"" + cluster + "\",\"admin-scheme\":\"http\"}}}";
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private HttpResponse<String> post(String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + mcpPort + "/mcp"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String rpc(String method, Map<String, Object> params) throws Exception {
        return JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params));
    }

    private static String call(String tool, Map<String, Object> args) throws Exception {
        return rpc("tools/call", Map.of("name", tool, "arguments", args));
    }

    private static JsonNode result(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        JsonNode node = JSON.readTree(r.body());
        assertFalse(node.has("error"), r.body());
        return node.path("result");
    }

    private static JsonNode structured(HttpResponse<String> r) throws Exception {
        JsonNode result = result(r);
        assertFalse(result.path("isError").asBoolean(), r.body());
        return result.path("structuredContent");
    }
}
