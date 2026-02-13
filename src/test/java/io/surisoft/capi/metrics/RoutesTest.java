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

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
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
}