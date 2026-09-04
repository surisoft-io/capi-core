package io.surisoft.capi.undertow;

import io.surisoft.capi.exception.AuthorizationException;
import io.surisoft.capi.processor.ThrottleProcessor;
import io.surisoft.capi.schema.*;
import io.surisoft.capi.service.consul.ConsulCatalogService;
import io.surisoft.capi.service.OpaWasmService;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.HttpUtils;
import io.surisoft.capi.utils.WebsocketUtils;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RestGatewayTest {

    @Mock
    private HttpUtils httpUtils;
    @Mock
    private WebsocketUtils websocketUtils;

    private Cache<String, Service> serviceCache;
    private Map<String, RestClient> restClientMap;
    private List<String> allowedHeaders;
    private RestGateway runningGateway;

    @BeforeEach
    void setUp() {
        serviceCache = Cache2kBuilder.of(String.class, Service.class)
                .name("restGwTest-" + System.nanoTime())
                .eternal(true)
                .entryCapacity(100)
                .build();
        restClientMap = new HashMap<>();
        allowedHeaders = new ArrayList<>(List.of("Authorization", "Content-Type"));
        when(httpUtils.contextToRole(anyString())).thenCallRealMethod();
        setConnectedToConsul(true);
    }

    @AfterEach
    void tearDown() {
        if (runningGateway != null) {
            runningGateway.stop();
            runningGateway = null;
        }
        serviceCache.close();
        setConnectedToConsul(false);
    }

    private int pickPort() {
        return 19100 + ThreadLocalRandom.current().nextInt(100);
    }

    private RestGateway createGateway(int port) {
        RestGateway gw = new RestGateway(port, 2, "/api", restClientMap, httpUtils, serviceCache, null, allowedHeaders, "");
        gw.setWebsocketUtils(websocketUtils);
        return gw;
    }

    private RestClient createOpenRestClient(String serviceId) {
        RestClient rc = new RestClient();
        rc.setServiceId(serviceId);
        rc.setSecured(false);
        rc.setHttpHandler(exchange -> {
            exchange.setStatusCode(200);
            exchange.getResponseHeaders().put(io.undertow.util.HttpString.tryFromString("Content-Type"), "application/json");
            exchange.getResponseSender().send("{\"ok\":true}");
        });
        return rc;
    }

    private void setConnectedToConsul(boolean value) {
        try {
            Field field = ConsulCatalogService.class.getDeclaredField("connectedToConsul");
            field.setAccessible(true);
            field.set(null, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // === Health endpoint ===

    @Test
    void healthEndpoint_returns200() throws Exception {
        int port = pickPort();
        runningGateway = createGateway(port);
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("UP"));
    }

    // === Route not found ===

    @Test
    void unknownRoute_returns404() throws Exception {
        int port = pickPort();
        runningGateway = createGateway(port);
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/unknown/v1")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(404, resp.statusCode());
    }

    @Test
    void tooFewPathSegments_returns404() throws Exception {
        int port = pickPort();
        runningGateway = createGateway(port);
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/onlyone")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(404, resp.statusCode());
    }

    // === Open service proxying ===

    @Test
    void openService_returns200() throws Exception {
        int port = pickPort();
        restClientMap.put("/my-service/v1", createOpenRestClient("/my-service/v1"));
        runningGateway = createGateway(port);
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/my-service/v1")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("ok"));
    }

    @Test
    void openService_withSubPath_returns200() throws Exception {
        int port = pickPort();
        restClientMap.put("/my-service/v1", createOpenRestClient("/my-service/v1"));
        runningGateway = createGateway(port);
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/my-service/v1/some/path")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
    }

    // === Cookie stripping (end to end, through a real gateway) ===

    /** A fake but structurally realistic JWT — long enough that the duplication is the point. */
    private static final String FAKE_JWT = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NSJ9.c2lnbmF0dXJl";

    /**
     * Stands in for the backend and records what actually arrived. The exchange headers are
     * recycled once the exchange completes, so the values are copied out here and not later.
     */
    private RestClient createHeaderCapturingRestClient(String serviceId, Map<String, List<String>> captured) {
        RestClient rc = new RestClient();
        rc.setServiceId(serviceId);
        rc.setSecured(false);
        rc.setHttpHandler(exchange -> {
            for (io.undertow.util.HeaderValues values : exchange.getRequestHeaders()) {
                captured.put(values.getHeaderName().toString(), new ArrayList<>(values));
            }
            exchange.setStatusCode(200);
            exchange.getResponseSender().send("{\"ok\":true}");
        });
        return rc;
    }

    /** Same gateway the other tests build, but with a real HttpUtils wired for cookie auth. */
    private RestGateway createGatewayWithCookieAuth(int port, String cookieNameHeader) {
        RestGateway gw = new RestGateway(port, 2, "/api", restClientMap,
                new HttpUtils(cookieNameHeader, null), serviceCache, null, allowedHeaders, cookieNameHeader);
        gw.setWebsocketUtils(websocketUtils);
        return gw;
    }

    /**
     * Registers the capturing backend and returns the map it will fill. Must be called before the
     * gateway is constructed: {@link RestGateway} snapshots {@code restClientMap} in its constructor.
     */
    private Map<String, List<String>> registerCapturingBackend() {
        Map<String, List<String>> backendSaw = new ConcurrentHashMap<>();
        restClientMap.put("/cookie-service/dev", createHeaderCapturingRestClient("/cookie-service/dev", backendSaw));
        return backendSaw;
    }

    private void proxyAndExpect200(HttpRequest.Builder request) throws Exception {
        HttpResponse<String> resp = HttpClient.newHttpClient()
                .send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "the request must have reached the capturing backend");
    }

    @Test
    void forwardedRequest_dropsConsumedAuthCookieAndKeepsApplicationCookies() throws Exception {
        Map<String, List<String>> backendSaw = registerCapturingBackend();
        int port = pickPort();
        runningGateway = createGatewayWithCookieAuth(port, "x-capi-cookie");
        runningGateway.runProxy();

        proxyAndExpect200(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/cookie-service/dev/resource"))
                        .header("x-capi-cookie", "my_session")
                        .header("Cookie", "locale=en; my_session=" + FAKE_JWT + "; XSRF-TOKEN=abc"));

        assertEquals(List.of("locale=en; XSRF-TOKEN=abc"), backendSaw.get(Constants.COOKIE_HEADER));
    }

    @Test
    void forwardedRequest_stillCarriesTheTokenAsBearer() throws Exception {
        Map<String, List<String>> backendSaw = registerCapturingBackend();
        int port = pickPort();
        runningGateway = createGatewayWithCookieAuth(port, "x-capi-cookie");
        runningGateway.runProxy();

        proxyAndExpect200(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/cookie-service/dev/resource"))
                        .header("x-capi-cookie", "my_session")
                        .header("Cookie", "my_session=" + FAKE_JWT + "; locale=en"));

        assertEquals(List.of("Bearer " + FAKE_JWT), backendSaw.get(Constants.AUTHORIZATION_HEADER),
                "the backend must still be able to authenticate the caller");
    }

    @Test
    void forwardedRequest_dropsTheHeaderNamingTheAuthCookie() throws Exception {
        Map<String, List<String>> backendSaw = registerCapturingBackend();
        int port = pickPort();
        runningGateway = createGatewayWithCookieAuth(port, "x-capi-cookie");
        runningGateway.runProxy();

        proxyAndExpect200(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/cookie-service/dev/resource"))
                        .header("x-capi-cookie", "my_session")
                        .header("Cookie", "my_session=" + FAKE_JWT + "; locale=en"));

        assertNull(backendSaw.get("x-capi-cookie"));
    }

    @Test
    void forwardedRequest_dropsCookieHeaderEntirelyWhenOnlyTheCredentialWasSent() throws Exception {
        Map<String, List<String>> backendSaw = registerCapturingBackend();
        int port = pickPort();
        runningGateway = createGatewayWithCookieAuth(port, "x-capi-cookie");
        runningGateway.runProxy();

        proxyAndExpect200(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/cookie-service/dev/resource"))
                        .header("x-capi-cookie", "my_session")
                        .header("Cookie", "my_session=" + FAKE_JWT));

        assertNull(backendSaw.get(Constants.COOKIE_HEADER));
    }

    @Test
    void forwardedRequest_dropsCapiOwnSessionCookie() throws Exception {
        Map<String, List<String>> backendSaw = registerCapturingBackend();
        int port = pickPort();
        runningGateway = createGatewayWithCookieAuth(port, "x-capi-cookie");
        runningGateway.runProxy();

        proxyAndExpect200(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/cookie-service/dev/resource"))
                        .header("Cookie", "locale=en; " + Constants.CAPI_SESSION_COOKIE_NAME + "=signature"));

        assertEquals(List.of("locale=en"), backendSaw.get(Constants.COOKIE_HEADER));
    }

    @Test
    void forwardedRequest_leavesCookiesUntouchedWhenCookieAuthIsNotConfigured() throws Exception {
        Map<String, List<String>> backendSaw = registerCapturingBackend();
        int port = pickPort();
        // A real HttpUtils, but with no cookie name configured — the feature is off.
        runningGateway = createGatewayWithCookieAuth(port, null);
        runningGateway.runProxy();

        proxyAndExpect200(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/cookie-service/dev/resource"))
                        .header("Cookie", "locale=en; sticky=node-1; XSRF-TOKEN=abc"));

        assertEquals(List.of("locale=en; sticky=node-1; XSRF-TOKEN=abc"), backendSaw.get(Constants.COOKIE_HEADER));
    }

    // === CORS ===

    @Test
    void corsHeaders_setOnAllResponses() throws Exception {
        int port = pickPort();
        restClientMap.put("/my-service/v1", createOpenRestClient("/my-service/v1"));
        runningGateway = createGateway(port);
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/my-service/v1"))
                        .header("Origin", "http://example.com")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertEquals("http://example.com", resp.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
        assertEquals("true", resp.headers().firstValue("Access-Control-Allow-Credentials").orElse(null));
    }

    @Test
    void corsHeaders_notSetWithoutOrigin() throws Exception {
        int port = pickPort();
        restClientMap.put("/my-service/v1", createOpenRestClient("/my-service/v1"));
        runningGateway = createGateway(port);
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/my-service/v1")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
    }

    // === OPTIONS ===

    @Test
    void optionsRequest_returns204() throws Exception {
        int port = pickPort();
        io.surisoft.capi.configuration.CAPIConfiguration.Websocket wsConfig = new io.surisoft.capi.configuration.CAPIConfiguration.Websocket();
        wsConfig.setContextPath("/ws/*");
        WebsocketUtils realUtils = new WebsocketUtils(wsConfig, null, null);
        doAnswer(inv -> { realUtils.handleOptionsRequest(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)); return null; })
                .when(websocketUtils).handleOptionsRequest(any(), any(), any(), any());

        runningGateway = createGateway(port);
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/anything"))
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .header("Origin", "http://example.com")
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(204, resp.statusCode());
    }

    // === Event Stream rejection ===

    @Test
    void eventStream_contentType_returns400() throws Exception {
        int port = pickPort();
        restClientMap.put("/my-service/v1", createOpenRestClient("/my-service/v1"));
        runningGateway = createGateway(port);
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/my-service/v1"))
                        .header("Content-Type", "text/event-stream")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(400, resp.statusCode());
    }

    @Test
    void eventStream_accept_returns400() throws Exception {
        int port = pickPort();
        restClientMap.put("/my-service/v1", createOpenRestClient("/my-service/v1"));
        runningGateway = createGateway(port);
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/my-service/v1"))
                        .header("Accept", "text/event-stream")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(400, resp.statusCode());
    }

    // === Secured service without token ===

    @Test
    void securedService_noToken_returns403() throws Exception {
        int port = pickPort();
        RestClient rc = createOpenRestClient("/secured/v1");
        rc.setSecured(true);
        restClientMap.put("/secured/v1", rc);
        when(httpUtils.processAuthorizationAccessToken(any(io.undertow.server.HttpServerExchange.class))).thenReturn(null);
        runningGateway = createGateway(port);
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/secured/v1")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(403, resp.statusCode());
    }

    // === API Key ===

    @Test
    void apiKeyService_noHeader_returns403() throws Exception {
        int port = pickPort();
        RestClient rc = createOpenRestClient("/apikey/v1");
        rc.setApiKeyEnabled(true);
        restClientMap.put("/apikey/v1", rc);
        runningGateway = createGateway(port);
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/apikey/v1")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(403, resp.statusCode());
    }

    // === Reverse proxy headers ===

    @Test
    void reverseProxyHeaders_setWhenConfigured() throws Exception {
        int port = pickPort();
        RestClient rc = new RestClient();
        rc.setServiceId("/my-service/v1");
        rc.setSecured(false);
        rc.setHttpHandler(exchange -> {
            // RestGateway stores reverse-proxy info as attachments; CAPIProxyHandler turns them into headers.
            String xfh = exchange.getAttachment(CAPIProxyHandler.REVERSE_PROXY_HOST);
            String xfp = exchange.getAttachment(CAPIProxyHandler.REVERSE_PROXY_PREFIX);
            exchange.setStatusCode(200);
            exchange.getResponseSender().send("{\"host\":\"" + (xfh != null ? xfh : "none") + "\",\"prefix\":\"" + (xfp != null ? xfp : "none") + "\"}");
        });
        restClientMap.put("/my-service/v1", rc);
        runningGateway = createGateway(port);
        runningGateway.setReverseProxyHost("proxy.example.com");
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/my-service/v1")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("proxy.example.com"));
        assertTrue(resp.body().contains("/api/my-service/v1"));
    }

    @Test
    void publicEndpointScheme_setsForwardedProtoAttachment() throws Exception {
        int port = pickPort();
        RestClient rc = new RestClient();
        rc.setServiceId("/my-service/v1");
        rc.setSecured(false);
        rc.setHttpHandler(exchange -> {
            String proto = exchange.getAttachment(CAPIProxyHandler.REVERSE_PROXY_PROTO);
            exchange.setStatusCode(200);
            exchange.getResponseSender().send("{\"proto\":\"" + (proto != null ? proto : "none") + "\"}");
        });
        restClientMap.put("/my-service/v1", rc);
        runningGateway = createGateway(port);
        runningGateway.setPublicEndpointScheme("https");
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/my-service/v1")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"proto\":\"https\""), "expected REVERSE_PROXY_PROTO=https, got: " + resp.body());
    }

    @Test
    void publicEndpointScheme_unsetLeavesProtoAttachmentNull() throws Exception {
        int port = pickPort();
        RestClient rc = new RestClient();
        rc.setServiceId("/my-service/v1");
        rc.setSecured(false);
        rc.setHttpHandler(exchange -> {
            String proto = exchange.getAttachment(CAPIProxyHandler.REVERSE_PROXY_PROTO);
            exchange.setStatusCode(200);
            exchange.getResponseSender().send("{\"proto\":\"" + (proto != null ? proto : "none") + "\"}");
        });
        restClientMap.put("/my-service/v1", rc);
        runningGateway = createGateway(port);
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/my-service/v1")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"proto\":\"none\""), "expected no REVERSE_PROXY_PROTO attachment, got: " + resp.body());
    }

    // === keepGroup header ===

    @Test
    void keepGroup_setsCapiGroupHeader() throws Exception {
        int port = pickPort();
        RestClient rc = new RestClient();
        rc.setServiceId("/my-service/v1");
        rc.setSecured(false);
        rc.setKeepGroup(true);
        rc.setHttpHandler(exchange -> {
            String group = exchange.getRequestHeaders().contains(Constants.CAPI_GROUP_HEADER)
                    ? exchange.getRequestHeaders().get(Constants.CAPI_GROUP_HEADER).getFirst() : "none";
            exchange.setStatusCode(200);
            exchange.getResponseSender().send("{\"group\":\"" + group + "\"}");
        });
        restClientMap.put("/my-service/v1", rc);
        runningGateway = createGateway(port);
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/my-service/v1")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("/my-service/v1"));
    }

    @Test
    void keepGroup_notSet_noCapiGroupHeader() throws Exception {
        int port = pickPort();
        RestClient rc = new RestClient();
        rc.setServiceId("/my-service/v1");
        rc.setSecured(false);
        rc.setKeepGroup(false);
        rc.setHttpHandler(exchange -> {
            boolean hasGroup = exchange.getRequestHeaders().contains(Constants.CAPI_GROUP_HEADER);
            exchange.setStatusCode(200);
            exchange.getResponseSender().send("{\"hasGroup\":" + hasGroup + "}");
        });
        restClientMap.put("/my-service/v1", rc);
        runningGateway = createGateway(port);
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/my-service/v1")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("false"));
    }

    // === Path normalization ===

    @Test
    void pathNormalization_stripsContextAndServiceId() throws Exception {
        int port = pickPort();
        RestClient rc = new RestClient();
        rc.setServiceId("/my-service/v1");
        rc.setSecured(false);
        rc.setHttpHandler(exchange -> {
            exchange.setStatusCode(200);
            exchange.getResponseSender().send("{\"uri\":\"" + exchange.getRequestURI() + "\"}");
        });
        restClientMap.put("/my-service/v1", rc);
        runningGateway = createGateway(port);
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/my-service/v1/some/resource")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("/some/resource"));
        assertFalse(resp.body().contains("my-service"));
    }

    @Test
    void pathNormalization_withRootContext() throws Exception {
        int port = pickPort();
        RestClient rc = new RestClient();
        rc.setServiceId("/my-service/v1");
        rc.setRootContext("/backend");
        rc.setSecured(false);
        rc.setHttpHandler(exchange -> {
            exchange.setStatusCode(200);
            exchange.getResponseSender().send("{\"uri\":\"" + exchange.getRequestURI() + "\"}");
        });
        restClientMap.put("/my-service/v1", rc);
        runningGateway = createGateway(port);
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/my-service/v1/endpoint")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("/backend/endpoint"));
    }

    // === BlueCoat header stripping ===

    @Test
    void bluecoatHeader_isStripped() throws Exception {
        int port = pickPort();
        RestClient rc = new RestClient();
        rc.setServiceId("/my-service/v1");
        rc.setSecured(false);
        rc.setHttpHandler(exchange -> {
            boolean hasBluecoat = exchange.getRequestHeaders().contains(Constants.BLUECOAT_HEADER);
            exchange.setStatusCode(200);
            exchange.getResponseSender().send("{\"bluecoat\":" + hasBluecoat + "}");
        });
        restClientMap.put("/my-service/v1", rc);
        runningGateway = createGateway(port);
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/my-service/v1"))
                        .header(Constants.BLUECOAT_HEADER, "some-value")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("false"));
    }

    // === OpenAPI validation ===

    @Test
    void openApi_unsecuredOperation_passes() throws Exception {
        int port = pickPort();
        OpenAPI openAPI = new OpenAPI();
        Paths paths = new Paths();
        PathItem pathItem = new PathItem();
        pathItem.setGet(new Operation());
        paths.addPathItem("/items", pathItem);
        openAPI.setPaths(paths);

        RestClient rc = createOpenRestClient("/my-service/v1");
        rc.setOpenAPI(openAPI);
        restClientMap.put("/my-service/v1", rc);
        runningGateway = createGateway(port);
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/my-service/v1/items")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
    }

    @Test
    void openApi_securedOperation_noToken_returns401() throws Exception {
        int port = pickPort();
        OpenAPI openAPI = new OpenAPI();
        Paths paths = new Paths();
        PathItem pathItem = new PathItem();
        Operation op = new Operation();
        op.setSecurity(List.of(new SecurityRequirement().addList("bearer")));
        pathItem.setGet(op);
        paths.addPathItem("/secret", pathItem);
        openAPI.setPaths(paths);

        RestClient rc = createOpenRestClient("/my-service/v1");
        rc.setOpenAPI(openAPI);
        restClientMap.put("/my-service/v1", rc);
        when(httpUtils.processAuthorizationAccessToken(any(io.undertow.server.HttpServerExchange.class))).thenReturn(null);
        runningGateway = createGateway(port);
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/my-service/v1/secret")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(401, resp.statusCode());
    }

    @Test
    void openApi_unknownPath_returns400() throws Exception {
        int port = pickPort();
        OpenAPI openAPI = new OpenAPI();
        Paths paths = new Paths();
        PathItem pathItem = new PathItem();
        pathItem.setGet(new Operation());
        paths.addPathItem("/items", pathItem);
        openAPI.setPaths(paths);

        RestClient rc = createOpenRestClient("/my-service/v1");
        rc.setOpenAPI(openAPI);
        restClientMap.put("/my-service/v1", rc);
        runningGateway = createGateway(port);
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/my-service/v1/nonexistent")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(400, resp.statusCode());
    }

    @Test
    void openApi_pathParameter_matches() throws Exception {
        int port = pickPort();
        OpenAPI openAPI = new OpenAPI();
        Paths paths = new Paths();
        PathItem pathItem = new PathItem();
        pathItem.setGet(new Operation());
        paths.addPathItem("/items/{id}", pathItem);
        openAPI.setPaths(paths);

        RestClient rc = createOpenRestClient("/my-service/v1");
        rc.setOpenAPI(openAPI);
        restClientMap.put("/my-service/v1", rc);
        runningGateway = createGateway(port);
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/my-service/v1/items/42")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
    }

    @Test
    void openApi_withRootContext_validatesApiPathNotBackendPath() throws Exception {
        // Regression: OpenAPI validation must match the API-facing path ("/items"), NOT the
        // backend-forwarding path ("/backend/items"). A service with a rootContext previously
        // failed every validated call with "Call not allowed" (400) because of the extra segment.
        int port = pickPort();
        OpenAPI openAPI = new OpenAPI();
        Paths paths = new Paths();
        PathItem pathItem = new PathItem();
        pathItem.setGet(new Operation());
        paths.addPathItem("/items", pathItem);
        openAPI.setPaths(paths);

        RestClient rc = createOpenRestClient("/my-service/v1");
        rc.setRootContext("/backend");
        rc.setOpenAPI(openAPI);
        restClientMap.put("/my-service/v1", rc);
        runningGateway = createGateway(port);
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/my-service/v1/items")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
    }

    // === Stop ===

    @Test
    void stop_afterStart_stopsCleanly() throws Exception {
        int port = pickPort();
        runningGateway = createGateway(port);
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());

        assertDoesNotThrow(() -> runningGateway.stop());
        runningGateway = null;
    }

    @Test
    void stop_withoutStart_doesNotThrow() {
        RestGateway gw = createGateway(pickPort());
        assertDoesNotThrow(gw::stop);
    }

    // === Throttle check ===

    @Test
    void throttleEnabled_whenBlocked_returns429() throws Exception {
        int port = pickPort();
        RestClient rc = createOpenRestClient("/throttled/v1");
        rc.setThrottle(true);
        // Production sets this in RestTransportHandler#buildRestClient. The test
        // helper doesn't, so we set it explicitly to match the serviceCache key
        // below; otherwise the throttle lookup misses and the check is silently
        // skipped — see the original 502 failures.
        rc.setCanonicalServiceId("throttled:v1");
        restClientMap.put("/throttled/v1", rc);

        Service svc = new Service();
        svc.setId("throttled:v1");
        ServiceMeta meta = new ServiceMeta();
        svc.setServiceMeta(meta);
        serviceCache.put("throttled:v1", svc);

        ThrottleProcessor throttleProcessor = mock(ThrottleProcessor.class);
        when(throttleProcessor.canContinue(any(Service.class), isNull(), eq(false), eq(-1L), eq(-1L))).thenReturn(false);

        runningGateway = createGateway(port);
        runningGateway.setThrottleProcessor(throttleProcessor);
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/throttled/v1")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(429, resp.statusCode());
    }

    @Test
    void throttleEnabled_whenAllowed_returns200() throws Exception {
        int port = pickPort();
        RestClient rc = createOpenRestClient("/throttled/v1");
        rc.setThrottle(true);
        // See sibling test for why this is set explicitly.
        rc.setCanonicalServiceId("throttled:v1");
        restClientMap.put("/throttled/v1", rc);

        Service svc = new Service();
        svc.setId("throttled:v1");
        ServiceMeta meta = new ServiceMeta();
        svc.setServiceMeta(meta);
        serviceCache.put("throttled:v1", svc);

        ThrottleProcessor throttleProcessor = mock(ThrottleProcessor.class);
        when(throttleProcessor.canContinue(any(Service.class), isNull(), eq(false), eq(-1L), eq(-1L))).thenReturn(true);

        runningGateway = createGateway(port);
        runningGateway.setThrottleProcessor(throttleProcessor);
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/throttled/v1")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
    }

    // === API Key with valid key ===

    @Test
    void apiKeyService_validKey_returns200() throws Exception {
        int port = pickPort();
        RestClient rc = createOpenRestClient("/apikey/v1");
        rc.setApiKeyEnabled(true);
        restClientMap.put("/apikey/v1", rc);

        String rawKey = "my-secret-api-key";
        String keyHash = HttpUtils.hashApiKey(rawKey);
        ApiKeyEntry entry = new ApiKeyEntry();
        entry.setKeyHash(keyHash);
        entry.setEnabled(true);
        ApiKeyStoreEntry storeEntry = new ApiKeyStoreEntry();
        storeEntry.getKeysByHash().put(keyHash, entry);

        Cache<String, ApiKeyStoreEntry> apiKeyCache = Cache2kBuilder.of(String.class, ApiKeyStoreEntry.class)
                .name("apiKeyCacheTest-" + System.nanoTime())
                .eternal(true)
                .entryCapacity(10)
                .build();
        apiKeyCache.put("apikey:v1", storeEntry);

        runningGateway = createGateway(port);
        runningGateway.setApiKeyCache(apiKeyCache);
        runningGateway.runProxy();

        try {
            HttpResponse<String> resp = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + "/api/apikey/v1"))
                            .header("Authorization", "ApiKey " + rawKey)
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, resp.statusCode());
        } finally {
            apiKeyCache.close();
        }
    }

    @Test
    void apiKeyService_invalidKey_returns403() throws Exception {
        int port = pickPort();
        RestClient rc = createOpenRestClient("/apikey/v1");
        rc.setApiKeyEnabled(true);
        restClientMap.put("/apikey/v1", rc);

        Cache<String, ApiKeyStoreEntry> apiKeyCache = Cache2kBuilder.of(String.class, ApiKeyStoreEntry.class)
                .name("apiKeyCacheTest2-" + System.nanoTime())
                .eternal(true)
                .entryCapacity(10)
                .build();
        // No store entry for service => invalid key
        runningGateway = createGateway(port);
        runningGateway.setApiKeyCache(apiKeyCache);
        runningGateway.runProxy();

        try {
            HttpResponse<String> resp = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + "/api/apikey/v1"))
                            .header("Authorization", "ApiKey wrong-key")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(403, resp.statusCode());
        } finally {
            apiKeyCache.close();
        }
    }

    // === API Key with Bearer token falls through to OAuth2 ===

    @Test
    void apiKeyService_withBearerToken_securedService_fallsThroughToOAuth2() throws Exception {
        int port = pickPort();
        RestClient rc = createOpenRestClient("/apikey-secured/v1");
        rc.setApiKeyEnabled(true);
        rc.setSecured(true);
        restClientMap.put("/apikey-secured/v1", rc);

        // When Bearer is provided on an apiKey+secured service, checkApiKey returns null (fall through)
        // Then checkAuthorization runs. Mock it to succeed.
        when(httpUtils.processAuthorizationAccessToken(any(io.undertow.server.HttpServerExchange.class))).thenReturn("valid-token");
        when(httpUtils.isAuthorized(eq("valid-token"), isNull())).thenReturn(true);

        Cache<String, ApiKeyStoreEntry> apiKeyCache = Cache2kBuilder.of(String.class, ApiKeyStoreEntry.class)
                .name("apiKeyCacheTest3-" + System.nanoTime())
                .eternal(true)
                .entryCapacity(10)
                .build();

        runningGateway = createGateway(port);
        runningGateway.setApiKeyCache(apiKeyCache);
        runningGateway.runProxy();

        try {
            HttpResponse<String> resp = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + "/api/apikey-secured/v1"))
                            .header("Authorization", "Bearer valid-token")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            // Should pass through API key check (Bearer prefix, secured=true => fall through)
            // Then pass OAuth2 check => 200
            assertEquals(200, resp.statusCode());
        } finally {
            apiKeyCache.close();
        }
    }

    @Test
    void apiKeyService_withBearerToken_unsecuredService_returns403() throws Exception {
        int port = pickPort();
        RestClient rc = createOpenRestClient("/apikey-open/v1");
        rc.setApiKeyEnabled(true);
        rc.setSecured(false); // not secured, so Bearer does NOT fall through
        restClientMap.put("/apikey-open/v1", rc);

        runningGateway = createGateway(port);
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/apikey-open/v1"))
                        .header("Authorization", "Bearer some-token")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        // apiKeyEnabled + not secured + Bearer prefix => returns "API key required"
        assertEquals(403, resp.statusCode());
    }

    // === Secured service with subscription group ===

    @Test
    void securedService_withSubscriptionGroup_notSubscribed_returns403() throws Exception {
        int port = pickPort();
        RestClient rc = createOpenRestClient("/sub-svc/v1");
        rc.setSecured(true);
        rc.setSubscriptionGroup("premium-group");
        restClientMap.put("/sub-svc/v1", rc);

        when(httpUtils.processAuthorizationAccessToken(any(io.undertow.server.HttpServerExchange.class))).thenReturn("some-token");
        when(httpUtils.isAuthorized("some-token", "premium-group")).thenReturn(false);

        runningGateway = createGateway(port);
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/sub-svc/v1"))
                        .header("Authorization", "Bearer some-token")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(403, resp.statusCode());
    }

    @Test
    void securedService_withSubscriptionGroup_subscribed_returns200() throws Exception {
        int port = pickPort();
        RestClient rc = createOpenRestClient("/sub-svc/v1");
        rc.setSecured(true);
        rc.setSubscriptionGroup("premium-group");
        restClientMap.put("/sub-svc/v1", rc);

        when(httpUtils.processAuthorizationAccessToken(any(io.undertow.server.HttpServerExchange.class))).thenReturn("valid-token");
        when(httpUtils.isAuthorized("valid-token", "premium-group")).thenReturn(true);

        runningGateway = createGateway(port);
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/sub-svc/v1"))
                        .header("Authorization", "Bearer valid-token")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
    }

    // === OPA wasm path ===

    @Test
    void opaService_denied_returns403() throws Exception {
        int port = pickPort();
        RestClient rc = createOpenRestClient("/opa-svc/v1");
        rc.setOpaRego("my-rego-policy");
        // Production passes restClient.getCanonicalServiceId() (the stable
        // "<name>:<group>" form) to OpaWasmService.evaluate; mirror that here.
        rc.setCanonicalServiceId("opa-svc:v1");
        restClientMap.put("/opa-svc/v1", rc);

        when(httpUtils.processAuthorizationAccessToken(any(io.undertow.server.HttpServerExchange.class))).thenReturn("opa-token");

        OpaWasmService opaWasmService = mock(OpaWasmService.class);
        OpaResult denied = new OpaResult();
        denied.setResult(false);
        when(opaWasmService.isReady()).thenReturn(true);
        when(opaWasmService.hasPolicy("my-rego-policy")).thenReturn(true);
        when(opaWasmService.evaluate("opa-svc:v1", "my-rego-policy", "opa-token", true)).thenReturn(denied);

        runningGateway = createGateway(port);
        runningGateway.setOpaWasmService(opaWasmService);
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/opa-svc/v1"))
                        .header("Authorization", "Bearer opa-token")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(403, resp.statusCode());
    }

    @Test
    void opaService_allowed_returns200() throws Exception {
        int port = pickPort();
        RestClient rc = createOpenRestClient("/opa-svc/v1");
        rc.setOpaRego("my-rego-policy");
        // See sibling test for why canonical id is set explicitly.
        rc.setCanonicalServiceId("opa-svc:v1");
        restClientMap.put("/opa-svc/v1", rc);

        when(httpUtils.processAuthorizationAccessToken(any(io.undertow.server.HttpServerExchange.class))).thenReturn("opa-token");

        OpaWasmService opaWasmService = mock(OpaWasmService.class);
        OpaResult allowed = new OpaResult();
        allowed.setResult(true);
        when(opaWasmService.isReady()).thenReturn(true);
        when(opaWasmService.hasPolicy("my-rego-policy")).thenReturn(true);
        when(opaWasmService.evaluate("opa-svc:v1", "my-rego-policy", "opa-token", true)).thenReturn(allowed);

        runningGateway = createGateway(port);
        runningGateway.setOpaWasmService(opaWasmService);
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/opa-svc/v1"))
                        .header("Authorization", "Bearer opa-token")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
    }

    @Test
    void opaService_noAuthHeader_returns403() throws Exception {
        int port = pickPort();
        RestClient rc = createOpenRestClient("/opa-svc/v1");
        rc.setOpaRego("my-rego-policy");
        restClientMap.put("/opa-svc/v1", rc);

        when(httpUtils.processAuthorizationAccessToken(any(io.undertow.server.HttpServerExchange.class))).thenReturn(null);

        OpaWasmService opaWasmService = mock(OpaWasmService.class);
        runningGateway = createGateway(port);
        runningGateway.setOpaWasmService(opaWasmService);
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/opa-svc/v1"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(403, resp.statusCode());
    }

    // === Authorization propagation ===

    @Test
    void authorizationPropagation_calledOnProxy() throws Exception {
        int port = pickPort();
        restClientMap.put("/my-service/v1", createOpenRestClient("/my-service/v1"));
        runningGateway = createGateway(port);
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/my-service/v1"))
                        .header("Authorization", "Bearer some-token")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        verify(httpUtils, atLeastOnce()).propagateAuthorization(any(io.undertow.server.HttpServerExchange.class));
    }

    // === API key with no cache configured ===

    @Test
    void apiKeyService_noCacheConfigured_returns403() throws Exception {
        int port = pickPort();
        RestClient rc = createOpenRestClient("/apikey/v1");
        rc.setApiKeyEnabled(true);
        restClientMap.put("/apikey/v1", rc);

        // Do NOT set apiKeyCache on gateway
        runningGateway = createGateway(port);
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/apikey/v1"))
                        .header("Authorization", "ApiKey some-key")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(403, resp.statusCode());
    }

    // === API key disabled entry ===

    @Test
    void apiKeyService_disabledKey_returns403() throws Exception {
        int port = pickPort();
        RestClient rc = createOpenRestClient("/apikey/v1");
        rc.setApiKeyEnabled(true);
        restClientMap.put("/apikey/v1", rc);

        String rawKey = "disabled-key";
        String keyHash = HttpUtils.hashApiKey(rawKey);
        ApiKeyEntry entry = new ApiKeyEntry();
        entry.setKeyHash(keyHash);
        entry.setEnabled(false);
        ApiKeyStoreEntry storeEntry = new ApiKeyStoreEntry();
        storeEntry.getKeysByHash().put(keyHash, entry);

        Cache<String, ApiKeyStoreEntry> apiKeyCache = Cache2kBuilder.of(String.class, ApiKeyStoreEntry.class)
                .name("apiKeyCacheTest4-" + System.nanoTime())
                .eternal(true)
                .entryCapacity(10)
                .build();
        apiKeyCache.put("apikey:v1", storeEntry);

        runningGateway = createGateway(port);
        runningGateway.setApiKeyCache(apiKeyCache);
        runningGateway.runProxy();

        try {
            HttpResponse<String> resp = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + "/api/apikey/v1"))
                            .header("Authorization", "ApiKey " + rawKey)
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(403, resp.statusCode());
        } finally {
            apiKeyCache.close();
        }
    }

    // === Access log: structured fields + external client IP ===

    /** Captures what the access logger actually emitted, arguments included. */
    private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> attachAccessLogAppender() {
        ch.qos.logback.classic.Logger accessLogger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("capi.access");
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        accessLogger.addAppender(appender);
        accessLogger.setLevel(ch.qos.logback.classic.Level.INFO);
        return appender;
    }

    /** The single access-log event for a proxied call, or a failure if none was recorded. */
    private ch.qos.logback.classic.spi.ILoggingEvent accessEventFor(String... requestHeaders) throws Exception {
        int port = pickPort();
        restClientMap.put("/my-service/v1", createOpenRestClient("/my-service/v1"));
        runningGateway = createGateway(port);
        runningGateway.runProxy();

        var appender = attachAccessLogAppender();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/my-service/v1")).GET();
        for (int i = 0; i < requestHeaders.length; i += 2) {
            builder.header(requestHeaders[i], requestHeaders[i + 1]);
        }
        HttpResponse<String> resp = HttpClient.newHttpClient()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());

        // The listener runs on exchange completion, which can trail the client's last byte.
        for (int i = 0; i < 100 && appender.list.isEmpty(); i++) {
            Thread.sleep(10);
        }
        assertFalse(appender.list.isEmpty(), "no access-log event was recorded");
        return appender.list.get(0);
    }

    /**
     * Value of a StructuredArgument field on the event, by field name.
     *
     * <p>{@code StructuredArguments.v()} yields an {@code ObjectAppendingMarker}: {@code
     * getFieldName()} is public but {@code getFieldValue()} is protected, so it has to be reached
     * through the declaring class rather than {@code getMethod}.
     */
    private Object accessField(ch.qos.logback.classic.spi.ILoggingEvent event, String field) {
        for (Object argument : event.getArgumentArray()) {
            if (argument instanceof net.logstash.logback.marker.SingleFieldAppendingMarker marker
                    && field.equals(marker.getFieldName())) {
                try {
                    var m = net.logstash.logback.marker.SingleFieldAppendingMarker.class
                            .getDeclaredMethod("getFieldValue");
                    m.setAccessible(true);
                    return m.invoke(marker);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError("could not read field " + field, e);
                }
            }
        }
        return null;
    }

    @Test
    void accessLog_emitsEachPartAsItsOwnStructuredField() throws Exception {
        var event = accessEventFor();

        assertEquals("GET", accessField(event, "http_method"));
        assertEquals("/api/my-service/v1", accessField(event, "http_path"));
        assertEquals(200, accessField(event, "http_status"));
        assertNotNull(accessField(event, "duration_ms"));
        assertNotNull(accessField(event, "client_ip"));
    }

    @Test
    void accessLog_plainTextMessageIsUnchanged() throws Exception {
        var event = accessEventFor();

        // Same shape the ACCESS-FILE appender writes: "VERB PATH STATUS Nms IP".
        assertTrue(event.getFormattedMessage().matches("GET /api/my-service/v1 200 \\d+ms \\S+"),
                "unexpected access line: " + event.getFormattedMessage());
    }

    @Test
    void xForwardedFor_isUsedForClientIp() throws Exception {
        var event = accessEventFor("X-Forwarded-For", "203.0.113.50");

        assertEquals("203.0.113.50", accessField(event, "client_ip"));
    }

    @Test
    void xForwardedForChain_logsTheLeftmostExternalClientNotTheWholeChain() throws Exception {
        var event = accessEventFor("X-Forwarded-For", "203.0.113.50, 70.41.3.18, 150.172.238.178");

        assertEquals("203.0.113.50", accessField(event, "client_ip"),
                "the external client is the leftmost hop; the rest of the chain is infrastructure");
    }

    @Test
    void noXForwardedFor_fallsBackToTheSocketAddress() throws Exception {
        var event = accessEventFor();

        assertEquals("127.0.0.1", accessField(event, "client_ip"));
    }

    // === /definitions/openapi/{serviceId} ===

    private Service createServiceWithOpenApi(String serviceId,
                                             boolean expose,
                                             boolean secure,
                                             String subscriptionGroup) {
        OpenAPI openAPI = new OpenAPI()
                .info(new io.swagger.v3.oas.models.info.Info().title("Original").version("1.0"))
                .servers(List.of(new io.swagger.v3.oas.models.servers.Server().url("http://backend")))
                .paths(new Paths().addPathItem("/hello", new PathItem()));
        ServiceMeta meta = new ServiceMeta();
        meta.setOpenApiEndpoint("http://backend/api-docs");
        meta.setExposeOpenApiDefinition(expose);
        meta.setSecureOpenApiDefinition(secure);
        meta.setSubscriptionGroup(subscriptionGroup);
        Service service = new Service();
        service.setId(serviceId);
        service.setServiceMeta(meta);
        service.setOpenAPI(openAPI);
        return service;
    }

    @Test
    void definitionsOpenApi_serviceUnknown_returns404() throws Exception {
        int port = pickPort();
        runningGateway = createGateway(port);
        runningGateway.setPublicEndpoint("http://capi.example.com/api/");
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/definitions/openapi/missing:v1")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(404, resp.statusCode());
    }

    @Test
    void definitionsOpenApi_exposeFalse_returns404() throws Exception {
        int port = pickPort();
        serviceCache.put("my-svc:v1", createServiceWithOpenApi("my-svc:v1", false, false, null));
        runningGateway = createGateway(port);
        runningGateway.setPublicEndpoint("http://capi.example.com/api/");
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/definitions/openapi/my-svc:v1")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(404, resp.statusCode());
    }

    @Test
    void definitionsOpenApi_exposedAndOpen_returns200WithRewrittenServers() throws Exception {
        int port = pickPort();
        serviceCache.put("my-svc:v1", createServiceWithOpenApi("my-svc:v1", true, false, null));
        runningGateway = createGateway(port);
        runningGateway.setPublicEndpoint("http://capi.example.com/api/");
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/definitions/openapi/my-svc:v1")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"url\":\"http://capi.example.com/api/my-svc/v1\""),
                "servers url should be rewritten to public endpoint; body=" + resp.body());
        assertTrue(resp.body().contains("\"title\":\"my-svc:v1\""),
                "info.title should be replaced with service id; body=" + resp.body());
        assertTrue(resp.body().contains("Open API definition generated by CAPI"),
                "info.description should be the CAPI marker; body=" + resp.body());
    }

    @Test
    void definitionsOpenApi_securedNoToken_returns404() throws Exception {
        int port = pickPort();
        serviceCache.put("my-svc:v1", createServiceWithOpenApi("my-svc:v1", true, true, "group-a"));
        when(httpUtils.processAuthorizationAccessToken(any(io.undertow.server.HttpServerExchange.class))).thenReturn(null);
        runningGateway = createGateway(port);
        runningGateway.setPublicEndpoint("http://capi.example.com/api/");
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/definitions/openapi/my-svc:v1")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(404, resp.statusCode());
    }

    @Test
    void definitionsOpenApi_securedBadToken_returns404() throws Exception {
        int port = pickPort();
        serviceCache.put("my-svc:v1", createServiceWithOpenApi("my-svc:v1", true, true, "group-a"));
        when(httpUtils.processAuthorizationAccessToken(any(io.undertow.server.HttpServerExchange.class))).thenReturn("bad-token");
        when(httpUtils.isAuthorized("bad-token", "group-a")).thenReturn(false);
        runningGateway = createGateway(port);
        runningGateway.setPublicEndpoint("http://capi.example.com/api/");
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/definitions/openapi/my-svc:v1"))
                        .header("Authorization", "Bearer bad-token")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(404, resp.statusCode());
    }

    @Test
    void definitionsOpenApi_securedValidToken_returns200() throws Exception {
        int port = pickPort();
        serviceCache.put("my-svc:v1", createServiceWithOpenApi("my-svc:v1", true, true, "group-a"));
        when(httpUtils.processAuthorizationAccessToken(any(io.undertow.server.HttpServerExchange.class))).thenReturn("good-token");
        when(httpUtils.isAuthorized("good-token", "group-a")).thenReturn(true);
        runningGateway = createGateway(port);
        runningGateway.setPublicEndpoint("http://capi.example.com/api/");
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/definitions/openapi/my-svc:v1"))
                        .header("Authorization", "Bearer good-token")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"url\":\"http://capi.example.com/api/my-svc/v1\""));
    }

    @Test
    void definitionsOpenApi_postMethod_returns405() throws Exception {
        int port = pickPort();
        serviceCache.put("my-svc:v1", createServiceWithOpenApi("my-svc:v1", true, false, null));
        runningGateway = createGateway(port);
        runningGateway.setPublicEndpoint("http://capi.example.com/api/");
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/definitions/openapi/my-svc:v1"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(405, resp.statusCode());
    }
}
