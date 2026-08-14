package io.surisoft.capi.tracer;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;


public class TracingBootstrap {

    /**
     * Builds the OpenTelemetry SDK.
     *
     * Returns the concrete {@link OpenTelemetrySdk} (not the {@code OpenTelemetry} interface) so the
     * caller holds something closeable: {@code close()} flushes pending spans and shuts the exporter
     * down. With {@link BatchSpanProcessor} that flush is REQUIRED — anything still queued at JVM
     * shutdown is lost otherwise. Wired into CAPIMain's shutdown hook via Startup#getOpenTelemetrySdk.
     *
     * BatchSpanProcessor (not SimpleSpanProcessor): Simple exports every span inline on span.end(),
     * which runs inside the completion listener on the XNIO IO thread — one outbound OTLP request per
     * request served, and an export failure there propagates into a listener that must never throw
     * (see CapiTracer#endSpan). Batch just enqueues and drains on its own thread.
     */
    public static OpenTelemetrySdk init(String serviceEndpoint, String serviceName, String appEnvironment) {

        OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(serviceEndpoint + "/v1/traces")
                .build();

        // service.name stays; appEnvironment is added alongside it. Omitted entirely when unset —
        // an empty value would create a real "" bucket in dashboards instead of simply being absent.
        AttributesBuilder resourceAttributes = Attributes.builder()
                .put(AttributeKey.stringKey("service.name"), serviceName);
        if (appEnvironment != null && !appEnvironment.isBlank()) {
            resourceAttributes.put(AttributeKey.stringKey("appEnvironment"), appEnvironment.trim());
        }

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                .setResource(Resource.getDefault().merge(Resource.create(resourceAttributes.build())))
                .build();

        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .buildAndRegisterGlobal();

    }
}