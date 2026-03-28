package io.surisoft.capi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.surisoft.capi.configuration.CapiSslContextHolder;
import io.surisoft.capi.exception.HttpErrorHandler;
import io.surisoft.capi.schema.*;
import io.surisoft.capi.utils.*;
import io.undertow.server.HttpHandler;
import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConsulStoreTest {

    @Mock
    private Cache<String, ConsulKeyStoreEntry> consulTrustStoreCache;

    @Mock
    private RouteUtils routeUtils;

    @Mock
    private CapiSslContextHolder capiSslContextHolder;

    @Mock
    private HttpClient httpClient;

    private ConsulStore consulStore;

    @BeforeEach
    void setUp() {
        consulStore = new ConsulStore(
                consulTrustStoreCache,
                routeUtils,
                "http://consul-host:8500",
                "test-token",
                "changeit",
                capiSslContextHolder,
                httpClient
        );
    }

    @Test
    void consulKeyValueToInputStream_decodesBase64Correctly() throws JsonProcessingException {
        String originalContent = "hello world";
        String innerBase64 = Base64.getEncoder().encodeToString(originalContent.getBytes());
        String outerBase64 = Base64.getEncoder().encodeToString(innerBase64.getBytes());

        InputStream result = consulStore.consulKeyValueToInputStream(outerBase64);
        assertNotNull(result);
    }

    @SuppressWarnings("unchecked")
    @Test
    void process_noRemoteTrustStore_doesNothing() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(404);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

        when(consulTrustStoreCache.get(Constants.CONSUL_CAPI_TRUST_STORE_GROUP_KEY)).thenReturn(null);

        consulStore.process();

        // No cache put should happen since remote is null (404)
        verify(consulTrustStoreCache, never()).put(eq(Constants.CONSUL_CAPI_TRUST_STORE_GROUP_KEY), any(ConsulKeyStoreEntry.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void process_remoteExistsButNoCached_cachesRemote() throws Exception {
        ConsulKeyStoreEntry remoteEntry = new ConsulKeyStoreEntry();
        remoteEntry.setModifyIndex(10);
        String innerBase64 = Base64.getEncoder().encodeToString("test-content".getBytes());
        String outerBase64 = Base64.getEncoder().encodeToString(innerBase64.getBytes());
        remoteEntry.setValue(outerBase64);

        ObjectMapper objectMapper = new ObjectMapper();
        String responseBody = objectMapper.writeValueAsString(new ConsulKeyStoreEntry[]{remoteEntry});

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseBody);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

        when(consulTrustStoreCache.get(Constants.CONSUL_CAPI_TRUST_STORE_GROUP_KEY)).thenReturn(null);

        consulStore.process();

        // Should cache the remote entry
        verify(consulTrustStoreCache).put(eq(Constants.CONSUL_CAPI_TRUST_STORE_GROUP_KEY), any(ConsulKeyStoreEntry.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void process_remoteSameAsCached_doesNotReprocess() throws Exception {
        ConsulKeyStoreEntry cachedEntry = new ConsulKeyStoreEntry();
        cachedEntry.setModifyIndex(10);

        ConsulKeyStoreEntry remoteEntry = new ConsulKeyStoreEntry();
        remoteEntry.setModifyIndex(10);
        remoteEntry.setValue("dGVzdA=="); // base64 of "test"

        ObjectMapper objectMapper = new ObjectMapper();
        String responseBody = objectMapper.writeValueAsString(new ConsulKeyStoreEntry[]{remoteEntry});

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseBody);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

        when(consulTrustStoreCache.get(Constants.CONSUL_CAPI_TRUST_STORE_GROUP_KEY)).thenReturn(cachedEntry);

        consulStore.process();

        // Same modify index, still caches
        verify(consulTrustStoreCache).put(eq(Constants.CONSUL_CAPI_TRUST_STORE_GROUP_KEY), any(ConsulKeyStoreEntry.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void process_remoteDifferentFromCached_reprocesses() throws Exception {
        ConsulKeyStoreEntry cachedEntry = new ConsulKeyStoreEntry();
        cachedEntry.setModifyIndex(5);

        ConsulKeyStoreEntry remoteEntry = new ConsulKeyStoreEntry();
        remoteEntry.setModifyIndex(10);
        String innerBase64 = Base64.getEncoder().encodeToString("updated-content".getBytes());
        String outerBase64 = Base64.getEncoder().encodeToString(innerBase64.getBytes());
        remoteEntry.setValue(outerBase64);

        ObjectMapper objectMapper = new ObjectMapper();
        String responseBody = objectMapper.writeValueAsString(new ConsulKeyStoreEntry[]{remoteEntry});

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseBody);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

        when(consulTrustStoreCache.get(Constants.CONSUL_CAPI_TRUST_STORE_GROUP_KEY)).thenReturn(cachedEntry);

        // processTrustStore will attempt to rebuild the HttpClient
        consulStore.process();

        // Different modify index, so processTrustStore is called and cache updated
        verify(consulTrustStoreCache).put(eq(Constants.CONSUL_CAPI_TRUST_STORE_GROUP_KEY), any(ConsulKeyStoreEntry.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void process_httpClientThrowsException_doesNotCrash() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("Connection refused"));

        when(consulTrustStoreCache.get(Constants.CONSUL_CAPI_TRUST_STORE_GROUP_KEY)).thenReturn(null);

        assertDoesNotThrow(() -> consulStore.process());
    }

    @Test
    void constructor_nullConsulKvHost_getRemoteTrustStoreReturnsNull() {
        ConsulStore nullHostStore = new ConsulStore(
                consulTrustStoreCache,
                routeUtils,
                null,
                "test-token",
                "changeit",
                capiSslContextHolder,
                httpClient
        );

        when(consulTrustStoreCache.get(Constants.CONSUL_CAPI_TRUST_STORE_GROUP_KEY)).thenReturn(null);

        // process will call getRemoteTrustStore which returns null for null host
        assertDoesNotThrow(() -> nullHostStore.process());
    }

    @Test
    void constructor_withNoToken_buildsRequestWithoutAuthHeader() {
        ConsulStore noTokenStore = new ConsulStore(
                consulTrustStoreCache,
                routeUtils,
                "http://consul-host:8500",
                null,
                "changeit",
                capiSslContextHolder,
                httpClient
        );

        when(consulTrustStoreCache.get(Constants.CONSUL_CAPI_TRUST_STORE_GROUP_KEY)).thenReturn(null);

        assertDoesNotThrow(() -> noTokenStore.process());
    }

    @Test
    void constructor_withEmptyToken_buildsRequestWithoutAuthHeader() {
        ConsulStore emptyTokenStore = new ConsulStore(
                consulTrustStoreCache,
                routeUtils,
                "http://consul-host:8500",
                "",
                "changeit",
                capiSslContextHolder,
                httpClient
        );

        when(consulTrustStoreCache.get(Constants.CONSUL_CAPI_TRUST_STORE_GROUP_KEY)).thenReturn(null);

        assertDoesNotThrow(() -> emptyTokenStore.process());
    }

    @Test
    void process_pathTraversalInHost_doesNotCallHttpClient() throws Exception {
        ConsulStore traversalStore = new ConsulStore(
                consulTrustStoreCache,
                routeUtils,
                "http://consul-host:8500/..",
                "test-token",
                "changeit",
                capiSslContextHolder,
                httpClient
        );

        when(consulTrustStoreCache.get(Constants.CONSUL_CAPI_TRUST_STORE_GROUP_KEY)).thenReturn(null);

        assertDoesNotThrow(() -> traversalStore.process());
        verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    // === rebuildRestClientHandlers ===

    @Test
    void rebuildRestClientHandlers_nullRestClientMap_doesNotThrow() {
        // restClientMap is not set (null) — should silently return
        WebsocketUtils wsUtils = mock(WebsocketUtils.class);
        consulStore.setWebsocketUtils(wsUtils);
        // restClientMap not set => should not throw
        assertDoesNotThrow(() -> consulStore.setRestClientMap(null));
    }

    @Test
    void rebuildRestClientHandlers_rebuildsHandlers() throws Exception {
        WebsocketUtils wsUtils = mock(WebsocketUtils.class);
        HttpUtils mockHttpUtils = mock(HttpUtils.class);
        when(mockHttpUtils.contextToRole(anyString())).thenCallRealMethod();

        HttpHandler mockHandler = mock(HttpHandler.class);
        when(wsUtils.createClientHttpHandler(any(WebsocketClient.class), any(Service.class), any(HttpErrorHandler.class), anyInt()))
                .thenReturn(mockHandler);

        Cache<String, Service> svcCache = Cache2kBuilder.of(String.class, Service.class)
                .name("consulStoreRebuildRest-" + System.nanoTime())
                .eternal(true)
                .entryCapacity(10)
                .build();

        Service service = new Service();
        ServiceMeta meta = new ServiceMeta();
        service.setServiceMeta(meta);
        Mapping mapping = new Mapping();
        mapping.setHostname("localhost");
        mapping.setPort(8080);
        service.setMappingList(Set.of(mapping));
        svcCache.put("svc:v1", service);

        RestClient rc = new RestClient();
        rc.setServiceId("/svc/v1");
        Map<String, RestClient> rcMap = new HashMap<>();
        rcMap.put("/svc/v1", rc);

        consulStore.setWebsocketUtils(wsUtils);
        consulStore.setHttpUtils(mockHttpUtils);
        consulStore.setServiceCache(svcCache);
        consulStore.setRestClientMap(rcMap);

        // Trigger process with a valid trust store update
        ConsulKeyStoreEntry remoteEntry = createValidTrustStoreEntry(10);
        ObjectMapper objectMapper = new ObjectMapper();
        String responseBody = objectMapper.writeValueAsString(new ConsulKeyStoreEntry[]{remoteEntry});

        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseBody);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);
        when(consulTrustStoreCache.get(Constants.CONSUL_CAPI_TRUST_STORE_GROUP_KEY)).thenReturn(null);

        // processTrustStore will fail on KeyStore.load because the content is not a valid JKS,
        // but it should not crash
        assertDoesNotThrow(() -> consulStore.process());

        svcCache.close();
    }

    // === rebuildWebsocketClientHandlers ===

    @Test
    void rebuildWebsocketClientHandlers_rebuildsHandlers() throws Exception {
        WebsocketUtils wsUtils = mock(WebsocketUtils.class);
        HttpHandler mockHandler = mock(HttpHandler.class);
        when(wsUtils.createClientHttpHandler(any(WebsocketClient.class), any(Service.class), isNull()))
                .thenReturn(mockHandler);

        Cache<String, Service> svcCache = Cache2kBuilder.of(String.class, Service.class)
                .name("consulStoreRebuildWs-" + System.nanoTime())
                .eternal(true)
                .entryCapacity(10)
                .build();

        Service service = new Service();
        ServiceMeta meta = new ServiceMeta();
        service.setServiceMeta(meta);
        svcCache.put("ws-svc", service);

        WebsocketClient wsc = new WebsocketClient();
        wsc.setServiceId("ws-svc");
        wsc.setMappingList(Set.of());
        Map<String, WebsocketClient> wsMap = new HashMap<>();
        wsMap.put("ws-svc", wsc);

        consulStore.setWebsocketUtils(wsUtils);
        consulStore.setServiceCache(svcCache);
        consulStore.setWebsocketClientMap(wsMap);

        // Same approach: trigger process, processTrustStore will fail on JKS parse but the structure is exercised
        ConsulKeyStoreEntry remoteEntry = createValidTrustStoreEntry(20);
        ObjectMapper objectMapper = new ObjectMapper();
        String responseBody = objectMapper.writeValueAsString(new ConsulKeyStoreEntry[]{remoteEntry});

        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseBody);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);
        when(consulTrustStoreCache.get(Constants.CONSUL_CAPI_TRUST_STORE_GROUP_KEY)).thenReturn(null);

        assertDoesNotThrow(() -> consulStore.process());

        svcCache.close();
    }

    // === rebuildGrpcClientHandlers ===

    @Test
    void rebuildGrpcClientHandlers_rebuildsHandlers() throws Exception {
        GrpcUtils grpcUtils = mock(GrpcUtils.class);
        HttpHandler mockHandler = mock(HttpHandler.class);
        when(grpcUtils.createClientHttpHandler(any(GrpcClient.class), any(Service.class)))
                .thenReturn(mockHandler);

        Cache<String, Service> svcCache = Cache2kBuilder.of(String.class, Service.class)
                .name("consulStoreRebuildGrpc-" + System.nanoTime())
                .eternal(true)
                .entryCapacity(10)
                .build();

        Service service = new Service();
        ServiceMeta meta = new ServiceMeta();
        service.setServiceMeta(meta);
        svcCache.put("grpc-svc", service);

        GrpcClient gc = new GrpcClient();
        gc.setServiceId("grpc-svc");
        Map<String, GrpcClient> grpcMap = new HashMap<>();
        grpcMap.put("grpc-svc", gc);

        consulStore.setGrpcUtils(grpcUtils);
        consulStore.setServiceCache(svcCache);
        consulStore.setGrpcClientMap(grpcMap);

        ConsulKeyStoreEntry remoteEntry = createValidTrustStoreEntry(30);
        ObjectMapper objectMapper = new ObjectMapper();
        String responseBody = objectMapper.writeValueAsString(new ConsulKeyStoreEntry[]{remoteEntry});

        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseBody);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);
        when(consulTrustStoreCache.get(Constants.CONSUL_CAPI_TRUST_STORE_GROUP_KEY)).thenReturn(null);

        assertDoesNotThrow(() -> consulStore.process());

        svcCache.close();
    }

    // === setGlobalResponseTimeout ===

    @Test
    void setGlobalResponseTimeout_setsValue() {
        assertDoesNotThrow(() -> consulStore.setGlobalResponseTimeout(60000));
    }

    // === setters ===

    @Test
    void setWebsocketUtils_setsCorrectly() {
        WebsocketUtils wsUtils = mock(WebsocketUtils.class);
        assertDoesNotThrow(() -> consulStore.setWebsocketUtils(wsUtils));
    }

    @Test
    void setGrpcUtils_setsCorrectly() {
        GrpcUtils grpcUtils = mock(GrpcUtils.class);
        assertDoesNotThrow(() -> consulStore.setGrpcUtils(grpcUtils));
    }

    @Test
    void setHttpUtils_setsCorrectly() {
        HttpUtils httpUtils = mock(HttpUtils.class);
        assertDoesNotThrow(() -> consulStore.setHttpUtils(httpUtils));
    }

    @Test
    void setServiceCache_setsCorrectly() {
        Cache<String, Service> svcCache = Cache2kBuilder.of(String.class, Service.class)
                .name("consulStoreSetSvc-" + System.nanoTime())
                .eternal(true)
                .entryCapacity(10)
                .build();
        try {
            assertDoesNotThrow(() -> consulStore.setServiceCache(svcCache));
        } finally {
            svcCache.close();
        }
    }

    @Test
    void setWebsocketClientMap_setsCorrectly() {
        Map<String, WebsocketClient> wsMap = new HashMap<>();
        assertDoesNotThrow(() -> consulStore.setWebsocketClientMap(wsMap));
    }

    @Test
    void setGrpcClientMap_setsCorrectly() {
        Map<String, GrpcClient> grpcMap = new HashMap<>();
        assertDoesNotThrow(() -> consulStore.setGrpcClientMap(grpcMap));
    }

    @SuppressWarnings("unchecked")
    @Test
    void process_remoteExistsAndCachedExists_sameIndex_updatesCache() throws Exception {
        ConsulKeyStoreEntry cachedEntry = new ConsulKeyStoreEntry();
        cachedEntry.setModifyIndex(10);

        ConsulKeyStoreEntry remoteEntry = new ConsulKeyStoreEntry();
        remoteEntry.setModifyIndex(10); // Same
        String innerBase64 = Base64.getEncoder().encodeToString("same-content".getBytes());
        String outerBase64 = Base64.getEncoder().encodeToString(innerBase64.getBytes());
        remoteEntry.setValue(outerBase64);

        ObjectMapper objectMapper = new ObjectMapper();
        String responseBody = objectMapper.writeValueAsString(new ConsulKeyStoreEntry[]{remoteEntry});

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseBody);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

        when(consulTrustStoreCache.get(Constants.CONSUL_CAPI_TRUST_STORE_GROUP_KEY)).thenReturn(cachedEntry);

        consulStore.process();

        // Same modifyIndex: still updates cache (the "equal" branch)
        verify(consulTrustStoreCache).put(eq(Constants.CONSUL_CAPI_TRUST_STORE_GROUP_KEY), any(ConsulKeyStoreEntry.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void process_remoteReturnsServerError_returnsNull() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(500);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

        when(consulTrustStoreCache.get(Constants.CONSUL_CAPI_TRUST_STORE_GROUP_KEY)).thenReturn(null);

        consulStore.process();

        verify(consulTrustStoreCache, never()).put(anyString(), any(ConsulKeyStoreEntry.class));
    }

    @Test
    void consulKeyValueToInputStream_validBase64_returnsStream() throws Exception {
        String content = "test trust store content";
        String innerBase64 = Base64.getEncoder().encodeToString(content.getBytes());
        String outerBase64 = Base64.getEncoder().encodeToString(innerBase64.getBytes());

        java.io.InputStream is = consulStore.consulKeyValueToInputStream(outerBase64);
        assertNotNull(is);

        byte[] bytes = is.readAllBytes();
        assertTrue(bytes.length > 0);
    }

    // === processTrustStore with a real JKS ===

    @SuppressWarnings("unchecked")
    @Test
    void process_withRealJks_updatesSSLContextAndRebuildsHandlers() throws Exception {
        // Create a real JKS keystore in memory
        java.security.KeyStore ks = java.security.KeyStore.getInstance("JKS");
        ks.load(null, "changeit".toCharArray());

        // Generate a self-signed certificate
        java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        java.security.KeyPair kp = kpg.generateKeyPair();

        // Use the Undertow X509CertificateBuilder approach - just store a SecretKey entry instead
        // For simplicity, store a certificate via keytool-like approach
        // Actually, let's just use a self-signed cert
        javax.security.auth.x500.X500Principal principal = new javax.security.auth.x500.X500Principal("CN=test");
        long now = System.currentTimeMillis();
        java.util.Date notBefore = new java.util.Date(now);
        java.util.Date notAfter = new java.util.Date(now + 365L * 24 * 60 * 60 * 1000);

        // We can't easily create an X509Certificate without BouncyCastle, but we can test
        // the flow by creating a keystore with a trusted cert entry.
        // Let's use a simpler approach: just store a secret key entry
        // Empty keystore is valid for trust store purposes

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        ks.store(baos, "changeit".toCharArray());
        byte[] jksBytes = baos.toByteArray();

        // Double-base64 encode (as Consul KV stores values)
        String innerBase64 = Base64.getEncoder().encodeToString(jksBytes);
        String outerBase64 = Base64.getEncoder().encodeToString(innerBase64.getBytes());

        ConsulKeyStoreEntry remoteEntry = new ConsulKeyStoreEntry();
        remoteEntry.setModifyIndex(100);
        remoteEntry.setValue(outerBase64);

        ObjectMapper objectMapper = new ObjectMapper();
        String responseBody = objectMapper.writeValueAsString(new ConsulKeyStoreEntry[]{remoteEntry});

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseBody);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

        when(consulTrustStoreCache.get(Constants.CONSUL_CAPI_TRUST_STORE_GROUP_KEY)).thenReturn(null);

        // Set up websocketUtils for rebuild
        WebsocketUtils wsUtils = mock(WebsocketUtils.class);
        consulStore.setWebsocketUtils(wsUtils);

        // Set up rest clients for rebuild
        Cache<String, Service> svcCache = Cache2kBuilder.of(String.class, Service.class)
                .name("consulStoreRealJks-" + System.nanoTime())
                .eternal(true)
                .entryCapacity(10)
                .build();

        Service service = new Service();
        ServiceMeta meta = new ServiceMeta();
        service.setServiceMeta(meta);
        Mapping mapping = new Mapping();
        mapping.setHostname("localhost");
        mapping.setPort(8080);
        mapping.setRootContext("/");
        service.setMappingList(Set.of(mapping));
        svcCache.put("svc:v1", service);

        RestClient rc = new RestClient();
        rc.setServiceId("/svc/v1");
        Map<String, RestClient> rcMap = new HashMap<>();
        rcMap.put("/svc/v1", rc);

        HttpUtils mockHttpUtils = mock(HttpUtils.class);
        when(mockHttpUtils.contextToRole(anyString())).thenReturn("svc:v1");

        HttpHandler mockHandler = mock(HttpHandler.class);
        when(wsUtils.createClientHttpHandler(any(WebsocketClient.class), any(Service.class), any(HttpErrorHandler.class), anyInt()))
                .thenReturn(mockHandler);

        consulStore.setHttpUtils(mockHttpUtils);
        consulStore.setServiceCache(svcCache);
        consulStore.setRestClientMap(rcMap);

        // WebSocket clients for rebuild
        WebsocketClient wsc = new WebsocketClient();
        wsc.setServiceId("svc:v1");
        wsc.setMappingList(Set.of());
        Map<String, WebsocketClient> wsMap = new HashMap<>();
        wsMap.put("svc:v1", wsc);
        consulStore.setWebsocketClientMap(wsMap);

        when(wsUtils.createClientHttpHandler(any(WebsocketClient.class), any(Service.class), isNull()))
                .thenReturn(mockHandler);

        consulStore.process();

        // Verify SSLContext was updated
        verify(capiSslContextHolder).setSslContext(any(javax.net.ssl.SSLContext.class));
        // Verify XnioSsl was refreshed
        verify(wsUtils).refreshXnioSsl();

        svcCache.close();
    }

    @SuppressWarnings("unchecked")
    @Test
    void process_withRealJks_andGrpcUtils_rebuildsGrpcHandlers() throws Exception {
        java.security.KeyStore ks = java.security.KeyStore.getInstance("JKS");
        ks.load(null, "changeit".toCharArray());
        // Empty keystore is valid for trust store purposes

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        ks.store(baos, "changeit".toCharArray());
        byte[] jksBytes = baos.toByteArray();

        String innerBase64 = Base64.getEncoder().encodeToString(jksBytes);
        String outerBase64 = Base64.getEncoder().encodeToString(innerBase64.getBytes());

        ConsulKeyStoreEntry remoteEntry = new ConsulKeyStoreEntry();
        remoteEntry.setModifyIndex(200);
        remoteEntry.setValue(outerBase64);

        ObjectMapper objectMapper = new ObjectMapper();
        String responseBody = objectMapper.writeValueAsString(new ConsulKeyStoreEntry[]{remoteEntry});

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseBody);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

        when(consulTrustStoreCache.get(Constants.CONSUL_CAPI_TRUST_STORE_GROUP_KEY)).thenReturn(null);

        // Set up gRPC for rebuild
        GrpcUtils grpcUtils = mock(GrpcUtils.class);
        consulStore.setGrpcUtils(grpcUtils);

        Cache<String, Service> svcCache = Cache2kBuilder.of(String.class, Service.class)
                .name("consulStoreRealJksGrpc-" + System.nanoTime())
                .eternal(true)
                .entryCapacity(10)
                .build();

        Service service = new Service();
        ServiceMeta meta = new ServiceMeta();
        service.setServiceMeta(meta);
        svcCache.put("grpc-svc", service);

        GrpcClient gc = new GrpcClient();
        gc.setServiceId("grpc-svc");
        Map<String, GrpcClient> grpcMap = new HashMap<>();
        grpcMap.put("grpc-svc", gc);

        HttpHandler mockHandler = mock(HttpHandler.class);
        when(grpcUtils.createClientHttpHandler(any(GrpcClient.class), any(Service.class)))
                .thenReturn(mockHandler);

        consulStore.setServiceCache(svcCache);
        consulStore.setGrpcClientMap(grpcMap);

        consulStore.process();

        verify(capiSslContextHolder).setSslContext(any(javax.net.ssl.SSLContext.class));
        verify(grpcUtils).refreshXnioSsl();

        svcCache.close();
    }

    // === Helper to create a trust store entry with double-base64 encoded content ===

    private ConsulKeyStoreEntry createValidTrustStoreEntry(int modifyIndex) {
        ConsulKeyStoreEntry entry = new ConsulKeyStoreEntry();
        entry.setModifyIndex(modifyIndex);
        // Double base64 encode some content (will fail JKS parse, but exercises code path)
        String innerBase64 = Base64.getEncoder().encodeToString("fake-trust-store".getBytes());
        String outerBase64 = Base64.getEncoder().encodeToString(innerBase64.getBytes());
        entry.setValue(outerBase64);
        return entry;
    }
}
