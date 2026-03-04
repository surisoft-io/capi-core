package io.surisoft.capi.configuration;

import io.surisoft.capi.schema.ApiKeyStoreEntry;
import io.surisoft.capi.schema.ConsulKeyStoreEntry;
import io.surisoft.capi.schema.McpSession;
import io.surisoft.capi.schema.Service;
import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;

import java.util.concurrent.TimeUnit;

public class LocalCacheConfiguration {

    public static Cache<String, Service> serviceCache() {

        return new Cache2kBuilder<String, Service>(){}
                .name("serviceCache-" + System.currentTimeMillis())
                .eternal(true)
                .entryCapacity(10000)
                .storeByReference(true)
                .build();
    }

    public static Cache<String, ConsulKeyStoreEntry> consulStoreCache() {
        return new Cache2kBuilder<String, ConsulKeyStoreEntry>(){}
                .name("consulStoreCache-" + System.currentTimeMillis())
                .eternal(true)
                .entryCapacity(10000)
                .storeByReference(true)
                .build();
    }

    public static Cache<String, ApiKeyStoreEntry> apiKeyCache() {
        return new Cache2kBuilder<String, ApiKeyStoreEntry>(){}
                .name("apiKeyCache-" + System.currentTimeMillis())
                .eternal(true)
                .entryCapacity(10000)
                .storeByReference(true)
                .build();
    }

    public static Cache<String, McpSession> mcpSessionCache(long ttlMillis) {
        return new Cache2kBuilder<String, McpSession>(){}
                .name("mcpSessionCache-" + System.currentTimeMillis())
                .expireAfterWrite(ttlMillis, TimeUnit.MILLISECONDS)
                .entryCapacity(10000)
                .storeByReference(true)
                .build();
    }
}
