package io.surisoft.capi.tracer;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.surisoft.capi.exception.AuthorizationException;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.HttpUtils;
import org.apache.camel.*;
import org.apache.camel.api.management.ManagedAttribute;
import org.apache.camel.opentelemetry.NoopTracingStrategy;
import org.apache.camel.opentelemetry.SpanCustomizer;
import org.apache.camel.opentelemetry.propagators.OpenTelemetryGetter;
import org.apache.camel.opentelemetry.propagators.OpenTelemetrySetter;
import org.apache.camel.spi.*;
import org.apache.camel.support.CamelContextHelper;
import org.apache.camel.support.DefaultEndpoint;
import org.apache.camel.support.EventNotifierSupport;
import org.apache.camel.support.RoutePolicySupport;
import org.apache.camel.support.service.ServiceHelper;
import org.apache.camel.support.service.ServiceSupport;
import org.apache.camel.tracing.*;
import org.apache.camel.tracing.decorators.AbstractInternalSpanDecorator;
import org.apache.camel.util.ObjectHelper;
import org.apache.camel.util.StringHelper;
import org.cache2k.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.ParseException;
import java.util.*;

public class CapiTracer extends ServiceSupport implements CamelTracingService, RoutePolicyFactory, StaticService {

    protected static final Map<String, SpanDecorator> DECORATORS = new HashMap<>();
    private static final Logger LOG = LoggerFactory.getLogger(CapiTracer.class);

    private io.opentelemetry.api.trace.Tracer tracer;
    private SpanCustomizer spanCustomizer;
    private ContextPropagators contextPropagators;
    private boolean traceProcessors;
    protected boolean encoding;
    private final TracingLogListener logListener = new TracingLogListener();
    private final TracingEventNotifier eventNotifier = new TracingEventNotifier();
    private List<String> excludePatterns = new ArrayList<>();
    private CapiOpenTelemetryTracingStrategy tracingStrategy;
    private CamelContext camelContext;
    private final HttpUtils httpUtils;
    private final String capiInstanceName;
    private final Cache<String, io.surisoft.capi.schema.Service> serviceCache;

    static {
        ServiceLoader.load(SpanDecorator.class).forEach(d -> {
            SpanDecorator existing = DECORATORS.get(d.getComponent());
            // Add span decorator if no existing decorator for the component,
            // or if derived from the existing decorator's class, allowing
            // custom decorators to be added if they extend the standard
            // decorators
            if (existing == null || existing.getClass().isInstance(d)) {
                DECORATORS.put(d.getComponent(), d);
            }
        });
    }

    public CapiTracer(HttpUtils httpUtils, String capiInstanceName, Cache<String, io.surisoft.capi.schema.Service> serviceCache, Tracer tracer) {
        this.httpUtils = httpUtils;
        this.capiInstanceName = capiInstanceName;
        this.serviceCache = serviceCache;
        this.tracer = tracer;
    }

    public void initTracer() {
        if (tracer == null) {
            tracer = CamelContextHelper.findSingleByType(getCamelContext(), io.opentelemetry.api.trace.Tracer.class);
        }
        if (tracer == null) {
            // GlobalOpenTelemetry.get() is always NotNull, falls back to OpenTelemetry.noop()
            String instrumentationName = "CAPI";
            tracer = GlobalOpenTelemetry.get().getTracer(instrumentationName);
        }
        if (traceProcessors && (getTracingStrategy() == null
                || getTracingStrategy().getClass().isAssignableFrom(NoopTracingStrategy.class))) {
            CapiOpenTelemetryTracingStrategy tracingStrategy = new CapiOpenTelemetryTracingStrategy(this);
            tracingStrategy.setPropagateContext(true);
            setTracingStrategy(tracingStrategy);
        }
        if (spanCustomizer == null) {
            spanCustomizer = CamelContextHelper.findSingleByType(getCamelContext(), SpanCustomizer.class);
        }
    }

