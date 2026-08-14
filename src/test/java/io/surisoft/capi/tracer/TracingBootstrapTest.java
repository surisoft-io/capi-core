package io.surisoft.capi.tracer;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
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
        OpenTelemetry result = TracingBootstrap.init("http://localhost:4318", "test-service", "dev");
        assertNotNull(result);
    }

    @Test
    void init_returnsSdkInstance() {
        OpenTelemetry result = TracingBootstrap.init("http://localhost:4318", "capi-test", "dev");
        assertTrue(result instanceof OpenTelemetrySdk);
    }

    // ---- appEnvironment resource attribute ----

    @Test
    void init_withAppEnvironment_setsResourceAttributeAlongsideServiceName() {
        OpenTelemetrySdk sdk = TracingBootstrap.init("http://localhost:4318", "capi-test", "prod");

        Attributes attributes = resourceOf(sdk);

        assertEquals("capi-test", attributes.get(AttributeKey.stringKey("service.name")));
        assertEquals("prod", attributes.get(AttributeKey.stringKey("appEnvironment")));
    }

    @Test
    void init_withNullAppEnvironment_omitsAttributeButKeepsServiceName() {
        OpenTelemetrySdk sdk = TracingBootstrap.init("http://localhost:4318", "capi-test", null);
        Attributes attributes = resourceOf(sdk);

        assertEquals("capi-test", attributes.get(AttributeKey.stringKey("service.name")));
        assertNull(attributes.get(AttributeKey.stringKey("appEnvironment")));
    }

    @Test
    void init_withBlankAppEnvironment_omitsAttribute() {
        OpenTelemetrySdk sdk = TracingBootstrap.init("http://localhost:4318", "capi-test", "   ");
        Attributes attributes = resourceOf(sdk);

        assertEquals("capi-test", attributes.get(AttributeKey.stringKey("service.name")));
        assertNull(attributes.get(AttributeKey.stringKey("appEnvironment")));
    }

    @Test
    void init_trimsAppEnvironment() {
        OpenTelemetrySdk sdk = TracingBootstrap.init("http://localhost:4318", "capi-test", "  acc  ");
        assertEquals("acc", resourceOf(sdk).get(AttributeKey.stringKey("appEnvironment")));
    }

    /** The resource isn't exposed directly on the SDK, so read it off a span the provider produces. */
    private static Attributes resourceOf(OpenTelemetrySdk sdk) {
        return ((io.opentelemetry.sdk.trace.ReadableSpan) sdk.getSdkTracerProvider()
                .get("resource-probe")
                .spanBuilder("probe")
                .startSpan())
                .toSpanData()
                .getResource()
                .getAttributes();
    }
}
