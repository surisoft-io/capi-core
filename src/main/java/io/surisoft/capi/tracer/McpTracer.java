package io.surisoft.capi.tracer;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.surisoft.capi.utils.Constants;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderValues;
import io.undertow.util.HttpString;

import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenTelemetry tracer for the MCP gateway. Emits spans/attributes per the
 * GenAI semantic conventions (gen_ai.*) plus a few CAPI-prefixed extensions
 * (mcp.session.id, mcp.protocol.version, mcp.attempt, capi.outcome).
 *
 * Intentionally separate from CapiTracer so the high-traffic Rest/Websocket
 * tracing path is not touched.
 */
public class McpTracer {

    private final Tracer tracer;
    private final String capiInstanceName;
    private final boolean enabled;

    public McpTracer(Tracer tracer, String capiInstanceName, boolean enabled) {
        this.tracer = tracer;
        this.capiInstanceName = capiInstanceName;
        this.enabled = enabled && tracer != null;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Span startServerSpan(HttpServerExchange exchange, String operationName) {
        return startServerSpan(exchange, operationName, null);
    }

    public Span startServerSpan(HttpServerExchange exchange, String operationName, String toolName) {
        if (!enabled) return null;
        TextMapPropagator propagator = GlobalOpenTelemetry.getPropagators().getTextMapPropagator();
        Context parent = propagator.extract(Context.current(), exchange, EXCHANGE_GETTER);
        String spanName = toolName != null ? operationName + " " + toolName : operationName;
        Span span = tracer.spanBuilder(spanName)
                .setParent(parent)
                .setSpanKind(SpanKind.SERVER)
                .setAttribute(Constants.GEN_AI_SYSTEM, Constants.GEN_AI_SYSTEM_MCP)
                .setAttribute(Constants.GEN_AI_OPERATION_NAME, operationName)
                .setAttribute("capi-instance", capiInstanceName)
                .startSpan();
        if (toolName != null) {
            span.setAttribute(Constants.GEN_AI_TOOL_NAME, toolName);
        }
        return span;
    }

    public Span startUpstreamSpan(Span parent, String backendUrl, String toolName, int attempt) {
        if (!enabled) return null;
        String name = toolName != null ? "mcp.upstream " + toolName : "mcp.upstream";
        var builder = tracer.spanBuilder(name).setSpanKind(SpanKind.CLIENT);
        if (parent != null) {
            builder = builder.setParent(Context.current().with(parent));
        }
        Span span = builder
                .setAttribute(Constants.GEN_AI_SYSTEM, Constants.GEN_AI_SYSTEM_MCP)
                .setAttribute(Constants.GEN_AI_OPERATION_NAME, Constants.GEN_AI_OPERATION_EXECUTE_TOOL)
                .setAttribute("url.full", backendUrl != null ? backendUrl : "")
                .setAttribute(Constants.MCP_ATTEMPT_ATTR, attempt)
                .startSpan();
        if (toolName != null) {
            span.setAttribute(Constants.GEN_AI_TOOL_NAME, toolName);
        }
        return span;
    }

    public void injectContext(Span span, HttpRequest.Builder requestBuilder) {
        if (!enabled || span == null || requestBuilder == null) return;
        Context ctx = Context.current().with(span);
        GlobalOpenTelemetry.getPropagators().getTextMapPropagator()
                .inject(ctx, requestBuilder, REQUEST_BUILDER_SETTER);
    }

    public Scope makeCurrent(Span span) {
        return span != null ? span.makeCurrent() : NO_OP_SCOPE;
    }

    public void setOutcome(Span span, String outcome) {
        if (span != null && outcome != null) {
            span.setAttribute(Constants.CAPI_OUTCOME_ATTR, outcome);
        }
    }

    public void setSession(Span span, String sessionId, String protocolVersion) {
        if (span == null) return;
        if (sessionId != null) span.setAttribute(Constants.MCP_SESSION_ID_ATTR, sessionId);
        if (protocolVersion != null) span.setAttribute(Constants.MCP_PROTOCOL_VERSION_ATTR, protocolVersion);
    }

    public void setToolType(Span span, boolean isMcpServer) {
        if (span == null) return;
        span.setAttribute(Constants.GEN_AI_TOOL_TYPE,
                isMcpServer ? Constants.GEN_AI_TOOL_TYPE_MCP_SERVER : Constants.GEN_AI_TOOL_TYPE_FUNCTION);
    }

    public void setToolName(Span span, String toolName) {
        if (span != null && toolName != null) {
            span.setAttribute(Constants.GEN_AI_TOOL_NAME, toolName);
        }
    }

    public void setToolCallId(Span span, Object id) {
        if (span == null || id == null) return;
        span.setAttribute(Constants.GEN_AI_TOOL_CALL_ID, String.valueOf(id));
    }

    public void setHttpStatus(Span span, int statusCode) {
        if (span == null) return;
        span.setAttribute("http.response.status_code", statusCode);
        if (statusCode >= 400) {
            span.setStatus(StatusCode.ERROR, "HTTP " + statusCode);
        }
    }

    public void recordError(Span span, Throwable t) {
        if (span == null || t == null) return;
        span.recordException(t);
        span.setStatus(StatusCode.ERROR,
                t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
    }

    public void end(Span span) {
        if (span != null) span.end();
    }

    private static final TextMapGetter<HttpServerExchange> EXCHANGE_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(HttpServerExchange carrier) {
            if (carrier == null) return List.of();
            List<String> names = new ArrayList<>();
            for (HttpString name : carrier.getRequestHeaders().getHeaderNames()) {
                names.add(name.toString());
            }
            return names;
        }

        @Override
        public String get(HttpServerExchange carrier, String key) {
            if (carrier == null || key == null) return null;
            HeaderValues values = carrier.getRequestHeaders().get(key);
            return values != null && !values.isEmpty() ? values.getFirst() : null;
        }
    };

    private static final TextMapSetter<HttpRequest.Builder> REQUEST_BUILDER_SETTER = (carrier, key, value) -> {
        if (carrier != null && key != null && value != null) {
            carrier.header(key, value);
        }
    };

    private static final Scope NO_OP_SCOPE = () -> { /* no-op */ };
}