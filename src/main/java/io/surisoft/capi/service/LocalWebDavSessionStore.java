package io.surisoft.capi.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side WebDAV sessions, so the cookie handed to a client is a short opaque id rather than the
 * access token itself.
 *
 * <p>Putting the raw JWT in the cookie made it roughly 2 KB — large enough that a client may quietly
 * decline to store or return it, and it meant the token travelled to the client and sat in its cookie
 * jar for the session's lifetime. An id is 22 characters and carries nothing: it is meaningless
 * without this store.
 *
 * <p>Unlike {@link WebDavTicketStore} a session is reusable — every request in a document-editing
 * session resolves through it — but it still expires, and the token it resolves to is run through the
 * normal subscription and policy checks on each request.
 *
 * <p>In memory, so it is per-process: with more than one CAPI behind a balancer, a session created on
 * one node is unknown to the others unless requests are pinned to a node.
 */
public class LocalWebDavSessionStore implements WebDavSessionStore {

    private static final Logger log = LoggerFactory.getLogger(WebDavSessionStore.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private record Session(String accessToken, long expiresAt) {}

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final int maxSessions;

    public LocalWebDavSessionStore(long ttlSeconds, int maxSessions) {
        this.ttlMillis = ttlSeconds * 1000L;
        this.maxSessions = maxSessions;
    }

    /**
     * @return an opaque session id for this token, or null when the store is full
     */
    @Override
    public String create(String accessToken) {
        evictExpired();
        if (sessions.size() >= maxSessions) {
            log.warn("WebDAV session store is full ({} sessions); refusing to create another", sessions.size());
            return null;
        }
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        sessions.put(id, new Session(accessToken, System.currentTimeMillis() + ttlMillis));
        return id;
    }

    /**
     * Resolves a session id to the token it was created from. Unlike a ticket this does not consume
     * it — every request in the session resolves through the same id.
     *
     * @return the access token, or null when the id is unknown or expired
     */
    @Override
    public String resolve(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        Session session = sessions.get(id);
        if (session == null) {
            return null;
        }
        if (System.currentTimeMillis() > session.expiresAt()) {
            sessions.remove(id);
            return null;
        }
        return session.accessToken();
    }

    @Override
    public int size() {
        return sessions.size();
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> now > e.getValue().expiresAt());
    }
}
