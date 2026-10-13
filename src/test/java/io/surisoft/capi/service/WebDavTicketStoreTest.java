package io.surisoft.capi.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tickets stand in for an access token inside a URL, so the properties that keep that safe are the
 * ones worth testing: single use, short lived, unguessable, and bounded.
 */
class WebDavTicketStoreTest {

    @Test
    void issuedTicketRedeemsToTheTokenItWasMintedFrom() {
        WebDavTicketStore store = new LocalWebDavTicketStore(60, 100);
        String ticket = store.issue("THE_JWT");
        assertNotNull(ticket);
        assertEquals("THE_JWT", store.redeem(ticket));
    }

    @Test
    void aTicketIsSingleUse() {
        // The copy left behind in a recent-documents list or a log must not be replayable.
        WebDavTicketStore store = new LocalWebDavTicketStore(60, 100);
        String ticket = store.issue("THE_JWT");
        assertEquals("THE_JWT", store.redeem(ticket));
        assertNull(store.redeem(ticket), "a second redemption must fail");
        assertNull(store.redeem(ticket));
    }

    @Test
    void anExpiredTicketIsRefused() throws Exception {
        WebDavTicketStore store = new LocalWebDavTicketStore(0, 100);
        String ticket = store.issue("THE_JWT");
        Thread.sleep(5);
        assertNull(store.redeem(ticket));
    }

    @Test
    void unknownAndMalformedTicketsAreRefused() {
        WebDavTicketStore store = new LocalWebDavTicketStore(60, 100);
        assertNull(store.redeem("nope"));
        assertNull(store.redeem(""));
        assertNull(store.redeem(null));
    }

    @Test
    void ticketsAreUnguessableAndDistinct() {
        WebDavTicketStore store = new LocalWebDavTicketStore(60, 10_000);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            String t = store.issue("THE_JWT");
            assertNotNull(t);
            assertTrue(seen.add(t), "tickets must not repeat");
            // 128 bits, base64url without padding
            assertEquals(22, t.length());
            assertTrue(t.matches("[A-Za-z0-9_-]{22}"), "unexpected alphabet: " + t);
        }
    }

    @Test
    void theStoreIsBounded() {
        // Without a cap, anything able to mint could exhaust the heap.
        WebDavTicketStore store = new LocalWebDavTicketStore(60, 3);
        assertNotNull(store.issue("a"));
        assertNotNull(store.issue("b"));
        assertNotNull(store.issue("c"));
        assertNull(store.issue("d"), "past the cap, minting must be refused rather than unbounded");
        assertEquals(3, store.size());
    }

    @Test
    void redeemingFreesCapacity() {
        WebDavTicketStore store = new LocalWebDavTicketStore(60, 1);
        String first = store.issue("a");
        assertNull(store.issue("b"));
        assertEquals("a", store.redeem(first));
        assertNotNull(store.issue("b"), "a redeemed ticket must not hold its slot");
    }

    @Test
    void expiredTicketsAreEvictedSoTheyDoNotFillTheStore() throws Exception {
        WebDavTicketStore store = new LocalWebDavTicketStore(0, 2);
        store.issue("a");
        store.issue("b");
        Thread.sleep(5);
        assertNotNull(store.issue("c"), "expired tickets must not permanently consume the cap");
    }
}
