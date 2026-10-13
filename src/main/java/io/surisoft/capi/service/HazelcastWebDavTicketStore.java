package io.surisoft.capi.service;

import com.hazelcast.map.IMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Cluster-wide single-use tickets.
 *
 * <p>This is the one that breaks first behind a load balancer: a ticket is minted by the web
 * application and redeemed by the document client, two different callers with no shared affinity, so
 * without a distributed store they routinely land on different nodes and the ticket is simply unknown.
 *
 * <p>Single use is enforced by {@link IMap#remove(Object)}, which is atomic across the cluster — two
 * concurrent redemptions of the same ticket cannot both succeed.
 */
public class HazelcastWebDavTicketStore implements WebDavTicketStore {

    private static final Logger log = LoggerFactory.getLogger(HazelcastWebDavTicketStore.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final IMap<String, String> map;
    private final long ttlSeconds;
    private final int maxOutstanding;

    public HazelcastWebDavTicketStore(IMap<String, String> map, long ttlSeconds, int maxOutstanding) {
        this.map = map;
        this.ttlSeconds = ttlSeconds;
        this.maxOutstanding = maxOutstanding;
    }

    @Override
    public String issue(String accessToken) {
        if (map.size() >= maxOutstanding) {
            log.warn("WebDAV ticket store is full ({} across the cluster); refusing to issue", map.size());
            return null;
        }
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        map.put(ticket, accessToken, ttlSeconds, TimeUnit.SECONDS);
        return ticket;
    }

    @Override
    public String redeem(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return null;
        }
        // Atomic cluster-wide: the entry is removed and returned in one operation, so a ticket cannot
        // be redeemed twice even by two nodes at once.
        return map.remove(ticket);
    }

    @Override
    public int size() {
        return map.size();
    }
}
