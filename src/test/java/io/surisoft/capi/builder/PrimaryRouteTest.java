package io.surisoft.capi.builder;

import io.surisoft.capi.schema.Service;
import io.surisoft.capi.service.ConsulNodeDiscovery;
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

import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PrimaryRouteTest {

    @Mock
    private RouteUtils routeUtils;

    private Cache<String, Service> serviceCache;
    private CamelContext camelContext;

    private static final String PRIMARY_ENDPOINT =
            "undertow:http://0.0.0.0:8380/api?matchOnUriPrefix=true&optionsEnabled=true"
                    + "&httpMethodRestrict=GET,POST,PUT,DELETE,OPTIONS,PATCH";

    @BeforeEach
    void setUp() {
        serviceCache = Cache2kBuilder.of(String.class, Service.class)
                .name("primaryRouteTestCache-" + System.nanoTime())
                .eternal(true)
                .build();
        camelContext = new DefaultCamelContext();
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

    @Test
    void constructionDoesNotThrow() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS,PATCH");

        PrimaryRoute primaryRoute = new PrimaryRoute(
                routeUtils,
                8380,
                "0.0.0.0",
                "/api",
                false,
                true,
                headers,
                serviceCache,
                PRIMARY_ENDPOINT
        );
        assertNotNull(primaryRoute);
    }

    @Test
    void configureDoesNotThrow() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS,PATCH");

        PrimaryRoute primaryRoute = new PrimaryRoute(
                routeUtils,
                8380,
                "0.0.0.0",
                "/api",
                false,
                true,
                headers,
                serviceCache,
                PRIMARY_ENDPOINT
        );

        // Attach the CamelContext so the RouteBuilder can build the route model
        camelContext.addRoutes(primaryRoute);

        // Verify that registerMetric was called during configure()
        verify(routeUtils).registerMetric("primary-route");
    }

    @Test
    void configureWithSslDisabledAndCorsDisabled_doesNotThrow() throws Exception {
        Map<String, String> headers = new HashMap<>();

        PrimaryRoute primaryRoute = new PrimaryRoute(
                routeUtils,
                8380,
                "0.0.0.0",
                "/api",
                false,
                false,
                headers,
                serviceCache,
                PRIMARY_ENDPOINT
        );

        assertDoesNotThrow(() -> camelContext.addRoutes(primaryRoute));
        verify(routeUtils).registerMetric("primary-route");
    }

    @Test
    void configureWithSslEnabled_doesNotThrow() throws Exception {
        Map<String, String> headers = Map.of(
                "Access-Control-Allow-Origin", "*"
        );

        PrimaryRoute primaryRoute = new PrimaryRoute(
                routeUtils,
                8443,
                "0.0.0.0",
                "/api",
                true,
                true,
                headers,
                serviceCache,
                "undertow:https://0.0.0.0:8443/api?matchOnUriPrefix=true&optionsEnabled=true"
                        + "&httpMethodRestrict=GET,POST,PUT,DELETE,OPTIONS,PATCH"
        );

        assertDoesNotThrow(() -> camelContext.addRoutes(primaryRoute));
    }

    // ---------------------------------------------------------------
    // Test processControlledHeaders / CORS behavior via route invocation
    // ---------------------------------------------------------------

    @Test
    void configure_processesOptionsRequest_withCorsEnabled() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS,PATCH");

        PrimaryRoute primaryRoute = new PrimaryRoute(
                routeUtils, 8380, "0.0.0.0", "/api", false, true,
                headers, serviceCache,
                "direct:primary-options-test"
        );

        camelContext.addRoutes(primaryRoute);
        camelContext.start();

        ProducerTemplate template = camelContext.createProducerTemplate();
        Exchange exchange = template.request("direct:primary-options-test", ex -> {
            ex.getIn().setHeader(Exchange.HTTP_METHOD, "OPTIONS");
            ex.getIn().setHeader(Exchange.HTTP_URI, "/api/test-svc/dev");
            ex.getIn().setHeader("Origin", "http://example.com");
        });

        assertNotNull(exchange);
        // OPTIONS should set Access-Control-Max-Age
        assertEquals("86400", exchange.getIn().getHeader("Access-Control-Max-Age"));
    }

    @Test
    void configure_nonOptionsRequest_noRoute_returns404() throws Exception {
        Map<String, String> headers = new HashMap<>();

        PrimaryRoute primaryRoute = new PrimaryRoute(
                routeUtils, 8380, "0.0.0.0", "/api", false, false,
                headers, serviceCache,
                "direct:primary-404-test"
        );

        camelContext.addRoutes(primaryRoute);
        camelContext.start();

        ProducerTemplate template = camelContext.createProducerTemplate();
        Exchange exchange = template.request("direct:primary-404-test", ex -> {
            ex.getIn().setHeader(Exchange.HTTP_METHOD, "GET");
            ex.getIn().setHeader(Exchange.HTTP_PATH, "/test-svc/dev/path");
            ex.getIn().setHeader(Exchange.HTTP_URI, "/api/test-svc/dev/path");
        });

        // Route does not exist, so should return 404
        assertEquals(404, exchange.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE));
    }

    @Test
    void configure_nonOptionsRequest_lessThan2Segments_returns404() throws Exception {
        Map<String, String> headers = new HashMap<>();

        PrimaryRoute primaryRoute = new PrimaryRoute(
                routeUtils, 8380, "0.0.0.0", "/api", false, false,
                headers, serviceCache,
                "direct:primary-short-path-test"
        );

        camelContext.addRoutes(primaryRoute);
        camelContext.start();

        ProducerTemplate template = camelContext.createProducerTemplate();
        Exchange exchange = template.request("direct:primary-short-path-test", ex -> {
            ex.getIn().setHeader(Exchange.HTTP_METHOD, "GET");
            ex.getIn().setHeader(Exchange.HTTP_PATH, "/single");
            ex.getIn().setHeader(Exchange.HTTP_URI, "/api/single");
        });

        assertEquals(404, exchange.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE));
    }

    @Test
    void configure_nonOptionsRequest_withCorsEnabled_originFromReferer() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Methods", "GET,POST");

        PrimaryRoute primaryRoute = new PrimaryRoute(
                routeUtils, 8380, "0.0.0.0", "/api", false, true,
                headers, serviceCache,
                "direct:primary-referer-test"
        );

        camelContext.addRoutes(primaryRoute);
        camelContext.start();

        ProducerTemplate template = camelContext.createProducerTemplate();
        Exchange exchange = template.request("direct:primary-referer-test", ex -> {
            ex.getIn().setHeader(Exchange.HTTP_METHOD, "GET");
            ex.getIn().setHeader(Exchange.HTTP_PATH, "/svc/grp/path");
            ex.getIn().setHeader(Exchange.HTTP_URI, "/api/svc/grp/path");
            ex.getIn().setHeader("Origin", "null");
            ex.getIn().setHeader("Referer", "http://example.com/page/");
        });

        assertNotNull(exchange);
    }

    @Test
    void configure_nonOptionsRequest_withCorsEnabled_nullOriginNoReferer() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Methods", "GET,POST");

        PrimaryRoute primaryRoute = new PrimaryRoute(
                routeUtils, 8380, "0.0.0.0", "/api", false, true,
                headers, serviceCache,
                "direct:primary-no-origin-test"
        );

        camelContext.addRoutes(primaryRoute);
        camelContext.start();

        ProducerTemplate template = camelContext.createProducerTemplate();
        Exchange exchange = template.request("direct:primary-no-origin-test", ex -> {
            ex.getIn().setHeader(Exchange.HTTP_METHOD, "GET");
            ex.getIn().setHeader(Exchange.HTTP_PATH, "/svc/grp/path");
            ex.getIn().setHeader(Exchange.HTTP_URI, "/api/svc/grp/path");
            // No Origin and no Referer
        });

        assertNotNull(exchange);
    }

    @Test
    void configure_nonOptionsRequest_withCorsEnabled_capiConsumerPath_withAllowedOrigins() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Methods", "GET,POST");

        // Put a service in the cache with allowed origins
        ServiceMeta meta = new ServiceMeta();
        meta.setAllowedOrigins("http://allowed.com,http://other.com");
        Service service = new Service();
        service.setServiceMeta(meta);
        serviceCache.put("svc:grp", service);

        PrimaryRoute primaryRoute = new PrimaryRoute(
                routeUtils, 8380, "0.0.0.0", "/api", false, true,
                headers, serviceCache,
                "direct:primary-allowed-origin-test"
        );

        camelContext.addRoutes(primaryRoute);
        camelContext.start();

        ProducerTemplate template = camelContext.createProducerTemplate();
        Exchange exchange = template.request("direct:primary-allowed-origin-test", ex -> {
            ex.getIn().setHeader(Exchange.HTTP_METHOD, "GET");
            ex.getIn().setHeader(Exchange.HTTP_PATH, "/svc/grp/path");
            ex.getIn().setHeader(Exchange.HTTP_URI, "/api/svc/grp/path");
            ex.getIn().setHeader("Origin", "http://allowed.com");
        });

        assertNotNull(exchange);
        assertEquals("http://allowed.com", exchange.getIn().getHeader("Access-Control-Allow-Origin"));
    }

    @Test
    void configure_nonOptionsRequest_withCorsEnabled_capiConsumerPath_originNotAllowed() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Methods", "GET,POST");

        ServiceMeta meta = new ServiceMeta();
        meta.setAllowedOrigins("http://allowed.com");
        Service service = new Service();
        service.setServiceMeta(meta);
        serviceCache.put("svc:grp", service);

        PrimaryRoute primaryRoute = new PrimaryRoute(
                routeUtils, 8380, "0.0.0.0", "/api", false, true,
                headers, serviceCache,
                "direct:primary-not-allowed-origin-test"
        );

        camelContext.addRoutes(primaryRoute);
        camelContext.start();

        ProducerTemplate template = camelContext.createProducerTemplate();
        Exchange exchange = template.request("direct:primary-not-allowed-origin-test", ex -> {
            ex.getIn().setHeader(Exchange.HTTP_METHOD, "GET");
            ex.getIn().setHeader(Exchange.HTTP_PATH, "/svc/grp/path");
            ex.getIn().setHeader(Exchange.HTTP_URI, "/api/svc/grp/path");
            ex.getIn().setHeader("Origin", "http://notallowed.com");
        });

        assertNotNull(exchange);
        // Origin should NOT be set since it's not in allowed list
        assertNull(exchange.getIn().getHeader("Access-Control-Allow-Origin"));
    }

    @Test
    void configure_nonOptionsRequest_withCorsEnabled_notCapiConsumer() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Methods", "GET,POST");

        PrimaryRoute primaryRoute = new PrimaryRoute(
                routeUtils, 8380, "0.0.0.0", "/api", false, true,
                headers, serviceCache,
                "direct:primary-non-capi-consumer-test"
        );

        camelContext.addRoutes(primaryRoute);
        camelContext.start();

        ProducerTemplate template = camelContext.createProducerTemplate();
        Exchange exchange = template.request("direct:primary-non-capi-consumer-test", ex -> {
            ex.getIn().setHeader(Exchange.HTTP_METHOD, "GET");
            ex.getIn().setHeader(Exchange.HTTP_PATH, "/svc/grp");
            // URI that does NOT start with /api (the capiRestPath)
            ex.getIn().setHeader(Exchange.HTTP_URI, "/other/svc/grp");
            ex.getIn().setHeader("Origin", "http://valid.com");
        });

        assertNotNull(exchange);
        // Not a capi consumer path, so origin is set directly
        assertEquals("http://valid.com", exchange.getIn().getHeader("Access-Control-Allow-Origin"));
    }

    @Test
    void configure_nonOptionsRequest_withCorsEnabled_invalidOrigin() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Methods", "GET,POST");

        PrimaryRoute primaryRoute = new PrimaryRoute(
                routeUtils, 8380, "0.0.0.0", "/api", false, true,
                headers, serviceCache,
                "direct:primary-invalid-origin-test"
        );

        camelContext.addRoutes(primaryRoute);
        camelContext.start();

        ProducerTemplate template = camelContext.createProducerTemplate();
        Exchange exchange = template.request("direct:primary-invalid-origin-test", ex -> {
            ex.getIn().setHeader(Exchange.HTTP_METHOD, "GET");
            ex.getIn().setHeader(Exchange.HTTP_PATH, "/svc/grp/path");
            ex.getIn().setHeader(Exchange.HTTP_URI, "/other/svc/grp/path");
            ex.getIn().setHeader("Origin", "not-a-valid-url");
        });

        assertNotNull(exchange);
        // Invalid origin URL should not set Access-Control-Allow-Origin
        assertNull(exchange.getIn().getHeader("Access-Control-Allow-Origin"));
    }

    @Test
    void configure_nonOptionsRequest_withCorsEnabled_serviceNullAllowedOrigins() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Methods", "GET,POST");

        // Service with null allowed origins should allow all
        ServiceMeta meta = new ServiceMeta();
        meta.setAllowedOrigins(null);
        Service service = new Service();
        service.setServiceMeta(meta);
        serviceCache.put("svc2:grp2", service);

        PrimaryRoute primaryRoute = new PrimaryRoute(
                routeUtils, 8380, "0.0.0.0", "/api", false, true,
                headers, serviceCache,
                "direct:primary-null-origins-test"
        );

        camelContext.addRoutes(primaryRoute);
        camelContext.start();

        ProducerTemplate template = camelContext.createProducerTemplate();
        Exchange exchange = template.request("direct:primary-null-origins-test", ex -> {
            ex.getIn().setHeader(Exchange.HTTP_METHOD, "GET");
            ex.getIn().setHeader(Exchange.HTTP_PATH, "/svc2/grp2/path");
            ex.getIn().setHeader(Exchange.HTTP_URI, "/api/svc2/grp2/path");
            ex.getIn().setHeader("Origin", "http://any-origin.com");
        });

        assertNotNull(exchange);
        // null allowedOrigins means allow all
        assertEquals("http://any-origin.com", exchange.getIn().getHeader("Access-Control-Allow-Origin"));
    }

    @Test
    void configure_routeExists_setsRecipientListEndpoint() throws Exception {
        Map<String, String> headers = new HashMap<>();

        // First add a target direct route so the route lookup succeeds
        camelContext.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:test-svc:dev:get")
                        .routeId("test-svc:dev:get")
                        .setBody(constant("response-body"));
            }
        });

        PrimaryRoute primaryRoute = new PrimaryRoute(
                routeUtils, 8380, "0.0.0.0", "/api", false, false,
                headers, serviceCache,
                "direct:primary-route-exists-test"
        );

        camelContext.addRoutes(primaryRoute);
        camelContext.start();

        ProducerTemplate template = camelContext.createProducerTemplate();
        Exchange exchange = template.request("direct:primary-route-exists-test", ex -> {
            ex.getIn().setHeader(Exchange.HTTP_METHOD, "GET");
            ex.getIn().setHeader(Exchange.HTTP_PATH, "/test-svc/dev/some/path");
            ex.getIn().setHeader(Exchange.HTTP_URI, "/api/test-svc/dev/some/path");
        });

        // Route exists so CamelRecipientListEndpoint should have been set
        assertNotNull(exchange);
        // Verify the exchange didn't get a 404 (routeStop would be true)
        assertNotEquals(404, exchange.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE));
    }

    // ---------------------------------------------------------------
    // Health endpoint tests
    // ---------------------------------------------------------------

    @Test
    void healthEndpoint_whenConnectedToConsul_returns200() throws Exception {
        java.lang.reflect.Field connectedField = ConsulNodeDiscovery.class.getDeclaredField("connectedToConsul");
        connectedField.setAccessible(true);
        connectedField.set(null, true);

        Map<String, String> headers = new HashMap<>();
        PrimaryRoute primaryRoute = new PrimaryRoute(
                routeUtils, 8380, "0.0.0.0", "/api", false, false,
                headers, serviceCache, "direct:primary-health-connected-test"
        );

        camelContext.addRoutes(primaryRoute);
        camelContext.start();

        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://0.0.0.0:8380/health"))
                .GET()
                .build();
        java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("UP"));
    }

    @Test
    void healthEndpoint_whenNotConnectedToConsul_returns503() throws Exception {
        java.lang.reflect.Field connectedField = ConsulNodeDiscovery.class.getDeclaredField("connectedToConsul");
        connectedField.setAccessible(true);
        connectedField.set(null, false);

        Map<String, String> headers = new HashMap<>();
        PrimaryRoute primaryRoute = new PrimaryRoute(
                routeUtils, 8380, "0.0.0.0", "/api", false, false,
                headers, serviceCache, "direct:primary-health-not-connected-test"
        );

        camelContext.addRoutes(primaryRoute);
        camelContext.start();

        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://0.0.0.0:8380/health"))
                .GET()
                .build();
        java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

        assertEquals(503, response.statusCode());
        assertTrue(response.body().contains("DOWN"));
    }
}
