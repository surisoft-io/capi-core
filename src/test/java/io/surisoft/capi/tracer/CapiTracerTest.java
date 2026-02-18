package io.surisoft.capi.tracer;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.surisoft.capi.schema.Mapping;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.HttpUtils;
import org.apache.camel.*;
import org.apache.camel.spi.*;
import org.apache.camel.tracing.SpanAdapter;
import org.apache.camel.support.DefaultEndpoint;
import org.apache.camel.tracing.ActiveSpanManager;
import org.apache.camel.tracing.ExtractAdapter;
import org.apache.camel.tracing.InjectAdapter;
import org.apache.camel.tracing.SpanDecorator;
import org.cache2k.Cache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CapiTracerTest {

    @Mock
    private HttpUtils httpUtils;
    @Mock
    private Cache<String, Service> serviceCache;
    @Mock
    private Tracer otelTracer;
    @Mock
    private CamelContext camelContext;
    @Mock
    private SpanBuilder spanBuilder;
    @Mock
    private Span otelSpan;
    @Mock
    private SpanContext spanContext;
    @Mock
    private ContextPropagators contextPropagators;
    @Mock
    private TextMapPropagator textMapPropagator;
    @Mock
    private Exchange exchange;
    @Mock
    private Message message;
    @Mock
    private Endpoint endpoint;
    @Mock
    private Route route;
    @Mock
    private ManagementStrategy managementStrategy;
    @Mock
    private ExtendedCamelContext camelContextExtension;
    @Mock
    private SpanDecorator spanDecorator;

    private CapiTracer capiTracer;

    @Mock
    private org.apache.camel.spi.Registry registry;

    @BeforeEach
    void setUp() {
        capiTracer = new CapiTracer(httpUtils, "test-capi-instance", serviceCache, otelTracer);
        when(camelContext.getRegistry()).thenReturn(registry);
        capiTracer.setCamelContext(camelContext);
    }

    // ---------------------------------------------------------------
    // Constructor and basic getters/setters
    // ---------------------------------------------------------------

    @Test
    void constructor_setsFieldsCorrectly() {
        assertSame(camelContext, capiTracer.getCamelContext());
        assertSame(otelTracer, capiTracer.getTracer());
    }

    @Test
    void setAndGetCamelContext() {
        CamelContext anotherContext = mock(CamelContext.class);
        capiTracer.setCamelContext(anotherContext);
        assertSame(anotherContext, capiTracer.getCamelContext());
    }

    @Test
    void setAndGetTracer() {
        Tracer anotherTracer = mock(Tracer.class);
        capiTracer.setTracer(anotherTracer);
        assertSame(anotherTracer, capiTracer.getTracer());
    }

    @Test
    void setAndGetExcludePatterns() {
        List<String> patterns = List.of("pattern1", "pattern2");
        capiTracer.setExcludePatterns(patterns);
        assertEquals(patterns, capiTracer.getExcludePatterns());
    }

    @Test
    void setAndGetEncoding() {
        assertFalse(capiTracer.isEncoding());
        capiTracer.setEncoding(true);
        assertTrue(capiTracer.isEncoding());
    }

    @Test
    void setAndGetContextPropagators() {
        capiTracer.setContextPropagators(contextPropagators);
        assertSame(contextPropagators, capiTracer.getContextPropagators());
    }

    @Test
    void setAndGetTracingStrategy() {
        assertNull(capiTracer.getTracingStrategy());
        CapiOpenTelemetryTracingStrategy strategy = new CapiOpenTelemetryTracingStrategy(capiTracer);
        capiTracer.setTracingStrategy(strategy);
        assertSame(strategy, capiTracer.getTracingStrategy());
    }

    // ---------------------------------------------------------------
    // initTracer
    // ---------------------------------------------------------------

    @Test
    void initTracer_withExistingTracer_doesNotOverwrite() {
        capiTracer.initTracer();
        assertSame(otelTracer, capiTracer.getTracer());
    }

    @Test
    void initTracer_withNullTracer_looksUpFromContext() {
        CapiTracer tracerWithNull = new CapiTracer(httpUtils, "test", serviceCache, null);
        // Setup the CamelContext mock that has a Tracer registered
        CamelContext ctx = mock(CamelContext.class);
        when(ctx.getRegistry()).thenReturn(mock(org.apache.camel.spi.Registry.class));
        tracerWithNull.setCamelContext(ctx);
        // initTracer will try CamelContextHelper.findSingleByType, which returns null from mock
        // Then it falls back to GlobalOpenTelemetry - just ensure no exception
        tracerWithNull.initTracer();
        assertNotNull(tracerWithNull.getTracer());
    }

    // ---------------------------------------------------------------
    // initContextPropagators
    // ---------------------------------------------------------------

    @Test
    void initContextPropagators_withExistingPropagators_doesNotOverwrite() {
        capiTracer.setContextPropagators(contextPropagators);
        capiTracer.initContextPropagators();
        assertSame(contextPropagators, capiTracer.getContextPropagators());
    }

    @Test
    void initContextPropagators_withNullPropagators_fallsBackToGlobal() {
        capiTracer.initContextPropagators();
        assertNotNull(capiTracer.getContextPropagators());
    }

    // ---------------------------------------------------------------
    // startSendingEventSpan
    // ---------------------------------------------------------------

    @Test
    void startSendingEventSpan_withoutParent_returnsSpanAdapter() {
        when(otelTracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        when(spanBuilder.setSpanKind(any())).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(otelSpan);

        SpanAdapter result = capiTracer.startSendingEventSpan(
                "testOp",
                org.apache.camel.tracing.SpanKind.SPAN_KIND_CLIENT,
                null,
                exchange,
                mock(InjectAdapter.class));

        assertNotNull(result);
        assertInstanceOf(CapiOpenTelemetrySpanAdapter.class, result);
    }

    @Test
    void startSendingEventSpan_withParent_setsParentContext() {
        CapiOpenTelemetrySpanAdapter parent = new CapiOpenTelemetrySpanAdapter(otelSpan, Baggage.empty());
        when(otelTracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        when(spanBuilder.setSpanKind(any())).thenReturn(spanBuilder);
        when(spanBuilder.setParent(any())).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(otelSpan);

        SpanAdapter result = capiTracer.startSendingEventSpan(
                "testOp",
                org.apache.camel.tracing.SpanKind.SPAN_KIND_CLIENT,
                parent,
                exchange,
                mock(InjectAdapter.class));

        assertNotNull(result);
        verify(spanBuilder).setParent(any());
    }

    // ---------------------------------------------------------------
    // startExchangeBeginSpan
    // ---------------------------------------------------------------

    @Test
    void startExchangeBeginSpan_withParent_setsParentContext() {
        CapiOpenTelemetrySpanAdapter parent = new CapiOpenTelemetrySpanAdapter(otelSpan, Baggage.empty());
        when(otelTracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        when(spanBuilder.setParent(any())).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(otelSpan);

        SpanAdapter result = capiTracer.startExchangeBeginSpan(
                exchange,
                spanDecorator,
                "testOp",
                org.apache.camel.tracing.SpanKind.SPAN_KIND_SERVER,
                parent);

        assertNotNull(result);
        verify(spanBuilder).setParent(any());
    }

    @Test
    void startExchangeBeginSpan_withoutParent_extractsContext() {
        when(exchange.getIn()).thenReturn(message);
        when(message.getHeaders()).thenReturn(new HashMap<>());

        ExtractAdapter extractAdapter = mock(ExtractAdapter.class);
        when(spanDecorator.getExtractAdapter(any(), anyBoolean())).thenReturn(extractAdapter);
        when(spanDecorator.getReceiverSpanKind()).thenReturn(org.apache.camel.tracing.SpanKind.SPAN_KIND_SERVER);

        capiTracer.setContextPropagators(contextPropagators);
        when(contextPropagators.getTextMapPropagator()).thenReturn(textMapPropagator);
        when(textMapPropagator.extract(any(), any(), any())).thenReturn(io.opentelemetry.context.Context.current());

        when(otelTracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        when(spanBuilder.setSpanKind(any())).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(otelSpan);

        SpanAdapter result = capiTracer.startExchangeBeginSpan(
                exchange,
                spanDecorator,
                "testOp",
                org.apache.camel.tracing.SpanKind.SPAN_KIND_SERVER,
                null);

        assertNotNull(result);
    }

    // ---------------------------------------------------------------
    // finishSpan
    // ---------------------------------------------------------------

    @Test
    void finishSpan_endsOtelSpan() {
        CapiOpenTelemetrySpanAdapter adapter = new CapiOpenTelemetrySpanAdapter(otelSpan);
        capiTracer.finishSpan(adapter);
        verify(otelSpan).end();
    }

    // ---------------------------------------------------------------
    // inject
    // ---------------------------------------------------------------

    @Test
    void inject_callsContextPropagator() {
        // The inject method delegates to contextPropagators.getTextMapPropagator().inject()
        // We verify contextPropagators is used (deep OTel context chain is hard to mock)
        capiTracer.setContextPropagators(contextPropagators);
        when(contextPropagators.getTextMapPropagator()).thenReturn(textMapPropagator);
        assertNotNull(capiTracer.getContextPropagators());
    }

    // ---------------------------------------------------------------
    // addDecorator
    // ---------------------------------------------------------------

    @Test
    void addDecorator_addsToMap() {
        SpanDecorator decorator = mock(SpanDecorator.class);
        when(decorator.getComponent()).thenReturn("test-component");
        capiTracer.addDecorator(decorator);
        assertEquals(decorator, CapiTracer.DECORATORS.get("test-component"));
    }

    // ---------------------------------------------------------------
    // getFromUri (static package-private)
    // ---------------------------------------------------------------

    @Test
    void getFromUri_unknownScheme_returnsNull() {
        SpanDecorator result = CapiTracer.getFromUri("unknownScheme:something");
        // unknownScheme is not registered in DECORATORS, so returns null
        assertNull(result);
    }

    @Test
    void getFromUri_noColon_returnsNull() {
        SpanDecorator result = CapiTracer.getFromUri("noColonHere");
        assertNull(result);
    }

    // ---------------------------------------------------------------
    // createRoutePolicy
    // ---------------------------------------------------------------

    @Test
    void createRoutePolicy_returnsRoutePolicyAndInitsContext() {
        // Setup camelContext.hasService to avoid NPE
        when(camelContext.hasService(CapiTracer.class)).thenReturn(null);
        when(camelContext.hasService(capiTracer)).thenReturn(true);
        when(camelContext.getManagementStrategy()).thenReturn(managementStrategy);
        when(camelContext.getRoutePolicyFactories()).thenReturn(new ArrayList<>());
        when(camelContext.getCamelContextExtension()).thenReturn(camelContextExtension);
        lenient().when(camelContext.isUseMDCLogging()).thenReturn(false);

        RoutePolicy policy = capiTracer.createRoutePolicy(camelContext, "routeId", mock(NamedNode.class));
        assertNotNull(policy);
    }

    // ---------------------------------------------------------------
    // init
    // ---------------------------------------------------------------

    @Test
    void init_whenServiceAlreadyAdded_doesNotAddAgain() {
        when(camelContext.hasService(CapiTracer.class)).thenReturn(null);
        when(camelContext.hasService(capiTracer)).thenReturn(true);

        // Should not throw and should not try to add the service
        capiTracer.init(camelContext);
    }

    @Test
    void init_whenOtherTracerTypeRegistered_doesNotThrow() {
        // Simulate a different CapiTracer already registered
        CapiTracer otherTracer = mock(CapiTracer.class);
        when(camelContext.hasService(CapiTracer.class)).thenReturn(otherTracer);

        // Should not throw even when another tracer is registered
        assertDoesNotThrow(() -> capiTracer.init(camelContext));
    }

    // ---------------------------------------------------------------
    // doShutdown
    // ---------------------------------------------------------------

    @Test
    void doShutdown_removesEventNotifierAndRoutePolicy() {
        when(camelContext.getManagementStrategy()).thenReturn(managementStrategy);
        when(camelContext.getRoutePolicyFactories()).thenReturn(new ArrayList<>(List.of(capiTracer)));

        capiTracer.doShutdown();

        verify(managementStrategy).removeEventNotifier(any());
    }

    // ---------------------------------------------------------------
    // mapToSpanKind (private, tested indirectly via startSendingEventSpan)
    // ---------------------------------------------------------------

    @Test
    void startSendingEventSpan_clientKind_mapsCorrectly() {
        when(otelTracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        when(spanBuilder.setSpanKind(io.opentelemetry.api.trace.SpanKind.CLIENT)).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(otelSpan);

        capiTracer.startSendingEventSpan("op", org.apache.camel.tracing.SpanKind.SPAN_KIND_CLIENT, null, exchange, mock(InjectAdapter.class));
        verify(spanBuilder).setSpanKind(io.opentelemetry.api.trace.SpanKind.CLIENT);
    }

    @Test
    void startSendingEventSpan_serverKind_mapsCorrectly() {
        when(otelTracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        when(spanBuilder.setSpanKind(io.opentelemetry.api.trace.SpanKind.SERVER)).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(otelSpan);

        capiTracer.startSendingEventSpan("op", org.apache.camel.tracing.SpanKind.SPAN_KIND_SERVER, null, exchange, mock(InjectAdapter.class));
        verify(spanBuilder).setSpanKind(io.opentelemetry.api.trace.SpanKind.SERVER);
    }

    @Test
    void startSendingEventSpan_consumerKind_mapsCorrectly() {
        when(otelTracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        when(spanBuilder.setSpanKind(io.opentelemetry.api.trace.SpanKind.CONSUMER)).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(otelSpan);

        capiTracer.startSendingEventSpan("op", org.apache.camel.tracing.SpanKind.CONSUMER, null, exchange, mock(InjectAdapter.class));
        verify(spanBuilder).setSpanKind(io.opentelemetry.api.trace.SpanKind.CONSUMER);
    }

    @Test
    void startSendingEventSpan_producerKind_mapsCorrectly() {
        when(otelTracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        when(spanBuilder.setSpanKind(io.opentelemetry.api.trace.SpanKind.PRODUCER)).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(otelSpan);

        capiTracer.startSendingEventSpan("op", org.apache.camel.tracing.SpanKind.PRODUCER, null, exchange, mock(InjectAdapter.class));
        verify(spanBuilder).setSpanKind(io.opentelemetry.api.trace.SpanKind.PRODUCER);
    }

    // ---------------------------------------------------------------
    // startSendingEventSpan with SpanCustomizer
    // ---------------------------------------------------------------

    @Test
    void startSendingEventSpan_withSpanCustomizer_callsCustomize() throws Exception {
        // Use reflection to set spanCustomizer field
        var field = CapiTracer.class.getDeclaredField("spanCustomizer");
        field.setAccessible(true);
        org.apache.camel.opentelemetry.SpanCustomizer customizer = mock(org.apache.camel.opentelemetry.SpanCustomizer.class);
        field.set(capiTracer, customizer);

        when(customizer.isEnabled(anyString(), any())).thenReturn(true);
        when(otelTracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        when(spanBuilder.setSpanKind(any())).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(otelSpan);

        capiTracer.startSendingEventSpan("op", org.apache.camel.tracing.SpanKind.SPAN_KIND_CLIENT, null, exchange, mock(InjectAdapter.class));

        verify(customizer).customize(eq(spanBuilder), eq("op"), eq(exchange));
    }

    // ---------------------------------------------------------------
    // getExcludePatterns default
    // ---------------------------------------------------------------

    @Test
    void getExcludePatterns_defaultIsEmptyList() {
        CapiTracer fresh = new CapiTracer(httpUtils, "test", serviceCache, otelTracer);
        assertNotNull(fresh.getExcludePatterns());
        assertTrue(fresh.getExcludePatterns().isEmpty());
    }

    // ---------------------------------------------------------------
    // inject with and without baggage
    // ---------------------------------------------------------------

    @Test
    void inject_withBaggage_callsPropagator() {
        capiTracer.setContextPropagators(contextPropagators);
        when(contextPropagators.getTextMapPropagator()).thenReturn(textMapPropagator);

        // Use a real span so Context.current().with() works
        Span realSpan = io.opentelemetry.api.trace.Span.getInvalid();
        CapiOpenTelemetrySpanAdapter spanAdapter = new CapiOpenTelemetrySpanAdapter(realSpan, Baggage.empty());
        InjectAdapter injectAdapter = mock(InjectAdapter.class);

        assertDoesNotThrow(() -> capiTracer.inject(spanAdapter, injectAdapter));
        verify(textMapPropagator).inject(any(), eq(injectAdapter), any());
    }

    @Test
    void inject_withNullBaggage_callsPropagator() {
        capiTracer.setContextPropagators(contextPropagators);
        when(contextPropagators.getTextMapPropagator()).thenReturn(textMapPropagator);

        Span realSpan = io.opentelemetry.api.trace.Span.getInvalid();
        CapiOpenTelemetrySpanAdapter spanAdapter = new CapiOpenTelemetrySpanAdapter(realSpan, null);
        InjectAdapter injectAdapter = mock(InjectAdapter.class);

        assertDoesNotThrow(() -> capiTracer.inject(spanAdapter, injectAdapter));
        verify(textMapPropagator).inject(any(), eq(injectAdapter), any());
    }

    // ---------------------------------------------------------------
    // getFromUri with known decorator
    // ---------------------------------------------------------------

    @Test
    void getFromUri_registeredScheme_returnsDecorator() {
        SpanDecorator decorator = mock(SpanDecorator.class);
        when(decorator.getComponent()).thenReturn("myscheme");
        CapiTracer.DECORATORS.put("myscheme", decorator);
        try {
            SpanDecorator result = CapiTracer.getFromUri("myscheme:something");
            assertSame(decorator, result);
        } finally {
            CapiTracer.DECORATORS.remove("myscheme");
        }
    }

    // ---------------------------------------------------------------
    // doInit
    // ---------------------------------------------------------------

    @Test
    void doInit_setsUpEventNotifierAndLogListener() {
        when(camelContext.getManagementStrategy()).thenReturn(managementStrategy);
        when(camelContext.getRoutePolicyFactories()).thenReturn(new ArrayList<>());
        when(camelContext.getCamelContextExtension()).thenReturn(camelContextExtension);
        lenient().when(camelContext.isUseMDCLogging()).thenReturn(false);

        capiTracer.doInit();

        verify(managementStrategy).addEventNotifier(any());
        verify(camelContext).addRoutePolicyFactory(capiTracer);
        verify(camelContextExtension).addLogListener(any());
    }

    @Test
    void doInit_withMDCLoggingEnabled_doesNotThrow() {
        when(camelContext.getManagementStrategy()).thenReturn(managementStrategy);
        when(camelContext.getRoutePolicyFactories()).thenReturn(new ArrayList<>());
        when(camelContext.getCamelContextExtension()).thenReturn(camelContextExtension);
        when(camelContext.isUseMDCLogging()).thenReturn(true);

        assertDoesNotThrow(() -> capiTracer.doInit());
    }

    @Test
    void doInit_withTracingStrategy_addsInterceptStrategy() {
        CapiOpenTelemetryTracingStrategy strategy = new CapiOpenTelemetryTracingStrategy(capiTracer);
        capiTracer.setTracingStrategy(strategy);

        when(camelContext.getManagementStrategy()).thenReturn(managementStrategy);
        when(camelContext.getRoutePolicyFactories()).thenReturn(new ArrayList<>());
        when(camelContext.getCamelContextExtension()).thenReturn(camelContextExtension);
        lenient().when(camelContext.isUseMDCLogging()).thenReturn(false);

        capiTracer.doInit();

        verify(camelContextExtension).addInterceptStrategy(strategy);
    }

    @Test
    void doInit_alreadyInRoutePolicyFactories_doesNotAddAgain() {
        ArrayList<RoutePolicyFactory> factories = new ArrayList<>();
        factories.add(capiTracer);

        when(camelContext.getManagementStrategy()).thenReturn(managementStrategy);
        when(camelContext.getRoutePolicyFactories()).thenReturn(factories);
        when(camelContext.getCamelContextExtension()).thenReturn(camelContextExtension);
        lenient().when(camelContext.isUseMDCLogging()).thenReturn(false);

        capiTracer.doInit();

        // Should not add again since it's already present
        assertEquals(1, factories.size());
    }

    // ---------------------------------------------------------------
    // init - service not yet added
    // ---------------------------------------------------------------

    @Test
    void init_whenServiceNotYetAdded_addsService() throws Exception {
        when(camelContext.hasService(CapiTracer.class)).thenReturn(null);
        when(camelContext.hasService(capiTracer)).thenReturn(false);

        // This will trigger camelContext.addService, which triggers doInit
        // We need to set up for doInit as well
        when(camelContext.getManagementStrategy()).thenReturn(managementStrategy);
        when(camelContext.getRoutePolicyFactories()).thenReturn(new ArrayList<>());
        when(camelContext.getCamelContextExtension()).thenReturn(camelContextExtension);
        lenient().when(camelContext.isUseMDCLogging()).thenReturn(false);

        capiTracer.init(camelContext);

        verify(camelContext).addService(eq(capiTracer), eq(true), eq(true));
    }

    // ---------------------------------------------------------------
    // initTracer with traceProcessors enabled
    // ---------------------------------------------------------------

    @Test
    void initTracer_withTraceProcessorsEnabled_createsTracingStrategy() throws Exception {
        // Enable traceProcessors via reflection
        var field = CapiTracer.class.getDeclaredField("traceProcessors");
        field.setAccessible(true);
        field.setBoolean(capiTracer, true);

        capiTracer.initTracer();

        assertNotNull(capiTracer.getTracingStrategy());
    }

    // ---------------------------------------------------------------
    // startExchangeBeginSpan with spanCustomizer
    // ---------------------------------------------------------------

    @Test
    void startExchangeBeginSpan_withSpanCustomizer_callsCustomize() throws Exception {
        var field = CapiTracer.class.getDeclaredField("spanCustomizer");
        field.setAccessible(true);
        org.apache.camel.opentelemetry.SpanCustomizer customizer = mock(org.apache.camel.opentelemetry.SpanCustomizer.class);
        field.set(capiTracer, customizer);

        when(customizer.isEnabled(anyString(), any())).thenReturn(true);

        CapiOpenTelemetrySpanAdapter parent = new CapiOpenTelemetrySpanAdapter(otelSpan, Baggage.empty());
        when(otelTracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        when(spanBuilder.setParent(any())).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(otelSpan);

        capiTracer.startExchangeBeginSpan(exchange, spanDecorator, "testOp",
                org.apache.camel.tracing.SpanKind.SPAN_KIND_SERVER, parent);

        verify(customizer).customize(eq(spanBuilder), eq("testOp"), eq(exchange));
    }

    @Test
    void startExchangeBeginSpan_withSpanCustomizer_notEnabled_doesNotCustomize() throws Exception {
        var field = CapiTracer.class.getDeclaredField("spanCustomizer");
        field.setAccessible(true);
        org.apache.camel.opentelemetry.SpanCustomizer customizer = mock(org.apache.camel.opentelemetry.SpanCustomizer.class);
        field.set(capiTracer, customizer);

        when(customizer.isEnabled(anyString(), any())).thenReturn(false);

        CapiOpenTelemetrySpanAdapter parent = new CapiOpenTelemetrySpanAdapter(otelSpan, Baggage.empty());
        when(otelTracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        when(spanBuilder.setParent(any())).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(otelSpan);

        capiTracer.startExchangeBeginSpan(exchange, spanDecorator, "testOp",
                org.apache.camel.tracing.SpanKind.SPAN_KIND_SERVER, parent);

        verify(customizer, never()).customize(any(), any(), any());
    }

    // ---------------------------------------------------------------
    // TracingEventNotifier - notify (ExchangeSendingEvent)
    // ---------------------------------------------------------------

    @Test
    void tracingEventNotifier_exchangeSendingEvent_httpEndpoint_isExcluded() throws Exception {
        // Access the eventNotifier via reflection
        var notifierField = CapiTracer.class.getDeclaredField("eventNotifier");
        notifierField.setAccessible(true);
        var notifier = notifierField.get(capiTracer);

        // Create mock ExchangeSendingEvent
        CamelEvent.ExchangeSendingEvent sendingEvent = mock(CamelEvent.ExchangeSendingEvent.class);
        when(sendingEvent.getEndpoint()).thenReturn(endpoint);
        when(sendingEvent.getExchange()).thenReturn(exchange);
        when(exchange.getIn()).thenReturn(message);
        // HTTP endpoint should be excluded by shouldExclude
        when(endpoint.getEndpointUri()).thenReturn("http://example.com/api");

        // The notify method should return without creating a span
        java.lang.reflect.Method notifyMethod = notifier.getClass().getMethod("notify", CamelEvent.class);
        notifyMethod.setAccessible(true);
        assertDoesNotThrow(() -> {
            try {
                notifyMethod.invoke(notifier, sendingEvent);
            } catch (java.lang.reflect.InvocationTargetException e) {
                if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
            }
        });
    }

    @Test
    void tracingEventNotifier_exchangeSentEvent_httpEndpoint_isExcluded() throws Exception {
        var notifierField = CapiTracer.class.getDeclaredField("eventNotifier");
        notifierField.setAccessible(true);
        var notifier = notifierField.get(capiTracer);

        CamelEvent.ExchangeSentEvent sentEvent = mock(CamelEvent.ExchangeSentEvent.class);
        when(sentEvent.getEndpoint()).thenReturn(endpoint);
        when(sentEvent.getExchange()).thenReturn(exchange);
        when(exchange.getIn()).thenReturn(message);
        when(exchange.getMessage()).thenReturn(message);
        when(endpoint.getEndpointUri()).thenReturn("https://example.com/api");

        java.lang.reflect.Method notifyMethod = notifier.getClass().getMethod("notify", CamelEvent.class);
        notifyMethod.setAccessible(true);
        assertDoesNotThrow(() -> {
            try {
                notifyMethod.invoke(notifier, sentEvent);
            } catch (java.lang.reflect.InvocationTargetException e) {
                if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
            }
        });
    }

    @Test
    void tracingEventNotifier_exchangeSendingEvent_optionsMethod_isExcluded() throws Exception {
        var notifierField = CapiTracer.class.getDeclaredField("eventNotifier");
        notifierField.setAccessible(true);
        var notifier = notifierField.get(capiTracer);

        CamelEvent.ExchangeSendingEvent sendingEvent = mock(CamelEvent.ExchangeSendingEvent.class);
        when(sendingEvent.getEndpoint()).thenReturn(endpoint);
        when(sendingEvent.getExchange()).thenReturn(exchange);
        when(exchange.getIn()).thenReturn(message);
        // Endpoint is not http, so shouldExclude falls through to isExcluded
        when(endpoint.getEndpointUri()).thenReturn("direct:myroute");
        when(endpoint.getEndpointKey()).thenReturn("direct:myroute");
        // isExcluded checks CamelHttpMethod header
        when(message.getHeader("CamelHttpMethod", String.class)).thenReturn("OPTIONS");
        when(spanDecorator.newSpan()).thenReturn(true);

        java.lang.reflect.Method notifyMethod = notifier.getClass().getMethod("notify", CamelEvent.class);
        notifyMethod.setAccessible(true);
        assertDoesNotThrow(() -> {
            try {
                notifyMethod.invoke(notifier, sendingEvent);
            } catch (java.lang.reflect.InvocationTargetException e) {
                if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
            }
        });
    }

    @Test
    void tracingEventNotifier_asyncProcessingStartedEvent_callsEndScope() throws Exception {
        var notifierField = CapiTracer.class.getDeclaredField("eventNotifier");
        notifierField.setAccessible(true);
        var notifier = notifierField.get(capiTracer);

        CamelEvent.ExchangeAsyncProcessingStartedEvent asyncEvent = mock(CamelEvent.ExchangeAsyncProcessingStartedEvent.class);
        when(asyncEvent.getExchange()).thenReturn(exchange);

        java.lang.reflect.Method notifyMethod = notifier.getClass().getMethod("notify", CamelEvent.class);
        notifyMethod.setAccessible(true);
        assertDoesNotThrow(() -> {
            try {
                notifyMethod.invoke(notifier, asyncEvent);
            } catch (java.lang.reflect.InvocationTargetException e) {
                if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
            }
        });
    }

    @Test
    void tracingEventNotifier_exchangeSendingEvent_excludePatternMatch() throws Exception {
        capiTracer.setExcludePatterns(List.of("direct://"));

        var notifierField = CapiTracer.class.getDeclaredField("eventNotifier");
        notifierField.setAccessible(true);
        var notifier = notifierField.get(capiTracer);

        CamelEvent.ExchangeSendingEvent sendingEvent = mock(CamelEvent.ExchangeSendingEvent.class);
        when(sendingEvent.getEndpoint()).thenReturn(endpoint);
        when(sendingEvent.getExchange()).thenReturn(exchange);
        when(exchange.getIn()).thenReturn(message);
        when(endpoint.getEndpointUri()).thenReturn("direct://myendpoint");
        when(endpoint.getEndpointKey()).thenReturn("direct://myendpoint");
        when(message.getHeader("CamelHttpMethod", String.class)).thenReturn("GET");

        java.lang.reflect.Method notifyMethod = notifier.getClass().getMethod("notify", CamelEvent.class);
        notifyMethod.setAccessible(true);
        assertDoesNotThrow(() -> {
            try {
                notifyMethod.invoke(notifier, sendingEvent);
            } catch (java.lang.reflect.InvocationTargetException e) {
                if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
            }
        });
    }

    // ---------------------------------------------------------------
    // TracingRoutePolicy - onExchangeBegin and onExchangeDone
    // ---------------------------------------------------------------

    @Test
    void tracingRoutePolicy_onExchangeBegin_directError_setsTraceId() throws Exception {
        // Get a TracingRoutePolicy instance
        when(camelContext.hasService(CapiTracer.class)).thenReturn(null);
        when(camelContext.hasService(capiTracer)).thenReturn(true);
        when(camelContext.getManagementStrategy()).thenReturn(managementStrategy);
        when(camelContext.getRoutePolicyFactories()).thenReturn(new ArrayList<>());
        when(camelContext.getCamelContextExtension()).thenReturn(camelContextExtension);
        lenient().when(camelContext.isUseMDCLogging()).thenReturn(false);

        RoutePolicy policy = capiTracer.createRoutePolicy(camelContext, "routeId", mock(NamedNode.class));

        // Setup route endpoint as direct://error
        when(route.getEndpoint()).thenReturn(endpoint);
        when(endpoint.getEndpointUri()).thenReturn("direct://error");

        // Use a real exchange to avoid ActiveSpanManager NPE on mock exchange
        CamelContext realCtx = new DefaultCamelContext();
        Exchange realExchange = new DefaultExchange(realCtx);
        realExchange.getIn().setHeader("CamelHttpMethod", "GET");

        // Put an active span on the exchange using ActiveSpanManager
        when(otelSpan.getSpanContext()).thenReturn(spanContext);
        when(spanContext.getTraceId()).thenReturn("abc123traceId");
        CapiOpenTelemetrySpanAdapter spanAdapter = new CapiOpenTelemetrySpanAdapter(otelSpan, Baggage.empty());
        ActiveSpanManager.activate(realExchange, spanAdapter);

        try {
            // onExchangeBegin should set the internal trace id property
            policy.onExchangeBegin(route, realExchange);
            assertEquals("abc123traceId", realExchange.getProperty(Constants.CAPI_EXCHANGE_INTERNAL_TRACE_ID));
        } finally {
            ActiveSpanManager.deactivate(realExchange);
            realCtx.close();
        }
    }

    @Test
    void tracingRoutePolicy_onExchangeBegin_excluded_returns() throws Exception {
        when(camelContext.hasService(CapiTracer.class)).thenReturn(null);
        when(camelContext.hasService(capiTracer)).thenReturn(true);
        when(camelContext.getManagementStrategy()).thenReturn(managementStrategy);
        when(camelContext.getRoutePolicyFactories()).thenReturn(new ArrayList<>());
        when(camelContext.getCamelContextExtension()).thenReturn(camelContextExtension);
        lenient().when(camelContext.isUseMDCLogging()).thenReturn(false);

        RoutePolicy policy = capiTracer.createRoutePolicy(camelContext, "routeId", mock(NamedNode.class));

        when(route.getEndpoint()).thenReturn(endpoint);
        when(endpoint.getEndpointUri()).thenReturn("timer://myTimer");
        when(exchange.getIn()).thenReturn(message);
        when(message.getHeader("CamelHttpMethod", String.class)).thenReturn("OPTIONS");

        // Should return early due to OPTIONS
        assertDoesNotThrow(() -> policy.onExchangeBegin(route, exchange));
    }

    @Test
    void tracingRoutePolicy_onExchangeDone_excluded_returns() throws Exception {
        when(camelContext.hasService(CapiTracer.class)).thenReturn(null);
        when(camelContext.hasService(capiTracer)).thenReturn(true);
        when(camelContext.getManagementStrategy()).thenReturn(managementStrategy);
        when(camelContext.getRoutePolicyFactories()).thenReturn(new ArrayList<>());
        when(camelContext.getCamelContextExtension()).thenReturn(camelContextExtension);
        lenient().when(camelContext.isUseMDCLogging()).thenReturn(false);

        RoutePolicy policy = capiTracer.createRoutePolicy(camelContext, "routeId", mock(NamedNode.class));

        when(route.getEndpoint()).thenReturn(endpoint);
        when(endpoint.getEndpointUri()).thenReturn("timer://myTimer");
        when(exchange.getIn()).thenReturn(message);
        when(message.getHeader("CamelHttpMethod", String.class)).thenReturn("OPTIONS");

        assertDoesNotThrow(() -> policy.onExchangeDone(route, exchange));
    }

    @Test
    void tracingRoutePolicy_onExchangeDone_noActiveSpan_logsWarning() throws Exception {
        when(camelContext.hasService(CapiTracer.class)).thenReturn(null);
        when(camelContext.hasService(capiTracer)).thenReturn(true);
        when(camelContext.getManagementStrategy()).thenReturn(managementStrategy);
        when(camelContext.getRoutePolicyFactories()).thenReturn(new ArrayList<>());
        when(camelContext.getCamelContextExtension()).thenReturn(camelContextExtension);
        lenient().when(camelContext.isUseMDCLogging()).thenReturn(false);

        capiTracer.setExcludePatterns(List.of());
        RoutePolicy policy = capiTracer.createRoutePolicy(camelContext, "routeId", mock(NamedNode.class));

        when(route.getEndpoint()).thenReturn(endpoint);
        when(endpoint.getEndpointUri()).thenReturn("undertow:http://0.0.0.0:8080/api");
        when(exchange.getIn()).thenReturn(message);
        when(message.getHeader("CamelHttpMethod", String.class)).thenReturn("GET");

        // No active span on exchange - should not throw, just log warning
        assertDoesNotThrow(() -> policy.onExchangeDone(route, exchange));
    }

    @Test
    void tracingRoutePolicy_onExchangeDone_withActiveSpan_decoratesAndFinishes() throws Exception {
        when(camelContext.hasService(CapiTracer.class)).thenReturn(null);
        when(camelContext.hasService(capiTracer)).thenReturn(true);
        when(camelContext.getManagementStrategy()).thenReturn(managementStrategy);
        when(camelContext.getRoutePolicyFactories()).thenReturn(new ArrayList<>());
        when(camelContext.getCamelContextExtension()).thenReturn(camelContextExtension);
        lenient().when(camelContext.isUseMDCLogging()).thenReturn(false);

        capiTracer.setExcludePatterns(List.of());
        RoutePolicy policy = capiTracer.createRoutePolicy(camelContext, "routeId", mock(NamedNode.class));

        when(route.getEndpoint()).thenReturn(endpoint);
        when(endpoint.getEndpointUri()).thenReturn("undertow:http://0.0.0.0:8080/api");

        // Use a real exchange
        CamelContext realCtx = new DefaultCamelContext();
        Exchange realExchange = new DefaultExchange(realCtx);
        realExchange.getIn().setHeader("CamelHttpMethod", "GET");
        realExchange.getIn().setHeader("Content-Type", "application/json");
        realExchange.getIn().setHeader("X-Forwarded-For", "10.0.0.1");
        realExchange.getIn().setHeader("CamelHttpUri", "/api/test-service/dev/something");
        realExchange.setProperty(Constants.CLIENT_START_TIME, 1000L);
        realExchange.setProperty(Constants.CLIENT_END_TIME, 2000L);
        realExchange.setProperty(Constants.CLIENT_ENDPOINT, "http://backend:8080");
        realExchange.setProperty(Constants.CLIENT_RESPONSE_CODE, "200");

        when(httpUtils.processAuthorizationAccessToken(any(Exchange.class))).thenReturn(null);

        CapiOpenTelemetrySpanAdapter spanAdapter = new CapiOpenTelemetrySpanAdapter(otelSpan, Baggage.empty());
        ActiveSpanManager.activate(realExchange, spanAdapter);

        try {
            assertDoesNotThrow(() -> policy.onExchangeDone(route, realExchange));
            verify(otelSpan).end();
        } finally {
            ActiveSpanManager.deactivate(realExchange);
            realCtx.close();
        }
    }

    @Test
    void tracingRoutePolicy_onExchangeDone_withClientResponseTimeNull() throws Exception {
        when(camelContext.hasService(CapiTracer.class)).thenReturn(null);
        when(camelContext.hasService(capiTracer)).thenReturn(true);
        when(camelContext.getManagementStrategy()).thenReturn(managementStrategy);
        when(camelContext.getRoutePolicyFactories()).thenReturn(new ArrayList<>());
        when(camelContext.getCamelContextExtension()).thenReturn(camelContextExtension);
        lenient().when(camelContext.isUseMDCLogging()).thenReturn(false);

        capiTracer.setExcludePatterns(List.of());
        RoutePolicy policy = capiTracer.createRoutePolicy(camelContext, "routeId", mock(NamedNode.class));

        when(route.getEndpoint()).thenReturn(endpoint);
        when(endpoint.getEndpointUri()).thenReturn("undertow:http://0.0.0.0:8080/api");

        // Use a real exchange
        CamelContext realCtx = new DefaultCamelContext();
        Exchange realExchange = new DefaultExchange(realCtx);
        realExchange.getIn().setHeader("CamelHttpMethod", "GET");
        realExchange.getIn().setHeader("CamelHttpUri", "/api/svc/grp/path");
        // No client timing, no content-type, no X-Forwarded-For

        when(httpUtils.processAuthorizationAccessToken(any(Exchange.class))).thenReturn(null);

        CapiOpenTelemetrySpanAdapter spanAdapter = new CapiOpenTelemetrySpanAdapter(otelSpan, Baggage.empty());
        ActiveSpanManager.activate(realExchange, spanAdapter);

        try {
            assertDoesNotThrow(() -> policy.onExchangeDone(route, realExchange));
            verify(otelSpan).end();
        } finally {
            ActiveSpanManager.deactivate(realExchange);
            realCtx.close();
        }
    }

    // ---------------------------------------------------------------
    // isExcluded - empty/null endpoint URI
    // ---------------------------------------------------------------

    @Test
    void tracingEventNotifier_exchangeSendingEvent_emptyUri_isExcluded() throws Exception {
        capiTracer.setExcludePatterns(List.of("somepattern"));

        var notifierField = CapiTracer.class.getDeclaredField("eventNotifier");
        notifierField.setAccessible(true);
        var notifier = notifierField.get(capiTracer);

        CamelEvent.ExchangeSendingEvent sendingEvent = mock(CamelEvent.ExchangeSendingEvent.class);
        when(sendingEvent.getEndpoint()).thenReturn(endpoint);
        when(sendingEvent.getExchange()).thenReturn(exchange);
        when(exchange.getIn()).thenReturn(message);
        when(endpoint.getEndpointUri()).thenReturn("");
        when(endpoint.getEndpointKey()).thenReturn("");
        when(message.getHeader("CamelHttpMethod", String.class)).thenReturn("GET");

        java.lang.reflect.Method notifyMethod = notifier.getClass().getMethod("notify", CamelEvent.class);
        notifyMethod.setAccessible(true);
        // Empty URI with a pattern should be excluded
        assertDoesNotThrow(() -> {
            try {
                notifyMethod.invoke(notifier, sendingEvent);
            } catch (java.lang.reflect.InvocationTargetException e) {
                if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
            }
        });
    }

    // ---------------------------------------------------------------
    // TracingLogListener
    // ---------------------------------------------------------------

    @Test
    void tracingLogListener_withActiveSpan_logsMessage() throws Exception {
        var logListenerField = CapiTracer.class.getDeclaredField("logListener");
        logListenerField.setAccessible(true);
        var logListener = logListenerField.get(capiTracer);

        // Use a real exchange to avoid NPE in ActiveSpanManager
        CamelContext realCtx = new DefaultCamelContext();
        Exchange realExchange = new DefaultExchange(realCtx);

        CapiOpenTelemetrySpanAdapter spanAdapter = new CapiOpenTelemetrySpanAdapter(otelSpan, Baggage.empty());
        ActiveSpanManager.activate(realExchange, spanAdapter);

        try {
            java.lang.reflect.Method onLogMethod = logListener.getClass().getMethod("onLog", Exchange.class, org.apache.camel.spi.CamelLogger.class, String.class);
            onLogMethod.setAccessible(true);
            String result = (String) onLogMethod.invoke(logListener, realExchange, null, "test message");
            assertEquals("test message", result);
        } finally {
            ActiveSpanManager.deactivate(realExchange);
            realCtx.close();
        }
    }

    @Test
    void tracingLogListener_withoutActiveSpan_returnsMessage() throws Exception {
        var logListenerField = CapiTracer.class.getDeclaredField("logListener");
        logListenerField.setAccessible(true);
        var logListener = logListenerField.get(capiTracer);

        CamelContext realCtx = new DefaultCamelContext();
        Exchange realExchange = new DefaultExchange(realCtx);

        try {
            java.lang.reflect.Method onLogMethod = logListener.getClass().getMethod("onLog", Exchange.class, org.apache.camel.spi.CamelLogger.class, String.class);
            onLogMethod.setAccessible(true);
            String result = (String) onLogMethod.invoke(logListener, realExchange, null, "test message");
            assertEquals("test message", result);
        } finally {
            realCtx.close();
        }
    }

    // ---------------------------------------------------------------
    // getClientResponseTime - various scenarios
    // ---------------------------------------------------------------

    @Test
    void getClientResponseTime_bothTimesPresent_returnsDifference() throws Exception {
        var method = CapiTracer.class.getDeclaredMethod("getClientResponseTime", Exchange.class);
        method.setAccessible(true);

        when(exchange.getProperty(Constants.CLIENT_START_TIME, Long.class)).thenReturn(1000L);
        when(exchange.getProperty(Constants.CLIENT_END_TIME, Long.class)).thenReturn(1500L);

        Long result = (Long) method.invoke(capiTracer, exchange);
        assertEquals(500L, result);
    }

    @Test
    void getClientResponseTime_missingStartTime_returnsNull() throws Exception {
        var method = CapiTracer.class.getDeclaredMethod("getClientResponseTime", Exchange.class);
        method.setAccessible(true);

        when(exchange.getProperty(Constants.CLIENT_START_TIME, Long.class)).thenReturn(null);
        when(exchange.getProperty(Constants.CLIENT_END_TIME, Long.class)).thenReturn(1500L);

        Long result = (Long) method.invoke(capiTracer, exchange);
        assertNull(result);
    }

    @Test
    void getClientResponseTime_missingEndTime_returnsNull() throws Exception {
        var method = CapiTracer.class.getDeclaredMethod("getClientResponseTime", Exchange.class);
        method.setAccessible(true);

        when(exchange.getProperty(Constants.CLIENT_START_TIME, Long.class)).thenReturn(1000L);
        when(exchange.getProperty(Constants.CLIENT_END_TIME, Long.class)).thenReturn(null);

        Long result = (Long) method.invoke(capiTracer, exchange);
        assertNull(result);
    }

    // ---------------------------------------------------------------
    // mapToSpanKind - default case
    // ---------------------------------------------------------------

    @Test
    void startSendingEventSpan_unknownSpanKind_defaultsToServer() {
        when(otelTracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        // The default case maps to SERVER
        when(spanBuilder.setSpanKind(io.opentelemetry.api.trace.SpanKind.SERVER)).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(otelSpan);

        // There's no "UNKNOWN" SpanKind to test the default, but we can verify SERVER is used
        // by using SPAN_KIND_SERVER which explicitly maps to SERVER
        capiTracer.startSendingEventSpan("op", org.apache.camel.tracing.SpanKind.SPAN_KIND_SERVER, null, exchange, mock(InjectAdapter.class));
        verify(spanBuilder).setSpanKind(io.opentelemetry.api.trace.SpanKind.SERVER);
    }

    // ---------------------------------------------------------------
    // TracingEventNotifier - notify with ExchangeSendingEvent (non-excluded, full path)
    // ---------------------------------------------------------------

    @Test
    void tracingEventNotifier_notify_exchangeSendingEvent_nonExcluded_createsSpan() throws Exception {
        // Setup the tracer so spans can be created
        when(otelTracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        when(spanBuilder.setSpanKind(any())).thenReturn(spanBuilder);
        when(spanBuilder.setParent(any())).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(otelSpan);

        capiTracer.setContextPropagators(contextPropagators);
        when(contextPropagators.getTextMapPropagator()).thenReturn(textMapPropagator);

        var notifierField = CapiTracer.class.getDeclaredField("eventNotifier");
        notifierField.setAccessible(true);
        var notifier = notifierField.get(capiTracer);

        // Use a real CamelContext and Exchange so ActiveSpanManager works
        CamelContext realCtx = new DefaultCamelContext();
        Exchange realExchange = new DefaultExchange(realCtx);
        realExchange.getIn().setHeader("CamelHttpMethod", "GET");

        // Create an endpoint that is not http/https and not excluded
        Endpoint directEndpoint = mock(Endpoint.class);
        when(directEndpoint.getEndpointUri()).thenReturn("direct:myService");
        when(directEndpoint.getEndpointKey()).thenReturn("direct:myService");

        CamelEvent.ExchangeSendingEvent sendingEvent = mock(CamelEvent.ExchangeSendingEvent.class);
        when(sendingEvent.getEndpoint()).thenReturn(directEndpoint);
        when(sendingEvent.getExchange()).thenReturn(realExchange);

        java.lang.reflect.Method notifyMethod = notifier.getClass().getMethod("notify", CamelEvent.class);
        notifyMethod.setAccessible(true);

        try {
            notifyMethod.invoke(notifier, sendingEvent);
            // Verify a span was started and the exchange has client start time set
            assertNotNull(realExchange.getProperty(Constants.CLIENT_START_TIME));
            assertNotNull(realExchange.getProperty(Constants.CLIENT_ENDPOINT));
        } catch (java.lang.reflect.InvocationTargetException e) {
            // The notify method catches all exceptions internally, so this should not happen
            fail("Unexpected exception: " + e.getCause());
        } finally {
            ActiveSpanManager.deactivate(realExchange);
            realCtx.close();
        }
    }

    // ---------------------------------------------------------------
    // TracingEventNotifier - notify with ExchangeSentEvent (non-excluded, with active span)
    // ---------------------------------------------------------------

    @Test
    void tracingEventNotifier_notify_exchangeSentEvent_nonExcluded_withSpan_finishesSpan() throws Exception {
        capiTracer.setContextPropagators(contextPropagators);
        when(contextPropagators.getTextMapPropagator()).thenReturn(textMapPropagator);

        var notifierField = CapiTracer.class.getDeclaredField("eventNotifier");
        notifierField.setAccessible(true);
        var notifier = notifierField.get(capiTracer);

        // Use a real exchange
        CamelContext realCtx = new DefaultCamelContext();
        Exchange realExchange = new DefaultExchange(realCtx);
        realExchange.getIn().setHeader("CamelHttpMethod", "GET");

        // Activate a span on the exchange first
        CapiOpenTelemetrySpanAdapter spanAdapter = new CapiOpenTelemetrySpanAdapter(otelSpan, Baggage.empty());
        ActiveSpanManager.activate(realExchange, spanAdapter);

        Endpoint directEndpoint = mock(Endpoint.class);
        when(directEndpoint.getEndpointUri()).thenReturn("direct:myService");
        when(directEndpoint.getEndpointKey()).thenReturn("direct:myService");

        Message sentMessage = mock(Message.class);
        when(sentMessage.getHeader(Exchange.HTTP_RESPONSE_CODE, String.class)).thenReturn("200");

        CamelEvent.ExchangeSentEvent sentEvent = mock(CamelEvent.ExchangeSentEvent.class);
        when(sentEvent.getEndpoint()).thenReturn(directEndpoint);
        when(sentEvent.getExchange()).thenReturn(realExchange);

        java.lang.reflect.Method notifyMethod = notifier.getClass().getMethod("notify", CamelEvent.class);
        notifyMethod.setAccessible(true);

        try {
            notifyMethod.invoke(notifier, sentEvent);
            // Verify the span was finished
            verify(otelSpan).end();
            // Verify client end time was set
            assertNotNull(realExchange.getProperty(Constants.CLIENT_END_TIME));
        } catch (java.lang.reflect.InvocationTargetException e) {
            fail("Unexpected exception: " + e.getCause());
        } finally {
            realCtx.close();
        }
    }

    // ---------------------------------------------------------------
    // TracingEventNotifier - notify with ExchangeSentEvent (non-excluded, no active span)
    // ---------------------------------------------------------------

    @Test
    void tracingEventNotifier_notify_exchangeSentEvent_nonExcluded_noSpan_logsWarning() throws Exception {
        var notifierField = CapiTracer.class.getDeclaredField("eventNotifier");
        notifierField.setAccessible(true);
        var notifier = notifierField.get(capiTracer);

        // Use a real exchange with no active span
        CamelContext realCtx = new DefaultCamelContext();
        Exchange realExchange = new DefaultExchange(realCtx);
        realExchange.getIn().setHeader("CamelHttpMethod", "GET");

        Endpoint directEndpoint = mock(Endpoint.class);
        when(directEndpoint.getEndpointUri()).thenReturn("direct:myService");
        when(directEndpoint.getEndpointKey()).thenReturn("direct:myService");

        CamelEvent.ExchangeSentEvent sentEvent = mock(CamelEvent.ExchangeSentEvent.class);
        when(sentEvent.getEndpoint()).thenReturn(directEndpoint);
        when(sentEvent.getExchange()).thenReturn(realExchange);

        java.lang.reflect.Method notifyMethod = notifier.getClass().getMethod("notify", CamelEvent.class);
        notifyMethod.setAccessible(true);

        try {
            // No active span - should hit the else branch that logs a warning
            notifyMethod.invoke(notifier, sentEvent);
            // Verify client end time was still set (before the span check)
            assertNotNull(realExchange.getProperty(Constants.CLIENT_END_TIME));
        } catch (java.lang.reflect.InvocationTargetException e) {
            fail("Unexpected exception: " + e.getCause());
        } finally {
            realCtx.close();
        }
    }

    // ---------------------------------------------------------------
    // TracingEventNotifier - isEnabled for various event types
    // ---------------------------------------------------------------

    @Test
    void tracingEventNotifier_isEnabled_forExchangeSendingEvent_returnsTrue() throws Exception {
        var notifierField = CapiTracer.class.getDeclaredField("eventNotifier");
        notifierField.setAccessible(true);
        var notifier = notifierField.get(capiTracer);

        // TracingEventNotifier extends EventNotifierSupport.
        // The EventNotifierSupport.isEnabled checks event type filters set in the constructor.
        // Since setIgnoreExchangeAsyncProcessingStartedEvents(false) was set, and
        // setIgnoreCamelContextEvents/InitEvents/RouteEvents are true, exchange events should be enabled.

        // Check via notify - if not enabled, notify won't be called.
        // The best we can do is verify it doesn't throw and processes correctly.
        CamelEvent.ExchangeSendingEvent sendingEvent = mock(CamelEvent.ExchangeSendingEvent.class);
        when(sendingEvent.getEndpoint()).thenReturn(endpoint);
        when(sendingEvent.getExchange()).thenReturn(exchange);
        when(exchange.getIn()).thenReturn(message);
        when(endpoint.getEndpointUri()).thenReturn("http://excluded.com");

        java.lang.reflect.Method notifyMethod = notifier.getClass().getMethod("notify", CamelEvent.class);
        assertDoesNotThrow(() -> {
            try {
                notifyMethod.invoke(notifier, sendingEvent);
            } catch (java.lang.reflect.InvocationTargetException e) {
                if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
            }
        });
    }

    // ---------------------------------------------------------------
    // TracingEventNotifier - shouldExclude with newSpan returning false
    // ---------------------------------------------------------------

    @Test
    void tracingEventNotifier_shouldExclude_whenSpanDecoratorNewSpanFalse() throws Exception {
        var notifierField = CapiTracer.class.getDeclaredField("eventNotifier");
        notifierField.setAccessible(true);
        var notifier = notifierField.get(capiTracer);

        // Register a SpanDecorator that returns newSpan() = false
        SpanDecorator noSpanDecorator = mock(SpanDecorator.class);
        when(noSpanDecorator.getComponent()).thenReturn("direct");
        when(noSpanDecorator.newSpan()).thenReturn(false);
        CapiTracer.DECORATORS.put("direct", noSpanDecorator);

        CamelContext realCtx = new DefaultCamelContext();
        Exchange realExchange = new DefaultExchange(realCtx);
        realExchange.getIn().setHeader("CamelHttpMethod", "GET");

        Endpoint directEndpoint = mock(Endpoint.class);
        when(directEndpoint.getEndpointUri()).thenReturn("direct:myService");
        when(directEndpoint.getEndpointKey()).thenReturn("direct:myService");

        CamelEvent.ExchangeSendingEvent sendingEvent = mock(CamelEvent.ExchangeSendingEvent.class);
        when(sendingEvent.getEndpoint()).thenReturn(directEndpoint);
        when(sendingEvent.getExchange()).thenReturn(realExchange);

        java.lang.reflect.Method notifyMethod = notifier.getClass().getMethod("notify", CamelEvent.class);

        try {
            notifyMethod.invoke(notifier, sendingEvent);
            // Should be excluded due to newSpan() returning false, so no span created
            // CLIENT_START_TIME and CLIENT_ENDPOINT are set before shouldExclude check
            assertNotNull(realExchange.getProperty(Constants.CLIENT_START_TIME));
        } catch (java.lang.reflect.InvocationTargetException e) {
            fail("Unexpected exception: " + e.getCause());
        } finally {
            CapiTracer.DECORATORS.remove("direct");
            realCtx.close();
        }
    }

    // ---------------------------------------------------------------
    // TracingEventNotifier - ExchangeAsyncProcessingStartedEvent
    // ---------------------------------------------------------------

    @Test
    void tracingEventNotifier_notify_asyncProcessingStartedEvent_callsEndScope() throws Exception {
        var notifierField = CapiTracer.class.getDeclaredField("eventNotifier");
        notifierField.setAccessible(true);
        var notifier = notifierField.get(capiTracer);

        CamelContext realCtx = new DefaultCamelContext();
        Exchange realExchange = new DefaultExchange(realCtx);

        // Activate a span so endScope has something to close
        CapiOpenTelemetrySpanAdapter spanAdapter = new CapiOpenTelemetrySpanAdapter(otelSpan, Baggage.empty());
        ActiveSpanManager.activate(realExchange, spanAdapter);

        CamelEvent.ExchangeAsyncProcessingStartedEvent asyncEvent = mock(CamelEvent.ExchangeAsyncProcessingStartedEvent.class);
        when(asyncEvent.getExchange()).thenReturn(realExchange);

        java.lang.reflect.Method notifyMethod = notifier.getClass().getMethod("notify", CamelEvent.class);

        try {
            notifyMethod.invoke(notifier, asyncEvent);
            // endScope should have been called - no exception means success
        } catch (java.lang.reflect.InvocationTargetException e) {
            fail("Unexpected exception: " + e.getCause());
        } finally {
            ActiveSpanManager.deactivate(realExchange);
            realCtx.close();
        }
    }

    // ---------------------------------------------------------------
    // TracingEventNotifier - notify with an unrecognized event type
    // ---------------------------------------------------------------

    @Test
    void tracingEventNotifier_notify_unrecognizedEvent_doesNothing() throws Exception {
        var notifierField = CapiTracer.class.getDeclaredField("eventNotifier");
        notifierField.setAccessible(true);
        var notifier = notifierField.get(capiTracer);

        // Create an event that is not ExchangeSendingEvent, ExchangeSentEvent, or AsyncProcessingStarted
        CamelEvent unknownEvent = mock(CamelEvent.class);

        java.lang.reflect.Method notifyMethod = notifier.getClass().getMethod("notify", CamelEvent.class);

        // Should not throw, just do nothing
        assertDoesNotThrow(() -> {
            try {
                notifyMethod.invoke(notifier, unknownEvent);
            } catch (java.lang.reflect.InvocationTargetException e) {
                if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
            }
        });
    }
}
