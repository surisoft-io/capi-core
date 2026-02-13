package io.surisoft.capi.tracer;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.zipkin.ZipkinSpanExporter;
import io.opentelemetry.extension.trace.propagation.B3Propagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes;


public class TracingBootstrap {

    public static OpenTelemetry init(String serviceEndpoint, String serviceName) {

        // Identify THIS process
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
                        .setEndpoint(serviceEndpoint + "/api/v2/spans")
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
                ).buildAndRegisterGlobal();

    }
}