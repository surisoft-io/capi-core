package io.surisoft.capi.service.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.surisoft.capi.TestPorts;
import io.surisoft.capi.configuration.CAPIConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Self-registration against a fake Consul agent that records every call. */
class AdminNodeRegistrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer consul;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private volatile String registrationBody;
    private volatile int passStatus = 200;
    private AdminNodeRegistration registration;

    @BeforeEach
    void setUp() throws Exception {
        int port = TestPorts.next();
        consul = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        consul.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            calls.add(ex.getRequestMethod() + " " + path);
            authHeaders.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            int status = 200;
            if (path.equals("/v1/agent/service/register")) {
                registrationBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            } else if (path.startsWith("/v1/agent/check/pass/")) {
                status = passStatus;
            }
            ex.sendResponseHeaders(status, 0);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(new byte[0]);
            }
        });
        consul.start();

        CAPIConfiguration.HostConfig host = new CAPIConfiguration.HostConfig();
        host.setEndpoint("http://127.0.0.1:" + port + "/");
        host.setToken("consul-acl-token");
        registration = new AdminNodeRegistration(host, HttpClient::newHttpClient,
                "capi-admin-prod-pod-1", "prod", "2.25", "10.1.2.3", 8381, "https", 8390);
    }

    @AfterEach
    void tearDown() {
        registration.stop();
        consul.stop(0);
    }

    @Test
    void firstHeartbeat_registersThenPassesTheCheck() {
        registration.heartbeat();
        assertEquals(List.of(
                "PUT /v1/agent/service/register",
                "PUT /v1/agent/check/pass/service:capi-admin-prod-pod-1"), calls);
        assertTrue(authHeaders.stream().allMatch("Bearer consul-acl-token"::equals));
    }

    @Test
    void registrationBody_isNeverRoutable() throws Exception {
        registration.heartbeat();
        JsonNode body = JSON.readTree(registrationBody);
        assertEquals("capi-admin", body.path("Name").asText());
        assertEquals("10.1.2.3", body.path("Address").asText());
        assertEquals(8381, body.path("Port").asInt());
        assertEquals("prod", body.path("Meta").path("capi-cluster").asText());
        assertEquals("https", body.path("Meta").path("admin-scheme").asText());
        assertEquals("8390", body.path("Meta").path("mcp-port").asText());
        assertFalse(body.path("Meta").has("group"), "a group meta would make the entry look routable");
        assertFalse(body.has("Tags"));
        assertEquals("30s", body.path("Check").path("TTL").asText());
        assertEquals("2m", body.path("Check").path("DeregisterCriticalServiceAfter").asText());
    }

    @Test
    void laterHeartbeats_onlyPass() {
        registration.heartbeat();
        calls.clear();
        registration.heartbeat();
        assertEquals(List.of("PUT /v1/agent/check/pass/service:capi-admin-prod-pod-1"), calls);
    }

    @Test
    void agentThatForgotUs_isReRegisteredOnTheSameTick() {
        registration.heartbeat();
        calls.clear();
        passStatus = 404;
        registration.heartbeat();
        assertTrue(calls.contains("PUT /v1/agent/service/register"), calls.toString());
    }

    @Test
    void deregister_removesTheEntry() {
        registration.heartbeat();
        registration.deregister();
        assertTrue(calls.contains("PUT /v1/agent/service/deregister/capi-admin-prod-pod-1"));
    }

    @Test
    void consulDown_doesNotThrow_andRetriesNextTick() {
        consul.stop(0);
        assertDoesNotThrow(registration::heartbeat);
    }

    // ------------------------------------------------------------------ admin.mcp.consul.meta

    /** Loads a capi: config exactly the way CAPIMain does (YAML -> dump -> bean). */
    private static CAPIConfiguration.AdminConsul load(String metaYaml) {
        String yaml = "capi:\n  admin:\n    mcp:\n      consul:\n        endpoint: http://consul:8500\n" + metaYaml;
        java.util.Map<String, Object> root = new org.yaml.snakeyaml.Yaml().load(yaml);
        CAPIConfiguration c = new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.Constructor(
                CAPIConfiguration.class, new org.yaml.snakeyaml.LoaderOptions()))
                .load(new org.yaml.snakeyaml.Yaml().dump(root.get("capi")));
        return c.getAdmin().getMcp().getConsul();
    }

    @Test
    void meta_listOfEntries_asWrittenInConfig() {
        CAPIConfiguration.AdminConsul consul = load("        meta:\n          - group: ops\n          - owner: platform\n");
        assertEquals("http://consul:8500", consul.getEndpoint());
        assertEquals(java.util.Map.of("group", "ops", "owner", "platform"), consul.metaAsMap());
        assertEquals(List.of("group", "owner"), List.copyOf(consul.metaAsMap().keySet()), "declaration order kept");
    }

    @Test
    void meta_plainMap_andNonStringValues() {
        CAPIConfiguration.AdminConsul consul = load("        meta:\n          group: ops\n          tier: 1\n          pci: false\n");
        assertEquals(java.util.Map.of("group", "ops", "tier", "1", "pci", "false"), consul.metaAsMap());
    }

    @Test
    void meta_absent_isEmpty() {
        assertTrue(load("").metaAsMap().isEmpty());
    }

    @Test
    void meta_duplicateKey_failsStartup() {
        CAPIConfiguration.AdminConsul consul = load("        meta:\n          - group: ops\n          - group: dev\n");
        assertThrows(IllegalStateException.class, consul::metaAsMap);
    }

    @Test
    void meta_isSentWithTheRegistration_andCapiKeysStay() throws Exception {
        CAPIConfiguration.HostConfig host = new CAPIConfiguration.HostConfig();
        host.setEndpoint("http://127.0.0.1:" + consul.getAddress().getPort());
        AdminNodeRegistration withMeta = new AdminNodeRegistration(host, HttpClient::newHttpClient,
                "capi-admin-ext-capi1", "ext", "2.25", "10.1.2.3", 8381, "https", 8390,
                java.util.Map.of("group", "ops", "owner", "platform"));
        withMeta.heartbeat();
        JsonNode meta = JSON.readTree(registrationBody).path("Meta");
        assertEquals("ops", meta.path("group").asText());
        assertEquals("platform", meta.path("owner").asText());
        assertEquals("ext", meta.path("capi-cluster").asText());
        assertEquals("8390", meta.path("mcp-port").asText());
    }

    @Test
    void meta_cannotOverrideWhatPeerDiscoveryReadsFrom() {
        CAPIConfiguration.HostConfig host = new CAPIConfiguration.HostConfig();
        host.setEndpoint("http://127.0.0.1:1");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new AdminNodeRegistration(
                host, HttpClient::newHttpClient, "id", "ext", "2.25", "10.1.2.3", 8381, "https", 8390,
                java.util.Map.of("capi-cluster", "someone-elses")));
        assertTrue(e.getMessage().contains("capi-cluster"), e.getMessage());
    }

    /**
     * Production bug, 2026-11-05: after a trust-store reload CAPI rebuilds its shared Consul client and
     * shuts the old one down. A registration holding the old client failed every heartbeat with
     * "IOException: closed" and never recovered.
     */
    @Test
    void sharedClientReplacedOnTrustStoreReload_heartbeatKeepsWorking() throws Exception {
        CAPIConfiguration.HostConfig host = new CAPIConfiguration.HostConfig();
        host.setEndpoint("http://127.0.0.1:" + consul.getAddress().getPort());
        java.util.concurrent.atomic.AtomicReference<HttpClient> shared = new java.util.concurrent.atomic.AtomicReference<>(HttpClient.newHttpClient());
        AdminNodeRegistration reg = new AdminNodeRegistration(host, shared::get,
                "capi-admin-ext-capi1", "ext", "2.25", "10.1.2.3", 8381, "https", 8390);
        reg.heartbeat();
        assertTrue(calls.contains("PUT /v1/agent/service/register"));

        // What Startup.rebuildConsulHttpClient() does: new client in, old one shut down.
        HttpClient old = shared.get();
        shared.set(HttpClient.newHttpClient());
        old.shutdown();
        old.awaitTermination(java.time.Duration.ofSeconds(5));

        // The symptom, reproduced on the old client: exactly the production log line.
        java.io.IOException closed = assertThrows(java.io.IOException.class, () -> old.send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(host.getEndpoint() + "/x")).build(),
                java.net.http.HttpResponse.BodyHandlers.discarding()));
        assertEquals("closed", closed.getMessage());

        calls.clear();
        reg.heartbeat();
        assertEquals(List.of("PUT /v1/agent/check/pass/service:capi-admin-ext-capi1"), calls,
                "the heartbeat must use the CURRENT shared client, not the one captured at startup");
    }

    @Test
    void clusterName_defaultsAndKeepsHyphens() {
        assertEquals("default", AdminNodeRegistration.clusterName(null));
        assertEquals("default", AdminNodeRegistration.clusterName("  "));
        assertEquals("capi-internal", AdminNodeRegistration.clusterName("capi-internal"));
    }

    @Test
    void serviceId_isSafeForAConsulPathSegment() {
        assertEquals("capi-admin-my_cluster-pod.1", AdminNodeRegistration.serviceId("my cluster", "pod.1"));
        assertEquals("capi-admin-a_b-host", AdminNodeRegistration.serviceId("a/b", "host"));
    }

    @Test
    void advertiseAddress_prefersConfig() {
        assertEquals("capi-0.capi.svc", AdminNodeRegistration.resolveAdvertiseAddress(" capi-0.capi.svc "));
        assertNotNull(AdminNodeRegistration.resolveAdvertiseAddress(null));
    }

    @Test
    void adminNode_bracketsIpv6() {
        assertEquals("http://[fd00::1]:8381", new AdminNode("x", "fd00::1", 8381, "http", false).baseUrl());
        assertEquals("https://10.0.0.1:8381", new AdminNode("x", "10.0.0.1", 8381, "https", false).baseUrl());
    }
}
