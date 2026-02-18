package io.surisoft.capi.service;

import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import io.surisoft.capi.utils.RouteUtils;
import io.swagger.v3.oas.models.OpenAPI;
import org.apache.camel.CamelContext;
import org.apache.camel.spi.RouteController;
import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RouteConsistencyCheckerTest {

    private RouteConsistencyChecker checker;

    @Mock
    private CamelContext camelContext;

    @Mock
    private RouteUtils routeUtils;

    @Mock
    private RouteController routeController;

    private Cache<String, Service> serviceCache;

    @BeforeEach
    void setUp() {
        serviceCache = Cache2kBuilder.of(String.class, Service.class)
                .eternal(true)
                .entryCapacity(100)
                .build();
        checker = new RouteConsistencyChecker(camelContext, routeUtils, serviceCache);
    }

    @AfterEach
    void tearDown() {
        serviceCache.close();
    }

    @Test
    void process_whenContextNotStarted_doesNothing() {
        when(camelContext.isStarted()).thenReturn(false);

        checker.process();

        verifyNoInteractions(routeUtils);
    }

    @Test
    void process_whenContextStarted_withConsistentServices_doesNotRemoveRoutes() {
        when(camelContext.isStarted()).thenReturn(true);

        Service service = new Service();
        service.setId("service-1");
        ServiceMeta meta = new ServiceMeta();
        meta.setOpenApiEndpoint("http://example.com/openapi");
        service.setServiceMeta(meta);
        service.setOpenAPI(new OpenAPI());
        serviceCache.put("service-1", service);

        checker.process();

        verifyNoInteractions(routeUtils);
        assertNotNull(serviceCache.peek("service-1"));
    }

    @Test
    void process_whenContextStarted_withNoOpenApiEndpoint_doesNotRemoveRoutes() {
        when(camelContext.isStarted()).thenReturn(true);

        Service service = new Service();
        service.setId("service-2");
        ServiceMeta meta = new ServiceMeta();
        // openApiEndpoint is null, openAPI is also null -- not inconsistent (no openapi expected)
        service.setServiceMeta(meta);
        serviceCache.put("service-2", service);

        checker.process();

        verifyNoInteractions(routeUtils);
        assertNotNull(serviceCache.peek("service-2"));
    }

    @Test
    void process_whenContextStarted_withInconsistentService_removesRoutes() throws Exception {
        when(camelContext.isStarted()).thenReturn(true);
        when(camelContext.getRouteController()).thenReturn(routeController);

        Service service = new Service();
        service.setId("service-3");
        ServiceMeta meta = new ServiceMeta();
        meta.setOpenApiEndpoint("http://example.com/openapi");
        service.setServiceMeta(meta);
        // openAPI is null -- this is the inconsistent case
        serviceCache.put("service-3", service);

        List<String> routeIds = List.of("service-3:delete", "service-3:put", "service-3:post", "service-3:get", "service-3:patch");
        when(routeUtils.getAllRouteIdForAGivenService(service)).thenReturn(routeIds);

        checker.process();

        for (String routeId : routeIds) {
            verify(routeController).stopRoute(routeId);
            verify(camelContext).removeRoute(routeId);
        }
        assertNull(serviceCache.peek("service-3"));
    }

    @Test
    void process_whenContextStarted_mixedServices_onlyRemovesInconsistent() throws Exception {
        when(camelContext.isStarted()).thenReturn(true);
        when(camelContext.getRouteController()).thenReturn(routeController);

        // Consistent service
        Service consistentService = new Service();
        consistentService.setId("consistent");
        ServiceMeta consistentMeta = new ServiceMeta();
        consistentMeta.setOpenApiEndpoint("http://example.com/openapi");
        consistentService.setServiceMeta(consistentMeta);
        consistentService.setOpenAPI(new OpenAPI());
        serviceCache.put("consistent", consistentService);

        // Inconsistent service
        Service inconsistentService = new Service();
        inconsistentService.setId("inconsistent");
        ServiceMeta inconsistentMeta = new ServiceMeta();
        inconsistentMeta.setOpenApiEndpoint("http://example.com/openapi");
        inconsistentService.setServiceMeta(inconsistentMeta);
        serviceCache.put("inconsistent", inconsistentService);

        List<String> routeIds = List.of("inconsistent:delete", "inconsistent:put", "inconsistent:post", "inconsistent:get", "inconsistent:patch");
        when(routeUtils.getAllRouteIdForAGivenService(inconsistentService)).thenReturn(routeIds);

        checker.process();

        verify(routeUtils, never()).getAllRouteIdForAGivenService(consistentService);
        verify(routeUtils).getAllRouteIdForAGivenService(inconsistentService);
        assertNotNull(serviceCache.peek("consistent"));
        assertNull(serviceCache.peek("inconsistent"));
    }

    @Test
    void process_whenStopRouteThrowsException_continuesProcessing() throws Exception {
        when(camelContext.isStarted()).thenReturn(true);
        when(camelContext.getRouteController()).thenReturn(routeController);

        Service service = new Service();
        service.setId("failing-service");
        ServiceMeta meta = new ServiceMeta();
        meta.setOpenApiEndpoint("http://example.com/openapi");
        service.setServiceMeta(meta);
        serviceCache.put("failing-service", service);

        List<String> routeIds = List.of("failing-service:delete", "failing-service:get");
        when(routeUtils.getAllRouteIdForAGivenService(service)).thenReturn(routeIds);
        doThrow(new RuntimeException("Stop failed")).when(routeController).stopRoute("failing-service:delete");

        // Should not throw; the handler catches exceptions
        assertDoesNotThrow(() -> checker.process());
    }
}
