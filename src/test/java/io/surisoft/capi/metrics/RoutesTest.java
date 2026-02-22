package io.surisoft.capi.metrics;

import io.surisoft.capi.schema.Service;
import org.apache.camel.CamelContext;
import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.apache.camel.Route;
import org.apache.camel.spi.ManagementStrategy;
import org.mockito.Mock;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoutesTest {

    @Mock
    private CamelContext camelContext;

    private Cache<String, Service> serviceCache;
    private Routes routes;

    @BeforeEach
    void setUp() {
        serviceCache = Cache2kBuilder.of(String.class, Service.class)
                .eternal(true)
                .entryCapacity(100)
                .build();
        routes = new Routes(camelContext, serviceCache);
    }

    @AfterEach
    void tearDown() {
        serviceCache.close();
    }

    @Test
    void getCachedService_found() {
        Service service = new Service();
        service.setId("my-service:dev");
        service.setName("my-service");
        serviceCache.put("my-service:dev", service);

        Service result = routes.getCachedService("my-service:dev");

        assertNotNull(result);
        assertEquals("my-service", result.getName());
    }

    @Test
    void getCachedService_notFound() {
        assertNull(routes.getCachedService("nonexistent:service"));
    }

    @Test
    void getAllRoutesInfo_emptyRoutes() {
        when(camelContext.getRoutes()).thenReturn(Collections.emptyList());
        assertTrue(routes.getAllRoutesInfo().isEmpty());
    }

    @Test
    void getAllRoutesInfo_withNonInternalRoutes_returnsInfo() {
        ManagementStrategy mgmtStrategy = mock(ManagementStrategy.class);
        when(mgmtStrategy.getManagementAgent()).thenReturn(null);
        when(camelContext.getManagementStrategy()).thenReturn(mgmtStrategy);

        Route route = mock(Route.class);
        when(route.getId()).thenReturn("my-service-route");
        when(route.getGroup()).thenReturn(null);
        when(route.getDescription()).thenReturn("A test route");
        when(route.getUptime()).thenReturn("1h");
        when(route.getUptimeMillis()).thenReturn(3600000L);
        when(route.getProperties()).thenReturn(null);

        when(camelContext.getRoutes()).thenReturn(List.of(route));
        when(camelContext.getRoute("my-service-route")).thenReturn(route);

        var result = routes.getAllRoutesInfo();
        assertFalse(result.isEmpty());
        assertEquals(1, result.size());
    }

    @Test
    void getAllRoutesInfo_withInternalRoutes_filtersThemOut() {
        ManagementStrategy mgmtStrategy = mock(ManagementStrategy.class);
        when(mgmtStrategy.getManagementAgent()).thenReturn(null);
        when(camelContext.getManagementStrategy()).thenReturn(mgmtStrategy);

        Route internalRoute = mock(Route.class);
        when(internalRoute.getId()).thenReturn("consul-discovery-service");
        when(internalRoute.getGroup()).thenReturn(null);
        when(internalRoute.getDescription()).thenReturn(null);
        when(internalRoute.getUptime()).thenReturn("1m");
        when(internalRoute.getUptimeMillis()).thenReturn(60000L);
        when(internalRoute.getProperties()).thenReturn(null);

        Route userRoute = mock(Route.class);
        when(userRoute.getId()).thenReturn("user-api-route");
        when(userRoute.getGroup()).thenReturn(null);
        when(userRoute.getDescription()).thenReturn("User API");
        when(userRoute.getUptime()).thenReturn("2h");
        when(userRoute.getUptimeMillis()).thenReturn(7200000L);
        when(userRoute.getProperties()).thenReturn(null);

        when(camelContext.getRoutes()).thenReturn(List.of(internalRoute, userRoute));
        when(camelContext.getRoute("user-api-route")).thenReturn(userRoute);

        var result = routes.getAllRoutesInfo();
        assertEquals(1, result.size());
    }
}