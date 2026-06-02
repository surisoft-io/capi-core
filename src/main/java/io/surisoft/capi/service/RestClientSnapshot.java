package io.surisoft.capi.service;

import io.surisoft.capi.schema.RestClient;

import java.util.Map;

/**
 * Immutable, atomically-published view of the active REST routing map.
 *
 * Writers (the Consul cycle, the consistency checker) keep mutating the live
 * ConcurrentHashMap of RestClients as they always have. At well-defined moments
 * (end of cycle; after a consistency-checker pass) they call publish() with the
 * current live map. publish() takes a Map.copyOf snapshot and stores it in the
 * volatile field below. Readers (RestGateway on every request) read current()
 * and always observe a fully-built, immutable view — never a mid-mutation state.
 */
public class RestClientSnapshot {

    private volatile Map<String, RestClient> current = Map.of();

    public Map<String, RestClient> current() {
        return current;
    }

    public void publish(Map<String, RestClient> next) {
        current = (next == null) ? Map.of() : Map.copyOf(next);
    }
}