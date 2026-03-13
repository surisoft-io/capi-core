package io.surisoft.capi.processor;

import io.surisoft.capi.exception.AuthorizationException;
import io.surisoft.capi.oidc.Oauth2Constants;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import io.surisoft.capi.service.OpaService;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthorizationProcessorTest {

    @Mock
    private HttpUtils httpUtils;
    @Mock
    private OpaService opaService;
    @Mock
    private Exchange exchange;
    @Mock
    private Message message;

    private Cache<String, Service> serviceCache;
    private AuthorizationProcessor processor;

    @BeforeEach
    void setUp() {
        serviceCache = Cache2kBuilder.of(String.class, Service.class)
                .name("authProcessorTestCache-" + System.nanoTime())
                .eternal(true)
                .build();
        processor = new AuthorizationProcessor(httpUtils, serviceCache, opaService, null);
        when(exchange.getIn()).thenReturn(message);
    }

    @AfterEach
    void tearDown() {
        if (serviceCache != null) {
            serviceCache.close();
        }
    }

    @Test
    void process_validToken_authorized() throws Exception {
        ServiceMeta serviceMeta = new ServiceMeta();
        serviceMeta.setThrottle(false);

        Service service = new Service();
        service.setId("test-service");
        service.setServiceMeta(serviceMeta);

        serviceCache.put("test:path", service);

        when(message.getHeader(Oauth2Constants.CAMEL_SERVLET_CONTEXT_PATH)).thenReturn("/test/path");
        when(httpUtils.processAuthorizationAccessToken(exchange)).thenReturn("valid-token");
        when(httpUtils.contextToRole("/test/path")).thenReturn("test:path");
        when(httpUtils.isAuthorized("valid-token", "/test/path", service, opaService)).thenReturn(true);

        processor.process(exchange);

        verify(httpUtils).propagateAuthorization(exchange, "valid-token");
        verify(httpUtils).prepareForThrottleIfNeeded(service, "valid-token", exchange);
        verify(exchange, never()).setException(any());
    }

    @Test
    void process_validToken_notAuthorized_setsException() throws Exception {
        ServiceMeta serviceMeta = new ServiceMeta();

        Service service = new Service();
        service.setId("test-service");
        service.setServiceMeta(serviceMeta);

        serviceCache.put("test:path", service);

        when(message.getHeader(Oauth2Constants.CAMEL_SERVLET_CONTEXT_PATH)).thenReturn("/test/path");
        when(httpUtils.processAuthorizationAccessToken(exchange)).thenReturn("invalid-token");
        when(httpUtils.contextToRole("/test/path")).thenReturn("test:path");
        when(httpUtils.isAuthorized("invalid-token", "/test/path", service, opaService)).thenReturn(false);

        processor.process(exchange);

        verify(exchange).setProperty(Constants.REASON_MESSAGE_HEADER, "Not subscribed");
        verify(message).setHeader(Constants.REASON_MESSAGE_HEADER, "Not subscribed");
        verify(message).setHeader(Constants.REASON_CODE_HEADER, 403);
        verify(exchange).setException(any(AuthorizationException.class));
    }

    @Test
    void process_nullToken_setsException() throws Exception {
        ServiceMeta serviceMeta = new ServiceMeta();

        Service service = new Service();
        service.setId("test-service");
        service.setServiceMeta(serviceMeta);

        serviceCache.put("test:path", service);

        when(message.getHeader(Oauth2Constants.CAMEL_SERVLET_CONTEXT_PATH)).thenReturn("/test/path");
        when(httpUtils.processAuthorizationAccessToken(exchange)).thenReturn(null);
        when(httpUtils.contextToRole("/test/path")).thenReturn("test:path");

        processor.process(exchange);

        verify(exchange).setProperty(Constants.REASON_MESSAGE_HEADER, "No authorization header provided");
        verify(message).setHeader(Constants.REASON_MESSAGE_HEADER, "No authorization header provided");
        verify(message).setHeader(Constants.REASON_CODE_HEADER, 403);
        verify(exchange).setException(any(AuthorizationException.class));
    }

    @Test
    void process_authorizationExceptionThrown_setsException() throws Exception {
        when(message.getHeader(Oauth2Constants.CAMEL_SERVLET_CONTEXT_PATH)).thenReturn("/test/path");
        when(httpUtils.processAuthorizationAccessToken(exchange)).thenThrow(new AuthorizationException("Token expired"));
        when(httpUtils.contextToRole("/test/path")).thenReturn("test:path");

        ServiceMeta serviceMeta = new ServiceMeta();
        Service service = new Service();
        service.setId("test-service");
        service.setServiceMeta(serviceMeta);
        serviceCache.put("test:path", service);

        processor.process(exchange);

        verify(exchange).setProperty(Constants.REASON_MESSAGE_HEADER, "Token expired");
        verify(message).setHeader(Constants.REASON_MESSAGE_HEADER, "Token expired");
        verify(message).setHeader(Constants.REASON_CODE_HEADER, 403);
        verify(exchange).setException(any(AuthorizationException.class));
    }

    @Test
    void process_withNullOpaService() throws Exception {
        AuthorizationProcessor processorNoOpa = new AuthorizationProcessor(httpUtils, serviceCache, null, null);

        ServiceMeta serviceMeta = new ServiceMeta();
        serviceMeta.setThrottle(false);

        Service service = new Service();
        service.setId("test-service");
        service.setServiceMeta(serviceMeta);

        serviceCache.put("test:path", service);

        when(message.getHeader(Oauth2Constants.CAMEL_SERVLET_CONTEXT_PATH)).thenReturn("/test/path");
        when(httpUtils.processAuthorizationAccessToken(exchange)).thenReturn("valid-token");
        when(httpUtils.contextToRole("/test/path")).thenReturn("test:path");
        when(httpUtils.isAuthorized("valid-token", "/test/path", service, null)).thenReturn(true);

        processorNoOpa.process(exchange);

        verify(httpUtils).propagateAuthorization(exchange, "valid-token");
    }
}
