package io.surisoft.capi.undertow;

import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.exception.HttpErrorHandler;
import io.surisoft.capi.schema.Mapping;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import io.surisoft.capi.schema.WebsocketClient;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.HttpUtils;
import io.surisoft.capi.utils.WebsocketUtils;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.util.Headers;
import io.undertow.util.SameThreadExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces the intermittent 504 seen behind a corporate reverse proxy.
 *
 * <p>The gateway pools keep-alive sockets to the backend. When something on the path (a
 * stateful appliance, NAT, or LB) silently evicts an idle flow — no FIN, no RST — the pooled
 * socket still reports {@code isOpen()}, so Undertow leases it out again. The request is
 * written into a dead socket and nothing ever comes back, so the exchange stalls until the
 * {@code maxRequestTime} watchdog fires and returns 504.
 *
 * <p>{@link BlackholeRelay} stands in for that appliance. Critically, going silent affects
 * only connections that already exist: a <em>new</em> connection is relayed normally. So if
 * the second request still times out, the gateway can only have reused the dead socket —
 * which {@link #proxyReusesSilentlyDroppedPooledConnection()} asserts directly via the
 * relay's accept count.
 */
class ProxyStaleConnectionReuseTest {

    /** Short so the test is quick; production default is rest.responseTimeout = 120000. */
    private static final int MAX_REQUEST_TIME_MS = 4000;

    private Undertow backend;
    private Undertow gateway;
    private BlackholeRelay relay;
    private HttpClient client;
    private String gatewayUrl;

    @BeforeEach
    void setUp() throws Exception {
        backend = Undertow.builder()
                .addHttpListener(0, "127.0.0.1")
                .setHandler(exchange -> {
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
                    exchange.getResponseSender().send("backend-ok");
                })
                .build();
        backend.start();

        relay = new BlackholeRelay("127.0.0.1", portOf(backend));
        startGateway(relay.port(), CAPILoadBalancerProxyClient.PoolSettings.DEFAULTS);
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    /** (Re)starts the gateway in front of {@code targetPort}. */
    private void startGateway(int targetPort, CAPILoadBalancerProxyClient.PoolSettings poolSettings) {
        if (gateway != null) {
            gateway.stop();
        }
        gateway = Undertow.builder()
                .addHttpListener(0, "127.0.0.1")
                // One IO thread: the proxy connection pool is per-IO-thread, so this guarantees
                // both requests hit the same pool. With the production ioThreads=4 the mechanism
                // is identical, it just takes more requests to land on the poisoned pool.
                .setIoThreads(1)
                .setHandler(buildProductionProxyHandler(targetPort, poolSettings))
                .build();
        gateway.start();
        gatewayUrl = "http://127.0.0.1:" + portOf(gateway) + "/";
    }

    @AfterEach
    void tearDown() {
        if (relay != null) relay.close();
        if (gateway != null) gateway.stop();
        if (backend != null) backend.stop();
    }

    /**
     * Builds the handler through the production factory rather than hand-rolling one, so the
     * pool settings under test are the real ones (WebsocketUtils#createClientHttpHandler).
     */
    private HttpHandler buildProductionProxyHandler(int backendPort) {
        return buildProductionProxyHandler(backendPort, CAPILoadBalancerProxyClient.PoolSettings.DEFAULTS);
    }

    private HttpHandler buildProductionProxyHandler(int backendPort, CAPILoadBalancerProxyClient.PoolSettings poolSettings) {
        Mapping mapping = new Mapping();
        mapping.setHostname("127.0.0.1");
        mapping.setPort(backendPort);
        mapping.setRootContext("/");

        WebsocketClient websocketClient = new WebsocketClient();
        websocketClient.setServiceId("/repro/dev");
        websocketClient.setRootContext("/");
        websocketClient.setMappingList(Set.of(mapping));

        ServiceMeta serviceMeta = new ServiceMeta();
        serviceMeta.setScheme("http");
        Service service = new Service();
        service.setServiceMeta(serviceMeta);

        WebsocketUtils websocketUtils =
                new WebsocketUtils(new CAPIConfiguration.Websocket(), List.of(), null, poolSettings);
        HttpErrorHandler errorHandler = new HttpErrorHandler(new HttpUtils(null, null));
        return websocketUtils.createClientHttpHandler(websocketClient, service, errorHandler, MAX_REQUEST_TIME_MS);
    }

    /**
     * The fix: with {@code softMaxConnectionsPerThread} pinned to 0 the idle timeout is real, so a
     * socket that has been sitting unused past it is closed and can never be leased out again.
     * Same silent drop as {@link #proxyReusesSilentlyDroppedPooledConnection()}, but the gateway now
     * waits out the idle window first — and serves the request from a fresh connection instead of
     * stalling on the dead one.
     */
    @Test
    void idleTimeoutEvictsTheConnectionBeforeItCanBeReusedDead() throws Exception {
        int idleTimeoutMs = 700;
        startGateway(relay.port(), new CAPILoadBalancerProxyClient.PoolSettings(200, 500, idleTimeoutMs));

        assertEquals(200, get().statusCode());
        assertEquals(1, relay.acceptedConnections());
        relay.silenceEstablishedConnections();

        Thread.sleep(idleTimeoutMs * 3L);

        long startedAt = System.nanoTime();
        HttpResponse<String> afterIdleWindow = get();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertEquals(200, afterIdleWindow.statusCode(),
                "the reaped connection should have been replaced, not reused");
        assertEquals("backend-ok", afterIdleWindow.body());
        assertEquals(2, relay.acceptedConnections(), "a fresh connection should have been opened");
        assertTrue(elapsedMs < MAX_REQUEST_TIME_MS,
                "should not have gone near the watchdog, but took " + elapsedMs + "ms");
    }

    /**
     * The original defect, and the residual exposure the idle-reap fix leaves behind: a socket
     * dropped <em>within</em> the idle window is still leased out once and still costs one 504.
     * Reaping bounds the window, it cannot close it — Undertow validates nothing before leasing.
     * Keeping {@code rest.connectionIdleTimeout} below the shortest idle timeout on the path is
     * what makes the window unreachable in practice.
     */
    @Test
    void proxyReusesSilentlyDroppedPooledConnection() throws Exception {
        HttpResponse<String> warmUp = get();
        assertEquals(200, warmUp.statusCode(), "warm-up request should reach the backend");
        assertEquals("backend-ok", warmUp.body());
        assertEquals(1, relay.acceptedConnections(), "warm-up should have opened exactly one backend connection");

        // The appliance silently drops the established flow. No FIN, no RST — the socket stays
        // open as far as the gateway's kernel is concerned. New connections are unaffected.
        relay.silenceEstablishedConnections();

        long startedAt = System.nanoTime();
        HttpResponse<String> afterDrop = get();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertEquals(504, afterDrop.statusCode(),
                "expected the maxRequestTime watchdog to fire; got " + afterDrop.statusCode()
                        + " after " + elapsedMs + "ms");
        assertTrue(afterDrop.body().contains(Constants.ERROR_REMOTE_SERVER_TIMEOUT),
                "expected CAPI's own timeout message, got: " + afterDrop.body());
        assertTrue(elapsedMs >= MAX_REQUEST_TIME_MS * 0.9,
                "should have stalled for the full watchdog window, but returned after " + elapsedMs + "ms");

        // The point of the test: the gateway never opened a fresh socket. A new connection would
        // have been relayed normally and returned 200, so the 504 proves the dead one was reused.
        assertEquals(1, relay.acceptedConnections(),
                "gateway reused the dead pooled connection instead of opening a new one");
    }

    /**
     * Blast radius: the watchdog's {@code cancel()} closes the dead socket, which evicts it from
     * the pool, so the next request opens a fresh connection and succeeds. Each silently-dropped
     * socket therefore costs exactly one 504 and then self-heals — which is why this shows up as
     * occasional timeouts rather than a route that stays broken.
     */
    @Test
    void poisonedConnectionCostsOneTimeoutThenRecovers() throws Exception {
        assertEquals(200, get().statusCode());
        relay.silenceEstablishedConnections();
        assertEquals(504, get().statusCode(), "the request that inherits the dead socket times out");

        HttpResponse<String> recovered = get();

        assertEquals(200, recovered.statusCode(), "the following request should open a fresh connection");
        assertEquals("backend-ok", recovered.body());
        assertEquals(2, relay.acceptedConnections(), "exactly one replacement connection was opened");
    }

    /**
     * No regression on the ordinary slow-backend case: a backend that accepts the request and
     * never answers must still produce the same 504 at {@code maxRequestTime}. The idle timeout
     * here is deliberately far shorter than the watchdog (700ms vs 4s), which also pins the other
     * half of the guarantee — the reaper only walks <em>idle</em> connections, so it can never
     * close one out from under an in-flight request and turn a 504 into an early 503.
     */
    @Test
    void unresponsiveBackendStillTimesOutAt504WithAggressiveIdleReaping() throws Exception {
        Undertow neverResponds = Undertow.builder()
                .addHttpListener(0, "127.0.0.1")
                .setHandler(exchange -> exchange.dispatch(SameThreadExecutor.INSTANCE, () -> {
                    // accept the request, then never complete the exchange
                }))
                .build();
        neverResponds.start();

        try (BlackholeRelay slowPath = new BlackholeRelay("127.0.0.1", portOf(neverResponds))) {
            startGateway(slowPath.port(), new CAPILoadBalancerProxyClient.PoolSettings(200, 500, 700));

            long startedAt = System.nanoTime();
            HttpResponse<String> response = get();
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

            assertEquals(504, response.statusCode(), "a hanging backend must still yield 504");
            assertTrue(response.body().contains(Constants.ERROR_REMOTE_SERVER_TIMEOUT),
                    "expected CAPI's own timeout message, got: " + response.body());
            assertTrue(elapsedMs >= MAX_REQUEST_TIME_MS * 0.9,
                    "the watchdog, not the reaper, must end the request; ended after " + elapsedMs + "ms");
        } finally {
            neverResponds.stop();
        }
    }

    /**
     * Control: without the silent drop the gateway reuses the pooled socket happily. Proves the
     * 504 above comes from the dropped flow, not from pooling or from the harness itself.
     */
    @Test
    void pooledConnectionIsReusedSuccessfullyWhenFlowSurvives() throws Exception {
        assertEquals(200, get().statusCode());
        HttpResponse<String> second = get();

        assertEquals(200, second.statusCode());
        assertEquals("backend-ok", second.body());
        assertEquals(1, relay.acceptedConnections(), "second request should have reused the pooled connection");
    }

    /**
     * Control: this is the "skip the gateway and it always works" observation. After the same
     * silent drop, a caller that opens a fresh connection to the relay is served normally — the
     * path is fine, only the gateway's already-pooled socket is dead.
     */
    @Test
    void freshConnectionThroughTheSameRelayStillWorksAfterTheDrop() throws Exception {
        assertEquals(200, get().statusCode());
        relay.silenceEstablishedConnections();

        HttpRequest direct = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + relay.port() + "/"))
                .timeout(Duration.ofMillis(MAX_REQUEST_TIME_MS * 3L))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build()
                .send(direct, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "a brand-new connection must still reach the backend");
        assertEquals("backend-ok", response.body());
        assertEquals(2, relay.acceptedConnections());
    }

    private HttpResponse<String> get() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(gatewayUrl))
                .timeout(Duration.ofMillis(MAX_REQUEST_TIME_MS * 3L))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static int portOf(Undertow server) {
        return ((InetSocketAddress) server.getListenerInfo().get(0).getAddress()).getPort();
    }

    /**
     * A TCP relay that can be told to go silent on the connections it already holds, mimicking a
     * stateful middlebox evicting an idle flow: bytes are swallowed in both directions and neither
     * socket is closed, so the peer never learns the path is dead. Connections accepted after
     * {@link #silenceEstablishedConnections()} are relayed normally.
     */
    private static final class BlackholeRelay implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final String backendHost;
        private final int backendPort;
        private final List<Session> sessions = new CopyOnWriteArrayList<>();
        private final AtomicInteger accepted = new AtomicInteger();
        private volatile boolean running = true;

        BlackholeRelay(String backendHost, int backendPort) throws IOException {
            this.backendHost = backendHost;
            this.backendPort = backendPort;
            this.serverSocket = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
            Thread acceptor = new Thread(this::acceptLoop, "blackhole-relay-accept");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        int acceptedConnections() {
            return accepted.get();
        }

        void silenceEstablishedConnections() {
            sessions.forEach(session -> session.silent = true);
        }

        private void acceptLoop() {
            while (running) {
                try {
                    Socket inbound = serverSocket.accept();
                    accepted.incrementAndGet();
                    Socket outbound = new Socket(backendHost, backendPort);
                    Session session = new Session(inbound, outbound);
                    sessions.add(session);
                    pump(session, inbound, outbound, "c2b");
                    pump(session, outbound, inbound, "b2c");
                } catch (IOException e) {
                    if (running) {
                        throw new IllegalStateException("relay accept failed", e);
                    }
                }
            }
        }

        private void pump(Session session, Socket from, Socket to, String direction) {
            Thread thread = new Thread(() -> {
                byte[] buffer = new byte[8192];
                try (InputStream in = from.getInputStream()) {
                    OutputStream out = to.getOutputStream();
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        if (session.silent) {
                            continue;   // swallow the bytes; the sender sees a successful write
                        }
                        out.write(buffer, 0, read);
                        out.flush();
                    }
                } catch (IOException ignored) {
                    // peer went away — nothing to do, close() tears the sockets down
                } finally {
                    if (!session.silent) {
                        session.close();
                    }
                }
            }, "blackhole-relay-" + direction);
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void close() {
            running = false;
            sessions.forEach(session -> {
                session.silent = false;
                session.close();
            });
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // shutting down
            }
        }

        private static final class Session {
            private final Socket inbound;
            private final Socket outbound;
            private volatile boolean silent;

            Session(Socket inbound, Socket outbound) {
                this.inbound = inbound;
                this.outbound = outbound;
            }

            void close() {
                closeQuietly(inbound);
                closeQuietly(outbound);
            }

            private static void closeQuietly(Socket socket) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }
}
