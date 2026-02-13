package io.surisoft.capi.tracer;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.NamedNode;
import org.apache.camel.Processor;
import org.apache.camel.opentelemetry.GetCorrelationContextProcessor;
import org.apache.camel.opentelemetry.SetCorrelationContextProcessor;
import org.apache.camel.spi.InterceptStrategy;
import org.apache.camel.support.PatternHelper;
import org.apache.camel.support.processor.DelegateAsyncProcessor;
import org.apache.camel.tracing.ActiveSpanManager;
import org.apache.camel.tracing.SpanDecorator;

public class CapiOpenTelemetryTracingStrategy implements InterceptStrategy {

    private static final String UNNAMED = "unnamed";

    private final CapiTracer tracer;
    private boolean propagateContext;

    public CapiOpenTelemetryTracingStrategy(CapiTracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public Processor wrapProcessorInInterceptors(
            CamelContext camelContext,
            NamedNode processorDefinition, Processor target, Processor nextTarget)
            throws Exception {
        if (shouldTrace(processorDefinition)) {
            return new CapiOpenTelemetryTracingStrategy.PropagateContextAndCreateSpan(processorDefinition, target);
        } else if (isPropagateContext()) {
            return new CapiOpenTelemetryTracingStrategy.PropagateContext(target);
        } else {
            return new DelegateAsyncProcessor(target);
        }
    }

    public boolean isPropagateContext() {
        return propagateContext;
    }

    public void setPropagateContext(boolean propagateContext) {
        this.propagateContext = propagateContext;
    }

    private class PropagateContextAndCreateSpan implements Processor {
        private final NamedNode processorDefinition;
        private final Processor target;

        public PropagateContextAndCreateSpan(NamedNode processorDefinition, Processor target) {
            this.processorDefinition = processorDefinition;
            this.target = target;
        }

        @Override
        public void process(Exchange exchange) throws Exception {
            Span span = null;
            CapiOpenTelemetrySpanAdapter spanWrapper = (CapiOpenTelemetrySpanAdapter) ActiveSpanManager.getSpan(exchange);
            if (spanWrapper != null) {
                span = spanWrapper.getOpenTelemetrySpan();
            }

            if (span == null) {
                target.process(exchange);
                return;
            }

            final Span processorSpan = tracer.getTracer().spanBuilder(getOperationName(processorDefinition))
                    .setParent(Context.current().with(span))
                    .setAttribute("component", getComponentName(processorDefinition))
                    .startSpan();

            boolean activateExchange = !(target instanceof GetCorrelationContextProcessor
                    || target instanceof SetCorrelationContextProcessor);

            if (activateExchange) {
                ActiveSpanManager.activate(exchange, new CapiOpenTelemetrySpanAdapter(processorSpan));
            }

            try (Scope ignored = processorSpan.makeCurrent()) {
                target.process(exchange);
            } catch (Exception ex) {
                span.setStatus(StatusCode.ERROR);
                span.recordException(ex);
                throw ex;
            } finally {
                if (activateExchange) {
                    ActiveSpanManager.deactivate(exchange);
                }
                processorSpan.end();
            }
        }
    }

    private static class PropagateContext implements Processor {
        private final Processor target;

        public PropagateContext(Processor target) {
            this.target = target;
        }

        @Override
        public void process(Exchange exchange) throws Exception {
            Span span = null;
            CapiOpenTelemetrySpanAdapter spanWrapper = (CapiOpenTelemetrySpanAdapter) ActiveSpanManager.getSpan(exchange);
            if (spanWrapper != null) {
                span = spanWrapper.getOpenTelemetrySpan();
            }

            if (span == null) {
                target.process(exchange);
                return;
            }

            boolean activateExchange = !(target instanceof GetCorrelationContextProcessor
                    || target instanceof SetCorrelationContextProcessor);

            if (activateExchange) {
                ActiveSpanManager.activate(exchange, new CapiOpenTelemetrySpanAdapter(span));
            }

            try {
                target.process(exchange);
            } catch (Exception ex) {
                span.setStatus(StatusCode.ERROR);
                span.recordException(ex);
                throw ex;
            } finally {
                if (activateExchange) {
                    ActiveSpanManager.deactivate(exchange);
                }
            }
        }
    }

    private static String getComponentName(NamedNode processorDefinition) {
        return SpanDecorator.CAMEL_COMPONENT + processorDefinition.getShortName();
    }

    private static String getOperationName(NamedNode processorDefinition) {
        final String name = processorDefinition.getId();
        return name == null ? UNNAMED : name;
    }

    private boolean shouldTrace(NamedNode definition) {
        for(String pattern : tracer.getExcludePatterns()) {
            if (PatternHelper.matchPattern(definition.getId(), pattern)) {
                return false;
            }
        }
        return true;
    }
}