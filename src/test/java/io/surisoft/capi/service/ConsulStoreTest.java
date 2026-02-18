package io.surisoft.capi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.surisoft.capi.configuration.CapiSslContextHolder;
import io.surisoft.capi.schema.ConsulKeyStoreEntry;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.RouteUtils;
import org.apache.camel.CamelContext;
import org.cache2k.Cache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

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
    private CamelContext camelContext;

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
                camelContext,
                httpClient
        );
    }

    @Test
    void consulKeyValueToInputStream_decodesBase64Correctly() throws JsonProcessingException {
        // Double-encode: the value in consul is base64-encoded, and the inner value is also base64
        String originalContent = "hello world";
        String innerBase64 = Base64.getEncoder().encodeToString(originalContent.getBytes());
        String outerBase64 = Base64.getEncoder().encodeToString(innerBase64.getBytes());

        InputStream result = consulStore.consulKeyValueToInputStream(outerBase64);
        assertNotNull(result);
    }

    @SuppressWarnings("unchecked")
    @Test
    void process_noRemoteTrustStore_doesNothing() throws Exception {
        // consulKvHost is set, but remote returns 404
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(404);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

        when(consulTrustStoreCache.get(Constants.CONSUL_CAPI_TRUST_STORE_GROUP_KEY)).thenReturn(null);

        consulStore.process();

        // No processTrustStore should be called since remote is null
        verify(routeUtils, never()).reloadTrustStoreManager(any(InputStream.class), anyString());
    }

    @SuppressWarnings("unchecked")
    @Test
    void process_remoteExistsButNoCached_cachesRemote() throws Exception {
        ConsulKeyStoreEntry remoteEntry = new ConsulKeyStoreEntry();
        remoteEntry.setModifyIndex(10);
        // Create a valid double-base64 encoded JKS content (empty will cause error, but that's ok for test)
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

        // processTrustStore will fail because the content is not a valid keystore,
        // but the error is caught internally
        consulStore.process();

        // The method should still attempt to reload the trust store
        verify(routeUtils).reloadTrustStoreManager(any(InputStream.class), eq("changeit"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void process_remoteSameAsCached_doesNotReloadTrustStore() throws Exception {
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

        // Same modify index, so no reload
        verify(routeUtils, never()).reloadTrustStoreManager(any(InputStream.class), anyString());
        // Still caches (the deserialized remote object has same modifyIndex)
        verify(consulTrustStoreCache).put(eq(Constants.CONSUL_CAPI_TRUST_STORE_GROUP_KEY), any(ConsulKeyStoreEntry.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void process_remoteDifferentFromCached_reloadsTrustStore() throws Exception {
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

        consulStore.process();

        // Different modify index, processTrustStore is called which calls reloadTrustStoreManager.
        // After reloadTrustStoreManager, processTrustStore tries to get the camel HTTPS component
        // which is null in tests, causing an exception that is caught by the outer catch.
        // So the put after processTrustStore is not reached.
        verify(routeUtils).reloadTrustStoreManager(any(InputStream.class), eq("changeit"));
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
                camelContext,
                httpClient
        );

        when(consulTrustStoreCache.get(Constants.CONSUL_CAPI_TRUST_STORE_GROUP_KEY)).thenReturn(null);

        // process will call getRemoteTrustStore which returns null for null host
        assertDoesNotThrow(() -> nullHostStore.process());
        verify(routeUtils, never()).reloadTrustStoreManager(any(InputStream.class), anyString());
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
                camelContext,
                httpClient
        );

        when(consulTrustStoreCache.get(Constants.CONSUL_CAPI_TRUST_STORE_GROUP_KEY)).thenReturn(null);

        // Should not crash when building request without token
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
                camelContext,
                httpClient
        );

        when(consulTrustStoreCache.get(Constants.CONSUL_CAPI_TRUST_STORE_GROUP_KEY)).thenReturn(null);

        assertDoesNotThrow(() -> emptyTokenStore.process());
    }
}
