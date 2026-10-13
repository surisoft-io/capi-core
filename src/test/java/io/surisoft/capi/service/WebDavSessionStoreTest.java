package io.surisoft.capi.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sessions exist so the cookie can be a short opaque id instead of the access token. The token itself
 * stays here and never reaches the client.
 */
class WebDavSessionStoreTest {

    @Test
    void sessionResolvesToItsToken() {
        WebDavSessionStore store = new LocalWebDavSessionStore(3600, 100);
        String id = store.create("THE_JWT");
        assertNotNull(id);
        assertEquals("THE_JWT", store.resolve(id));
    }

    @Test
    void aSessionIsReusable() {
        // Unlike a ticket: every request in a document-editing session resolves through the same id.
        WebDavSessionStore store = new LocalWebDavSessionStore(3600, 100);
        String id = store.create("THE_JWT");
        for (int i = 0; i < 50; i++) {
            assertEquals("THE_JWT", store.resolve(id), "request " + i + " must still resolve");
        }
    }

    @Test
    void theIdIsShortEnoughToCookieAndCarriesNoToken() {
        // The whole point: a ~2 KB cookie full of JWT may be silently dropped by a client.
        WebDavSessionStore store = new LocalWebDavSessionStore(3600, 100);
        String jwt = "eyJhbGciOiJSUzI1NiJ9." + "x".repeat(1800) + ".sig";
        String id = store.create(jwt);
        assertEquals(22, id.length());
        assertFalse(id.contains("eyJ"), "the id must not carry any of the token");
        assertTrue(jwt.length() > 1000 && id.length() < 30, "the point is the size difference");
    }

    @Test
    void idsAreUnguessableAndDistinct() {
        WebDavSessionStore store = new LocalWebDavSessionStore(3600, 10_000);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            String id = store.create("t");
            assertTrue(seen.add(id), "session ids must not repeat");
            assertTrue(id.matches("[A-Za-z0-9_-]{22}"), "unexpected alphabet: " + id);
        }
    }

    @Test
    void anExpiredSessionStopsResolving() throws Exception {
        WebDavSessionStore store = new LocalWebDavSessionStore(0, 100);
        String id = store.create("THE_JWT");
        Thread.sleep(5);
        assertNull(store.resolve(id));
    }

    @Test
    void unknownIdsResolveToNothing() {
        WebDavSessionStore store = new LocalWebDavSessionStore(3600, 100);
        assertNull(store.resolve("nope"));
        assertNull(store.resolve(""));
        assertNull(store.resolve(null));
    }

    @Test
    void theStoreIsBounded() {
        WebDavSessionStore store = new LocalWebDavSessionStore(3600, 2);
        assertNotNull(store.create("a"));
        assertNotNull(store.create("b"));
        assertNull(store.create("c"), "past the cap, creation must be refused rather than unbounded");
    }

    @Test
    void expiredSessionsFreeCapacity() throws Exception {
        WebDavSessionStore store = new LocalWebDavSessionStore(0, 2);
        store.create("a");
        store.create("b");
        Thread.sleep(5);
        assertNotNull(store.create("c"), "expired sessions must not hold the cap forever");
    }
}
