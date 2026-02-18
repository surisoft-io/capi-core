package io.surisoft.capi.tracer;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageBuilder;
import io.opentelemetry.api.baggage.BaggageEntry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import org.apache.camel.tracing.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CapiOpenTelemetrySpanAdapterTest {

    @Mock
    private Span span;
    @Mock
    private SpanContext spanContext;
    @Mock
    private Baggage baggage;
    @Mock
    private Scope scope;

    // ---------------------------------------------------------------
    // Constructor tests
    // ---------------------------------------------------------------

    @Test
    void constructorWithSpanOnly_setsSpanAndBaggageIsNull() {
        CapiOpenTelemetrySpanAdapter adapter = new CapiOpenTelemetrySpanAdapter(span);
        assertSame(span, adapter.getOpenTelemetrySpan());
        assertNull(adapter.getBaggage());
    }

    @Test
    void constructorWithSpanAndBaggage_setsBoth() {
        CapiOpenTelemetrySpanAdapter adapter = new CapiOpenTelemetrySpanAdapter(span, baggage);
        assertSame(span, adapter.getOpenTelemetrySpan());
        assertSame(baggage, adapter.getBaggage());
    }

    // ---------------------------------------------------------------
    // setComponent
    // ---------------------------------------------------------------

    @Test
    void setComponent_delegatesToSpanSetAttribute() {
        lenient().when(span.setAttribute(anyString(), anyString())).thenReturn(span);

        CapiOpenTelemetrySpanAdapter adapter = new CapiOpenTelemetrySpanAdapter(span);
        adapter.setComponent("http");

        verify(span).setAttribute("component", "http");
    }

    // ---------------------------------------------------------------
    // setError
    // ---------------------------------------------------------------

    @Test
    void setError_true_setsErrorStatusAndAttribute() {
        lenient().when(span.setAttribute(anyString(), anyBoolean())).thenReturn(span);
        lenient().when(span.setStatus(any(StatusCode.class))).thenReturn(span);

        CapiOpenTelemetrySpanAdapter adapter = new CapiOpenTelemetrySpanAdapter(span);
        adapter.setError(true);

        verify(span).setAttribute("error", true);
        verify(span).setStatus(StatusCode.ERROR);
    }

    @Test
    void setError_false_setsOkStatusAndAttribute() {
        lenient().when(span.setAttribute(anyString(), anyBoolean())).thenReturn(span);
        lenient().when(span.setStatus(any(StatusCode.class))).thenReturn(span);

        CapiOpenTelemetrySpanAdapter adapter = new CapiOpenTelemetrySpanAdapter(span);
        adapter.setError(false);

        verify(span).setAttribute("error", false);
        verify(span).setStatus(StatusCode.OK);
    }

    // ---------------------------------------------------------------
    // setTag(Tag, String) - regular tag
    // ---------------------------------------------------------------

    @Test
    void setTagEnumString_regularTag_delegatesToSpan() {
        lenient().when(span.setAttribute(anyString(), anyString())).thenReturn(span);

        CapiOpenTelemetrySpanAdapter adapter = new CapiOpenTelemetrySpanAdapter(span);
        adapter.setTag(Tag.COMPONENT, "camel-http");

        // Tag.COMPONENT maps to "component" in TAG_MAP
        verify(span).setAttribute("component", "camel-http");
    }

    @Test
    void setTagEnumString_httpMethod_setsMappedAndOriginalAttribute() {
        lenient().when(span.setAttribute(anyString(), anyString())).thenReturn(span);

        CapiOpenTelemetrySpanAdapter adapter = new CapiOpenTelemetrySpanAdapter(span);
        adapter.setTag(Tag.HTTP_METHOD, "GET");

        // TAG_MAP maps HTTP_METHOD to SemanticAttributes.HTTP_METHOD key
        // It also sets the original key if different from the mapped key
        verify(span, atLeastOnce()).setAttribute(anyString(), eq("GET"));
    }

    // ---------------------------------------------------------------
    // setTag(Tag, Number)
    // ---------------------------------------------------------------

    @Test
    void setTagEnumNumber_regularTag_delegatesToSpan() {
        lenient().when(span.setAttribute(anyString(), anyLong())).thenReturn(span);

        CapiOpenTelemetrySpanAdapter adapter = new CapiOpenTelemetrySpanAdapter(span);
        adapter.setTag(Tag.HTTP_STATUS, 200);

        verify(span, atLeastOnce()).setAttribute(anyString(), eq(200L));
    }

    // ---------------------------------------------------------------
    // setTag(String, String) - suppressed tags
    // ---------------------------------------------------------------

    @Test
    void setTagStringString_suppressedTag_doesNotCallSpan() {
        CapiOpenTelemetrySpanAdapter adapter = new CapiOpenTelemetrySpanAdapter(span);
        adapter.setTag("camel.uri", "http://example.com");

        verify(span, never()).setAttribute(eq("camel.uri"), anyString());
    }

    @Test
    void setTagStringString_suppressedUrlPath_doesNotCallSpan() {
        CapiOpenTelemetrySpanAdapter adapter = new CapiOpenTelemetrySpanAdapter(span);
        adapter.setTag("url.path", "/some/path");

        verify(span, never()).setAttribute(eq("url.path"), anyString());
    }

    @Test
    void setTagStringString_suppressedUrlQuery_doesNotCallSpan() {
        CapiOpenTelemetrySpanAdapter adapter = new CapiOpenTelemetrySpanAdapter(span);
        adapter.setTag("url.query", "param=value");

        verify(span, never()).setAttribute(eq("url.query"), anyString());
    }

    @Test
    void setTagStringString_suppressedServerAddress_doesNotCallSpan() {
        CapiOpenTelemetrySpanAdapter adapter = new CapiOpenTelemetrySpanAdapter(span);
        adapter.setTag("server.address", "localhost");

        verify(span, never()).setAttribute(eq("server.address"), anyString());
    }

    @Test
    void setTagStringString_regularTag_delegatesToSpan() {
        lenient().when(span.setAttribute(anyString(), anyString())).thenReturn(span);

        CapiOpenTelemetrySpanAdapter adapter = new CapiOpenTelemetrySpanAdapter(span);
        adapter.setTag("my.custom.tag", "my-value");

        verify(span).setAttribute("my.custom.tag", "my-value");
    }

    // ---------------------------------------------------------------
    // setTag(String, Number) - suppressed and regular
    // ---------------------------------------------------------------

    @Test
    void setTagStringNumber_suppressedTag_doesNotCallSpan() {
        CapiOpenTelemetrySpanAdapter adapter = new CapiOpenTelemetrySpanAdapter(span);
        adapter.setTag("camel.uri", 42);

        verify(span, never()).setAttribute(eq("camel.uri"), anyLong());
    }

    @Test
    void setTagStringNumber_regularTag_delegatesToSpan() {
        lenient().when(span.setAttribute(anyString(), anyLong())).thenReturn(span);

        CapiOpenTelemetrySpanAdapter adapter = new CapiOpenTelemetrySpanAdapter(span);
        adapter.setTag("my.number.tag", 99);

        verify(span).setAttribute("my.number.tag", 99);
    }

    // ---------------------------------------------------------------
    // setTag(String, Boolean) - suppressed and regular
    // ---------------------------------------------------------------

    @Test
    void setTagStringBoolean_suppressedTag_doesNotCallSpan() {
        CapiOpenTelemetrySpanAdapter adapter = new CapiOpenTelemetrySpanAdapter(span);
        adapter.setTag("camel.uri", Boolean.TRUE);

        verify(span, never()).setAttribute(eq("camel.uri"), anyBoolean());
    }

    @Test
    void setTagStringBoolean_regularTag_delegatesToSpan() {
        lenient().when(span.setAttribute(anyString(), anyBoolean())).thenReturn(span);

        CapiOpenTelemetrySpanAdapter adapter = new CapiOpenTelemetrySpanAdapter(span);
        adapter.setTag("my.bool.tag", Boolean.TRUE);

        verify(span).setAttribute("my.bool.tag", true);
    }

    // ---------------------------------------------------------------
    // log
    // ---------------------------------------------------------------

    @Test
    void log_withEventKey_usesEventAsName() {
        lenient().when(span.addEvent(anyString(), any(Attributes.class))).thenReturn(span);

        CapiOpenTelemetrySpanAdapter adapter = new CapiOpenTelemetrySpanAdapter(span);
        Map<String, String> fields = new HashMap<>();
        fields.put("event", "my-event");
        fields.put("detail", "some detail");
        adapter.log(fields);

        verify(span).addEvent(eq("my-event"), any(Attributes.class));
    }

    @Test
    void log_withoutEventKey_usesDefaultLogName() {
        lenient().when(span.addEvent(anyString(), any(Attributes.class))).thenReturn(span);

        CapiOpenTelemetrySpanAdapter adapter = new CapiOpenTelemetrySpanAdapter(span);
        Map<String, String> fields = new HashMap<>();
        fields.put("detail", "some detail");
        adapter.log(fields);

        verify(span).addEvent(eq("log"), any(Attributes.class));
    }

    // ---------------------------------------------------------------
    // traceId / spanId
    // ---------------------------------------------------------------

    @Test
    void traceId_returnsSpanContextTraceId() {
        when(span.getSpanContext()).thenReturn(spanContext);
        when(spanContext.getTraceId()).thenReturn("abc123trace");

        CapiOpenTelemetrySpanAdapter adapter = new CapiOpenTelemetrySpanAdapter(span);
        assertEquals("abc123trace", adapter.traceId());
    }

    @Test
    void spanId_returnsSpanContextSpanId() {
        when(span.getSpanContext()).thenReturn(spanContext);
        when(spanContext.getSpanId()).thenReturn("def456span");

        CapiOpenTelemetrySpanAdapter adapter = new CapiOpenTelemetrySpanAdapter(span);
        assertEquals("def456span", adapter.spanId());
    }

    // ---------------------------------------------------------------
    // makeCurrent
    // ---------------------------------------------------------------

    @Test
    void makeCurrent_delegatesToSpan() {
        when(span.makeCurrent()).thenReturn(scope);

        CapiOpenTelemetrySpanAdapter adapter = new CapiOpenTelemetrySpanAdapter(span);
        AutoCloseable result = adapter.makeCurrent();

        assertSame(scope, result);
        verify(span).makeCurrent();
    }

    // ---------------------------------------------------------------
    // getBaggage / setBaggage
    // ---------------------------------------------------------------

    @Test
    void setBaggage_updatesBaggage() {
        CapiOpenTelemetrySpanAdapter adapter = new CapiOpenTelemetrySpanAdapter(span);
        assertNull(adapter.getBaggage());

        adapter.setBaggage(baggage);
        assertSame(baggage, adapter.getBaggage());
    }

    // ---------------------------------------------------------------
    // getContextPropagationItem
    // ---------------------------------------------------------------

    @Test
    void getContextPropagationItem_withBaggage_returnsEntryValue() {
        when(baggage.getEntryValue("my-key")).thenReturn("my-value");

        CapiOpenTelemetrySpanAdapter adapter = new CapiOpenTelemetrySpanAdapter(span, baggage);
        assertEquals("my-value", adapter.getContextPropagationItem("my-key"));
    }

    @Test
    void getContextPropagationItem_withoutBaggage_returnsNull() {
        CapiOpenTelemetrySpanAdapter adapter = new CapiOpenTelemetrySpanAdapter(span);
        assertNull(adapter.getContextPropagationItem("my-key"));
    }

    // ---------------------------------------------------------------
    // setCorrelationContextItem
    // ---------------------------------------------------------------

    @Test
    void setCorrelationContextItem_withNullBaggage_createsBaggage() {
        CapiOpenTelemetrySpanAdapter adapter = new CapiOpenTelemetrySpanAdapter(span);
        assertNull(adapter.getBaggage());

        adapter.setCorrelationContextItem("correlation-key", "correlation-value");

        assertNotNull(adapter.getBaggage());
    }

    @Test
    void setCorrelationContextItem_withExistingBaggage_updatesAndRebuildsBaggage() {
        CapiOpenTelemetrySpanAdapter adapter = new CapiOpenTelemetrySpanAdapter(span, baggage);
        adapter.setCorrelationContextItem("key1", "value1");

        Baggage result = adapter.getBaggage();
        assertNotNull(result);
    }

    // ---------------------------------------------------------------
    // getEventNameFromFields (package-private helper)
    // ---------------------------------------------------------------

    @Test
    void getEventNameFromFields_nullFields_returnsDefault() {
        CapiOpenTelemetrySpanAdapter adapter = new CapiOpenTelemetrySpanAdapter(span);
        assertEquals("log", adapter.getEventNameFromFields(null));
    }

    @Test
    void getEventNameFromFields_emptyMap_returnsDefault() {
        CapiOpenTelemetrySpanAdapter adapter = new CapiOpenTelemetrySpanAdapter(span);
        assertEquals("log", adapter.getEventNameFromFields(Map.of()));
    }

    @Test
    void getEventNameFromFields_withEventKey_returnsEventValue() {
        CapiOpenTelemetrySpanAdapter adapter = new CapiOpenTelemetrySpanAdapter(span);
        Map<String, Object> fields = new HashMap<>();
        fields.put("event", "custom-event");
        assertEquals("custom-event", adapter.getEventNameFromFields(fields));
    }

    // ---------------------------------------------------------------
    // convertToAttributes (package-private helper)
    // ---------------------------------------------------------------

    @Test
    void convertToAttributes_variousTypes() {
        CapiOpenTelemetrySpanAdapter adapter = new CapiOpenTelemetrySpanAdapter(span);
        Map<String, Object> fields = new HashMap<>();
        fields.put("string-key", "string-value");
        fields.put("int-key", 42);
        fields.put("long-key", 100L);
        fields.put("double-key", 3.14);
        fields.put("bool-key", true);
        fields.put("null-key", null);

        Attributes attrs = adapter.convertToAttributes(fields);
        assertNotNull(attrs);
        // null-key should be skipped, so we should have 5 attributes
        assertEquals(5, attrs.size());
    }

    // ---------------------------------------------------------------
    // toString
    // ---------------------------------------------------------------

    @Test
    void toString_containsClassInfo() {
        CapiOpenTelemetrySpanAdapter adapter = new CapiOpenTelemetrySpanAdapter(span, baggage);
        String result = adapter.toString();
        assertTrue(result.contains("CapiOpenTelemetrySpanAdapter"));
    }
}
