package io.surisoft.capi.service;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.apache.camel.component.undertow.CamelUndertowHttpHandler;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * A Camel Undertow handler that resolves the real client IP from reverse proxy headers.
 * <p>
 * When CAPI is behind a reverse proxy (e.g., Nginx, HAProxy, Traefik), the TCP source address
 * is the proxy's internal IP. This handler reads X-Forwarded-For (and X-Forwarded-Proto,
 * X-Forwarded-Host) and updates the Undertow exchange's source address so that the
 * AccessLogHandler logs the real client IP instead of the proxy IP.
 */
public class CamelProxyPeerAddressHandler implements CamelUndertowHttpHandler {

    private HttpHandler next;

    @Override
    public void setNext(HttpHandler nextHandler) {
        this.next = nextHandler;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        String forwardedFor = exchange.getRequestHeaders().getFirst(Headers.X_FORWARDED_FOR);
        if (forwardedFor != null) {
            String remoteClient = forwardedFor.split(",")[0].trim();
            if (!remoteClient.isEmpty()) {
                try {
                    InetAddress address = InetAddress.getByName(remoteClient);
                    exchange.setSourceAddress(new InetSocketAddress(address, exchange.getSourceAddress().getPort()));
                } catch (Exception e) {
                    // If parsing fails, keep the original source address
                }
            }
        }

        String forwardedProto = exchange.getRequestHeaders().getFirst(Headers.X_FORWARDED_PROTO);
        if (forwardedProto != null) {
            exchange.setRequestScheme(forwardedProto.trim());
        }

        String forwardedHost = exchange.getRequestHeaders().getFirst(Headers.X_FORWARDED_HOST);
        if (forwardedHost != null) {
            String host = forwardedHost.split(",")[0].trim();
            if (!host.isEmpty()) {
                exchange.getRequestHeaders().put(Headers.HOST, host);
            }
        }

        next.handleRequest(exchange);
    }
}