    public SpanAdapter startSendingEventSpan(
            String operationName, SpanKind kind, SpanAdapter parent, Exchange exchange, InjectAdapter injectAdapter) {
        Baggage baggage = null;
        SpanBuilder builder = tracer.spanBuilder(operationName).setSpanKind(mapToSpanKind(kind));
        if (parent != null) {
            CapiOpenTelemetrySpanAdapter oTelSpanWrapper = (CapiOpenTelemetrySpanAdapter) parent;
            Span parentSpan = oTelSpanWrapper.getOpenTelemetrySpan();
            baggage = oTelSpanWrapper.getBaggage();
            builder = builder.setParent(Context.current().with(parentSpan));
        }
        if (spanCustomizer != null && spanCustomizer.isEnabled(operationName, exchange)) {
            spanCustomizer.customize(builder, operationName, exchange);
        }
        return new CapiOpenTelemetrySpanAdapter(builder.startSpan(), baggage);
    }

    public void initContextPropagators() {
        if (contextPropagators == null) {
            contextPropagators = CamelContextHelper.findSingleByType(getCamelContext(), ContextPropagators.class);
        }
        if (contextPropagators == null) {
            // GlobalOpenTelemetry.get() is always NotNull, falls back to OpenTelemetry.noop()
            contextPropagators = GlobalOpenTelemetry.get().getPropagators();
        }
    }

    public SpanAdapter startExchangeBeginSpan(Exchange exchange, SpanDecorator sd, String operationName, SpanKind kind, SpanAdapter parent) {
        SpanBuilder builder = tracer.spanBuilder(operationName);
        Baggage baggage;
        if (parent != null) {
            CapiOpenTelemetrySpanAdapter spanFromExchange = (CapiOpenTelemetrySpanAdapter) parent;
            builder = builder.setParent(Context.current().with(spanFromExchange.getOpenTelemetrySpan()));
            baggage = spanFromExchange.getBaggage();
        } else {
            ExtractAdapter adapter = sd.getExtractAdapter(exchange.getIn().getHeaders(), encoding);
            Context ctx = getContextPropagators().getTextMapPropagator().extract(Context.current(), adapter,
                    new OpenTelemetryGetter(adapter));
            Span span = Span.fromContext(ctx);
            baggage = Baggage.fromContext(ctx);
            if (span != null && span.getSpanContext().isValid()) {
                builder.setParent(ctx).setSpanKind(mapToSpanKind(sd.getReceiverSpanKind()));
            } else if (!(sd instanceof AbstractInternalSpanDecorator)) {
                builder.setSpanKind(mapToSpanKind(sd.getReceiverSpanKind()));
            }
        }
        if (spanCustomizer != null && spanCustomizer.isEnabled(operationName, exchange)) {
            spanCustomizer.customize(builder, operationName, exchange);
        }
        return new CapiOpenTelemetrySpanAdapter(builder.startSpan(), baggage);
    }

    public void finishSpan(SpanAdapter span) {
        CapiOpenTelemetrySpanAdapter openTracingSpanWrapper = (CapiOpenTelemetrySpanAdapter) span;
        openTracingSpanWrapper.getOpenTelemetrySpan().end();
    }

    public void inject(SpanAdapter span, InjectAdapter adapter) {
        CapiOpenTelemetrySpanAdapter spanFromExchange = (CapiOpenTelemetrySpanAdapter) span;
        Span otelSpan = spanFromExchange.getOpenTelemetrySpan();
        Context ctx = Context.current().with(otelSpan);
        if (spanFromExchange.getBaggage() != null) {
            ctx = ctx.with(spanFromExchange.getBaggage());
        }
        getContextPropagators().getTextMapPropagator().inject(ctx, adapter, new OpenTelemetrySetter());
    }

    public CapiOpenTelemetryTracingStrategy getTracingStrategy() {
        return tracingStrategy;
    }

    public void setTracingStrategy(CapiOpenTelemetryTracingStrategy tracingStrategy) {
        this.tracingStrategy = tracingStrategy;
    }

    public void addDecorator(SpanDecorator decorator) {
        DECORATORS.put(decorator.getComponent(), decorator);
    }

    @Override
    public CamelContext getCamelContext() {
        return camelContext;
    }

