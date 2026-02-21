package io.surisoft.capi.schema;

import java.io.Serializable;

public class McpSession implements Serializable {
    private String sessionId;
    private String clientIdentity;
    private long createdAt;
    private long lastAccessedAt;
    private long ttlMillis;

    public McpSession() {}

    public McpSession(String sessionId, String clientIdentity, long ttlMillis) {
        this.sessionId = sessionId;
        this.clientIdentity = clientIdentity;
        this.ttlMillis = ttlMillis;
        this.createdAt = System.currentTimeMillis();
        this.lastAccessedAt = this.createdAt;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() - lastAccessedAt > ttlMillis;
    }

    public void touch() {
        this.lastAccessedAt = System.currentTimeMillis();
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getClientIdentity() {
        return clientIdentity;
    }

    public void setClientIdentity(String clientIdentity) {
        this.clientIdentity = clientIdentity;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getLastAccessedAt() {
        return lastAccessedAt;
    }

    public void setLastAccessedAt(long lastAccessedAt) {
        this.lastAccessedAt = lastAccessedAt;
    }

    public long getTtlMillis() {
        return ttlMillis;
    }

    public void setTtlMillis(long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }
}
