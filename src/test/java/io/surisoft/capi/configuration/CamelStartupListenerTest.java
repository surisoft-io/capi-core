package io.surisoft.capi.configuration;

import org.apache.camel.CamelContext;
import org.apache.camel.builder.RouteBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CamelStartupListenerTest {

    @Mock
    private CamelContext camelContext;

    @Test
    void constructor_setsFields() {
        CamelStartupListener listener = new CamelStartupListener(60000L, true, true);
        assertNotNull(listener);
    }

    @Test
    void onCamelContextStarted_doesNothing() throws Exception {
        CamelStartupListener listener = new CamelStartupListener(60000L, false, false);
        assertDoesNotThrow(() -> listener.onCamelContextStarted(camelContext, false));
    }

    @Test
    void onCamelContextFullyStarted_withConsulAndTrustStoreEnabled_addsThreeRoutes() throws Exception {
        CamelStartupListener listener = new CamelStartupListener(60000L, true, true);
        listener.onCamelContextFullyStarted(camelContext, false);

        // Should add 3 routes: consulDiscovery, consulStore, consistencyCheck
        verify(camelContext, times(3)).addRoutes(any(RouteBuilder.class));
    }

    @Test
    void onCamelContextFullyStarted_withConsulStoreDisabled_addsTwoRoutes() throws Exception {
        CamelStartupListener listener = new CamelStartupListener(60000L, false, true);
        listener.onCamelContextFullyStarted(camelContext, false);

        // Should add 2 routes: consulDiscovery, consistencyCheck (no consulStore)
        verify(camelContext, times(2)).addRoutes(any(RouteBuilder.class));
    }

    @Test
    void onCamelContextFullyStarted_withTrustStoreDisabled_addsTwoRoutes() throws Exception {
        CamelStartupListener listener = new CamelStartupListener(60000L, true, false);
        listener.onCamelContextFullyStarted(camelContext, false);

        // consulStoreEnabled && trustStoreEnabled is false, so only 2 routes
        verify(camelContext, times(2)).addRoutes(any(RouteBuilder.class));
    }

    @Test
    void consulDiscoveryRouteBuilder_returnsNonNullRouteBuilder() {
        CamelStartupListener listener = new CamelStartupListener(30000L, false, false);
        RouteBuilder routeBuilder = listener.consulDiscoveryRouteBuilder();
        assertNotNull(routeBuilder);
    }

    @Test
    void consulStoreRouteBuilder_returnsNonNullRouteBuilder() {
        CamelStartupListener listener = new CamelStartupListener(30000L, true, true);
        RouteBuilder routeBuilder = listener.consulStoreRouteBuilder();
        assertNotNull(routeBuilder);
    }

    @Test
    void consistencyCheckRouteBuilder_returnsNonNullRouteBuilder() {
        CamelStartupListener listener = new CamelStartupListener(30000L, false, false);
        RouteBuilder routeBuilder = listener.consistencyCheckRouteBuilder();
        assertNotNull(routeBuilder);
    }
}
