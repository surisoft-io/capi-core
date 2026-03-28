package io.surisoft.capi.utils;

import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.surisoft.capi.schema.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RouteUtilsTest {

    private RouteUtils routeUtils;

    @BeforeEach
    void setUp() {
        routeUtils = new RouteUtils(new CompositeMeterRegistry());
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
    void registerMetric_callsCounter() {
        CompositeMeterRegistry registry = new CompositeMeterRegistry();
        RouteUtils ru = new RouteUtils(registry);

        assertDoesNotThrow(() -> ru.registerMetric("test-route-id"));
    }

    @Test
    void registerTracer_withNullTracer_doesNotThrow() {
        Service service = new Service();
        service.setId("test-svc:dev");
        assertDoesNotThrow(() -> routeUtils.registerTracer(service));
    }
}
