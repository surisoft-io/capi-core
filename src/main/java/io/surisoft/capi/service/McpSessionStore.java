package io.surisoft.capi.service;

import io.surisoft.capi.schema.McpSession;

public interface McpSessionStore {
    void put(String sessionId, McpSession session);
    McpSession get(String sessionId);
    void remove(String sessionId);
    int size();
}
