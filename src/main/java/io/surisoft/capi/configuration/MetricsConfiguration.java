package io.surisoft.capi.configuration;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.util.HierarchicalNameMapper;
import io.micrometer.jmx.JmxConfig;
import io.micrometer.jmx.JmxMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.surisoft.capi.utils.Constants;

import java.time.Duration;

public class MetricsConfiguration {

    public static CompositeMeterRegistry createMetricsRegistry() {
        PrometheusMeterRegistry prometheus =
                new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        CompositeMeterRegistry compositeMeterRegistry = new CompositeMeterRegistry();
        compositeMeterRegistry.config()
                .commonTags(Tags.of("application", Constants.APPLICATION_NAME))
                .meterFilter(new io.micrometer.core.instrument.config.MeterFilter() {
                    @Override
                    public DistributionStatisticConfig configure(io.micrometer.core.instrument.Meter.Id id, DistributionStatisticConfig config) {
                        return DistributionStatisticConfig.builder()
                                .percentilesHistogram(true)
                                .minimumExpectedValue(Duration.ofMillis(1).toNanos() * 1.0)
                                .maximumExpectedValue(Duration.ofMillis(150).toNanos() * 1.0)
                                .build()
                                .merge(config);
                    }
                });

        compositeMeterRegistry.add(new JmxMeterRegistry(
                JmxConfig.DEFAULT,
                Clock.SYSTEM,
                HierarchicalNameMapper.DEFAULT));

        // JVM metrics
        new ClassLoaderMetrics().bindTo(prometheus);
        new JvmMemoryMetrics().bindTo(prometheus);
        try (JvmGcMetrics jvmGcMetrics = new JvmGcMetrics()) {
            jvmGcMetrics.bindTo(prometheus);
        }
        new JvmThreadMetrics().bindTo(prometheus);
        new ProcessorMetrics().bindTo(prometheus);

        compositeMeterRegistry.add(prometheus);
        return compositeMeterRegistry;
    }

    public static PrometheusMeterRegistry createPrometheusMeterRegistry(CompositeMeterRegistry meterRegistry) {
        return meterRegistry.getRegistries()
                        .stream()
                        .filter(r -> r instanceof PrometheusMeterRegistry)
                        .map(r -> (PrometheusMeterRegistry) r)
                        .findFirst()
                        .orElseThrow(() ->
                                new IllegalStateException("Prometheus registry not found")
                        );
    }
}
