package io.surisoft.capi.undertow;

import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.ServerConnection;
import io.undertow.server.handlers.ResponseCodeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the FD-leak fix: an orphaned {@link CAPILoadBalancerProxyClient} actually closes its pooled
 * backend keep-alive sockets when drained (which Undertow's TTL never does for a dereferenced pool),
 * and that draining never truncates an in-flight request.
 */
class CAPILoadBalancerProxyClientDrainTest {

    /** Live backend connections, tracked by registering a close listener the first time we see each. */
    private final Set<ServerConnection> liveBackendConnections = ConcurrentHashMap.newKeySet();

    private Undertow backend;
    private Undertow front;
    private long originalGraceBuffer;

    private int startBackend(long slowPathDelayMs) throws Exception {
        int port = freePort();
        HttpHandler handler = exchange -> {
            ServerConnection conn = exchange.getConnection();
            if (liveBackendConnections.add(conn)) {
                conn.addCloseListener(c -> liveBackendConnections.remove(c));
            }
            if (slowPathDelayMs > 0 && exchange.getRequestPath().endsWith("/slow")) {
                exchange.dispatch(() -> {
                    try {
                        Thread.sleep(slowPathDelayMs);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    exchange.getResponseSender().send("slow-ok");
                });
                return;
            }
            exchange.getResponseSender().send("ok");
        };
        backend = Undertow.builder().addHttpListener(port, "localhost").setHandler(handler).build();
        backend.start();
        return port;
    }

    private CAPILoadBalancerProxyClient startFront(int backendPort, int maxRequestTimeMs) throws Exception {
        CAPILoadBalancerProxyClient proxyClient = new CAPILoadBalancerProxyClient();
        proxyClient.setConnectionsPerThread(200);
        proxyClient.setSoftMaxConnectionsPerThread(100);
        proxyClient.setTtl(30000);
        proxyClient.addHost(URI.create("http://localhost:" + backendPort));

        HttpHandler proxyHandler = CAPIProxyHandler.builder()
                .setProxyClient(proxyClient)
                .setMaxRequestTime(maxRequestTimeMs)
                .setNext(ResponseCodeHandler.HANDLE_404)
                .build();

        int frontPort = freePort();
        front = Undertow.builder().addHttpListener(frontPort, "localhost").setHandler(proxyHandler).build();
        front.start();
        frontBaseUri = "http://localhost:" + frontPort;
        return proxyClient;
    }

    private String frontBaseUri;

    @AfterEach
    void tearDown() {
        CAPILoadBalancerProxyClient.drainGraceBufferMs = originalGraceBuffer;
        if (front != null) front.stop();
        if (backend != null) backend.stop();
    }

    @Test
    void drain_reclaimsPooledBackendSockets() throws Exception {
        originalGraceBuffer = CAPILoadBalancerProxyClient.drainGraceBufferMs;
        CAPILoadBalancerProxyClient.drainGraceBufferMs = 0; // drain ~immediately for the test

        int backendPort = startBackend(0);
        CAPILoadBalancerProxyClient proxyClient = startFront(backendPort, 5000);

        // Drive one request so the proxy opens and pools a backend keep-alive connection.
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder().uri(URI.create(frontBaseUri + "/x")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("ok", response.body());

        // The backend keep-alive socket is now pooled and stays open — this is the leak Undertow's
        // TTL never reclaims once the client is orphaned.
        assertTrue(awaitUntil(() -> liveBackendConnections.size() >= 1, 3000),
                "expected the proxy to hold at least one pooled backend connection");

        // Orphan + drain: the fix must close that pooled socket.
        proxyClient.drain(0);

        assertTrue(awaitUntil(() -> liveBackendConnections.isEmpty(), 5000),
                "drain() must close the orphaned pool's backend sockets; still open: " + liveBackendConnections.size());
    }

    @Test
    void drain_marksPoolClosed_soConnectionReturningAfterDrainDoesNotRePool() throws Exception {
        // The WS/SSE/streaming-gRPC case: a connection still leased when we drain must NOT re-pool
        // into the orphaned client when it later returns. drain() marks the pool closed, so Undertow's
        // returnConnection closes it instead of re-pooling (which would silently re-leak the socket).
        originalGraceBuffer = CAPILoadBalancerProxyClient.drainGraceBufferMs;
        CAPILoadBalancerProxyClient.drainGraceBufferMs = 0; // drain ~immediately

        int backendPort = startBackend(1500);          // /slow leases a connection for ~1.5s
        CAPILoadBalancerProxyClient proxyClient = startFront(backendPort, 10000);

        HttpClient client = HttpClient.newHttpClient();
        CompletableFuture<HttpResponse<String>> inFlight = client.sendAsync(
                HttpRequest.newBuilder().uri(URI.create(frontBaseUri + "/slow")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        // Connection is leased at the backend...
        assertTrue(awaitUntil(() -> liveBackendConnections.size() >= 1, 3000),
                "expected the slow request to lease a backend connection");
        // ...drain now, while it is still leased (simulates orphaning a client with a live tunnel).
        proxyClient.drain(0);

        // The in-flight request still completes cleanly (the leased connection is untouched).
        HttpResponse<String> response = inFlight.get(8, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(200, response.statusCode());
        assertEquals("slow-ok", response.body());

        // And because the pool is now closed, the returned connection is closed, not re-pooled.
        assertTrue(awaitUntil(() -> liveBackendConnections.isEmpty(), 5000),
                "a connection returned after drain must be closed, not re-pooled; still open: " + liveBackendConnections.size());
    }

    @Test
    void closeCurrentConnections_doesNotAbortInFlightRequest() throws Exception {
        originalGraceBuffer = CAPILoadBalancerProxyClient.drainGraceBufferMs;

        int backendPort = startBackend(1500); // /slow sleeps 1.5s before responding
        CAPILoadBalancerProxyClient proxyClient = startFront(backendPort, 10000);

        HttpClient client = HttpClient.newHttpClient();
        // Fire a slow request; it leases a backend connection for ~1.5s.
        CompletableFuture<HttpResponse<String>> inFlight = client.sendAsync(
                HttpRequest.newBuilder().uri(URI.create(frontBaseUri + "/slow")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        // Wait until the request has reached the backend (connection is leased), then drain mid-flight.
        assertTrue(awaitUntil(() -> liveBackendConnections.size() >= 1, 3000),
                "expected the slow request to lease a backend connection");
        proxyClient.closeCurrentConnections(); // the exact sweep drain() performs, run while leased

        // The leased connection is not in availableConnections, so the in-flight request must finish cleanly.
        HttpResponse<String> response = inFlight.get(8, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(200, response.statusCode(), "in-flight request must not be aborted by a drain");
        assertEquals("slow-ok", response.body(), "in-flight response must not be truncated");
    }

    @Test
    void drain_isIdempotent() throws Exception {
        originalGraceBuffer = CAPILoadBalancerProxyClient.drainGraceBufferMs;
        int backendPort = startBackend(0);
        CAPILoadBalancerProxyClient proxyClient = startFront(backendPort, 5000);

        proxyClient.drain(1000);
        assertTrue(proxyClient.isDrainScheduled());
        assertDoesNotThrow(() -> proxyClient.drain(1000)); // second call is a no-op
    }

    @Test
    void drainHandler_ignoresNonCapiHandler() {
        originalGraceBuffer = CAPILoadBalancerProxyClient.drainGraceBufferMs;
        // A handler that is not a CAPIProxyHandler (and a null) must be silently ignored.
        assertDoesNotThrow(() -> CAPILoadBalancerProxyClient.drainHandler(ResponseCodeHandler.HANDLE_404, 1000));
        assertDoesNotThrow(() -> CAPILoadBalancerProxyClient.drainHandler(null, 1000));
    }

    private static boolean awaitUntil(java.util.function.BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return true;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}