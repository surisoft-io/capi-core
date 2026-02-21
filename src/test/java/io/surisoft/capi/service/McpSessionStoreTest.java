package io.surisoft.capi.service;

import io.surisoft.capi.schema.McpSession;
import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class McpSessionStoreTest {

    private Cache<String, McpSession> cache;
    private LocalMcpSessionStore store;

    @BeforeEach
    void setUp() {
        cache = new Cache2kBuilder<String, McpSession>() {}
                .name("testSessionStore-" + System.currentTimeMillis())
                .expireAfterWrite(1800000, TimeUnit.MILLISECONDS)
                .entryCapacity(100)
                .storeByReference(true)
                .build();
        store = new LocalMcpSessionStore(cache);
    }

    @AfterEach
    void tearDown() {
        cache.close();
    }

    @Test
    void put_andGet_returnsSession() {
        McpSession session = new McpSession("s1", "client", 60000);
        store.put("s1", session);

        McpSession retrieved = store.get("s1");
        assertNotNull(retrieved);
        assertEquals("s1", retrieved.getSessionId());
    }

    @Test
    void get_nonExistent_returnsNull() {
        assertNull(store.get("nonexistent"));
    }

    @Test
    void remove_deletesSession() {
        McpSession session = new McpSession("s1", "client", 60000);
        store.put("s1", session);
        assertNotNull(store.get("s1"));

        store.remove("s1");
        assertNull(store.get("s1"));
    }

    @Test
    void size_reflectsEntries() {
        assertEquals(0, store.size());

        store.put("s1", new McpSession("s1", "c1", 60000));
        assertEquals(1, store.size());

        store.put("s2", new McpSession("s2", "c2", 60000));
        assertEquals(2, store.size());

        store.remove("s1");
        assertEquals(1, store.size());
    }

    @Test
    void put_overwrites_existingSession() {
        McpSession session1 = new McpSession("s1", "client-a", 60000);
        store.put("s1", session1);
        assertEquals("client-a", store.get("s1").getClientIdentity());

        McpSession session2 = new McpSession("s1", "client-b", 60000);
        store.put("s1", session2);
        assertEquals("client-b", store.get("s1").getClientIdentity());
    }
}
