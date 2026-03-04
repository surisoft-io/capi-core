package io.surisoft.capi.schema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiKeyEntry {
    private String keyHash;
    private String name;
    private boolean enabled;
    private long throttleTotalCalls = -1;
    private long throttleDuration = -1;

    public String getKeyHash() {
        return keyHash;
    }

    public void setKeyHash(String keyHash) {
        this.keyHash = keyHash;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getThrottleTotalCalls() {
        return throttleTotalCalls;
    }

    public void setThrottleTotalCalls(long throttleTotalCalls) {
        this.throttleTotalCalls = throttleTotalCalls;
    }

    public long getThrottleDuration() {
        return throttleDuration;
    }

    public void setThrottleDuration(long throttleDuration) {
        this.throttleDuration = throttleDuration;
    }
}
