package io.surisoft.capi.processor;

import io.surisoft.capi.utils.Constants;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContentTypeValidatorTest {

    private ContentTypeValidator validator;

    @Mock
    private Exchange exchange;
    @Mock
    private Message message;

    @BeforeEach
    void setUp() {
        validator = new ContentTypeValidator();
        when(exchange.getIn()).thenReturn(message);
    }

    @Test
    void process_validContentType_noException() throws Exception {
        when(message.getHeader(Constants.CONTENT_TYPE)).thenReturn("application/json");
        when(message.getHeader(Constants.ACCEPT_TYPE)).thenReturn("application/json");

        validator.process(exchange);

        verify(exchange, never()).setException(any());
    }

    @Test
    void process_nullContentType_noException() throws Exception {
        when(message.getHeader(Constants.CONTENT_TYPE)).thenReturn(null);
        when(message.getHeader("content-type")).thenReturn(null);
        when(message.getHeader(Constants.ACCEPT_TYPE)).thenReturn(null);
        when(message.getHeader("accept")).thenReturn(null);

        validator.process(exchange);

        verify(exchange, never()).setException(any());
    }

    @Test
    void process_eventStreamContentType_throwsException() throws Exception {
        when(message.getHeader(Constants.CONTENT_TYPE)).thenReturn("text/event-stream");

        validator.process(exchange);

        verify(exchange).setException(any());
        verify(message).setHeader(eq(Constants.REASON_CODE_HEADER), eq(400));
    }

    @Test
    void process_eventStreamAcceptType_throwsException() throws Exception {
        when(message.getHeader(Constants.CONTENT_TYPE)).thenReturn(null);
        when(message.getHeader("content-type")).thenReturn(null);
        when(message.getHeader(Constants.ACCEPT_TYPE)).thenReturn("text/event-stream");

        validator.process(exchange);

        verify(exchange).setException(any());
    }

    @Test
    void process_eventStreamInLowercaseContentTypeHeader_throwsException() throws Exception {
        when(message.getHeader(Constants.CONTENT_TYPE)).thenReturn(null);
        when(message.getHeader("content-type")).thenReturn("Text/Event-Stream");
        when(message.getHeader(Constants.ACCEPT_TYPE)).thenReturn(null);
        when(message.getHeader("accept")).thenReturn(null);

        validator.process(exchange);

        verify(exchange).setException(any());
    }
}