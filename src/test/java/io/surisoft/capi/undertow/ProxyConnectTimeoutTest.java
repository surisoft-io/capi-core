package io.surisoft.capi.undertow;

import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.exception.HttpErrorHandler;
import io.surisoft.capi.schema.Mapping;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import io.surisoft.capi.schema.WebsocketClient;
import io.surisoft.capi.utils.HttpUtils;
import io.surisoft.capi.utils.WebsocketUtils;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.util.Headers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A backend host that never answers must be failed over, not waited on.
 *
 * <p>Two registrations, one healthy and one that accepts nothing. Undertow bounds neither the TCP
 * connect nor the TLS handshake — {@code ProxyConnectionPool.connect} applies its timeout only to
 * requests it queues when the pool is full — so a host that refuses the port (RST) fails over fine,
 * while a host that is merely unreachable produces silence: the exchange stalls until the
 * maxRequestTime watchdog returns 504, with a healthy sibling sitting idle. That is the customer
 * report this covers, and it is exactly the difference between killing a process and losing a node.
 *
 * <p>{@link SilentHost} models the unreachable case with a socket whose accept backlog is never
 * drained, so connects hang rather than being refused.
 */
class ProxyConnectTimeoutTest {

    private static final int CONNECT_TIMEOUT_MS = 400;
    private static final int MAX_REQUEST_TIME_MS = 10_000;

    private Undertow backend;
    private Undertow gateway;
    private SilentHost silent;
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
        silent = new SilentHost();
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    @AfterEach
    void tearDown() {
        if (gateway != null) gateway.stop();
        if (backend != null) backend.stop();
        if (silent != null) silent.close();
    }

    @Test
    void unreachableHostIsFailedOverInsteadOfStallingUntilTheRequestDeadline() throws Exception {
        startGateway(CONNECT_TIMEOUT_MS);

        // Enough requests that round-robin lands on the silent host repeatedly.
        for (int i = 0; i < 10; i++) {
            long startedAt = System.nanoTime();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder().uri(URI.create(gatewayUrl)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

            assertEquals(200, response.statusCode(),
                    "request " + i + " should have failed over to the healthy host");
            assertEquals("backend-ok", response.body());
            assertTrue(elapsedMs < MAX_REQUEST_TIME_MS,
                    "request " + i + " took " + elapsedMs + "ms — it waited on the dead host "
                            + "instead of failing over");
        }
    }

    @Test
    void deadHostIsTakenOutOfRotationSoOnlyTheFirstRequestPaysTheTimeout() throws Exception {
        startGateway(CONNECT_TIMEOUT_MS);

        List<Long> timings = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            long startedAt = System.nanoTime();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder().uri(URI.create(gatewayUrl)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            timings.add((System.nanoTime() - startedAt) / 1_000_000);
            assertEquals(200, response.statusCode());
        }

        // The retry consumes a round-robin slot too, so with the dead host left in rotation every
        // request would land on it first and pay the timeout. Penalising it must stop that.
        long slow = timings.stream().filter(ms -> ms >= CONNECT_TIMEOUT_MS).count();
        assertTrue(slow <= 2,
                "expected at most the first couple of requests to pay the connect timeout, but "
                        + slow + " of " + timings.size() + " did: " + timings);
    }

    @Test
    void watchdogDisabledRestoresTheOldStallingBehaviour() throws Exception {
        // 0 = opt out. Proves the guard is what changes the outcome, and that the previous
        // behaviour is still reachable for anyone who needs it.
        startGateway(0);

        int stalled = 0;
        for (int i = 0; i < 6; i++) {
            try {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder().uri(URI.create(gatewayUrl))
                                .timeout(java.time.Duration.ofMillis(1500)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
            } catch (Exception timeoutOnTheDeadHost) {
                stalled++;
            }
        }
        assertTrue(stalled > 0,
                "without the watchdog some requests must stall on the unreachable host");
    }

    private void startGateway(int connectTimeoutMs) {
        gateway = Undertow.builder()
                .addHttpListener(0, "127.0.0.1")
                // One IO thread so both hosts share a single pool and round-robin is deterministic.
                .setIoThreads(1)
                .setHandler(buildProductionProxyHandler(connectTimeoutMs))
                .build();
        gateway.start();
        gatewayUrl = "http://127.0.0.1:" + portOf(gateway) + "/";
    }

    /** Built through the production factory rather than hand-rolled. */
    private HttpHandler buildProductionProxyHandler(int connectTimeoutMs) {
        Set<Mapping> mappings = new LinkedHashSet<>();
        mappings.add(mapping(portOf(backend)));
        mappings.add(mapping(silent.port()));

        WebsocketClient websocketClient = new WebsocketClient();
        websocketClient.setServiceId("/connect-timeout/dev");
        websocketClient.setRootContext("/");
        websocketClient.setMappingList(mappings);

        ServiceMeta serviceMeta = new ServiceMeta();
        serviceMeta.setScheme("http");
        Service service = new Service();
        service.setServiceMeta(serviceMeta);

        WebsocketUtils websocketUtils = new WebsocketUtils(
                new CAPIConfiguration.Websocket(), List.of(), null,
                new CAPILoadBalancerProxyClient.PoolSettings(200, 500, 30_000, connectTimeoutMs));
        HttpErrorHandler errorHandler = new HttpErrorHandler(new HttpUtils(null, null));
        return websocketUtils.createClientHttpHandler(
                websocketClient, service, errorHandler, MAX_REQUEST_TIME_MS);
    }

    private Mapping mapping(int port) {
        Mapping mapping = new Mapping();
        mapping.setHostname("127.0.0.1");
        mapping.setPort(port);
        mapping.setRootContext("/");
        return mapping;
    }

    private static int portOf(Undertow server) {
        return ((InetSocketAddress) server.getListenerInfo().get(0).getAddress()).getPort();
    }

    /**
     * A host whose SYNs go unanswered.
     *
     * <p>Binds with a backlog of 1 and then saturates the accept queue, never accepting. Once the
     * queue is full the kernel silently drops further SYNs instead of sending an RST, so a peer
     * just waits — which is precisely how a vanished node behaves, and precisely what a dead
     * <em>process</em> does not do (that answers with RST, which Undertow already fails over on).
     */
    private static final class SilentHost implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final List<Socket> parked = new ArrayList<>();

        private SilentHost() throws IOException {
            serverSocket = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
            for (int i = 0; i < 4; i++) {
                Socket socket = new Socket();
                try {
                    socket.connect(new InetSocketAddress("127.0.0.1", serverSocket.getLocalPort()), 200);
                    parked.add(socket);
                } catch (IOException queueIsFull) {
                    socket.close();
                    break;
                }
            }
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        @Override
        public void close() {
            for (Socket socket : parked) {
                try { socket.close(); } catch (IOException ignored) { /* closing */ }
            }
            try { serverSocket.close(); } catch (IOException ignored) { /* closing */ }
        }
    }
}
