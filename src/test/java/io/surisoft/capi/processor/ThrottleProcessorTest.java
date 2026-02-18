package io.surisoft.capi.processor;

import io.surisoft.capi.exception.AuthorizationException;
import io.surisoft.capi.kafka.CapiInstance;
import io.surisoft.capi.oidc.Oauth2Constants;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.HttpUtils;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.junit.jupiter.api.AfterEach;
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
    private final CapiInstance capiInstance = new CapiInstance("test-uuid");

    @BeforeEach
    void setUp() {
        serviceCache = Cache2kBuilder.of(String.class, Service.class)
                .name("throttleTestCache-" + System.nanoTime())
                .eternal(true)
                .build();
        throttleProcessor = new ThrottleProcessor(serviceCache, httpUtils, "test-topic", capiInstance);
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
        // serviceCache has no entry for "test:path", so service is null

        throttleProcessor.process(exchange);

        verify(exchange, never()).setException(any());
    }

    @Test
    void process_globalThrottle_cannotContinue_setsException() throws Exception {
        ServiceMeta serviceMeta = new ServiceMeta();
        serviceMeta.setThrottleGlobal(true);
        serviceMeta.setThrottleDuration(1000);
        serviceMeta.setThrottleTotalCalls(10);

        Service service = new Service();
        service.setId("test-service");
        service.setServiceMeta(serviceMeta);

        serviceCache.put("test:path", service);

        when(exchange.getIn()).thenReturn(message);
        when(message.getHeader(Oauth2Constants.CAMEL_SERVLET_CONTEXT_PATH)).thenReturn("/test/path");
        when(httpUtils.contextToRole("/test/path")).thenReturn("test:path");

        throttleProcessor.process(exchange);

        // canContinue always returns false (logic is commented out), so exception should be set
        verify(exchange).setProperty(Constants.REASON_MESSAGE_HEADER, "Too Many requests");
        verify(message).setHeader(Constants.REASON_MESSAGE_HEADER, "Too Many requests");
        verify(message).setHeader(Constants.REASON_CODE_HEADER, 407);
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
    void process_consumerThrottle_withHeaders_cannotContinue_setsException() throws Exception {
        ServiceMeta serviceMeta = new ServiceMeta();
        serviceMeta.setThrottleGlobal(false);

        Service service = new Service();
        service.setId("test-service");
        service.setServiceMeta(serviceMeta);

        serviceCache.put("test:path", service);

        when(exchange.getIn()).thenReturn(message);
        when(message.getHeader(Oauth2Constants.CAMEL_SERVLET_CONTEXT_PATH)).thenReturn("/test/path");
        when(httpUtils.contextToRole("/test/path")).thenReturn("test:path");
        when(message.getHeader(Constants.CAPI_META_THROTTLE_CONSUMER_KEY)).thenReturn("consumer-123");
        when(message.getHeader(Constants.CAPI_META_THROTTLE_DURATION)).thenReturn(5000L);
        when(message.getHeader(Constants.CAPI_META_THROTTLE_TOTAL_CALLS_ALLOWED)).thenReturn(100L);

        throttleProcessor.process(exchange);

        // canContinue always returns false (logic is commented out)
        verify(exchange).setProperty(Constants.REASON_MESSAGE_HEADER, "Too Many requests");
        verify(message).setHeader(Constants.REASON_MESSAGE_HEADER, "Too Many requests");
        verify(message).setHeader(Constants.REASON_CODE_HEADER, 407);
        verify(exchange).setException(any(AuthorizationException.class));
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

        // Should not throw - exception is caught internally
        assertDoesNotThrow(() -> throttleProcessor.process(exchange));
    }

    @Test
    void canContinue_alwaysReturnsFalse() {
        Service service = new Service();
        // Since the logic is commented out, canContinue always returns false
        assertFalse(throttleProcessor.canContinue(exchange, service, null, false, -1, -1));
        assertFalse(throttleProcessor.canContinue(exchange, service, "consumer-key", true, 100, 5000));
    }
}
