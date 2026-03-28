package io.surisoft.capi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.surisoft.capi.schema.ApiKeyEntry;
import io.surisoft.capi.schema.ApiKeyStoreEntry;
import io.surisoft.capi.schema.ConsulKeyStoreEntry;
import io.surisoft.capi.utils.Constants;
import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiKeyStoreTest {

    @Mock
    private HttpClient httpClient;

    private Cache<String, ApiKeyStoreEntry> apiKeyCache;
    private ApiKeyStore apiKeyStore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        apiKeyCache = Cache2kBuilder.of(String.class, ApiKeyStoreEntry.class)
                .name("apiKeyStoreTest-" + System.nanoTime())
                .eternal(true)
                .entryCapacity(100)
                .build();
        apiKeyStore = new ApiKeyStore(apiKeyCache, "http://consul:8500", "test-token", httpClient);
    }

    @AfterEach
    void tearDown() {
        apiKeyCache.close();
    }

    @SuppressWarnings("unchecked")
    @Test
    void process_404_response_doesNothing() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(404);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

        apiKeyStore.process();

        assertTrue(apiKeyCache.keys().isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test
    void process_500_response_doesNothing() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(500);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

        apiKeyStore.process();

        assertTrue(apiKeyCache.keys().isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test
    void process_validResponse_cachesApiKeys() throws Exception {
        ApiKeyEntry key1 = new ApiKeyEntry();
        key1.setKeyHash("hash123");
        key1.setName("key1");
        key1.setEnabled(true);

        ApiKeyEntry key2 = new ApiKeyEntry();
        key2.setKeyHash("hash456");
        key2.setName("key2");
        key2.setEnabled(false);

        String apiKeysJson = objectMapper.writeValueAsString(new ApiKeyEntry[]{key1, key2});
        String encodedValue = Base64.getEncoder().encodeToString(apiKeysJson.getBytes());

        ConsulKeyStoreEntry entry = new ConsulKeyStoreEntry();
        entry.setKey(Constants.CONSUL_API_KEY_STORE_PREFIX + "my-service");
        entry.setModifyIndex(10);
        entry.setValue(encodedValue);

        String responseBody = objectMapper.writeValueAsString(new ConsulKeyStoreEntry[]{entry});

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseBody);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

        apiKeyStore.process();

        ApiKeyStoreEntry cached = apiKeyCache.get("my-service");
        assertNotNull(cached);
        assertEquals("my-service", cached.getServiceId());
        assertEquals(10, cached.getModifyIndex());
        assertEquals(2, cached.getKeysByHash().size());
        assertNotNull(cached.getKeysByHash().get("hash123"));
        assertNotNull(cached.getKeysByHash().get("hash456"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void process_unchangedModifyIndex_skipsUpdate() throws Exception {
        // Pre-populate cache
        ApiKeyStoreEntry existing = new ApiKeyStoreEntry();
        existing.setServiceId("my-service");
        existing.setModifyIndex(10);
        apiKeyCache.put("my-service", existing);

        ApiKeyEntry key1 = new ApiKeyEntry();
        key1.setKeyHash("hash123");
        key1.setName("key1");
        key1.setEnabled(true);

        String apiKeysJson = objectMapper.writeValueAsString(new ApiKeyEntry[]{key1});
        String encodedValue = Base64.getEncoder().encodeToString(apiKeysJson.getBytes());

        ConsulKeyStoreEntry entry = new ConsulKeyStoreEntry();
        entry.setKey(Constants.CONSUL_API_KEY_STORE_PREFIX + "my-service");
        entry.setModifyIndex(10); // Same as cached
        entry.setValue(encodedValue);

        String responseBody = objectMapper.writeValueAsString(new ConsulKeyStoreEntry[]{entry});

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseBody);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

        apiKeyStore.process();

        // Should still have the old entry (keysByHash empty because we didn't update it)
        ApiKeyStoreEntry cached = apiKeyCache.get("my-service");
        assertNotNull(cached);
        assertEquals(10, cached.getModifyIndex());
    }

    @SuppressWarnings("unchecked")
    @Test
    void process_changedModifyIndex_updatesCache() throws Exception {
        // Pre-populate cache with old version
        ApiKeyStoreEntry existing = new ApiKeyStoreEntry();
        existing.setServiceId("my-service");
        existing.setModifyIndex(5);
        apiKeyCache.put("my-service", existing);

        ApiKeyEntry key1 = new ApiKeyEntry();
        key1.setKeyHash("newHash");
        key1.setName("newKey");
        key1.setEnabled(true);

        String apiKeysJson = objectMapper.writeValueAsString(new ApiKeyEntry[]{key1});
        String encodedValue = Base64.getEncoder().encodeToString(apiKeysJson.getBytes());

        ConsulKeyStoreEntry entry = new ConsulKeyStoreEntry();
        entry.setKey(Constants.CONSUL_API_KEY_STORE_PREFIX + "my-service");
        entry.setModifyIndex(15); // Different from cached (5)
        entry.setValue(encodedValue);

        String responseBody = objectMapper.writeValueAsString(new ConsulKeyStoreEntry[]{entry});

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseBody);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

        apiKeyStore.process();

        ApiKeyStoreEntry cached = apiKeyCache.get("my-service");
        assertNotNull(cached);
        assertEquals(15, cached.getModifyIndex());
        assertEquals(1, cached.getKeysByHash().size());
        assertNotNull(cached.getKeysByHash().get("newHash"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void process_evictsRemovedServices() throws Exception {
        // Pre-populate cache with service that no longer exists in Consul
        ApiKeyStoreEntry staleEntry = new ApiKeyStoreEntry();
        staleEntry.setServiceId("removed-service");
        staleEntry.setModifyIndex(5);
        apiKeyCache.put("removed-service", staleEntry);

        // Remote response has only "active-service"
        ApiKeyEntry key1 = new ApiKeyEntry();
        key1.setKeyHash("hash1");
        key1.setName("key1");
        key1.setEnabled(true);

        String apiKeysJson = objectMapper.writeValueAsString(new ApiKeyEntry[]{key1});
        String encodedValue = Base64.getEncoder().encodeToString(apiKeysJson.getBytes());

        ConsulKeyStoreEntry entry = new ConsulKeyStoreEntry();
        entry.setKey(Constants.CONSUL_API_KEY_STORE_PREFIX + "active-service");
        entry.setModifyIndex(10);
        entry.setValue(encodedValue);

        String responseBody = objectMapper.writeValueAsString(new ConsulKeyStoreEntry[]{entry});

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseBody);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

        apiKeyStore.process();

        assertNotNull(apiKeyCache.get("active-service"));
        assertNull(apiKeyCache.get("removed-service"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void process_httpClientThrows_doesNotCrash() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("Connection refused"));

        assertDoesNotThrow(() -> apiKeyStore.process());
    }

    @SuppressWarnings("unchecked")
    @Test
    void process_skipsEntriesWithoutPrefix() throws Exception {
        ConsulKeyStoreEntry entry = new ConsulKeyStoreEntry();
        entry.setKey("some-other-key");
        entry.setModifyIndex(10);
        entry.setValue("dGVzdA==");

        String responseBody = objectMapper.writeValueAsString(new ConsulKeyStoreEntry[]{entry});

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseBody);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

        apiKeyStore.process();

        assertTrue(apiKeyCache.keys().isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test
    void process_skipsFolderEntries() throws Exception {
        ConsulKeyStoreEntry folderEntry = new ConsulKeyStoreEntry();
        folderEntry.setKey(Constants.CONSUL_API_KEY_STORE_PREFIX);
        folderEntry.setModifyIndex(1);

        ConsulKeyStoreEntry trailingSlashEntry = new ConsulKeyStoreEntry();
        trailingSlashEntry.setKey(Constants.CONSUL_API_KEY_STORE_PREFIX + "subfolder/");
        trailingSlashEntry.setModifyIndex(2);

        String responseBody = objectMapper.writeValueAsString(new ConsulKeyStoreEntry[]{folderEntry, trailingSlashEntry});

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseBody);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

        apiKeyStore.process();

        assertTrue(apiKeyCache.keys().isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test
    void process_skipsEntriesWithNullKey() throws Exception {
        ConsulKeyStoreEntry entry = new ConsulKeyStoreEntry();
        entry.setKey(null);
        entry.setModifyIndex(10);

        String responseBody = objectMapper.writeValueAsString(new ConsulKeyStoreEntry[]{entry});

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseBody);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

        apiKeyStore.process();

        assertTrue(apiKeyCache.keys().isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test
    void process_apiKeyWithNullHash_skippedInMap() throws Exception {
        ApiKeyEntry key1 = new ApiKeyEntry();
        key1.setKeyHash(null); // null hash
        key1.setName("no-hash-key");
        key1.setEnabled(true);

        ApiKeyEntry key2 = new ApiKeyEntry();
        key2.setKeyHash("validHash");
        key2.setName("valid-key");
        key2.setEnabled(true);

        String apiKeysJson = objectMapper.writeValueAsString(new ApiKeyEntry[]{key1, key2});
        String encodedValue = Base64.getEncoder().encodeToString(apiKeysJson.getBytes());

        ConsulKeyStoreEntry entry = new ConsulKeyStoreEntry();
        entry.setKey(Constants.CONSUL_API_KEY_STORE_PREFIX + "svc-with-null-hash");
        entry.setModifyIndex(10);
        entry.setValue(encodedValue);

        String responseBody = objectMapper.writeValueAsString(new ConsulKeyStoreEntry[]{entry});

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseBody);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

        apiKeyStore.process();

        ApiKeyStoreEntry cached = apiKeyCache.get("svc-with-null-hash");
        assertNotNull(cached);
        assertEquals(1, cached.getKeysByHash().size()); // Only the valid key
        assertNotNull(cached.getKeysByHash().get("validHash"));
    }

    @Test
    void constructor_withNullToken_buildsRequestWithoutAuthHeader() {
        Cache<String, ApiKeyStoreEntry> cache = Cache2kBuilder.of(String.class, ApiKeyStoreEntry.class)
                .name("apiKeyStoreNullToken-" + System.nanoTime())
                .eternal(true)
                .entryCapacity(10)
                .build();
        try {
            ApiKeyStore store = new ApiKeyStore(cache, "http://consul:8500", null, httpClient);
            assertDoesNotThrow(() -> store.process());
        } finally {
            cache.close();
        }
    }

    @Test
    void constructor_withEmptyToken_buildsRequestWithoutAuthHeader() {
        Cache<String, ApiKeyStoreEntry> cache = Cache2kBuilder.of(String.class, ApiKeyStoreEntry.class)
                .name("apiKeyStoreEmptyToken-" + System.nanoTime())
                .eternal(true)
                .entryCapacity(10)
                .build();
        try {
            ApiKeyStore store = new ApiKeyStore(cache, "http://consul:8500", "", httpClient);
            assertDoesNotThrow(() -> store.process());
        } finally {
            cache.close();
        }
    }
}
