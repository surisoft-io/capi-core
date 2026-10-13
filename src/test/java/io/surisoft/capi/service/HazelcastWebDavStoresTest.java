package io.surisoft.capi.service;

import com.hazelcast.map.IMap;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The cluster-wide stores. Hazelcast itself is not under test — what matters is that these keep the
 * invariants the local ones have, and that single use is expressed as an atomic remove rather than a
 * get-then-delete that two nodes could both win.
 */
class HazelcastWebDavStoresTest {

    /** A stand-in for IMap backed by a plain map; TTL arguments are recorded, not honoured. */
    @SuppressWarnings("unchecked")
    private static IMap<String, String> fakeMap(Map<String, String> backing) {
        IMap<String, String> map = mock(IMap.class);
        when(map.size()).thenAnswer(i -> backing.size());
        when(map.get(anyString())).thenAnswer(i -> backing.get(i.getArgument(0)));
        when(map.remove(anyString())).thenAnswer(i -> backing.remove(i.getArgument(0)));
        when(map.put(anyString(), anyString(), anyLong(), any()))
                .thenAnswer(i -> backing.put(i.getArgument(0), i.getArgument(1)));
        return map;
    }

    @Test
    void sessionRoundTrips() {
        Map<String, String> backing = new HashMap<>();
        WebDavSessionStore store = new HazelcastWebDavSessionStore(fakeMap(backing), 3600, 100);
        String id = store.create("THE_JWT");
        assertEquals(22, id.length());
        assertEquals("THE_JWT", store.resolve(id));
        assertEquals("THE_JWT", store.resolve(id), "a session is reusable");
        assertEquals(1, store.size());
    }

    @Test
    void sessionIsWrittenWithATtlSoItExpiresClusterWide() {
        Map<String, String> backing = new HashMap<>();
        IMap<String, String> map = fakeMap(backing);
        new HazelcastWebDavSessionStore(map, 1800, 100).create("THE_JWT");
        verify(map).put(anyString(), eq("THE_JWT"), eq(1800L), eq(java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    void ticketIsSingleUseViaAnAtomicRemove() {
        Map<String, String> backing = new HashMap<>();
        IMap<String, String> map = fakeMap(backing);
        WebDavTicketStore store = new HazelcastWebDavTicketStore(map, 60, 100);
        String ticket = store.issue("THE_JWT");

        assertEquals("THE_JWT", store.redeem(ticket));
        assertNull(store.redeem(ticket), "a second redemption must fail");
        // remove() returns the value and deletes in one cluster-wide operation, so two nodes
        // redeeming concurrently cannot both succeed.
        verify(map, times(2)).remove(ticket);
        verify(map, never()).get(ticket);
    }

    @Test
    void storesAreBounded() {
        Map<String, String> backing = new HashMap<>();
        WebDavSessionStore sessions = new HazelcastWebDavSessionStore(fakeMap(backing), 3600, 2);
        assertNotNull(sessions.create("a"));
        assertNotNull(sessions.create("b"));
        assertNull(sessions.create("c"), "the cap is cluster-wide, counted from the shared map");

        Map<String, String> tbacking = new HashMap<>();
        WebDavTicketStore tickets = new HazelcastWebDavTicketStore(fakeMap(tbacking), 60, 1);
        assertNotNull(tickets.issue("a"));
        assertNull(tickets.issue("b"));
    }

    @Test
    void idsAreUnguessableAndDistinct() {
        Map<String, String> backing = new HashMap<>();
        WebDavSessionStore store = new HazelcastWebDavSessionStore(fakeMap(backing), 3600, 10_000);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String id = store.create("t");
            assertTrue(seen.add(id));
            assertTrue(id.matches("[A-Za-z0-9_-]{22}"));
        }
    }

    @Test
    void nullAndBlankResolveToNothing() {
        Map<String, String> backing = new HashMap<>();
        assertNull(new HazelcastWebDavSessionStore(fakeMap(backing), 3600, 100).resolve(null));
        assertNull(new HazelcastWebDavSessionStore(fakeMap(backing), 3600, 100).resolve(" "));
        assertNull(new HazelcastWebDavTicketStore(fakeMap(backing), 60, 100).redeem(null));
        assertNull(new HazelcastWebDavTicketStore(fakeMap(backing), 60, 100).redeem(""));
    }
}
