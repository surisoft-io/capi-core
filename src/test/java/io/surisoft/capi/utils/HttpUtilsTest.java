package io.surisoft.capi.utils;

import io.surisoft.capi.exception.AuthorizationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class HttpUtilsTest {

    private HttpUtils httpUtils;

    @BeforeEach
    void setUp() {
        httpUtils = new HttpUtils(null, null);
    }

    @Test
    void getBearerTokenFromHeader_validHeader() throws AuthorizationException {
        String token = httpUtils.getBearerTokenFromHeader("Bearer my-access-token");
        assertEquals("my-access-token", token);
    }

    @Test
    void getBearerTokenFromHeader_invalidHeader_throwsException() {
        assertThrows(AuthorizationException.class, () -> httpUtils.getBearerTokenFromHeader(null));
    }

    @Test
    void normalizeHttpEndpoint_removesHttpPrefix() {
        assertEquals("example.com/api", httpUtils.normalizeHttpEndpoint("http://example.com/api"));
    }

    @Test
    void normalizeHttpEndpoint_removesHttpsPrefix() {
        assertEquals("example.com/api", httpUtils.normalizeHttpEndpoint("https://example.com/api"));
    }

    @Test
    void normalizeHttpEndpoint_noPrefix_returnsAsIs() {
        assertEquals("example.com/api", httpUtils.normalizeHttpEndpoint("example.com/api"));
    }

    @Test
    void isEndpointSecure_httpsReturnsTrue() {
        assertTrue(httpUtils.isEndpointSecure("https://example.com"));
    }

    @Test
    void isEndpointSecure_httpReturnsFalse() {
        assertFalse(httpUtils.isEndpointSecure("http://example.com"));
    }

    @Test
    void contextToRole_stripsLeadingSlashAndReplacesSlashes() {
        assertEquals("sample-service:dev", httpUtils.contextToRole("/sample-service/dev"));
    }

    @Test
    void contextToRole_noLeadingSlash() {
        assertEquals("sample-service:dev", httpUtils.contextToRole("sample-service/dev"));
    }

    @Test
    void setIngressEndpoint_appendsHostHeader() {
        String result = httpUtils.setIngressEndpoint("http://host:8080/path?bridgeEndpoint=true", "my-host");
        assertTrue(result.contains("&"));
        assertTrue(result.contains("my-host"));
    }

    @Test
    void setHttpConnectTimeout_appendsTimeout() {
        String result = httpUtils.setHttpConnectTimeout("http://host:8080/path", 5000);
        assertTrue(result.contains("5000"));
    }

    @Test
    void getCapiContext_stripsWildcard() {
        assertEquals("/api", httpUtils.getCapiContext("/api/*"));
    }

    @Test
    void validateHeaderValue_validValue() {
        assertEquals("valid-header", HttpUtils.validateHeaderValue("valid-header"));
    }

    @Test
    void validateHeaderValue_invalidValue_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> HttpUtils.validateHeaderValue("invalid<script>"));
    }

    @Test
    void isSafeUri_httpScheme_returnsTrue() {
        assertTrue(httpUtils.isSafeUri(URI.create("http://example.com/api"), true));
    }

    @Test
    void isSafeUri_httpsScheme_returnsTrue() {
        assertTrue(httpUtils.isSafeUri(URI.create("https://example.com/api"), true));
    }

    @Test
    void isSafeUri_ftpScheme_returnsFalse() {
        assertFalse(httpUtils.isSafeUri(URI.create("ftp://example.com"), true));
    }

    @Test
    void isSafeUri_privateIp_noLocalTraffic_returnsFalse() {
        assertFalse(httpUtils.isSafeUri(URI.create("http://10.0.0.1/api"), false));
    }

    @Test
    void isSafeUri_privateIp_allowLocalTraffic_returnsTrue() {
        assertTrue(httpUtils.isSafeUri(URI.create("http://10.0.0.1/api"), true));
    }

    @Test
    void isSafeUri_localhost_noLocalTraffic_returnsFalse() {
        assertFalse(httpUtils.isSafeUri(URI.create("http://127.0.0.1/api"), false));
    }

    @Test
    void isSafeUri_172Range_noLocalTraffic_returnsFalse() {
        assertFalse(httpUtils.isSafeUri(URI.create("http://172.16.0.1/api"), false));
    }

    @Test
    void isSafeUri_192Range_noLocalTraffic_returnsFalse() {
        assertFalse(httpUtils.isSafeUri(URI.create("http://192.168.1.1/api"), false));
    }

    @Test
    void getAuthorizationCookieValue_found() {
        var cookies = java.util.List.of(
                new java.net.HttpCookie("session", "abc"),
                new java.net.HttpCookie("auth", "token123")
        );
        assertEquals("token123", httpUtils.getAuthorizationCookieValue(cookies, "auth"));
    }

    @Test
    void getAuthorizationCookieValue_notFound() {
        var cookies = java.util.List.of(new java.net.HttpCookie("session", "abc"));
        assertNull(httpUtils.getAuthorizationCookieValue(cookies, "auth"));
    }
}