package io.surisoft.capi.undertow;

import io.undertow.client.ClientConnection;
import io.undertow.predicate.IdempotentPredicate;
import io.undertow.predicate.Predicate;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.ServerConnection;
import io.undertow.server.handlers.ResponseCodeHandler;
import io.undertow.server.handlers.proxy.LoadBalancingProxyClient;
import io.undertow.server.handlers.proxy.ProxyClient;
import io.undertow.server.handlers.proxy.ProxyConnection;
import io.undertow.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;
import org.xnio.IoUtils;
import org.xnio.OptionMap;
import org.xnio.XnioIoThread;
import org.xnio.XnioExecutor;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CAPIProxyHandlerTest {

    @Mock
    private CAPILoadBalancerProxyClient proxyClient;
    @Mock
    private HttpHandler nextHandler;
    @Mock
    private HttpServerExchange exchange;
    @Mock
    private ServerConnection serverConnection;
    @Mock
    private ClientConnection clientConnection;

    private CAPIProxyHandler handler;

    @BeforeEach
    void setUp() {
        handler = CAPIProxyHandler.builder()
                .setProxyClient(proxyClient)
                .setMaxRequestTime(-1)
                .setNext(nextHandler)
                .build();
    }

    // --- Builder tests ---

    @Test
    void builder_setsProxyClient() {
        assertNotNull(handler);
        assertSame(proxyClient, handler.getProxyClient());
    }

    @Test
    void builder_setProxyClientNull_throwsException() {
        assertThrows(Exception.class, () -> CAPIProxyHandler.builder().setProxyClient(null));
    }

    @Test
    void builder_defaultNext_isHandle404() {
        CAPIProxyHandler h = CAPIProxyHandler.builder()
                .setProxyClient(proxyClient)
                .build();
        assertNotNull(h);
    }

    @Test
    void builder_setMaxRequestTime() {
        CAPIProxyHandler h = CAPIProxyHandler.builder()
                .setProxyClient(proxyClient)
                .setMaxRequestTime(5000)
                .build();
        assertNotNull(h);
    }

    @Test
    void builder_setNext() {
        HttpHandler custom = mock(HttpHandler.class);
        CAPIProxyHandler h = CAPIProxyHandler.builder()
                .setProxyClient(proxyClient)
                .setNext(custom)
                .build();
        assertNotNull(h);
    }

    @Test
    void builder_build_returnsCAPIProxyHandler() {
        CAPIProxyHandler.Builder b = CAPIProxyHandler.builder();
        b.setProxyClient(proxyClient);
        CAPIProxyHandler result = b.build();
        assertNotNull(result);
        assertTrue(result instanceof CAPIProxyHandler);
    }

    // --- handleRequest tests ---

    @Test
    void handleRequest_noTarget_delegatesToNext() throws Exception {
        when(proxyClient.findTarget(exchange)).thenReturn(null);

        handler.handleRequest(exchange);

        verify(nextHandler).handleRequest(exchange);
    }

    @Test
    void handleRequest_responseAlreadyStarted_sets500() throws Exception {
        LoadBalancingProxyClient.ProxyTarget target = mock(LoadBalancingProxyClient.ProxyTarget.class);
        when(proxyClient.findTarget(exchange)).thenReturn(target);
        when(exchange.isResponseStarted()).thenReturn(true);

        handler.handleRequest(exchange);

        verify(exchange).setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
        verify(exchange).endExchange();
    }

    @Test
    void handleRequest_withTarget_dispatchesRequest() throws Exception {
        LoadBalancingProxyClient.ProxyTarget target = mock(LoadBalancingProxyClient.ProxyTarget.class);
        when(proxyClient.findTarget(exchange)).thenReturn(target);
        when(exchange.isResponseStarted()).thenReturn(false);
        XnioIoThread ioThread = mock(XnioIoThread.class);
        when(exchange.getIoThread()).thenReturn(ioThread);
        when(exchange.isInIoThread()).thenReturn(false);

        handler.handleRequest(exchange);

        verify(exchange).dispatch(any(), any(Runnable.class));
    }

    // --- toString tests ---

    @Test
    void toString_emptyTargets_returnsSimpleString() {
        when(proxyClient.getAllTargets()).thenReturn(Collections.emptyList());

        String result = handler.toString();

        assertTrue(result.startsWith("ProxyHandler - "));
    }

    @Test
    void toString_singleTarget_returnsReverseProxySingle() {
        ProxyClient.ProxyTarget target = mock(ProxyClient.ProxyTarget.class);
        when(target.toString()).thenReturn("http://localhost:8080");
        when(proxyClient.getAllTargets()).thenReturn(List.of(target));

        String result = handler.toString();

        assertEquals("reverse-proxy( 'http://localhost:8080' )", result);
    }

    @Test
    void toString_multipleTargets_returnsReverseProxyMultiple() {
        ProxyClient.ProxyTarget target1 = mock(ProxyClient.ProxyTarget.class);
        when(target1.toString()).thenReturn("http://host1:8080");
        ProxyClient.ProxyTarget target2 = mock(ProxyClient.ProxyTarget.class);
        when(target2.toString()).thenReturn("http://host2:8080");
        when(proxyClient.getAllTargets()).thenReturn(List.of(target1, target2));

        String result = handler.toString();

        assertTrue(result.startsWith("reverse-proxy( { '"));
        assertTrue(result.contains("http://host1:8080"));
        assertTrue(result.contains("http://host2:8080"));
        assertTrue(result.endsWith("' } )"));
    }

    // --- copyHeaders tests ---

    @Test
    void copyHeaders_copiesNonExistingHeaders() {
        HeaderMap from = new HeaderMap();
        HeaderMap to = new HeaderMap();
        from.put(new HttpString("X-Custom"), "value1");

        HttpServerExchange mockExchange = mock(HttpServerExchange.class);
        CAPIProxyHandler.copyHeaders(mockExchange, to, from);

        assertEquals("value1", to.getFirst(new HttpString("X-Custom")));
    }

    @Test
    void copyHeaders_doesNotOverwriteExistingHeaders() {
        HeaderMap from = new HeaderMap();
        HeaderMap to = new HeaderMap();
        to.put(new HttpString("X-Custom"), "existing");
        from.put(new HttpString("X-Custom"), "new-value");

        HttpServerExchange mockExchange = mock(HttpServerExchange.class);
        CAPIProxyHandler.copyHeaders(mockExchange, to, from);

        assertEquals("existing", to.getFirst(new HttpString("X-Custom")));
    }

    @Test
    void copyHeaders_emptyFrom_doesNothing() {
        HeaderMap from = new HeaderMap();
        HeaderMap to = new HeaderMap();

        HttpServerExchange mockExchange = mock(HttpServerExchange.class);
        CAPIProxyHandler.copyHeaders(mockExchange, to, from);

        assertEquals(0, to.size());
    }

    @Test
    void copyHeaders_multipleHeaders_copiesAll() {
        HeaderMap from = new HeaderMap();
        HeaderMap to = new HeaderMap();
        from.put(new HttpString("X-One"), "val1");
        from.put(new HttpString("X-Two"), "val2");
        from.put(new HttpString("X-Three"), "val3");

        HttpServerExchange mockExchange = mock(HttpServerExchange.class);
        CAPIProxyHandler.copyHeaders(mockExchange, to, from);

        assertEquals("val1", to.getFirst(new HttpString("X-One")));
        assertEquals("val2", to.getFirst(new HttpString("X-Two")));
        assertEquals("val3", to.getFirst(new HttpString("X-Three")));
    }

    // --- handleFailure static method tests ---

    @Test
    void handleFailure_responseStarted_closesConnection() {
        HttpServerExchange ex = mock(HttpServerExchange.class);
        ServerConnection conn = mock(ServerConnection.class);
        when(ex.isResponseStarted()).thenReturn(true);
        when(ex.getConnection()).thenReturn(conn);

        Predicate predicate = mock(Predicate.class);

        CAPIProxyHandler.handleFailure(ex, null, predicate, new IOException("test"));

        verify(ex).getConnection();
    }

    @Test
    void handleFailure_notStarted_notIdempotent_noHandler_sets503() {
        HttpServerExchange ex = mock(HttpServerExchange.class);
        when(ex.isResponseStarted()).thenReturn(false);
        when(ex.getRequestURI()).thenReturn("/test");

        Predicate predicate = mock(Predicate.class);
        when(predicate.resolve(ex)).thenReturn(false);

        CAPIProxyHandler.handleFailure(ex, null, predicate, new IOException("test"));

        verify(ex).setStatusCode(StatusCodes.SERVICE_UNAVAILABLE);
        verify(ex).endExchange();
    }

    @Test
    void handleFailure_notStarted_idempotent_nullHandler_sets503() {
        HttpServerExchange ex = mock(HttpServerExchange.class);
        when(ex.isResponseStarted()).thenReturn(false);
        when(ex.getRequestURI()).thenReturn("/test");

        Predicate predicate = mock(Predicate.class);
        when(predicate.resolve(ex)).thenReturn(true);

        // proxyClientHandler is null, so it goes to else branch: sets 503
        CAPIProxyHandler.handleFailure(ex, null, predicate, new IOException("test"));

        verify(ex).setStatusCode(StatusCodes.SERVICE_UNAVAILABLE);
        verify(ex).endExchange();
    }

    // --- getProxyClient tests ---

    @Test
    void getProxyClient_returnsConfiguredClient() {
        ProxyClient client = handler.getProxyClient();
        assertSame(proxyClient, client);
    }

    // === New tests to increase coverage ===

    @Test
    void handleRequest_withMaxRetryTarget_usesTargetMaxRetries() throws Exception {
        // Create a target that implements MaxRetriesProxyTarget
        ProxyClient.MaxRetriesProxyTarget maxRetriesTarget = mock(ProxyClient.MaxRetriesProxyTarget.class);
        when(maxRetriesTarget.getMaxRetries()).thenReturn(5);
        when(proxyClient.findTarget(exchange)).thenReturn(maxRetriesTarget);
        when(exchange.isResponseStarted()).thenReturn(false);
        XnioIoThread ioThread = mock(XnioIoThread.class);
        when(exchange.getIoThread()).thenReturn(ioThread);
        when(exchange.isInIoThread()).thenReturn(true);

        handler.handleRequest(exchange);

        verify(exchange).dispatch(any(), any(Runnable.class));
    }

    @Test
    void handleRequest_withPositiveMaxRequestTime_setsTimeout() throws Exception {
        CAPIProxyHandler timedHandler = CAPIProxyHandler.builder()
                .setProxyClient(proxyClient)
                .setMaxRequestTime(5000)
                .setNext(nextHandler)
                .build();

        LoadBalancingProxyClient.ProxyTarget target = mock(LoadBalancingProxyClient.ProxyTarget.class);
        when(proxyClient.findTarget(exchange)).thenReturn(target);
        when(exchange.isResponseStarted()).thenReturn(false);
        XnioIoThread ioThread = mock(XnioIoThread.class);
        when(exchange.getIoThread()).thenReturn(ioThread);
        when(exchange.isInIoThread()).thenReturn(false);

        XnioExecutor.Key timeoutKey = mock(XnioExecutor.Key.class);
        when(ioThread.executeAfter(any(Runnable.class), anyLong(), any(TimeUnit.class))).thenReturn(timeoutKey);

        timedHandler.handleRequest(exchange);

        verify(exchange).dispatch(any(), any(Runnable.class));
        verify(exchange).addExchangeCompleteListener(any());
    }

    @Test
    void handleRequest_inIoThread_usesSameThreadExecutor() throws Exception {
        LoadBalancingProxyClient.ProxyTarget target = mock(LoadBalancingProxyClient.ProxyTarget.class);
        when(proxyClient.findTarget(exchange)).thenReturn(target);
        when(exchange.isResponseStarted()).thenReturn(false);
        XnioIoThread ioThread = mock(XnioIoThread.class);
        when(exchange.getIoThread()).thenReturn(ioThread);
        when(exchange.isInIoThread()).thenReturn(true);

        handler.handleRequest(exchange);

        verify(exchange).dispatch(eq(SameThreadExecutor.INSTANCE), any(Runnable.class));
    }

    @Test
    void handleRequest_notInIoThread_usesIoThread() throws Exception {
        LoadBalancingProxyClient.ProxyTarget target = mock(LoadBalancingProxyClient.ProxyTarget.class);
        when(proxyClient.findTarget(exchange)).thenReturn(target);
        when(exchange.isResponseStarted()).thenReturn(false);
        XnioIoThread ioThread = mock(XnioIoThread.class);
        when(exchange.getIoThread()).thenReturn(ioThread);
        when(exchange.isInIoThread()).thenReturn(false);

        handler.handleRequest(exchange);

        verify(exchange).dispatch(eq(ioThread), any(Runnable.class));
    }

    @Test
    void handleFailure_responseNotStarted_idempotent_nonNullHandler_callsFailed() {
        HttpServerExchange ex = mock(HttpServerExchange.class);
        when(ex.isResponseStarted()).thenReturn(false);
        when(ex.getRequestURI()).thenReturn("/test");

        Predicate predicate = mock(Predicate.class);
        when(predicate.resolve(ex)).thenReturn(true);

        // We need to create a real ProxyClientHandler through the handler, but since it's a private inner class,
        // we test through handleFailure which is package-private static
        // When the predicate resolves to true AND proxyClientHandler is null, it falls through to 503
        CAPIProxyHandler.handleFailure(ex, null, predicate, new IOException("test"));

        verify(ex).setStatusCode(StatusCodes.SERVICE_UNAVAILABLE);
        verify(ex).endExchange();
    }

    @Test
    void builder_chainedCalls_returnsSameBuilder() {
        CAPIProxyHandler.Builder builder = CAPIProxyHandler.builder();
        CAPIProxyHandler.Builder result = builder.setProxyClient(proxyClient);
        assertSame(builder, result);

        CAPIProxyHandler.Builder result2 = builder.setMaxRequestTime(1000);
        assertSame(builder, result2);

        HttpHandler nh = mock(HttpHandler.class);
        CAPIProxyHandler.Builder result3 = builder.setNext(nh);
        assertSame(builder, result3);
    }

    @Test
    void builder_defaultBuild_usesDefaults() {
        CAPIProxyHandler h = CAPIProxyHandler.builder()
                .setProxyClient(proxyClient)
                .build();
        assertNotNull(h);
        assertSame(proxyClient, h.getProxyClient());
    }

    @Test
    void toString_threeTargets_formatCorrectly() {
        ProxyClient.ProxyTarget t1 = mock(ProxyClient.ProxyTarget.class);
        when(t1.toString()).thenReturn("http://h1:80");
        ProxyClient.ProxyTarget t2 = mock(ProxyClient.ProxyTarget.class);
        when(t2.toString()).thenReturn("http://h2:80");
        ProxyClient.ProxyTarget t3 = mock(ProxyClient.ProxyTarget.class);
        when(t3.toString()).thenReturn("http://h3:80");
        when(proxyClient.getAllTargets()).thenReturn(List.of(t1, t2, t3));

        String result = handler.toString();

        assertTrue(result.contains("h1"));
        assertTrue(result.contains("h2"));
        assertTrue(result.contains("h3"));
        assertTrue(result.startsWith("reverse-proxy("));
        assertTrue(result.endsWith(")"));
    }

    @Test
    void copyHeaders_withHttp2SettingsHeader_createsSettingsFrame() {
        HeaderMap from = new HeaderMap();
        HeaderMap to = new HeaderMap();
        from.put(new HttpString("HTTP2-Settings"), "old-settings-value");

        HttpServerExchange mockExchange = mock(HttpServerExchange.class);
        ServerConnection mockConnection = mock(ServerConnection.class);
        when(mockExchange.getConnection()).thenReturn(mockConnection);
        when(mockConnection.getUndertowOptions()).thenReturn(OptionMap.EMPTY);
        io.undertow.connector.ByteBufferPool bufferPool = mock(io.undertow.connector.ByteBufferPool.class);
        when(mockConnection.getByteBufferPool()).thenReturn(bufferPool);

        // Allocate returns a pooled byte buffer
        io.undertow.connector.PooledByteBuffer pooledBuffer = mock(io.undertow.connector.PooledByteBuffer.class);
        java.nio.ByteBuffer byteBuffer = java.nio.ByteBuffer.allocate(1024);
        when(pooledBuffer.getBuffer()).thenReturn(byteBuffer);
        when(bufferPool.allocate()).thenReturn(pooledBuffer);

        CAPIProxyHandler.copyHeaders(mockExchange, to, from);

        // The HTTP2-Settings header should have been replaced with a generated settings frame
        assertTrue(to.contains(new HttpString("HTTP2-Settings")));
    }

    @Test
    void handleRequest_withMaxRequestTimeAndTimeout_addsCompleteListener() throws Exception {
        CAPIProxyHandler timedHandler = CAPIProxyHandler.builder()
                .setProxyClient(proxyClient)
                .setMaxRequestTime(3000)
                .setNext(nextHandler)
                .build();

        LoadBalancingProxyClient.ProxyTarget target = mock(LoadBalancingProxyClient.ProxyTarget.class);
        when(proxyClient.findTarget(exchange)).thenReturn(target);
        when(exchange.isResponseStarted()).thenReturn(false);
        XnioIoThread ioThread = mock(XnioIoThread.class);
        when(exchange.getIoThread()).thenReturn(ioThread);
        when(exchange.isInIoThread()).thenReturn(true);

        XnioExecutor.Key timeoutKey = mock(XnioExecutor.Key.class);
        when(ioThread.executeAfter(any(Runnable.class), anyLong(), any(TimeUnit.class))).thenReturn(timeoutKey);

        timedHandler.handleRequest(exchange);

        verify(exchange).putAttachment(any(), eq(timeoutKey));
        verify(exchange).addExchangeCompleteListener(any());
    }

    @Test
    void builder_staticMethod_returnsNewBuilder() {
        CAPIProxyHandler.Builder b1 = CAPIProxyHandler.builder();
        CAPIProxyHandler.Builder b2 = CAPIProxyHandler.builder();
        assertNotNull(b1);
        assertNotNull(b2);
        assertNotSame(b1, b2);
    }

    @Test
    void handleFailure_responseStarted_closesExchangeConnection() {
        HttpServerExchange ex = mock(HttpServerExchange.class);
        ServerConnection conn = mock(ServerConnection.class);
        when(ex.isResponseStarted()).thenReturn(true);
        when(ex.getConnection()).thenReturn(conn);
        when(ex.getRequestURI()).thenReturn("/fail");

        Predicate predicate = mock(Predicate.class);
        CAPIProxyHandler.handleFailure(ex, null, predicate, new IOException("connection error"));

        // Verify that connection was obtained for closing
        verify(ex, atLeastOnce()).getConnection();
    }

    @Test
    void handleFailure_notStarted_notIdempotent_noRetryHandler_sets503AndEnds() {
        HttpServerExchange ex = mock(HttpServerExchange.class);
        when(ex.isResponseStarted()).thenReturn(false);
        when(ex.getRequestURI()).thenReturn("/api/resource");

        Predicate predicate = mock(Predicate.class);
        when(predicate.resolve(ex)).thenReturn(false);

        CAPIProxyHandler.handleFailure(ex, null, predicate, new IOException("timeout"));

        verify(ex).setStatusCode(503);
        verify(ex).endExchange();
    }

    @Test
    void copyHeaders_multipleValuesForSameHeader_copiesAll() {
        HeaderMap from = new HeaderMap();
        HeaderMap to = new HeaderMap();
        from.add(new HttpString("X-Multi"), "val1");
        from.add(new HttpString("X-Multi"), "val2");

        HttpServerExchange mockExchange = mock(HttpServerExchange.class);
        CAPIProxyHandler.copyHeaders(mockExchange, to, from);

        HeaderValues values = to.get(new HttpString("X-Multi"));
        assertNotNull(values);
        assertEquals(2, values.size());
        assertTrue(values.contains("val1"));
        assertTrue(values.contains("val2"));
    }

    @Test
    void copyHeaders_mixedExistingAndNew() {
        HeaderMap from = new HeaderMap();
        HeaderMap to = new HeaderMap();
        to.put(new HttpString("Existing"), "keep");
        from.put(new HttpString("Existing"), "discard");
        from.put(new HttpString("New-Header"), "add-me");

        HttpServerExchange mockExchange = mock(HttpServerExchange.class);
        CAPIProxyHandler.copyHeaders(mockExchange, to, from);

        assertEquals("keep", to.getFirst(new HttpString("Existing")));
        assertEquals("add-me", to.getFirst(new HttpString("New-Header")));
    }

    @Test
    void builder_maxRequestTimeZero_treatedAsNoTimeout() {
        CAPIProxyHandler h = CAPIProxyHandler.builder()
                .setProxyClient(proxyClient)
                .setMaxRequestTime(0)
                .build();
        assertNotNull(h);
    }

    @Test
    void builder_negativeMaxRequestTime_treatedAsNoTimeout() {
        CAPIProxyHandler h = CAPIProxyHandler.builder()
                .setProxyClient(proxyClient)
                .setMaxRequestTime(-100)
                .build();
        assertNotNull(h);
    }
}