    @Override
    public void setCamelContext(CamelContext camelContext) {
        this.camelContext = camelContext;
    }

    @ManagedAttribute
    public List<String> getExcludePatterns() {
        return excludePatterns;
    }

    public void setExcludePatterns(List<String> excludePatterns) {
        this.excludePatterns = excludePatterns;
    }

    @ManagedAttribute
    public boolean isEncoding() {
        return encoding;
    }

    public void setEncoding(boolean encoding) {
        this.encoding = encoding;
    }

    @Override
    public RoutePolicy createRoutePolicy(CamelContext camelContext, String routeId, NamedNode route) {
        init(camelContext);
        return new TracingRoutePolicy();
    }

    public void init(CamelContext camelContext) {
        if (hasOtherTracerType(camelContext)) {
            LOG.warn("Could not add {} tracer type. Another tracer type, {}, was already registered. " +
                            "Make sure to include only one tracing dependency type.",
                    this.getClass(),
                    camelContext.hasService(CapiTracer.class).getClass());
            return;
        }
        if (!camelContext.hasService(this)) {
            try {
                // start this service eager so we init before Camel is starting up
                camelContext.addService(this, true, true);
            } catch (Exception e) {
                throw RuntimeCamelException.wrapRuntimeCamelException(e);
            }
        }
    }

    // Check if there is any other registered Tracer.
    private boolean hasOtherTracerType(CamelContext camelContext) {
        CapiTracer t = camelContext.hasService(CapiTracer.class);
        if (t == null) {
            return false;
        }
        return !this.getClass().equals(t.getClass());
    }

    @Override
    protected void doInit() {
        ObjectHelper.notNull(camelContext, "CamelContext", this);

        camelContext.getManagementStrategy().addEventNotifier(eventNotifier);
        if (!camelContext.getRoutePolicyFactories().contains(this)) {
            camelContext.addRoutePolicyFactory(this);
        }
        camelContext.getCamelContextExtension().addLogListener(logListener);

        if (tracingStrategy != null) {
            camelContext.getCamelContextExtension().addInterceptStrategy(tracingStrategy);
        }
        initTracer();
        initContextPropagators();
        ServiceHelper.startService(eventNotifier);

        if (Boolean.TRUE.equals(camelContext.isUseMDCLogging())) {
            LOG.warn("Initialized tracing component to put trace_id and span_id into MDC. " +
                    "This is a deprecated feature and may disappear in the future. " +
                    "You should replace it with the specific MDC instrumentation provided by your tracing/telemetry SDK instead. "
                    +
                    "See the tracing component documentation to learn more about it.");
        }
    }

    @Override
    protected void doShutdown() {
        // stop event notifier
        camelContext.getManagementStrategy().removeEventNotifier(eventNotifier);
        ServiceHelper.stopService(eventNotifier);

        // remove route policy
        camelContext.getRoutePolicyFactories().remove(this);
    }

    private SpanDecorator getSpanDecorator(Endpoint endpoint) {
        SpanDecorator sd = CapiTracer.getFromUri(endpoint.getEndpointUri());
        if (sd == null && endpoint instanceof DefaultEndpoint de) {
            Component comp = de.getComponent();
            String fqn = comp.getClass().getName();
            // lookup via FQN
            sd = DECORATORS.values().stream().filter(d -> fqn.equals(d.getComponentClassName())).findFirst()
                    .orElse(null);
        }
        if (sd == null) {
            sd = SpanDecorator.DEFAULT;
        }

        return sd;
    }

    static SpanDecorator getFromUri(String uri) {
        SpanDecorator sd = null;

        String[] splitURI = StringHelper.splitOnCharacter(uri, ":", 2);
        if (splitURI[1] != null) {
            String scheme = splitURI[0];
            sd = DECORATORS.get(scheme);
        }

        return sd;
    }

