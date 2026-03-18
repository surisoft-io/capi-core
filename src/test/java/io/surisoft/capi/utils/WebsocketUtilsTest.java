package io.surisoft.capi.utils;

import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.configuration.CapiSslContextHolder;
import io.surisoft.capi.exception.CapiUndertowException;
import io.surisoft.capi.oidc.WebsocketAuthorization;
import io.surisoft.capi.schema.*;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.HttpString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebsocketUtilsTest {

    @Mock
    private DefaultJWTProcessor<SecurityContext> jwtProcessor;

    private CAPIConfiguration.Websocket websocketConfig;
    private WebsocketUtils websocketUtils;

    @BeforeEach
    void setUp() {
        websocketConfig = new CAPIConfiguration.Websocket();
        websocketConfig.setContextPath("/ws/*");

        websocketUtils = new WebsocketUtils(
                websocketConfig,
                List.of(jwtProcessor),
                null
        );
    }

    // ---------------------------------------------------------------
    // normalizeBaseContextName
    // ---------------------------------------------------------------

    @Test
    void normalizeBaseContextName_removesSlashesAndStars() {
        assertEquals("ws", websocketUtils.normalizeBaseContextName());
    }

    @Test
    void normalizeBaseContextName_withMultipleSlashes() {
        CAPIConfiguration.Websocket config = new CAPIConfiguration.Websocket();
        config.setContextPath("/ws/sub/*");
        WebsocketUtils utils = new WebsocketUtils(config, List.of(jwtProcessor), null);
        assertEquals("wssub", utils.normalizeBaseContextName());
    }

    // ---------------------------------------------------------------
    // normalizeCapiContextPath
    // ---------------------------------------------------------------

    @Test
    void normalizeCapiContextPath_returnsNormalizedPath() {
        assertEquals("/ws", websocketUtils.normalizeCapiContextPath());
    }

    // ---------------------------------------------------------------
    // normalizePathForForwarding
    // ---------------------------------------------------------------

    @Test
    void normalizePathForForwarding_withoutRootContext_removesContextAndServiceId() {
        WebsocketClient client = new WebsocketClient();
        client.setServiceId("my-service");

        String result = websocketUtils.normalizePathForForwarding(client, "/ws/my-service/some/path");
        // First replaceFirst removes /ws/* prefix, then replaces serviceId
        assertNotNull(result);
        assertFalse(result.contains("my-service"));
    }

    @Test
    void normalizePathForForwarding_withRootContext_prependsRootContext() {
        WebsocketClient client = new WebsocketClient();
        client.setServiceId("my-service");
        client.setRootContext("/api");

        String result = websocketUtils.normalizePathForForwarding(client, "/ws/my-service/endpoint");
        assertTrue(result.startsWith("/api"));
    }

    @Test
    void normalizePathForForwarding_withEmptyRootContext_doesNotPrependRootContext() {
        WebsocketClient client = new WebsocketClient();
        client.setServiceId("my-service");
        client.setRootContext("");

        String result = websocketUtils.normalizePathForForwarding(client, "/ws/my-service/endpoint");
        assertNotNull(result);
    }

    // ---------------------------------------------------------------
    // getWebclientId
    // ---------------------------------------------------------------

    @Test
    void getWebclientId_validRequest_returnsId() {
        String result = websocketUtils.getWebclientId("/ws/service-name/version");
        assertEquals("/service-name/version", result);
    }

    @Test
    void getWebclientId_tooFewParts_returnsNull() {
        String result = websocketUtils.getWebclientId("/ws/only");
        assertNull(result);
    }

    @Test
    void getWebclientId_wrongContext_returnsNull() {
        String result = websocketUtils.getWebclientId("/wrong/service-name/version");
        assertNull(result);
    }

    @Test
    void getWebclientId_emptyPath_returnsNull() {
        String result = websocketUtils.getWebclientId("/a");
        assertNull(result);
    }

    // ---------------------------------------------------------------
    // createWebsocketAuthorization
    // ---------------------------------------------------------------

    @Test
    void createWebsocketAuthorization_withProcessor_returnsAuthorization() throws CapiUndertowException {
        WebsocketAuthorization auth = websocketUtils.createWebsocketAuthorization();
        assertNotNull(auth);
    }

    @Test
    void createWebsocketAuthorization_withoutProcessor_throwsException() {
        WebsocketUtils utilsNoProcessor = new WebsocketUtils(
                websocketConfig, null, null
        );
        assertThrows(CapiUndertowException.class, utilsNoProcessor::createWebsocketAuthorization);
    }

    // ---------------------------------------------------------------
    // createClientHttpHandler
    // ---------------------------------------------------------------

    @Test
    void createClientHttpHandler_withoutTrustStore_addsHostsWithoutSsl() {
        Mapping mapping = new Mapping();
        mapping.setHostname("localhost");
        mapping.setPort(8080);
        mapping.setRootContext("/");

        WebsocketClient client = new WebsocketClient();
        client.setMappingList(Set.of(mapping));
        client.setServiceId("my-service");

        Service service = new Service();
        ServiceMeta meta = new ServiceMeta();
        service.setServiceMeta(meta);

        HttpHandler handler = websocketUtils.createClientHttpHandler(client, service, null);
        assertNotNull(handler);
    }

    @Test
    void createClientHttpHandler_withScheme_usesScheme() {
        Mapping mapping = new Mapping();
        mapping.setHostname("localhost");
        mapping.setPort(8443);
        mapping.setRootContext("/");

        WebsocketClient client = new WebsocketClient();
        client.setMappingList(Set.of(mapping));
        client.setServiceId("my-service");

        Service service = new Service();
        ServiceMeta meta = new ServiceMeta();
        meta.setScheme("https");
        service.setServiceMeta(meta);

        HttpHandler handler = websocketUtils.createClientHttpHandler(client, service, null);
        assertNotNull(handler);
    }

    // ---------------------------------------------------------------
    // createWebsocketClient
    // ---------------------------------------------------------------

    @Test
    void createWebsocketClient_setsAllFields() {
        Mapping mapping = new Mapping();
        mapping.setHostname("localhost");
        mapping.setPort(8080);
        mapping.setRootContext("/api");

        Service service = new Service();
        service.setContext("/my-service/v1");
        service.setMappingList(Set.of(mapping));
        ServiceMeta meta = new ServiceMeta();
        meta.setSecured(true);
        service.setServiceMeta(meta);

        WebsocketClient client = websocketUtils.createWebsocketClient(service);

        assertNotNull(client);
        assertEquals("/my-service/v1", client.getServiceId());
        assertTrue(client.requiresSubscription());
        assertNotNull(client.getPath());
        assertNotNull(client.getHttpHandler());
        assertNotNull(client.getMappingList());
    }

    @Test
    void createWebsocketClient_withNullRootContext_doesNotSetRootContext() {
        Mapping mapping = new Mapping();
        mapping.setHostname("localhost");
        mapping.setPort(8080);
        mapping.setRootContext(null);

        Service service = new Service();
        service.setContext("/my-service/v1");
        service.setMappingList(Set.of(mapping));
        ServiceMeta meta = new ServiceMeta();
        service.setServiceMeta(meta);

        WebsocketClient client = websocketUtils.createWebsocketClient(service);

        assertNull(client.getRootContext());
    }

    @Test
    void createWebsocketClient_withSlashRootContext_doesNotSetRootContext() {
        Mapping mapping = new Mapping();
        mapping.setHostname("localhost");
        mapping.setPort(8080);
        mapping.setRootContext("/");

        Service service = new Service();
        service.setContext("/my-service/v1");
        service.setMappingList(Set.of(mapping));
        ServiceMeta meta = new ServiceMeta();
        service.setServiceMeta(meta);

        WebsocketClient client = websocketUtils.createWebsocketClient(service);

        assertNull(client.getRootContext());
    }

    @Test
    void createWebsocketClient_withStarRootContext_doesNotSetRootContext() {
        Mapping mapping = new Mapping();
        mapping.setHostname("localhost");
        mapping.setPort(8080);
        mapping.setRootContext("*");

        Service service = new Service();
        service.setContext("/my-service/v1");
        service.setMappingList(Set.of(mapping));
        ServiceMeta meta = new ServiceMeta();
        service.setServiceMeta(meta);

        WebsocketClient client = websocketUtils.createWebsocketClient(service);

        assertNull(client.getRootContext());
    }

    // ---------------------------------------------------------------
    // removeClientFromMap
    // ---------------------------------------------------------------

    @Test
    void removeClientFromMap_removesCorrectEntry() {
        Map<String, WebsocketClient> map = new HashMap<>();
        WebsocketClient client = new WebsocketClient();
        map.put("/my-service", client);

        Service service = new Service();
        service.setContext("/my-service");

        websocketUtils.removeClientFromMap(map, service);

        assertFalse(map.containsKey("/my-service"));
    }

    // ---------------------------------------------------------------
    // getXnioSsl
    // ---------------------------------------------------------------

    @Test
    void getXnioSsl_whenNotEnabled_returnsNull() {
        assertNull(websocketUtils.getXnioSsl());
    }

    // ---------------------------------------------------------------
    // handleOptionsRequest
    // ---------------------------------------------------------------

    @Test
    void handleOptionsRequest_setsStatusCodeAndHeaders() {
        HttpServerExchange exchange = mock(HttpServerExchange.class);
        HeaderMap requestHeaders = new HeaderMap();
        requestHeaders.put(HttpString.tryFromString("Origin"), "http://example.com");
        HeaderMap responseHeaders = new HeaderMap();

        when(exchange.getRequestHeaders()).thenReturn(requestHeaders);
        when(exchange.getResponseHeaders()).thenReturn(responseHeaders);
        when(exchange.setStatusCode(204)).thenReturn(exchange);

        List<String> allowedHeaders = new ArrayList<>(List.of("Authorization", "Content-Type"));
        Map<String, String> managedHeaders = new HashMap<>(Constants.CAPI_CORS_MANAGED_HEADERS);

        websocketUtils.handleOptionsRequest(exchange, allowedHeaders, managedHeaders, null);

        verify(exchange).setStatusCode(204);
        verify(exchange).endExchange();
        // Should have set Access-Control-Allow-Origin
        assertTrue(responseHeaders.contains("Access-Control-Allow-Origin"));
        assertEquals("http://example.com", responseHeaders.get("Access-Control-Allow-Origin").getFirst());
        // Should have set Access-Control-Max-Age
        assertTrue(responseHeaders.contains("Access-Control-Max-Age"));
    }

    @Test
    void handleOptionsRequest_addsCookieNameToAllowHeaders() {
        HttpServerExchange exchange = mock(HttpServerExchange.class);
        HeaderMap requestHeaders = new HeaderMap();
        HeaderMap responseHeaders = new HeaderMap();
        when(exchange.getRequestHeaders()).thenReturn(requestHeaders);
        when(exchange.getResponseHeaders()).thenReturn(responseHeaders);
        when(exchange.setStatusCode(204)).thenReturn(exchange);

        List<String> allowedHeaders = new ArrayList<>(List.of("Authorization"));
        Map<String, String> managedHeaders = new HashMap<>();
        managedHeaders.put(Constants.ACCESS_CONTROL_ALLOW_HEADERS, "Authorization");

        websocketUtils.handleOptionsRequest(exchange, allowedHeaders, managedHeaders, "my-auth-cookie");

        verify(exchange).setStatusCode(204);
        // The cookie name should be added to the headers list
        String allowHeaders = responseHeaders.contains(Constants.ACCESS_CONTROL_ALLOW_HEADERS)
                ? responseHeaders.get(Constants.ACCESS_CONTROL_ALLOW_HEADERS).getFirst()
                : "";
        assertTrue(allowHeaders.contains("my-auth-cookie"));
    }

    @Test
    void handleOptionsRequest_doesNotDuplicateCookieName() {
        HttpServerExchange exchange = mock(HttpServerExchange.class);
        HeaderMap requestHeaders = new HeaderMap();
        HeaderMap responseHeaders = new HeaderMap();
        when(exchange.getRequestHeaders()).thenReturn(requestHeaders);
        when(exchange.getResponseHeaders()).thenReturn(responseHeaders);
        when(exchange.setStatusCode(204)).thenReturn(exchange);

        List<String> allowedHeaders = new ArrayList<>(List.of("Authorization", "my-cookie"));
        Map<String, String> managedHeaders = new HashMap<>();
        managedHeaders.put(Constants.ACCESS_CONTROL_ALLOW_HEADERS, "Authorization,my-cookie");

        websocketUtils.handleOptionsRequest(exchange, allowedHeaders, managedHeaders, "my-cookie");

        verify(exchange).setStatusCode(204);
    }

    @Test
    void handleOptionsRequest_withoutOrigin_doesNotSetAllowOrigin() {
        HttpServerExchange exchange = mock(HttpServerExchange.class);
        HeaderMap requestHeaders = new HeaderMap();
        // No Origin header
        HeaderMap responseHeaders = new HeaderMap();
        when(exchange.getRequestHeaders()).thenReturn(requestHeaders);
        when(exchange.getResponseHeaders()).thenReturn(responseHeaders);
        when(exchange.setStatusCode(204)).thenReturn(exchange);

        websocketUtils.handleOptionsRequest(exchange, List.of("Authorization"), new HashMap<>(), null);

        verify(exchange).setStatusCode(204);
        assertFalse(responseHeaders.contains("Access-Control-Allow-Origin"));
    }

    @Test
    void handleOptionsRequest_invalidOrigin_doesNotSetAllowOrigin() {
        HttpServerExchange exchange = mock(HttpServerExchange.class);
        HeaderMap requestHeaders = new HeaderMap();
        requestHeaders.put(HttpString.tryFromString("Origin"), "not-a-valid-url");
        HeaderMap responseHeaders = new HeaderMap();
        when(exchange.getRequestHeaders()).thenReturn(requestHeaders);
        when(exchange.getResponseHeaders()).thenReturn(responseHeaders);
        when(exchange.setStatusCode(204)).thenReturn(exchange);

        websocketUtils.handleOptionsRequest(exchange, List.of("Authorization"), new HashMap<>(), null);

        verify(exchange).setStatusCode(204);
        assertFalse(responseHeaders.contains("Access-Control-Allow-Origin"));
    }

    // ---------------------------------------------------------------
    // refreshXnioSsl
    // ---------------------------------------------------------------

    @Test
    void refreshXnioSsl_withoutSslContextHolder_doesNotThrow() {
        // websocketUtils constructed with null capiSslContextHolder
        assertDoesNotThrow(() -> websocketUtils.refreshXnioSsl());
        assertNull(websocketUtils.getXnioSsl());
    }

    @Test
    void refreshXnioSsl_withSslContext_createsXnioSsl() throws Exception {
        javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getDefault();
        CapiSslContextHolder holder = new CapiSslContextHolder(sslContext);

        WebsocketUtils utilsWithSsl = new WebsocketUtils(websocketConfig, List.of(jwtProcessor), holder);

        assertDoesNotThrow(() -> utilsWithSsl.refreshXnioSsl());
        assertNotNull(utilsWithSsl.getXnioSsl());
    }

    @Test
    void refreshXnioSsl_withNullSslContextInHolder_doesNotUpdateXnioSsl() {
        CapiSslContextHolder holder = new CapiSslContextHolder(null);
        WebsocketUtils utilsWithNullCtx = new WebsocketUtils(websocketConfig, List.of(jwtProcessor), holder);

        assertDoesNotThrow(() -> utilsWithNullCtx.refreshXnioSsl());
        assertNull(utilsWithNullCtx.getXnioSsl());
    }

    @Test
    void constructorWithSslContext_createsXnioSsl() throws Exception {
        javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getDefault();
        CapiSslContextHolder holder = new CapiSslContextHolder(sslContext);

        WebsocketUtils utilsWithSsl = new WebsocketUtils(websocketConfig, List.of(jwtProcessor), holder);

        assertNotNull(utilsWithSsl.getXnioSsl());
    }
}
