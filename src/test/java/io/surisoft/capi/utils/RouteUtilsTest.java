package io.surisoft.capi.utils;

import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.surisoft.capi.processor.AuthorizationProcessor;
import io.surisoft.capi.processor.HttpErrorProcessor;
import io.surisoft.capi.schema.*;
import io.surisoft.capi.service.OpaService;
import io.surisoft.capi.tracer.CapiTracer;
import io.swagger.v3.oas.models.OpenAPI;
import org.apache.camel.CamelContext;
import org.apache.camel.Route;
import org.apache.camel.model.RouteDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
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

    // ---------------------------------------------------------------
    // buildEndpoints - null scheme (defaults to http)
    // ---------------------------------------------------------------

    @Test
    void buildEndpoints_nullScheme_defaultsToHttp() {
        Service service = createServiceWithMapping("host1", 8080, null, "/");
        String[] endpoints = routeUtils.buildEndpoints(service);
        assertEquals(1, endpoints.length);
        assertTrue(endpoints[0].startsWith("http://host1:8080/"));
    }

    @Test
    void buildEndpoints_httpScheme_explicit() {
        Service service = createServiceWithMapping("host1", 8080, "http", "/api");
        String[] endpoints = routeUtils.buildEndpoints(service);
        assertTrue(endpoints[0].startsWith("http://host1:8080/api"));
    }

    @Test
    void buildEndpoints_multipleMappings() {
        Service service = new Service();
        ServiceMeta meta = new ServiceMeta();
        meta.setScheme("http");
        service.setServiceMeta(meta);

        Mapping m1 = new Mapping();
        m1.setHostname("host1");
        m1.setPort(8080);
        m1.setRootContext("/");

        Mapping m2 = new Mapping();
        m2.setHostname("host2");
        m2.setPort(9090);
        m2.setRootContext("/api");

        Set<Mapping> mappings = new HashSet<>();
        mappings.add(m1);
        mappings.add(m2);
        service.setMappingList(mappings);

        String[] endpoints = routeUtils.buildEndpoints(service);
        assertEquals(2, endpoints.length);
    }

    @Test
    void buildEndpoints_withIngressMapping() {
        HttpUtils httpUtilsSpy = new HttpUtils(null, null);
        RouteUtils routeUtilsWithIngress = new RouteUtils(
                null, httpUtilsSpy, mock(CompositeMeterRegistry.class), null,
                camelContext, null, false, null,
                120000, 5000, 5000
        );

        Service service = new Service();
        ServiceMeta meta = new ServiceMeta();
        meta.setScheme("http");
        service.setServiceMeta(meta);

        Mapping m = new Mapping();
        m.setHostname("ingress-host");
        m.setPort(80);
        m.setRootContext("/");
        m.setIngress(true);

        Set<Mapping> mappings = new HashSet<>();
        mappings.add(m);
        service.setMappingList(mappings);

        String[] endpoints = routeUtilsWithIngress.buildEndpoints(service);
        assertEquals(1, endpoints.length);
        assertTrue(endpoints[0].contains("customHostHeader=ingress-host"));
    }

    @Test
    void buildEndpoints_withCorsEnabled() {
        RouteUtils routeUtilsCors = new RouteUtils(
                null, new HttpUtils(null, null), mock(CompositeMeterRegistry.class), null,
                camelContext, null, true, null,
                120000, 5000, 5000
        );

        Service service = createServiceWithMapping("host1", 8080, "http", "/");
        String[] endpoints = routeUtilsCors.buildEndpoints(service);
        assertEquals(1, endpoints.length);
        assertTrue(endpoints[0].contains("headerFilterStrategy=#capiCorsFilterStrategy"));
    }

    // ---------------------------------------------------------------
    // registerMetric
    // ---------------------------------------------------------------

    @Test
    void registerMetric_callsCounter() {
        CompositeMeterRegistry registry = new CompositeMeterRegistry();
        RouteUtils ru = new RouteUtils(
                null, new HttpUtils(null, null), registry, null,
                camelContext, null, false, null,
                120000, 5000, 5000
        );

        assertDoesNotThrow(() -> ru.registerMetric("test-route-id"));
    }

    // ---------------------------------------------------------------
    // registerTracer
    // ---------------------------------------------------------------

    @Test
    void registerTracer_withNullTracer_doesNotThrow() {
        // routeUtils has null capiTracer, so this should be a no-op
        Service service = new Service();
        service.setId("test-svc:dev");
        assertDoesNotThrow(() -> routeUtils.registerTracer(service));
    }

    @Test
    void registerTracer_withNonNullTracer_doesNotThrow() {
        CapiTracer mockTracer = mock(CapiTracer.class);
        RouteUtils ru = new RouteUtils(
                null, new HttpUtils(null, null), mock(CompositeMeterRegistry.class), mockTracer,
                camelContext, null, false, null,
                120000, 5000, 5000
        );

        Service service = new Service();
        service.setId("test-svc:dev");
        assertDoesNotThrow(() -> ru.registerTracer(service));
    }

    // ---------------------------------------------------------------
    // getHttpErrorProcessor
    // ---------------------------------------------------------------

    @Test
    void getHttpErrorProcessor_returnsExpected() {
        HttpErrorProcessor processor = new HttpErrorProcessor();
        RouteUtils ru = new RouteUtils(
                processor, new HttpUtils(null, null), mock(CompositeMeterRegistry.class), null,
                camelContext, null, false, null,
                120000, 5000, 5000
        );
        assertSame(processor, ru.getHttpErrorProcessor());
    }

    // ---------------------------------------------------------------
    // authorizationProcessor
    // ---------------------------------------------------------------

    @Test
    void authorizationProcessor_withProcessorAndSecured_returnsProcessor() {
        AuthorizationProcessor authProcessor = mock(AuthorizationProcessor.class);
        RouteUtils ru = new RouteUtils(
                null, new HttpUtils(null, null), mock(CompositeMeterRegistry.class), null,
                camelContext, authProcessor, false, null,
                120000, 5000, 5000
        );

        RouteDefinition routeDefinition = mock(RouteDefinition.class);
        AuthorizationProcessor result = ru.authorizationProcessor("api-1", routeDefinition, true);
        assertSame(authProcessor, result);
    }

    @Test
    void authorizationProcessor_withProcessorButNotSecured_returnsNull() {
        AuthorizationProcessor authProcessor = mock(AuthorizationProcessor.class);
        RouteUtils ru = new RouteUtils(
                null, new HttpUtils(null, null), mock(CompositeMeterRegistry.class), null,
                camelContext, authProcessor, false, null,
                120000, 5000, 5000
        );

        RouteDefinition routeDefinition = mock(RouteDefinition.class);
        AuthorizationProcessor result = ru.authorizationProcessor("api-1", routeDefinition, false);
        assertNull(result);
    }

    @Test
    void authorizationProcessor_withNullProcessor_returnsNull() {
        RouteDefinition routeDefinition = mock(RouteDefinition.class);
        AuthorizationProcessor result = routeUtils.authorizationProcessor("api-1", routeDefinition, true);
        assertNull(result);
    }

    // ---------------------------------------------------------------
    // openApiProcessor
    // ---------------------------------------------------------------

    @Test
    void openApiProcessor_withOpenApiEndpointAndOpenApi_returnsProcessor() {
        Service service = new Service();
        ServiceMeta meta = new ServiceMeta();
        meta.setOpenApiEndpoint("http://example.com/api-docs");
        service.setServiceMeta(meta);
        service.setOpenAPI(new OpenAPI());

        var result = routeUtils.openApiProcessor(service, null, null);
        assertNotNull(result);
    }

    @Test
    void openApiProcessor_withNullOpenApiEndpoint_returnsNull() {
        Service service = new Service();
        ServiceMeta meta = new ServiceMeta();
        meta.setOpenApiEndpoint(null);
        service.setServiceMeta(meta);
        service.setOpenAPI(new OpenAPI());

        var result = routeUtils.openApiProcessor(service, null, null);
        assertNull(result);
    }

    @Test
    void openApiProcessor_withNullOpenApi_returnsNull() {
        Service service = new Service();
        ServiceMeta meta = new ServiceMeta();
        meta.setOpenApiEndpoint("http://example.com/api-docs");
        service.setServiceMeta(meta);
        service.setOpenAPI(null);

        var result = routeUtils.openApiProcessor(service, null, null);
        assertNull(result);
    }

    @Test
    void openApiProcessor_bothNull_returnsNull() {
        Service service = new Service();
        ServiceMeta meta = new ServiceMeta();
        service.setServiceMeta(meta);

        var result = routeUtils.openApiProcessor(service, null, null);
        assertNull(result);
    }

    // ---------------------------------------------------------------
    // enableAuthorization
    // ---------------------------------------------------------------

    @Test
    void enableAuthorization_withNullAuthProcessor_logsWarning() {
        RouteDefinition routeDefinition = mock(RouteDefinition.class);
        // routeUtils has null authorizationProcessor
        assertDoesNotThrow(() -> routeUtils.enableAuthorization("api-1", routeDefinition));
        // Verify process is not called on routeDefinition since processor is null
        verify(routeDefinition, never()).process((org.apache.camel.Processor) any());
    }

    @Test
    void enableAuthorization_withAuthProcessor_callsProcess() {
        AuthorizationProcessor authProcessor = mock(AuthorizationProcessor.class);
        RouteUtils ru = new RouteUtils(
                null, new HttpUtils(null, null), mock(CompositeMeterRegistry.class), null,
                camelContext, authProcessor, false, null,
                120000, 5000, 5000
        );

        RouteDefinition routeDefinition = mock(RouteDefinition.class);
        when(routeDefinition.process((org.apache.camel.Processor) any())).thenReturn(routeDefinition);
        ru.enableAuthorization("api-1", routeDefinition);
        verify(routeDefinition).process(authProcessor);
    }

    // ---------------------------------------------------------------
    // per-service responseTimeout
    // ---------------------------------------------------------------

    @Test
    void buildEndpoints_withPerServiceResponseTimeout_overridesGlobal() {
        Service service = createServiceWithMapping("host1", 8080, "http", "/");
        service.getServiceMeta().setResponseTimeout(5000);
        String[] endpoints = routeUtils.buildEndpoints(service);

        assertEquals(1, endpoints.length);
        assertTrue(endpoints[0].contains("responseTimeout=5000"));
        assertFalse(endpoints[0].contains("responseTimeout=120000"));
    }

    @Test
    void buildEndpoints_withoutPerServiceResponseTimeout_usesGlobal() {
        Service service = createServiceWithMapping("host1", 8080, "http", "/");
        String[] endpoints = routeUtils.buildEndpoints(service);

        assertEquals(1, endpoints.length);
        assertTrue(endpoints[0].contains("responseTimeout=120000"));
    }

    @Test
    void buildEndpoints_withPerServiceResponseTimeoutZero_usesGlobal() {
        Service service = createServiceWithMapping("host1", 8080, "http", "/");
        service.getServiceMeta().setResponseTimeout(0);
        String[] endpoints = routeUtils.buildEndpoints(service);

        assertTrue(endpoints[0].contains("responseTimeout=120000"));
    }

    @Test
    void buildEndpoints_withPerServiceResponseTimeoutNegative_usesGlobal() {
        Service service = createServiceWithMapping("host1", 8080, "http", "/");
        service.getServiceMeta().setResponseTimeout(-1);
        String[] endpoints = routeUtils.buildEndpoints(service);

        assertTrue(endpoints[0].contains("responseTimeout=120000"));
    }

    // ---------------------------------------------------------------
    // buildFrom edge cases
    // ---------------------------------------------------------------

    @Test
    void buildFrom_emptyContext() {
        Service service = new Service();
        service.setContext("");
        assertEquals("/", routeUtils.buildFrom(service));
    }

    @Test
    void buildFrom_slashOnly() {
        Service service = new Service();
        service.setContext("/");
        assertEquals("/", routeUtils.buildFrom(service));
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