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
    /** Full URI of the host chosen for this exchange, so a failed attempt can name it precisely.
     *  {@link #SELECTED_HOST_KEY} holds only the hostname, which is ambiguous when two mappings
     *  share a host on different ports. */
    public static final AttachmentKey<URI> SELECTED_HOST_URI_KEY = AttachmentKey.create(URI.class);

    /** Hosts whose connect timed out, and the time they become eligible again. */
    private final java.util.Map<URI, Long> connectPenaltyUntil = new java.util.concurrent.ConcurrentHashMap<>();

    /** How long a host sits out after a connect timeout. Mirrors the problemServerRetry window
     *  Undertow applies to hosts that fail a connect outright. */
    private volatile long connectPenaltyMs = 10_000L;

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

    /**
     * Backend connection-pool sizing, shared by every transport (REST/WS via {@code WebsocketUtils},
     * gRPC via {@code GrpcUtils}) so one network path gets one policy.
     *
     * @param connectionsPerThread max pooled connections per IO thread per host
     * @param maxQueueSize         requests allowed to queue when the pool is saturated
     * @param idleTimeoutMs        how long an unused connection may sit in the pool before it is closed
     */
    public record PoolSettings(int connectionsPerThread, int maxQueueSize, int idleTimeoutMs,
                               int connectTimeoutMs) {
        /**
         * Default bound on acquiring a backend connection. Generous next to a healthy connect
         * (sub-millisecond on a LAN, tens of ms across regions, plus any TLS handshake), but far
         * below {@code responseTimeout} so an unreachable host fails over instead of burning the
         * whole request budget. See {@code CAPIProxyHandler.ConnectAttempt}.
         */
        public static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;

        public static final PoolSettings DEFAULTS =
                new PoolSettings(200, 500, 30_000, DEFAULT_CONNECT_TIMEOUT_MS);

        /** Overload for call sites that only tune pooling and want the default connect bound. */
        public PoolSettings(int connectionsPerThread, int maxQueueSize, int idleTimeoutMs) {
            this(connectionsPerThread, maxQueueSize, idleTimeoutMs, DEFAULT_CONNECT_TIMEOUT_MS);
        }
    }

    /**
     * Apply pool sizing, and — critically — make the idle timeout real.
     *
     * <p><b>Why {@code softMaxConnectionsPerThread} is pinned to 0.</b> Undertow's
     * {@code ProxyConnectionPool.timeoutConnections} only reaps while
     * {@code availableConnections.size() > coreCachedConnections}, and {@code coreCachedConnections}
     * <em>is</em> the soft max. Any non-zero value therefore exempts that many sockets per thread
     * per host from the TTL entirely — they are cached until the backend closes them. We previously
     * set it to 100 alongside a 30s TTL, which meant the TTL never fired in practice.
     *
     * <p>That matters because a pooled socket can die <em>silently</em>: a stateful middlebox
     * (corporate proxy, NAT, LB) expiring an idle flow sends neither FIN nor RST, so the socket
     * still reports {@code isOpen()} and gets leased out again. The request is written into a dead
     * socket, nothing comes back, and the exchange stalls until the {@code maxRequestTime} watchdog
     * returns 504 — with no retry and no failover. Undertow validates nothing before leasing, so
     * bounding how long a socket may sit idle is the only defence available here.
     * See {@code ProxyStaleConnectionReuseTest}, which reproduces the 504 and pins this behaviour.
     *
     * <p>The TTL is an <em>idle</em> timeout, not a hard lifetime: {@code returnConnection} refreshes
     * it on every return, so a busy pool never churns — only genuinely idle sockets are closed.
     * Keep {@code idleTimeoutMs} below the shortest idle timeout on the path to the backend.
     */
    public void applyPoolSettings(PoolSettings settings) {
        setConnectionsPerThread(settings.connectionsPerThread());
        setSoftMaxConnectionsPerThread(0);
        setMaxQueueSize(settings.maxQueueSize());
        setTtl(settings.idleTimeoutMs());
        setProblemServerRetry(10);
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
        Host host = selectHostSkippingPenalised(exchange);
        if(host != null) {
            String hostName = host.getUri().getHost();
            exchange.putAttachment(SELECTED_HOST_KEY, hostName);
            exchange.putAttachment(SELECTED_SCHEME_KEY, host.getUri().getScheme());
            exchange.putAttachment(SELECTED_HOST_URI_KEY, host.getUri());
            exchange.getRequestHeaders().put(HttpString.tryFromString("CapiSelectedHost"), hostName);
            return host;
        }
        //no available hosts
        return null;
    }

    /**
     * Round-robin, but stepping over hosts that recently timed out on connect.
     *
     * <p>Undertow takes a host out of rotation when a connect <em>fails</em>
     * ({@code ConnectionPoolManager.handleError}), which is why a refused port recovers cleanly. A
     * host that never answers produces no failure, so without this it stays in rotation and every
     * request pays the connect timeout before failing over — the retry also consumes a round-robin
     * slot, so with two hosts each request lands on the dead one first and the tax is permanent.
     *
     * <p>Undertow's own flag is not reusable here: {@code handleError()} sets it forever and only a
     * private retry task ever clears it, so a recovered node would never return. This penalty
     * expires on its own instead.
     *
     * <p>Fails open by design: if every host is penalised we still return one, because refusing to
     * route is worse than trying a host that may have recovered.
     */
    private Host selectHostSkippingPenalised(HttpServerExchange exchange) {
        int candidates = Math.max(1, trackedHosts.size());
        Host firstChoice = null;
        for (int i = 0; i < candidates; i++) {
            Host host = super.selectHost(exchange);
            if (host == null) {
                return null;
            }
            if (firstChoice == null) {
                firstChoice = host;
            }
            if (!isPenalised(host.getUri())) {
                return host;
            }
        }
        return firstChoice;
    }

    private boolean isPenalised(URI hostUri) {
        Long until = connectPenaltyUntil.get(hostUri);
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() >= until) {
            connectPenaltyUntil.remove(hostUri, until);
            return false;
        }
        return true;
    }

    /** Take a host out of rotation for {@link #connectPenaltyMs} after it failed to answer a connect. */
    public void penaliseHost(URI hostUri) {
        if (hostUri == null || connectPenaltyMs <= 0) {
            return;
        }
        connectPenaltyUntil.put(hostUri, System.currentTimeMillis() + connectPenaltyMs);
        log.warn("Backend {} did not answer a connection attempt; skipping it for {} ms", hostUri, connectPenaltyMs);
    }

    /** Test seam: shorten or disable ({@code 0}) the penalty window. */
    void setConnectPenaltyMs(long connectPenaltyMs) {
        this.connectPenaltyMs = connectPenaltyMs;
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
