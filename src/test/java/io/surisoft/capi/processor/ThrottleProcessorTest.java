package io.surisoft.capi.processor;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import io.surisoft.capi.schema.ThrottleServiceObject;
import io.surisoft.capi.utils.HttpUtils;
import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ThrottleProcessorTest {

    @Mock
    private HttpUtils httpUtils;

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
    void canContinue_globalThrottle_firstCall_returnsTrue() {
        Service service = createServiceWithGlobalThrottle(10, 60000);
        serviceCache.put("test-service", service);

        assertTrue(throttleProcessor.canContinue(service, null, false, -1, -1));
    }

    @Test
    void canContinue_globalThrottle_exceedsLimit_returnsFalse() {
        Service service = createServiceWithGlobalThrottle(2, 60000);
        serviceCache.put("test-service", service);

        assertTrue(throttleProcessor.canContinue(service, null, false, -1, -1));
        assertTrue(throttleProcessor.canContinue(service, null, false, -1, -1));
        assertFalse(throttleProcessor.canContinue(service, null, false, -1, -1));
    }

    @Test
    void canContinue_consumerThrottle_firstCall_returnsTrue() {
        Service service = createService();
        serviceCache.put("test-service", service);

        assertTrue(throttleProcessor.canContinue(service, "consumer1", true, 5, 60000));
    }

    @Test
    void canContinue_consumerThrottle_exceedsLimit_returnsFalse() {
        Service service = createService();
        serviceCache.put("test-service", service);

        assertTrue(throttleProcessor.canContinue(service, "consumer1", true, 2, 60000));
        assertTrue(throttleProcessor.canContinue(service, "consumer1", true, 2, 60000));
        assertFalse(throttleProcessor.canContinue(service, "consumer1", true, 2, 60000));
    }

    @Test
    void canContinue_differentConsumers_independent() {
        Service service = createService();
        serviceCache.put("test-service", service);

        assertTrue(throttleProcessor.canContinue(service, "consumer1", true, 1, 60000));
        assertFalse(throttleProcessor.canContinue(service, "consumer1", true, 1, 60000));
        assertTrue(throttleProcessor.canContinue(service, "consumer2", true, 1, 60000));
    }

    @Test
    void canContinue_globalThrottle_afterExpiry_resetsAndAllows() throws InterruptedException {
        Service service = createServiceWithGlobalThrottle(1, 500); // 500ms duration
        serviceCache.put("test-service", service);

        assertTrue(throttleProcessor.canContinue(service, null, false, -1, -1));
        assertFalse(throttleProcessor.canContinue(service, null, false, -1, -1));

        Thread.sleep(600);

        assertTrue(throttleProcessor.canContinue(service, null, false, -1, -1));
    }

    private Service createService() {
        Service service = new Service();
        service.setId("test-service");
        service.setServiceMeta(new ServiceMeta());
        return service;
    }

    private Service createServiceWithGlobalThrottle(long totalCalls, long duration) {
        ServiceMeta meta = new ServiceMeta();
        meta.setThrottleGlobal(true);
        meta.setThrottleDuration(duration);
        meta.setThrottleTotalCalls(totalCalls);

        Service service = new Service();
        service.setId("test-service");
        service.setServiceMeta(meta);
        return service;
    }
}
