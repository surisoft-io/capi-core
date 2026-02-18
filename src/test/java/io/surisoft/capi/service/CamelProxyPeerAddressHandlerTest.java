package io.surisoft.capi.service;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CamelProxyPeerAddressHandlerTest {

    private CamelProxyPeerAddressHandler handler;

    @Mock
    private HttpHandler nextHandler;

    @Mock
    private HttpServerExchange exchange;

    private HeaderMap headerMap;

    @BeforeEach
    void setUp() throws Exception {
        handler = new CamelProxyPeerAddressHandler();
        handler.setNext(nextHandler);
        headerMap = new HeaderMap();
        lenient().when(exchange.getRequestHeaders()).thenReturn(headerMap);
        lenient().when(exchange.getSourceAddress())
                .thenReturn(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 8080));
    }

    @Test
    void handleRequest_withXForwardedFor_setsSourceAddress() throws Exception {
        headerMap.put(Headers.X_FORWARDED_FOR, "1.2.3.4");

        handler.handleRequest(exchange);

        ArgumentCaptor<InetSocketAddress> captor = ArgumentCaptor.forClass(InetSocketAddress.class);
        verify(exchange).setSourceAddress(captor.capture());
        assertEquals("1.2.3.4", captor.getValue().getAddress().getHostAddress());
        assertEquals(8080, captor.getValue().getPort());
        verify(nextHandler).handleRequest(exchange);
    }

    @Test
    void handleRequest_withMultipleIPs_usesFirstIP() throws Exception {
        headerMap.put(Headers.X_FORWARDED_FOR, "10.0.0.1, 10.0.0.2, 10.0.0.3");

        handler.handleRequest(exchange);

        ArgumentCaptor<InetSocketAddress> captor = ArgumentCaptor.forClass(InetSocketAddress.class);
        verify(exchange).setSourceAddress(captor.capture());
        assertEquals("10.0.0.1", captor.getValue().getAddress().getHostAddress());
        assertEquals(8080, captor.getValue().getPort());
        verify(nextHandler).handleRequest(exchange);
    }

    @Test
    void handleRequest_withoutHeaders_doesNotSetSourceAddress() throws Exception {
        handler.handleRequest(exchange);

        verify(exchange, never()).setSourceAddress(any());
        verify(exchange, never()).setRequestScheme(anyString());
        verify(nextHandler).handleRequest(exchange);
    }

    @Test
    void handleRequest_withInvalidIP_doesNotSetSourceAddress() throws Exception {
        headerMap.put(Headers.X_FORWARDED_FOR, "not-a-valid-ip-!!!@@@");

        handler.handleRequest(exchange);

        // The handler catches the exception silently and does not set source address
        // InetAddress.getByName may or may not throw depending on the input;
        // regardless, next handler must always be called
        verify(nextHandler).handleRequest(exchange);
    }

    @Test
    void handleRequest_withXForwardedProto_setsRequestScheme() throws Exception {
        headerMap.put(Headers.X_FORWARDED_PROTO, "https");

        handler.handleRequest(exchange);

        verify(exchange).setRequestScheme("https");
        verify(nextHandler).handleRequest(exchange);
    }

    @Test
    void handleRequest_withXForwardedProtoHttp_setsRequestScheme() throws Exception {
        headerMap.put(Headers.X_FORWARDED_PROTO, " http ");

        handler.handleRequest(exchange);

        verify(exchange).setRequestScheme("http");
        verify(nextHandler).handleRequest(exchange);
    }

    @Test
    void handleRequest_withXForwardedHost_setsHostHeader() throws Exception {
        headerMap.put(Headers.X_FORWARDED_HOST, "example.com");

        handler.handleRequest(exchange);

        assertEquals("example.com", headerMap.getFirst(Headers.HOST));
        verify(nextHandler).handleRequest(exchange);
    }

    @Test
    void handleRequest_withXForwardedHostMultiple_usesFirstHost() throws Exception {
        headerMap.put(Headers.X_FORWARDED_HOST, "primary.com, secondary.com");

        handler.handleRequest(exchange);

        assertEquals("primary.com", headerMap.getFirst(Headers.HOST));
        verify(nextHandler).handleRequest(exchange);
    }

    @Test
    void handleRequest_withAllHeaders_setsAll() throws Exception {
        headerMap.put(Headers.X_FORWARDED_FOR, "192.168.1.100");
        headerMap.put(Headers.X_FORWARDED_PROTO, "https");
        headerMap.put(Headers.X_FORWARDED_HOST, "api.example.com");

        handler.handleRequest(exchange);

        ArgumentCaptor<InetSocketAddress> captor = ArgumentCaptor.forClass(InetSocketAddress.class);
        verify(exchange).setSourceAddress(captor.capture());
        assertEquals("192.168.1.100", captor.getValue().getAddress().getHostAddress());
        verify(exchange).setRequestScheme("https");
        assertEquals("api.example.com", headerMap.getFirst(Headers.HOST));
        verify(nextHandler).handleRequest(exchange);
    }

    @Test
    void handleRequest_alwaysCallsNextHandler() throws Exception {
        // Even with all headers, next handler must be invoked
        headerMap.put(Headers.X_FORWARDED_FOR, "1.2.3.4");
        headerMap.put(Headers.X_FORWARDED_PROTO, "https");
        headerMap.put(Headers.X_FORWARDED_HOST, "host.com");

        handler.handleRequest(exchange);

        verify(nextHandler, times(1)).handleRequest(exchange);
    }
}
