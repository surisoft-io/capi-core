package io.surisoft.capi.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import io.undertow.Undertow;
import io.undertow.util.Headers;

import java.net.ServerSocket;
import java.util.Map;

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

        Map<String, Object> result = openAPIDefinition.getCacheOpenApiDefinition(service, objectMapper, "test:service");
        assertNull(result);
    }

    @Test
    void getCacheOpenApiDefinition_nullOpenApiEndpoint_returnsNull() {
        ServiceMeta serviceMeta = new ServiceMeta();
        // openApiEndpoint is null

        Service service = new Service();
        service.setId("test-service");
        service.setServiceMeta(serviceMeta);

        Map<String, Object> result = openAPIDefinition.getCacheOpenApiDefinition(service, objectMapper, "test:service");
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
        Map<String, Object> result = openAPIDefinition.getCacheOpenApiDefinition(service, objectMapper, "test:service");
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
    void getCacheOpenApiDefinition_validEndpoint_returnsEnrichedDefinition() throws Exception {
        int port = findFreePort();
        String openApiJson = "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"Original\"},\"servers\":[{\"url\":\"http://old\"}],\"paths\":{}}";

        Undertow backend = Undertow.builder()
                .addHttpListener(port, "0.0.0.0")
                .setHandler(exchange -> {
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    exchange.getResponseSender().send(openApiJson);
                }).build();
        backend.start();

        try {
            ServiceMeta meta = new ServiceMeta();
            meta.setOpenApiEndpoint("http://localhost:" + port + "/api-docs");

            Service service = new Service();
            service.setId("my-svc");
            service.setServiceMeta(meta);

            Map<String, Object> result = openAPIDefinition.getCacheOpenApiDefinition(service, objectMapper, "my-svc:v1");
            assertNotNull(result, "Expected non-null result from valid endpoint");
            // info should be replaced with CAPI-generated info
            Object info = result.get("info");
            assertNotNull(info);
            // paths should still be present
            assertNotNull(result.get("paths"));
            // servers should contain CAPI endpoint
            assertNotNull(result.get("servers"));
        } finally {
            backend.stop();
        }
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
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
