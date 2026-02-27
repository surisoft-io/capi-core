package io.surisoft.capi.service;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
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
 * <p>
 * For multipart requests, this handler hides the Content-Type header before the downstream
 * {@code EagerFormParsingHandler} runs. This prevents Undertow from eagerly parsing the
 * multipart body into a HashMap, which would destroy the original raw bytes needed for
 * transparent proxying. The original Content-Type is stored in a temporary header and
 * restored by the Camel route.
 */
public class CamelProxyPeerAddressHandler implements CamelUndertowHttpHandler {

    /**
     * Temporary header used to pass the original Content-Type through when it is
     * hidden from {@code EagerFormParsingHandler}.
     */
    public static final String ORIGINAL_CONTENT_TYPE_HEADER = "X-CAPI-Original-Content-Type";

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

        // Hide Content-Type from EagerFormParsingHandler for form/multipart requests.
        // CAPI is a transparent proxy and never needs to read the body. Without this,
        // Undertow eagerly parses form-urlencoded and multipart bodies into a HashMap,
        // consuming the raw stream and breaking transparent proxying.
        String contentType = exchange.getRequestHeaders().getFirst(Headers.CONTENT_TYPE);
        String ctLower = contentType != null ? contentType.toLowerCase() : "";
        boolean hideContentType = ctLower.contains("multipart/form-data")
                || ctLower.contains("application/x-www-form-urlencoded");
        if (hideContentType) {
            exchange.getRequestHeaders().remove(Headers.CONTENT_TYPE);
            exchange.getRequestHeaders().put(
                    HttpString.tryFromString(ORIGINAL_CONTENT_TYPE_HEADER), contentType);
        }

        next.handleRequest(exchange);
    }
}
