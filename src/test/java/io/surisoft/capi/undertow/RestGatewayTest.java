package io.surisoft.capi.undertow;

import io.surisoft.capi.exception.AuthorizationException;
import io.surisoft.capi.processor.ThrottleProcessor;
import io.surisoft.capi.schema.*;
import io.surisoft.capi.service.ConsulNodeDiscovery;
import io.surisoft.capi.service.OpaService;
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
            Field field = ConsulNodeDiscovery.class.getDeclaredField("connectedToConsul");
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

    // === OPA async path ===

    @Test
    void opaService_denied_returns403() throws Exception {
        int port = pickPort();
        RestClient rc = createOpenRestClient("/opa-svc/v1");
        rc.setOpaRego("my-rego-policy");
        restClientMap.put("/opa-svc/v1", rc);

        when(httpUtils.processAuthorizationAccessToken(any(io.undertow.server.HttpServerExchange.class))).thenReturn("opa-token");

        OpaService opaService = mock(OpaService.class);
        OpaResult denied = new OpaResult();
        denied.setResult(false);
        when(opaService.callOpaAsync("my-rego-policy", "opa-token", true))
                .thenReturn(CompletableFuture.completedFuture(denied));

        runningGateway = createGateway(port);
        runningGateway.setOpaService(opaService);
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
        restClientMap.put("/opa-svc/v1", rc);

        when(httpUtils.processAuthorizationAccessToken(any(io.undertow.server.HttpServerExchange.class))).thenReturn("opa-token");

        OpaService opaService = mock(OpaService.class);
        OpaResult allowed = new OpaResult();
        allowed.setResult(true);
        when(opaService.callOpaAsync("my-rego-policy", "opa-token", true))
                .thenReturn(CompletableFuture.completedFuture(allowed));

        runningGateway = createGateway(port);
        runningGateway.setOpaService(opaService);
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

        OpaService opaService = mock(OpaService.class);
        runningGateway = createGateway(port);
        runningGateway.setOpaService(opaService);
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

    // === X-Forwarded-For in access log ===

    @Test
    void xForwardedFor_isUsedForClientIp() throws Exception {
        int port = pickPort();
        restClientMap.put("/my-service/v1", createOpenRestClient("/my-service/v1"));
        runningGateway = createGateway(port);
        runningGateway.runProxy();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/my-service/v1"))
                        .header("X-Forwarded-For", "203.0.113.50")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
    }
}
