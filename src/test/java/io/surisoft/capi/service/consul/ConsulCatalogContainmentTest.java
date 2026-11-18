package io.surisoft.capi.service.consul;

import com.sun.net.httpserver.HttpServer;
import io.surisoft.capi.TestPorts;
import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.schema.InvalidService;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.utils.ServiceUtils;
import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One service with an unusable registration must stay that service's problem. Before, a single
 * failed lookup — a malformed {@code capi-instance-*} key threw out of discovery — kept the whole
 * node DOWN forever and skipped route cleanup for every service, on every node.
 *
 * <p>Runs real discovery cycles against a fake Consul whose answers each test sets per service.
 */
class ConsulCatalogContainmentTest {

    private static final String INSTANCE = "capi1";

    private HttpServer consul;
    /** service name → [status, body]; the catalog lists exactly these names. */
    private final Map<String, Object[]> registry = new ConcurrentHashMap<>();
    private volatile int catalogStatus = 200;

    private Cache<String, Service> cache;
    private ConsulCatalogService discovery;
    private final Map<String, InvalidService> invalid = new ConcurrentHashMap<>();
    private final List<String> appeared = new CopyOnWriteArrayList<>();
    private final List<String> disappeared = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        setConnected(false);
        int port = TestPorts.next();
        consul = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        consul.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            int status;
            String body;
            if (path.equals("/v1/catalog/services")) {
                status = catalogStatus;
                StringBuilder sb = new StringBuilder("{");
                registry.keySet().forEach(n -> sb.append(sb.length() > 1 ? "," : "").append('"').append(n).append("\":[]"));
                body = status == 200 ? sb.append('}').toString() : "catalog unavailable";
            } else {
                Object[] entry = registry.get(path.substring("/v1/catalog/service/".length()));
                status = entry == null ? 200 : (int) entry[0];
                body = entry == null ? "[]" : (String) entry[1];
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
        consul.start();

        CAPIConfiguration.HostConfig host = new CAPIConfiguration.HostConfig();
        host.setEndpoint("http://127.0.0.1:" + port);
        cache = Cache2kBuilder.of(String.class, Service.class).entryCapacity(1000).build();
        ServiceUtils serviceUtils = new ServiceUtils(null, Optional.empty(), null, Optional.empty(), "full");
        TransportHandler recorder = new TransportHandler() {
            public boolean supports(Service s) { return true; }
            public void onAppear(Service s) { appeared.add(s.getId()); }
            public void onChange(Service o, Service n) { }
            public void onDisappear(Service s) { disappeared.add(s.getId()); }
        };
        discovery = new ConsulCatalogService(List.of(host), cache, serviceUtils, List.of(recorder),
                HttpClient.newHttpClient(), INSTANCE, false, null, invalid);
    }

    @AfterEach
    void tearDown() throws Exception {
        discovery.shutdown();
        consul.stop(0);
        cache.close();
        setConnected(false);
    }

    // ---------------------------------------------------------------- the reported failure

    @Test
    void malformedInstanceKey_isReported_notRouted_andTheNodeIsReady() {
        ok("orders");
        registry.put("reporting",
                new Object[]{200, entry("reporting", "\"capi-instance-default\":\"true\"")});

        discovery.runCycle();

        assertTrue(ConsulCatalogService.isConnectedToConsul(), "one bad registration must not keep the node DOWN");
        assertTrue(cache.containsKey("orders:v1"));
        // Fail closed: the key was meant to restrict the service, so it must not open it to every instance.
        assertFalse(cache.containsKey("reporting:v1"));
        InvalidService reported = invalid.get("reporting");
        assertNotNull(reported, invalid.toString());
        assertEquals(InvalidService.Reason.CONSUL_INVALID_REGISTRATION, reported.reason());
        assertTrue(reported.detail().contains("capi-instance-default"), reported.detail());
    }

    @Test
    void malformedInstanceKey_besideAWellFormedOneForThisInstance_stillRoutes() {
        registry.put("orders", new Object[]{200, entry("orders",
                "\"capi-instance-default\":\"true\",\"capi-instance-" + INSTANCE + "-secured\":\"false\"")});

        discovery.runCycle();

        assertTrue(cache.containsKey("orders:v1"));
        assertNotNull(invalid.get("orders"), "the stray key is still reported");
    }

    // ---------------------------------------------------------------- other unusable registrations

