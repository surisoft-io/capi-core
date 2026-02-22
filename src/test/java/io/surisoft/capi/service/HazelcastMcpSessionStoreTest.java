package io.surisoft.capi.service;

import com.hazelcast.map.IMap;
import io.surisoft.capi.schema.McpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HazelcastMcpSessionStoreTest {

    @Mock
    private IMap<String, McpSession> map;

    private HazelcastMcpSessionStore store;

    @BeforeEach
    void setUp() {
        store = new HazelcastMcpSessionStore(map);
    }

    @Test
    void put_delegatesToMapWithTtl() {
        McpSession session = new McpSession("s1", "client", 60000);
        store.put("s1", session);

        verify(map).put("s1", session, 60000L, TimeUnit.MILLISECONDS);
    }

    @Test
    void get_delegatesToMap() {
        McpSession session = new McpSession("s1", "client", 60000);
        when(map.get("s1")).thenReturn(session);

        McpSession result = store.get("s1");
        assertNotNull(result);
        assertEquals("s1", result.getSessionId());
        verify(map).get("s1");
    }

    @Test
    void get_nonExistent_returnsNull() {
        when(map.get("nonexistent")).thenReturn(null);

        assertNull(store.get("nonexistent"));
        verify(map).get("nonexistent");
    }

    @Test
    void remove_delegatesToMap() {
        store.remove("s1");
        verify(map).remove("s1");
    }

    @Test
    void size_delegatesToMap() {
        when(map.size()).thenReturn(5);

        assertEquals(5, store.size());
        verify(map).size();
    }
}
