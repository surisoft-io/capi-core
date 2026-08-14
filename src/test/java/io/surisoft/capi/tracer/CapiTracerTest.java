package io.surisoft.capi.tracer;

import io.opentelemetry.api.trace.*;
import io.surisoft.capi.schema.*;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.HttpUtils;
import io.undertow.server.ExchangeCompletionListener;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.ServerConnection;
import io.undertow.util.HeaderMap;
import io.undertow.util.HttpString;
import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CapiTracerTest {

    @Mock
    private Tracer tracer;

    @Mock
    private SpanBuilder spanBuilder;

    @Mock
    private Span span;

    @Mock
    private SpanContext spanContext;

    @Mock
    private HttpUtils httpUtils;

    private Cache<String, Service> serviceCache;
    private CapiTracer capiTracer;

    @BeforeEach
    void setUp() {
        serviceCache = Cache2kBuilder.of(String.class, Service.class)
                .name("capiTracerTest-" + System.nanoTime())
                .eternal(true)
                .entryCapacity(100)
                .build();

        capiTracer = new CapiTracer(tracer, httpUtils, serviceCache, "test-instance");

        // Common mock setup for span builder chain
        when(tracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        when(spanBuilder.setSpanKind(any(SpanKind.class))).thenReturn(spanBuilder);
        when(spanBuilder.setAttribute(anyString(), anyString())).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(span);
        when(span.getSpanContext()).thenReturn(spanContext);
        when(spanContext.getTraceId()).thenReturn("abc123traceId");
    }

    @AfterEach
    void tearDown() {
        serviceCache.close();
    }

    private HttpServerExchange createExchange(String method, String path) {
        ServerConnection connection = mock(ServerConnection.class);
        when(connection.getBufferPool()).thenReturn(null);
        HttpServerExchange exchange = new HttpServerExchange(connection);
        exchange.setRequestMethod(new HttpString(method));
        exchange.setRequestPath(path);
        exchange.setRelativePath(path);
        return exchange;
    }

    // ---- traceRequest(exchange, RestClient) ----

    @Test
    void traceRequest_withRestClient_createsSpanAndDecoratesIt() throws Exception {
        when(httpUtils.contextToRole("/my-service/v1")).thenReturn("my-service:v1");
        when(httpUtils.processAuthorizationAccessToken(any(HttpServerExchange.class))).thenReturn(null);

        HttpServerExchange exchange = createExchange("GET", "/my-service/v1/items");

        RestClient restClient = new RestClient();
        restClient.setServiceId("/my-service/v1");

        capiTracer.traceRequest(exchange, restClient);

        verify(tracer).spanBuilder("my-service:v1:get");
        verify(spanBuilder).setSpanKind(SpanKind.SERVER);
        verify(spanBuilder).setAttribute("http.method", "GET");
        verify(spanBuilder).setAttribute("http.url", "/my-service/v1/items");
        verify(spanBuilder).setAttribute("http.target", "my-service:v1");
        verify(spanBuilder).setAttribute("component", "capi-rest-gateway");
        verify(spanBuilder).setAttribute("capi-instance", "test-instance");
        verify(spanBuilder).startSpan();
    }

    @Test
    void traceRequest_withRestClient_andAccessToken_decoratesTokenAttributes() throws Exception {
        when(httpUtils.contextToRole("/secured-svc/v1")).thenReturn("secured-svc:v1");

        // Create a properly-formed signed JWT (HS256 with dummy signature)
        String header = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes());
        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"exp\":1999999999,\"azp\":\"my-client\",\"clientHost\":\"10.0.0.1\",\"iss\":\"http://keycloak/realms/test\"}".getBytes());
        String signature = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("fakesignature1234567890123456".getBytes());
        String fakeJwt = header + "." + payload + "." + signature;

        when(httpUtils.processAuthorizationAccessToken(any(HttpServerExchange.class))).thenReturn(fakeJwt);

        HttpServerExchange exchange = createExchange("POST", "/secured-svc/v1/data");

        RestClient restClient = new RestClient();
        restClient.setServiceId("/secured-svc/v1");

        capiTracer.traceRequest(exchange, restClient);

        // Verify token-related attributes are set on the span
        verify(span).setAttribute(eq(Constants.CAPI_TOKEN_EXPIRED), anyString());
        verify(span).setAttribute(eq(Constants.CAPI_EXCHANGE_REQUESTER_ID), eq("my-client"));
        verify(span).setAttribute(eq("capi.requester.host"), eq("10.0.0.1"));
        verify(span).setAttribute(eq(Constants.CAPI_REQUESTER_TOKEN_ISSUER), eq("http://keycloak/realms/test"));
    }

    @Test
    void traceRequest_withRestClient_andExtraServiceMeta_setsMetaAttributes() throws Exception {
        when(httpUtils.contextToRole("/meta-svc/v1")).thenReturn("meta-svc:v1");
        when(httpUtils.processAuthorizationAccessToken(any(HttpServerExchange.class))).thenReturn(null);

        Service service = new Service();
        ServiceMeta meta = new ServiceMeta();
        meta.addExtraServiceMeta("env", "production");
        meta.addExtraServiceMeta("region", "us-east-1");
        service.setServiceMeta(meta);
        serviceCache.put("meta-svc:v1", service);

        HttpServerExchange exchange = createExchange("GET", "/meta-svc/v1/health");

        RestClient restClient = new RestClient();
        restClient.setServiceId("/meta-svc/v1");
        // serviceCache is keyed on the canonical "<name>:<group>" id, not the context path
        // (RestTransportHandler:111 sets this in production). Without it the lookup misses.
        restClient.setCanonicalServiceId("meta-svc:v1");

        capiTracer.traceRequest(exchange, restClient);

        verify(span).setAttribute("env", "production");
        verify(span).setAttribute("region", "us-east-1");
    }

    @Test
    void traceRequest_withRestClient_andContentType_setsContentTypeAttribute() throws Exception {
        when(httpUtils.contextToRole("/ct-svc/v1")).thenReturn("ct-svc:v1");
        when(httpUtils.processAuthorizationAccessToken(any(HttpServerExchange.class))).thenReturn(null);

        HttpServerExchange exchange = createExchange("POST", "/ct-svc/v1/data");
        exchange.getRequestHeaders().put(new HttpString("Content-Type"), "application/json");

        RestClient restClient = new RestClient();
        restClient.setServiceId("/ct-svc/v1");

        capiTracer.traceRequest(exchange, restClient);

        verify(span).setAttribute("capi.incoming.request.content.type", "application/json");
    }

    @Test
    void traceRequest_withRestClient_andXForwardedFor_setsOriginalIpAttribute() throws Exception {
        when(httpUtils.contextToRole("/xff-svc/v1")).thenReturn("xff-svc:v1");
        when(httpUtils.processAuthorizationAccessToken(any(HttpServerExchange.class))).thenReturn(null);

        HttpServerExchange exchange = createExchange("GET", "/xff-svc/v1/items");
        exchange.getRequestHeaders().put(new HttpString("X-Forwarded-For"), "192.168.1.100");

        RestClient restClient = new RestClient();
        restClient.setServiceId("/xff-svc/v1");

        capiTracer.traceRequest(exchange, restClient);

        verify(span).setAttribute("capi.incoming.request.original.ip", "192.168.1.100");
    }

    @Test
    void traceRequest_withRestClient_noServiceInCache_skipsExtraMeta() throws Exception {
        when(httpUtils.contextToRole("/nosvc/v1")).thenReturn("nosvc:v1");
        when(httpUtils.processAuthorizationAccessToken(any(HttpServerExchange.class))).thenReturn(null);

        HttpServerExchange exchange = createExchange("GET", "/nosvc/v1/items");

        RestClient restClient = new RestClient();
        restClient.setServiceId("/nosvc/v1");

        // Service not in cache - should not throw
        assertDoesNotThrow(() -> capiTracer.traceRequest(exchange, restClient));
    }

    @Test
    void traceRequest_withRestClient_tokenParseException_doesNotThrow() throws Exception {
        when(httpUtils.contextToRole("/err-svc/v1")).thenReturn("err-svc:v1");
        when(httpUtils.processAuthorizationAccessToken(any(HttpServerExchange.class))).thenReturn("not-a-valid-jwt");

        HttpServerExchange exchange = createExchange("GET", "/err-svc/v1/items");

        RestClient restClient = new RestClient();
        restClient.setServiceId("/err-svc/v1");

        assertDoesNotThrow(() -> capiTracer.traceRequest(exchange, restClient));
    }

    // ---- traceRequest(exchange, String serviceId) ----

    @Test
    void traceRequest_withServiceId_createsSpanCorrectly() throws Exception {
        when(httpUtils.processAuthorizationAccessToken(any(HttpServerExchange.class))).thenReturn(null);

        HttpServerExchange exchange = createExchange("GET", "/ws/my-service/v1");

        capiTracer.traceRequest(exchange, "my-ws-service");

        verify(tracer).spanBuilder("my-ws-service:get");
        verify(spanBuilder).setSpanKind(SpanKind.SERVER);
        verify(spanBuilder).setAttribute("component", "capi-gateway");
        verify(spanBuilder).setAttribute("http.target", "my-ws-service");
        verify(spanBuilder).startSpan();
    }

    @Test
    void traceRequest_withServiceId_andToken_decoratesAttributes() throws Exception {
        String header = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes());
        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"exp\":1999999999,\"azp\":\"ws-client\",\"iss\":\"http://keycloak/realms/ws\"}".getBytes());
        String signature = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("fakesignature1234567890123456".getBytes());
        String fakeJwt = header + "." + payload + "." + signature;

        when(httpUtils.processAuthorizationAccessToken(any(HttpServerExchange.class))).thenReturn(fakeJwt);

        HttpServerExchange exchange = createExchange("GET", "/ws/svc");

        capiTracer.traceRequest(exchange, "ws-svc");

        verify(span).setAttribute(eq(Constants.CAPI_EXCHANGE_REQUESTER_ID), eq("ws-client"));
        verify(span).setAttribute(eq(Constants.CAPI_REQUESTER_TOKEN_ISSUER), eq("http://keycloak/realms/ws"));
    }

    @Test
    void traceRequest_withServiceId_andContentTypeAndXFF_setsAttributes() throws Exception {
        when(httpUtils.processAuthorizationAccessToken(any(HttpServerExchange.class))).thenReturn(null);

        HttpServerExchange exchange = createExchange("POST", "/grpc/svc");
        exchange.getRequestHeaders().put(new HttpString("Content-Type"), "application/grpc");
        exchange.getRequestHeaders().put(new HttpString("X-Forwarded-For"), "10.0.0.50");

        capiTracer.traceRequest(exchange, "grpc-svc");

        verify(span).setAttribute("capi.incoming.request.content.type", "application/grpc");
        verify(span).setAttribute("capi.incoming.request.original.ip", "10.0.0.50");
    }

    @Test
    void traceRequest_withServiceId_tokenParseException_doesNotThrow() throws Exception {
        when(httpUtils.processAuthorizationAccessToken(any(HttpServerExchange.class))).thenReturn("bad-jwt");

        HttpServerExchange exchange = createExchange("GET", "/ws/svc");

        assertDoesNotThrow(() -> capiTracer.traceRequest(exchange, "ws-svc"));
    }

    @Test
    void traceRequest_withServiceId_nullToken_noTokenAttributes() throws Exception {
        when(httpUtils.processAuthorizationAccessToken(any(HttpServerExchange.class))).thenReturn(null);

        HttpServerExchange exchange = createExchange("DELETE", "/ws/svc");

        capiTracer.traceRequest(exchange, "del-svc");

        // Token attributes should NOT be set
        verify(span, never()).setAttribute(eq(Constants.CAPI_TOKEN_EXPIRED), anyString());
        verify(span, never()).setAttribute(eq(Constants.CAPI_EXCHANGE_REQUESTER_ID), anyString());
    }

    @Test
    void traceRequest_withRestClient_expiredToken_setsExpiredTrue() throws Exception {
        when(httpUtils.contextToRole("/exp-svc/v1")).thenReturn("exp-svc:v1");

        // Token with past expiration - proper signed JWT format
        String header = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes());
        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"exp\":1000000000,\"azp\":\"expired-client\"}".getBytes());
        String signature = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("fakesignature1234567890123456".getBytes());
        String fakeJwt = header + "." + payload + "." + signature;

        when(httpUtils.processAuthorizationAccessToken(any(HttpServerExchange.class))).thenReturn(fakeJwt);

        HttpServerExchange exchange = createExchange("GET", "/exp-svc/v1/data");

        RestClient restClient = new RestClient();
        restClient.setServiceId("/exp-svc/v1");

        capiTracer.traceRequest(exchange, restClient);

        verify(span).setAttribute(Constants.CAPI_TOKEN_EXPIRED, "true");
    }

    @Test
    void traceRequest_withRestClient_serviceWithNullExtraMeta_doesNotThrow() throws Exception {
        when(httpUtils.contextToRole("/nullmeta-svc/v1")).thenReturn("nullmeta-svc:v1");
        when(httpUtils.processAuthorizationAccessToken(any(HttpServerExchange.class))).thenReturn(null);

        Service service = new Service();
        ServiceMeta meta = new ServiceMeta();
        // extraServiceMeta is initialized to empty ConcurrentHashMap by default, not null
        service.setServiceMeta(meta);
        serviceCache.put("nullmeta-svc:v1", service);

        HttpServerExchange exchange = createExchange("GET", "/nullmeta-svc/v1/items");

        RestClient restClient = new RestClient();
        restClient.setServiceId("/nullmeta-svc/v1");

        assertDoesNotThrow(() -> capiTracer.traceRequest(exchange, restClient));
    }

    @Test
    void constructor_setsFieldsCorrectly() {
        assertNotNull(capiTracer);
    }

    // ---- completion-listener FD-leak regression (endSpan must ALWAYS proceed) ----
    //
    // Undertow runs completion listeners LIFO and the tracer registers last, so it runs FIRST.
    // If it fails to call proceed(), the chain stalls before RestGateway's hardened access-log
    // and metrics listeners, Undertow's terminal connection cleanup never runs, and the socket
    // FD leaks for the life of the process. These tests pin that contract.

    @Test
    void endSpan_happyPath_endsSpanAndProceeds() {
        HttpServerExchange exchange = createExchange("GET", "/svc/v1/items");
        exchange.setStatusCode(200);
        ExchangeCompletionListener.NextListener nextListener = mock(ExchangeCompletionListener.NextListener.class);

        capiTracer.endSpan(exchange, span, "svc:v1", System.currentTimeMillis(), nextListener);

        verify(span).updateName("svc:v1");
        verify(span).end();
        verify(nextListener).proceed();
    }

    @Test
    void endSpan_whenSetAttributeThrows_stillEndsSpanAndProceeds() {
        HttpServerExchange exchange = createExchange("GET", "/svc/v1/items");
        exchange.setStatusCode(200);
        ExchangeCompletionListener.NextListener nextListener = mock(ExchangeCompletionListener.NextListener.class);

        doThrow(new RuntimeException("exporter blew up"))
                .when(span).setAttribute(eq("capi.client.response.time"), anyString());

        capiTracer.endSpan(exchange, span, "svc:v1", System.currentTimeMillis(), nextListener);

        // decoration failed, but the span must still be closed and the chain must still advance
        verify(span).end();
        verify(nextListener).proceed();
    }

    @Test
    void endSpan_whenSpanEndThrows_stillProceeds() {
        HttpServerExchange exchange = createExchange("GET", "/svc/v1/items");
        exchange.setStatusCode(200);
        ExchangeCompletionListener.NextListener nextListener = mock(ExchangeCompletionListener.NextListener.class);

        doThrow(new RuntimeException("SimpleSpanProcessor export failed")).when(span).end();

        capiTracer.endSpan(exchange, span, "svc:v1", System.currentTimeMillis(), nextListener);

        verify(nextListener).proceed();
    }

    @Test
    void endSpan_whenDecorationAndEndBothThrow_stillProceeds() {
        HttpServerExchange exchange = createExchange("GET", "/svc/v1/items");
        exchange.setStatusCode(500);
        ExchangeCompletionListener.NextListener nextListener = mock(ExchangeCompletionListener.NextListener.class);

        doThrow(new RuntimeException("decorate failed"))
                .when(span).setStatus(any(StatusCode.class), anyString());
        doThrow(new RuntimeException("end failed")).when(span).end();

        capiTracer.endSpan(exchange, span, "svc:v1", System.currentTimeMillis(), nextListener);

        verify(nextListener).proceed();
    }

    @Test
    void endSpan_whenSpanThrowsError_stillProceeds() {
        HttpServerExchange exchange = createExchange("GET", "/svc/v1/items");
        exchange.setStatusCode(200);
        ExchangeCompletionListener.NextListener nextListener = mock(ExchangeCompletionListener.NextListener.class);

        // Throwable, not Exception: leaking an FD forever is worse than swallowing an Error here
        doThrow(new StackOverflowError("agent instrumentation")).when(span).end();

        capiTracer.endSpan(exchange, span, "svc:v1", System.currentTimeMillis(), nextListener);

        verify(nextListener).proceed();
    }
}
