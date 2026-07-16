package io.surisoft.capi.undertow;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.proxy.ProxyCallback;
import io.undertow.server.handlers.proxy.ProxyConnection;
import io.undertow.server.handlers.proxy.LoadBalancingProxyClient;
import io.undertow.util.AttachmentKey;
import io.undertow.util.HttpString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.OptionMap;
import org.xnio.ssl.XnioSsl;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;


public class CAPILoadBalancerProxyClient extends LoadBalancingProxyClient {

    private static final Logger log = LoggerFactory.getLogger(CAPILoadBalancerProxyClient.class);

    public static final AttachmentKey<String> SELECTED_HOST_KEY = AttachmentKey.create(String.class);
    public static final AttachmentKey<String> SELECTED_SCHEME_KEY = AttachmentKey.create(String.class);
    public static final AttachmentKey<Throwable> CONNECTION_ERROR_KEY = AttachmentKey.create(Throwable.class);

    /** Extra slack added to a route's maxRequestTime before an orphaned pool is drained, so the
     *  in-flight watchdog ({@link CAPIProxyHandler} maxRequestTime) has certainly fired first and
     *  no connection is still leased at drain time. Package-private + volatile only so tests can
     *  shorten it; never reassigned in production. */
    static volatile long drainGraceBufferMs = 15_000L;

    /** Single daemon timer that runs the deferred idle-pool drains. One thread is enough: a drain
     *  is just a non-blocking sweep of already-idle keep-alive sockets. */
    private static final ScheduledExecutorService DRAIN_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "capi-proxy-drain");
                t.setDaemon(true);
                return t;
            });

    /** Guards against scheduling the drain more than once for the same orphaned client. */
    private final AtomicBoolean drainScheduled = new AtomicBoolean(false);

    /** URIs of every host added to this client, so {@link #drain} can mark each host's connection
     *  pool closed (via {@link #removeHost}) — the only public lever that flips the pool's internal
     *  {@code closed} flag. Recorded by overriding the two terminal {@code addHost} builders that all
     *  other overloads delegate to. */
    private final List<URI> trackedHosts = new CopyOnWriteArrayList<>();

    /** Test-only visibility into the idempotency guard. */
    boolean isDrainScheduled() {
        return drainScheduled.get();
    }

    // Terminal addHost builders (every other addHost overload delegates to one of these via virtual
    // dispatch), overridden only to record the host URI for drain-time pool closing.
    @Override
    public synchronized LoadBalancingProxyClient addHost(URI host, String jvmRoute, XnioSsl ssl) {
        trackedHosts.add(host);
        return super.addHost(host, jvmRoute, ssl);
    }

    @Override
    public synchronized LoadBalancingProxyClient addHost(InetSocketAddress bindAddress, URI host, String jvmRoute, XnioSsl ssl, OptionMap options) {
        trackedHosts.add(host);
        return super.addHost(bindAddress, host, jvmRoute, ssl, options);
    }

    /**
     * Reclaim this (already-replaced) pool's backend keep-alive sockets after a grace window.
     *
     * <p>Background: Undertow never evicts a pool below {@code softMaxConnectionsPerThread} and only
     * re-arms its TTL timer from {@code returnConnection}; once this client is dropped from the route
     * map it handles no further requests, so its idle sockets would otherwise stay open until the
     * backend closes them — i.e. leak. We therefore drain them explicitly.
     *
     * <p>Two steps, in order:
     * <ol>
     *   <li>{@code closeCurrentConnections()} sweeps every <em>idle</em> connection out of each host
     *       pool. Leased (in-use) connections are not in {@code availableConnections}, so this can
     *       never abort an in-flight request or a live WebSocket/streaming tunnel.</li>
     *   <li>{@code removeHost(uri)} on each tracked host flips that pool's {@code closed} flag. This
     *       matters for long-lived transports (WebSocket/SSE, streaming gRPC): such a connection can
     *       still be leased at drain time and would, on close, <em>return and re-pool</em> into the
     *       orphaned pool (Undertow zeroes the count in step 1) — leaking again. With the pool marked
     *       closed, {@code returnConnection} closes it instead of re-pooling. REST requests are all
     *       finished by drain time so this is a no-op for them; it is what makes the drain correct
     *       for WS/gRPC.</li>
     * </ol>
     *
     * <p>We defer by {@code maxRequestTimeMs + grace} so that for bounded (REST) requests every
     * exchange has completed or been cancelled by the maxRequestTime watchdog before we sweep.
     */
    public void drain(long maxRequestTimeMs) {
        if (!drainScheduled.compareAndSet(false, true)) {
            return;
        }
        long delayMs = Math.max(0L, maxRequestTimeMs) + drainGraceBufferMs;
        DRAIN_SCHEDULER.schedule(() -> {
            try {
                closeCurrentConnections();                 // step 1: sweep idle keep-alives
                for (URI host : trackedHosts) {
                    removeHost(host);                      // step 2: mark pool closed -> no re-pool
                }
                log.debug("Drained orphaned proxy-client pool ({} host(s))", trackedHosts.size());
            } catch (Exception e) {
                log.warn("Failed to drain orphaned proxy-client pool: {}", e.getMessage());
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Convenience for the route-removal sites: if {@code handler} fronts a {@link CAPILoadBalancerProxyClient},
     * schedule its drain. No-op for any other handler/proxy-client type.
     */
    public static void drainHandler(HttpHandler handler, long maxRequestTimeMs) {
        if (handler instanceof CAPIProxyHandler proxyHandler
                && proxyHandler.getProxyClient() instanceof CAPILoadBalancerProxyClient client) {
            client.drain(maxRequestTimeMs);
        }
    }

    public Host selectHost(HttpServerExchange exchange) {
        Host host = super.selectHost(exchange);
        if(host != null) {
            String hostName = host.getUri().getHost();
            exchange.putAttachment(SELECTED_HOST_KEY, hostName);
            exchange.putAttachment(SELECTED_SCHEME_KEY, host.getUri().getScheme());
            exchange.getRequestHeaders().put(HttpString.tryFromString("CapiSelectedHost"), hostName);
            return host;
        }
        //no available hosts
        return null;
    }

    public static String getSelectedHost(HttpServerExchange exchange) {
        return exchange.getAttachment(SELECTED_HOST_KEY);
    }

    @Override
    public void getConnection(ProxyTarget target, HttpServerExchange exchange, ProxyCallback<ProxyConnection> callback, long timeout, TimeUnit timeUnit) {
        try {
            super.getConnection(target, exchange, callback, timeout, timeUnit);
        } catch (Exception e) {
            // Capture the actual exception for error reporting
            exchange.putAttachment(CONNECTION_ERROR_KEY, e);
            callback.failed(exchange);
        }
    }
}
