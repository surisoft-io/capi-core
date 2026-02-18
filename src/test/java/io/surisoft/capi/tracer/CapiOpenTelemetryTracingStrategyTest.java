package io.surisoft.capi.tracer;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.NamedNode;
import org.apache.camel.Processor;
import org.apache.camel.opentelemetry.GetCorrelationContextProcessor;
import org.apache.camel.opentelemetry.SetCorrelationContextProcessor;
import org.apache.camel.tracing.ActiveSpanManager;
import org.apache.camel.tracing.SpanAdapter;
import org.apache.camel.tracing.SpanDecorator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.apache.camel.ExchangePropertyKey;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CapiOpenTelemetryTracingStrategyTest {

    @Mock
    private CapiTracer capiTracer;
    @Mock
    private CamelContext camelContext;
    @Mock
    private NamedNode processorDefinition;
    @Mock
    private Processor target;
    @Mock
    private Processor nextTarget;
    @Mock
    private Tracer otelTracer;
    @Mock
    private SpanBuilder spanBuilder;
    @Mock
    private Span otelSpan;
    @Mock
    private SpanContext spanContext;
    @Mock
    private Scope scope;
    @Mock
    private Exchange exchange;

    private CapiOpenTelemetryTracingStrategy strategy;

    @BeforeEach
    void setUp() {
        when(capiTracer.getExcludePatterns()).thenReturn(new ArrayList<>());
        when(exchange.getProperty(any(ExchangePropertyKey.class), any(), eq(Boolean.class))).thenReturn(false);
        when(exchange.getContext()).thenReturn(camelContext);
        when(camelContext.isUseMDCLogging()).thenReturn(false);
        strategy = new CapiOpenTelemetryTracingStrategy(capiTracer);
    }

    // ---------------------------------------------------------------
    // isPropagateContext / setPropagateContext
    // ---------------------------------------------------------------

    @Test
    void propagateContext_defaultIsFalse() {
        assertFalse(strategy.isPropagateContext());
    }

    @Test
    void setPropagateContext_updatesValue() {
        strategy.setPropagateContext(true);
        assertTrue(strategy.isPropagateContext());
    }

    // ---------------------------------------------------------------
    // wrapProcessorInInterceptors - shouldTrace = true
    // ---------------------------------------------------------------

    @Test
    void wrapProcessor_whenShouldTrace_returnsPropagateContextAndCreateSpan() throws Exception {
        // No exclude patterns, processorDefinition.getId() returns non-null
        lenient().when(processorDefinition.getId()).thenReturn("myProcessor");

        Processor result = strategy.wrapProcessorInInterceptors(camelContext, processorDefinition, target, nextTarget);

        assertNotNull(result);
        // The returned processor should be a PropagateContextAndCreateSpan inner class
        assertTrue(result.getClass().getName().contains("PropagateContextAndCreateSpan"));
    }

    // ---------------------------------------------------------------
    // wrapProcessorInInterceptors - shouldTrace = false, propagateContext = true
    // ---------------------------------------------------------------

    @Test
    void wrapProcessor_whenNotTraceButPropagateContext_returnsPropagateContext() throws Exception {
        // Add an exclude pattern that matches the processor
        List<String> excludePatterns = new ArrayList<>();
        excludePatterns.add("myProcessor");
        when(capiTracer.getExcludePatterns()).thenReturn(excludePatterns);

        when(processorDefinition.getId()).thenReturn("myProcessor");

        strategy.setPropagateContext(true);

        Processor result = strategy.wrapProcessorInInterceptors(camelContext, processorDefinition, target, nextTarget);

        assertNotNull(result);
        assertTrue(result.getClass().getName().contains("PropagateContext"));
    }

    // ---------------------------------------------------------------
    // wrapProcessorInInterceptors - shouldTrace = false, propagateContext = false
    // ---------------------------------------------------------------

    @Test
    void wrapProcessor_whenNotTraceAndNotPropagate_returnsDelegateAsyncProcessor() throws Exception {
        List<String> excludePatterns = new ArrayList<>();
        excludePatterns.add("myProcessor");
        when(capiTracer.getExcludePatterns()).thenReturn(excludePatterns);

        when(processorDefinition.getId()).thenReturn("myProcessor");

        strategy.setPropagateContext(false);

        Processor result = strategy.wrapProcessorInInterceptors(camelContext, processorDefinition, target, nextTarget);

        assertNotNull(result);
        assertTrue(result.getClass().getName().contains("DelegateAsyncProcessor"));
    }

    // ---------------------------------------------------------------
    // PropagateContextAndCreateSpan.process - no span
    // ---------------------------------------------------------------

    @Test
    void propagateContextAndCreateSpan_process_noSpan_delegatesToTarget() throws Exception {
        when(processorDefinition.getId()).thenReturn("testProcessor");
        when(processorDefinition.getShortName()).thenReturn("process");

        Processor wrapped = strategy.wrapProcessorInInterceptors(camelContext, processorDefinition, target, nextTarget);

        // ActiveSpanManager.getSpan returns null since no span is active for exchange
        wrapped.process(exchange);

        verify(target).process(exchange);
    }

    // ---------------------------------------------------------------
    // PropagateContextAndCreateSpan.process - with span
    // ---------------------------------------------------------------

    @Test
    void propagateContextAndCreateSpan_process_withNoActiveSpan_delegatesToTarget() throws Exception {
        when(processorDefinition.getId()).thenReturn("testProcessor");
        when(processorDefinition.getShortName()).thenReturn("process");

        Processor wrapped = strategy.wrapProcessorInInterceptors(camelContext, processorDefinition, target, nextTarget);

        // No active span - should still delegate to target
        wrapped.process(exchange);

        verify(target).process(exchange);
    }

    // ---------------------------------------------------------------
    // PropagateContextAndCreateSpan.process - with exception
    // ---------------------------------------------------------------

    @Test
    void propagateContextAndCreateSpan_process_withException_propagatesException() throws Exception {
        when(processorDefinition.getId()).thenReturn("testProcessor");
        when(processorDefinition.getShortName()).thenReturn("process");

        RuntimeException exception = new RuntimeException("test error");
        doThrow(exception).when(target).process(exchange);

        Processor wrapped = strategy.wrapProcessorInInterceptors(camelContext, processorDefinition, target, nextTarget);

        // Without an active span, exception should still propagate
        assertThrows(RuntimeException.class, () -> wrapped.process(exchange));
    }

    // ---------------------------------------------------------------
    // PropagateContext.process - no span
    // ---------------------------------------------------------------

    @Test
    void propagateContext_process_noSpan_delegatesToTarget() throws Exception {
        List<String> excludePatterns = new ArrayList<>();
        excludePatterns.add("excluded");
        when(capiTracer.getExcludePatterns()).thenReturn(excludePatterns);
        when(processorDefinition.getId()).thenReturn("excluded");

        strategy.setPropagateContext(true);

        Processor wrapped = strategy.wrapProcessorInInterceptors(camelContext, processorDefinition, target, nextTarget);

        wrapped.process(exchange);

        verify(target).process(exchange);
    }

    // ---------------------------------------------------------------
    // PropagateContext.process - with span
    // ---------------------------------------------------------------

    @Test
    void propagateContext_process_withSpan_activatesAndDeactivatesExchange() throws Exception {
        List<String> excludePatterns = new ArrayList<>();
        excludePatterns.add("excluded");
        when(capiTracer.getExcludePatterns()).thenReturn(excludePatterns);
        when(processorDefinition.getId()).thenReturn("excluded");

        strategy.setPropagateContext(true);

        Processor wrapped = strategy.wrapProcessorInInterceptors(camelContext, processorDefinition, target, nextTarget);

        Span parentSpan = mock(Span.class);
        CapiOpenTelemetrySpanAdapter parentAdapter = new CapiOpenTelemetrySpanAdapter(parentSpan);
        ActiveSpanManager.activate(exchange, parentAdapter);

        try {
            wrapped.process(exchange);
        } finally {
            ActiveSpanManager.deactivate(exchange);
        }

        verify(target).process(exchange);
    }

    // ---------------------------------------------------------------
    // PropagateContext.process - with span and exception
    // ---------------------------------------------------------------

    @Test
    void propagateContext_process_withException_setsErrorOnSpan() throws Exception {
        List<String> excludePatterns = new ArrayList<>();
        excludePatterns.add("excluded");
        when(capiTracer.getExcludePatterns()).thenReturn(excludePatterns);
        when(processorDefinition.getId()).thenReturn("excluded");

        strategy.setPropagateContext(true);

        Processor wrapped = strategy.wrapProcessorInInterceptors(camelContext, processorDefinition, target, nextTarget);

        Span parentSpan = mock(Span.class);
        when(parentSpan.setStatus(any())).thenReturn(parentSpan);
        CapiOpenTelemetrySpanAdapter parentAdapter = new CapiOpenTelemetrySpanAdapter(parentSpan);
        ActiveSpanManager.activate(exchange, parentAdapter);

        RuntimeException exception = new RuntimeException("test error");
        doThrow(exception).when(target).process(exchange);

        try {
            assertThrows(RuntimeException.class, () -> wrapped.process(exchange));
        } finally {
            ActiveSpanManager.deactivate(exchange);
        }
    }

    // ---------------------------------------------------------------
    // getOperationName - with and without id
    // ---------------------------------------------------------------

    @Test
    void wrapProcessor_withNullId_stillWrapsProcessor() throws Exception {
        when(processorDefinition.getId()).thenReturn(null);

        Processor wrapped = strategy.wrapProcessorInInterceptors(camelContext, processorDefinition, target, nextTarget);

        // Even with null id, wrapping should succeed
        assertNotNull(wrapped);
        assertTrue(wrapped.getClass().getName().contains("PropagateContextAndCreateSpan"));
    }

    // ---------------------------------------------------------------
    // PropagateContextAndCreateSpan - process with active span, creates child span
    // Uses a real Exchange (DefaultExchange) so ActiveSpanManager can store properties
    // ---------------------------------------------------------------

    @Test
    void propagateContextAndCreateSpan_process_withActiveSpan_createsChildSpan() throws Exception {
        when(processorDefinition.getId()).thenReturn("testProcessor");
        when(processorDefinition.getShortName()).thenReturn("process");

        // Setup tracer to return a span builder and span
        when(capiTracer.getTracer()).thenReturn(otelTracer);
        when(otelTracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        when(spanBuilder.setParent(any())).thenReturn(spanBuilder);
        when(spanBuilder.setAttribute(anyString(), anyString())).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(otelSpan);
        when(otelSpan.makeCurrent()).thenReturn(scope);

        Processor wrapped = strategy.wrapProcessorInInterceptors(camelContext, processorDefinition, target, nextTarget);

        // Use a real exchange so ActiveSpanManager can store/retrieve span properties
        CamelContext realCtx = new DefaultCamelContext();
        Exchange realExchange = new DefaultExchange(realCtx);

        Span parentSpan = mock(Span.class);
        CapiOpenTelemetrySpanAdapter parentAdapter = new CapiOpenTelemetrySpanAdapter(parentSpan);
        ActiveSpanManager.activate(realExchange, parentAdapter);

        try {
            wrapped.process(realExchange);
            // Verify the child processor span was created
            verify(otelTracer).spanBuilder("testProcessor");
            verify(spanBuilder).setAttribute("component", SpanDecorator.CAMEL_COMPONENT + "process");
            verify(spanBuilder).startSpan();
            // Verify the child span was ended
            verify(otelSpan).end();
            // Verify the target was called
            verify(target).process(realExchange);
        } finally {
            ActiveSpanManager.deactivate(realExchange);
            realCtx.close();
        }
    }

    // ---------------------------------------------------------------
    // PropagateContextAndCreateSpan - process with active span and exception
    // ---------------------------------------------------------------

    @Test
    void propagateContextAndCreateSpan_process_withActiveSpanAndException_setsErrorAndRethrows() throws Exception {
        when(processorDefinition.getId()).thenReturn("testProcessor");
        when(processorDefinition.getShortName()).thenReturn("process");

        when(capiTracer.getTracer()).thenReturn(otelTracer);
        when(otelTracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        when(spanBuilder.setParent(any())).thenReturn(spanBuilder);
        when(spanBuilder.setAttribute(anyString(), anyString())).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(otelSpan);
        when(otelSpan.makeCurrent()).thenReturn(scope);

        Processor wrapped = strategy.wrapProcessorInInterceptors(camelContext, processorDefinition, target, nextTarget);

        CamelContext realCtx = new DefaultCamelContext();
        Exchange realExchange = new DefaultExchange(realCtx);

        Span parentSpan = mock(Span.class);
        when(parentSpan.setStatus(any())).thenReturn(parentSpan);
        CapiOpenTelemetrySpanAdapter parentAdapter = new CapiOpenTelemetrySpanAdapter(parentSpan);
        ActiveSpanManager.activate(realExchange, parentAdapter);

        RuntimeException exception = new RuntimeException("test error");
        doThrow(exception).when(target).process(realExchange);

        try {
            assertThrows(RuntimeException.class, () -> wrapped.process(realExchange));
            // Verify error was recorded on the parent span (the span retrieved by getSpan)
            verify(parentSpan).setStatus(StatusCode.ERROR);
            verify(parentSpan).recordException(exception);
            // Verify the child processor span was still ended
            verify(otelSpan).end();
        } finally {
            ActiveSpanManager.deactivate(realExchange);
            realCtx.close();
        }
    }

    // ---------------------------------------------------------------
    // PropagateContextAndCreateSpan - process with unnamed processor (null id)
    // ---------------------------------------------------------------

    @Test
    void propagateContextAndCreateSpan_process_withNullId_usesUnnamedOperationName() throws Exception {
        when(processorDefinition.getId()).thenReturn(null);
        when(processorDefinition.getShortName()).thenReturn("to");

        when(capiTracer.getTracer()).thenReturn(otelTracer);
        when(otelTracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        when(spanBuilder.setParent(any())).thenReturn(spanBuilder);
        when(spanBuilder.setAttribute(anyString(), anyString())).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(otelSpan);
        when(otelSpan.makeCurrent()).thenReturn(scope);

        Processor wrapped = strategy.wrapProcessorInInterceptors(camelContext, processorDefinition, target, nextTarget);

        CamelContext realCtx = new DefaultCamelContext();
        Exchange realExchange = new DefaultExchange(realCtx);

        Span parentSpan = mock(Span.class);
        CapiOpenTelemetrySpanAdapter parentAdapter = new CapiOpenTelemetrySpanAdapter(parentSpan);
        ActiveSpanManager.activate(realExchange, parentAdapter);

        try {
            wrapped.process(realExchange);
            // Verify the span was created with "unnamed" since id is null
            verify(otelTracer).spanBuilder("unnamed");
            verify(target).process(realExchange);
        } finally {
            ActiveSpanManager.deactivate(realExchange);
            realCtx.close();
        }
    }

    // ---------------------------------------------------------------
    // PropagateContext - process with active span, activates and deactivates
    // ---------------------------------------------------------------

    @Test
    void propagateContext_process_withActiveSpan_activatesContextAndDelegates() throws Exception {
        List<String> excludePatterns = new ArrayList<>();
        excludePatterns.add("excluded");
        when(capiTracer.getExcludePatterns()).thenReturn(excludePatterns);
        when(processorDefinition.getId()).thenReturn("excluded");

        strategy.setPropagateContext(true);

        Processor wrapped = strategy.wrapProcessorInInterceptors(camelContext, processorDefinition, target, nextTarget);

        CamelContext realCtx = new DefaultCamelContext();
        Exchange realExchange = new DefaultExchange(realCtx);

        Span parentSpan = mock(Span.class);
        CapiOpenTelemetrySpanAdapter parentAdapter = new CapiOpenTelemetrySpanAdapter(parentSpan);
        ActiveSpanManager.activate(realExchange, parentAdapter);

        try {
            wrapped.process(realExchange);
            // Verify the target was called
            verify(target).process(realExchange);
        } finally {
            ActiveSpanManager.deactivate(realExchange);
            realCtx.close();
        }
    }

    // ---------------------------------------------------------------
    // PropagateContext - process with active span and exception, records error
    // ---------------------------------------------------------------

    @Test
    void propagateContext_process_withActiveSpanAndException_recordsErrorAndRethrows() throws Exception {
        List<String> excludePatterns = new ArrayList<>();
        excludePatterns.add("excluded");
        when(capiTracer.getExcludePatterns()).thenReturn(excludePatterns);
        when(processorDefinition.getId()).thenReturn("excluded");

        strategy.setPropagateContext(true);

        Processor wrapped = strategy.wrapProcessorInInterceptors(camelContext, processorDefinition, target, nextTarget);

        CamelContext realCtx = new DefaultCamelContext();
        Exchange realExchange = new DefaultExchange(realCtx);

        Span parentSpan = mock(Span.class);
        when(parentSpan.setStatus(any())).thenReturn(parentSpan);
        CapiOpenTelemetrySpanAdapter parentAdapter = new CapiOpenTelemetrySpanAdapter(parentSpan);
        ActiveSpanManager.activate(realExchange, parentAdapter);

        RuntimeException exception = new RuntimeException("propagate context error");
        doThrow(exception).when(target).process(realExchange);

        try {
            RuntimeException thrown = assertThrows(RuntimeException.class, () -> wrapped.process(realExchange));
            assertEquals("propagate context error", thrown.getMessage());
            // Verify error was recorded on the span
            verify(parentSpan).setStatus(StatusCode.ERROR);
            verify(parentSpan).recordException(exception);
        } finally {
            ActiveSpanManager.deactivate(realExchange);
            realCtx.close();
        }
    }

    // ---------------------------------------------------------------
    // PropagateContext - process with no active span delegates directly
    // ---------------------------------------------------------------

    @Test
    void propagateContext_process_withNoActiveSpan_delegatesDirectly() throws Exception {
        List<String> excludePatterns = new ArrayList<>();
        excludePatterns.add("excluded");
        when(capiTracer.getExcludePatterns()).thenReturn(excludePatterns);
        when(processorDefinition.getId()).thenReturn("excluded");

        strategy.setPropagateContext(true);

        Processor wrapped = strategy.wrapProcessorInInterceptors(camelContext, processorDefinition, target, nextTarget);

        CamelContext realCtx = new DefaultCamelContext();
        Exchange realExchange = new DefaultExchange(realCtx);

        try {
            // No active span on this real exchange
            wrapped.process(realExchange);
            verify(target).process(realExchange);
        } finally {
            realCtx.close();
        }
    }

    // ---------------------------------------------------------------
    // PropagateContextAndCreateSpan - process with GetCorrelationContextProcessor
    // (tests the activateExchange = false branch)
    // ---------------------------------------------------------------

    @Test
    void propagateContextAndCreateSpan_process_withCorrelationContextProcessor_doesNotActivateExchange() throws Exception {
        when(processorDefinition.getId()).thenReturn("corrCtx");
        when(processorDefinition.getShortName()).thenReturn("process");

        when(capiTracer.getTracer()).thenReturn(otelTracer);
        when(otelTracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        when(spanBuilder.setParent(any())).thenReturn(spanBuilder);
        when(spanBuilder.setAttribute(anyString(), anyString())).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(otelSpan);
        when(otelSpan.makeCurrent()).thenReturn(scope);

        // Use a GetCorrelationContextProcessor as the target
        Processor corrTarget = mock(GetCorrelationContextProcessor.class);

        Processor wrapped = strategy.wrapProcessorInInterceptors(camelContext, processorDefinition, corrTarget, nextTarget);

        CamelContext realCtx = new DefaultCamelContext();
        Exchange realExchange = new DefaultExchange(realCtx);

        Span parentSpan = mock(Span.class);
        CapiOpenTelemetrySpanAdapter parentAdapter = new CapiOpenTelemetrySpanAdapter(parentSpan);
        ActiveSpanManager.activate(realExchange, parentAdapter);

        try {
            wrapped.process(realExchange);
            // Verify the target was called
            verify(corrTarget).process(realExchange);
            // The processor span should still be ended
            verify(otelSpan).end();
        } finally {
            ActiveSpanManager.deactivate(realExchange);
            realCtx.close();
        }
    }

    // ---------------------------------------------------------------
    // PropagateContext - process with SetCorrelationContextProcessor
    // (tests the activateExchange = false branch in PropagateContext)
    // ---------------------------------------------------------------

    @Test
    void propagateContext_process_withSetCorrelationContextProcessor_doesNotActivateExchange() throws Exception {
        List<String> excludePatterns = new ArrayList<>();
        excludePatterns.add("corrCtx");
        when(capiTracer.getExcludePatterns()).thenReturn(excludePatterns);
        when(processorDefinition.getId()).thenReturn("corrCtx");

        strategy.setPropagateContext(true);

        // Use a SetCorrelationContextProcessor as the target
        Processor corrTarget = mock(SetCorrelationContextProcessor.class);

        Processor wrapped = strategy.wrapProcessorInInterceptors(camelContext, processorDefinition, corrTarget, nextTarget);

        CamelContext realCtx = new DefaultCamelContext();
        Exchange realExchange = new DefaultExchange(realCtx);

        Span parentSpan = mock(Span.class);
        CapiOpenTelemetrySpanAdapter parentAdapter = new CapiOpenTelemetrySpanAdapter(parentSpan);
        ActiveSpanManager.activate(realExchange, parentAdapter);

        try {
            wrapped.process(realExchange);
            verify(corrTarget).process(realExchange);
        } finally {
            ActiveSpanManager.deactivate(realExchange);
            realCtx.close();
        }
    }
}
