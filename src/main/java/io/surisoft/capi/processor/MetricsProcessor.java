package io.surisoft.capi.processor;

import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.search.RequiredSearch;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

public class MetricsProcessor implements Processor {

    private final CompositeMeterRegistry meterRegistry;

    public MetricsProcessor(CompositeMeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void process(Exchange exchange) {
        if(exchange.getFromRouteId() != null) {
            RequiredSearch requiredSearch = meterRegistry.get(exchange.getFromRouteId());
            requiredSearch.counter().increment();
        }
    }
}