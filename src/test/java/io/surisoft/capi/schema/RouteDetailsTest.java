package io.surisoft.capi.schema;

import org.apache.camel.api.management.mbean.ManagedRouteMBean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RouteDetailsTest {

    @Mock
    private ManagedRouteMBean managedRoute;

    @Test
    void defaultConstructor_initializesDefaults() {
        RouteDetails rd = new RouteDetails();
        assertEquals(0, rd.getDeltaProcessingTime());
        assertEquals(0, rd.getExchangesInflight());
        assertEquals(0, rd.getExchangesTotal());
        assertNull(rd.getFirstExchangeCompletedExchangeId());
        assertFalse(rd.isHasRouteController());
    }

    @Test
    void constructorWithManagedRoute_populatesAllFields() throws Exception {
        Date now = new Date();
        when(managedRoute.getDeltaProcessingTime()).thenReturn(100L);
        when(managedRoute.getExchangesInflight()).thenReturn(5L);
        when(managedRoute.getExchangesTotal()).thenReturn(1000L);
        when(managedRoute.getExternalRedeliveries()).thenReturn(2L);
        when(managedRoute.getFailuresHandled()).thenReturn(3L);
        when(managedRoute.getFirstExchangeCompletedExchangeId()).thenReturn("id-1");
        when(managedRoute.getFirstExchangeCompletedTimestamp()).thenReturn(now);
        when(managedRoute.getFirstExchangeFailureExchangeId()).thenReturn("id-2");
        when(managedRoute.getFirstExchangeFailureTimestamp()).thenReturn(now);
        when(managedRoute.getLastExchangeCompletedExchangeId()).thenReturn("id-3");
        when(managedRoute.getLastExchangeCompletedTimestamp()).thenReturn(now);
        when(managedRoute.getLastExchangeFailureExchangeId()).thenReturn("id-4");
        when(managedRoute.getLastExchangeFailureTimestamp()).thenReturn(now);
        when(managedRoute.getLastProcessingTime()).thenReturn(50L);
        when(managedRoute.getLoad01()).thenReturn("0.5");
        when(managedRoute.getLoad05()).thenReturn("0.3");
        when(managedRoute.getLoad15()).thenReturn("0.1");
        when(managedRoute.getMaxProcessingTime()).thenReturn(200L);
        when(managedRoute.getMeanProcessingTime()).thenReturn(75L);
        when(managedRoute.getMinProcessingTime()).thenReturn(10L);
        when(managedRoute.getOldestInflightDuration()).thenReturn(500L);
        when(managedRoute.getOldestInflightExchangeId()).thenReturn("id-5");
        when(managedRoute.getRedeliveries()).thenReturn(1L);
        when(managedRoute.getTotalProcessingTime()).thenReturn(75000L);
        when(managedRoute.getHasRouteController()).thenReturn(true);

        RouteDetails rd = new RouteDetails(managedRoute);

        assertEquals(100L, rd.getDeltaProcessingTime());
        assertEquals(5L, rd.getExchangesInflight());
        assertEquals(1000L, rd.getExchangesTotal());
        assertEquals(2L, rd.getExternalRedeliveries());
        assertEquals(3L, rd.getFailuresHandled());
        assertEquals("id-1", rd.getFirstExchangeCompletedExchangeId());
        assertEquals(now, rd.getFirstExchangeCompletedTimestamp());
        assertEquals("id-2", rd.getFirstExchangeFailureExchangeId());
        assertEquals(now, rd.getFirstExchangeFailureTimestamp());
        assertEquals("id-3", rd.getLastExchangeCompletedExchangeId());
        assertEquals(now, rd.getLastExchangeCompletedTimestamp());
        assertEquals("id-4", rd.getLastExchangeFailureExchangeId());
        assertEquals(now, rd.getLastExchangeFailureTimestamp());
        assertEquals(50L, rd.getLastProcessingTime());
        assertEquals("0.5", rd.getLoad01());
        assertEquals("0.3", rd.getLoad05());
        assertEquals("0.1", rd.getLoad15());
        assertEquals(200L, rd.getMaxProcessingTime());
        assertEquals(75L, rd.getMeanProcessingTime());
        assertEquals(10L, rd.getMinProcessingTime());
        assertEquals(500L, rd.getOldestInflightDuration());
        assertEquals("id-5", rd.getOldestInflightExchangeId());
        assertEquals(1L, rd.getRedeliveries());
        assertEquals(75000L, rd.getTotalProcessingTime());
        assertTrue(rd.isHasRouteController());
    }

    @Test
    void constructorWithException_doesNotThrow() throws Exception {
        when(managedRoute.getDeltaProcessingTime()).thenThrow(new RuntimeException("test error"));
        RouteDetails rd = new RouteDetails(managedRoute);
        assertEquals(0, rd.getDeltaProcessingTime());
    }

    @Test
    void settersAndGetters() {
        RouteDetails rd = new RouteDetails();
        Date now = new Date();

        rd.setDeltaProcessingTime(10);
        rd.setExchangesInflight(20);
        rd.setExchangesTotal(30);
        rd.setExternalRedeliveries(40);
        rd.setFailuresHandled(50);
        rd.setFirstExchangeCompletedExchangeId("fc-id");
        rd.setFirstExchangeCompletedTimestamp(now);
        rd.setFirstExchangeFailureExchangeId("ff-id");
        rd.setFirstExchangeFailureTimestamp(now);
        rd.setLastExchangeCompletedExchangeId("lc-id");
        rd.setLastExchangeCompletedTimestamp(now);
        rd.setLastExchangeFailureExchangeId("lf-id");
        rd.setLastExchangeFailureTimestamp(now);
        rd.setLastProcessingTime(60);
        rd.setLoad01("l01");
        rd.setLoad05("l05");
        rd.setLoad15("l15");
        rd.setMaxProcessingTime(70);
        rd.setMeanProcessingTime(80);
        rd.setMinProcessingTime(90);
        rd.setOldestInflightDuration(100L);
        rd.setOldestInflightExchangeId("oi-id");
        rd.setRedeliveries(110);
        rd.setTotalProcessingTime(120);
        rd.setHasRouteController(true);

        assertEquals(10, rd.getDeltaProcessingTime());
        assertEquals(20, rd.getExchangesInflight());
        assertEquals(30, rd.getExchangesTotal());
        assertEquals(40, rd.getExternalRedeliveries());
        assertEquals(50, rd.getFailuresHandled());
        assertEquals("fc-id", rd.getFirstExchangeCompletedExchangeId());
        assertEquals(now, rd.getFirstExchangeCompletedTimestamp());
        assertEquals("ff-id", rd.getFirstExchangeFailureExchangeId());
        assertEquals(now, rd.getFirstExchangeFailureTimestamp());
        assertEquals("lc-id", rd.getLastExchangeCompletedExchangeId());
        assertEquals(now, rd.getLastExchangeCompletedTimestamp());
        assertEquals("lf-id", rd.getLastExchangeFailureExchangeId());
        assertEquals(now, rd.getLastExchangeFailureTimestamp());
        assertEquals(60, rd.getLastProcessingTime());
        assertEquals("l01", rd.getLoad01());
        assertEquals("l05", rd.getLoad05());
        assertEquals("l15", rd.getLoad15());
        assertEquals(70, rd.getMaxProcessingTime());
        assertEquals(80, rd.getMeanProcessingTime());
        assertEquals(90, rd.getMinProcessingTime());
        assertEquals(100L, rd.getOldestInflightDuration());
        assertEquals("oi-id", rd.getOldestInflightExchangeId());
        assertEquals(110, rd.getRedeliveries());
        assertEquals(120, rd.getTotalProcessingTime());
        assertTrue(rd.isHasRouteController());
    }
}