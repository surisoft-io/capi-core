package io.surisoft.capi.service.admin;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * The read-only tools the admin MCP server offers. Each one discovers the cluster's nodes, fans an
 * admin path (or a few) out to all of them, and folds the answers into a per-node summary.
 *
 * <p>Two rules every tool keeps:
 * <ul>
 *   <li>Every answer carries a {@code nodes} block. A node that did not answer is listed in
 *       {@code unreachable}, never silently dropped — "2 of 3 answered" is itself a finding.</li>
 *   <li>Answers are summaries. Full route lists, OpenAPI bodies and verdict lists can flood an
 *       agent's context; a tool returns differences and counts and caps every list.</li>
 * </ul>
 */
public class AdminMcpTools {

    public static final String LIST_NODES = "capi_list_nodes";
    public static final String EXPLAIN_SERVICE = "capi_explain_service";
    public static final String COMPARE_ROUTES = "capi_compare_routes";
    public static final String INVALID_SERVICES = "capi_invalid_services";
    public static final String TRUSTSTORE_EXPIRING = "capi_truststore_expiring";
    public static final String SPEC_COMPLIANCE_SUMMARY = "capi_spec_compliance_summary";
    public static final String CHECK_ALIGNMENT = "capi_check_alignment";
    public static final String TRUSTSTORE_FIND = "capi_truststore_find";
    public static final String TRAFFIC = "capi_traffic";
    public static final String SERVICE_HEALTH = "capi_service_health";
    public static final String NODE_HEALTH = "capi_node_health";
    public static final String SECURITY_EVENTS = "capi_security_events";
    static final int DEFAULT_MINUTES = 15;

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 1000;
    static final int DEFAULT_DAYS = 30;
    private static final long DAY_MS = 86_400_000L;

    private final AdminNodeDirectory directory;
    private final AdminFanOut fanOut;
    private final LongSupplier clock;

    public AdminMcpTools(AdminNodeDirectory directory, AdminFanOut fanOut) {
        this(directory, fanOut, System::currentTimeMillis);
    }

    /** With an explicit clock, for the truststore window in tests. */
    public AdminMcpTools(AdminNodeDirectory directory, AdminFanOut fanOut, LongSupplier clock) {
        this.directory = directory;
        this.fanOut = fanOut;
        this.clock = clock;
    }

