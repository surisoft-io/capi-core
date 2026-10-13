package io.surisoft.capi.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short, single-use references to an access token, so a credential can travel in a URL that a
 * protocol handler will actually accept.
 *
 * <p>A document is opened by handing the client a URL and nothing else, so the query string is the
 * only way to get a credential into that first request — but a JWT makes the resulting
 * {@code ms-word:} URI long enough that Word opens and then does nothing at all (observed). A ticket
 * is 22 characters, so the URI stays short.
 *
 * <p>The properties that make this safe to put in a URL: it is single-use (redeeming removes it),
 * short-lived (seconds, not the token's lifetime), and unguessable (128 bits from
 * {@link SecureRandom}). A ticket carries no authority of its own — the token it resolves to is run
 * through the normal subscription and policy checks on every request, exactly as a bearer token
 * would be.
 */
public class LocalWebDavTicketStore implements WebDavTicketStore {

    private static final Logger log = LoggerFactory.getLogger(WebDavTicketStore.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private record Ticket(String accessToken, long expiresAt) {}

    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final int maxOutstanding;

    public LocalWebDavTicketStore(long ttlSeconds, int maxOutstanding) {
        this.ttlMillis = ttlSeconds * 1000L;
        this.maxOutstanding = maxOutstanding;
    }

    /**
     * @return a new ticket for this token, or null when the store is full
     */
    @Override
    public String issue(String accessToken) {
        evictExpired();
        if (tickets.size() >= maxOutstanding) {
            log.warn("WebDAV ticket store is full ({} outstanding); refusing to issue", tickets.size());
            return null;
        }
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        tickets.put(ticket, new Ticket(accessToken, System.currentTimeMillis() + ttlMillis));
        return ticket;
    }

    /**
     * Resolves a ticket and consumes it. A second attempt with the same ticket returns null, so a
     * ticket left behind in a log or a recent-documents list cannot be replayed.
     *
     * @return the access token, or null when the ticket is unknown, already used, or expired
     */
    @Override
    public String redeem(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return null;
        }
        Ticket found = tickets.remove(ticket);
        if (found == null) {
            return null;
        }
        if (System.currentTimeMillis() > found.expiresAt()) {
            return null;
        }
        return found.accessToken();
    }

    /** Outstanding, unredeemed tickets. */
    @Override
    public int size() {
        return tickets.size();
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        tickets.entrySet().removeIf(e -> now > e.getValue().expiresAt());
    }
}
