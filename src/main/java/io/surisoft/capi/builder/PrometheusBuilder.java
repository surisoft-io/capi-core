package io.surisoft.capi.builder;

import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.apache.camel.builder.RouteBuilder;

public class PrometheusBuilder extends RouteBuilder {

    private final PrometheusMeterRegistry prometheusRegistry;
    private final boolean sslEnabled;

    public PrometheusBuilder(PrometheusMeterRegistry prometheusRegistry, boolean sslEnabled) {
        this.prometheusRegistry = prometheusRegistry;
        this.sslEnabled = sslEnabled;
    }

    @Override
    public void configure() throws Exception {
        String scheme = sslEnabled ? "https" : "http";
        from("undertow:" + scheme + "://0.0.0.0:8381/metrics")
                .setBody(exchange ->
                        prometheusRegistry.scrape()
                )
                .setHeader("Content-Type", constant("text/plain; version=0.0.4"));
    }
}
