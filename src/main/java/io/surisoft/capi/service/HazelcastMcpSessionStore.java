package io.surisoft.capi.service;

import com.hazelcast.map.IMap;
import io.surisoft.capi.schema.McpSession;

import java.util.concurrent.TimeUnit;

public class HazelcastMcpSessionStore implements McpSessionStore {

    private final IMap<String, McpSession> map;

    public HazelcastMcpSessionStore(IMap<String, McpSession> map) {
        this.map = map;
    }

    @Override
    public void put(String sessionId, McpSession session) {
        map.put(sessionId, session, session.getTtlMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public McpSession get(String sessionId) {
        return map.get(sessionId);
    }

    @Override
    public void remove(String sessionId) {
        map.remove(sessionId);
    }

    @Override
    public int size() {
        return map.size();
    }
}
