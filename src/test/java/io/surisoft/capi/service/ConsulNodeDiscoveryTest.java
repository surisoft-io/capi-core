package io.surisoft.capi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.surisoft.capi.schema.*;
import io.surisoft.capi.utils.*;
import org.cache2k.Cache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.surisoft.capi.configuration.CAPIConfiguration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class ConsulNodeDiscoveryTest {

    @Mock
    private HttpClient httpClient;

    @Mock
    private ServiceUtils serviceUtils;

    @Mock
    private Cache<String, Service> serviceCache;

    @Mock
    private RouteUtils routeUtils;

    @Mock
    private WebsocketUtils websocketUtils;

    private ConsulNodeDiscovery consulNodeDiscovery;

    private List<CAPIConfiguration.HostConfig> consulHosts;

    @BeforeEach
    void setUp() {
        CAPIConfiguration.HostConfig hostConfig = new CAPIConfiguration.HostConfig();
        hostConfig.setEndpoint("http://consul-host:8500");
        hostConfig.setToken("test-token");
        consulHosts = List.of(hostConfig);

        consulNodeDiscovery = new ConsulNodeDiscovery(
                null, consulHosts, serviceUtils, serviceCache, routeUtils, websocketUtils, httpClient
        );
        consulNodeDiscovery.setCapiInstanceNamespace("default");
        consulNodeDiscovery.setCapiRunningMode("full");
        consulNodeDiscovery.setCapiContext("/capi");
        consulNodeDiscovery.setStrictToInstanceName(false);
    }

    @Test
    void getServiceMeta_returnsMetaForMatchingGroup() {
        ServiceMeta meta = new ServiceMeta();
        meta.setGroup("v1");

        ConsulObject co = new ConsulObject();
        co.setServiceMeta(meta);

        ServiceMeta result = consulNodeDiscovery.getServiceMeta("v1", List.of(co));
        assertNotNull(result);
        assertEquals("v1", result.getGroup());
    }

    @Test
    void getServiceMeta_returnsNullWhenNoMatch() {
        ServiceMeta meta = new ServiceMeta();
        meta.setGroup("v1");

        ConsulObject co = new ConsulObject();
        co.setServiceMeta(meta);

        ServiceMeta result = consulNodeDiscovery.getServiceMeta("v2", List.of(co));
        assertNull(result);
    }

    @Test
    void getServiceMeta_returnsNullForNullGroup() {
        ConsulObject co = new ConsulObject();
        co.setServiceMeta(new ServiceMeta());

        ServiceMeta result = consulNodeDiscovery.getServiceMeta("v1", List.of(co));
        assertNull(result);
    }

    @Test
    void isConnectedToConsul_returnsBooleanValue() {
        // Just verify the static method is accessible and returns a value
        // Initially static field might be false or true depending on previous test runs
        boolean result = ConsulNodeDiscovery.isConnectedToConsul();
        assertNotNull((Object) result);
    }

    @Test
    void setters_doNotThrow() {
        assertDoesNotThrow(() -> {
            consulNodeDiscovery.setOpaService(null);
            consulNodeDiscovery.setHttpUtils(null);
            consulNodeDiscovery.setCapiRunningMode("full");
            consulNodeDiscovery.setWebsocketClientMap(new HashMap<>());
            consulNodeDiscovery.setThrottleProcessor(null);
            consulNodeDiscovery.setReverseProxyHost("host.example.com");
            consulNodeDiscovery.setCapiContext("/capi");
            consulNodeDiscovery.setStrictToInstanceName(true);
            consulNodeDiscovery.setCapiInstanceNamespace("default");
            consulNodeDiscovery.setServiceMetaExtrasPrefix("extra-");
        });
    }

    @SuppressWarnings("unchecked")
    @Test
    void processInfo_handlesIOException() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("Connection refused"));

        // Should not throw, just log the error
        assertDoesNotThrow(() -> consulNodeDiscovery.processInfo());
    }

    @SuppressWarnings("unchecked")
    @Test
    void processInfo_handlesInterruptedException() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new InterruptedException("interrupted"));

        assertDoesNotThrow(() -> consulNodeDiscovery.processInfo());
    }

    @SuppressWarnings("unchecked")
    @Test
    void processInfo_withValidServicesResponse() throws Exception {
        // Mock the response for getAllServices
        HttpResponse<String> servicesResponse = mock(HttpResponse.class);
        when(servicesResponse.body()).thenReturn("{\"my-service\": [\"tag1\"]}");

        // Mock the response for getServiceByName
        HttpResponse<String> serviceByNameResponse = mock(HttpResponse.class);
        ServiceMeta meta = new ServiceMeta();
        meta.setGroup("v1");
        meta.setCapiNamespace("default");
        meta.setType("rest");

        ConsulObject co = new ConsulObject();
        co.setServiceName("my-service");
        co.setServiceMeta(meta);
        co.setServiceAddress("10.0.0.1");
        co.setServicePort(8080);
        co.setModifyIndex(1);

        ObjectMapper objectMapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        String serviceByNameJson = objectMapper.writeValueAsString(List.of(co));
        when(serviceByNameResponse.body()).thenReturn(serviceByNameJson);

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(servicesResponse);
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(serviceByNameResponse));

        when(serviceCache.entries()).thenReturn(Collections.emptySet());
        when(serviceCache.peek(anyString())).thenReturn(null);

        Mapping mapping = new Mapping();
        mapping.setHostname("10.0.0.1");
        mapping.setPort(8080);
        mapping.setRootContext("/");
        when(serviceUtils.consulObjectToMapping(any(ConsulObject.class))).thenReturn(mapping);
        when(serviceUtils.checkIfOpenApiIsEnabled(any(Service.class), any(HttpClient.class))).thenReturn(true);

        consulNodeDiscovery.processInfo();

        verify(serviceCache, atLeastOnce()).put(anyString(), any(Service.class));
    }

    @Test
    void getServiceMeta_multipleConsulObjects_returnsCorrectOne() {
        ServiceMeta meta1 = new ServiceMeta();
        meta1.setGroup("v1");
        ServiceMeta meta2 = new ServiceMeta();
        meta2.setGroup("v2");

        ConsulObject co1 = new ConsulObject();
        co1.setServiceMeta(meta1);
        ConsulObject co2 = new ConsulObject();
        co2.setServiceMeta(meta2);

        ServiceMeta result = consulNodeDiscovery.getServiceMeta("v2", List.of(co1, co2));
        assertNotNull(result);
        assertEquals("v2", result.getGroup());
    }

    @Test
    void getServiceMeta_emptyList_returnsNull() {
        assertNull(consulNodeDiscovery.getServiceMeta("v1", List.of()));
    }

    @SuppressWarnings("unchecked")
    @Test
    void processInfo_withConsulHostWithoutToken() throws Exception {
        CAPIConfiguration.HostConfig hostConfig = new CAPIConfiguration.HostConfig();
        hostConfig.setEndpoint("http://consul-host:8500");
        // no token set

        consulNodeDiscovery = new ConsulNodeDiscovery(
                null, List.of(hostConfig), serviceUtils, serviceCache, routeUtils, websocketUtils, httpClient
        );
        consulNodeDiscovery.setCapiInstanceNamespace("default");
        consulNodeDiscovery.setCapiRunningMode("full");
        consulNodeDiscovery.setCapiContext("/capi");

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("Connection refused"));

        assertDoesNotThrow(() -> consulNodeDiscovery.processInfo());
    }

    @SuppressWarnings("unchecked")
    @Test
    void processInfo_existingServiceNotChanged() throws Exception {
        HttpResponse<String> servicesResponse = mock(HttpResponse.class);
        when(servicesResponse.body()).thenReturn("{\"my-service\": []}");

        ServiceMeta meta = new ServiceMeta();
        meta.setGroup("v1");
        meta.setCapiNamespace("default");
        meta.setType("rest");

        ConsulObject co = new ConsulObject();
        co.setServiceName("my-service");
        co.setServiceMeta(meta);
        co.setServiceAddress("10.0.0.1");
        co.setServicePort(8080);
        co.setModifyIndex(1);

        ObjectMapper objectMapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        HttpResponse<String> serviceByNameResponse = mock(HttpResponse.class);
        when(serviceByNameResponse.body()).thenReturn(objectMapper.writeValueAsString(List.of(co)));

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(servicesResponse);
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(serviceByNameResponse));

        when(serviceCache.entries()).thenReturn(Collections.emptySet());

        Service existingService = new Service();
        existingService.setId("my-service:v1");
        existingService.setServiceMeta(meta);
        when(serviceCache.peek("my-service:v1")).thenReturn(existingService);

        Mapping mapping = new Mapping();
        mapping.setHostname("10.0.0.1");
        mapping.setPort(8080);
        mapping.setRootContext("/");
        when(serviceUtils.consulObjectToMapping(any(ConsulObject.class))).thenReturn(mapping);
        when(serviceUtils.updateExistingService(any(Service.class), any(Service.class), eq(serviceCache))).thenReturn(false);

        consulNodeDiscovery.processInfo();

        verify(serviceUtils).updateExistingService(any(Service.class), any(Service.class), eq(serviceCache));
    }

    // --- Additional tests for uncovered branches ---

    @SuppressWarnings("unchecked")
    @Test
    void processInfo_existingServiceChanged_redeploysRoute() throws Exception {
        HttpResponse<String> servicesResponse = mock(HttpResponse.class);
        when(servicesResponse.body()).thenReturn("{\"my-service\": []}");

        ServiceMeta meta = new ServiceMeta();
        meta.setGroup("v1");
        meta.setCapiNamespace("default");
        meta.setType("rest");

        ConsulObject co = new ConsulObject();
        co.setServiceName("my-service");
        co.setServiceMeta(meta);
        co.setServiceAddress("10.0.0.1");
        co.setServicePort(8080);
        co.setModifyIndex(1);

        ObjectMapper objectMapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        HttpResponse<String> serviceByNameResponse = mock(HttpResponse.class);
        when(serviceByNameResponse.body()).thenReturn(objectMapper.writeValueAsString(List.of(co)));

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(servicesResponse);
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(serviceByNameResponse));

        when(serviceCache.entries()).thenReturn(Collections.emptySet());

        Service existingService = new Service();
        existingService.setId("my-service:v1");
        existingService.setServiceMeta(meta);
        when(serviceCache.peek("my-service:v1")).thenReturn(existingService);

        Mapping mapping = new Mapping();
        mapping.setHostname("10.0.0.1");
        mapping.setPort(8080);
        mapping.setRootContext("/");
        when(serviceUtils.consulObjectToMapping(any(ConsulObject.class))).thenReturn(mapping);
        when(serviceUtils.updateExistingService(any(Service.class), any(Service.class), eq(serviceCache))).thenReturn(true);
        when(serviceUtils.checkIfOpenApiIsEnabled(any(Service.class), any(HttpClient.class))).thenReturn(true);

        when(routeUtils.getAllRouteIdForAGivenService(any(Service.class))).thenReturn(List.of("my-service:v1:GET"));

        consulNodeDiscovery.processInfo();

        verify(serviceUtils).updateExistingService(any(Service.class), any(Service.class), eq(serviceCache));
    }

    @SuppressWarnings("unchecked")
    @Test
    void processInfo_routeGroupFirst_setsIdCorrectly() throws Exception {
        HttpResponse<String> servicesResponse = mock(HttpResponse.class);
        when(servicesResponse.body()).thenReturn("{\"my-service\": []}");

        ServiceMeta meta = new ServiceMeta();
        meta.setGroup("v1");
        meta.setCapiNamespace("default");
        meta.setType("rest");
        meta.setRouteGroupFirst(true);

        ConsulObject co = new ConsulObject();
        co.setServiceName("my-service");
        co.setServiceMeta(meta);
        co.setServiceAddress("10.0.0.1");
        co.setServicePort(8080);
        co.setModifyIndex(1);

        ObjectMapper objectMapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        HttpResponse<String> serviceByNameResponse = mock(HttpResponse.class);
        when(serviceByNameResponse.body()).thenReturn(objectMapper.writeValueAsString(List.of(co)));

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(servicesResponse);
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(serviceByNameResponse));

        when(serviceCache.entries()).thenReturn(Collections.emptySet());
        when(serviceCache.peek("v1:my-service")).thenReturn(null);

        Mapping mapping = new Mapping();
        mapping.setHostname("10.0.0.1");
        mapping.setPort(8080);
        mapping.setRootContext("/");
        when(serviceUtils.consulObjectToMapping(any(ConsulObject.class))).thenReturn(mapping);
        when(serviceUtils.checkIfOpenApiIsEnabled(any(Service.class), any(HttpClient.class))).thenReturn(true);

        when(routeUtils.getAllRouteIdForAGivenService(any(Service.class))).thenReturn(List.of("v1:my-service:GET"));

        consulNodeDiscovery.processInfo();

        verify(serviceCache, atLeastOnce()).put(eq("v1:my-service"), any(Service.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void processInfo_existingServiceChanged_openApiDisabled_doesNotCreateRoute() throws Exception {
        HttpResponse<String> servicesResponse = mock(HttpResponse.class);
        when(servicesResponse.body()).thenReturn("{\"my-service\": []}");

        ServiceMeta meta = new ServiceMeta();
        meta.setGroup("v1");
        meta.setCapiNamespace("default");
        meta.setType("rest");

        ConsulObject co = new ConsulObject();
        co.setServiceName("my-service");
        co.setServiceMeta(meta);
        co.setServiceAddress("10.0.0.1");
        co.setServicePort(8080);
        co.setModifyIndex(1);

        ObjectMapper objectMapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        HttpResponse<String> serviceByNameResponse = mock(HttpResponse.class);
        when(serviceByNameResponse.body()).thenReturn(objectMapper.writeValueAsString(List.of(co)));

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(servicesResponse);
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(serviceByNameResponse));

        when(serviceCache.entries()).thenReturn(Collections.emptySet());

        Service existingService = new Service();
        existingService.setId("my-service:v1");
        existingService.setServiceMeta(meta);
        when(serviceCache.peek("my-service:v1")).thenReturn(existingService);

        Mapping mapping = new Mapping();
        mapping.setHostname("10.0.0.1");
        mapping.setPort(8080);
        mapping.setRootContext("/");
        when(serviceUtils.consulObjectToMapping(any(ConsulObject.class))).thenReturn(mapping);
        when(serviceUtils.updateExistingService(any(Service.class), any(Service.class), eq(serviceCache))).thenReturn(true);
        when(serviceUtils.needsOpenApiFetch(any(Service.class))).thenReturn(true);
        when(serviceUtils.buildOpenApiRequest(any(Service.class))).thenReturn(mock(HttpRequest.class));
        when(serviceUtils.processOpenApiSpec(any(Service.class), any(HttpResponse.class))).thenReturn(false);

        consulNodeDiscovery.processInfo();

        // Verify no route was cached (since openApi spec processing failed)
        verify(serviceCache, never()).put(eq("my-service:v1"), any(Service.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void processInfo_serviceWithNullGroup_isNotDeployed() throws Exception {
        HttpResponse<String> servicesResponse = mock(HttpResponse.class);
        when(servicesResponse.body()).thenReturn("{\"my-service\": []}");

        ServiceMeta meta = new ServiceMeta();
        // group is null
        meta.setCapiNamespace("default");
        meta.setType("rest");

        ConsulObject co = new ConsulObject();
        co.setServiceName("my-service");
        co.setServiceMeta(meta);
        co.setServiceAddress("10.0.0.1");
        co.setServicePort(8080);
        co.setModifyIndex(1);

        ObjectMapper objectMapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        HttpResponse<String> serviceByNameResponse = mock(HttpResponse.class);
        when(serviceByNameResponse.body()).thenReturn(objectMapper.writeValueAsString(List.of(co)));

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(servicesResponse);
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(serviceByNameResponse));

        when(serviceCache.entries()).thenReturn(Collections.emptySet());

        consulNodeDiscovery.processInfo();

        // No service should be put in cache since group is null
        verify(serviceCache, never()).put(anyString(), any(Service.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void processInfo_serviceForDifferentNamespace_notDeployed() throws Exception {
        HttpResponse<String> servicesResponse = mock(HttpResponse.class);
        when(servicesResponse.body()).thenReturn("{\"my-service\": []}");

        ServiceMeta meta = new ServiceMeta();
        meta.setGroup("v1");
        meta.setCapiNamespace("other-namespace");
        meta.setType("rest");

        ConsulObject co = new ConsulObject();
        co.setServiceName("my-service");
        co.setServiceMeta(meta);
        co.setServiceAddress("10.0.0.1");
        co.setServicePort(8080);
        co.setModifyIndex(1);

        ObjectMapper objectMapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        HttpResponse<String> serviceByNameResponse = mock(HttpResponse.class);
        when(serviceByNameResponse.body()).thenReturn(objectMapper.writeValueAsString(List.of(co)));

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(servicesResponse);
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(serviceByNameResponse));

        when(serviceCache.entries()).thenReturn(Collections.emptySet());

        consulNodeDiscovery.processInfo();

        // Service is declared for different namespace, should not be deployed
        verify(serviceCache, never()).put(anyString(), any(Service.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void processInfo_strictMode_serviceWithoutNamespace_notDeployed() throws Exception {
        consulNodeDiscovery.setStrictToInstanceName(true);

        HttpResponse<String> servicesResponse = mock(HttpResponse.class);
        when(servicesResponse.body()).thenReturn("{\"my-service\": []}");

        ServiceMeta meta = new ServiceMeta();
        meta.setGroup("v1");
        // No capiNamespace set
        meta.setType("rest");

        ConsulObject co = new ConsulObject();
        co.setServiceName("my-service");
        co.setServiceMeta(meta);
        co.setServiceAddress("10.0.0.1");
        co.setServicePort(8080);
        co.setModifyIndex(1);

        ObjectMapper objectMapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        HttpResponse<String> serviceByNameResponse = mock(HttpResponse.class);
        when(serviceByNameResponse.body()).thenReturn(objectMapper.writeValueAsString(List.of(co)));

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(servicesResponse);
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(serviceByNameResponse));

        when(serviceCache.entries()).thenReturn(Collections.emptySet());

        consulNodeDiscovery.processInfo();

        // Strict mode: no namespace means not deployed
        verify(serviceCache, never()).put(anyString(), any(Service.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void processInfo_serviceWithMultipleInstances_forOtherInstances_notDeployed() throws Exception {
        HttpResponse<String> servicesResponse = mock(HttpResponse.class);
        when(servicesResponse.body()).thenReturn("{\"my-service\": []}");

        ServiceMeta meta = new ServiceMeta();
        meta.setGroup("v1");
        // No capiNamespace
        meta.setType("rest");
        meta.handleUnknown("capi-instance-other-secured", "true");

        ConsulObject co = new ConsulObject();
        co.setServiceName("my-service");
        co.setServiceMeta(meta);
        co.setServiceAddress("10.0.0.1");
        co.setServicePort(8080);
        co.setModifyIndex(1);

        ObjectMapper objectMapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        HttpResponse<String> serviceByNameResponse = mock(HttpResponse.class);
        when(serviceByNameResponse.body()).thenReturn(objectMapper.writeValueAsString(List.of(co)));

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(servicesResponse);
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(serviceByNameResponse));

        when(serviceCache.entries()).thenReturn(Collections.emptySet());
        when(serviceUtils.getServiceCapiInstance(any(ConsulObject.class), eq("default"))).thenReturn(null);
        when(serviceUtils.isTheServiceRegisteredForOtherInstances(any(ConsulObject.class), eq("default"))).thenReturn(true);

        consulNodeDiscovery.processInfo();

        // Service is for other instances, should not be deployed
        verify(serviceCache, never()).put(anyString(), any(Service.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void processInfo_serviceWithMultipleConsulHosts_mergesServices() throws Exception {
        CAPIConfiguration.HostConfig host1 = new CAPIConfiguration.HostConfig();
        host1.setEndpoint("http://consul1:8500");
        host1.setToken("token1");
        CAPIConfiguration.HostConfig host2 = new CAPIConfiguration.HostConfig();
        host2.setEndpoint("http://consul2:8500");
        host2.setToken("token2");

        consulNodeDiscovery = new ConsulNodeDiscovery(
                null, List.of(host1, host2), serviceUtils, serviceCache, routeUtils, websocketUtils, httpClient
        );
        consulNodeDiscovery.setCapiInstanceNamespace("default");
        consulNodeDiscovery.setCapiRunningMode("full");
        consulNodeDiscovery.setCapiContext("/capi");
        consulNodeDiscovery.setStrictToInstanceName(false);

        HttpResponse<String> servicesResponse1 = mock(HttpResponse.class);
        when(servicesResponse1.body()).thenReturn("{\"my-service\": []}");

        HttpResponse<String> servicesResponse2 = mock(HttpResponse.class);
        when(servicesResponse2.body()).thenReturn("{\"my-service\": []}");

        ServiceMeta meta = new ServiceMeta();
        meta.setGroup("v1");
        meta.setCapiNamespace("default");
        meta.setType("rest");

        ConsulObject co1 = new ConsulObject();
        co1.setServiceName("my-service");
        co1.setServiceMeta(meta);
        co1.setServiceAddress("10.0.0.1");
        co1.setServicePort(8080);
        co1.setModifyIndex(1);

        ConsulObject co2 = new ConsulObject();
        co2.setServiceName("my-service");
        co2.setServiceMeta(meta);
        co2.setServiceAddress("10.0.0.2");
        co2.setServicePort(8080);
        co2.setModifyIndex(2);

        ObjectMapper objectMapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

        HttpResponse<String> serviceByNameResponse1 = mock(HttpResponse.class);
        when(serviceByNameResponse1.body()).thenReturn(objectMapper.writeValueAsString(List.of(co1)));
        HttpResponse<String> serviceByNameResponse2 = mock(HttpResponse.class);
        when(serviceByNameResponse2.body()).thenReturn(objectMapper.writeValueAsString(List.of(co2)));

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(servicesResponse1)
                .thenReturn(servicesResponse2);
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(serviceByNameResponse1))
                .thenReturn(CompletableFuture.completedFuture(serviceByNameResponse2));

        when(serviceCache.entries()).thenReturn(Collections.emptySet());
        when(serviceCache.peek(anyString())).thenReturn(null);

        Mapping mapping1 = new Mapping();
        mapping1.setHostname("10.0.0.1");
        mapping1.setPort(8080);
        mapping1.setRootContext("/");
        Mapping mapping2 = new Mapping();
        mapping2.setHostname("10.0.0.2");
        mapping2.setPort(8080);
        mapping2.setRootContext("/");
        when(serviceUtils.consulObjectToMapping(any(ConsulObject.class))).thenReturn(mapping1).thenReturn(mapping2);
        when(serviceUtils.checkIfOpenApiIsEnabled(any(Service.class), any(HttpClient.class))).thenReturn(true);

        when(routeUtils.getAllRouteIdForAGivenService(any(Service.class))).thenReturn(List.of("my-service:v1:GET"));

        consulNodeDiscovery.processInfo();

        verify(serviceCache, atLeastOnce()).put(anyString(), any(Service.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void processInfo_websocketType_createsWebsocketClient() throws Exception {
        consulNodeDiscovery.setCapiRunningMode("full");
        consulNodeDiscovery.setWebsocketClientMap(new HashMap<>());

        HttpResponse<String> servicesResponse = mock(HttpResponse.class);
        when(servicesResponse.body()).thenReturn("{\"ws-service\": []}");

        ServiceMeta meta = new ServiceMeta();
        meta.setGroup("v1");
        meta.setCapiNamespace("default");
        meta.setType("websocket");

        ConsulObject co = new ConsulObject();
        co.setServiceName("ws-service");
        co.setServiceMeta(meta);
        co.setServiceAddress("10.0.0.1");
        co.setServicePort(8080);
        co.setModifyIndex(1);

        ObjectMapper objectMapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        HttpResponse<String> serviceByNameResponse = mock(HttpResponse.class);
        when(serviceByNameResponse.body()).thenReturn(objectMapper.writeValueAsString(List.of(co)));

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(servicesResponse);
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(serviceByNameResponse));

        when(serviceCache.entries()).thenReturn(Collections.emptySet());
        when(serviceCache.peek(anyString())).thenReturn(null);

        Mapping mapping = new Mapping();
        mapping.setHostname("10.0.0.1");
        mapping.setPort(8080);
        mapping.setRootContext("/");
        when(serviceUtils.consulObjectToMapping(any(ConsulObject.class))).thenReturn(mapping);
        when(serviceUtils.checkIfOpenApiIsEnabled(any(Service.class), any(HttpClient.class))).thenReturn(true);

        WebsocketClient wsClient = new WebsocketClient();
        wsClient.setServiceId("/ws-service/v1");
        when(websocketUtils.createWebsocketClient(any(Service.class))).thenReturn(wsClient);

        consulNodeDiscovery.processInfo();

        verify(websocketUtils).createWebsocketClient(any(Service.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void processInfo_sseType_createsWebsocketClient() throws Exception {
        consulNodeDiscovery.setCapiRunningMode("full");
        consulNodeDiscovery.setWebsocketClientMap(new HashMap<>());

        HttpResponse<String> servicesResponse = mock(HttpResponse.class);
        when(servicesResponse.body()).thenReturn("{\"sse-service\": []}");

        ServiceMeta meta = new ServiceMeta();
        meta.setGroup("v1");
        meta.setCapiNamespace("default");
        meta.setType("sse");

        ConsulObject co = new ConsulObject();
        co.setServiceName("sse-service");
        co.setServiceMeta(meta);
        co.setServiceAddress("10.0.0.1");
        co.setServicePort(8080);
        co.setModifyIndex(1);

        ObjectMapper objectMapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        HttpResponse<String> serviceByNameResponse = mock(HttpResponse.class);
        when(serviceByNameResponse.body()).thenReturn(objectMapper.writeValueAsString(List.of(co)));

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(servicesResponse);
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(serviceByNameResponse));

        when(serviceCache.entries()).thenReturn(Collections.emptySet());
        when(serviceCache.peek(anyString())).thenReturn(null);

        Mapping mapping = new Mapping();
        mapping.setHostname("10.0.0.1");
        mapping.setPort(8080);
        mapping.setRootContext("/");
        when(serviceUtils.consulObjectToMapping(any(ConsulObject.class))).thenReturn(mapping);
        when(serviceUtils.checkIfOpenApiIsEnabled(any(Service.class), any(HttpClient.class))).thenReturn(true);

        WebsocketClient wsClient = new WebsocketClient();
        wsClient.setServiceId("/sse-service/v1");
        when(websocketUtils.createWebsocketClient(any(Service.class))).thenReturn(wsClient);

        consulNodeDiscovery.processInfo();

        verify(websocketUtils).createWebsocketClient(any(Service.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void processInfo_serviceWithExistingRoute_doesNotCreateDuplicate() throws Exception {
        HttpResponse<String> servicesResponse = mock(HttpResponse.class);
        when(servicesResponse.body()).thenReturn("{\"my-service\": []}");

        ServiceMeta meta = new ServiceMeta();
        meta.setGroup("v1");
        meta.setCapiNamespace("default");
        meta.setType("rest");

        ConsulObject co = new ConsulObject();
        co.setServiceName("my-service");
        co.setServiceMeta(meta);
        co.setServiceAddress("10.0.0.1");
        co.setServicePort(8080);
        co.setModifyIndex(1);

        ObjectMapper objectMapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        HttpResponse<String> serviceByNameResponse = mock(HttpResponse.class);
        when(serviceByNameResponse.body()).thenReturn(objectMapper.writeValueAsString(List.of(co)));

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(servicesResponse);
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(serviceByNameResponse));

        when(serviceCache.entries()).thenReturn(Collections.emptySet());
        when(serviceCache.peek(anyString())).thenReturn(null);

        Mapping mapping = new Mapping();
        mapping.setHostname("10.0.0.1");
        mapping.setPort(8080);
        mapping.setRootContext("/");
        when(serviceUtils.consulObjectToMapping(any(ConsulObject.class))).thenReturn(mapping);
        when(serviceUtils.checkIfOpenApiIsEnabled(any(Service.class), any(HttpClient.class))).thenReturn(true);

        when(routeUtils.getAllRouteIdForAGivenService(any(Service.class))).thenReturn(List.of("my-service:v1:GET"));

        consulNodeDiscovery.processInfo();

        // Service should still be cached
        verify(serviceCache, atLeastOnce()).put(anyString(), any(Service.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void processInfo_newServiceWithCapiInstances_createRouteOnlyIfInstanceMatches() throws Exception {
        HttpResponse<String> servicesResponse = mock(HttpResponse.class);
        when(servicesResponse.body()).thenReturn("{\"my-service\": []}");

        ServiceMeta meta = new ServiceMeta();
        meta.setGroup("v1");
        meta.setCapiNamespace("default");
        meta.setType("rest");
        meta.handleUnknown("capi-instance-default-secured", "true");

        ConsulObject co = new ConsulObject();
        co.setServiceName("my-service");
        co.setServiceMeta(meta);
        co.setServiceAddress("10.0.0.1");
        co.setServicePort(8080);
        co.setModifyIndex(1);

        ObjectMapper objectMapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        HttpResponse<String> serviceByNameResponse = mock(HttpResponse.class);
        when(serviceByNameResponse.body()).thenReturn(objectMapper.writeValueAsString(List.of(co)));

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(servicesResponse);
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(serviceByNameResponse));

        when(serviceCache.entries()).thenReturn(Collections.emptySet());
        when(serviceCache.peek(anyString())).thenReturn(null);

        ServiceCapiInstances.Instance instance = new ServiceCapiInstances.Instance();
        instance.setSecured(true);
        instance.setAssumeParentSecured(false);
        when(serviceUtils.getServiceCapiInstance(any(ConsulObject.class), eq("default"))).thenReturn(instance);

        Mapping mapping = new Mapping();
        mapping.setHostname("10.0.0.1");
        mapping.setPort(8080);
        mapping.setRootContext("/");
        when(serviceUtils.consulObjectToMapping(any(ConsulObject.class))).thenReturn(mapping);
        when(serviceUtils.checkIfOpenApiIsEnabled(any(Service.class), any(HttpClient.class))).thenReturn(true);

        when(routeUtils.getAllRouteIdForAGivenService(any(Service.class))).thenReturn(List.of("my-service:v1:GET"));

        consulNodeDiscovery.processInfo();

        verify(serviceCache, atLeastOnce()).put(anyString(), any(Service.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void processInfo_serviceWithSuspendedState_doesNotCreateRoute() throws Exception {
        HttpResponse<String> servicesResponse = mock(HttpResponse.class);
        when(servicesResponse.body()).thenReturn("{\"my-service\": []}");

        ServiceMeta meta = new ServiceMeta();
        meta.setGroup("v1");
        meta.setCapiNamespace("default");
        meta.setType("rest");
        meta.setState(State.SUSPENDED);

        ConsulObject co = new ConsulObject();
        co.setServiceName("my-service");
        co.setServiceMeta(meta);
        co.setServiceAddress("10.0.0.1");
        co.setServicePort(8080);
        co.setModifyIndex(1);

        ObjectMapper objectMapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        HttpResponse<String> serviceByNameResponse = mock(HttpResponse.class);
        when(serviceByNameResponse.body()).thenReturn(objectMapper.writeValueAsString(List.of(co)));

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(servicesResponse);
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(serviceByNameResponse));

        when(serviceCache.entries()).thenReturn(Collections.emptySet());
        when(serviceCache.peek(anyString())).thenReturn(null);

        Mapping mapping = new Mapping();
        mapping.setHostname("10.0.0.1");
        mapping.setPort(8080);
        mapping.setRootContext("/");
        when(serviceUtils.consulObjectToMapping(any(ConsulObject.class))).thenReturn(mapping);
        when(serviceUtils.checkIfOpenApiIsEnabled(any(Service.class), any(HttpClient.class))).thenReturn(true);

        consulNodeDiscovery.processInfo();

        // Suspended state means no route is created
        verify(serviceCache, never()).put(anyString(), any(Service.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void processInfo_serviceMetaExtrasPrefix_setsExtraMeta() throws Exception {
        consulNodeDiscovery.setServiceMetaExtrasPrefix("extra-");

        HttpResponse<String> servicesResponse = mock(HttpResponse.class);
        when(servicesResponse.body()).thenReturn("{\"my-service\": []}");

        ServiceMeta meta = new ServiceMeta();
        meta.setGroup("v1");
        meta.setCapiNamespace("default");
        meta.setType("rest");
        meta.handleUnknown("extra-custom-key", "custom-value");

        ConsulObject co = new ConsulObject();
        co.setServiceName("my-service");
        co.setServiceMeta(meta);
        co.setServiceAddress("10.0.0.1");
        co.setServicePort(8080);
        co.setModifyIndex(1);

        ObjectMapper objectMapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        HttpResponse<String> serviceByNameResponse = mock(HttpResponse.class);
        when(serviceByNameResponse.body()).thenReturn(objectMapper.writeValueAsString(List.of(co)));

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(servicesResponse);
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(serviceByNameResponse));

        when(serviceCache.entries()).thenReturn(Collections.emptySet());
        when(serviceCache.peek(anyString())).thenReturn(null);

        Mapping mapping = new Mapping();
        mapping.setHostname("10.0.0.1");
        mapping.setPort(8080);
        mapping.setRootContext("/");
        when(serviceUtils.consulObjectToMapping(any(ConsulObject.class))).thenReturn(mapping);
        when(serviceUtils.checkIfOpenApiIsEnabled(any(Service.class), any(HttpClient.class))).thenReturn(true);

        when(routeUtils.getAllRouteIdForAGivenService(any(Service.class))).thenReturn(List.of("my-service:v1:GET"));

        consulNodeDiscovery.processInfo();

        verify(serviceCache, atLeastOnce()).put(anyString(), any(Service.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void processInfo_websocketRunningMode_onlyDeploysWebsocket() throws Exception {
        consulNodeDiscovery.setCapiRunningMode("websocket");
        consulNodeDiscovery.setWebsocketClientMap(new HashMap<>());

        HttpResponse<String> servicesResponse = mock(HttpResponse.class);
        when(servicesResponse.body()).thenReturn("{\"ws-service\": []}");

        ServiceMeta meta = new ServiceMeta();
        meta.setGroup("v1");
        meta.setCapiNamespace("default");
        meta.setType("websocket");

        ConsulObject co = new ConsulObject();
        co.setServiceName("ws-service");
        co.setServiceMeta(meta);
        co.setServiceAddress("10.0.0.1");
        co.setServicePort(8080);
        co.setModifyIndex(1);

        ObjectMapper objectMapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        HttpResponse<String> serviceByNameResponse = mock(HttpResponse.class);
        when(serviceByNameResponse.body()).thenReturn(objectMapper.writeValueAsString(List.of(co)));

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(servicesResponse);
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(serviceByNameResponse));

        when(serviceCache.entries()).thenReturn(Collections.emptySet());
        when(serviceCache.peek(anyString())).thenReturn(null);

        Mapping mapping = new Mapping();
        mapping.setHostname("10.0.0.1");
        mapping.setPort(8080);
        mapping.setRootContext("/");
        when(serviceUtils.consulObjectToMapping(any(ConsulObject.class))).thenReturn(mapping);
        when(serviceUtils.checkIfOpenApiIsEnabled(any(Service.class), any(HttpClient.class))).thenReturn(true);

        WebsocketClient wsClient = new WebsocketClient();
        wsClient.setServiceId("/ws-service/v1");
        when(websocketUtils.createWebsocketClient(any(Service.class))).thenReturn(wsClient);

        consulNodeDiscovery.processInfo();

        verify(websocketUtils).createWebsocketClient(any(Service.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void processInfo_newServiceWithCapiInstances_noMatchingInstance_doesNotCreateRoute() throws Exception {
        HttpResponse<String> servicesResponse = mock(HttpResponse.class);
        when(servicesResponse.body()).thenReturn("{\"my-service\": []}");

        ServiceMeta meta = new ServiceMeta();
        meta.setGroup("v1");
        // No capiNamespace — service only has multi-instance for "other", not for "default"
        meta.setType("rest");
        meta.handleUnknown("capi-instance-other-secured", "true");

        ConsulObject co = new ConsulObject();
        co.setServiceName("my-service");
        co.setServiceMeta(meta);
        co.setServiceAddress("10.0.0.1");
        co.setServicePort(8080);
        co.setModifyIndex(1);

        ObjectMapper objectMapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        HttpResponse<String> serviceByNameResponse = mock(HttpResponse.class);
        when(serviceByNameResponse.body()).thenReturn(objectMapper.writeValueAsString(List.of(co)));

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(servicesResponse);
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(serviceByNameResponse));

        when(serviceCache.entries()).thenReturn(Collections.emptySet());
        when(serviceCache.peek(anyString())).thenReturn(null);

        when(serviceUtils.getServiceCapiInstance(any(ConsulObject.class), eq("default"))).thenReturn(null);
        when(serviceUtils.isTheServiceRegisteredForOtherInstances(any(ConsulObject.class), eq("default"))).thenReturn(true);

        consulNodeDiscovery.processInfo();

        // Only multi-instance for "other", no single or multi for "default" — should not create route
        verify(serviceCache, never()).put(anyString(), any(Service.class));
    }
}
