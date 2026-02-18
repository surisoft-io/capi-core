package io.surisoft.capi.schema;

import org.apache.camel.Route;
import org.apache.camel.ServiceStatus;
import org.apache.camel.StatefulService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RouteEndpointInfoTest {

    @Mock
    private Route route;

    /**
     * A synthetic interface that extends both Route and StatefulService,
     * allowing Mockito to produce a single mock that satisfies the
     * {@code route instanceof StatefulService} check.
     */
    interface StatefulRoute extends Route, StatefulService {}

    // ---------------------------------------------------------------
    // Constructor: basic route (non-StatefulService) with properties
    // ---------------------------------------------------------------

    @Test
    void constructor_withProperties_populatesAllFields() {
        Map<String, Object> props = Map.of("key1", "value1", "key2", "value2");

        when(route.getId()).thenReturn("route-1");
        when(route.getGroup()).thenReturn("group-a");
        when(route.getDescription()).thenReturn("A test route");
        when(route.getUptime()).thenReturn("1h30m");
        when(route.getUptimeMillis()).thenReturn(5400000L);
        when(route.getProperties()).thenReturn(props);

        RouteEndpointInfo info = new RouteEndpointInfo(route);

        assertEquals("route-1", info.getId());
        assertEquals("group-a", info.getGroup());
        assertEquals("A test route", info.getDescription());
        assertEquals("1h30m", info.getUptime());
        assertEquals(5400000L, info.getUptimeMillis());
        assertEquals(2, info.getProperties().size());
        assertEquals("value1", info.getProperties().get("key1"));
        assertNull(info.getStatus());
    }

    // ---------------------------------------------------------------
    // Constructor: route with null properties
    // ---------------------------------------------------------------

    @Test
    void constructor_withNullProperties_setsEmptyMap() {
        when(route.getId()).thenReturn("route-2");
        when(route.getGroup()).thenReturn(null);
        when(route.getDescription()).thenReturn(null);
        when(route.getUptime()).thenReturn("0s");
        when(route.getUptimeMillis()).thenReturn(0L);
        when(route.getProperties()).thenReturn(null);

        RouteEndpointInfo info = new RouteEndpointInfo(route);

        assertEquals("route-2", info.getId());
        assertNull(info.getGroup());
        assertNull(info.getDescription());
        assertEquals("0s", info.getUptime());
        assertEquals(0L, info.getUptimeMillis());
        assertNotNull(info.getProperties());
        assertTrue(info.getProperties().isEmpty());
        assertNull(info.getStatus());
    }

    // ---------------------------------------------------------------
    // Constructor: route IS a StatefulService
    // ---------------------------------------------------------------

    @Test
    void constructor_withStatefulService_setsStatusFromService() {
        StatefulRoute statefulRoute = mock(StatefulRoute.class);

        when(statefulRoute.getId()).thenReturn("route-3");
        when(statefulRoute.getGroup()).thenReturn("group-b");
        when(statefulRoute.getDescription()).thenReturn("Stateful route");
        when(statefulRoute.getUptime()).thenReturn("2h");
        when(statefulRoute.getUptimeMillis()).thenReturn(7200000L);
        when(statefulRoute.getProperties()).thenReturn(Collections.emptyMap());
        when(statefulRoute.getStatus()).thenReturn(ServiceStatus.Started);

        RouteEndpointInfo info = new RouteEndpointInfo(statefulRoute);

        assertEquals("route-3", info.getId());
        assertEquals("Started", info.getStatus());
    }

    @Test
    void constructor_withStatefulService_stoppedStatus() {
        StatefulRoute statefulRoute = mock(StatefulRoute.class);

        when(statefulRoute.getId()).thenReturn("route-4");
        when(statefulRoute.getGroup()).thenReturn(null);
        when(statefulRoute.getDescription()).thenReturn(null);
        when(statefulRoute.getUptime()).thenReturn("0s");
        when(statefulRoute.getUptimeMillis()).thenReturn(0L);
        when(statefulRoute.getProperties()).thenReturn(null);
        when(statefulRoute.getStatus()).thenReturn(ServiceStatus.Stopped);

        RouteEndpointInfo info = new RouteEndpointInfo(statefulRoute);

        assertEquals("Stopped", info.getStatus());
    }

    // ---------------------------------------------------------------
    // Constructor: route is NOT a StatefulService -> status is null
    // ---------------------------------------------------------------

    @Test
    void constructor_nonStatefulRoute_statusIsNull() {
        when(route.getId()).thenReturn("route-5");
        when(route.getGroup()).thenReturn(null);
        when(route.getDescription()).thenReturn(null);
        when(route.getUptime()).thenReturn("5m");
        when(route.getUptimeMillis()).thenReturn(300000L);
        when(route.getProperties()).thenReturn(Map.of());

        RouteEndpointInfo info = new RouteEndpointInfo(route);

        assertNull(info.getStatus());
    }

    // ---------------------------------------------------------------
    // setId
    // ---------------------------------------------------------------

    @Test
    void setId_overridesConstructorValue() {
        when(route.getId()).thenReturn("original-id");
        when(route.getGroup()).thenReturn(null);
        when(route.getDescription()).thenReturn(null);
        when(route.getUptime()).thenReturn("0s");
        when(route.getUptimeMillis()).thenReturn(0L);
        when(route.getProperties()).thenReturn(null);

        RouteEndpointInfo info = new RouteEndpointInfo(route);
        assertEquals("original-id", info.getId());

        info.setId("new-id");
        assertEquals("new-id", info.getId());
    }

    // ---------------------------------------------------------------
    // Properties are a defensive copy
    // ---------------------------------------------------------------

    @Test
    void constructor_propertiesAreCopied_notSameInstance() {
        Map<String, Object> originalProps = new java.util.HashMap<>();
        originalProps.put("k", "v");

        when(route.getId()).thenReturn("route-6");
        when(route.getGroup()).thenReturn(null);
        when(route.getDescription()).thenReturn(null);
        when(route.getUptime()).thenReturn("0s");
        when(route.getUptimeMillis()).thenReturn(0L);
        when(route.getProperties()).thenReturn(originalProps);

        RouteEndpointInfo info = new RouteEndpointInfo(route);

        // Modify original — should NOT affect the info's copy
        originalProps.put("extra", "extra-value");
        assertFalse(info.getProperties().containsKey("extra"));
    }
}
