package io.surisoft.capi.undertow;

import io.surisoft.capi.exception.CapiUndertowException;
import io.surisoft.capi.oidc.WebsocketAuthorization;
import io.surisoft.capi.schema.WebsocketClient;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.WebsocketUtils;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.ServerConnection;
import io.undertow.util.HeaderMap;
import io.undertow.util.HttpString;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;

import javax.net.ssl.SSLContext;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebsocketGatewayTest {

    @Mock
    private WebsocketUtils websocketUtils;
    @Mock
    private SSLContext sslContext;

    private Map<String, WebsocketClient> webSocketClients;
    private List<String> accessControlAllowHeaders;

    private WebsocketGateway runningGateway;

    @BeforeEach
    void setUp() {
        webSocketClients = new HashMap<>();
        accessControlAllowHeaders = new ArrayList<>(List.of("Authorization", "Content-Type"));
    }

    @AfterEach
    void tearDown() {
        if (runningGateway != null) {
            runningGateway.stop();
            runningGateway = null;
        }
    }

    @Test
    void constructor_initializesFields() {
        WebsocketGateway gateway = new WebsocketGateway(
                8080, webSocketClients, websocketUtils, sslContext,
                accessControlAllowHeaders, "oauth2-cookie"
        );
        assertNotNull(gateway);
    }

    @Test
    void constructor_withNullSslContext_initializesFields() {
        WebsocketGateway gateway = new WebsocketGateway(
                8080, webSocketClients, websocketUtils, null,
                accessControlAllowHeaders, "oauth2-cookie"
        );
        assertNotNull(gateway);
    }

    @Test
    void constructor_withEmptyHeaders_initializesFields() {
        WebsocketGateway gateway = new WebsocketGateway(
                8080, webSocketClients, websocketUtils, null,
                Collections.emptyList(), null
        );
        assertNotNull(gateway);
    }

    @Test
    void constructor_withNullOauth2CookieName_initializesFields() {
        WebsocketGateway gateway = new WebsocketGateway(
                8080, webSocketClients, websocketUtils, sslContext,
                accessControlAllowHeaders, null
        );
        assertNotNull(gateway);
    }

    @Test
    void constructor_withEmptyOauth2CookieName_initializesFields() {
        WebsocketGateway gateway = new WebsocketGateway(
                8080, webSocketClients, websocketUtils, sslContext,
                accessControlAllowHeaders, ""
        );
        assertNotNull(gateway);
    }

    @Test
    void stop_whenServerIsNull_doesNotThrow() {
        WebsocketGateway gateway = new WebsocketGateway(
                8080, webSocketClients, websocketUtils, null,
                accessControlAllowHeaders, null
        );
        // server is null because runProxy() was never called
        assertDoesNotThrow(gateway::stop);
    }

    @Test
    void stop_whenServerIsNull_withSsl_doesNotThrow() {
        WebsocketGateway gateway = new WebsocketGateway(
                8080, webSocketClients, websocketUtils, sslContext,
                accessControlAllowHeaders, "cookie"
        );
        assertDoesNotThrow(gateway::stop);
    }

    @Test
    void constructor_withWebsocketClients_preservesMap() {
        WebsocketClient client = new WebsocketClient();
        client.setServiceId("test-ws-service");
        client.setPath("/ws/test");
        webSocketClients.put("/test/ws", client);

        WebsocketGateway gateway = new WebsocketGateway(
                8080, webSocketClients, websocketUtils, null,
                accessControlAllowHeaders, null
        );
        assertNotNull(gateway);
    }

    @Test
    void constructor_differentPorts() {
        WebsocketGateway gateway1 = new WebsocketGateway(
                8081, webSocketClients, websocketUtils, null,
                accessControlAllowHeaders, null
        );
        WebsocketGateway gateway2 = new WebsocketGateway(
                9090, webSocketClients, websocketUtils, null,
                accessControlAllowHeaders, null
        );
        assertNotNull(gateway1);
        assertNotNull(gateway2);
    }

    // === New tests to increase coverage ===

    @Test
    void processOrigin_validOrigin_setsResponseHeader() throws Exception {
        WebsocketGateway gateway = new WebsocketGateway(
                8080, webSocketClients, websocketUtils, null,
                accessControlAllowHeaders, null
        );

        HttpServerExchange exchange = mock(HttpServerExchange.class);
        HeaderMap responseHeaders = new HeaderMap();
        when(exchange.getResponseHeaders()).thenReturn(responseHeaders);

        Method processOrigin = WebsocketGateway.class.getDeclaredMethod("processOrigin", HttpServerExchange.class, String.class);
        processOrigin.setAccessible(true);
        processOrigin.invoke(gateway, exchange, "http://example.com");

        String value = responseHeaders.getFirst(HttpString.tryFromString(Constants.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals("http://example.com", value);
    }

    @Test
    void processOrigin_invalidOrigin_doesNotSetHeader() throws Exception {
        WebsocketGateway gateway = new WebsocketGateway(
                8080, webSocketClients, websocketUtils, null,
                accessControlAllowHeaders, null
        );

        HttpServerExchange exchange = mock(HttpServerExchange.class);
        HeaderMap responseHeaders = new HeaderMap();
        when(exchange.getResponseHeaders()).thenReturn(responseHeaders);

        Method processOrigin = WebsocketGateway.class.getDeclaredMethod("processOrigin", HttpServerExchange.class, String.class);
        processOrigin.setAccessible(true);
        processOrigin.invoke(gateway, exchange, "not a url");

        assertNull(responseHeaders.getFirst(HttpString.tryFromString(Constants.ACCESS_CONTROL_ALLOW_ORIGIN)));
    }

    @Test
    void isValidOrigin_validUrl_returnsTrue() throws Exception {
        WebsocketGateway gateway = new WebsocketGateway(
                8080, webSocketClients, websocketUtils, null,
                accessControlAllowHeaders, null
        );

        Method isValidOrigin = WebsocketGateway.class.getDeclaredMethod("isValidOrigin", String.class);
        isValidOrigin.setAccessible(true);
        assertTrue((Boolean) isValidOrigin.invoke(gateway, "http://example.com"));
        assertTrue((Boolean) isValidOrigin.invoke(gateway, "https://example.com:8080/path"));
    }

    @Test
    void isValidOrigin_invalidUrl_returnsFalse() throws Exception {
        WebsocketGateway gateway = new WebsocketGateway(
                8080, webSocketClients, websocketUtils, null,
                accessControlAllowHeaders, null
        );

        Method isValidOrigin = WebsocketGateway.class.getDeclaredMethod("isValidOrigin", String.class);
        isValidOrigin.setAccessible(true);
        assertFalse((Boolean) isValidOrigin.invoke(gateway, "not-a-url"));
        assertFalse((Boolean) isValidOrigin.invoke(gateway, ""));
    }

    @Test
    void processOrigin_originWithNewlines_sanitizes() throws Exception {
        WebsocketGateway gateway = new WebsocketGateway(
                8080, webSocketClients, websocketUtils, null,
                accessControlAllowHeaders, null
        );

        HttpServerExchange exchange = mock(HttpServerExchange.class);
        HeaderMap responseHeaders = new HeaderMap();
        when(exchange.getResponseHeaders()).thenReturn(responseHeaders);

        Method processOrigin = WebsocketGateway.class.getDeclaredMethod("processOrigin", HttpServerExchange.class, String.class);
        processOrigin.setAccessible(true);
        processOrigin.invoke(gateway, exchange, "http://example.com\n");

        // The newline should be stripped from the header value
        String value = responseHeaders.getFirst(HttpString.tryFromString(Constants.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertNotNull(value);
        assertFalse(value.contains("\n"));
        assertEquals("http://example.com", value);
    }

    @Test
    void constructor_managedHeaders_includesAccessControlAllowHeaders() throws Exception {
        List<String> headers = List.of("Authorization", "Content-Type", "X-Custom");
        WebsocketGateway gateway = new WebsocketGateway(
                8080, webSocketClients, websocketUtils, null,
                headers, null
        );

        Field managedHeadersField = WebsocketGateway.class.getDeclaredField("managedHeaders");
        managedHeadersField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> managedHeaders = (Map<String, String>) managedHeadersField.get(gateway);

        assertTrue(managedHeaders.containsKey("Access-Control-Allow-Headers"));
        String headerValue = managedHeaders.get("Access-Control-Allow-Headers");
        assertTrue(headerValue.contains("Authorization"));
        assertTrue(headerValue.contains("Content-Type"));
        assertTrue(headerValue.contains("X-Custom"));
    }

    @Test
    void constructor_managedHeaders_includesCredentialsAndMethods() throws Exception {
        WebsocketGateway gateway = new WebsocketGateway(
                8080, webSocketClients, websocketUtils, null,
                accessControlAllowHeaders, null
        );

        Field managedHeadersField = WebsocketGateway.class.getDeclaredField("managedHeaders");
        managedHeadersField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> managedHeaders = (Map<String, String>) managedHeadersField.get(gateway);

        assertTrue(managedHeaders.containsKey("Access-Control-Allow-Credentials"));
        assertTrue(managedHeaders.containsKey("Access-Control-Allow-Methods"));
        assertTrue(managedHeaders.containsKey("Access-Control-Max-Age"));
    }

    @Test
    void isValidOrigin_httpsUrl_returnsTrue() throws Exception {
        WebsocketGateway gateway = new WebsocketGateway(
                8080, webSocketClients, websocketUtils, null,
                accessControlAllowHeaders, null
        );

        Method isValidOrigin = WebsocketGateway.class.getDeclaredMethod("isValidOrigin", String.class);
        isValidOrigin.setAccessible(true);
        assertTrue((Boolean) isValidOrigin.invoke(gateway, "https://secure.example.com"));
    }

    @Test
    void isValidOrigin_malformedUrl_returnsFalse() throws Exception {
        WebsocketGateway gateway = new WebsocketGateway(
                8080, webSocketClients, websocketUtils, null,
                accessControlAllowHeaders, null
        );

        Method isValidOrigin = WebsocketGateway.class.getDeclaredMethod("isValidOrigin", String.class);
        isValidOrigin.setAccessible(true);
        assertFalse((Boolean) isValidOrigin.invoke(gateway, "://bad"));
    }

    // === Integration tests: start real Undertow server and send HTTP requests ===

    private int pickPort() {
        return 18900 + ThreadLocalRandom.current().nextInt(100);
    }

    @Test
    void runProxy_healthEndpoint_returns200() throws Exception {
        int port = pickPort();
        when(websocketUtils.createWebsocketAuthorization()).thenThrow(new CapiUndertowException("no oidc"));

        runningGateway = new WebsocketGateway(
                port, webSocketClients, websocketUtils, null,
                accessControlAllowHeaders, null
        );
        runningGateway.runProxy();

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/health"))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
    }

    @Test
    void runProxy_unknownPath_returns404() throws Exception {
        int port = pickPort();
        when(websocketUtils.createWebsocketAuthorization()).thenThrow(new CapiUndertowException("no oidc"));
        when(websocketUtils.getWebclientId("/unknown/path")).thenReturn("unknown-client");

        runningGateway = new WebsocketGateway(
                port, webSocketClients, websocketUtils, null,
                accessControlAllowHeaders, null
        );
        runningGateway.runProxy();

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/unknown/path"))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(404, response.statusCode());
    }

    @Test
    void runProxy_knownPath_noAuth_noSubscription_forwards() throws Exception {
        int port = pickPort();
        when(websocketUtils.createWebsocketAuthorization()).thenThrow(new CapiUndertowException("no oidc"));

        String clientKey = "/test/ws";
        String requestPath = "/capi/test/ws/extra";
        when(websocketUtils.getWebclientId(requestPath)).thenReturn(clientKey);
        when(websocketUtils.normalizePathForForwarding(any(WebsocketClient.class), eq(requestPath))).thenReturn("/extra");

        WebsocketClient wsClient = new WebsocketClient();
        wsClient.setServiceId("test");
        wsClient.setPath("/capi/test/ws");
        wsClient.setRequiresSubscription(false);
        // Set a mock HttpHandler that returns 200
        HttpHandler mockHandler = exchange -> {
            exchange.setStatusCode(200);
            exchange.endExchange();
        };
        wsClient.setHttpHandler(mockHandler);
        webSocketClients.put(clientKey, wsClient);

        runningGateway = new WebsocketGateway(
                port, webSocketClients, websocketUtils, null,
                accessControlAllowHeaders, null
        );
        runningGateway.runProxy();

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + requestPath))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
    }

    @Test
    void runProxy_knownPath_noAuth_requiresSubscription_returns403() throws Exception {
        int port = pickPort();
        when(websocketUtils.createWebsocketAuthorization()).thenThrow(new CapiUndertowException("no oidc"));

        String clientKey = "/test/ws";
        String requestPath = "/capi/test/ws/data";
        when(websocketUtils.getWebclientId(requestPath)).thenReturn(clientKey);

        WebsocketClient wsClient = new WebsocketClient();
        wsClient.setServiceId("test");
        wsClient.setPath("/capi/test/ws");
        wsClient.setRequiresSubscription(true);
        wsClient.setHttpHandler(exchange -> {
            exchange.setStatusCode(200);
            exchange.endExchange();
        });
        webSocketClients.put(clientKey, wsClient);

        runningGateway = new WebsocketGateway(
                port, webSocketClients, websocketUtils, null,
                accessControlAllowHeaders, null
        );
        runningGateway.runProxy();

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + requestPath))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(403, response.statusCode());
    }

    @Test
    void runProxy_createsWebsocketAuthorization_exception() throws Exception {
        int port = pickPort();
        when(websocketUtils.createWebsocketAuthorization()).thenThrow(new CapiUndertowException("no oidc provider"));

        runningGateway = new WebsocketGateway(
                port, webSocketClients, websocketUtils, null,
                accessControlAllowHeaders, null
        );
        // Should not throw even though createWebsocketAuthorization fails
        assertDoesNotThrow(() -> runningGateway.runProxy());

        // Verify it still works (health endpoint)
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/health"))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
    }

    @Test
    void runProxy_bluecoatHeaderIsRemoved() throws Exception {
        int port = pickPort();
        when(websocketUtils.createWebsocketAuthorization()).thenThrow(new CapiUndertowException("no oidc"));

        runningGateway = new WebsocketGateway(
                port, webSocketClients, websocketUtils, null,
                accessControlAllowHeaders, null
        );
        runningGateway.runProxy();

        // Send a request with BlueCoat header to /health - the handler should remove the header
        // and still return 200
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/health"))
                .header("X-BlueCoat-Via", "some-proxy")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
    }

    @Test
    void runProxy_optionsRequest_knownPath_returnsCorsHeaders() throws Exception {
        int port = pickPort();
        when(websocketUtils.createWebsocketAuthorization()).thenThrow(new CapiUndertowException("no oidc"));

        String clientKey = "/test/ws";
        String requestPath = "/capi/test/ws/stream";
        when(websocketUtils.getWebclientId(requestPath)).thenReturn(clientKey);

        WebsocketClient wsClient = new WebsocketClient();
        wsClient.setServiceId("test");
        wsClient.setPath("/capi/test/ws");
        wsClient.setRequiresSubscription(false);
        wsClient.setHttpHandler(exchange -> {
            exchange.setStatusCode(200);
            exchange.endExchange();
        });
        webSocketClients.put(clientKey, wsClient);

        runningGateway = new WebsocketGateway(
                port, webSocketClients, websocketUtils, null,
                accessControlAllowHeaders, "oauth2-cookie"
        );
        runningGateway.runProxy();

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + requestPath))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://example.com")
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(202, response.statusCode());
        // Verify CORS headers are present
        assertTrue(response.headers().firstValue("Access-Control-Allow-Credentials").isPresent());
        assertTrue(response.headers().firstValue("Access-Control-Allow-Methods").isPresent());
        assertTrue(response.headers().firstValue("Access-Control-Max-Age").isPresent());
        assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isPresent());
        assertEquals("http://example.com", response.headers().firstValue("Access-Control-Allow-Origin").get());
    }

    @Test
    void runProxy_xForwardedForHeader_doesNotThrow() throws Exception {
        int port = pickPort();
        when(websocketUtils.createWebsocketAuthorization()).thenThrow(new CapiUndertowException("no oidc"));

        runningGateway = new WebsocketGateway(
                port, webSocketClients, websocketUtils, null,
                accessControlAllowHeaders, null
        );
        runningGateway.runProxy();

        // The finally block reads X-Forwarded-For if present
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/health"))
                .header("X-Forwarded-For", "10.0.0.1")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
    }

    @Test
    void runProxy_stopAfterStart_stopsCleanly() throws Exception {
        int port = pickPort();
        when(websocketUtils.createWebsocketAuthorization()).thenThrow(new CapiUndertowException("no oidc"));

        runningGateway = new WebsocketGateway(
                port, webSocketClients, websocketUtils, null,
                accessControlAllowHeaders, null
        );
        runningGateway.runProxy();

        // Verify the server is running
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/health"))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        // Stop the server
        assertDoesNotThrow(() -> runningGateway.stop());
        runningGateway = null; // prevent double-stop in tearDown
    }

    @Test
    void runProxy_optionsRequest_withoutOauth2Cookie_returnsCors() throws Exception {
        int port = pickPort();
        when(websocketUtils.createWebsocketAuthorization()).thenThrow(new CapiUndertowException("no oidc"));

        String clientKey = "/test/ws";
        String requestPath = "/capi/test/ws/stream";
        when(websocketUtils.getWebclientId(requestPath)).thenReturn(clientKey);

        WebsocketClient wsClient = new WebsocketClient();
        wsClient.setServiceId("test");
        wsClient.setPath("/capi/test/ws");
        wsClient.setRequiresSubscription(false);
        wsClient.setHttpHandler(exchange -> {
            exchange.setStatusCode(200);
            exchange.endExchange();
        });
        webSocketClients.put(clientKey, wsClient);

        // null oauth2CookieName - so the localAccessControlAllowHeaders branch is skipped
        runningGateway = new WebsocketGateway(
                port, webSocketClients, websocketUtils, null,
                accessControlAllowHeaders, null
        );
        runningGateway.runProxy();

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + requestPath))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://example.com")
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(202, response.statusCode());
        assertTrue(response.headers().firstValue("Access-Control-Allow-Credentials").isPresent());
    }

    @Test
    void runProxy_bluecoatHeaderRemoved_thenNotFound() throws Exception {
        int port = pickPort();
        when(websocketUtils.createWebsocketAuthorization()).thenThrow(new CapiUndertowException("no oidc"));
        when(websocketUtils.getWebclientId("/some/unknown/path")).thenReturn("missing-client");

        runningGateway = new WebsocketGateway(
                port, webSocketClients, websocketUtils, null,
                accessControlAllowHeaders, null
        );
        runningGateway.runProxy();

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/some/unknown/path"))
                .header("X-BlueCoat-Via", "proxy-server")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(404, response.statusCode());
    }
}
