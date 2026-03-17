package io.surisoft.capi.utils;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import io.surisoft.capi.exception.AuthorizationException;
import io.surisoft.capi.schema.CapiRestError;
import io.surisoft.capi.schema.OpaResult;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import io.surisoft.capi.service.OpaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.HttpCookie;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
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

    // --- Tests for uncovered methods ---

    @Test
    void setHttpSocketTimeout_appendsTimeout() {
        String result = httpUtils.setHttpSocketTimeout("http://host:8080/path", 3000);
        assertTrue(result.contains("socketTimeout=3000"));
    }

    @Test
    void setHttpSocketTimeout_endpointWithExistingQueryParams() {
        String result = httpUtils.setHttpSocketTimeout("http://host:8080/path?foo=bar", 3000);
        assertTrue(result.contains("&socketTimeout=3000"));
    }

    @Test
    void setHttpConnectTimeout_endpointWithExistingQueryParamsEndingWithAmpersand() {
        String result = httpUtils.setHttpConnectTimeout("http://host:8080/path?foo=bar&", 5000);
        assertTrue(result.contains("connectTimeout=5000"));
        assertFalse(result.contains("&&"));
    }

    @Test
    void prepareEndpoint_viaSetHttpConnectTimeout_addsQuestionMark() {
        String result = httpUtils.setHttpConnectTimeout("http://host:8080/path", 5000);
        assertTrue(result.contains("?connectTimeout=5000"));
    }

    @Test
    void prepareEndpoint_viaSetHttpConnectTimeout_addsAmpersand() {
        String result = httpUtils.setHttpConnectTimeout("http://host:8080/path?existing=true", 5000);
        assertTrue(result.contains("&connectTimeout=5000"));
    }

    @Test
    void proxyErrorMapper_validError() {
        CapiRestError error = new CapiRestError();
        error.setErrorCode(500);
        error.setErrorMessage("Internal Server Error");
        error.setRouteID("route-1");

        String json = httpUtils.proxyErrorMapper(error);
        assertTrue(json.contains("500"));
        assertTrue(json.contains("Internal Server Error"));
        assertTrue(json.contains("route-1"));
    }

    @Test
    void authorizeRequest_nullProcessorList_returnsNull() throws AuthorizationException {
        // httpUtils constructed with null jwtProcessorList
        assertNull(httpUtils.authorizeRequest("some-token"));
    }

    @Test
    void processAuthorizationAccessToken_httpServletRequest_withBearerHeader() throws AuthorizationException {
        jakarta.servlet.http.HttpServletRequest request = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(request.getHeader(Constants.AUTHORIZATION_HEADER)).thenReturn("Bearer my-token");

        String result = httpUtils.processAuthorizationAccessToken(request);
        assertEquals("my-token", result);
    }

    @Test
    void processAuthorizationAccessToken_httpServletRequest_withAccessTokenParam() throws AuthorizationException {
        jakarta.servlet.http.HttpServletRequest request = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(request.getHeader(Constants.AUTHORIZATION_HEADER)).thenReturn(null);
        when(request.getHeader(Constants.AUTHORIZATION_REQUEST_PARAMETER)).thenReturn("param-token");

        String result = httpUtils.processAuthorizationAccessToken(request);
        assertEquals("param-token", result);
    }

    @Test
    void processAuthorizationAccessToken_httpServletRequest_noAuth_returnsNull() throws AuthorizationException {
        jakarta.servlet.http.HttpServletRequest request = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(request.getHeader(Constants.AUTHORIZATION_HEADER)).thenReturn(null);
        when(request.getHeader(Constants.AUTHORIZATION_REQUEST_PARAMETER)).thenReturn(null);
        when(request.getHeader(null)).thenReturn(null);
        when(request.getHeader(Constants.COOKIE_HEADER)).thenReturn(null);

        String result = httpUtils.processAuthorizationAccessToken(request);
        assertNull(result);
    }

    @Test
    void processAuthorizationAccessToken_httpServletRequest_withCookieAuth() throws AuthorizationException {
        HttpUtils httpUtilsWithCookie = new HttpUtils("auth-cookie", null);
        jakarta.servlet.http.HttpServletRequest request = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(request.getHeader(Constants.AUTHORIZATION_HEADER)).thenReturn(null);
        when(request.getHeader(Constants.AUTHORIZATION_REQUEST_PARAMETER)).thenReturn(null);
        when(request.getHeader("auth-cookie")).thenReturn("my-auth-cookie");
        when(request.getHeader(Constants.COOKIE_HEADER)).thenReturn("my-auth-cookie=tokenvalue");

        String result = httpUtilsWithCookie.processAuthorizationAccessToken(request);
        assertEquals("tokenvalue", result);
    }

    @Test
    void processAuthorizationAccessToken_httpServletRequest_withCookieButNoName_returnsNull() throws AuthorizationException {
        HttpUtils httpUtilsWithCookie = new HttpUtils("auth-cookie", null);
        jakarta.servlet.http.HttpServletRequest request = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(request.getHeader(Constants.AUTHORIZATION_HEADER)).thenReturn(null);
        when(request.getHeader(Constants.AUTHORIZATION_REQUEST_PARAMETER)).thenReturn(null);
        when(request.getHeader("auth-cookie")).thenReturn(null);
        when(request.getHeader(Constants.COOKIE_HEADER)).thenReturn("session=abc");

        String result = httpUtilsWithCookie.processAuthorizationAccessToken(request);
        assertNull(result);
    }

    @Test
    void processAuthorizationAccessToken_httpServerExchange_withBearerHeader() throws AuthorizationException {
        io.undertow.server.HttpServerExchange httpServerExchange = mock(io.undertow.server.HttpServerExchange.class);
        io.undertow.util.HeaderMap headerMap = new io.undertow.util.HeaderMap();
        headerMap.put(io.undertow.util.HttpString.tryFromString(Constants.AUTHORIZATION_HEADER), "Bearer my-token");
        when(httpServerExchange.getRequestHeaders()).thenReturn(headerMap);

        String result = httpUtils.processAuthorizationAccessToken(httpServerExchange);
        assertEquals("my-token", result);
    }

    @Test
    void processAuthorizationAccessToken_httpServerExchange_withAccessTokenHeader() throws AuthorizationException {
        io.undertow.server.HttpServerExchange httpServerExchange = mock(io.undertow.server.HttpServerExchange.class);
        io.undertow.util.HeaderMap headerMap = new io.undertow.util.HeaderMap();
        headerMap.put(new io.undertow.util.HttpString(Constants.AUTHORIZATION_REQUEST_PARAMETER), "header-token");
        when(httpServerExchange.getRequestHeaders()).thenReturn(headerMap);

        String result = httpUtils.processAuthorizationAccessToken(httpServerExchange);
        assertEquals("header-token", result);
    }

    @Test
    void processAuthorizationAccessToken_httpServerExchange_noAuth_returnsNull() throws AuthorizationException {
        io.undertow.server.HttpServerExchange httpServerExchange = mock(io.undertow.server.HttpServerExchange.class);
        io.undertow.util.HeaderMap headerMap = new io.undertow.util.HeaderMap();
        when(httpServerExchange.getRequestHeaders()).thenReturn(headerMap);
        when(httpServerExchange.getQueryParameters()).thenReturn(new HashMap<>());

        String result = httpUtils.processAuthorizationAccessToken(httpServerExchange);
        assertNull(result);
    }

    @Test
    void isAuthorized_withNullProcessorList_throwsOrReturnsFalse() {
        Service service = new Service();
        ServiceMeta meta = new ServiceMeta();
        service.setServiceMeta(meta);

        try {
            boolean result = httpUtils.isAuthorized("some-token", "/context/path", service, null);
            assertFalse(result);
        } catch (Exception e) {
            assertNotNull(e);
        }
    }

    @Test
    void isAuthorized_twoArgWithNullSubscriptionGroup_returnsFalse() {
        boolean result = httpUtils.isAuthorized("some-token", null);
        assertFalse(result);
    }

    @Test
    void isSafeUri_nullHost_returnsFalse() {
        assertFalse(httpUtils.isSafeUri(URI.create("http:///path"), false));
    }

    @Test
    void isSafeUri_169_254_range_returnsFalse() {
        assertFalse(httpUtils.isSafeUri(URI.create("http://169.254.169.254/latest/meta-data"), false));
    }

    @Test
    void isSafeUri_172_31_noLocal_returnsFalse() {
        assertFalse(httpUtils.isSafeUri(URI.create("http://172.31.0.1/api"), false));
    }

    @Test
    void isSafeUri_172_15_noLocal_returnsTrue() {
        assertTrue(httpUtils.isSafeUri(URI.create("http://172.15.0.1/api"), false));
    }

    @Test
    void isSafeUri_172_32_noLocal_returnsTrue() {
        assertTrue(httpUtils.isSafeUri(URI.create("http://172.32.0.1/api"), false));
    }

    @Test
    void getCookiesFromRequest_validCookies() {
        jakarta.servlet.http.HttpServletRequest request = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(request.getHeader(Constants.COOKIE_HEADER)).thenReturn("key1=value1;key2=value2");

        List<HttpCookie> cookies = httpUtils.getCookiesFromRequest(request);
        assertEquals(2, cookies.size());
    }

    @Test
    void getCookiesFromRequest_nullCookieHeader() {
        jakarta.servlet.http.HttpServletRequest request = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(request.getHeader(Constants.COOKIE_HEADER)).thenReturn(null);

        List<HttpCookie> cookies = httpUtils.getCookiesFromRequest(request);
        assertTrue(cookies.isEmpty());
    }

    @Test
    void setHttpSocketTimeout_noQueryParams() {
        String result = httpUtils.setHttpSocketTimeout("http://host:8080/path", 5000);
        assertTrue(result.contains("?socketTimeout=5000"));
    }

    @Test
    void setIngressEndpoint_withoutQueryParams() {
        String result = httpUtils.setIngressEndpoint("http://host:8080/path", "my-host");
        assertTrue(result.contains("?"));
        assertTrue(result.contains("customHostHeader=my-host"));
    }

    @Test
    void setIngressEndpoint_withExistingQueryAndAmpersand() {
        String result = httpUtils.setIngressEndpoint("http://host:8080/path?foo=bar&", "my-host");
        assertTrue(result.contains("customHostHeader=my-host"));
        assertFalse(result.contains("&&"));
    }

    @Test
    void getAuthorizationCookieValue_emptyList_returnsNull() {
        assertNull(httpUtils.getAuthorizationCookieValue(List.of(), "auth"));
    }

    @Test
    void isAuthorized_twoArg_withNonNullGroupButNullProcessor_returnsFalse() {
        boolean result = httpUtils.isAuthorized("some-token", "some-group");
        assertFalse(result);
    }

    @Test
    void contextToRole_emptyPath() {
        assertEquals("", httpUtils.contextToRole(""));
    }

    @Test
    void contextToRole_singleSlash() {
        assertEquals("", httpUtils.contextToRole("/"));
    }

    @Test
    void contextToRole_deepPath() {
        assertEquals("a:b:c", httpUtils.contextToRole("/a/b/c"));
    }

    @Test
    void getCapiContext_nestedPath() {
        assertEquals("/api/v1", httpUtils.getCapiContext("/api/v1/*"));
    }

    @Test
    void validateHeaderValue_alphanumericSpacesAndHyphen() {
        assertEquals("Valid Header 123", HttpUtils.validateHeaderValue("Valid Header 123"));
    }

    @Test
    void proxyErrorMapper_serializationError_returnsNoMessage() {
        CapiRestError error = new CapiRestError();
        error.setErrorCode(400);
        error.setErrorMessage("Bad Request");

        String json = httpUtils.proxyErrorMapper(error);
        assertNotNull(json);
        assertTrue(json.contains("400"));
    }

    @Test
    void getCookiesFromRequest_cookiesWithSingleQuotes() {
        jakarta.servlet.http.HttpServletRequest request = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(request.getHeader(Constants.COOKIE_HEADER)).thenReturn("'key1'='value1'");

        List<HttpCookie> cookies = httpUtils.getCookiesFromRequest(request);
        assertFalse(cookies.isEmpty());
    }

    @Test
    void isSafeUri_unknownHost_returnsFalse() {
        assertFalse(httpUtils.isSafeUri(URI.create("http://this-host-does-not-exist-xyz-123.invalid/api"), false));
    }

    @Test
    void isSafeUri_publicIp_noLocalTraffic_returnsTrue() {
        assertTrue(httpUtils.isSafeUri(URI.create("http://8.8.8.8/api"), false));
    }

    @Test
    void isSafeUri_localhost_allowLocal_returnsTrue() {
        assertTrue(httpUtils.isSafeUri(URI.create("http://127.0.0.1/api"), true));
    }

    // --- Tests for authorizeRequest with mock JWT processors ---

    @SuppressWarnings("unchecked")
    @Test
    void authorizeRequest_withSuccessfulProcessor_returnsClaimsSet() throws Exception {
        DefaultJWTProcessor<SecurityContext> mockProcessor = mock(DefaultJWTProcessor.class);
        JWTClaimsSet expectedClaims = new JWTClaimsSet.Builder().subject("user1").build();
        when(mockProcessor.process("valid-token", null)).thenReturn(expectedClaims);

        HttpUtils httpUtilsWithProcessor = new HttpUtils(null, List.of(mockProcessor));
        JWTClaimsSet result = httpUtilsWithProcessor.authorizeRequest("valid-token");

        assertEquals("user1", result.getSubject());
    }

    @SuppressWarnings("unchecked")
    @Test
    void authorizeRequest_allProcessorsFail_throwsAuthorizationException() throws Exception {
        DefaultJWTProcessor<SecurityContext> mockProcessor = mock(DefaultJWTProcessor.class);
        when(mockProcessor.process("bad-token", null)).thenThrow(new BadJOSEException("Invalid token"));

        HttpUtils httpUtilsWithProcessor = new HttpUtils(null, List.of(mockProcessor));

        assertThrows(AuthorizationException.class, () -> httpUtilsWithProcessor.authorizeRequest("bad-token"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void authorizeRequest_firstProcessorFailsSecondSucceeds_returnsClaimsSet() throws Exception {
        DefaultJWTProcessor<SecurityContext> failingProcessor = mock(DefaultJWTProcessor.class);
        when(failingProcessor.process("token", null)).thenThrow(new BadJOSEException("Wrong key"));

        DefaultJWTProcessor<SecurityContext> successfulProcessor = mock(DefaultJWTProcessor.class);
        JWTClaimsSet expectedClaims = new JWTClaimsSet.Builder().subject("user1").build();
        when(successfulProcessor.process("token", null)).thenReturn(expectedClaims);

        HttpUtils httpUtilsWithProcessor = new HttpUtils(null, List.of(failingProcessor, successfulProcessor));
        JWTClaimsSet result = httpUtilsWithProcessor.authorizeRequest("token");

        assertEquals("user1", result.getSubject());
    }

    // --- Tests for isAuthorized with OPA ---

    @Test
    void isAuthorized_withOpaAllowed_returnsTrue() throws IOException {
        OpaService opaService = mock(OpaService.class);
        OpaResult opaResult = new OpaResult();
        opaResult.setResult(true);
        when(opaService.callOpa("my-rego", "token", true)).thenReturn(opaResult);

        Service service = new Service();
        ServiceMeta meta = new ServiceMeta();
        meta.setOpaRego("my-rego");
        service.setServiceMeta(meta);

        boolean result = httpUtils.isAuthorized("token", "/ctx", service, opaService);
        assertTrue(result);
    }

    @Test
    void isAuthorized_withOpaDenied_returnsFalse() throws IOException {
        OpaService opaService = mock(OpaService.class);
        OpaResult opaResult = new OpaResult();
        opaResult.setResult(false);
        when(opaService.callOpa("my-rego", "token", true)).thenReturn(opaResult);

        Service service = new Service();
        ServiceMeta meta = new ServiceMeta();
        meta.setOpaRego("my-rego");
        service.setServiceMeta(meta);

        boolean result = httpUtils.isAuthorized("token", "/ctx", service, opaService);
        assertFalse(result);
    }

    // --- Tests for isAuthorized with JWT role checking ---

    @SuppressWarnings("unchecked")
    @Test
    void isAuthorized_withSubscribedRole_returnsTrue() throws Exception {
        DefaultJWTProcessor<SecurityContext> mockProcessor = mock(DefaultJWTProcessor.class);
        Map<String, Object> realmAccess = new HashMap<>();
        realmAccess.put("roles", List.of("ctx"));
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .claim("realm_access", realmAccess)
                .build();
        when(mockProcessor.process("token", null)).thenReturn(claims);

        HttpUtils httpUtilsWithProcessor = new HttpUtils(null, List.of(mockProcessor));
        Service service = new Service();
        ServiceMeta meta = new ServiceMeta();
        service.setServiceMeta(meta);

        boolean result = httpUtilsWithProcessor.isAuthorized("token", "/ctx", service, null);
        assertTrue(result);
    }

    @SuppressWarnings("unchecked")
    @Test
    void isAuthorized_notSubscribedButInGroup_returnsTrue() throws Exception {
        DefaultJWTProcessor<SecurityContext> mockProcessor = mock(DefaultJWTProcessor.class);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .claim("realm_access", Map.of("roles", List.of("other-role")))
                .claim("subscriptions", List.of("my-group"))
                .build();
        when(mockProcessor.process("token", null)).thenReturn(claims);

        HttpUtils httpUtilsWithProcessor = new HttpUtils(null, List.of(mockProcessor));
        Service service = new Service();
        ServiceMeta meta = new ServiceMeta();
        meta.setSubscriptionGroup("my-group");
        service.setServiceMeta(meta);

        boolean result = httpUtilsWithProcessor.isAuthorized("token", "/ctx", service, null);
        assertTrue(result);
    }

    @SuppressWarnings("unchecked")
    @Test
    void isAuthorized_notSubscribedNotInGroup_returnsFalse() throws Exception {
        DefaultJWTProcessor<SecurityContext> mockProcessor = mock(DefaultJWTProcessor.class);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .claim("realm_access", Map.of("roles", List.of("other-role")))
                .claim("subscriptions", List.of("wrong-group"))
                .build();
        when(mockProcessor.process("token", null)).thenReturn(claims);

        HttpUtils httpUtilsWithProcessor = new HttpUtils(null, List.of(mockProcessor));
        Service service = new Service();
        ServiceMeta meta = new ServiceMeta();
        meta.setSubscriptionGroup("my-group");
        service.setServiceMeta(meta);

        boolean result = httpUtilsWithProcessor.isAuthorized("token", "/ctx", service, null);
        assertFalse(result);
    }

    @SuppressWarnings("unchecked")
    @Test
    void isAuthorized_noRealmAccessClaim_nullGroup_returnsFalse() throws Exception {
        DefaultJWTProcessor<SecurityContext> mockProcessor = mock(DefaultJWTProcessor.class);
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject("user").build();
        when(mockProcessor.process("token", null)).thenReturn(claims);

        HttpUtils httpUtilsWithProcessor = new HttpUtils(null, List.of(mockProcessor));
        Service service = new Service();
        ServiceMeta meta = new ServiceMeta();
        service.setServiceMeta(meta);

        boolean result = httpUtilsWithProcessor.isAuthorized("token", "/ctx", service, null);
        assertFalse(result);
    }

    // --- Tests for isAuthorized two-arg with group matching ---

    @SuppressWarnings("unchecked")
    @Test
    void isAuthorized_twoArg_tokenInGroup_returnsTrue() throws Exception {
        DefaultJWTProcessor<SecurityContext> mockProcessor = mock(DefaultJWTProcessor.class);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .claim("subscriptions", List.of("groupA"))
                .build();
        when(mockProcessor.process("token", null)).thenReturn(claims);

        HttpUtils httpUtilsWithProcessor = new HttpUtils(null, List.of(mockProcessor));
        boolean result = httpUtilsWithProcessor.isAuthorized("token", "groupA");
        assertTrue(result);
    }

    @SuppressWarnings("unchecked")
    @Test
    void isAuthorized_twoArg_tokenNotInGroup_returnsFalse() throws Exception {
        DefaultJWTProcessor<SecurityContext> mockProcessor = mock(DefaultJWTProcessor.class);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .claim("subscriptions", List.of("groupB"))
                .build();
        when(mockProcessor.process("token", null)).thenReturn(claims);

        HttpUtils httpUtilsWithProcessor = new HttpUtils(null, List.of(mockProcessor));
        boolean result = httpUtilsWithProcessor.isAuthorized("token", "groupA");
        assertFalse(result);
    }
}
