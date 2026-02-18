package io.surisoft.capi.schema;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThrottleServiceObjectTest {

    @Test
    void defaultConstructor() {
        ThrottleServiceObject obj = new ThrottleServiceObject();
        assertNull(obj.getServiceId());
        assertNull(obj.getConsumerKey());
        assertEquals(0, obj.getTotalCallsAllowed());
        assertEquals(0, obj.getCurrentCalls());
    }

    @Test
    void parameterizedConstructor_initializesFields() {
        ThrottleServiceObject obj = new ThrottleServiceObject("svc-1", "consumer-key", 100, 60000);
        assertEquals("svc-1", obj.getServiceId());
        assertEquals("consumer-key", obj.getConsumerKey());
        assertEquals(100, obj.getTotalCallsAllowed());
        assertEquals(1, obj.getCurrentCalls());
        assertTrue(obj.getExpirationTime() > System.currentTimeMillis());
    }

    @Test
    void getCacheKey_returnsServiceId() {
        ThrottleServiceObject obj = new ThrottleServiceObject("svc-1", "key", 10, 60000);
        assertEquals("svc-1", obj.getCacheKey());
    }

    @Test
    void isExpired_notExpired() {
        ThrottleServiceObject obj = new ThrottleServiceObject("svc-1", "key", 10, 60000);
        assertFalse(obj.isExpired());
    }

    @Test
    void isExpired_expired() throws InterruptedException {
        ThrottleServiceObject obj = new ThrottleServiceObject("svc-1", "key", 10, 1);
        // With 1ms duration, sleep a bit to ensure we pass expiration
        Thread.sleep(10);
        assertTrue(obj.isExpired());
    }

    @Test
    void canCall_withinLimits() {
        ThrottleServiceObject obj = new ThrottleServiceObject("svc-1", "key", 10, 60000);
        assertTrue(obj.canCall());
    }

    @Test
    void canCall_exceededCalls() {
        ThrottleServiceObject obj = new ThrottleServiceObject("svc-1", "key", 2, 60000);
        // currentCalls starts at 1, increment to 2 (== totalCallsAllowed)
        obj.increment();
        assertFalse(obj.canCall());
    }

    @Test
    void canCall_expired() throws InterruptedException {
        ThrottleServiceObject obj = new ThrottleServiceObject("svc-1", "key", 100, 1);
        Thread.sleep(10);
        assertFalse(obj.canCall());
    }

    @Test
    void increment_increasesCurrentCalls() {
        ThrottleServiceObject obj = new ThrottleServiceObject("svc-1", "key", 10, 60000);
        assertEquals(1, obj.getCurrentCalls());
        obj.increment();
        assertEquals(2, obj.getCurrentCalls());
        obj.increment();
        assertEquals(3, obj.getCurrentCalls());
    }
}