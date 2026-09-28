package io.surisoft.capi.undertow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dot-segment guard behind {@code capi.rest.rejectDotSegments}.
 *
 * <p>The must-NOT-match list carries more weight than the must-match list. This guard refuses
 * requests, so a false positive breaks a working service — and a naive {@code contains("..")} test
 * fails six of the seven legitimate shapes below.
 *
 * <p>Encodings are covered by reading the decoded path: Undertow turns {@code %2e%2e}, {@code %2E%2E}
 * and {@code .%2e} into {@code ..} before any handler runs (verified against 2.3.21), so the values
 * here are what a handler actually receives.
 */
class DotSegmentGuardTest {

    // ---- must be refused: a whole segment that walks the path ----

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/svcA/dev/../../svcB/dev/secret",
            "/api/svcA/dev/..",
            "/api/svcA/dev/../x",
            "/..",
            "/../",
            "/api/../dev",
            "/api/svcA/dev/a/../../b",
            "/api/svcA/dev/.",
            "/api/svcA/dev/./x",
            "/.",
            "/./",
            "/api/./dev",
            // trailing dot-segment with no following slash
            "/api/svcA/dev/x/..",
            "/api/svcA/dev/x/.",
    })
    void dotSegments_areDetected(String path) {
        assertTrue(RestGateway.containsDotSegment(path), path);
    }

    // ---- must be allowed: dots inside a segment are ordinary data ----

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/svcA/dev/range/1..10",        // Ruby/Rust-style range in a path param
            "/api/svcA/dev/files/report..pdf",  // double dot in a filename
            "/api/svcA/dev/a..b",
            "/api/svcA/dev/...",                // three dots is not a dot-segment (RFC 3986)
            "/api/svcA/dev/....",
            "/api/svcA/dev/..foo",
            "/api/svcA/dev/foo..",
            "/api/svcA/dev/.hidden",            // dotfile-style segment
            "/api/svcA/dev/v1.2.3/x",
            "/api/svcA/dev/orders",
            "/api/svcA/dev/a.b.c",
            "/",
            "",
            // double-encoded arrives as this literal segment; reaching ".." from here needs a
            // backend that decodes twice, which is that backend's bug, not a dot-segment here
            "/api/svcA/dev/%2e%2e/x",
    })
    void ordinaryPaths_areNotFlagged(String path) {
        assertFalse(RestGateway.containsDotSegment(path), path);
    }

    @Test
    void nullPath_isNotFlagged() {
        assertFalse(RestGateway.containsDotSegment(null));
    }

    @Test
    void pathWithoutAnyDot_takesTheFastPath() {
        assertFalse(RestGateway.containsDotSegment("/api/svcA/dev/orders/12345/items"));
    }

    @Test
    void emptySegmentsAreNotDotSegments() {
        // "//" collapses to an empty segment, which does not walk the path.
        assertFalse(RestGateway.containsDotSegment("/api//svcA//dev"));
    }
}
