package io.surisoft.capi.processor;

import io.surisoft.capi.exception.AuthorizationException;
import io.surisoft.capi.utils.Constants;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.HttpHostConnectException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.NoHttpResponseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HttpErrorProcessorTest {

    private HttpErrorProcessor processor;

    @Mock
    private Exchange exchange;
    @Mock
    private Message message;

    @BeforeEach
    void setUp() {
        processor = new HttpErrorProcessor();
        lenient().when(exchange.getIn()).thenReturn(message);
        lenient().when(message.getHeader(Exchange.HTTP_URI)).thenReturn("http://example.com/api");
        lenient().when(message.getHeader(Exchange.HTTP_URL)).thenReturn("http://example.com/api?param=1");
    }

    @Test
    void process_sslHandshakeException_sets502() {
        when(exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class))
                .thenReturn(new SSLHandshakeException("cert error"));

        processor.process(exchange);

        // SSLHandshakeException extends SSLException, so both branches match — header is set twice
        verify(message, atLeast(1)).setHeader(Constants.REASON_CODE_HEADER, 502);
        verify(message, atLeast(1)).setHeader(Constants.REASON_MESSAGE_HEADER, Constants.ERROR_SERVICE_CERTIFICATE);
    }

    @Test
    void process_unknownHostException_sets502() {
        when(exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class))
                .thenReturn(new UnknownHostException("unknown.host"));

        processor.process(exchange);

        verify(message).setHeader(Constants.REASON_CODE_HEADER, 502);
        verify(message).setHeader(Constants.REASON_MESSAGE_HEADER, Constants.ERROR_SERVICE_HOST);
    }

    @Test
    void process_socketTimeoutException_sets502() {
        when(exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class))
                .thenReturn(new SocketTimeoutException("timeout"));

        processor.process(exchange);

        verify(message).setHeader(Constants.REASON_CODE_HEADER, 502);
        verify(message).setHeader(Constants.REASON_MESSAGE_HEADER, Constants.ERROR_REMOTE_SERVER_TIMEOUT);
    }

    @Test
    void process_authorizationException_sets401() {
        when(exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class))
                .thenReturn(new AuthorizationException("not authorized"));

        processor.process(exchange);

        verify(message).setHeader(Constants.REASON_CODE_HEADER, 401);
        verify(message).setHeader(Constants.REASON_MESSAGE_HEADER, "not authorized");
    }

    @Test
    void process_httpHostConnectException_sets502() {
        when(exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class))
                .thenReturn(new HttpHostConnectException("connection refused"));

        processor.process(exchange);

        verify(message).setHeader(Constants.REASON_CODE_HEADER, 502);
        verify(message).setHeader(Constants.REASON_MESSAGE_HEADER, Constants.ERROR_NO_SERVER_AVAILABLE);
    }

    @Test
    void process_noHttpResponseException_sets502() {
        when(exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class))
                .thenReturn(new NoHttpResponseException("no response"));

        processor.process(exchange);

        verify(message).setHeader(Constants.REASON_CODE_HEADER, 502);
        verify(message).setHeader(Constants.REASON_MESSAGE_HEADER, Constants.ERROR_REMOTE_NOT_RESPONDING);
    }

    @Test
    void process_connectTimeoutException_sets502() {
        when(exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class))
                .thenReturn(new ConnectTimeoutException("connect timed out"));

        processor.process(exchange);

        verify(message).setHeader(Constants.REASON_CODE_HEADER, 502);
        verify(message).setHeader(Constants.REASON_MESSAGE_HEADER, Constants.ERROR_REMOTE_SERVER_TIMEOUT);
    }

    @Test
    void process_sslException_sets502() {
        when(exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class))
                .thenReturn(new SSLException("ssl error"));

        processor.process(exchange);

        verify(message).setHeader(Constants.REASON_CODE_HEADER, 502);
        verify(message).setHeader(Constants.REASON_MESSAGE_HEADER, Constants.ERROR_SERVICE_CERTIFICATE);
    }

    @Test
    void process_socketException_sets502() {
        when(exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class))
                .thenReturn(new SocketException("connection reset"));

        processor.process(exchange);

        // SocketException doesn't match any specific branch, so no reason headers set
        // but URI/URL headers are always set
        verify(message).setHeader(Constants.CAPI_URI_IN_ERROR, "http://example.com/api");
        verify(message).setHeader(Constants.CAPI_URL_IN_ERROR, "http://example.com/api?param=1");
    }

    @Test
    void process_setsErrorUriHeaders() {
        when(exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class))
                .thenReturn(new UnknownHostException("test"));

        processor.process(exchange);

        verify(message).setHeader(Constants.CAPI_URI_IN_ERROR, "http://example.com/api");
        verify(message).setHeader(Constants.CAPI_URL_IN_ERROR, "http://example.com/api?param=1");
    }
}