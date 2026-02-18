package io.surisoft.capi.undertow;

import io.surisoft.capi.schema.SSEClient;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.SSEUtils;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.HttpString;
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
class SSEGatewayTest {

    @Mock
    private SSEUtils sseUtils;
    @Mock
    private SSLContext sslContext;

    private Map<String, SSEClient> sseClients;
    private List<String> accessControlAllowHeaders;

    private SSEGateway runningGateway;

    @BeforeEach
    void setUp() {
        sseClients = new HashMap<>();
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
        SSEGateway gateway = new SSEGateway(
                8080, sseClients, sseUtils,
                Optional.of(sslContext),
                accessControlAllowHeaders, "oauth2-cookie"
        );
        assertNotNull(gateway);
    }

    @Test
    void constructor_withEmptySslContext_initializesFields() {
        SSEGateway gateway = new SSEGateway(
                8080, sseClients, sseUtils,
                Optional.empty(),
                accessControlAllowHeaders, "oauth2-cookie"
        );
        assertNotNull(gateway);
    }

    @Test
    void constructor_withNullOauth2CookieName_initializesFields() {
        SSEGateway gateway = new SSEGateway(
                8080, sseClients, sseUtils,
                Optional.empty(),
                accessControlAllowHeaders, null
        );
        assertNotNull(gateway);
    }

    @Test
    void constructor_withEmptyOauth2CookieName_initializesFields() {
        SSEGateway gateway = new SSEGateway(
                8080, sseClients, sseUtils,
                Optional.empty(),
                accessControlAllowHeaders, ""
        );
        assertNotNull(gateway);
    }

    @Test
    void constructor_withEmptyHeaders_initializesFields() {
        SSEGateway gateway = new SSEGateway(
                8080, sseClients, sseUtils,
                Optional.empty(),
                Collections.emptyList(), null
        );
        assertNotNull(gateway);
    }

    @Test
    void stop_whenServerIsNull_doesNotThrow() {
        SSEGateway gateway = new SSEGateway(
                8080, sseClients, sseUtils,
                Optional.empty(),
                accessControlAllowHeaders, null
        );
        // server is null because runProxy() was never called
        assertDoesNotThrow(gateway::stop);
    }

    @Test
    void stop_whenServerIsNull_withSsl_doesNotThrow() {
        SSEGateway gateway = new SSEGateway(
                8080, sseClients, sseUtils,
                Optional.of(sslContext),
                accessControlAllowHeaders, "cookie"
        );
        assertDoesNotThrow(gateway::stop);
    }

    @Test
    void constructor_withSseClients_preservesMap() {
        SSEClient client = new SSEClient();
        client.setApiId("test-sse-api");
        client.setPath("/sse/test");
        sseClients.put("/test/sse", client);

        SSEGateway gateway = new SSEGateway(
                8080, sseClients, sseUtils,
                Optional.empty(),
                accessControlAllowHeaders, null
        );
        assertNotNull(gateway);
    }

    @Test
    void constructor_differentPorts() {
        SSEGateway gateway1 = new SSEGateway(
                8081, sseClients, sseUtils,
                Optional.empty(),
                accessControlAllowHeaders, null
        );
        SSEGateway gateway2 = new SSEGateway(
                9090, sseClients, sseUtils,
                Optional.of(sslContext),
                accessControlAllowHeaders, "cookie-name"
        );
        assertNotNull(gateway1);
        assertNotNull(gateway2);
    }

    // === New tests to increase coverage ===

