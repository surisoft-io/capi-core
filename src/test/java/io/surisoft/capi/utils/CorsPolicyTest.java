package io.surisoft.capi.utils;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CorsPolicyTest {

    @Test
    void nullList_deniesEverything() {
        CorsPolicy policy = new CorsPolicy(null);
        assertTrue(policy.isDenyAll());
        assertNull(policy.resolveAllowOrigin("https://app.example"));
        assertFalse(policy.allowsCredentials("https://app.example"));
    }

    @Test
    void emptyList_deniesEverything() {
        CorsPolicy policy = new CorsPolicy(List.of());
        assertTrue(policy.isDenyAll());
        assertNull(policy.resolveAllowOrigin("https://app.example"));
    }

    @Test
    void denyAll_isDenyAll() {
        assertTrue(CorsPolicy.denyAll().isDenyAll());
    }

    @Test
    void listedOrigin_isEchoedWithCredentials() {
        CorsPolicy policy = new CorsPolicy(List.of("https://app.example"));
        assertFalse(policy.isDenyAll());
        assertEquals("https://app.example", policy.resolveAllowOrigin("https://app.example"));
        assertTrue(policy.allowsCredentials("https://app.example"));
    }

    @Test
    void unlistedOrigin_isDenied() {
        CorsPolicy policy = new CorsPolicy(List.of("https://app.example"));
        assertNull(policy.resolveAllowOrigin("https://evil.example"));
        assertFalse(policy.allowsCredentials("https://evil.example"));
    }

    @Test
    void unlistedOrigin_isNotMatchedBySubstringOrSuffix() {
        CorsPolicy policy = new CorsPolicy(List.of("https://app.example"));
        // The classic allowlist bypasses: prefix, suffix and embedded matches must all fail.
        assertNull(policy.resolveAllowOrigin("https://app.example.evil.com"));
        assertNull(policy.resolveAllowOrigin("https://evil-app.example"));
        assertNull(policy.resolveAllowOrigin("https://app.example:8443"));
        assertNull(policy.resolveAllowOrigin("http://app.example"));
    }

    @Test
    void wildcard_allowsAnyOriginButNeverCredentials() {
        CorsPolicy policy = new CorsPolicy(List.of("*"));
        assertFalse(policy.isDenyAll());
        assertEquals("*", policy.resolveAllowOrigin("https://anything.example"));
        // A credentialed wildcard is invalid per the Fetch standard.
        assertFalse(policy.allowsCredentials("https://anything.example"));
    }

    @Test
    void wildcardWithExplicitEntry_stillGivesCredentialsToTheExplicitOne() {
        CorsPolicy policy = new CorsPolicy(List.of("*", "https://app.example"));
        assertEquals("https://app.example", policy.resolveAllowOrigin("https://app.example"));
        assertTrue(policy.allowsCredentials("https://app.example"));
        assertEquals("*", policy.resolveAllowOrigin("https://other.example"));
        assertFalse(policy.allowsCredentials("https://other.example"));
    }

    @Test
    void nullOrBlankOrigin_isDenied() {
        CorsPolicy policy = new CorsPolicy(List.of("https://app.example"));
        assertNull(policy.resolveAllowOrigin(null));
        assertNull(policy.resolveAllowOrigin("   "));
        assertFalse(policy.allowsCredentials(null));
    }

    @Test
    void configuredOrigins_areNormalizedForCaseAndTrailingSlash() {
        CorsPolicy policy = new CorsPolicy(List.of("  HTTPS://App.Example/  "));
        assertEquals("https://app.example", policy.resolveAllowOrigin("https://app.example"));
        assertTrue(policy.allowsCredentials("HTTPS://APP.EXAMPLE"));
    }

    @Test
    void blankAndNullEntries_areIgnored() {
        CorsPolicy policy = new CorsPolicy(Arrays.asList(null, "", "   ", "https://app.example"));
        assertEquals("https://app.example", policy.resolveAllowOrigin("https://app.example"));
        assertNull(policy.resolveAllowOrigin("https://evil.example"));
    }

    @Test
    void fromCsv_nullOrBlank_returnsNullMeaningInherit() {
        // Null is "this service said nothing", which inherits the gateway default — not "deny".
        assertNull(CorsPolicy.fromCsv(null));
        assertNull(CorsPolicy.fromCsv(""));
        assertNull(CorsPolicy.fromCsv("   "));
    }

    @Test
    void fromCsv_parsesCommaSeparatedServiceMetaValue() {
        CorsPolicy policy = CorsPolicy.fromCsv("https://a.example, https://b.example");
        assertNotNull(policy);
        assertEquals("https://a.example", policy.resolveAllowOrigin("https://a.example"));
        assertEquals("https://b.example", policy.resolveAllowOrigin("https://b.example"));
        assertTrue(policy.allowsCredentials("https://b.example"));
        assertNull(policy.resolveAllowOrigin("https://c.example"));
    }

    @Test
    void fromCsv_singleOrigin() {
        CorsPolicy policy = CorsPolicy.fromCsv("https://only.example");
        assertNotNull(policy);
        assertEquals("https://only.example", policy.resolveAllowOrigin("https://only.example"));
    }

    @Test
    void fromCsv_wildcardFromServiceMeta() {
        CorsPolicy policy = CorsPolicy.fromCsv("*");
        assertNotNull(policy);
        assertEquals("*", policy.resolveAllowOrigin("https://anything.example"));
        assertFalse(policy.allowsCredentials("https://anything.example"));
    }

    @Test
    void onlyBlankEntries_collapseToDenyAll() {
        assertTrue(new CorsPolicy(Arrays.asList(null, "", "  ")).isDenyAll());
    }
}
