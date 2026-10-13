package io.surisoft.capi.service;

/**
 * Short single-use references to an access token, so a credential can travel in a URL a protocol
 * handler will accept.
 *
 * <p>Two implementations, chosen at startup — see {@link WebDavSessionStore} for why the distributed
 * one matters behind a load balancer. For tickets it matters more sharply: minting and redeeming are
 * done by two different clients, so without a shared store they routinely land on different nodes.
 */
public interface WebDavTicketStore {

    /** @return a new single-use ticket, or null when the store is full */
    String issue(String accessToken);

    /** Resolves and consumes a ticket. @return the access token, or null if unknown/used/expired. */
    String redeem(String ticket);

    int size();
}
