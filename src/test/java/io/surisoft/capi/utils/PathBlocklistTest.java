package io.surisoft.capi.utils;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PathBlocklistTest {

    private static final PathBlocklist ACTUATOR = PathBlocklist.of(List.of("/actuator"));

    // --- Matching ---

    @Test
    void matchesSegmentAnywhereInPath() {
        assertEquals("/actuator", ACTUATOR.match("/api/svc/v1/actuator"));
        assertEquals("/actuator", ACTUATOR.match("/api/svc/v1/actuator/env"));
        assertEquals("/actuator", ACTUATOR.match("/actuator"));
    }

    @Test
    void wholeSegmentsOnly() {
        assertNull(ACTUATOR.match("/api/svc/v1/actuators"));
        assertNull(ACTUATOR.match("/api/svc/v1/my-actuator/x"));
        assertNull(ACTUATOR.match("/api/svc/v1/orders"));
    }

    @Test
    void multiSegmentRule_matchesConsecutiveSegmentsOnly() {
        PathBlocklist env = PathBlocklist.of(List.of("/actuator/env"));
        assertEquals("/actuator/env", env.match("/api/svc/v1/actuator/env"));
        assertEquals("/actuator/env", env.match("/api/svc/v1/actuator/env/password"));
        assertNull(env.match("/api/svc/v1/actuator/health"));
        assertNull(env.match("/api/svc/v1/actuator/x/env"));
    }

    @Test
    void returnsTheRuleAsConfigured() {
        PathBlocklist blocklist = PathBlocklist.of(List.of("/Actuator", "/internal/debug"));
        assertEquals("/Actuator", blocklist.match("/api/svc/v1/actuator"));
        assertEquals("/internal/debug", blocklist.match("/api/svc/v1/internal/debug"));
        assertEquals(List.of("/Actuator", "/internal/debug"), blocklist.describe());
    }

    /** Every spelling a backend might resolve to /actuator. */
    @Test
    void bypassAttempts_areAllMatched() {
        for (String path : List.of(
                "/api/svc/v1/ACTUATOR",
                "/api/svc/v1/AcTuAtOr/env",
                "/api/svc/v1/%61ctuator",
                "/api/svc/v1/%41CTUATOR",
                "/api/svc/v1/%2561ctuator",                 // double-encoded
                "/api/svc/v1/actuator;jsessionid=x",        // matrix parameter
                "/api/svc/v1/actuator;/env",
                "/api/svc/v1//actuator",                    // empty segment
                "/api/svc/v1/./actuator",
                "/api/svc/v1/x/../actuator",
                "/api/svc/v1/x/..;/actuator",               // Tomcat-style traversal
                "/api/svc/v1\\actuator",                    // backslash separator
                "/api/svc/v1/foo%2Factuator",               // encoded slash
                "/api/svc/v1/actuator?x=1",
                "/api/svc/v1/actuator#frag",
                "http://host:8380/api/svc/v1/actuator")) {  // absolute-form request target
            assertEquals("/actuator", ACTUATOR.match(path), "should be blocked: " + path);
        }
    }

    @Test
    void ruleInQueryString_doesNotMatch() {
        assertNull(ACTUATOR.match("/api/svc/v1/orders?next=/actuator"));
    }

    @Test
    void malformedEscapes_doNotThrow() {
        assertNull(ACTUATOR.match("/api/svc/v1/%zz/%4"));
        assertNull(ACTUATOR.match("/api/svc/v1/%"));
    }

    @Test
    void dotDotAboveRoot_isHarmless() {
        assertEquals("/actuator", ACTUATOR.match("/../../actuator"));
    }

    @Test
    void emptyBlocklist_matchesNothing() {
        assertTrue(PathBlocklist.of(null).isEmpty());
        assertTrue(PathBlocklist.of(List.of()).isEmpty());
        assertNull(PathBlocklist.EMPTY.match("/api/svc/v1/actuator"));
    }

    @Test
    void nullOrEmptyPath_matchesNothing() {
        assertNull(ACTUATOR.match((String) null));
        assertNull(ACTUATOR.match(""));
    }

    // --- Validation: a bad rule fails startup instead of blocking nothing, or everything ---

    @Test
    void blankRule_isRefused() {
        assertThrows(IllegalArgumentException.class, () -> PathBlocklist.of(List.of("  ")));
        assertThrows(IllegalArgumentException.class, () -> PathBlocklist.of(Arrays.asList("/actuator", null)));
    }

    @Test
    void rootRule_isRefused_itWouldBlockEverything() {
        assertThrows(IllegalArgumentException.class, () -> PathBlocklist.of(List.of("/")));
        assertThrows(IllegalArgumentException.class, () -> PathBlocklist.of(List.of("//")));
    }

    @Test
    void unsupportedSyntax_isRefused() {
        for (String rule : List.of("/actuator/*", "/actuator?x", "/actuator#x", "/actuator;x", "/%61ctuator", "\\actuator")) {
            assertThrows(IllegalArgumentException.class, () -> PathBlocklist.of(List.of(rule)), rule);
        }
    }

    @Test
    void dotSegmentInRule_isRefused() {
        assertThrows(IllegalArgumentException.class, () -> PathBlocklist.of(List.of("/x/../actuator")));
        assertThrows(IllegalArgumentException.class, () -> PathBlocklist.of(List.of("/./actuator")));
    }

    @Test
    void ruleWithoutLeadingSlash_isAccepted() {
        assertEquals("actuator", PathBlocklist.of(List.of("actuator")).match("/api/svc/v1/actuator"));
    }

    // --- Metric ---

    @Test
    void count_tagsListenerRuleAndService() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PathBlocklist.count(registry, "rest", "/actuator", "svc:v1");
        PathBlocklist.count(registry, "rest", "/actuator", null);

        assertEquals(1.0, registry.get("capi_blocked_requests_total")
                .tags("listener", "rest", "rule", "/actuator", "service", "svc:v1").counter().count());
        assertEquals(1.0, registry.get("capi_blocked_requests_total")
                .tags("service", "unrouted").counter().count());
        assertDoesNotThrow(() -> PathBlocklist.count(null, "rest", "/actuator", "svc:v1"));
    }
}
