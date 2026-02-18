package io.surisoft.capi.tracer;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TracingBootstrapTest {

    @BeforeEach
    void setUp() {
        // Clear any globally registered OpenTelemetry instance before each test,
        // because TracingBootstrap.init calls buildAndRegisterGlobal().
        GlobalOpenTelemetry.resetForTest();
    }

    @AfterEach
    void tearDown() {
        GlobalOpenTelemetry.resetForTest();
    }

    @Test
    void init_returnsNonNullOpenTelemetry() {
        OpenTelemetry result = TracingBootstrap.init("http://localhost:4318", "test-service");
        assertNotNull(result);
    }

    @Test
    void init_returnsSdkInstance() {
        OpenTelemetry result = TracingBootstrap.init("http://localhost:4318", "capi-test");
        assertTrue(result instanceof OpenTelemetrySdk);
    }
}
