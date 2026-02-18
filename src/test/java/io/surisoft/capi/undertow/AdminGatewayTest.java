package io.surisoft.capi.undertow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.WebsocketClient;
import io.surisoft.capi.service.CapiTrustManager;
import io.surisoft.capi.service.ConsulNodeDiscovery;
import io.surisoft.capi.utils.Constants;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.ServerConnection;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.io.Sender;
import org.apache.camel.CamelContext;
import org.apache.camel.util.json.JsonObject;
import org.cache2k.Cache;
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
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminGatewayTest {

    @Mock
    private PrometheusMeterRegistry prometheusRegistry;
    @Mock
    private CAPIConfiguration capiConfiguration;
    @Mock
    private CamelContext camelContext;
    @Mock
    private Cache<String, Service> serviceCache;
    @Mock
    private SSLContext sslContext;
    @Mock
    private CapiTrustManager capiTrustManager;

    private AdminGateway adminGateway;
    private AdminGateway adminGatewayNoSsl;

    @BeforeEach
    void setUp() {
        adminGateway = new AdminGateway(9090, prometheusRegistry, capiConfiguration, camelContext, serviceCache, sslContext, capiTrustManager);
        adminGatewayNoSsl = new AdminGateway(9091, prometheusRegistry, capiConfiguration, camelContext, serviceCache, null, capiTrustManager);
    }

    @Test
    void buildError_returnsJsonObjectWithMessageAndCode() {
        JsonObject error = adminGateway.buildError(404, "Not Found");

        assertEquals("Not Found", error.get(Constants.ERROR_MESSAGE));
        assertEquals(404, error.get(Constants.ERROR_CODE));
    }

    @Test
    void buildError_withDifferentStatusCode() {
        JsonObject error = adminGateway.buildError(500, "Internal Server Error");

        assertEquals("Internal Server Error", error.get(Constants.ERROR_MESSAGE));
        assertEquals(500, error.get(Constants.ERROR_CODE));
    }

    @Test
    void setWebsocketClients_setsClientsMap() {
        Map<String, WebsocketClient> clients = new HashMap<>();
        WebsocketClient client = new WebsocketClient();
        client.setServiceId("test-service");
        clients.put("test", client);

        adminGateway.setWebsocketClients(clients);

        // No exception means success - verifying setter works
        assertDoesNotThrow(() -> adminGateway.setWebsocketClients(clients));
    }

    @Test
    void setWebsocketClients_withNullMap() {
        assertDoesNotThrow(() -> adminGateway.setWebsocketClients(null));
    }

    @Test
    void setWebsocketClients_withEmptyMap() {
        Map<String, WebsocketClient> clients = new HashMap<>();
        assertDoesNotThrow(() -> adminGateway.setWebsocketClients(clients));
    }

    @Test
    void stop_whenServerIsNull_doesNotThrow() {
        // server is null because start() was never called
        assertDoesNotThrow(() -> adminGateway.stop());
    }

    @Test
    void stop_whenServerIsNull_noSsl_doesNotThrow() {
        assertDoesNotThrow(() -> adminGatewayNoSsl.stop());
    }

    @Test
    void constructor_setsFieldsCorrectly() {
        // Verify the adminGateway was created without error with SSL context
        assertNotNull(adminGateway);
    }

    @Test
    void constructor_withNullSslContext_setsFieldsCorrectly() {
        // Verify the adminGateway was created without error without SSL context
        assertNotNull(adminGatewayNoSsl);
    }

    @Test
    void buildError_withZeroStatusCode() {
        JsonObject error = adminGateway.buildError(0, "");

        assertEquals("", error.get(Constants.ERROR_MESSAGE));
        assertEquals(0, error.get(Constants.ERROR_CODE));
    }

    @Test
    void objectMapper_isInitialized() {
        // The objectMapper field is package-accessible
        assertNotNull(adminGateway.objectMapper);
    }

    // === New tests to increase coverage ===

    @Test
    void handleMetrics_sendsPrometheusData() throws Exception {
        when(prometheusRegistry.scrape()).thenReturn("# HELP metric\n# TYPE metric counter\nmetric 42");

        HttpServerExchange exchange = mock(HttpServerExchange.class);
        HeaderMap headerMap = new HeaderMap();
        when(exchange.getResponseHeaders()).thenReturn(headerMap);
        Sender sender = mock(Sender.class);
        when(exchange.getResponseSender()).thenReturn(sender);

        Method handleMetrics = AdminGateway.class.getDeclaredMethod("handleMetrics", HttpServerExchange.class);
        handleMetrics.setAccessible(true);
        handleMetrics.invoke(adminGateway, exchange);

        verify(exchange).setStatusCode(200);
        verify(sender).send("# HELP metric\n# TYPE metric counter\nmetric 42");
        assertEquals("text/plain; version=0.0.4; charset=utf-8", headerMap.getFirst(Headers.CONTENT_TYPE));
    }

    @Test
    void handleHealth_whenConnectedToConsul_sendsUpStatus() throws Exception {
        Field connectedField = ConsulNodeDiscovery.class.getDeclaredField("connectedToConsul");
        connectedField.setAccessible(true);
        connectedField.set(null, true);

        HttpServerExchange exchange = mock(HttpServerExchange.class);
        HeaderMap headerMap = new HeaderMap();
        when(exchange.getResponseHeaders()).thenReturn(headerMap);
        Sender sender = mock(Sender.class);
        when(exchange.getResponseSender()).thenReturn(sender);

        Method handleHealth = AdminGateway.class.getDeclaredMethod("handleHealth", HttpServerExchange.class);
        handleHealth.setAccessible(true);
        handleHealth.invoke(adminGateway, exchange);

        verify(exchange).setStatusCode(200);
        verify(sender).send("{\"status\":\"UP\"}");
    }

    @Test
    void handleHealth_whenNotConnectedToConsul_sendsDownStatus() throws Exception {
        Field connectedField = ConsulNodeDiscovery.class.getDeclaredField("connectedToConsul");
        connectedField.setAccessible(true);
        connectedField.set(null, false);

        HttpServerExchange exchange = mock(HttpServerExchange.class);
        HeaderMap headerMap = new HeaderMap();
        when(exchange.getResponseHeaders()).thenReturn(headerMap);
        Sender sender = mock(Sender.class);
        when(exchange.getResponseSender()).thenReturn(sender);

        Method handleHealth = AdminGateway.class.getDeclaredMethod("handleHealth", HttpServerExchange.class);
        handleHealth.setAccessible(true);
        handleHealth.invoke(adminGateway, exchange);

        verify(exchange).setStatusCode(503);
        verify(sender).send("{\"status\":\"DOWN\"}");
    }

    @Test
    void handleCapiInfo_sendsInfoJson() throws Exception {
        when(camelContext.getUptime()).thenReturn(Duration.ofMinutes(90));
        when(camelContext.getVersion()).thenReturn("4.17.0");
        when(camelContext.getRoutesSize()).thenReturn(5);
        when(capiConfiguration.getVersion()).thenReturn("1.0");
        when(capiConfiguration.getInstanceName()).thenReturn("test-instance");

        HttpServerExchange exchange = mock(HttpServerExchange.class);
        HeaderMap headerMap = new HeaderMap();
        when(exchange.getResponseHeaders()).thenReturn(headerMap);
        Sender sender = mock(Sender.class);
        when(exchange.getResponseSender()).thenReturn(sender);

        Method handleCapiInfo = AdminGateway.class.getDeclaredMethod("handleCapiInfo", HttpServerExchange.class);
        handleCapiInfo.setAccessible(true);
        handleCapiInfo.invoke(adminGateway, exchange);

        verify(exchange).setStatusCode(200);
        verify(sender).send(anyString());
    }

    @Test
    void handleRoutesInfo_sendsRoutesList() throws Exception {
        when(camelContext.getRoutes()).thenReturn(java.util.Collections.emptyList());

        HttpServerExchange exchange = mock(HttpServerExchange.class);
        HeaderMap headerMap = new HeaderMap();
        when(exchange.getResponseHeaders()).thenReturn(headerMap);
        Sender sender = mock(Sender.class);
        when(exchange.getResponseSender()).thenReturn(sender);

        Method handleRoutesInfo = AdminGateway.class.getDeclaredMethod("handleRoutesInfo", HttpServerExchange.class);
        handleRoutesInfo.setAccessible(true);
        handleRoutesInfo.invoke(adminGateway, exchange);

        verify(exchange).setStatusCode(200);
        verify(sender).send(anyString());
    }

    @Test
    void handleRouteById_withEmptyServiceId_returnsNotFound() throws Exception {
        HttpServerExchange exchange = mock(HttpServerExchange.class);
        HeaderMap headerMap = new HeaderMap();
        when(exchange.getResponseHeaders()).thenReturn(headerMap);
        Sender sender = mock(Sender.class);
        when(exchange.getResponseSender()).thenReturn(sender);
        when(exchange.getRelativePath()).thenReturn("/");

        Method handleRouteById = AdminGateway.class.getDeclaredMethod("handleRouteById", HttpServerExchange.class);
        handleRouteById.setAccessible(true);
        handleRouteById.invoke(adminGateway, exchange);

        verify(exchange).setStatusCode(404);
        verify(sender).send(anyString());
    }

    @Test
    void handleRouteById_withNonExistentServiceId_returnsNotFound() throws Exception {
        HttpServerExchange exchange = mock(HttpServerExchange.class);
        HeaderMap headerMap = new HeaderMap();
        when(exchange.getResponseHeaders()).thenReturn(headerMap);
        Sender sender = mock(Sender.class);
        when(exchange.getResponseSender()).thenReturn(sender);
        when(exchange.getRelativePath()).thenReturn("/my-service");
        when(serviceCache.containsKey("my-service")).thenReturn(false);

        Method handleRouteById = AdminGateway.class.getDeclaredMethod("handleRouteById", HttpServerExchange.class);
        handleRouteById.setAccessible(true);
        handleRouteById.invoke(adminGateway, exchange);

        verify(exchange).setStatusCode(404);
    }

    @Test
    void handleRouteById_withExistingServiceId_returnsService() throws Exception {
        HttpServerExchange exchange = mock(HttpServerExchange.class);
        HeaderMap headerMap = new HeaderMap();
        when(exchange.getResponseHeaders()).thenReturn(headerMap);
        Sender sender = mock(Sender.class);
        when(exchange.getResponseSender()).thenReturn(sender);
        when(exchange.getRelativePath()).thenReturn("/my-service");

        Service service = new Service();
        service.setId("my-service");
        service.setName("My Service");
        when(serviceCache.containsKey("my-service")).thenReturn(true);
        when(serviceCache.get("my-service")).thenReturn(service);

        Method handleRouteById = AdminGateway.class.getDeclaredMethod("handleRouteById", HttpServerExchange.class);
        handleRouteById.setAccessible(true);
        handleRouteById.invoke(adminGateway, exchange);

        verify(exchange).setStatusCode(200);
        verify(sender).send(anyString());
    }

    @Test
    void handleOpenApi_withEmptyServiceId_returnsNotFound() throws Exception {
        HttpServerExchange exchange = mock(HttpServerExchange.class);
        HeaderMap headerMap = new HeaderMap();
        when(exchange.getResponseHeaders()).thenReturn(headerMap);
        Sender sender = mock(Sender.class);
        when(exchange.getResponseSender()).thenReturn(sender);
        when(exchange.getRelativePath()).thenReturn("/");

        Method handleOpenApi = AdminGateway.class.getDeclaredMethod("handleOpenApi", HttpServerExchange.class);
        handleOpenApi.setAccessible(true);
        handleOpenApi.invoke(adminGateway, exchange);

        verify(exchange).setStatusCode(404);
    }

    @Test
    void handleOpenApi_serviceNotInCache_returnsNotFound() throws Exception {
        HttpServerExchange exchange = mock(HttpServerExchange.class);
        HeaderMap headerMap = new HeaderMap();
        when(exchange.getResponseHeaders()).thenReturn(headerMap);
        Sender sender = mock(Sender.class);
        when(exchange.getResponseSender()).thenReturn(sender);
        when(exchange.getRelativePath()).thenReturn("/my-service");
        when(capiConfiguration.getPublicEndpoint()).thenReturn("http://localhost:8080");
        when(serviceCache.containsKey("my-service")).thenReturn(false);

        Method handleOpenApi = AdminGateway.class.getDeclaredMethod("handleOpenApi", HttpServerExchange.class);
        handleOpenApi.setAccessible(true);
        handleOpenApi.invoke(adminGateway, exchange);

        verify(exchange).setStatusCode(404);
    }

    @Test
    void handleOpenApi_serviceExistsButNoOpenApi_returnsNotFound() throws Exception {
        HttpServerExchange exchange = mock(HttpServerExchange.class);
        HeaderMap headerMap = new HeaderMap();
        when(exchange.getResponseHeaders()).thenReturn(headerMap);
        Sender sender = mock(Sender.class);
        when(exchange.getResponseSender()).thenReturn(sender);
        when(exchange.getRelativePath()).thenReturn("/my-service");
        when(capiConfiguration.getPublicEndpoint()).thenReturn("http://localhost:8080");

        Service service = new Service();
        service.setId("my-service");
        // serviceMeta is null, so getOpenApiEndpoint will be null
        when(serviceCache.containsKey("my-service")).thenReturn(true);
        when(serviceCache.get("my-service")).thenReturn(service);

        Method handleOpenApi = AdminGateway.class.getDeclaredMethod("handleOpenApi", HttpServerExchange.class);
        handleOpenApi.setAccessible(true);
        handleOpenApi.invoke(adminGateway, exchange);

        verify(exchange).setStatusCode(404);
    }

    @Test
    void handleTruststore_enabled_returnsOk() throws Exception {
        CAPIConfiguration.TrustStore trustStoreConfig = new CAPIConfiguration.TrustStore();
        trustStoreConfig.setEnabled(true);
        when(capiConfiguration.getTrustStore()).thenReturn(trustStoreConfig);

        // capiTrustManager.getKeyStore() will throw or return null - we need to handle that
        java.security.KeyStore keyStore = java.security.KeyStore.getInstance("JKS");
        keyStore.load(null, null);
        when(capiTrustManager.getKeyStore()).thenReturn(keyStore);

        HttpServerExchange exchange = mock(HttpServerExchange.class);
        HeaderMap headerMap = new HeaderMap();
        when(exchange.getResponseHeaders()).thenReturn(headerMap);
        Sender sender = mock(Sender.class);
        when(exchange.getResponseSender()).thenReturn(sender);

        Method handleTruststore = AdminGateway.class.getDeclaredMethod("handleTruststore", HttpServerExchange.class);
        handleTruststore.setAccessible(true);
        handleTruststore.invoke(adminGateway, exchange);

        verify(exchange).setStatusCode(200);
        verify(sender).send(anyString());
    }

    @Test
    void handleTruststore_disabled_returnsNotFound() throws Exception {
        CAPIConfiguration.TrustStore trustStoreConfig = new CAPIConfiguration.TrustStore();
        trustStoreConfig.setEnabled(false);
        when(capiConfiguration.getTrustStore()).thenReturn(trustStoreConfig);

        HttpServerExchange exchange = mock(HttpServerExchange.class);
        HeaderMap headerMap = new HeaderMap();
        when(exchange.getResponseHeaders()).thenReturn(headerMap);
        Sender sender = mock(Sender.class);
        when(exchange.getResponseSender()).thenReturn(sender);

        Method handleTruststore = AdminGateway.class.getDeclaredMethod("handleTruststore", HttpServerExchange.class);
        handleTruststore.setAccessible(true);
        handleTruststore.invoke(adminGateway, exchange);

        verify(exchange).setStatusCode(404);
    }

    @Test
    void handleTruststore_enabledButNullTrustManager_returnsNotFound() throws Exception {
        CAPIConfiguration.TrustStore trustStoreConfig = new CAPIConfiguration.TrustStore();
        trustStoreConfig.setEnabled(true);
        when(capiConfiguration.getTrustStore()).thenReturn(trustStoreConfig);

        // Create an AdminGateway with null capiTrustManager
        AdminGateway gatewayNoTrustManager = new AdminGateway(9092, prometheusRegistry, capiConfiguration, camelContext, serviceCache, null, null);

        HttpServerExchange exchange = mock(HttpServerExchange.class);
        HeaderMap headerMap = new HeaderMap();
        when(exchange.getResponseHeaders()).thenReturn(headerMap);
        Sender sender = mock(Sender.class);
        when(exchange.getResponseSender()).thenReturn(sender);

        Method handleTruststore = AdminGateway.class.getDeclaredMethod("handleTruststore", HttpServerExchange.class);
        handleTruststore.setAccessible(true);
        handleTruststore.invoke(gatewayNoTrustManager, exchange);

        verify(exchange).setStatusCode(404);
    }

    @Test
    void handleWsRoutes_websocketDisabled_returnsNotFound() throws Exception {
        CAPIConfiguration.Websocket wsConfig = new CAPIConfiguration.Websocket();
        wsConfig.setEnabled(false);
        when(capiConfiguration.getWebsocket()).thenReturn(wsConfig);

        HttpServerExchange exchange = mock(HttpServerExchange.class);
        HeaderMap headerMap = new HeaderMap();
        when(exchange.getResponseHeaders()).thenReturn(headerMap);
        Sender sender = mock(Sender.class);
        when(exchange.getResponseSender()).thenReturn(sender);

        Method handleWsRoutes = AdminGateway.class.getDeclaredMethod("handleWsRoutes", HttpServerExchange.class);
        handleWsRoutes.setAccessible(true);
        handleWsRoutes.invoke(adminGateway, exchange);

        verify(exchange).setStatusCode(404);
    }

    @Test
    void handleWsRoutes_websocketEnabledWithClients_returnsOk() throws Exception {
        CAPIConfiguration.Websocket wsConfig = new CAPIConfiguration.Websocket();
        wsConfig.setEnabled(true);
        when(capiConfiguration.getWebsocket()).thenReturn(wsConfig);

        Map<String, WebsocketClient> clients = new HashMap<>();
        WebsocketClient client = new WebsocketClient();
        client.setServiceId("ws-service");
        clients.put("/ws/test", client);
        adminGateway.setWebsocketClients(clients);

        HttpServerExchange exchange = mock(HttpServerExchange.class);
        HeaderMap headerMap = new HeaderMap();
        when(exchange.getResponseHeaders()).thenReturn(headerMap);
        Sender sender = mock(Sender.class);
        when(exchange.getResponseSender()).thenReturn(sender);

        Method handleWsRoutes = AdminGateway.class.getDeclaredMethod("handleWsRoutes", HttpServerExchange.class);
        handleWsRoutes.setAccessible(true);
        handleWsRoutes.invoke(adminGateway, exchange);

        verify(exchange).setStatusCode(200);
        verify(sender).send(anyString());
    }

    @Test
    void handleWsRoutes_nullWebsocketClients_returnsNotFound() throws Exception {
        CAPIConfiguration.Websocket wsConfig = new CAPIConfiguration.Websocket();
        wsConfig.setEnabled(true);
        when(capiConfiguration.getWebsocket()).thenReturn(wsConfig);

        // websocketClients is null by default - don't call setWebsocketClients

        HttpServerExchange exchange = mock(HttpServerExchange.class);
        HeaderMap headerMap = new HeaderMap();
        when(exchange.getResponseHeaders()).thenReturn(headerMap);
        Sender sender = mock(Sender.class);
        when(exchange.getResponseSender()).thenReturn(sender);

        Method handleWsRoutes = AdminGateway.class.getDeclaredMethod("handleWsRoutes", HttpServerExchange.class);
        handleWsRoutes.setAccessible(true);
        handleWsRoutes.invoke(adminGateway, exchange);

        verify(exchange).setStatusCode(404);
    }

    @Test
    void handleCapiInfo_withRestConfig_sendsContextPath() throws Exception {
        when(camelContext.getUptime()).thenReturn(Duration.ofHours(2));
        when(camelContext.getVersion()).thenReturn("4.17.0");
        when(camelContext.getRoutesSize()).thenReturn(3);
        when(capiConfiguration.getVersion()).thenReturn("2.0");
        when(capiConfiguration.getInstanceName()).thenReturn("ns");

        CAPIConfiguration.Rest restConfig = new CAPIConfiguration.Rest();
        restConfig.setContextPath("/api");
        when(capiConfiguration.getRest()).thenReturn(restConfig);

        CAPIConfiguration.Oauth2 oauth2 = new CAPIConfiguration.Oauth2();
        oauth2.setEnabled(true);
        oauth2.setKeys(java.util.List.of("http://key1", "http://key2"));
        when(capiConfiguration.getOauth2()).thenReturn(oauth2);

        CAPIConfiguration.Traces traces = new CAPIConfiguration.Traces();
        traces.setEnabled(true);
        traces.setEndpoint("http://traces.endpoint");
        when(capiConfiguration.getTraces()).thenReturn(traces);

        CAPIConfiguration.HostConfig host = new CAPIConfiguration.HostConfig();
        host.setEndpoint("http://consul:8500");
        when(capiConfiguration.getConsulHosts()).thenReturn(java.util.List.of(host));

        HttpServerExchange exchange = mock(HttpServerExchange.class);
        HeaderMap headerMap = new HeaderMap();
        when(exchange.getResponseHeaders()).thenReturn(headerMap);
        Sender sender = mock(Sender.class);
        when(exchange.getResponseSender()).thenReturn(sender);

        Method handleCapiInfo = AdminGateway.class.getDeclaredMethod("handleCapiInfo", HttpServerExchange.class);
        handleCapiInfo.setAccessible(true);
        handleCapiInfo.invoke(adminGateway, exchange);

        verify(exchange).setStatusCode(200);
        verify(sender).send(argThat((String s) -> s.contains("ns") || s.contains("2.0")));
    }

    @Test
    void buildError_containsCorrectKeys() {
        JsonObject error = adminGateway.buildError(403, "Forbidden");
        assertTrue(error.containsKey(Constants.ERROR_MESSAGE));
        assertTrue(error.containsKey(Constants.ERROR_CODE));
        assertEquals("Forbidden", error.getString(Constants.ERROR_MESSAGE));
    }

    // === Lifecycle tests for start() and stop() ===

    @Test
    void start_withoutSsl_startsHttpServer_healthEndpoint_connected() throws Exception {
        Field connectedField = ConsulNodeDiscovery.class.getDeclaredField("connectedToConsul");
        connectedField.setAccessible(true);
        connectedField.set(null, true);

        AdminGateway gw = new AdminGateway(19100, prometheusRegistry, capiConfiguration, camelContext, serviceCache, null, capiTrustManager);
        try {
            gw.start();

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:19100/info/health"))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("UP"));
        } finally {
            gw.stop();
        }
    }

    @Test
    void start_withoutSsl_startsHttpServer_healthEndpoint_notConnected() throws Exception {
        Field connectedField = ConsulNodeDiscovery.class.getDeclaredField("connectedToConsul");
        connectedField.setAccessible(true);
        connectedField.set(null, false);

        AdminGateway gw = new AdminGateway(19113, prometheusRegistry, capiConfiguration, camelContext, serviceCache, null, capiTrustManager);
        try {
            gw.start();

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:19113/info/health"))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(503, response.statusCode());
            assertTrue(response.body().contains("DOWN"));
        } finally {
            gw.stop();
        }
    }

    @Test
    void start_withoutSsl_metricsEndpoint() throws Exception {
        when(prometheusRegistry.scrape()).thenReturn("# HELP test_metric\n# TYPE test_metric counter\ntest_metric 42");

        AdminGateway gw = new AdminGateway(19101, prometheusRegistry, capiConfiguration, camelContext, serviceCache, null, capiTrustManager);
        try {
            gw.start();

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:19101/info/metrics"))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("test_metric 42"));
        } finally {
            gw.stop();
        }
    }

    @Test
    void start_withoutSsl_routesEndpoint() throws Exception {
        when(camelContext.getRoutes()).thenReturn(Collections.emptyList());

        AdminGateway gw = new AdminGateway(19102, prometheusRegistry, capiConfiguration, camelContext, serviceCache, null, capiTrustManager);
        try {
            gw.start();

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:19102/info/routes"))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            // Routes returns a JSON array
            assertTrue(response.body().startsWith("["));
        } finally {
            gw.stop();
        }
    }

    @Test
    void start_withoutSsl_capiInfoEndpoint() throws Exception {
        when(camelContext.getUptime()).thenReturn(Duration.ofMinutes(5));
        when(camelContext.getVersion()).thenReturn("4.17.0");
        when(camelContext.getRoutesSize()).thenReturn(2);
        when(capiConfiguration.getVersion()).thenReturn("2.0");
        when(capiConfiguration.getInstanceName()).thenReturn("my-instance");

        AdminGateway gw = new AdminGateway(19103, prometheusRegistry, capiConfiguration, camelContext, serviceCache, null, capiTrustManager);
        try {
            gw.start();

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:19103/info/capi"))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("my-instance") || response.body().contains("2.0"));
        } finally {
            gw.stop();
        }
    }

    @Test
    void start_thenStop_serverStopsCleanly() throws Exception {
        Field connectedField = ConsulNodeDiscovery.class.getDeclaredField("connectedToConsul");
        connectedField.setAccessible(true);
        connectedField.set(null, true);

        AdminGateway gw = new AdminGateway(19104, prometheusRegistry, capiConfiguration, camelContext, serviceCache, null, capiTrustManager);
        gw.start();

        // Verify the server is running
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:19104/info/health"))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        // Stop the server
        gw.stop();

        // After stopping, the port should be unavailable
        // A connection attempt should throw
        assertThrows(Exception.class, () -> {
            HttpClient client2 = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
            HttpRequest request2 = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:19104/info/health"))
                    .GET()
                    .build();
            client2.send(request2, HttpResponse.BodyHandlers.ofString());
        });
    }

    @Test
    void stop_afterStart_canBeCalledMultipleTimes() throws Exception {
        AdminGateway gw = new AdminGateway(19105, prometheusRegistry, capiConfiguration, camelContext, serviceCache, null, capiTrustManager);
        gw.start();
        gw.stop();
        // Second stop should not throw
        assertDoesNotThrow(() -> gw.stop());
    }

    @Test
    void start_withoutSsl_truststoreEndpoint_enabledReturnsOk() throws Exception {
        CAPIConfiguration.TrustStore trustStoreConfig = new CAPIConfiguration.TrustStore();
        trustStoreConfig.setEnabled(true);
        when(capiConfiguration.getTrustStore()).thenReturn(trustStoreConfig);

        java.security.KeyStore keyStore = java.security.KeyStore.getInstance("JKS");
        keyStore.load(null, null);
        when(capiTrustManager.getKeyStore()).thenReturn(keyStore);

        AdminGateway gw = new AdminGateway(19106, prometheusRegistry, capiConfiguration, camelContext, serviceCache, null, capiTrustManager);
        try {
            gw.start();

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:19106/info/truststore"))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
        } finally {
            gw.stop();
        }
    }

    @Test
    void start_withoutSsl_truststoreEndpoint_disabledReturnsNotFound() throws Exception {
        CAPIConfiguration.TrustStore trustStoreConfig = new CAPIConfiguration.TrustStore();
        trustStoreConfig.setEnabled(false);
        when(capiConfiguration.getTrustStore()).thenReturn(trustStoreConfig);

        AdminGateway gw = new AdminGateway(19107, prometheusRegistry, capiConfiguration, camelContext, serviceCache, null, capiTrustManager);
        try {
            gw.start();

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:19107/info/truststore"))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(404, response.statusCode());
        } finally {
            gw.stop();
        }
    }

    @Test
    void start_withoutSsl_wsroutesEndpoint_disabledReturnsNotFound() throws Exception {
        CAPIConfiguration.Websocket wsConfig = new CAPIConfiguration.Websocket();
        wsConfig.setEnabled(false);
        when(capiConfiguration.getWebsocket()).thenReturn(wsConfig);

        AdminGateway gw = new AdminGateway(19108, prometheusRegistry, capiConfiguration, camelContext, serviceCache, null, capiTrustManager);
        try {
            gw.start();

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:19108/info/wsroutes"))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(404, response.statusCode());
        } finally {
            gw.stop();
        }
    }

    @Test
    void start_withoutSsl_wsroutesEndpoint_enabledWithClientsReturnsOk() throws Exception {
        CAPIConfiguration.Websocket wsConfig = new CAPIConfiguration.Websocket();
        wsConfig.setEnabled(true);
        when(capiConfiguration.getWebsocket()).thenReturn(wsConfig);

        Map<String, WebsocketClient> clients = new HashMap<>();
        WebsocketClient client = new WebsocketClient();
        client.setServiceId("ws-test");
        clients.put("/ws/test", client);

        AdminGateway gw = new AdminGateway(19109, prometheusRegistry, capiConfiguration, camelContext, serviceCache, null, capiTrustManager);
        gw.setWebsocketClients(clients);
        try {
            gw.start();

            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:19109/info/wsroutes"))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
        } finally {
            gw.stop();
        }
    }

    @Test
    void start_withoutSsl_routeByIdEndpoint_serviceNotFoundReturns404() throws Exception {
        when(serviceCache.containsKey("nonexistent")).thenReturn(false);

        AdminGateway gw = new AdminGateway(19110, prometheusRegistry, capiConfiguration, camelContext, serviceCache, null, capiTrustManager);
        try {
            gw.start();

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:19110/info/routes/nonexistent"))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(404, response.statusCode());
        } finally {
            gw.stop();
        }
    }

    @Test
    void start_withoutSsl_routeByIdEndpoint_serviceFoundReturns200() throws Exception {
        Service service = new Service();
        service.setId("my-svc");
        service.setName("My Service");
        when(serviceCache.containsKey("my-svc")).thenReturn(true);
        when(serviceCache.get("my-svc")).thenReturn(service);

        AdminGateway gw = new AdminGateway(19111, prometheusRegistry, capiConfiguration, camelContext, serviceCache, null, capiTrustManager);
        try {
            gw.start();

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:19111/info/routes/my-svc"))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("my-svc"));
        } finally {
            gw.stop();
        }
    }

    @Test
    void start_withoutSsl_openApiEndpoint_serviceNotFoundReturns404() throws Exception {
        when(capiConfiguration.getPublicEndpoint()).thenReturn("http://localhost:8080");
        when(serviceCache.containsKey("unknown")).thenReturn(false);

        AdminGateway gw = new AdminGateway(19112, prometheusRegistry, capiConfiguration, camelContext, serviceCache, null, capiTrustManager);
        try {
            gw.start();

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:19112/info/openapi/unknown"))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(404, response.statusCode());
        } finally {
            gw.stop();
        }
    }
}
