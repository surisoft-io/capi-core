package io.surisoft.capi.utils;

import io.surisoft.capi.schema.*;
import org.apache.camel.CamelContext;
import org.apache.camel.Route;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RouteUtilsTest {

    @Mock
    private CamelContext camelContext;

    private RouteUtils routeUtils;

    @BeforeEach
    void setUp() {
        routeUtils = new RouteUtils(
                null, new HttpUtils(null, null), null, null,
                camelContext, null, false, null,
                120000, 5000, 5000
        );
    }

    @Test
    void buildFrom_withLeadingSlash() {
        Service service = new Service();
        service.setContext("/my-service/dev");
        assertEquals("/my-service/dev", routeUtils.buildFrom(service));
    }

    @Test
    void buildFrom_withoutLeadingSlash() {
        Service service = new Service();
        service.setContext("my-service/dev");
        assertEquals("/my-service/dev", routeUtils.buildFrom(service));
    }

    @Test
    void getAllRouteIdForAGivenService_returnsFiveRoutes() {
        Service service = new Service();
        service.setId("my-service:dev");
        List<String> routeIds = routeUtils.getAllRouteIdForAGivenService(service);

        assertEquals(5, routeIds.size());
        assertTrue(routeIds.contains("my-service:dev:delete"));
        assertTrue(routeIds.contains("my-service:dev:put"));
        assertTrue(routeIds.contains("my-service:dev:post"));
        assertTrue(routeIds.contains("my-service:dev:get"));
        assertTrue(routeIds.contains("my-service:dev:patch"));
    }

    @Test
    void getMethodFromRouteId_extractsMethod() {
        assertEquals("get", routeUtils.getMethodFromRouteId("my-service:dev:get"));
        assertEquals("post", routeUtils.getMethodFromRouteId("my-service:dev:post"));
    }

    @Test
    void getAllActiveRoutes_returnsRouteIds() {
        Route route1 = mock(Route.class);
        Route route2 = mock(Route.class);
        when(route1.getRouteId()).thenReturn("route-1");
        when(route2.getRouteId()).thenReturn("route-2");
        when(camelContext.getRoutes()).thenReturn(List.of(route1, route2));

        List<String> activeRoutes = routeUtils.getAllActiveRoutes(camelContext);

        assertEquals(2, activeRoutes.size());
        assertTrue(activeRoutes.contains("route-1"));
        assertTrue(activeRoutes.contains("route-2"));
    }

    @Test
    void getAllActiveRoutes_emptyContext() {
        when(camelContext.getRoutes()).thenReturn(List.of());
        assertTrue(routeUtils.getAllActiveRoutes(camelContext).isEmpty());
    }

    @Test
    void buildEndpoints_httpWithPort() {
        Service service = createServiceWithMapping("host1", 8080, "http", "/");
        String[] endpoints = routeUtils.buildEndpoints(service);

        assertEquals(1, endpoints.length);
        assertTrue(endpoints[0].startsWith("http://host1:8080/"));
        assertTrue(endpoints[0].contains("bridgeEndpoint=true"));
        assertTrue(endpoints[0].contains("throwExceptionOnFailure=false"));
    }

    @Test
    void buildEndpoints_httpsScheme() {
        Service service = createServiceWithMapping("host1", 443, "https", "/api");
        String[] endpoints = routeUtils.buildEndpoints(service);

        assertEquals(1, endpoints.length);
        assertTrue(endpoints[0].startsWith("https://host1:443/api"));
    }

    @Test
    void buildEndpoints_noPort() {
        Service service = createServiceWithMapping("host1", -1, "http", "/");
        String[] endpoints = routeUtils.buildEndpoints(service);

        assertEquals(1, endpoints.length);
        assertTrue(endpoints[0].startsWith("http://host1/"));
        assertFalse(endpoints[0].contains(":-1"));
    }

    private Service createServiceWithMapping(String host, int port, String scheme, String rootContext) {
        Service service = new Service();
        ServiceMeta meta = new ServiceMeta();
        meta.setScheme(scheme);
        service.setServiceMeta(meta);

        Mapping mapping = new Mapping();
        mapping.setHostname(host);
        mapping.setPort(port);
        mapping.setRootContext(rootContext);

        Set<Mapping> mappings = new HashSet<>();
        mappings.add(mapping);
        service.setMappingList(mappings);
        return service;
    }
}