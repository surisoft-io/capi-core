package io.surisoft.capi.processor;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import io.surisoft.capi.exception.AuthorizationException;
import io.surisoft.capi.oidc.Oauth2Constants;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import io.surisoft.capi.schema.ThrottleServiceObject;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.HttpUtils;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ThrottleProcessorTest {

    @Mock
    private HttpUtils httpUtils;
    @Mock
    private Exchange exchange;
    @Mock
    private Message message;

    private Cache<String, Service> serviceCache;
    private ThrottleProcessor throttleProcessor;

    private static HazelcastInstance hazelcastInstance;
    private static IMap<String, ThrottleServiceObject> throttleMap;

    @BeforeAll
    static void startHazelcast() {
        Config config = new Config();
        config.setClusterName("capi-throttle-test");
        hazelcastInstance = Hazelcast.newHazelcastInstance(config);
        throttleMap = hazelcastInstance.getMap("throttle-test-cache");
    }

    @AfterAll
    static void stopHazelcast() {
        if (hazelcastInstance != null) {
            hazelcastInstance.shutdown();
        }
    }

    @BeforeEach
    void setUp() {
        serviceCache = Cache2kBuilder.of(String.class, Service.class)
                .name("throttleTestCache-" + System.nanoTime())
                .eternal(true)
                .build();
        throttleMap.clear();
        throttleProcessor = new ThrottleProcessor(serviceCache, httpUtils, throttleMap);
    }

    @AfterEach
    void tearDown() {
        if (serviceCache != null) {
            serviceCache.close();
        }
    }

    @Test
    void process_nullService_doesNothing() throws Exception {
        when(exchange.getIn()).thenReturn(message);
        when(message.getHeader(Oauth2Constants.CAMEL_SERVLET_CONTEXT_PATH)).thenReturn("/test/path");
        when(httpUtils.contextToRole("/test/path")).thenReturn("test:path");

        throttleProcessor.process(exchange);

        verify(exchange, never()).setException(any());
    }

    @Test
    void process_globalThrottle_firstCall_allows() throws Exception {
        ServiceMeta serviceMeta = new ServiceMeta();
        serviceMeta.setThrottleGlobal(true);
        serviceMeta.setThrottleDuration(60000);
        serviceMeta.setThrottleTotalCalls(10);

        Service service = new Service();
        service.setId("test-service");
        service.setServiceMeta(serviceMeta);

        serviceCache.put("test:path", service);

        when(exchange.getIn()).thenReturn(message);
        when(message.getHeader(Oauth2Constants.CAMEL_SERVLET_CONTEXT_PATH)).thenReturn("/test/path");
        when(httpUtils.contextToRole("/test/path")).thenReturn("test:path");

        throttleProcessor.process(exchange);

        verify(exchange, never()).setException(any());
    }

    @Test
    void process_globalThrottle_exceedingLimit_setsException() throws Exception {
        ServiceMeta serviceMeta = new ServiceMeta();
        serviceMeta.setThrottleGlobal(true);
        serviceMeta.setThrottleDuration(60000);
        serviceMeta.setThrottleTotalCalls(1);

        Service service = new Service();
        service.setId("test-service-exceed");
        service.setServiceMeta(serviceMeta);

        serviceCache.put("test:path", service);

        when(exchange.getIn()).thenReturn(message);
        when(message.getHeader(Oauth2Constants.CAMEL_SERVLET_CONTEXT_PATH)).thenReturn("/test/path");
        when(httpUtils.contextToRole("/test/path")).thenReturn("test:path");

        // First call — creates entry (currentCalls=1), allowed
        throttleProcessor.process(exchange);
        verify(exchange, never()).setException(any());

        // Second call — canCall: currentCalls(1) < totalCallsAllowed(1) = false -> throttled
        throttleProcessor.process(exchange);
        verify(exchange).setException(any(AuthorizationException.class));
    }

    @Test
    void process_globalThrottle_durationNegative_doesNotThrottle() throws Exception {
        ServiceMeta serviceMeta = new ServiceMeta();
        serviceMeta.setThrottleGlobal(true);
        serviceMeta.setThrottleDuration(-1);
        serviceMeta.setThrottleTotalCalls(10);

        Service service = new Service();
        service.setId("test-service");
        service.setServiceMeta(serviceMeta);

        serviceCache.put("test:path", service);

        when(exchange.getIn()).thenReturn(message);
        when(message.getHeader(Oauth2Constants.CAMEL_SERVLET_CONTEXT_PATH)).thenReturn("/test/path");
        when(httpUtils.contextToRole("/test/path")).thenReturn("test:path");

        throttleProcessor.process(exchange);

        verify(exchange, never()).setException(any());
    }

    @Test
    void process_globalThrottle_totalCallsNegative_doesNotThrottle() throws Exception {
        ServiceMeta serviceMeta = new ServiceMeta();
        serviceMeta.setThrottleGlobal(true);
        serviceMeta.setThrottleDuration(1000);
        serviceMeta.setThrottleTotalCalls(-1);

        Service service = new Service();
        service.setId("test-service");
        service.setServiceMeta(serviceMeta);

        serviceCache.put("test:path", service);

        when(exchange.getIn()).thenReturn(message);
        when(message.getHeader(Oauth2Constants.CAMEL_SERVLET_CONTEXT_PATH)).thenReturn("/test/path");
        when(httpUtils.contextToRole("/test/path")).thenReturn("test:path");

        throttleProcessor.process(exchange);

        verify(exchange, never()).setException(any());
    }

    @Test
    void process_consumerThrottle_withHeaders_firstCall_allows() throws Exception {
        ServiceMeta serviceMeta = new ServiceMeta();
        serviceMeta.setThrottleGlobal(false);

        Service service = new Service();
        service.setId("test-service-consumer");
        service.setServiceMeta(serviceMeta);

        serviceCache.put("test:path", service);

        when(exchange.getIn()).thenReturn(message);
        when(message.getHeader(Oauth2Constants.CAMEL_SERVLET_CONTEXT_PATH)).thenReturn("/test/path");
        when(httpUtils.contextToRole("/test/path")).thenReturn("test:path");
        when(message.getHeader(Constants.CAPI_META_THROTTLE_CONSUMER_KEY)).thenReturn("consumer-123");
        when(message.getHeader(Constants.CAPI_META_THROTTLE_DURATION)).thenReturn(60000L);
        when(message.getHeader(Constants.CAPI_META_THROTTLE_TOTAL_CALLS_ALLOWED)).thenReturn(100L);

        throttleProcessor.process(exchange);

        verify(exchange, never()).setException(any());
    }

    @Test
    void process_consumerThrottle_missingHeaders_doesNotThrottle() throws Exception {
        ServiceMeta serviceMeta = new ServiceMeta();
        serviceMeta.setThrottleGlobal(false);

        Service service = new Service();
        service.setId("test-service");
        service.setServiceMeta(serviceMeta);

        serviceCache.put("test:path", service);

        when(exchange.getIn()).thenReturn(message);
        when(message.getHeader(Oauth2Constants.CAMEL_SERVLET_CONTEXT_PATH)).thenReturn("/test/path");
        when(httpUtils.contextToRole("/test/path")).thenReturn("test:path");
        when(message.getHeader(Constants.CAPI_META_THROTTLE_CONSUMER_KEY)).thenReturn(null);

        throttleProcessor.process(exchange);

        verify(exchange, never()).setException(any());
    }

    @Test
    void process_exceptionInProcessing_isCaughtSilently() throws Exception {
        when(exchange.getIn()).thenReturn(message);
        when(message.getHeader(Oauth2Constants.CAMEL_SERVLET_CONTEXT_PATH)).thenThrow(new RuntimeException("test error"));

        assertDoesNotThrow(() -> throttleProcessor.process(exchange));
    }

    @Test
    void canContinue_firstCall_returnsTrue() {
        Service service = new Service();
        service.setId("svc-first-call");
        ServiceMeta meta = new ServiceMeta();
        meta.setThrottleTotalCalls(10);
        meta.setThrottleDuration(60000);
        service.setServiceMeta(meta);

        assertTrue(throttleProcessor.canContinue(service, null, false, -1, -1));
    }

    @Test
    void canContinue_withinLimit_returnsTrue() {
        Service service = new Service();
        service.setId("svc-within-limit");
        ServiceMeta meta = new ServiceMeta();
        meta.setThrottleTotalCalls(5);
        meta.setThrottleDuration(60000);
        service.setServiceMeta(meta);

        // Call multiple times within limit
        for (int i = 0; i < 4; i++) {
            assertTrue(throttleProcessor.canContinue(service, null, false, -1, -1));
        }
    }

    @Test
    void canContinue_exceedingLimit_returnsFalse() {
        Service service = new Service();
        service.setId("svc-exceed-limit-v2");
        ServiceMeta meta = new ServiceMeta();
        meta.setThrottleTotalCalls(3);
        meta.setThrottleDuration(60000);
        service.setServiceMeta(meta);

        assertTrue(throttleProcessor.canContinue(service, null, false, -1, -1));
        assertTrue(throttleProcessor.canContinue(service, null, false, -1, -1));
        assertTrue(throttleProcessor.canContinue(service, null, false, -1, -1));
        assertFalse(throttleProcessor.canContinue(service, null, false, -1, -1));
    }

    @Test
    void canContinue_expiredEntry_resets() throws InterruptedException {
        Service service = new Service();
        service.setId("svc-expired");
        ServiceMeta meta = new ServiceMeta();
        meta.setThrottleTotalCalls(2);
        meta.setThrottleDuration(100); // 100ms expiration
        service.setServiceMeta(meta);

        assertTrue(throttleProcessor.canContinue(service, null, false, -1, -1));
        assertTrue(throttleProcessor.canContinue(service, null, false, -1, -1));
        assertFalse(throttleProcessor.canContinue(service, null, false, -1, -1));

        // Wait for expiration
        Thread.sleep(150);

        // Should reset and allow again
        assertTrue(throttleProcessor.canContinue(service, null, false, -1, -1));
    }

    @Test
    void canContinue_consumerBased_differentKeys() {
        Service service = new Service();
        service.setId("svc-consumer");
        ServiceMeta meta = new ServiceMeta();
        service.setServiceMeta(meta);

        // Consumer A: limit of 2
        assertTrue(throttleProcessor.canContinue(service, "consumer-A", true, 2, 60000));
        assertTrue(throttleProcessor.canContinue(service, "consumer-A", true, 2, 60000));
        assertFalse(throttleProcessor.canContinue(service, "consumer-A", true, 2, 60000));

        // Consumer B: should still be allowed (different key)
        assertTrue(throttleProcessor.canContinue(service, "consumer-B", true, 2, 60000));
    }
}
