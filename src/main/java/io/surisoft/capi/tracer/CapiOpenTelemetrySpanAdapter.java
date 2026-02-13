package io.surisoft.capi.tracer;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageBuilder;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes;
import org.apache.camel.tracing.SpanAdapter;
import org.apache.camel.tracing.Tag;

public class CapiOpenTelemetrySpanAdapter implements SpanAdapter {

    private static final String DEFAULT_EVENT_NAME = "log";
    private static final Set<String> SUPPRESSED_TAGS = Set.of("camel.uri", "url.path", "url.query", "server.address");
    private static final Map<Tag, String> TAG_MAP = new EnumMap<>(Tag.class);

    static {
        TAG_MAP.put(Tag.COMPONENT, "component");
        TAG_MAP.put(Tag.DB_TYPE, SemanticAttributes.DB_SYSTEM.getKey());
        TAG_MAP.put(Tag.DB_STATEMENT, SemanticAttributes.DB_STATEMENT.getKey());
        TAG_MAP.put(Tag.DB_INSTANCE, SemanticAttributes.DB_NAME.getKey());
        TAG_MAP.put(Tag.HTTP_METHOD, SemanticAttributes.HTTP_METHOD.getKey());
        TAG_MAP.put(Tag.HTTP_STATUS, SemanticAttributes.HTTP_STATUS_CODE.getKey());
        TAG_MAP.put(Tag.HTTP_URL, SemanticAttributes.HTTP_URL.getKey());
        TAG_MAP.put(Tag.MESSAGE_BUS_DESTINATION, "message_bus.destination");
    }

    private Baggage baggage;
    private Span span;

    CapiOpenTelemetrySpanAdapter(Span span) {
        this.span = span;
    }

    CapiOpenTelemetrySpanAdapter(Span span, Baggage baggage) {
        this.span = span;
        this.baggage = baggage;
    }

    io.opentelemetry.api.trace.Span getOpenTelemetrySpan() {
        return this.span;
    }

    @Override
    public void setComponent(String component) {
        this.span.setAttribute("component", component);
    }

    @Override
    public void setError(boolean error) {
        this.span.setAttribute("error", error);
        this.span.setStatus(error ? StatusCode.ERROR : StatusCode.OK);
    }

    @Override
    public void setTag(Tag key, String value) {
        String attribute = TAG_MAP.getOrDefault(key, key.getAttribute());
        if (SUPPRESSED_TAGS.contains(attribute) || SUPPRESSED_TAGS.contains(key.getAttribute())) {
            return;
        }
        this.span.setAttribute(attribute, value);
        if (!attribute.equals(key.getAttribute())) {
            this.span.setAttribute(key.getAttribute(), value);
        }
    }

    @Override
    public void setTag(Tag key, Number value) {
        String attribute = TAG_MAP.getOrDefault(key, key.getAttribute());
        if (SUPPRESSED_TAGS.contains(attribute) || SUPPRESSED_TAGS.contains(key.getAttribute())) {
            return;
        }
        this.span.setAttribute(attribute, value.intValue());
    }

    @Override
    public void setTag(String key, String value) {
        if (SUPPRESSED_TAGS.contains(key)) {
            return;
        }
        this.span.setAttribute(key, value);
    }

    @Override
    public void setTag(String key, Number value) {
        if (SUPPRESSED_TAGS.contains(key)) {
            return;
        }
        this.span.setAttribute(key, value.intValue());
    }

    @Override
    public void setTag(String key, Boolean value) {
        if (SUPPRESSED_TAGS.contains(key)) {
            return;
        }
        this.span.setAttribute(key, value);
    }

    @Override
    public void log(Map<String, String> fields) {
        span.addEvent(getEventNameFromFields(fields), convertToAttributes(fields));
    }

    @Override
    public String traceId() {
        return span.getSpanContext().getTraceId();
    }

    @Override
    public String spanId() {
        return span.getSpanContext().getSpanId();
    }

    @Override
    public AutoCloseable makeCurrent() {
        return span.makeCurrent();
    }

    String getEventNameFromFields(Map<String, ?> fields) {
        Object eventValue = fields == null ? null : fields.get("event");
        if (eventValue != null) {
            return eventValue.toString();
        }

        return DEFAULT_EVENT_NAME;
    }

    Attributes convertToAttributes(Map<String, ?> fields) {
        AttributesBuilder attributesBuilder = Attributes.builder();

        for (Map.Entry<String, ?> entry : fields.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (value instanceof Byte
                    || value instanceof Short
                    || value instanceof Integer
                    || value instanceof Long) {
                attributesBuilder.put(key, ((Number) value).longValue());
            } else if (value instanceof Float || value instanceof Double) {
                attributesBuilder.put(key, ((Number) value).doubleValue());
            } else if (value instanceof Boolean) {
                attributesBuilder.put(key, (Boolean) value);
            } else {
                attributesBuilder.put(key, value.toString());
            }
        }
        return attributesBuilder.build();
    }

    public Baggage getBaggage() {
        return this.baggage;
    }

    public void setBaggage(Baggage baggage) {
        this.baggage = baggage;
    }

    public void setCorrelationContextItem(String key, String value) {
        BaggageBuilder builder = Baggage.builder();
        if (baggage != null) {
            builder = Baggage.current().toBuilder();
        }
        baggage = builder.put(key, value).build();
    }

    public String getContextPropagationItem(String key) {
        if (baggage != null) {
            return baggage.getEntryValue(key);
        }
        return null;
    }

    @Override
    public String toString() {
        return "CapiOpenTelemetrySpanAdapter [baggage=" + baggage + ", span=" + span + "]";
    }
}