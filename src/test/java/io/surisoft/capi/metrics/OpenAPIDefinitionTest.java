package io.surisoft.capi.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import org.apache.camel.util.json.JsonObject;
import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class OpenAPIDefinitionTest {

    private Cache<String, Service> serviceCache;
    private OpenAPIDefinition openAPIDefinition;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        serviceCache = Cache2kBuilder.of(String.class, Service.class)
                .name("openApiDefTestCache-" + System.nanoTime())
                .eternal(true)
                .build();
        openAPIDefinition = new OpenAPIDefinition(serviceCache, "http://localhost:8080");
    }

    @AfterEach
    void tearDown() {
        if (serviceCache != null) {
            serviceCache.close();
        }
    }

    @Test
    void getCacheOpenApiDefinition_nullServiceMeta_returnsNull() {
        Service service = new Service();
        service.setId("test-service");
        // serviceMeta is null

        JsonObject result = openAPIDefinition.getCacheOpenApiDefinition(service, objectMapper, "test:service");
        assertNull(result);
    }

    @Test
    void getCacheOpenApiDefinition_nullOpenApiEndpoint_returnsNull() {
        ServiceMeta serviceMeta = new ServiceMeta();
        // openApiEndpoint is null

        Service service = new Service();
        service.setId("test-service");
        service.setServiceMeta(serviceMeta);

        JsonObject result = openAPIDefinition.getCacheOpenApiDefinition(service, objectMapper, "test:service");
        assertNull(result);
    }

    @Test
    void getCacheOpenApiDefinition_invalidEndpoint_returnsNull() {
        ServiceMeta serviceMeta = new ServiceMeta();
        serviceMeta.setOpenApiEndpoint("http://invalid-host-that-does-not-exist-12345.example.com/api-docs");

        Service service = new Service();
        service.setId("test-service");
        service.setServiceMeta(serviceMeta);

        // This will fail with IOException/ConnectException and return null
        JsonObject result = openAPIDefinition.getCacheOpenApiDefinition(service, objectMapper, "test:service");
        assertNull(result);
    }

    @Test
    void getCachedService_exists_returnsService() {
        Service service = new Service();
        service.setId("my-service");

        serviceCache.put("my-service", service);

        Service result = openAPIDefinition.getCachedService("my-service");
        assertNotNull(result);
        assertEquals("my-service", result.getId());
    }

    @Test
    void getCachedService_doesNotExist_returnsNull() {
        Service result = openAPIDefinition.getCachedService("nonexistent-service");
        assertNull(result);
    }

    @Test
    void getCachedService_multipleEntries_returnsCorrectOne() {
        Service service1 = new Service();
        service1.setId("service-1");
        Service service2 = new Service();
        service2.setId("service-2");

        serviceCache.put("service-1", service1);
        serviceCache.put("service-2", service2);

        Service result = openAPIDefinition.getCachedService("service-2");
        assertNotNull(result);
        assertEquals("service-2", result.getId());
    }
}