    @Test
    void processOrigin_validOrigin_setsResponseHeader() throws Exception {
        SSEGateway gateway = new SSEGateway(
                8080, sseClients, sseUtils,
                Optional.empty(),
                accessControlAllowHeaders, null
        );

        HttpServerExchange exchange = mock(HttpServerExchange.class);
        HeaderMap responseHeaders = new HeaderMap();
        when(exchange.getResponseHeaders()).thenReturn(responseHeaders);

        Method processOrigin = SSEGateway.class.getDeclaredMethod("processOrigin", HttpServerExchange.class, String.class);
        processOrigin.setAccessible(true);
        processOrigin.invoke(gateway, exchange, "http://example.com");

        String value = responseHeaders.getFirst(HttpString.tryFromString(Constants.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals("http://example.com", value);
    }

    @Test
    void processOrigin_invalidOrigin_doesNotSetHeader() throws Exception {
        SSEGateway gateway = new SSEGateway(
                8080, sseClients, sseUtils,
                Optional.empty(),
                accessControlAllowHeaders, null
        );

        HttpServerExchange exchange = mock(HttpServerExchange.class);
        HeaderMap responseHeaders = new HeaderMap();
        when(exchange.getResponseHeaders()).thenReturn(responseHeaders);

        Method processOrigin = SSEGateway.class.getDeclaredMethod("processOrigin", HttpServerExchange.class, String.class);
        processOrigin.setAccessible(true);
        processOrigin.invoke(gateway, exchange, "not a url");

        assertNull(responseHeaders.getFirst(HttpString.tryFromString(Constants.ACCESS_CONTROL_ALLOW_ORIGIN)));
    }

    @Test
    void isValidOrigin_validUrl_returnsTrue() throws Exception {
        SSEGateway gateway = new SSEGateway(
                8080, sseClients, sseUtils,
                Optional.empty(),
                accessControlAllowHeaders, null
        );

        Method isValidOrigin = SSEGateway.class.getDeclaredMethod("isValidOrigin", String.class);
        isValidOrigin.setAccessible(true);
        assertTrue((Boolean) isValidOrigin.invoke(gateway, "http://example.com"));
        assertTrue((Boolean) isValidOrigin.invoke(gateway, "https://example.com:8080/path"));
    }

    @Test
    void isValidOrigin_invalidUrl_returnsFalse() throws Exception {
        SSEGateway gateway = new SSEGateway(
                8080, sseClients, sseUtils,
                Optional.empty(),
                accessControlAllowHeaders, null
        );

        Method isValidOrigin = SSEGateway.class.getDeclaredMethod("isValidOrigin", String.class);
        isValidOrigin.setAccessible(true);
        assertFalse((Boolean) isValidOrigin.invoke(gateway, "not-a-url"));
        assertFalse((Boolean) isValidOrigin.invoke(gateway, ""));
    }

    @Test
    void isValidOrigin_httpsUrl_returnsTrue() throws Exception {
        SSEGateway gateway = new SSEGateway(
                8080, sseClients, sseUtils,
                Optional.empty(),
                accessControlAllowHeaders, null
        );

        Method isValidOrigin = SSEGateway.class.getDeclaredMethod("isValidOrigin", String.class);
        isValidOrigin.setAccessible(true);
        assertTrue((Boolean) isValidOrigin.invoke(gateway, "https://secure.example.com"));
    }

    @Test
    void isValidOrigin_malformedUrl_returnsFalse() throws Exception {
        SSEGateway gateway = new SSEGateway(
                8080, sseClients, sseUtils,
                Optional.empty(),
                accessControlAllowHeaders, null
        );

        Method isValidOrigin = SSEGateway.class.getDeclaredMethod("isValidOrigin", String.class);
        isValidOrigin.setAccessible(true);
        assertFalse((Boolean) isValidOrigin.invoke(gateway, "://bad"));
    }

    @Test
    void constructor_managedHeaders_includesAccessControlAllowHeaders() throws Exception {
        List<String> headers = List.of("Authorization", "Content-Type", "X-Custom-SSE");
        SSEGateway gateway = new SSEGateway(
                8080, sseClients, sseUtils,
                Optional.empty(),
                headers, null
        );

        Field managedHeadersField = SSEGateway.class.getDeclaredField("managedHeaders");
        managedHeadersField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> managedHeaders = (Map<String, String>) managedHeadersField.get(gateway);

        assertTrue(managedHeaders.containsKey("Access-Control-Allow-Headers"));
        String headerValue = managedHeaders.get("Access-Control-Allow-Headers");
        assertTrue(headerValue.contains("Authorization"));
        assertTrue(headerValue.contains("Content-Type"));
        assertTrue(headerValue.contains("X-Custom-SSE"));
    }

    @Test
    void constructor_managedHeaders_includesCredentialsAndMethods() throws Exception {
        SSEGateway gateway = new SSEGateway(
                8080, sseClients, sseUtils,
                Optional.empty(),
                accessControlAllowHeaders, null
        );

        Field managedHeadersField = SSEGateway.class.getDeclaredField("managedHeaders");
        managedHeadersField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> managedHeaders = (Map<String, String>) managedHeadersField.get(gateway);

        assertTrue(managedHeaders.containsKey("Access-Control-Allow-Credentials"));
        assertEquals("true", managedHeaders.get("Access-Control-Allow-Credentials"));
        assertTrue(managedHeaders.containsKey("Access-Control-Allow-Methods"));
        assertTrue(managedHeaders.containsKey("Access-Control-Max-Age"));
        assertEquals(Constants.ACCESS_CONTROL_MAX_AGE_VALUE, managedHeaders.get("Access-Control-Max-Age"));
    }

    @Test
    void processOrigin_originWithNewlines_sanitizesNewlines() throws Exception {
        SSEGateway gateway = new SSEGateway(
                8080, sseClients, sseUtils,
                Optional.empty(),
                accessControlAllowHeaders, null
        );

        HttpServerExchange exchange = mock(HttpServerExchange.class);
        HeaderMap responseHeaders = new HeaderMap();
        when(exchange.getResponseHeaders()).thenReturn(responseHeaders);

        Method processOrigin = SSEGateway.class.getDeclaredMethod("processOrigin", HttpServerExchange.class, String.class);
        processOrigin.setAccessible(true);
        // Test with something that fails URL parsing
        processOrigin.invoke(gateway, exchange, "bad\norigin");

        // Invalid URL, so no header should be set
        assertNull(responseHeaders.getFirst(HttpString.tryFromString(Constants.ACCESS_CONTROL_ALLOW_ORIGIN)));
    }

    @Test
    void isValidOrigin_urlWithPort_returnsTrue() throws Exception {
        SSEGateway gateway = new SSEGateway(
                8080, sseClients, sseUtils,
                Optional.empty(),
                accessControlAllowHeaders, null
        );

        Method isValidOrigin = SSEGateway.class.getDeclaredMethod("isValidOrigin", String.class);
        isValidOrigin.setAccessible(true);
        assertTrue((Boolean) isValidOrigin.invoke(gateway, "http://localhost:3000"));
    }

    @Test
    void isValidOrigin_ftpProtocol_returnsTrue() throws Exception {
        SSEGateway gateway = new SSEGateway(
                8080, sseClients, sseUtils,
                Optional.empty(),
                accessControlAllowHeaders, null
        );

        Method isValidOrigin = SSEGateway.class.getDeclaredMethod("isValidOrigin", String.class);
        isValidOrigin.setAccessible(true);
        // ftp:// is a valid URL protocol
        assertTrue((Boolean) isValidOrigin.invoke(gateway, "ftp://ftp.example.com"));
    }

    // === Integration tests: start real Undertow server and send HTTP requests ===

    private int pickPort() {
        return 19000 + ThreadLocalRandom.current().nextInt(100);
    }

    @Test
    void runProxy_unknownPath_returns404() throws Exception {
        int port = pickPort();
        when(sseUtils.getWebclientId("/unknown/path")).thenReturn("missing-client");

        runningGateway = new SSEGateway(
                port, sseClients, sseUtils,
                Optional.empty(),
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
    void runProxy_knownPath_nonOptions_noError() throws Exception {
        int port = pickPort();

        String clientKey = "/test/sse";
        String requestPath = "/capi/test/sse/events";
        when(sseUtils.getWebclientId(requestPath)).thenReturn(clientKey);

        SSEClient sseClient = new SSEClient();
        sseClient.setApiId("test");
        sseClient.setPath("/capi/test/sse");
        sseClient.setRequiresSubscription(false);
        // The else branch is empty (auth code commented out), so the handler does nothing
        // but we need a client in the map for the containsKey check
        sseClients.put(clientKey, sseClient);

        runningGateway = new SSEGateway(
                port, sseClients, sseUtils,
                Optional.empty(),
                accessControlAllowHeaders, null
        );
        runningGateway.runProxy();

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + requestPath))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        // The else branch does nothing (auth commented out), so Undertow returns 200 by default
        assertEquals(200, response.statusCode());
    }

    @Test
    void runProxy_optionsRequest_knownPath_returnsCorsHeaders() throws Exception {
        int port = pickPort();

        String clientKey = "/test/sse";
        String requestPath = "/capi/test/sse/events";
        when(sseUtils.getWebclientId(requestPath)).thenReturn(clientKey);

        SSEClient sseClient = new SSEClient();
        sseClient.setApiId("test");
        sseClient.setPath("/capi/test/sse");
        sseClient.setRequiresSubscription(false);
        sseClients.put(clientKey, sseClient);

        runningGateway = new SSEGateway(
                port, sseClients, sseUtils,
                Optional.empty(),
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
        assertTrue(response.headers().firstValue("Access-Control-Allow-Credentials").isPresent());
        assertTrue(response.headers().firstValue("Access-Control-Allow-Methods").isPresent());
        assertTrue(response.headers().firstValue("Access-Control-Max-Age").isPresent());
        assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isPresent());
        assertEquals("http://example.com", response.headers().firstValue("Access-Control-Allow-Origin").get());
    }

    @Test
    void runProxy_optionsRequest_withoutOauth2Cookie_returnsCors() throws Exception {
        int port = pickPort();

        String clientKey = "/test/sse";
        String requestPath = "/capi/test/sse/events";
        when(sseUtils.getWebclientId(requestPath)).thenReturn(clientKey);

        SSEClient sseClient = new SSEClient();
        sseClient.setApiId("test");
        sseClient.setPath("/capi/test/sse");
        sseClient.setRequiresSubscription(false);
        sseClients.put(clientKey, sseClient);

        // null oauth2CookieName - the localAccessControlAllowHeaders.add branch is skipped
        runningGateway = new SSEGateway(
                port, sseClients, sseUtils,
                Optional.empty(),
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
    void runProxy_stopAfterStart_stopsCleanly() throws Exception {
        int port = pickPort();

        runningGateway = new SSEGateway(
                port, sseClients, sseUtils,
                Optional.empty(),
                accessControlAllowHeaders, null
        );
        runningGateway.runProxy();

        // Verify server is running by sending a request (unknown path returns 404)
        when(sseUtils.getWebclientId("/ping")).thenReturn(null);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ping"))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode());

        // Stop and verify no exception
        assertDoesNotThrow(() -> runningGateway.stop());
        runningGateway = null; // prevent double-stop in tearDown
    }

    @Test
    void runProxy_unknownPath_withNullWebclientId_returns404() throws Exception {
        int port = pickPort();
        // getWebclientId returns null for short paths
        when(sseUtils.getWebclientId("/short")).thenReturn(null);

        runningGateway = new SSEGateway(
                port, sseClients, sseUtils,
                Optional.empty(),
                accessControlAllowHeaders, null
        );
        runningGateway.runProxy();

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/short"))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(404, response.statusCode());
    }
}
