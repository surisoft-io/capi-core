package io.surisoft.capi.service;

import com.hazelcast.map.IMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Cluster-wide WebDAV sessions.
 *
 * <p>Needed as soon as more than one CAPI sits behind a load balancer: the client that creates the
 * session and the client that uses it are the same, but nothing pins them to a node, so a per-process
 * store refuses requests that happen to land elsewhere.
 *
 * <p>Expiry is Hazelcast's per-entry TTL rather than a sweep of our own.
 */
public class HazelcastWebDavSessionStore implements WebDavSessionStore {

    private static final Logger log = LoggerFactory.getLogger(HazelcastWebDavSessionStore.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final IMap<String, String> map;
    private final long ttlSeconds;
    private final int maxSessions;

    public HazelcastWebDavSessionStore(IMap<String, String> map, long ttlSeconds, int maxSessions) {
        this.map = map;
        this.ttlSeconds = ttlSeconds;
        this.maxSessions = maxSessions;
    }

    @Override
    public String create(String accessToken) {
        if (map.size() >= maxSessions) {
            log.warn("WebDAV session store is full ({} across the cluster); refusing to create another",
                    map.size());
            return null;
        }
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        map.put(id, accessToken, ttlSeconds, TimeUnit.SECONDS);
        return id;
    }

    @Override
    public String resolve(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return map.get(id);
    }

    @Override
    public int size() {
        return map.size();
    }
}