    /** Thrown for a bad tool name or argument; the gateway maps it to JSON-RPC -32602. */
    public static class InvalidToolCall extends RuntimeException {
        public InvalidToolCall(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ definitions

    public List<Map<String, Object>> definitions() {
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(tool(LIST_NODES,
                "List every CAPI node in this cluster (from Consul) with version, uptime, health, route count and "
                        + "invalid-service count. Start here: it shows whether all nodes are reachable and alike.",
                Map.of()));
        tools.add(tool(EXPLAIN_SERVICE,
                "Explain one service on every node: is it routed, to which backend endpoints, is it marked invalid "
                        + "(and why), and its OpenAPI spec-compliance verdict. Use it for 'why does service X not route' "
                        + "or 'why do nodes answer differently for X'.",
                Map.of("serviceId", prop("string", "Service id as CAPI shows it, usually <name>:<group>"))));
        tools.add(tool(COMPARE_ROUTES,
                "Compare REST routes across all nodes and return only the differences: routes missing on some nodes, "
                        + "or routed to different backend endpoints.",
                Map.of("limit", prop("integer", "Maximum differences returned (default 100)"))));
        tools.add(tool(INVALID_SERVICES,
                "Services CAPI refused to route (OpenAPI fetch/parse failures, version or identity mismatch, WebDAV "
                        + "conflicts), merged across nodes with the nodes reporting each.",
                Map.of("limit", prop("integer", "Maximum entries returned (default 100)"))));
        tools.add(tool(TRUSTSTORE_EXPIRING,
                "Certificates in CAPI's trust store that are expired or expire within N days, with the nodes holding "
                        + "them — and for each, whether it is already REPLACED: a certificate valid beyond the window that "
                        + "shares its CN or a DNS name (listed under replacedBy). Also lists aliases added to the trust "
                        + "store in the last recentDays (the date the alias was added, not the certificate's issue date).",
                Map.of("days", prop("integer", "Look-ahead window in days (default 30)"),
                        "recentDays", prop("integer", "Window for recently added aliases, in days (default 30)"))));
        tools.add(tool(TRUSTSTORE_FIND,
                "Search the whole trust store, valid and expired certificates alike, by alias, subject, issuer or DNS "
                        + "name (case-insensitive substring), with validity, date added and the nodes holding each.",
                Map.of("query", prop("string", "Text to look for, e.g. a CN, a domain or an alias"),
                        "limit", prop("integer", "Maximum certificates returned (default 100)"))));
        tools.add(tool(CHECK_ALIGNMENT,
                "Check that every node is aligned: same CAPI version, same JWKS endpoints AND the same signing "
                        + "keys actually loaded (keys load once at startup, so nodes can differ after a key rotation "
                        + "or an IdP outage), same Consul hosts, and the same services exposed (counts per transport "
                        + "and the exact REST service ids). Returns one verdict per check and only the differences.",
                Map.of("limit", prop("integer", "Maximum differing service ids listed (default 100)"))));
        Map<String, Object> minutesProp = prop("integer",
                "Look back this many minutes (default 15; bounded by each node's history retention, 60 by default)");
        tools.add(tool(TRAFFIC,
                "Traffic over the last N minutes, per node and cluster-wide: requests/s, 4xx/5xx and 5xx ratio, average "
                        + "and recent max latency; how load is shared between nodes (flags imbalance); top services by "
                        + "5xx, by latency and by traffic; 5xx spikes. From each node's in-memory metrics history.",
                Map.of("minutes", minutesProp, "limit", prop("integer", "Entries per top list (default 10)"))));
        tools.add(tool(SERVICE_HEALTH,
                "One service over the last N minutes on every node: requests/s, status groups, 5xx ratio, latency and a "
                        + "per-interval series. Lists nodes that served it no traffic. Metrics companion to capi_explain_service.",
                Map.of("serviceId", prop("string", "Service id as CAPI shows it, usually <name>:<group>"), "minutes", minutesProp)));
        tools.add(tool(NODE_HEALTH,
                "JVM health per node over the last N minutes — heap use, threads, process CPU, open file descriptors — "
                        + "with outliers flagged against the other nodes and growth within the window (leak signals: "
                        + "threads or file descriptors climbing).",
                Map.of("minutes", minutesProp)));
        tools.add(tool(SECURITY_EVENTS,
                "Security counters over the last N minutes per node and cluster-wide: blocked paths, dot-segment "
                        + "requests, tokens in query strings, WebSocket origin checks, WebDAV depth — split by action "
                        + "(observed vs rejected). A spike is a probing or attack signal.",
                Map.of("minutes", minutesProp)));
        tools.add(tool(SPEC_COMPLIANCE_SUMMARY,
                "Per-node counts of OpenAPI spec-identity verdicts (compliant / non-compliant / ...) and whether "
                        + "enforcement is on. Counts only; use capi_explain_service for one service.",
                Map.of()));
        return tools;
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (properties.containsKey("serviceId")) {
            schema.put("required", List.of("serviceId"));
        } else if (properties.containsKey("query")) {
            schema.put("required", List.of("query"));
        }
        schema.put("additionalProperties", false);
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", name);
        tool.put("description", description);
        tool.put("inputSchema", schema);
        tool.put("annotations", Map.of("readOnlyHint", true, "openWorldHint", false));
        return tool;
    }

    private static Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    // ------------------------------------------------------------------ dispatch

    public CompletableFuture<Map<String, Object>> call(String name, Map<String, Object> arguments, String authorization) {
        Map<String, Object> args = arguments != null ? arguments : Map.of();
        return switch (name == null ? "" : name) {
            case LIST_NODES -> listNodes(authorization);
            case EXPLAIN_SERVICE -> explainService(requiredString(args, "serviceId"), authorization);
            case COMPARE_ROUTES -> compareRoutes(intArg(args, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT), authorization);
            case INVALID_SERVICES -> invalidServices(intArg(args, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT), authorization);
            case TRUSTSTORE_EXPIRING -> truststoreExpiring(intArg(args, "days", DEFAULT_DAYS, 0, 3650),
                    intArg(args, "recentDays", DEFAULT_DAYS, 0, 3650), authorization);
            case TRUSTSTORE_FIND -> truststoreFind(requiredString(args, "query"),
                    intArg(args, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT), authorization);
            case SPEC_COMPLIANCE_SUMMARY -> specComplianceSummary(authorization);
            case CHECK_ALIGNMENT -> checkAlignment(intArg(args, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT), authorization);
            case TRAFFIC -> traffic(intArg(args, "minutes", DEFAULT_MINUTES, 1, 1440), intArg(args, "limit", 10, 1, MAX_LIMIT), authorization);
            case SERVICE_HEALTH -> serviceHealth(requiredString(args, "serviceId"), intArg(args, "minutes", DEFAULT_MINUTES, 1, 1440), authorization);
            case NODE_HEALTH -> nodeHealth(intArg(args, "minutes", DEFAULT_MINUTES, 1, 1440), authorization);
            case SECURITY_EVENTS -> securityEvents(intArg(args, "minutes", DEFAULT_MINUTES, 1, 1440), authorization);
            default -> throw new InvalidToolCall("Unknown tool: " + name);
        };
    }

    /** Discovers the nodes, fetches every path from all of them, then hands the answers to {@code fold}. */
    private CompletableFuture<Map<String, Object>> fanOut(List<String> paths, String authorization,
                                                          Function<Fetched, Map<String, Object>> fold) {
        return directory.discover().thenCompose(discovery -> {
            List<CompletableFuture<List<AdminFanOut.NodeResult>>> calls = new ArrayList<>();
            for (String path : paths) {
                calls.add(fanOut.getAll(discovery.nodes(), path, authorization));
            }
            return CompletableFuture.allOf(calls.toArray(new CompletableFuture[0])).thenApply(ignored -> {
                Map<String, List<AdminFanOut.NodeResult>> byPath = new LinkedHashMap<>();
                for (int i = 0; i < paths.size(); i++) {
                    byPath.put(paths.get(i), calls.get(i).join());
                }
                Fetched fetched = new Fetched(discovery, byPath);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("nodes", fetched.nodesBlock());
                String rejection = tokenRejectionHint(fetched, authorization);
                if (rejection != null) {
                    result.put("tokenRejectedByPeers", rejection);
                }
                if (!discovery.errors().isEmpty()) {
                    result.put("discoveryErrors", discovery.errors());
                }
                result.putAll(fold.apply(fetched));
                return result;
            });
        });
    }

    /** All answers for one call, with the bookkeeping for the {@code nodes} block. */
    record Fetched(AdminNodeDirectory.Discovery discovery, Map<String, List<AdminFanOut.NodeResult>> byPath) {

        List<AdminFanOut.NodeResult> results(String path) {
            return byPath.get(path);
        }

        /** A node is unreachable when any of its calls failed outright (no status, or an auth rejection). */
        Map<String, Object> nodesBlock() {
            Map<String, String> unreachable = new LinkedHashMap<>();
            for (List<AdminFanOut.NodeResult> results : byPath.values()) {
                for (AdminFanOut.NodeResult r : results) {
                    if (r.error() != null) {
                        unreachable.putIfAbsent(label(r.node()), r.error());
                    }
                }
            }
            List<Map<String, Object>> unreachableList = new ArrayList<>();
            unreachable.forEach((node, error) -> unreachableList.add(Map.of("node", node, "error", error)));
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("total", discovery.nodes().size());
            block.put("answered", discovery.nodes().size() - unreachable.size());
            block.put("unreachable", unreachableList);
            return block;
        }
    }

    /**
     * This node accepted the caller's token (the request got here), so a peer answering 401/403 with
     * the same admin config almost always lacks the token's signing key: CAPI loads JWKS keys once at
     * startup, so a node started before an IdP key rotation rejects every token signed with the new key.
     */
    static String tokenRejectionHint(Fetched fetched, String authorization) {
        java.util.Set<String> rejecting = new java.util.TreeSet<>();
        for (List<AdminFanOut.NodeResult> results : fetched.byPath().values()) {
            for (AdminFanOut.NodeResult r : results) {
                if (r.status() == 401 || r.status() == 403) {
                    rejecting.add(label(r.node()));
                }
            }
        }
        if (rejecting.isEmpty()) {
            return null;
        }
        String kid = tokenKeyId(authorization);
        return rejecting.size() + " node(s) rejected a token this node accepted: " + String.join(", ", rejecting)
                + ". Most likely they do not have its signing key" + (kid != null ? " (kid " + kid + ")" : "")
                + " — CAPI loads JWKS keys only at startup, so a node started before an IdP key rotation rejects "
                + "every token signed with the new key until it is restarted. Their data was not compared.";
    }

    private static String tokenKeyId(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        try {
            return com.nimbusds.jose.JWSObject.parse(authorization.substring(7)).getHeader().getKeyID();
        } catch (java.text.ParseException e) {
            return null;
        }
    }

    static String label(AdminNode node) {
        return node.self() ? node.id() + " (this node)" : node.id();
    }

    // ------------------------------------------------------------------ tools

    CompletableFuture<Map<String, Object>> listNodes(String authorization) {
        return fanOut(List.of("/info/capi", "/info/health"), authorization, fetched -> {
            List<AdminFanOut.NodeResult> infos = fetched.results("/info/capi");
            List<AdminFanOut.NodeResult> healths = fetched.results("/info/health");
            List<Map<String, Object>> nodes = new ArrayList<>();
            Set<String> versions = new TreeSet<>();
            for (int i = 0; i < infos.size(); i++) {
                AdminFanOut.NodeResult info = infos.get(i);
                AdminFanOut.NodeResult health = healths.get(i);
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("node", label(info.node()));
                n.put("address", info.node().baseUrl());
                // /info/health answers 503 with {"status":"DOWN"}, so read the body whatever the status.
                n.put("health", health.body() != null ? health.body().path("status").asText(null) : null);
                if (info.ok()) {
                    JsonNode b = info.body();
                    String version = b.path("capiVersion").asText(null);
                    n.put("capiVersion", version);
                    if (version != null) versions.add(version);
                    n.put("uptime", b.path("uptime").asText(null));
                    n.put("totalRoutes", b.path("totalRoutes").asInt());
                    n.put("invalidServiceCount", b.path("invalidServiceCount").asInt());
                } else {
                    n.put("error", info.error() != null ? info.error() : "HTTP " + info.status());
                }
                nodes.add(n);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("versions", versions);
            out.put("mixedVersions", versions.size() > 1);
            out.put("nodeDetails", nodes);
            return out;
        });
    }

    CompletableFuture<Map<String, Object>> explainService(String serviceId, String authorization) {
        String routePath = "/info/routes/" + URLEncoder.encode(serviceId, StandardCharsets.UTF_8).replace("+", "%20");
        return fanOut(List.of(routePath, "/info/invalid-services", "/info/spec-compliance"), authorization, fetched -> {
            List<AdminFanOut.NodeResult> routes = fetched.results(routePath);
            List<AdminFanOut.NodeResult> invalid = fetched.results("/info/invalid-services");
            List<AdminFanOut.NodeResult> compliance = fetched.results("/info/spec-compliance");

            List<Map<String, Object>> perNode = new ArrayList<>();
            Set<String> signatures = new LinkedHashSet<>();
            for (int i = 0; i < routes.size(); i++) {
                AdminFanOut.NodeResult route = routes.get(i);
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("node", label(route.node()));
                if (route.error() != null) {
                    n.put("error", route.error());
                    perNode.add(n);
                    continue;
                }
                boolean routed = route.status() == 200 && route.body() != null;
                n.put("routed", routed);
                List<String> endpoints = routed ? endpointsOf(route.body().path("mappingList")) : List.of();
                if (routed) {
                    n.put("endpoints", endpoints);
                    JsonNode meta = route.body().path("serviceMeta");
                    if (!meta.isMissingNode() && !meta.isNull()) {
                        n.put("secured", meta.path("secured").asBoolean(false));
                    }
                } else if (route.status() != 404) {
                    n.put("routeLookup", "HTTP " + route.status());
                }

                JsonNode invalidEntry = findInvalid(invalid.get(i), serviceId);
                if (invalidEntry != null) {
                    n.put("invalid", Map.of(
                            "reason", invalidEntry.path("reason").asText(""),
                            "detail", invalidEntry.path("detail").asText(""),
                            "detectedAt", invalidEntry.path("detectedAt").asText("")));
                }
                String verdict = findVerdict(compliance.get(i), serviceId);
                if (verdict != null) {
                    n.put("specCompliance", verdict);
                }
                perNode.add(n);
                signatures.add(routed + "|" + endpoints + "|"
                        + (invalidEntry != null ? invalidEntry.path("reason").asText("") : "") + "|" + verdict);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("serviceId", serviceId);
            // Only nodes that answered are compared; an unreachable node is in nodes.unreachable.
            out.put("consistentAcrossNodes", signatures.size() <= 1);
            out.put("perNode", perNode);
            return out;
        });
    }

    CompletableFuture<Map<String, Object>> compareRoutes(int limit, String authorization) {
        return fanOut(List.of("/info/routes"), authorization, fetched -> {
            List<AdminFanOut.NodeResult> results = fetched.results("/info/routes");
            List<String> answered = new ArrayList<>();
            // route id -> node -> endpoints
            Map<String, Map<String, List<String>>> routes = new TreeMap<>();
            for (AdminFanOut.NodeResult r : results) {
                if (!r.ok() || !r.body().isArray()) continue;
                String node = label(r.node());
                answered.add(node);
                for (JsonNode route : r.body()) {
                    List<String> endpoints = new ArrayList<>();
                    route.path("endpoints").forEach(e -> endpoints.add(e.asText()));
                    endpoints.sort(Comparator.naturalOrder());
                    routes.computeIfAbsent(route.path("id").asText(), k -> new LinkedHashMap<>()).put(node, endpoints);
                }
            }
            List<Map<String, Object>> differences = new ArrayList<>();
            for (Map.Entry<String, Map<String, List<String>>> e : routes.entrySet()) {
                Map<String, List<String>> byNode = e.getValue();
                Map<String, Object> diff = new LinkedHashMap<>();
                if (byNode.size() < answered.size()) {
                    diff.put("id", e.getKey());
                    diff.put("missingOn", answered.stream().filter(n -> !byNode.containsKey(n)).toList());
                    diff.put("presentOn", List.copyOf(byNode.keySet()));
                } else if (new LinkedHashSet<>(byNode.values()).size() > 1) {
                    diff.put("id", e.getKey());
                    diff.put("endpointsByNode", byNode);
                }
                if (!diff.isEmpty()) differences.add(diff);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("routesCompared", routes.size());
            out.put("nodesCompared", answered.size());
            out.put("differenceCount", differences.size());
            out.put("differences", cap(differences, limit));
            out.put("truncated", differences.size() > limit);
            return out;
        });
    }

    CompletableFuture<Map<String, Object>> invalidServices(int limit, String authorization) {
        return fanOut(List.of("/info/invalid-services"), authorization, fetched -> {
            List<AdminFanOut.NodeResult> results = fetched.results("/info/invalid-services");
            Map<String, Map<String, Object>> merged = new TreeMap<>();
            int answered = 0;
            for (AdminFanOut.NodeResult r : results) {
                if (!r.ok() || !r.body().isArray()) continue;
                answered++;
                for (JsonNode entry : r.body()) {
                    String id = entry.path("serviceId").asText();
                    String reason = entry.path("reason").asText();
                    Map<String, Object> m = merged.computeIfAbsent(id + "|" + reason, k -> {
                        Map<String, Object> fresh = new LinkedHashMap<>();
                        fresh.put("serviceId", id);
                        fresh.put("reason", reason);
                        fresh.put("detail", entry.path("detail").asText(""));
                        fresh.put("reportedBy", new ArrayList<String>());
                        return fresh;
                    });
                    @SuppressWarnings("unchecked")
                    List<String> reportedBy = (List<String>) m.get("reportedBy");
                    reportedBy.add(label(r.node()));
                }
            }
            int answeredNodes = answered;
            List<Map<String, Object>> entries = new ArrayList<>(merged.values());
            entries.forEach(m -> m.put("onAllAnsweringNodes", ((List<?>) m.get("reportedBy")).size() == answeredNodes));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("count", entries.size());
            out.put("invalidServices", cap(entries, limit));
            out.put("truncated", entries.size() > limit);
            return out;
        });
    }

    /** One certificate as seen across the cluster: identity, validity and the nodes holding it. */
    record Cert(String alias, String subjectDN, String issuerDN, long notBefore, long notAfter, Long addedAt,
                List<String> dnsNames, String cn, List<String> nodes) {

        /** Names this certificate vouches for, lower-cased: its CN and its DNS SANs. */
        Set<String> names() {
            Set<String> names = new TreeSet<>();
            if (cn != null) names.add(cn.toLowerCase(java.util.Locale.ROOT));
            dnsNames.forEach(n -> names.add(n.toLowerCase(java.util.Locale.ROOT)));
            return names;
        }

        Map<String, Object> describe(long now) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("alias", alias);
            d.put("subjectDN", subjectDN);
            d.put("issuerDN", issuerDN);
            if (!dnsNames.isEmpty()) d.put("dnsNames", dnsNames);
            d.put("notBefore", java.time.Instant.ofEpochMilli(notBefore).toString());
            d.put("notAfter", java.time.Instant.ofEpochMilli(notAfter).toString());
            d.put("expired", notAfter < now);
            d.put("daysLeft", Math.floorDiv(notAfter - now, DAY_MS));
            // When the alias entered the trust store (not the certificate's issue date); null if the store type does not keep it.
            d.put("addedToTrustStore", addedAt != null ? java.time.Instant.ofEpochMilli(addedAt).toString() : null);
            d.put("nodes", nodes);
            return d;
        }
    }

    /** Every certificate in every answering node's trust store, merged by alias + notAfter. */
    private static List<Cert> mergeTrustStores(Fetched fetched, List<String> notEnabledOn) {
        Map<String, Cert> merged = new TreeMap<>();
        for (AdminFanOut.NodeResult r : fetched.results("/info/truststore")) {
            if (r.error() != null) continue;
            if (r.status() == 404) {
                notEnabledOn.add(label(r.node()));
                continue;
            }
            if (!r.ok() || !r.body().isArray()) continue;
            for (JsonNode c : r.body()) {
                if (!c.path("notAfter").canConvertToLong() || c.path("alias").isMissingNode()) continue;
                String alias = c.path("alias").asText();
                long notAfter = c.path("notAfter").asLong();
                Cert cert = merged.computeIfAbsent(alias + "|" + notAfter, k -> {
                    List<String> dns = new ArrayList<>();
                    c.path("dnsNames").forEach(n -> dns.add(n.asText()));
                    String subject = c.path("subjectDN").asText("");
                    return new Cert(alias, subject, c.path("issuerDN").asText(""), c.path("notBefore").asLong(0),
                            notAfter, c.path("addedAt").canConvertToLong() ? c.path("addedAt").asLong() : null,
                            dns, commonName(subject), new ArrayList<>());
                });
                cert.nodes().add(label(r.node()));
            }
        }
        return new ArrayList<>(merged.values());
    }

    static String commonName(String subjectDN) {
        try {
            for (javax.naming.ldap.Rdn rdn : new javax.naming.ldap.LdapName(subjectDN).getRdns()) {
                if ("CN".equalsIgnoreCase(rdn.getType())) return String.valueOf(rdn.getValue());
            }
        } catch (javax.naming.InvalidNameException | IllegalArgumentException e) {
            // Not a parseable DN: no CN.
        }
        return null;
    }

    CompletableFuture<Map<String, Object>> truststoreExpiring(int days, int recentDays, String authorization) {
        return fanOut(List.of("/info/truststore"), authorization, fetched -> {
            long now = clock.getAsLong();
            long horizon = now + days * DAY_MS;
            List<String> notEnabledOn = new ArrayList<>();
            List<Cert> all = mergeTrustStores(fetched, notEnabledOn);
            List<Cert> healthy = all.stream().filter(c -> c.notAfter() > horizon).toList();

            List<Map<String, Object>> certificates = new ArrayList<>();
            int replaced = 0;
            for (Cert cert : all.stream().filter(c -> c.notAfter() <= horizon)
                    .sorted(Comparator.comparingLong(Cert::notAfter)).toList()) {
                Map<String, Object> d = cert.describe(now);
                // A replacement: valid past the window AND vouching for at least one of the same names.
                Set<String> names = cert.names();
                List<Map<String, Object>> replacedBy = new ArrayList<>();
                for (Cert candidate : healthy) {
                    Set<String> shared = new TreeSet<>(candidate.names());
                    shared.retainAll(names);
                    if (!shared.isEmpty()) {
                        Map<String, Object> rd = candidate.describe(now);
                        rd.put("sharedNames", shared);
                        replacedBy.add(rd);
                    }
                }
                d.put("status", replacedBy.isEmpty() ? "NOT replaced" : "replaced");
                d.put("replacedBy", replacedBy);
                if (!replacedBy.isEmpty()) replaced++;
                certificates.add(d);
            }

            long recentFrom = now - recentDays * DAY_MS;
            List<Map<String, Object>> recentlyAdded = all.stream()
                    .filter(c -> c.addedAt() != null && c.addedAt() >= recentFrom)
                    .sorted(Comparator.comparingLong(Cert::addedAt).reversed())
                    .map(c -> c.describe(now)).toList();

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("windowDays", days);
            out.put("count", certificates.size());
            out.put("replaced", replaced);
            out.put("notReplaced", certificates.size() - replaced);
            out.put("matching", "a replacement is a certificate valid beyond the window that shares the CN or a DNS SAN");
            out.put("certificates", certificates);
            out.put("recentlyAddedDays", recentDays);
            out.put("recentlyAdded", recentlyAdded);
            if (all.stream().anyMatch(c -> c.addedAt() == null)) {
                out.put("note", "Some entries carry no trust-store creation date (store type does not keep it); "
                        + "they cannot appear in recentlyAdded.");
            }
            if (!notEnabledOn.isEmpty()) {
                out.put("trustStoreNotEnabledOn", notEnabledOn);
            }
            return out;
        });
    }

    CompletableFuture<Map<String, Object>> truststoreFind(String query, int limit, String authorization) {
        String q = query.toLowerCase(java.util.Locale.ROOT);
        return fanOut(List.of("/info/truststore"), authorization, fetched -> {
            long now = clock.getAsLong();
            List<String> notEnabledOn = new ArrayList<>();
            List<Map<String, Object>> matches = mergeTrustStores(fetched, notEnabledOn).stream()
                    .filter(c -> c.alias().toLowerCase(java.util.Locale.ROOT).contains(q)
                            || c.subjectDN().toLowerCase(java.util.Locale.ROOT).contains(q)
                            || c.issuerDN().toLowerCase(java.util.Locale.ROOT).contains(q)
                            || c.names().stream().anyMatch(n -> n.contains(q)))
                    .sorted(Comparator.comparing(Cert::alias).thenComparingLong(Cert::notAfter))
                    .map(c -> c.describe(now)).toList();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("query", query);
            out.put("count", matches.size());
            out.put("certificates", cap(matches, limit));
            out.put("truncated", matches.size() > limit);
            if (!notEnabledOn.isEmpty()) out.put("trustStoreNotEnabledOn", notEnabledOn);
            return out;
        });
    }

    CompletableFuture<Map<String, Object>> specComplianceSummary(String authorization) {
        return fanOut(List.of("/info/spec-compliance"), authorization, fetched -> {
            List<Map<String, Object>> perNode = new ArrayList<>();
            Set<String> distinct = new LinkedHashSet<>();
            for (AdminFanOut.NodeResult r : fetched.results("/info/spec-compliance")) {
                if (r.error() != null) continue;
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("node", label(r.node()));
                if (r.ok()) {
                    n.put("summary", r.body().path("summary"));
                    distinct.add(r.body().path("summary").toString());
                } else {
                    n.put("unavailable", "HTTP " + r.status());
                }
                perNode.add(n);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("consistentAcrossNodes", distinct.size() <= 1);
            out.put("perNode", perNode);
            return out;
        });
    }

    CompletableFuture<Map<String, Object>> checkAlignment(int limit, String authorization) {
        return fanOut(List.of("/info/capi", "/info/routes"), authorization, fetched -> {
            List<AdminFanOut.NodeResult> infos = fetched.results("/info/capi");
            Map<String, Object> checks = new LinkedHashMap<>();
            checks.put("capiVersion", dimension(infos, b -> textNode(b.get("capiVersion"))));
            checks.put("jwksEndpoints", dimension(infos, b -> !b.path("oauth2Enabled").asBoolean(false)
                    ? MAPPER.getNodeFactory().textNode("oauth2 disabled")
                    : sortedArray(b.path("oauth2Endpoint").asText("").split(","))));
            checks.put("jwksKeysLoaded", dimension(infos, b -> !b.path("oauth2Enabled").asBoolean(false)
                    ? MAPPER.getNodeFactory().textNode("oauth2 disabled")
                    : b.has("jwksKeyIds") && !b.get("jwksKeyIds").isNull() ? canonical(b.get("jwksKeyIds")) : null));
            checks.put("consulHosts", dimension(infos, b -> b.has("consulHosts") ? canonical(b.get("consulHosts")) : null));
            checks.put("serviceCounts", dimension(infos, AdminMcpTools::serviceCounts));
            checks.put("restServices", restServiceAlignment(fetched.results("/info/routes"), limit));

            // A node that loaded no keys from an endpoint rejects every token from that issuer.
            List<String> warnings = new ArrayList<>();
            for (AdminFanOut.NodeResult r : infos) {
                if (!r.ok()) continue;
                r.body().path("jwksKeyIds").fields().forEachRemaining(e -> {
                    if (e.getValue().isArray() && e.getValue().isEmpty()) {
                        warnings.add(label(r.node()) + " loaded NO keys from " + e.getKey()
                                + " — it rejects every token from that issuer until restarted");
                    }
                });
            }

            boolean allAligned = checks.values().stream().allMatch(c -> Boolean.TRUE.equals(((Map<?, ?>) c).get("aligned")))
                    && ((List<?>) fetched.nodesBlock().get("unreachable")).isEmpty();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("aligned", allAligned);
            if (!warnings.isEmpty()) out.put("warnings", warnings);
            out.put("checks", checks);
            return out;
        });
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * One alignment check: the value each answering node reports, grouped. {@code aligned} means every
     * node with a known value reports the same one; a node whose CAPI predates the field (null) is
     * listed in {@code unknownOn} instead of counting as a difference — a rolling deploy is not drift.
     */
    static Map<String, Object> dimension(List<AdminFanOut.NodeResult> results, Function<JsonNode, JsonNode> extract) {
        Map<String, JsonNode> valueByKey = new LinkedHashMap<>();
        Map<String, List<String>> nodesByKey = new LinkedHashMap<>();
        List<String> unknownOn = new ArrayList<>();
        for (AdminFanOut.NodeResult r : results) {
            if (!r.ok()) continue;   // reported in nodes.unreachable
            JsonNode value = extract.apply(r.body());
            if (value == null || value.isNull() || value.isMissingNode()) {
                unknownOn.add(label(r.node()));
                continue;
            }
            String key = value.toString();
            valueByKey.putIfAbsent(key, value);
            nodesByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(label(r.node()));
        }
        int compared = nodesByKey.values().stream().mapToInt(List::size).sum();
        Map<String, Object> out = new LinkedHashMap<>();
        // Agreement among the nodes that answered. With fewer than two compared while others exist,
        // there is nothing to agree with — say so instead of claiming alignment.
        boolean comparable = compared >= 2 || compared == results.size();
        out.put("aligned", comparable ? nodesByKey.size() <= 1 : null);
        out.put("nodesCompared", compared + " of " + results.size());
        if (nodesByKey.size() == 1) {
            out.put("value", valueByKey.values().iterator().next());
        } else if (nodesByKey.size() > 1) {
            List<Map<String, Object>> groups = new ArrayList<>();
            nodesByKey.forEach((key, nodes) -> groups.add(Map.of("value", valueByKey.get(key), "nodes", nodes)));
            out.put("byValue", groups);
        }
        if (!unknownOn.isEmpty()) {
            out.put("unknownOn", unknownOn);
        }
        return out;
    }

    private static JsonNode serviceCounts(JsonNode b) {
        if (!b.has("serviceCount")) {
            return null;   // node predates the field
        }
        com.fasterxml.jackson.databind.node.ObjectNode counts = MAPPER.createObjectNode();
        counts.put("services", b.path("serviceCount").asInt());
        counts.put("restRoutes", b.path("totalRoutes").asInt());
        counts.put("websocketRoutes", b.path("websocketRoutes").asInt());
        counts.put("webdavRoutes", b.path("webdavRoutes").asInt());
        counts.put("invalidServices", b.path("invalidServiceCount").asInt());
        return counts;
    }

    /** The exact REST service ids: aligned when every answering node exposes the same set. */
    private static Map<String, Object> restServiceAlignment(List<AdminFanOut.NodeResult> results, int limit) {
        Map<String, Set<String>> idsByNode = new LinkedHashMap<>();
        for (AdminFanOut.NodeResult r : results) {
            if (!r.ok() || !r.body().isArray()) continue;
            Set<String> ids = new TreeSet<>();
            r.body().forEach(route -> ids.add(route.path("id").asText()));
            idsByNode.put(label(r.node()), ids);
        }
        Set<String> union = new TreeSet<>();
        idsByNode.values().forEach(union::addAll);
        List<Map<String, Object>> differences = new ArrayList<>();
        for (String id : union) {
            List<String> missingOn = idsByNode.entrySet().stream()
                    .filter(e -> !e.getValue().contains(id)).map(Map.Entry::getKey).toList();
            if (!missingOn.isEmpty()) {
                differences.add(Map.of("id", id, "missingOn", missingOn));
            }
        }
        Map<String, Object> countByNode = new LinkedHashMap<>();
        idsByNode.forEach((node, ids) -> countByNode.put(node, ids.size()));
        Map<String, Object> out = new LinkedHashMap<>();
        boolean comparable = idsByNode.size() >= 2 || idsByNode.size() == results.size();
        out.put("aligned", comparable ? differences.isEmpty() : null);
        out.put("nodesCompared", idsByNode.size() + " of " + results.size());
        out.put("countByNode", countByNode);
        if (!differences.isEmpty()) {
            out.put("differenceCount", differences.size());
            out.put("differences", cap(differences, limit));
            out.put("truncated", differences.size() > limit);
        }
        return out;
    }

    private static JsonNode textNode(JsonNode node) {
        return node == null || node.isNull() ? null : node;
    }

    private static JsonNode sortedArray(String[] values) {
        com.fasterxml.jackson.databind.node.ArrayNode array = MAPPER.createArrayNode();
        java.util.Arrays.stream(values).map(String::trim).filter(v -> !v.isEmpty()).sorted().forEach(array::add);
        return array;
    }

    /** Order-insensitive form: object keys sorted, array elements sorted — so equal sets compare equal. */
    static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            TreeMap<String, JsonNode> byKey = new TreeMap<>();
            node.fields().forEachRemaining(e -> byKey.put(e.getKey(), e.getValue()));
            com.fasterxml.jackson.databind.node.ObjectNode sorted = MAPPER.createObjectNode();
            byKey.forEach((k, v) -> sorted.set(k, canonical(v)));
            return sorted;
        }
        if (node.isArray()) {
            List<JsonNode> items = new ArrayList<>();
            node.forEach(item -> items.add(canonical(item)));
            items.sort(Comparator.comparing(JsonNode::toString));
            com.fasterxml.jackson.databind.node.ArrayNode array = MAPPER.createArrayNode();
            items.forEach(array::add);
            return array;
        }
        return node;
    }

    // ------------------------------------------------------------------ metrics (per-node history)

    private static String historyPath(int minutes, String service) {
        return "/info/metrics/history?minutes=" + minutes
                + (service != null ? "&service=" + URLEncoder.encode(service, StandardCharsets.UTF_8) : "");
    }

    /** Nodes whose admin API has no history: older CAPI, or admin.mcp not enabled there. */
    private static List<String> historyUnavailable(List<AdminFanOut.NodeResult> results) {
        return results.stream().filter(r -> r.error() == null && r.status() == 404).map(r -> label(r.node())).toList();
    }

    private static Map<String, Object> pick(JsonNode node, String... fields) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String f : fields) {
            if (node.has(f)) m.put(f, node.get(f));
        }
        return m;
    }

    CompletableFuture<Map<String, Object>> traffic(int minutes, int limit, String authorization) {
        String path = historyPath(minutes, null);
        return fanOut(List.of(path), authorization, fetched -> {
            List<AdminFanOut.NodeResult> results = fetched.results(path).stream().filter(AdminFanOut.NodeResult::ok).toList();
            long clusterRequests = 0, cluster4xx = 0, cluster5xx = 0;
            double latencyWeighted = 0;
            Map<String, double[]> services = new TreeMap<>();   // requests, 4xx, 5xx, latency*requests
            List<Map<String, Object>> perNode = new ArrayList<>();
            List<Map<String, Object>> spikes = new ArrayList<>();
            for (AdminFanOut.NodeResult r : results) {
                JsonNode t = r.body().path("totals");
                long requests = t.path("requests").asLong();
                clusterRequests += requests;
                cluster4xx += t.path("4xx").asLong();
                cluster5xx += t.path("5xx").asLong();
                latencyWeighted += t.path("avgLatencyMs").asDouble() * requests;
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("node", label(r.node()));
                n.put("coveredSeconds", r.body().path("coveredSeconds").asLong());
                n.putAll(pick(t, "requests", "requestsPerSecond", "4xx", "5xx", "errorRatio5xx", "avgLatencyMs", "maxLatencyMsSampled"));
                n.put("requests", requests);   // a number, not a JsonNode: the load share is computed from it
                perNode.add(n);
                for (JsonNode svc : r.body().path("services")) {
                    double[] acc = services.computeIfAbsent(svc.path("service").asText(), k -> new double[4]);
                    long req = svc.path("requests").asLong();
                    acc[0] += req;
                    acc[1] += svc.path("4xx").asLong();
                    acc[2] += svc.path("5xx").asLong();
                    acc[3] += svc.path("avgLatencyMs").asDouble() * req;
                }
                Map<String, Object> spike = spike(r);
                if (spike != null) spikes.add(spike);
            }
            // Load share: with an even spread every node takes 1/N; far off that is worth a look.
            List<String> imbalance = new ArrayList<>();
            if (clusterRequests > 0) {
                double even = 1.0 / perNode.size();
                for (Map<String, Object> n : perNode) {
                    double share = ((Number) n.get("requests")).doubleValue() / clusterRequests;
                    n.put("loadShare", Math.round(share * 1000) / 1000.0);
                    if (perNode.size() > 1 && clusterRequests >= 100 && (share < even * 0.5 || share > even * 1.5)) {
                        imbalance.add(n.get("node") + " takes " + Math.round(share * 100) + "% of traffic (even share: "
                                + Math.round(even * 100) + "%)");
                    }
                }
            }
            List<Map<String, Object>> serviceList = new ArrayList<>();
            services.forEach((name, v) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("service", name);
                m.put("requests", (long) v[0]);
                m.put("4xx", (long) v[1]);
                m.put("5xx", (long) v[2]);
                m.put("errorRatio5xx", v[0] > 0 ? Math.round(v[2] / v[0] * 10000) / 10000.0 : 0.0);
                m.put("avgLatencyMs", v[0] > 0 ? Math.round(v[3] / v[0] * 100) / 100.0 : 0.0);
                serviceList.add(m);
            });
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("minutes", minutes);
            Map<String, Object> cluster = new LinkedHashMap<>();
            cluster.put("requests", clusterRequests);
            cluster.put("4xx", cluster4xx);
            cluster.put("5xx", cluster5xx);
            cluster.put("errorRatio5xx", clusterRequests > 0 ? Math.round((double) cluster5xx / clusterRequests * 10000) / 10000.0 : 0.0);
            cluster.put("avgLatencyMs", clusterRequests > 0 ? Math.round(latencyWeighted / clusterRequests * 100) / 100.0 : 0.0);
            out.put("cluster", cluster);
            out.put("perNode", perNode);
            if (!imbalance.isEmpty()) out.put("loadImbalance", imbalance);
            if (!spikes.isEmpty()) out.put("errorSpikes", spikes);
            out.put("topBy5xx", cap(serviceList.stream().filter(m -> (Long) m.get("5xx") > 0)
                    .sorted(Comparator.comparingLong((Map<String, Object> m) -> (Long) m.get("5xx")).reversed()).toList(), limit));
            // Slowest only among services with enough traffic for the average to mean something.
            out.put("topByLatency", cap(serviceList.stream().filter(m -> (Long) m.get("requests") >= 10)
                    .sorted(Comparator.comparingDouble((Map<String, Object> m) -> (Double) m.get("avgLatencyMs")).reversed()).toList(), limit));
            out.put("topByTraffic", cap(serviceList.stream()
                    .sorted(Comparator.comparingLong((Map<String, Object> m) -> (Long) m.get("requests")).reversed()).toList(), limit));
            List<String> unavailable = historyUnavailable(fetched.results(path));
            if (!unavailable.isEmpty()) out.put("historyUnavailableOn", unavailable);
            return out;
        });
    }

