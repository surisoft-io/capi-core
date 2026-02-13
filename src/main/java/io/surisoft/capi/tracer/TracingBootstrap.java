package io.surisoft.capi.tracer;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;


public class TracingBootstrap {

    public static OpenTelemetry init(String serviceEndpoint, String serviceName) {

        /*
        Resource resource = Resource.getDefault().merge(
                Resource.create(
                        Attributes.of(
                                ResourceAttributes.SERVICE_NAME, serviceName
                        )
                )
        );

        // Export to OTEL / Zipkin endpoint
        ZipkinSpanExporter exporter =
                ZipkinSpanExporter.builder()
                        .setEndpoint(serviceEndpoint + "/v1/spans")
                      .build();

        SdkTracerProvider tracerProvider =
                SdkTracerProvider.builder()
                        .setResource(resource)
                        .addSpanProcessor(
                                BatchSpanProcessor.builder(exporter).build()
                        )
                        .build();

        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(
                        ContextPropagators.create(
                                B3Propagator.injectingSingleHeader()
                        )
                ).buildAndRegisterGlobal();*/
        OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(serviceEndpoint + "/v1/traces")
                .build();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .setResource(Resource.getDefault().merge(
                        Resource.create(Attributes.of(
                                AttributeKey.stringKey("service.name"), serviceName))))
                .build();

        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .buildAndRegisterGlobal();

    }
}