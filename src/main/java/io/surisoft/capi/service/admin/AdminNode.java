package io.surisoft.capi.service.admin;

/**
 * One CAPI node's admin API, as registered in Consul under {@code capi-admin}.
 *
 * @param id      the Consul service ID — stable per node, and the label every tool answer uses
 * @param address address peers reach the admin port on
 * @param port    admin port
 * @param scheme  {@code http} or {@code https}
 * @param self    true for the node answering the MCP call
 */
public record AdminNode(String id, String address, int port, String scheme, boolean self) {

    public String baseUrl() {
        String host = address.contains(":") && !address.startsWith("[") ? "[" + address + "]" : address;
        return scheme + "://" + host + ":" + port;
    }
}
