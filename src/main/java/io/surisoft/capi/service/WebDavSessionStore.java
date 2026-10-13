package io.surisoft.capi.service;

/**
 * Server-side WebDAV sessions: an opaque id the client carries, resolving to the access token it was
 * created from.
 *
 * <p>Two implementations, chosen at startup. {@link LocalWebDavSessionStore} is per-process, which is
 * fine on a single node; behind a load balancer a session created on one node is unknown to the
 * others, so a request that lands elsewhere is refused. {@link HazelcastWebDavSessionStore} shares
 * them across the cluster.
 */
public interface WebDavSessionStore {

    /** @return an opaque session id, or null when the store is full */
    String create(String accessToken);

    /** @return the access token, or null when the id is unknown or expired. Does not consume it. */
    String resolve(String id);

    int size();
}
