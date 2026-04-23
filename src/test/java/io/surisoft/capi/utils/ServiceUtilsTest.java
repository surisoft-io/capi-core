package io.surisoft.capi.utils;

import io.surisoft.capi.schema.*;
import org.cache2k.Cache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ServiceUtilsTest {

    @Mock
    private RouteUtils routeUtils;
    @Mock
    private Cache<String, Service> serviceCache;
    @Mock
    private WebsocketUtils websocketUtils;

    private ServiceUtils serviceUtils;
    private HttpUtils httpUtils;

    @BeforeEach
    void setUp() {
        httpUtils = new HttpUtils(null, null);
        serviceUtils = new ServiceUtils(httpUtils, Optional.empty(), null, Optional.empty(), "full");
    }

    @Test
    void getServiceId_returnsNameColonGroup() {
        Service service = createService("my-service", "dev");
        assertEquals("my-service:dev", serviceUtils.getServiceId(service));
    }

    @Test
    void getServiceIdFromPath_validPath() {
        assertEquals("sample-service:dev", serviceUtils.getServiceIdFromPath("/sample-service/dev/some/path"));
    }

    @Test
    void getServiceIdFromPath_exactTwoSegments() {
        assertEquals("sample-service:dev", serviceUtils.getServiceIdFromPath("/sample-service/dev"));
    }

    @Test
    void getServiceIdFromPath_withoutLeadingSlash() {
        assertEquals("sample-service:dev", serviceUtils.getServiceIdFromPath("sample-service/dev"));
    }

    @Test
    void getServiceIdFromPath_singleSegment_returnsNull() {
        assertNull(serviceUtils.getServiceIdFromPath("/single"));
    }

    @Test
    void getServiceIdFromPath_null_returnsNull() {
        assertNull(serviceUtils.getServiceIdFromPath(null));
    }

    @Test
    void getServiceIdFromPath_empty_returnsNull() {
        assertNull(serviceUtils.getServiceIdFromPath(""));
    }

    @Test
    void validateServiceType_nullType_setsRest() {
        Service service = createService("svc", "dev");
        service.getServiceMeta().setType(null);
        serviceUtils.validateServiceType(service);
        assertEquals("rest", service.getServiceMeta().getType());
    }

    @Test
    void validateServiceType_existingType_unchanged() {
        Service service = createService("svc", "dev");
        service.getServiceMeta().setType("websocket");
        serviceUtils.validateServiceType(service);
        assertEquals("websocket", service.getServiceMeta().getType());
    }

    @Test
    void didServiceChange_sameMappings_returnsFalse() {
        Service existing = createServiceWithMapping("svc", "dev", "host1", 8080);
        Service incoming = createServiceWithMapping("svc", "dev", "host1", 8080);
        assertFalse(serviceUtils.didServiceChange(existing, incoming));
    }

    @Test
    void didServiceChange_differentMappingSize_returnsTrue() {
        Service existing = createServiceWithMapping("svc", "dev", "host1", 8080);
        Service incoming = createService("svc", "dev");
        incoming.setMappingList(new HashSet<>());
        assertTrue(serviceUtils.didServiceChange(existing, incoming));
    }

    @Test
    void didServiceChange_securedChanged_returnsTrue() {
        Service existing = createServiceWithMapping("svc", "dev", "host1", 8080);
        Service incoming = createServiceWithMapping("svc", "dev", "host1", 8080);
        incoming.getServiceMeta().setSecured(true);
        assertTrue(serviceUtils.didServiceChange(existing, incoming));
    }

    @Test
    void isMappingChanged_sameList_returnsFalse() {
        Mapping m = new Mapping();
        m.setHostname("host1");
        m.setPort(8080);
        m.setRootContext("/");
        assertFalse(serviceUtils.isMappingChanged(List.of(m), List.of(m)));
    }

    @Test
    void isMappingChanged_differentSize_returnsTrue() {
        Mapping m = new Mapping();
        m.setHostname("host1");
        m.setPort(8080);
        m.setRootContext("/");
        assertTrue(serviceUtils.isMappingChanged(List.of(m), List.of()));
    }

    // --- consulObjectToMapping tests ---

    @Test
    void consulObjectToMapping_withIngress_httpEndpoint() {
        ConsulObject co = new ConsulObject();
        co.setServiceAddress("10.0.0.1");
        co.setServicePort(8080);
        ServiceMeta meta = new ServiceMeta();
        meta.setIngress("http://my-ingress.example.com");
        meta.setGroup("v1");
        co.setServiceMeta(meta);

        Mapping result = serviceUtils.consulObjectToMapping(co);
        assertEquals("my-ingress.example.com", result.getHostname());
        assertEquals(Constants.HTTP_PORT, result.getPort());
        assertTrue(result.isIngress());
        assertEquals("/", result.getRootContext());
    }

    @Test
    void consulObjectToMapping_withIngress_httpsEndpoint() {
        ConsulObject co = new ConsulObject();
        co.setServiceAddress("10.0.0.1");
        co.setServicePort(8080);
        ServiceMeta meta = new ServiceMeta();
        meta.setIngress("https://secure-ingress.example.com");
        meta.setGroup("v1");
        co.setServiceMeta(meta);

        Mapping result = serviceUtils.consulObjectToMapping(co);
        assertEquals("secure-ingress.example.com", result.getHostname());
        assertEquals(Constants.HTTPS_PORT, result.getPort());
        assertTrue(result.isIngress());
    }

    @Test
    void consulObjectToMapping_withoutIngress_usesHostAndPort() {
        ConsulObject co = new ConsulObject();
        co.setServiceAddress("192.168.1.100");
        co.setServicePort(9090);
        ServiceMeta meta = new ServiceMeta();
        meta.setGroup("v1");
        co.setServiceMeta(meta);

        Mapping result = serviceUtils.consulObjectToMapping(co);
        assertEquals("192.168.1.100", result.getHostname());
        assertEquals(9090, result.getPort());
        assertFalse(result.isIngress());
        assertEquals("/", result.getRootContext());
    }

    @Test
    void consulObjectToMapping_withRootContext_startingWithSlash() {
        ConsulObject co = new ConsulObject();
        co.setServiceAddress("10.0.0.1");
        co.setServicePort(8080);
        ServiceMeta meta = new ServiceMeta();
        meta.setGroup("v1");
        meta.setRootContext("/api/v1");
        co.setServiceMeta(meta);

        Mapping result = serviceUtils.consulObjectToMapping(co);
        assertEquals("/api/v1", result.getRootContext());
    }

    @Test
    void consulObjectToMapping_withRootContext_notStartingWithSlash() {
        ConsulObject co = new ConsulObject();
        co.setServiceAddress("10.0.0.1");
        co.setServicePort(8080);
        ServiceMeta meta = new ServiceMeta();
        meta.setGroup("v1");
        meta.setRootContext("api/v1");
        co.setServiceMeta(meta);

        Mapping result = serviceUtils.consulObjectToMapping(co);
        assertEquals("/api/v1", result.getRootContext());
    }

    @Test
    void consulObjectToMapping_withEmptyRootContext() {
        ConsulObject co = new ConsulObject();
        co.setServiceAddress("10.0.0.1");
        co.setServicePort(8080);
        ServiceMeta meta = new ServiceMeta();
        meta.setGroup("v1");
        meta.setRootContext("");
        co.setServiceMeta(meta);

        Mapping result = serviceUtils.consulObjectToMapping(co);
        assertEquals("/", result.getRootContext());
    }

    @Test
    void consulObjectToMapping_withNullRootContext() {
        ConsulObject co = new ConsulObject();
        co.setServiceAddress("10.0.0.1");
        co.setServicePort(8080);
        ServiceMeta meta = new ServiceMeta();
        meta.setGroup("v1");
        co.setServiceMeta(meta);

        Mapping result = serviceUtils.consulObjectToMapping(co);
        assertEquals("/", result.getRootContext());
    }

    // --- didServiceChange additional branch tests ---

    @Test
    void didServiceChange_differentMapping_returnsTrue() {
        Service existing = createServiceWithMapping("svc", "dev", "host1", 8080);
        Service incoming = createServiceWithMapping("svc", "dev", "host2", 8080);
        assertTrue(serviceUtils.didServiceChange(existing, incoming));
    }

    @Test
    void didServiceChange_openApiEndpointChangedFromNullToValue_returnsTrue() {
        Service existing = createServiceWithMapping("svc", "dev", "host1", 8080);
        Service incoming = createServiceWithMapping("svc", "dev", "host1", 8080);
        incoming.getServiceMeta().setOpenApiEndpoint("http://example.com/api-docs");
        assertTrue(serviceUtils.didServiceChange(existing, incoming));
    }

    @Test
    void didServiceChange_openApiEndpointChangedFromValueToNull_returnsTrue() {
        Service existing = createServiceWithMapping("svc", "dev", "host1", 8080);
        existing.getServiceMeta().setOpenApiEndpoint("http://example.com/api-docs");
        Service incoming = createServiceWithMapping("svc", "dev", "host1", 8080);
        assertTrue(serviceUtils.didServiceChange(existing, incoming));
    }

    @Test
    void didServiceChange_openApiEndpointChangedValues_returnsTrue() {
        Service existing = createServiceWithMapping("svc", "dev", "host1", 8080);
        existing.getServiceMeta().setOpenApiEndpoint("http://example.com/old");
        Service incoming = createServiceWithMapping("svc", "dev", "host1", 8080);
        incoming.getServiceMeta().setOpenApiEndpoint("http://example.com/new");
        assertTrue(serviceUtils.didServiceChange(existing, incoming));
    }

    @Test
    void didServiceChange_openApiEndpointBothSame_returnsFalse() {
        Service existing = createServiceWithMapping("svc", "dev", "host1", 8080);
        existing.getServiceMeta().setOpenApiEndpoint("http://example.com/same");
        Service incoming = createServiceWithMapping("svc", "dev", "host1", 8080);
        incoming.getServiceMeta().setOpenApiEndpoint("http://example.com/same");
        assertFalse(serviceUtils.didServiceChange(existing, incoming));
    }

    @Test
    void didServiceChange_openApiEndpointBothNull_returnsFalse() {
        Service existing = createServiceWithMapping("svc", "dev", "host1", 8080);
        Service incoming = createServiceWithMapping("svc", "dev", "host1", 8080);
        assertFalse(serviceUtils.didServiceChange(existing, incoming));
    }

    @Test
    void didServiceChange_routeGroupFirstChanged_returnsTrue() {
        Service existing = createServiceWithMapping("svc", "dev", "host1", 8080);
        Service incoming = createServiceWithMapping("svc", "dev", "host1", 8080);
        incoming.getServiceMeta().setRouteGroupFirst(true);
        assertTrue(serviceUtils.didServiceChange(existing, incoming));
    }

    @Test
    void didServiceChange_versionChangedFromNullToValue_returnsTrue() {
        Service existing = createServiceWithMapping("svc", "dev", "host1", 8080);
        Service incoming = createServiceWithMapping("svc", "dev", "host1", 8080);
        incoming.getServiceMeta().setVersion("2.0");
        assertTrue(serviceUtils.didServiceChange(existing, incoming));
    }

    @Test
    void didServiceChange_versionChangedFromValueToNull_returnsTrue() {
        Service existing = createServiceWithMapping("svc", "dev", "host1", 8080);
        existing.getServiceMeta().setVersion("1.0");
        Service incoming = createServiceWithMapping("svc", "dev", "host1", 8080);
        assertTrue(serviceUtils.didServiceChange(existing, incoming));
    }

    @Test
    void didServiceChange_versionChangedValues_returnsTrue() {
        Service existing = createServiceWithMapping("svc", "dev", "host1", 8080);
        existing.getServiceMeta().setVersion("1.0");
        Service incoming = createServiceWithMapping("svc", "dev", "host1", 8080);
        incoming.getServiceMeta().setVersion("2.0");
        assertTrue(serviceUtils.didServiceChange(existing, incoming));
    }

    @Test
    void didServiceChange_versionBothSame_returnsFalse() {
        Service existing = createServiceWithMapping("svc", "dev", "host1", 8080);
        existing.getServiceMeta().setVersion("1.0");
        Service incoming = createServiceWithMapping("svc", "dev", "host1", 8080);
        incoming.getServiceMeta().setVersion("1.0");
        assertFalse(serviceUtils.didServiceChange(existing, incoming));
    }

    @Test
    void didServiceChange_subscriptionGroupChangedFromNullToValue_returnsTrue() {
        Service existing = createServiceWithMapping("svc", "dev", "host1", 8080);
        Service incoming = createServiceWithMapping("svc", "dev", "host1", 8080);
        incoming.getServiceMeta().setSubscriptionGroup("group-a");
        assertTrue(serviceUtils.didServiceChange(existing, incoming));
    }

    @Test
    void didServiceChange_subscriptionGroupChangedFromValueToNull_returnsTrue() {
        Service existing = createServiceWithMapping("svc", "dev", "host1", 8080);
        existing.getServiceMeta().setSubscriptionGroup("group-a");
        Service incoming = createServiceWithMapping("svc", "dev", "host1", 8080);
        assertTrue(serviceUtils.didServiceChange(existing, incoming));
    }

    @Test
    void didServiceChange_subscriptionGroupChangedValues_returnsTrue() {
        Service existing = createServiceWithMapping("svc", "dev", "host1", 8080);
        existing.getServiceMeta().setSubscriptionGroup("group-a");
        Service incoming = createServiceWithMapping("svc", "dev", "host1", 8080);
        incoming.getServiceMeta().setSubscriptionGroup("group-b");
        assertTrue(serviceUtils.didServiceChange(existing, incoming));
    }

    @Test
    void didServiceChange_subscriptionGroupBothSame_returnsFalse() {
        Service existing = createServiceWithMapping("svc", "dev", "host1", 8080);
        existing.getServiceMeta().setSubscriptionGroup("group-a");
        Service incoming = createServiceWithMapping("svc", "dev", "host1", 8080);
        incoming.getServiceMeta().setSubscriptionGroup("group-a");
        assertFalse(serviceUtils.didServiceChange(existing, incoming));
    }

    // --- isMappingChanged additional tests ---

    @Test
    void isMappingChanged_differentContent_returnsTrue() {
        Mapping m1 = new Mapping();
        m1.setHostname("host1");
        m1.setPort(8080);
        m1.setRootContext("/");
        Mapping m2 = new Mapping();
        m2.setHostname("host2");
        m2.setPort(8080);
        m2.setRootContext("/");
        assertTrue(serviceUtils.isMappingChanged(List.of(m1), List.of(m2)));
    }

    // updateExistingService / removeUnusedService tests removed — logic migrated to transport handlers.
    // Handler-level tests belong in a follow-up PR.

    // --- checkIfOpenApiIsEnabled tests ---

    @Test
    void checkIfOpenApiIsEnabled_notFullMode_returnsTrue() {
        ServiceUtils notFullServiceUtils = new ServiceUtils(httpUtils, Optional.empty(), routeUtils, Optional.empty(), "websocket");

        Service service = createService("svc", "dev");
        service.getServiceMeta().setOpenApiEndpoint("http://example.com/api-docs");

        assertTrue(notFullServiceUtils.checkIfOpenApiIsEnabled(service, null));
    }

    @Test
    void checkIfOpenApiIsEnabled_noOpenApiEndpoint_returnsTrue() {
        Service service = createService("svc", "dev");
        // openApiEndpoint is null by default
        assertTrue(serviceUtils.checkIfOpenApiIsEnabled(service, null));
    }

    @Test
    void checkIfOpenApiIsEnabled_emptyOpenApiEndpoint_returnsTrue() {
        Service service = createService("svc", "dev");
        service.getServiceMeta().setOpenApiEndpoint("");
        assertTrue(serviceUtils.checkIfOpenApiIsEnabled(service, null));
    }

    @Test
    void checkIfOpenApiIsEnabled_nullServiceMeta_returnsTrue() {
        Service service = new Service();
        service.setServiceMeta(null);
        assertTrue(serviceUtils.checkIfOpenApiIsEnabled(service, null));
    }

    @SuppressWarnings("unchecked")
    @Test
    void checkIfOpenApiIsEnabled_pathTraversalDetected_returnsFalse() {
        Service service = createService("svc", "dev");
        service.getServiceMeta().setOpenApiEndpoint("http://example.com/../etc/passwd");

        boolean result = serviceUtils.checkIfOpenApiIsEnabled(service, mock(HttpClient.class));
        assertFalse(result);
    }

    @SuppressWarnings("unchecked")
    @Test
    void checkIfOpenApiIsEnabled_invalidUri_returnsFalse() {
        Service service = createService("svc", "dev");
        service.getServiceMeta().setOpenApiEndpoint("not a valid uri with spaces");

        boolean result = serviceUtils.checkIfOpenApiIsEnabled(service, mock(HttpClient.class));
        assertFalse(result);
    }

    @SuppressWarnings("unchecked")
    @Test
    void checkIfOpenApiIsEnabled_non200Response_returnsFalse() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(404);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

        Service service = createService("svc", "dev");
        service.setId("svc:dev");
        service.getServiceMeta().setOpenApiEndpoint("http://example.com/api-docs");

        boolean result = serviceUtils.checkIfOpenApiIsEnabled(service, mockHttpClient);
        assertFalse(result);
    }

    @SuppressWarnings("unchecked")
    @Test
    void checkIfOpenApiIsEnabled_ioException_returnsFalse() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new java.io.IOException("Connection refused"));

        Service service = createService("svc", "dev");
        service.setId("svc:dev");
        service.getServiceMeta().setOpenApiEndpoint("http://example.com/api-docs");

        boolean result = serviceUtils.checkIfOpenApiIsEnabled(service, mockHttpClient);
        assertFalse(result);
    }

    @SuppressWarnings("unchecked")
    @Test
    void checkIfOpenApiIsEnabled_200ButInvalidOpenApiSpec_returnsFalse() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("this is not valid openapi json or yaml");
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

        Service service = createService("svc", "dev");
        service.setId("svc:dev");
        service.getServiceMeta().setOpenApiEndpoint("http://example.com/api-docs");

        boolean result = serviceUtils.checkIfOpenApiIsEnabled(service, mockHttpClient);
        // OpenAPI parser may return null for invalid content
        assertFalse(result);
    }

    @SuppressWarnings("unchecked")
    @Test
    void checkIfOpenApiIsEnabled_200WithValidOpenApiSpec_returnsTrue() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        String validOpenApi = "{\n  \"openapi\": \"3.0.0\",\n  \"info\": { \"title\": \"Test\", \"version\": \"1.0\" },\n  \"paths\": {}\n}";
        when(mockResponse.body()).thenReturn(validOpenApi);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

        Service service = createService("svc", "dev");
        service.setId("svc:dev");
        service.getServiceMeta().setOpenApiEndpoint("http://example.com/api-docs");

        boolean result = serviceUtils.checkIfOpenApiIsEnabled(service, mockHttpClient);
        assertTrue(result);
        assertNotNull(service.getOpenAPI());
    }

    // --- getServiceCapiInstance tests ---

    @Test
    void getServiceCapiInstance_noCapiInstanceProperties_returnsNull() {
        ConsulObject co = new ConsulObject();
        ServiceMeta meta = new ServiceMeta();
        meta.setGroup("v1");
        co.setServiceMeta(meta);

        assertNull(serviceUtils.getServiceCapiInstance(co, "default"));
    }

    @Test
    void getServiceCapiInstance_withMatchingInstance_returnsInstance() {
        ConsulObject co = new ConsulObject();
        ServiceMeta meta = new ServiceMeta();
        meta.setGroup("v1");
        meta.handleUnknown("capi-instance-default-secured", "true");
        co.setServiceMeta(meta);

        ServiceCapiInstances.Instance result = serviceUtils.getServiceCapiInstance(co, "default");
        assertNotNull(result);
        assertTrue(result.isSecured());
    }

    @Test
    void getServiceCapiInstance_withNonMatchingInstance_returnsNull() {
        ConsulObject co = new ConsulObject();
        ServiceMeta meta = new ServiceMeta();
        meta.setGroup("v1");
        meta.handleUnknown("capi-instance-other-secured", "true");
        co.setServiceMeta(meta);

        assertNull(serviceUtils.getServiceCapiInstance(co, "default"));
    }

    // --- isTheServiceRegisteredForOtherInstances tests ---

    @Test
    void isTheServiceRegisteredForOtherInstances_hasInstancePrefix_returnsTrue() {
        ConsulObject co = new ConsulObject();
        ServiceMeta meta = new ServiceMeta();
        meta.handleUnknown("capi-instance-other-secured", "true");
        co.setServiceMeta(meta);

        assertTrue(serviceUtils.isTheServiceRegisteredForOtherInstances(co, "default"));
    }

    @Test
    void isTheServiceRegisteredForOtherInstances_noInstancePrefix_returnsFalse() {
        ConsulObject co = new ConsulObject();
        ServiceMeta meta = new ServiceMeta();
        meta.handleUnknown("some-other-key", "value");
        co.setServiceMeta(meta);

        assertFalse(serviceUtils.isTheServiceRegisteredForOtherInstances(co, "default"));
    }

    @Test
    void isTheServiceRegisteredForOtherInstances_emptyUnknownProps_returnsFalse() {
        ConsulObject co = new ConsulObject();
        ServiceMeta meta = new ServiceMeta();
        co.setServiceMeta(meta);

        assertFalse(serviceUtils.isTheServiceRegisteredForOtherInstances(co, "default"));
    }

    // --- getServiceIdFromPath edge cases ---

    @Test
    void getServiceIdFromPath_slashOnly_returnsNull() {
        assertNull(serviceUtils.getServiceIdFromPath("/"));
    }

    @Test
    void getServiceIdFromPath_doubleSlash_returnsNull() {
        // "//" -> first="" second="" -> both empty -> null
        assertNull(serviceUtils.getServiceIdFromPath("//"));
    }

    @Test
    void getServiceIdFromPath_trailingSlashNoSecondSegment() {
        // "/abc/" -> first="abc", second="" -> empty -> null
        assertNull(serviceUtils.getServiceIdFromPath("/abc/"));
    }

    private Service createService(String name, String group) {
        Service service = new Service();
        service.setName(name);
        ServiceMeta meta = new ServiceMeta();
        meta.setGroup(group);
        service.setServiceMeta(meta);
        return service;
    }

    private Service createServiceWithMapping(String name, String group, String host, int port) {
        Service service = createService(name, group);
        Mapping mapping = new Mapping();
        mapping.setHostname(host);
        mapping.setPort(port);
        mapping.setRootContext("/");
        Set<Mapping> mappings = new HashSet<>();
        mappings.add(mapping);
        service.setMappingList(mappings);
        return service;
    }

    @Test
    void stripJsonNulls_removesTopLevelNulls() {
        String body = "{\"a\":\"x\",\"b\":null,\"c\":1}";
        String result = serviceUtils.stripJsonNulls(body);
        assertFalse(result.contains("\"b\""));
        assertTrue(result.contains("\"a\":\"x\""));
        assertTrue(result.contains("\"c\":1"));
    }

    @Test
    void stripJsonNulls_removesNestedNulls() {
        String body = "{\"outer\":{\"inner\":null,\"keep\":\"ok\"}}";
        String result = serviceUtils.stripJsonNulls(body);
        assertFalse(result.contains("\"inner\""));
        assertTrue(result.contains("\"keep\":\"ok\""));
    }

    @Test
    void stripJsonNulls_handlesNullsInsideArrayElements() {
        String body = "{\"list\":[{\"keep\":1,\"drop\":null},{\"keep\":2}]}";
        String result = serviceUtils.stripJsonNulls(body);
        assertFalse(result.contains("\"drop\""));
        assertTrue(result.contains("\"keep\":1"));
        assertTrue(result.contains("\"keep\":2"));
    }

    @Test
    void stripJsonNulls_preservesEmptyObjectsAndArrays() {
        String body = "{\"obj\":{},\"arr\":[]}";
        String result = serviceUtils.stripJsonNulls(body);
        assertTrue(result.contains("\"obj\":{}"));
        assertTrue(result.contains("\"arr\":[]"));
    }

    @Test
    void stripJsonNulls_malformedJson_returnsOriginal() {
        String body = "not json at all";
        assertEquals(body, serviceUtils.stripJsonNulls(body));
    }

    @SuppressWarnings("unchecked")
    @Test
    void processOpenApiSpec_openApi31WithExplicitNulls_parsesSuccessfully() {
        // Minimal OpenAPI 3.1 spec with the null pollution pattern emitted by
        // springdoc without @JsonInclude(NON_NULL) — this shape is what triggered
        // NullNode → ObjectNode cast in OpenAPIV3Parser on babel's real spec.
        String spec = "{"
                + "\"openapi\":\"3.1.0\","
                + "\"info\":{\"title\":\"t\",\"version\":\"1\",\"termsOfService\":null,"
                +   "\"contact\":{\"name\":\"x\",\"url\":null,\"email\":\"a@b\",\"extensions\":null},"
                +   "\"license\":{\"name\":\"l\",\"url\":null,\"identifier\":null,\"extensions\":null},"
                +   "\"extensions\":null,\"summary\":null},"
                + "\"externalDocs\":{\"description\":\"d\",\"url\":\"https://x\",\"extensions\":null},"
                + "\"servers\":[{\"url\":\"https://s\",\"description\":\"d\",\"variables\":null,\"extensions\":null}],"
                + "\"security\":null,"
                + "\"paths\":{\"/ping\":{\"get\":{\"responses\":{\"200\":{\"description\":\"ok\"}}}}}"
                + "}";

        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(spec);

        Service service = createService("test", "dev");
        assertTrue(serviceUtils.processOpenApiSpec(service, response),
                "spec with explicit nulls should parse after null-stripping");
        assertNotNull(service.getOpenAPI());
        assertEquals("3.1.0", service.getOpenAPI().getOpenapi());
        assertNotNull(service.getOpenAPI().getPaths());
        assertTrue(service.getOpenAPI().getPaths().containsKey("/ping"));
    }
}