    @Test
    void metaValueOfTheWrongType_isReported_andTheNodeIsReady() {
        ok("orders");
        registry.put("billing", new Object[]{200, entry("billing", "\"secured\":\"yes\"")});

        discovery.runCycle();

        assertTrue(ConsulCatalogService.isConnectedToConsul());
        assertTrue(cache.containsKey("orders:v1"));
        assertTrue(invalid.get("billing").detail().contains("yes"), invalid.get("billing").detail());
    }

    @Test
    void aclRefusal_isReported_withoutParsingTheErrorBody_andTheNodeIsReady() {
        ok("orders");
        registry.put("billing", new Object[]{403, "Permission denied"});

        discovery.runCycle();

        assertTrue(ConsulCatalogService.isConnectedToConsul());
        assertTrue(invalid.get("billing").detail().startsWith("Consul answered HTTP 403"), invalid.get("billing").detail());
    }

    // ---------------------------------------------------------------- cleanup is per service now

    @Test
    void deregisteredService_isRemoved_whileAnotherRegistrationIsBroken() {
        ok("orders");
        ok("billing");
        registry.put("broken", new Object[]{200, entry("broken", "\"secured\":\"yes\"")});
        discovery.runCycle();
        assertTrue(cache.containsKey("billing:v1"));

        registry.remove("billing");
        discovery.runCycle();

        assertEquals(List.of("billing:v1"), disappeared, "cleanup must still run: only the broken name is preserved");
        assertTrue(cache.containsKey("orders:v1"));
    }

    @Test
    void serviceWhoseRegistrationBreaks_keepsItsRoute_andIsReported() {
        ok("orders");
        discovery.runCycle();
        assertTrue(cache.containsKey("orders:v1"));

        registry.put("orders", new Object[]{200, entry("orders", "\"secured\":\"yes\"")});
        discovery.runCycle();

        assertTrue(disappeared.isEmpty(), "a route must not be dropped because its registration became unreadable");
        assertTrue(cache.containsKey("orders:v1"));
        assertNotNull(invalid.get("orders"));
    }

    @Test
    void fixedRegistration_leavesInvalidServices() {
        registry.put("billing", new Object[]{200, entry("billing", "\"secured\":\"yes\"")});
        discovery.runCycle();
        assertNotNull(invalid.get("billing"));

        ok("billing");
        discovery.runCycle();

        assertNull(invalid.get("billing"), invalid.toString());
        assertTrue(cache.containsKey("billing:v1"));
    }

    // ---------------------------------------------------------------- what still blocks, on purpose

    @Test
    void consulErrorOnALookup_keepsTheNodeDown_andPreservesThatRoute() {
        ok("orders");
        ok("billing");
        discovery.runCycle();
        setConnectedQuietly(false);   // as if this were the first cycle

        registry.put("orders", new Object[]{500, "rpc error"});
        registry.remove("billing");
        discovery.runCycle();

        assertFalse(ConsulCatalogService.isConnectedToConsul(), "a lookup that could not be made must still block readiness");
        assertTrue(cache.containsKey("orders:v1"), "its route is preserved");
        assertEquals(List.of("billing:v1"), disappeared, "the catalog was complete, so cleanup still ran");
        assertNull(invalid.get("orders"), "a Consul error is not the registration's fault");
    }

    @Test
    void unreadableCatalog_skipsAllCleanup() {
        ok("orders");
        discovery.runCycle();

        catalogStatus = 500;
        discovery.runCycle();

        assertTrue(disappeared.isEmpty());
        assertTrue(cache.containsKey("orders:v1"));
    }

    // ---------------------------------------------------------------- helpers

    private void ok(String name) {
        registry.put(name, new Object[]{200, entry(name, null)});
    }

    private static String entry(String name, String extraMeta) {
        return "[{\"ID\":\"" + name + "-1\",\"ServiceName\":\"" + name + "\",\"ServiceAddress\":\"10.0.0.1\","
                + "\"ServicePort\":8080,\"ModifyIndex\":1,\"ServiceMeta\":{\"group\":\"v1\""
                + (extraMeta != null ? "," + extraMeta : "") + "}}]";
    }

    private static void setConnected(boolean value) throws Exception {
        Field f = ConsulCatalogService.class.getDeclaredField("connectedToConsul");
        f.setAccessible(true);
        f.set(null, value);
    }

    private static void setConnectedQuietly(boolean value) {
        try {
            setConnected(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
