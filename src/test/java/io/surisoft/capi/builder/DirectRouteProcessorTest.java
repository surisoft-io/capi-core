package io.surisoft.capi.builder;

import io.surisoft.capi.processor.ContentTypeValidator;
import io.surisoft.capi.processor.HttpErrorProcessor;
import io.surisoft.capi.processor.MetricsProcessor;
import io.surisoft.capi.processor.ThrottleProcessor;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import io.surisoft.capi.service.OpaService;
import io.surisoft.capi.utils.RouteUtils;
import org.apache.camel.CamelContext;
import org.apache.camel.impl.DefaultCamelContext;
import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DirectRouteProcessorTest {

    @Mock
    private RouteUtils routeUtils;
    @Mock
    private MetricsProcessor metricsProcessor;
    @Mock
    private ContentTypeValidator contentTypeValidator;
    @Mock
    private ThrottleProcessor throttleProcessor;
    @Mock
    private HttpErrorProcessor httpErrorProcessor;
    @Mock
    private OpaService opaService;

    private CamelContext camelContext;
    private Cache<String, Service> serviceCache;

    @BeforeEach
    void setUp() {
        camelContext = new DefaultCamelContext();
        serviceCache = Cache2kBuilder.of(String.class, Service.class)
                .name("directRouteTestCache-" + System.nanoTime())
                .eternal(true)
                .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (camelContext != null) {
            camelContext.stop();
        }
        if (serviceCache != null) {
            serviceCache.close();
        }
    }

    private Service buildService(boolean failOver, boolean keepGroup, boolean throttle) {
        ServiceMeta serviceMeta = new ServiceMeta();
        serviceMeta.setKeepGroup(keepGroup);
        serviceMeta.setThrottle(throttle);
        serviceMeta.setB3TraceId(false);
        serviceMeta.setSecured(false);

        Service service = new Service();
        service.setId("test-service");
        service.setContext("/test");
        service.setFailOverEnabled(failOver);
        service.setRoundRobinEnabled(false);
        service.setServiceMeta(serviceMeta);

        return service;
    }

    @Test
    void constructionDoesNotThrow() {
        Service service = buildService(false, false, false);

        DirectRouteProcessor processor = new DirectRouteProcessor(
                camelContext, service, routeUtils, metricsProcessor,
                "test-route:get:get", "/capi", "localhost",
                contentTypeValidator, throttleProcessor
        );
        assertNotNull(processor);
    }

    @Test
    void configure_noFailover_noReverseProxy_noKeepGroup() throws Exception {
        Service service = buildService(false, false, false);

        when(routeUtils.buildEndpoints(service)).thenReturn(new String[]{"http://localhost:8080/test?bridgeEndpoint=true"});
        when(routeUtils.getHttpErrorProcessor()).thenReturn(httpErrorProcessor);

        DirectRouteProcessor processor = new DirectRouteProcessor(
                camelContext, service, routeUtils, metricsProcessor,
                "test-service:get:get", "/capi", null,
                contentTypeValidator, throttleProcessor
        );
        processor.setOpaService(opaService);
        processor.setServiceCache(serviceCache);

        assertDoesNotThrow(() -> camelContext.addRoutes(processor));
        verify(routeUtils).registerMetric("test-service:get:get");
        verify(routeUtils).registerTracer(service);
    }

    @Test
    void configure_noFailover_withReverseProxy() throws Exception {
        Service service = buildService(false, false, false);

        when(routeUtils.buildEndpoints(service)).thenReturn(new String[]{"http://localhost:8080/test?bridgeEndpoint=true"});
        when(routeUtils.getHttpErrorProcessor()).thenReturn(httpErrorProcessor);

        DirectRouteProcessor processor = new DirectRouteProcessor(
                camelContext, service, routeUtils, metricsProcessor,
                "test-service:get:get", "/capi", "proxy.example.com",
                contentTypeValidator, throttleProcessor
        );
        processor.setServiceCache(serviceCache);

        assertDoesNotThrow(() -> camelContext.addRoutes(processor));
        verify(routeUtils).registerMetric("test-service:get:get");
    }

    @Test
    void configure_noFailover_withKeepGroup() throws Exception {
        Service service = buildService(false, true, false);

        when(routeUtils.buildEndpoints(service)).thenReturn(new String[]{"http://localhost:8080/test?bridgeEndpoint=true"});
        when(routeUtils.getHttpErrorProcessor()).thenReturn(httpErrorProcessor);

        DirectRouteProcessor processor = new DirectRouteProcessor(
                camelContext, service, routeUtils, metricsProcessor,
                "test-service:get:get", "/capi", "proxy.example.com",
                contentTypeValidator, throttleProcessor
        );
        processor.setServiceCache(serviceCache);

        assertDoesNotThrow(() -> camelContext.addRoutes(processor));
        verify(routeUtils).registerMetric("test-service:get:get");
    }

    @Test
    void configure_failoverEnabled() throws Exception {
        Service service = buildService(true, false, false);

        when(routeUtils.buildEndpoints(service)).thenReturn(new String[]{"http://localhost:8080/test?bridgeEndpoint=true"});
        when(routeUtils.getHttpErrorProcessor()).thenReturn(httpErrorProcessor);

        DirectRouteProcessor processor = new DirectRouteProcessor(
                camelContext, service, routeUtils, metricsProcessor,
                "test-service:get:get", "/capi", null,
                contentTypeValidator, throttleProcessor
        );
        processor.setServiceCache(serviceCache);

        assertDoesNotThrow(() -> camelContext.addRoutes(processor));
        verify(routeUtils).registerMetric("test-service:get:get");
        verify(routeUtils).registerTracer(service);
    }

    @Test
    void configure_failoverEnabled_withReverseProxy() throws Exception {
        Service service = buildService(true, false, false);

        when(routeUtils.buildEndpoints(service)).thenReturn(new String[]{"http://localhost:8080/test?bridgeEndpoint=true"});
        when(routeUtils.getHttpErrorProcessor()).thenReturn(httpErrorProcessor);

        DirectRouteProcessor processor = new DirectRouteProcessor(
                camelContext, service, routeUtils, metricsProcessor,
                "test-service:get:get", "/capi", "proxy.example.com",
                contentTypeValidator, throttleProcessor
        );
        processor.setServiceCache(serviceCache);

        assertDoesNotThrow(() -> camelContext.addRoutes(processor));
        verify(routeUtils).registerMetric("test-service:get:get");
    }

    @Test
    void configure_failoverEnabled_withKeepGroup() throws Exception {
        Service service = buildService(true, true, false);

        when(routeUtils.buildEndpoints(service)).thenReturn(new String[]{"http://localhost:8080/test?bridgeEndpoint=true"});
        when(routeUtils.getHttpErrorProcessor()).thenReturn(httpErrorProcessor);

        DirectRouteProcessor processor = new DirectRouteProcessor(
                camelContext, service, routeUtils, metricsProcessor,
                "test-service:get:get", "/capi", "proxy.example.com",
                contentTypeValidator, throttleProcessor
        );
        processor.setServiceCache(serviceCache);

        assertDoesNotThrow(() -> camelContext.addRoutes(processor));
    }

    @Test
    void setOpaService_setsField() {
        Service service = buildService(false, false, false);
        DirectRouteProcessor processor = new DirectRouteProcessor(
                camelContext, service, routeUtils, metricsProcessor,
                "test-service:get:get", "/capi", null,
                contentTypeValidator, throttleProcessor
        );
        assertDoesNotThrow(() -> processor.setOpaService(opaService));
    }

    @Test
    void setServiceCache_setsField() {
        Service service = buildService(false, false, false);
        DirectRouteProcessor processor = new DirectRouteProcessor(
                camelContext, service, routeUtils, metricsProcessor,
                "test-service:get:get", "/capi", null,
                contentTypeValidator, throttleProcessor
        );
        assertDoesNotThrow(() -> processor.setServiceCache(serviceCache));
    }

    // ---------------------------------------------------------------
    // Additional coverage tests
    // ---------------------------------------------------------------

    @Test
    void configure_noFailover_withThrottle() throws Exception {
        Service service = buildService(false, false, true);

        when(routeUtils.buildEndpoints(service)).thenReturn(new String[]{"http://localhost:8080/test?bridgeEndpoint=true"});
        when(routeUtils.getHttpErrorProcessor()).thenReturn(httpErrorProcessor);

        DirectRouteProcessor processor = new DirectRouteProcessor(
                camelContext, service, routeUtils, metricsProcessor,
                "test-service:get:get", "/capi", null,
                contentTypeValidator, throttleProcessor
        );
        processor.setOpaService(opaService);

        processor.setServiceCache(serviceCache);

        assertDoesNotThrow(() -> camelContext.addRoutes(processor));
        verify(routeUtils).registerMetric("test-service:get:get");
    }

    @Test
    void configure_failoverEnabled_withThrottle() throws Exception {
        Service service = buildService(true, false, true);

        when(routeUtils.buildEndpoints(service)).thenReturn(new String[]{"http://localhost:8080/test?bridgeEndpoint=true"});
        when(routeUtils.getHttpErrorProcessor()).thenReturn(httpErrorProcessor);

        DirectRouteProcessor processor = new DirectRouteProcessor(
                camelContext, service, routeUtils, metricsProcessor,
                "test-service:get:get", "/capi", null,
                contentTypeValidator, throttleProcessor
        );
        processor.setOpaService(opaService);

        processor.setServiceCache(serviceCache);

        assertDoesNotThrow(() -> camelContext.addRoutes(processor));
        verify(routeUtils).registerMetric("test-service:get:get");
    }

    @Test
    void configure_failoverEnabled_withKeepGroupAndReverseProxy() throws Exception {
        Service service = buildService(true, true, false);

        when(routeUtils.buildEndpoints(service)).thenReturn(new String[]{"http://localhost:8080/test?bridgeEndpoint=true"});
        when(routeUtils.getHttpErrorProcessor()).thenReturn(httpErrorProcessor);

        DirectRouteProcessor processor = new DirectRouteProcessor(
                camelContext, service, routeUtils, metricsProcessor,
                "test-service:get:get", "/capi", "proxy.example.com",
                contentTypeValidator, throttleProcessor
        );
        processor.setServiceCache(serviceCache);
        processor.setOpaService(opaService);


        assertDoesNotThrow(() -> camelContext.addRoutes(processor));
        verify(routeUtils).registerTracer(service);
    }

    @Test
    void configure_noFailover_withKeepGroupAndThrottle() throws Exception {
        Service service = buildService(false, true, true);

        when(routeUtils.buildEndpoints(service)).thenReturn(new String[]{"http://localhost:8080/test?bridgeEndpoint=true"});
        when(routeUtils.getHttpErrorProcessor()).thenReturn(httpErrorProcessor);

        DirectRouteProcessor processor = new DirectRouteProcessor(
                camelContext, service, routeUtils, metricsProcessor,
                "test-service:get:get", "/capi", "proxy.example.com",
                contentTypeValidator, throttleProcessor
        );
        processor.setServiceCache(serviceCache);
        processor.setOpaService(opaService);


        assertDoesNotThrow(() -> camelContext.addRoutes(processor));
    }

    @Test
    void configure_noFailover_multipleEndpoints() throws Exception {
        Service service = buildService(false, false, false);

        when(routeUtils.buildEndpoints(service)).thenReturn(new String[]{
                "http://host1:8080/test?bridgeEndpoint=true",
                "http://host2:8080/test?bridgeEndpoint=true"
        });
        when(routeUtils.getHttpErrorProcessor()).thenReturn(httpErrorProcessor);

        DirectRouteProcessor processor = new DirectRouteProcessor(
                camelContext, service, routeUtils, metricsProcessor,
                "multi-svc:dev:get", "/capi", null,
                contentTypeValidator, throttleProcessor
        );
        processor.setServiceCache(serviceCache);

        assertDoesNotThrow(() -> camelContext.addRoutes(processor));
        verify(routeUtils).registerMetric("multi-svc:dev:get");
    }

    @Test
    void configure_failoverEnabled_multipleEndpoints() throws Exception {
        Service service = buildService(true, false, false);
        service.setRoundRobinEnabled(true);

        when(routeUtils.buildEndpoints(service)).thenReturn(new String[]{
                "http://host1:8080/test?bridgeEndpoint=true",
                "http://host2:8080/test?bridgeEndpoint=true"
        });
        when(routeUtils.getHttpErrorProcessor()).thenReturn(httpErrorProcessor);

        DirectRouteProcessor processor = new DirectRouteProcessor(
                camelContext, service, routeUtils, metricsProcessor,
                "failover-multi:dev:get", "/capi", null,
                contentTypeValidator, throttleProcessor
        );
        processor.setServiceCache(serviceCache);

        assertDoesNotThrow(() -> camelContext.addRoutes(processor));
    }

    @Test
    void configure_withSecuredService_noAuthorizationProcessor() throws Exception {
        Service service = buildService(false, false, false);
        service.getServiceMeta().setSecured(true);

        when(routeUtils.buildEndpoints(service)).thenReturn(new String[]{"http://localhost:8080/test?bridgeEndpoint=true"});
        when(routeUtils.getHttpErrorProcessor()).thenReturn(httpErrorProcessor);
        when(routeUtils.authorizationProcessor(anyString(), any(), eq(true))).thenReturn(null);

        DirectRouteProcessor processor = new DirectRouteProcessor(
                camelContext, service, routeUtils, metricsProcessor,
                "secured-svc:dev:get", "/capi", null,
                contentTypeValidator, throttleProcessor
        );
        processor.setServiceCache(serviceCache);

        assertDoesNotThrow(() -> camelContext.addRoutes(processor));
    }

    @Test
    void configure_failover_withSecuredServiceAndReverseProxy() throws Exception {
        Service service = buildService(true, true, true);
        service.getServiceMeta().setSecured(true);
        service.getServiceMeta().setB3TraceId(true);

        when(routeUtils.buildEndpoints(service)).thenReturn(new String[]{"http://localhost:8080/test?bridgeEndpoint=true"});
        when(routeUtils.getHttpErrorProcessor()).thenReturn(httpErrorProcessor);
        when(routeUtils.authorizationProcessor(anyString(), any(), eq(true))).thenReturn(null);

        DirectRouteProcessor processor = new DirectRouteProcessor(
                camelContext, service, routeUtils, metricsProcessor,
                "all-flags:dev:get", "/capi", "proxy.test.com",
                contentTypeValidator, throttleProcessor
        );
        processor.setServiceCache(serviceCache);
        processor.setOpaService(opaService);


        assertDoesNotThrow(() -> camelContext.addRoutes(processor));
    }

}
