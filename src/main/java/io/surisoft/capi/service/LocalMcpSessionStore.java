package io.surisoft.capi.service;

import io.surisoft.capi.schema.McpSession;
import org.cache2k.Cache;

public class LocalMcpSessionStore implements McpSessionStore {

    private final Cache<String, McpSession> cache;

    public LocalMcpSessionStore(Cache<String, McpSession> cache) {
        this.cache = cache;
    }

    @Override
    public void put(String sessionId, McpSession session) {
        cache.put(sessionId, session);
    }

    @Override
    public McpSession get(String sessionId) {
        return cache.peek(sessionId);
    }

    @Override
    public void remove(String sessionId) {
        cache.remove(sessionId);
    }

    @Override
    public int size() {
        return (int) cache.asMap().size();
    }
}
