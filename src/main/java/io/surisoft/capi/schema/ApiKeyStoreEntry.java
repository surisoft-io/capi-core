package io.surisoft.capi.schema;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ApiKeyStoreEntry {
    private String serviceId;
    private int modifyIndex;
    private Map<String, ApiKeyEntry> keysByHash = new ConcurrentHashMap<>();

    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }

    public int getModifyIndex() {
        return modifyIndex;
    }

    public void setModifyIndex(int modifyIndex) {
        this.modifyIndex = modifyIndex;
    }

    public Map<String, ApiKeyEntry> getKeysByHash() {
        return keysByHash;
    }

    public void setKeysByHash(Map<String, ApiKeyEntry> keysByHash) {
        this.keysByHash = keysByHash;
    }
}
