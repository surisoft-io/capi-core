package io.surisoft.capi.service;

import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import io.swagger.v3.oas.models.OpenAPI;
import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RouteConsistencyCheckerTest {

    private RouteConsistencyChecker checker;
    private Cache<String, Service> serviceCache;

    @BeforeEach
    void setUp() {
        serviceCache = Cache2kBuilder.of(String.class, Service.class)
                .eternal(true)
                .entryCapacity(100)
                .build();
        checker = new RouteConsistencyChecker(serviceCache);
    }

    @AfterEach
    void tearDown() {
        serviceCache.close();
    }

    @Test
    void process_withConsistentServices_doesNotRemove() {
        Service service = new Service();
        service.setId("service-1");
        ServiceMeta meta = new ServiceMeta();
        meta.setOpenApiEndpoint("http://example.com/openapi");
        service.setServiceMeta(meta);
        service.setOpenAPI(new OpenAPI());
        serviceCache.put("service-1", service);

        checker.process();

        assertNotNull(serviceCache.peek("service-1"));
    }

    @Test
    void process_withNoOpenApiEndpoint_doesNotRemove() {
        Service service = new Service();
        service.setId("service-2");
        ServiceMeta meta = new ServiceMeta();
        // openApiEndpoint is null, openAPI is also null -- not inconsistent (no openapi expected)
        service.setServiceMeta(meta);
        serviceCache.put("service-2", service);

        checker.process();

        assertNotNull(serviceCache.peek("service-2"));
    }

    @Test
    void process_withEmptyOpenApiEndpoint_doesNotRemove() {
        // Per-instance metadata can leave the global "open-api" key as "" while no
        // per-instance override applies for this CAPI. The fetch gate treats empty
        // as "no endpoint", so openAPI stays null — which must NOT trip the checker.
        Service service = new Service();
        service.setId("service-empty");
        ServiceMeta meta = new ServiceMeta();
        meta.setOpenApiEndpoint("");
        service.setServiceMeta(meta);
        serviceCache.put("service-empty", service);

        checker.process();

        assertNotNull(serviceCache.peek("service-empty"),
                "service with empty openApiEndpoint must not be flagged as inconsistent");
    }

    @Test
    void process_withInconsistentService_removesFromCache() {
        Service service = new Service();
        service.setId("service-3");
        ServiceMeta meta = new ServiceMeta();
        meta.setOpenApiEndpoint("http://example.com/openapi");
        service.setServiceMeta(meta);
        // openAPI is null -- this is the inconsistent case
        serviceCache.put("service-3", service);

        checker.process();

        assertNull(serviceCache.peek("service-3"));
    }

    @Test
    void process_mixedServices_onlyRemovesInconsistent() {
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

        checker.process();

        assertNotNull(serviceCache.peek("consistent"));
        assertNull(serviceCache.peek("inconsistent"));
    }

    @Test
    void process_emptyCache_doesNotThrow() {
        assertDoesNotThrow(() -> checker.process());
    }
}
