package io.surisoft.capi.service.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.utils.Constants;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Finds the admin APIs of every CAPI node in this cluster, from the {@code capi-admin}
 * registrations in the admin MCP's own Consul ({@code admin.mcp.consul} — the same one
 * {@link AdminNodeRegistration} writes to, never the catalog hosts). Non-blocking: {@code sendAsync}.
 *
 * <p>The local node is always in the answer, even when Consul is unreachable, so a tool can still
 * say something about the node the agent is talking to — and report that discovery failed.
 */
public class AdminNodeDirectory {

    private final CAPIConfiguration.HostConfig host;
    /** Looked up per call — see {@link AdminNodeRegistration}: the shared Consul client is replaced on trust-store reload. */
    private final Supplier<HttpClient> httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String cluster;
    private final AdminNode self;
    private final Duration timeout;

    public AdminNodeDirectory(CAPIConfiguration.HostConfig host, Supplier<HttpClient> httpClient,
                              String cluster, AdminNode self, Duration timeout) {
        this.host = host;
        this.httpClient = httpClient;
        this.cluster = cluster;
        this.self = self;
        this.timeout = timeout;
    }

    /** The nodes found, plus why Consul could not be asked (empty when it answered). */
    public record Discovery(List<AdminNode> nodes, List<String> errors) {}

    public CompletableFuture<Discovery> discover() {
        return queryHost(host).thenApply(answer -> {
            Map<String, AdminNode> byId = new LinkedHashMap<>();
            byId.put(self.id(), self);
            for (AdminNode node : answer.nodes()) {
                // Our own entry is replaced by `self`, which carries the self flag.
                byId.putIfAbsent(node.id(), node);
            }
            return new Discovery(List.copyOf(byId.values()),
                    answer.error() != null ? List.of(answer.error()) : List.of());
        });
    }

    private record HostAnswer(List<AdminNode> nodes, String error) {}

    private CompletableFuture<HostAnswer> queryHost(CAPIConfiguration.HostConfig host) {
        String endpoint = AdminNodeRegistration.stripTrailingSlash(host.getEndpoint());
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + "/v1/health/service/"
                        + URLEncoder.encode(Constants.CAPI_ADMIN_SERVICE_NAME, StandardCharsets.UTF_8) + "?passing=true"))
                .timeout(timeout)
                .GET();
        if (host.getToken() != null && !host.getToken().isEmpty()) {
            builder.header(Constants.AUTHORIZATION_HEADER,
                    Constants.BEARER + host.getToken().replaceAll("(\r\n|\n)", ""));
        }
        return httpClient.get().sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        return new HostAnswer(List.of(), endpoint + ": HTTP " + response.statusCode());
                    }
                    try {
                        return new HostAnswer(parse(objectMapper.readTree(response.body())), null);
                    } catch (Exception e) {
                        return new HostAnswer(List.of(), endpoint + ": unreadable response");
                    }
                })
                .exceptionally(t -> new HostAnswer(List.of(), endpoint + ": " + AdminFanOut.describe(t)));
    }

    /** Health-endpoint entries for this cluster. Entries for other clusters sharing the Consul are ignored. */
    List<AdminNode> parse(JsonNode entries) {
        if (entries == null || !entries.isArray()) {
            return Collections.emptyList();
        }
        List<AdminNode> nodes = new ArrayList<>();
        for (JsonNode entry : entries) {
            JsonNode service = entry.path("Service");
            JsonNode meta = service.path("Meta");
            if (!cluster.equals(meta.path(Constants.CAPI_ADMIN_META_CLUSTER).asText(null))) {
                continue;
            }
            String id = service.path("ID").asText("");
            int port = service.path("Port").asInt(0);
            String address = service.path("Address").asText("");
            if (address.isEmpty()) {
                address = entry.path("Node").path("Address").asText("");
            }
            if (id.isEmpty() || address.isEmpty() || port <= 0) {
                continue;
            }
            String scheme = "https".equals(meta.path(Constants.CAPI_ADMIN_META_SCHEME).asText()) ? "https" : "http";
            nodes.add(new AdminNode(id, address, port, scheme, id.equals(self.id())));
        }
        return nodes;
    }
}
