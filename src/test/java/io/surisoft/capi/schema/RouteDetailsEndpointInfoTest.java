package io.surisoft.capi.schema;

import org.apache.camel.CamelContext;
import org.apache.camel.ExtendedCamelContext;
import org.apache.camel.Route;
import org.apache.camel.spi.ManagementStrategy;
import org.apache.camel.spi.ManagementAgent;
import org.apache.camel.api.management.ManagedCamelContext;
import org.apache.camel.api.management.mbean.ManagedRouteMBean;
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
class RouteDetailsEndpointInfoTest {

    @Mock
    private CamelContext camelContext;

    @Mock
    private ManagementStrategy managementStrategy;

    @Mock
    private Route route;

    @Test
    void constructor_withNullManagementAgent_noDetails() {
        when(camelContext.getManagementStrategy()).thenReturn(managementStrategy);
        when(managementStrategy.getManagementAgent()).thenReturn(null);
        when(route.getId()).thenReturn("test-route");
        when(route.getEndpoint()).thenReturn(mock(org.apache.camel.Endpoint.class));

        RouteDetailsEndpointInfo info = new RouteDetailsEndpointInfo(camelContext, route);
        assertNotNull(info);
    }

    @Test
    void constructor_withManagementAgent_setsDetails() {
        ManagementAgent agent = mock(ManagementAgent.class);
        when(camelContext.getManagementStrategy()).thenReturn(managementStrategy);
        when(managementStrategy.getManagementAgent()).thenReturn(agent);

        ExtendedCamelContext extendedContext = mock(ExtendedCamelContext.class);
        ManagedCamelContext managedCamelContext = mock(ManagedCamelContext.class);
        ManagedRouteMBean managedRoute = mock(ManagedRouteMBean.class);

        when(camelContext.getCamelContextExtension()).thenReturn(extendedContext);
        when(extendedContext.getContextPlugin(ManagedCamelContext.class)).thenReturn(managedCamelContext);
        when(route.getId()).thenReturn("test-route");
        when(route.getEndpoint()).thenReturn(mock(org.apache.camel.Endpoint.class));
        when(managedCamelContext.getManagedRoute("test-route", ManagedRouteMBean.class)).thenReturn(managedRoute);

        RouteDetailsEndpointInfo info = new RouteDetailsEndpointInfo(camelContext, route);
        assertNotNull(info);
    }
}
