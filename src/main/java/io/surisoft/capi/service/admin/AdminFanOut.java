package io.surisoft.capi.service.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.surisoft.capi.utils.Constants;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/**
 * GETs one admin path from many nodes at once. Non-blocking ({@code sendAsync}), and never fails
 * as a whole: a node that times out, refuses, or answers garbage becomes a {@link NodeResult}
 * with an error, so the caller can report it instead of losing the other nodes' answers.
 *
 * <p>The caller's {@code Authorization} header is forwarded unchanged: peers are the same resource
 * server, so each one re-checks the caller against its own admin group.
 */
public class AdminFanOut {

    /**
     * Looked up per call. Without {@code peerTlsName} this is CAPI's shared client, which is replaced
     * (and the old one shut down) on trust-store reload; with it, the dedicated peer client.
     */
    private final Supplier<HttpClient> httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Duration timeout;

    public AdminFanOut(Supplier<HttpClient> httpClient, Duration timeout) {
        this.httpClient = httpClient;
        this.timeout = timeout;
    }

    /**
     * An HttpClient that verifies every peer's admin certificate against {@code peerTlsName} instead
     * of the dialled IP — see {@link PeerTlsNameTrustManager}. Used only for the fan-out; the shared
     * Consul client keeps standard hostname verification.
     *
     * @param trust CAPI's trust manager (reloads in place, so trust-store changes need no rebuild),
     *              or null for the JVM default trust store
     */
    public static HttpClient peerClient(javax.net.ssl.X509TrustManager trust, String peerTlsName, Duration connectTimeout) {
        try {
            javax.net.ssl.X509TrustManager delegate = trust != null ? trust : defaultTrustManager();
            javax.net.ssl.SSLContext context = javax.net.ssl.SSLContext.getInstance("TLS");
            context.init(null, new javax.net.ssl.TrustManager[]{new PeerTlsNameTrustManager(delegate, peerTlsName)}, null);
            return HttpClient.newBuilder()
                    .sslContext(context)
                    .connectTimeout(connectTimeout)
                    .executor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
                    .build();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Could not build the admin MCP peer TLS client", e);
        }
    }

    private static javax.net.ssl.X509TrustManager defaultTrustManager() throws java.security.GeneralSecurityException {
        javax.net.ssl.TrustManagerFactory factory = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        factory.init((java.security.KeyStore) null);
        for (javax.net.ssl.TrustManager tm : factory.getTrustManagers()) {
            if (tm instanceof javax.net.ssl.X509TrustManager x509) {
                return x509;
            }
        }
        throw new IllegalStateException("No X509TrustManager in the JVM default trust store");
    }

    /**
     * @param status HTTP status, or 0 when no response arrived
     * @param body   parsed JSON body (also on non-2xx when it parses), else null
     * @param error  why there is no usable answer, else null
     */
    public record NodeResult(AdminNode node, int status, JsonNode body, String error) {
        public boolean ok() {
            return error == null && status == 200 && body != null;
        }
    }

    public CompletableFuture<List<NodeResult>> getAll(List<AdminNode> nodes, String path, String authorization) {
        List<CompletableFuture<NodeResult>> futures = new ArrayList<>(nodes.size());
        for (AdminNode node : nodes) {
            futures.add(get(node, path, authorization));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList());
    }

    public CompletableFuture<NodeResult> get(AdminNode node, String path, String authorization) {
        HttpRequest request;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(node.baseUrl() + path))
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .GET();
            if (authorization != null) {
                builder.header(Constants.AUTHORIZATION_HEADER, authorization);
            }
            request = builder.build();
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(new NodeResult(node, 0, null, "invalid address: " + e.getMessage()));
        }
        return httpClient.get().sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> toResult(node, response.statusCode(), response.body()))
                .exceptionally(t -> new NodeResult(node, 0, null, describe(t)));
    }

    private NodeResult toResult(AdminNode node, int status, String body) {
        JsonNode parsed = null;
        try {
            if (body != null && !body.isBlank()) {
                parsed = objectMapper.readTree(body);
            }
        } catch (Exception e) {
            // Left null; reported below when the status alone does not explain it.
        }
        if (status == 401 || status == 403) {
            return new NodeResult(node, status, parsed, "HTTP " + status + " — this node rejected the caller's token");
        }
        if (status == 200 && parsed == null) {
            return new NodeResult(node, status, null, "HTTP 200 with an unreadable body");
        }
        return new NodeResult(node, status, parsed, null);
    }

    /** A short, log-safe reason for a failed call. */
    static String describe(Throwable t) {
        Throwable cause = t;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof HttpTimeoutException) {
            return "timeout";
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message != null && !message.isBlank() ? ": " + message : "");
    }
}
