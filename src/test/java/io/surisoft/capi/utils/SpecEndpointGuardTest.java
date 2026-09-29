package io.surisoft.capi.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The guard on the {@code open-api} endpoint — the one URL a service owner supplies that CAPI then
 * fetches itself, with the Consul HTTP client and its trust store.
 *
 * <p>The must-allow cases carry the weight. CAPI's real backends sit on RFC 1918 addresses, so a
 * conventional "block private ranges" SSRF check would refuse nearly every legitimate spec endpoint —
 * which is almost certainly why {@code HttpUtils.isSafeUri} was written and then never wired up.
 */
class SpecEndpointGuardTest {

    private ServiceUtils serviceUtils;

    @BeforeEach
    void setUp() {
        serviceUtils = new ServiceUtils(null, Optional.empty(), null, Optional.empty(), "full");
    }

    // ---- must be refused ----

    @ParameterizedTest
    @ValueSource(strings = {
            "http://169.254.169.254/latest/meta-data/",       // AWS / Azure metadata
            "http://169.254.170.2/v2/credentials",            // ECS task credentials
            "https://169.254.169.254/computeMetadata/v1/",    // GCP metadata by address
    })
    void linkLocalIsAlwaysRefused(String url) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> serviceUtils.assertSpecEndpointAllowed(URI.create(url)));
        assertTrue(e.getMessage().contains("link-local"), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1:8500/v1/kv/?recurse",   // a co-located Consul agent — holds the trust store
            "http://localhost:8381/info/routes",      // CAPI's own admin port
            "http://[::1]:8500/v1/kv/",
    })
    void loopbackIsRefusedByDefault(String url) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> serviceUtils.assertSpecEndpointAllowed(URI.create(url)));
        assertTrue(e.getMessage().contains("loopback"), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://2130706433:8500/",      // decimal 127.0.0.1
            "http://127.1/",                // short form
            "http://localhost:8500/",       // name that resolves to loopback
    })
    void obfuscatedLoopbackFormsAreRefused(String url) {
        // Resolving before comparison is what catches these: all are accepted by URI and by the JDK
        // HTTP client, and all reach 127.0.0.1.
        assertThrows(IllegalArgumentException.class,
                () -> serviceUtils.assertSpecEndpointAllowed(URI.create(url)));
    }

    @Test
    void octalFormIsNotALoopbackBypassOnTheJdk() {
        // Worth pinning down rather than assuming. `0177.0.0.1` is the classic inet_aton octal
        // bypass, but Java does not parse octal: it resolves to 177.0.0.1 — an ordinary public
        // address, not loopback. So it is correctly allowed here, and the JDK HTTP client would
        // connect to 177.0.0.1 too. The guard and the fetch agree, which is the property that
        // matters; a check that disagreed with the client would be the bug.
        assertDoesNotThrow(() -> serviceUtils.assertSpecEndpointAllowed(URI.create("http://0177.0.0.1:8500/")));
    }

    @Test
    void loopbackIsAllowedWhenExplicitlyEnabled() {
        serviceUtils.setAllowLocalSpecEndpoints(true);
        assertDoesNotThrow(() -> serviceUtils.assertSpecEndpointAllowed(URI.create("http://127.0.0.1:9000/spec.json")));
    }

    @Test
    void linkLocalStaysRefusedEvenWhenLocalIsEnabled() {
        // The escape hatch is for dev stacks serving specs from loopback, not for metadata endpoints.
        serviceUtils.setAllowLocalSpecEndpoints(true);
        assertThrows(IllegalArgumentException.class,
                () -> serviceUtils.assertSpecEndpointAllowed(URI.create("http://169.254.169.254/latest/meta-data/")));
    }

    @Test
    void missingHostIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> serviceUtils.assertSpecEndpointAllowed(URI.create("http:///spec.json")));
    }

    // ---- must be allowed: these are what a real spec endpoint looks like ----

    @ParameterizedTest
    @ValueSource(strings = {
            "http://10.0.0.5:8080/openapi.json",        // a pod IP — the normal case
            "http://172.16.4.9:8080/openapi.json",
            "http://192.168.1.20:8080/openapi.json",
            "http://203.0.113.10/openapi.json",         // public literal
    })
    void privateAndPublicBackendsAreAllowed(String url) {
        // The whole point: a private address is where a spec normally lives, so blocking RFC 1918
        // would break the feature rather than secure it.
        assertDoesNotThrow(() -> serviceUtils.assertSpecEndpointAllowed(URI.create(url)));
    }

    @Test
    void unresolvableHostIsLeftToTheFetch() {
        // Not this check's job to decide — let it fail on connect with a message that says so.
        assertDoesNotThrow(() -> serviceUtils.assertSpecEndpointAllowed(
                URI.create("http://this-host-does-not-exist-xyz-123.invalid/openapi.json")));
    }
}
