package io.surisoft.capi.service.consul;

import com.sun.net.httpserver.HttpServer;
import io.surisoft.capi.TestPorts;
import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.utils.ServiceUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * CAPI registers its own admin API in Consul as {@code capi-admin} (admin MCP peer discovery).
 * Discovery must drop it before any per-service lookup, exactly like {@code consul} — otherwise CAPI
 * would try to route to its own admin API, or report it under /info/invalid-services.
 */
class ConsulCatalogAdminExclusionTest {

    private HttpServer consul;
    private final List<String> requested = new CopyOnWriteArrayList<>();
    private ConsulCatalogService catalogService;
    private CAPIConfiguration.HostConfig host;

    @BeforeEach
    void setUp() throws Exception {
        int port = TestPorts.next();
        consul = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        consul.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            requested.add(path);
            String body = path.endsWith("/services")
                    ? "{\"consul\":[],\"capi-admin\":[],\"orders\":[]}"
                    : "[]";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
        consul.start();
        host = new CAPIConfiguration.HostConfig();
        host.setEndpoint("http://127.0.0.1:" + port);
        catalogService = new ConsulCatalogService(List.of(host), null, mock(ServiceUtils.class), List.of(),
                HttpClient.newHttpClient(), "capi-1", false, null, new HashMap<>());
    }

    @AfterEach
    void tearDown() {
        catalogService.shutdown();
        consul.stop(0);
    }

    @Test
    void capiAdminIsDroppedBeforeAnyLookup() throws Exception {
        Method fetch = ConsulCatalogService.class.getDeclaredMethod("fetchHostSync", CAPIConfiguration.HostConfig.class);
        fetch.setAccessible(true);
        HostResult result = (HostResult) fetch.invoke(catalogService, host);

        assertEquals(Set.of("orders"), result.catalogNames());
        assertTrue(requested.stream().noneMatch(p -> p.endsWith("/capi-admin")),
                "capi-admin must never be looked up: " + requested);
        assertTrue(requested.stream().anyMatch(p -> p.endsWith("/orders")), requested.toString());
    }
}