    private boolean isExcluded(Exchange exchange, Endpoint endpoint) {
        if(exchange.getIn().getHeader("CamelHttpMethod", String.class) != null &&
                exchange.getIn().getHeader("CamelHttpMethod", String.class).equalsIgnoreCase("OPTIONS")) {
            return true;
        }

        String endpointUri = endpoint.getEndpointUri();

        for(String pattern : excludePatterns) {
            if(endpointUri == null || endpointUri.isEmpty()) {
                return true;
            }
            if(endpointUri.startsWith(pattern)) {
                return true;
            }
        }
        return false;
    }

    private final class TracingEventNotifier extends EventNotifierSupport {

        public TracingEventNotifier() {
            // ignore these
            setIgnoreCamelContextEvents(true);
            setIgnoreCamelContextInitEvents(true);
            setIgnoreRouteEvents(true);
            // we need also async processing started events
            setIgnoreExchangeAsyncProcessingStartedEvents(false);
        }

        @Override
        public void notify(CamelEvent event) throws Exception {
            try {
                if (event instanceof CamelEvent.ExchangeSendingEvent exchangeSendingEvent) {
                    Endpoint endpoint = exchangeSendingEvent.getEndpoint();
                    exchangeSendingEvent.getExchange().setProperty(Constants.CLIENT_START_TIME, System.currentTimeMillis());
                    exchangeSendingEvent.getExchange().setProperty(Constants.CLIENT_ENDPOINT, endpoint.getEndpointKey());

                    SpanDecorator sd = getSpanDecorator(exchangeSendingEvent.getEndpoint());
                    if (shouldExclude(sd, exchangeSendingEvent.getExchange(), exchangeSendingEvent.getEndpoint())) {
                        return;
                    }
                    SpanAdapter parent = ActiveSpanManager.getSpan(exchangeSendingEvent.getExchange());
                    InjectAdapter injectAdapter = sd.getInjectAdapter(exchangeSendingEvent.getExchange().getIn().getHeaders(), encoding);
                    SpanAdapter span = startSendingEventSpan(sd.getOperationName(exchangeSendingEvent.getExchange(), exchangeSendingEvent.getEndpoint()),
                            sd.getInitiatorSpanKind(), parent, exchangeSendingEvent.getExchange(), injectAdapter);
                    sd.pre(span, exchangeSendingEvent.getExchange(), exchangeSendingEvent.getEndpoint());
                    inject(span, injectAdapter);
                    ActiveSpanManager.activate(exchangeSendingEvent.getExchange(), span);
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Tracing: start client span: {} with parent {}", span, parent);
                    }
                } else if (event instanceof CamelEvent.ExchangeSentEvent exchangeSentEvent) {
                    exchangeSentEvent.getExchange().setProperty(Constants.CLIENT_RESPONSE_CODE, exchangeSentEvent.getExchange().getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, String.class));
                    exchangeSentEvent.getExchange().setProperty(Constants.CLIENT_END_TIME, System.currentTimeMillis());

                    SpanDecorator sd = getSpanDecorator(exchangeSentEvent.getEndpoint());

                    if (shouldExclude(sd, exchangeSentEvent.getExchange(), exchangeSentEvent.getEndpoint())) {
                        return;
                    }

                    SpanAdapter span = ActiveSpanManager.getSpan(exchangeSentEvent.getExchange());
                    if (span != null) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Tracing: stop client span: {}", span);
                        }
                        sd.post(span, exchangeSentEvent.getExchange(), exchangeSentEvent.getEndpoint());
                        ActiveSpanManager.deactivate(exchangeSentEvent.getExchange());
                        finishSpan(span);
                    } else {
                        LOG.warn("Tracing: could not find managed span for exchange: {}", exchangeSentEvent.getExchange());
                    }
                } else if (event instanceof CamelEvent.ExchangeAsyncProcessingStartedEvent eap) {
                    // no need to filter scopes here. It's ok to close a scope multiple times and
                    // implementations check if the scope being disposed is current
                    // and should not do anything if scopes don't match.
                    ActiveSpanManager.endScope(eap.getExchange());
                }
            } catch (Exception t) {
                // This exception is ignored
                LOG.warn("Tracing: Failed to capture tracing data. This exception is ignored.", t);
            }
        }

        private boolean shouldExclude(SpanDecorator sd, Exchange exchange, Endpoint endpoint) {
            String uri = endpoint.getEndpointUri();
            // Exclude outbound HTTP calls from creating client spans.
            // Timing data (start/end/response code) is already captured via exchange properties
            // and decorated onto the single server span in onExchangeDone.
            if (uri != null && (uri.startsWith("http://") || uri.startsWith("https://"))) {
                return true;
            }
            return !sd.newSpan()
                    || isExcluded(exchange, endpoint);
        }
    }

    private final class TracingRoutePolicy extends RoutePolicySupport {
        @Override
        public void onExchangeBegin(Route route, Exchange exchange) {
            try {
                if(route.getEndpoint().getEndpointUri().startsWith("direct://error")) {
                    SpanAdapter parent = ActiveSpanManager.getSpan(exchange);
                    exchange.setProperty(Constants.CAPI_EXCHANGE_INTERNAL_TRACE_ID, parent.traceId());
                }

                if (isExcluded(exchange, route.getEndpoint())) {
                    return;
                }
                SpanDecorator sd = getSpanDecorator(route.getEndpoint());
                SpanAdapter parent = ActiveSpanManager.getSpan(exchange);
                SpanAdapter span;
                span = startExchangeBeginSpan(exchange, sd, sd.getOperationName(exchange, route.getEndpoint()),
                        sd.getReceiverSpanKind(), parent);

                sd.pre(span, exchange, route.getEndpoint());
                ActiveSpanManager.activate(exchange, span);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Tracing: start server span={} with parent {}", span, parent);
                }
            } catch (Exception t) {
                // This exception is ignored
                LOG.warn("Tracing: Failed to capture tracing data. This exception is ignored.", t);
            }
        }

        @Override
        public void onExchangeDone(Route route, Exchange exchange) {
            try {
                if (isExcluded(exchange, route.getEndpoint())) {
                    return;
                }
                SpanAdapter span = ActiveSpanManager.getSpan(exchange);
                if (span != null) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Tracing: finish server span={}", span);
                    }

                    decorateWithCapi(exchange, span);

                    Long clientResponseTime = getClientResponseTime(exchange);
                    if(clientResponseTime != null) {
                        span.setTag("capi.client.response.time", clientResponseTime + "");
                    }

                    String clientEndpoint = exchange.getProperty(Constants.CLIENT_ENDPOINT, String.class);
                    if(clientEndpoint != null) {
                        span.setTag("capi.client.address", clientEndpoint);
                    }

                    String clientResponseCode = exchange.getProperty(Constants.CLIENT_RESPONSE_CODE, String.class);
                    if(clientResponseCode != null) {
                        span.setTag("capi.client.response.code", clientResponseCode);
                    }

                    SpanDecorator sd = getSpanDecorator(route.getEndpoint());
                    sd.post(span, exchange, route.getEndpoint());
                    finishSpan(span);
                    ActiveSpanManager.deactivate(exchange);
                } else {
                    LOG.warn("Tracing: could not find managed span for exchange: {}", exchange);
                }
            } catch (Exception t) {
                // This exception is ignored
                LOG.warn("Tracing: Failed to capture tracing data. This exception is ignored.", t);
            }
        }
    }

    private static final class TracingLogListener implements LogListener {

        @Override
        public String onLog(Exchange exchange, CamelLogger camelLogger, String message) {
            try {
                SpanAdapter span = ActiveSpanManager.getSpan(exchange);
                if (span != null) {
                    Map<String, String> fields = new HashMap<>();
                    fields.put("message", message);
                    span.log(fields);
                }
            } catch (Exception t) {
                // This exception is ignored
                LOG.warn("Tracing: Failed to capture tracing data. This exception is ignored.", t);
            }
            return message;
        }
    }

    private io.opentelemetry.api.trace.SpanKind mapToSpanKind(org.apache.camel.tracing.SpanKind kind) {
        switch (kind) {
            case SPAN_KIND_CLIENT:
                return io.opentelemetry.api.trace.SpanKind.CLIENT;
            case SPAN_KIND_SERVER:
                return io.opentelemetry.api.trace.SpanKind.SERVER;
            case CONSUMER:
                return io.opentelemetry.api.trace.SpanKind.CONSUMER;
            case PRODUCER:
                return io.opentelemetry.api.trace.SpanKind.PRODUCER;
            default:
                return io.opentelemetry.api.trace.SpanKind.SERVER;
        }
    }

    public ContextPropagators getContextPropagators() {
        return contextPropagators;
    }

    public void setContextPropagators(ContextPropagators contextPropagators) {
        this.contextPropagators = contextPropagators;
    }

    public io.opentelemetry.api.trace.Tracer getTracer() {
        return tracer;
    }

    public void setTracer(io.opentelemetry.api.trace.Tracer tracer) {
        this.tracer = tracer;
    }

    private Long getClientResponseTime(Exchange exchange) {
        Long clientStartTime = exchange.getProperty(Constants.CLIENT_START_TIME, Long.class);
        Long clientEndTime = exchange.getProperty(Constants.CLIENT_END_TIME, Long.class);
        if(clientStartTime != null && clientEndTime != null) {
            return (clientEndTime - clientStartTime);
        } else {
            return null;
        }
    }

    private void decorateWithCapi(Exchange exchange, SpanAdapter span) {
        try {
            String accessToken = httpUtils.processAuthorizationAccessToken(exchange);
            if(accessToken != null) {
                SignedJWT signedJWT = SignedJWT.parse(accessToken);
                JWTClaimsSet jwtClaimsSet = signedJWT.getJWTClaimsSet();
                Date expirationTime = jwtClaimsSet.getExpirationTime();
                if(expirationTime.before(Calendar.getInstance().getTime())) {
                    span.setTag(Constants.CAPI_TOKEN_EXPIRED, Boolean.toString(true));
                } else {
                    span.setTag(Constants.CAPI_TOKEN_EXPIRED, Boolean.toString(false));
                }
                String authorizedParty = jwtClaimsSet.getStringClaim(Constants.AUTHORIZED_PARTY);
                if(authorizedParty != null) {
                    span.setTag(Constants.CAPI_EXCHANGE_REQUESTER_ID, authorizedParty);
                }
                String clientHost = jwtClaimsSet.getStringClaim("clientHost");
                if(clientHost != null) {
                    span.setTag("capi.requester.host", clientHost);
                }
                String iss = jwtClaimsSet.getStringClaim("iss");
                if(iss != null) {
                    span.setTag(Constants.CAPI_REQUESTER_TOKEN_ISSUER, iss);
                }
            }

            if(exchange.getIn().getHeader("Content-Type") != null) {
                span.setTag("capi.incoming.request.content.type", exchange.getIn().getHeader("Content-Type", String.class));
            }

            if(exchange.getIn().getHeader("X-Forwarded-For") != null) {
                span.setTag("capi.incoming.request.original.ip", exchange.getIn().getHeader("X-Forwarded-For", String.class));
            }

            try {
                String url = exchange.getIn().getHeader("CamelHttpUri", String.class);
                String serviceId = url.trim().split("/")[2] + ":" + url.split("/")[3];
                io.surisoft.capi.schema.Service service = serviceCache.peek(serviceId);
                if(service != null) {
                    Map<String, String> extras = service.getServiceMeta().getExtraServiceMeta();
                    for(Map.Entry<String, String> entry : extras.entrySet()) {
                        span.setTag(entry.getKey(), entry.getValue());
                    }
                }
                span.setTag(Constants.CAMEL_SERVER_ENDPOINT_URL, url);
                // Update span name so Jaeger shows "sample-service:dev" instead of "get"
                if (span instanceof CapiOpenTelemetrySpanAdapter otelAdapter) {
                    otelAdapter.getOpenTelemetrySpan().updateName(serviceId);
                }
            } catch (Exception e) {
                LOG.trace("Problem parsing cached service metadata");
            }
            span.setTag("capi-instance", capiInstanceName);

        } catch (ParseException e) {
            LOG.trace("No Authorization header detected, or access token invalid");
        } catch (AuthorizationException e) {
            throw new RuntimeException(e);
        }
    }
}