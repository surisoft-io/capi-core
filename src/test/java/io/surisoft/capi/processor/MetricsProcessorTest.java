package io.surisoft.capi.processor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.search.RequiredSearch;
import org.apache.camel.Exchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MetricsProcessorTest {

    @Mock
    private CompositeMeterRegistry meterRegistry;

    @Mock
    private Exchange exchange;

    @Mock
    private RequiredSearch requiredSearch;

    @Mock
    private Counter counter;

    private MetricsProcessor metricsProcessor;

    @BeforeEach
    void setUp() {
        metricsProcessor = new MetricsProcessor(meterRegistry);
    }

    @Test
    void process_withRouteId_incrementsCounter() {
        when(exchange.getFromRouteId()).thenReturn("my-route-id");
        when(meterRegistry.get("my-route-id")).thenReturn(requiredSearch);
        when(requiredSearch.counter()).thenReturn(counter);

        metricsProcessor.process(exchange);

        verify(counter).increment();
    }

    @Test
    void process_withNullRouteId_doesNothing() {
        when(exchange.getFromRouteId()).thenReturn(null);

        metricsProcessor.process(exchange);

        verifyNoInteractions(meterRegistry);
    }
}