    /** The worst 5xx interval when it stands well above the node's typical interval. */
    private static Map<String, Object> spike(AdminFanOut.NodeResult r) {
        List<JsonNode> points = new ArrayList<>();
        r.body().path("series").forEach(points::add);
        if (points.size() < 4) return null;
        List<Long> values = points.stream().map(p -> p.path("5xx").asLong()).sorted().toList();
        long median = values.get(values.size() / 2);
        JsonNode worst = points.stream().max(Comparator.comparingLong(p -> p.path("5xx").asLong())).orElseThrow();
        long peak = worst.path("5xx").asLong();
        if (peak < 5 || peak < 3 * Math.max(1, median)) return null;
        return Map.of("node", label(r.node()), "at", worst.path("t").asText(), "5xx", peak, "typicalPerInterval", median);
    }

    CompletableFuture<Map<String, Object>> serviceHealth(String serviceId, int minutes, String authorization) {
        String path = historyPath(minutes, serviceId);
        return fanOut(List.of(path), authorization, fetched -> {
            List<Map<String, Object>> perNode = new ArrayList<>();
            List<String> noTraffic = new ArrayList<>();
            long requests = 0, e5xx = 0;
            for (AdminFanOut.NodeResult r : fetched.results(path)) {
                if (!r.ok()) continue;
                JsonNode svc = null;
                for (JsonNode candidate : r.body().path("services")) {
                    if (serviceId.equals(candidate.path("service").asText())) svc = candidate;
                }
                if (svc == null) {
                    noTraffic.add(label(r.node()));
                    continue;
                }
                requests += svc.path("requests").asLong();
                e5xx += svc.path("5xx").asLong();
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("node", label(r.node()));
                n.putAll(pick(svc, "requests", "requestsPerSecond", "2xx", "3xx", "4xx", "5xx", "errorRatio5xx",
                        "avgLatencyMs", "maxLatencyMsSampled"));
                n.put("series", r.body().path("series"));
                perNode.add(n);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("serviceId", serviceId);
            out.put("minutes", minutes);
            out.put("requests", requests);
            out.put("5xx", e5xx);
            out.put("errorRatio5xx", requests > 0 ? Math.round((double) e5xx / requests * 10000) / 10000.0 : 0.0);
            out.put("perNode", perNode);
            if (!noTraffic.isEmpty()) {
                out.put("noTrafficOn", noTraffic);
                out.put("noTrafficHint", "No requests for this service in the window on these nodes: idle, not routed there "
                        + "(see capi_explain_service), or the load balancer is not sending them traffic.");
            }
            List<String> unavailable = historyUnavailable(fetched.results(path));
            if (!unavailable.isEmpty()) out.put("historyUnavailableOn", unavailable);
            return out;
        });
    }

    CompletableFuture<Map<String, Object>> nodeHealth(int minutes, String authorization) {
        String path = historyPath(minutes, null);
        return fanOut(List.of(path), authorization, fetched -> {
            List<AdminFanOut.NodeResult> ok = fetched.results(path).stream().filter(AdminFanOut.NodeResult::ok).toList();
            List<Map<String, Object>> perNode = new ArrayList<>();
            boolean anyFlag = false;
            for (AdminFanOut.NodeResult r : ok) {
                JsonNode latest = r.body().path("jvm").path("latest");
                JsonNode window = r.body().path("jvm").path("window");
                List<String> flags = new ArrayList<>();
                int threads = latest.path("threads").asInt();
                // Compared with the OTHER nodes: including itself, a 2-node cluster's median is the
                // higher value and the outlier would never stand out.
                List<Integer> others = ok.stream().filter(o -> o != r)
                        .map(o -> o.body().path("jvm").path("latest").path("threads").asInt()).sorted().toList();
                if (!others.isEmpty()) {
                    int typical = others.get(others.size() / 2);
                    if (threads > Math.max(typical * 1.5, typical + 20)) {
                        flags.add("threads " + threads + " vs " + typical + " typical on the other nodes");
                    }
                }
                if (latest.path("heapUsedPct").asDouble() > 85) {
                    flags.add("heap " + latest.path("heapUsedPct").asDouble() + "% used");
                }
                long fds = latest.path("openFds").asLong(-1);
                long maxFds = latest.path("maxFds").asLong(-1);
                if (fds >= 0 && maxFds > 0 && fds > 0.8 * maxFds) {
                    flags.add("file descriptors at " + Math.round(fds * 100.0 / maxFds) + "% of the limit");
                }
                if (window.has("openFdsFirst")) {
                    long first = window.path("openFdsFirst").asLong();
                    long last = window.path("openFdsLast").asLong();
                    if (first >= 0 && last - first > Math.max(50, first / 5)) {
                        flags.add("file descriptors grew " + first + " -> " + last + " within the window (leak signal)");
                    }
                    long tFirst = window.path("threadsFirst").asLong();
                    long tLast = window.path("threadsLast").asLong();
                    if (tLast - tFirst > Math.max(20, tFirst / 5)) {
                        flags.add("threads grew " + tFirst + " -> " + tLast + " within the window");
                    }
                }
                if (latest.path("processCpu").asDouble() > 0.85) {
                    flags.add("process CPU " + Math.round(latest.path("processCpu").asDouble() * 100) + "%");
                }
                anyFlag |= !flags.isEmpty();
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("node", label(r.node()));
                n.put("healthy", flags.isEmpty());
                if (!flags.isEmpty()) n.put("flags", flags);
                n.put("latest", latest);
                n.put("window", window);
                perNode.add(n);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("minutes", minutes);
            out.put("allHealthy", !anyFlag && ok.size() == fetched.discovery().nodes().size());
            out.put("perNode", perNode);
            List<String> unavailable = historyUnavailable(fetched.results(path));
            if (!unavailable.isEmpty()) out.put("historyUnavailableOn", unavailable);
            return out;
        });
    }

    CompletableFuture<Map<String, Object>> securityEvents(int minutes, String authorization) {
        String path = historyPath(minutes, null);
        return fanOut(List.of(path), authorization, fetched -> {
            Map<String, Long> cluster = new TreeMap<>();
            List<Map<String, Object>> perNode = new ArrayList<>();
            for (AdminFanOut.NodeResult r : fetched.results(path)) {
                if (!r.ok()) continue;
                Map<String, Long> events = new TreeMap<>();
                r.body().path("security").fields().forEachRemaining(e -> {
                    events.put(e.getKey(), e.getValue().asLong());
                    cluster.merge(e.getKey(), e.getValue().asLong(), Long::sum);
                });
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("node", label(r.node()));
                n.put("events", events);
                perNode.add(n);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("minutes", minutes);
            out.put("total", cluster.values().stream().mapToLong(Long::longValue).sum());
            out.put("cluster", cluster);
            out.put("perNode", perNode);
            out.put("legend", "action=rejected: refused by CAPI; action=observed: seen but allowed (enforcement off)");
            List<String> unavailable = historyUnavailable(fetched.results(path));
            if (!unavailable.isEmpty()) out.put("historyUnavailableOn", unavailable);
            return out;
        });
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> endpointsOf(JsonNode mappingList) {
        List<String> endpoints = new ArrayList<>();
        if (mappingList != null && mappingList.isArray()) {
            for (JsonNode m : mappingList) {
                endpoints.add(m.path("hostname").asText() + ":" + m.path("port").asInt());
            }
        }
        endpoints.sort(Comparator.naturalOrder());
        return endpoints;
    }

    private static JsonNode findInvalid(AdminFanOut.NodeResult result, String serviceId) {
        if (result == null || !result.ok() || !result.body().isArray()) return null;
        for (JsonNode entry : result.body()) {
            if (serviceId.equals(entry.path("serviceId").asText())) return entry;
        }
        return null;
    }

    /** The verdict list (e.g. {@code nonCompliant}) this service appears in, if any. */
    private static String findVerdict(AdminFanOut.NodeResult result, String serviceId) {
        if (result == null || !result.ok()) return null;
        var fields = result.body().fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (!field.getValue().isArray()) continue;
            for (JsonNode entry : field.getValue()) {
                if (serviceId.equals(entry.path("serviceId").asText())) return field.getKey();
            }
        }
        return null;
    }

    private static <T> List<T> cap(List<T> list, int limit) {
        return list.size() > limit ? List.copyOf(list.subList(0, limit)) : list;
    }

    private static String requiredString(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new InvalidToolCall("Missing required argument: " + name);
        }
        return s.trim();
    }

    private static int intArg(Map<String, Object> args, String name, int defaultValue, int min, int max) {
        Object value = args.get(name);
        if (value == null) return defaultValue;
        if (value instanceof Number n) return Math.max(min, Math.min(max, n.intValue()));
        throw new InvalidToolCall("Argument " + name + " must be an integer");
    }
}
