package io.surisoft.capi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.surisoft.capi.schema.ApiKeyEntry;
import io.surisoft.capi.schema.ApiKeyStoreEntry;
import io.surisoft.capi.schema.ConsulKeyStoreEntry;
import io.surisoft.capi.utils.Constants;
import org.cache2k.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ApiKeyStore {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyStore.class);
    private final Cache<String, ApiKeyStoreEntry> apiKeyCache;
    private final String consulKvHost;
    private final String consulKvToken;
    private volatile HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ApiKeyStore(Cache<String, ApiKeyStoreEntry> apiKeyCache,
                       String consulKvHost,
                       String consulKvToken,
                       HttpClient httpClient) {
        this.apiKeyCache = apiKeyCache;
        this.consulKvHost = consulKvHost;
        this.consulKvToken = consulKvToken;
        this.httpClient = httpClient;
    }

    public void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public void process() {
        log.debug("Syncing API key store from Consul KV...");
        syncApiKeys();
    }

    private void syncApiKeys() {
        try {
            HttpResponse<String> response = httpClient.send(buildHttpRequest(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                log.debug("No API key entries found in Consul KV");
                return;
            }
            if (response.statusCode() != 200) {
                log.error("Error fetching API keys from Consul KV, status code: {}", response.statusCode());
                return;
            }

            ConsulKeyStoreEntry[] entries = objectMapper.readValue(response.body(), ConsulKeyStoreEntry[].class);
            Set<String> remoteServiceIds = new HashSet<>();

            for (ConsulKeyStoreEntry entry : entries) {
                String key = entry.getKey();
                if (key == null || !key.startsWith(Constants.CONSUL_API_KEY_STORE_PREFIX)) {
                    continue;
                }
                // Strip trailing slash entries (the folder itself)
                if (key.equals(Constants.CONSUL_API_KEY_STORE_PREFIX) || key.endsWith("/")) {
                    continue;
                }
                String serviceId = key.substring(Constants.CONSUL_API_KEY_STORE_PREFIX.length());
                remoteServiceIds.add(serviceId);

                ApiKeyStoreEntry cached = apiKeyCache.get(serviceId);
                if (cached != null && cached.getModifyIndex() == entry.getModifyIndex()) {
                    log.debug("API keys for service {} unchanged (ModifyIndex={})", serviceId, entry.getModifyIndex());
                    continue;
                }

                log.info("Updating API keys for service {}", serviceId);
                String decodedValue = new String(Base64.getDecoder().decode(entry.getValue()));
                ApiKeyEntry[] apiKeys = objectMapper.readValue(decodedValue, ApiKeyEntry[].class);

                Map<String, ApiKeyEntry> keysByHash = new ConcurrentHashMap<>();
                for (ApiKeyEntry apiKey : apiKeys) {
                    if (apiKey.getKeyHash() != null) {
                        keysByHash.put(apiKey.getKeyHash(), apiKey);
                    }
                }

                ApiKeyStoreEntry storeEntry = new ApiKeyStoreEntry();
                storeEntry.setServiceId(serviceId);
                storeEntry.setModifyIndex(entry.getModifyIndex());
                storeEntry.setKeysByHash(keysByHash);
                apiKeyCache.put(serviceId, storeEntry);
            }

            // Evict cached entries for services no longer in Consul
            for (String cachedServiceId : apiKeyCache.keys()) {
                if (!remoteServiceIds.contains(cachedServiceId)) {
                    log.info("Removing API keys for service {} (no longer in Consul)", cachedServiceId);
                    apiKeyCache.remove(cachedServiceId);
                }
            }
        } catch (Exception e) {
            log.error("Error syncing API key store: {}", e.getMessage(), e);
        }
    }

    private HttpRequest buildHttpRequest() {
        HttpRequest.Builder builder = HttpRequest.newBuilder();
        URI uri = URI.create(consulKvHost + Constants.CONSUL_KV_STORE_API + Constants.CONSUL_API_KEY_STORE_PREFIX + "?recurse");
        if (uri.getPath() != null && uri.getPath().contains("..")) {
            throw new IllegalArgumentException("Path traversal detected in URI path: " + uri.getPath());
        }
        if (consulKvToken != null && !consulKvToken.isEmpty()) {
            builder.header(Constants.AUTHORIZATION_HEADER, Constants.BEARER + consulKvToken.replaceAll("(\r\n|\n)", ""));
        }
        return builder
                .uri(uri)
                .timeout(Duration.ofMinutes(2))
                .build();
    }
}
