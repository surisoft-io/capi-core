package io.surisoft.capi.builder;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.apache.camel.CamelContext;
import org.apache.camel.Route;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class PrometheusBuilderTest {

    @Mock
    private PrometheusMeterRegistry prometheusRegistry;

    private CamelContext camelContext;

    @BeforeEach
    void setUp() {
        camelContext = new DefaultCamelContext();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (camelContext != null) {
            camelContext.stop();
        }
    }

    @Test
    void constructor_withSslDisabled_doesNotThrow() {
        PrometheusBuilder builder = new PrometheusBuilder(prometheusRegistry, false);
        assertNotNull(builder);
    }

    @Test
    void constructor_withSslEnabled_doesNotThrow() {
        PrometheusBuilder builder = new PrometheusBuilder(prometheusRegistry, true);
        assertNotNull(builder);
    }

    @Test
    void configure_withSslDisabled_registersHttpRoute() throws Exception {
        PrometheusBuilder builder = new PrometheusBuilder(prometheusRegistry, false);
        camelContext.addRoutes(builder);
        camelContext.start();

        List<Route> routes = camelContext.getRoutes();
        assertFalse(routes.isEmpty(), "Expected at least one route to be registered");

        // Verify the route endpoint URI contains "http" (not "https")
        Route route = routes.get(0);
        String endpointUri = route.getEndpoint().getEndpointUri();
        assertTrue(endpointUri.contains("http://0.0.0.0:8381/metrics"),
                "Expected route endpoint to use http scheme, but got: " + endpointUri);
    }

    @Test
    void configure_withSslEnabled_registersHttpsRoute() throws Exception {
        PrometheusBuilder builder = new PrometheusBuilder(prometheusRegistry, true);
        camelContext.addRoutes(builder);
        camelContext.start();

        List<Route> routes = camelContext.getRoutes();
        assertFalse(routes.isEmpty(), "Expected at least one route to be registered");

        // Verify the route endpoint URI contains "https"
        Route route = routes.get(0);
        String endpointUri = route.getEndpoint().getEndpointUri();
        assertTrue(endpointUri.contains("https://0.0.0.0:8381/metrics"),
                "Expected route endpoint to use https scheme, but got: " + endpointUri);
    }

    @Test
    void configure_addRoutesDoesNotThrow_sslDisabled() {
        PrometheusBuilder builder = new PrometheusBuilder(prometheusRegistry, false);
        assertDoesNotThrow(() -> camelContext.addRoutes(builder));
    }

    @Test
    void configure_addRoutesDoesNotThrow_sslEnabled() {
        PrometheusBuilder builder = new PrometheusBuilder(prometheusRegistry, true);
        assertDoesNotThrow(() -> camelContext.addRoutes(builder));
    }
}
