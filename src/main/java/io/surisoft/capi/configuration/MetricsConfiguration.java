package io.surisoft.capi.configuration;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.util.HierarchicalNameMapper;
import io.micrometer.jmx.JmxMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.surisoft.capi.utils.Constants;
import org.apache.camel.component.micrometer.CamelJmxConfig;
import org.apache.camel.component.micrometer.DistributionStatisticConfigFilter;

import java.time.Duration;

import static org.apache.camel.component.micrometer.messagehistory.MicrometerMessageHistoryNamingStrategy.MESSAGE_HISTORIES;
import static org.apache.camel.component.micrometer.routepolicy.MicrometerRoutePolicyNamingStrategy.ROUTE_POLICIES;

public class MetricsConfiguration {

    public static CompositeMeterRegistry createMetricsRegistry() {
        PrometheusMeterRegistry prometheus =
                new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        DistributionStatisticConfigFilter timerMeterFilter = new DistributionStatisticConfigFilter()
                .andAppliesTo(ROUTE_POLICIES)
                .orAppliesTo(MESSAGE_HISTORIES)
                .setPublishPercentileHistogram(true)
                .setMinimumExpectedDuration(Duration.ofMillis(1L))
                .setMaximumExpectedDuration(Duration.ofMillis(150L));


        DistributionStatisticConfigFilter summaryMeterFilter = new DistributionStatisticConfigFilter()
                .setPublishPercentileHistogram(true)
                .setMinimumExpectedValue(1L)
                .setMaximumExpectedValue(100L);

        CompositeMeterRegistry compositeMeterRegistry = new CompositeMeterRegistry();
        compositeMeterRegistry.config().commonTags(Tags.of("application", Constants.APPLICATION_NAME))
                .meterFilter(timerMeterFilter)
                .meterFilter(summaryMeterFilter).namingConvention().tagKey(Constants.APPLICATION_NAME);

        compositeMeterRegistry.add(new JmxMeterRegistry(
                CamelJmxConfig.DEFAULT,
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

        // Note: jvm_threads_live_threads (from JvmThreadMetrics above) only counts
        // platform threads — Java's ThreadMXBean and getAllStackTraces() exclude
        // virtual threads by design. There is no standard JVM API to count active
        // virtual threads.

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
