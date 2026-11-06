package io.surisoft.capi.service.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.utils.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Registers this node's admin API in Consul as {@code capi-admin}, so the admin MCP server on any
 * node can find its peers without knowing anything about the platform underneath.
 *
 * <p>Registered on ONE Consul — {@code admin.mcp.consul}, never the catalog hosts: CAPI may read
 * several catalogs owned by others, and must not write into them. The same host is where
 * {@link AdminNodeDirectory} looks for peers. A TTL check keeps the entry honest: {@link #heartbeat()} passes it every {@link #HEARTBEAT_INTERVAL},
 * a node that stops heartbeating drops out of {@code ?passing} after {@link #TTL}, and Consul
 * removes it after {@link #DEREGISTER_AFTER}.
 *
 * <p>All Consul calls are blocking and run on this class's own heartbeat thread or the shutdown
 * hook — never on an Undertow thread, and never on the shared scheduler that runs discovery.
 */
public class AdminNodeRegistration {

    private static final Logger log = LoggerFactory.getLogger(AdminNodeRegistration.class);

    public static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(10);
    static final String TTL = "30s";
    static final String DEREGISTER_AFTER = "2m";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final CAPIConfiguration.HostConfig host;
    /**
     * Looked up on every call, never held: CAPI rebuilds its shared Consul client when the trust store
     * reloads and shuts the old one down, so a captured client fails forever with {@code IOException: closed}.
     */
    private final Supplier<HttpClient> httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String serviceId;
    private final String body;
    /** Whether the registration is known to be live. A failed heartbeat clears it, so the next tick re-registers. */
    private volatile boolean registered;

    /** Meta keys CAPI sets on the registration itself; configured meta may not override them. */
    static final java.util.Set<String> RESERVED_META = java.util.Set.of(
            Constants.CAPI_ADMIN_META_CLUSTER, Constants.CAPI_ADMIN_META_VERSION,
            Constants.CAPI_ADMIN_META_SCHEME, Constants.CAPI_ADMIN_META_MCP_PORT);

    public AdminNodeRegistration(CAPIConfiguration.HostConfig host, Supplier<HttpClient> httpClient,
                                 String serviceId, String cluster, String version, String address,
                                 int adminPort, String scheme, int mcpPort) {
        this(host, httpClient, serviceId, cluster, version, address, adminPort, scheme, mcpPort, Map.of());
    }

    /**
     * @param extraMeta operator-supplied meta ({@code admin.mcp.consul.meta}), e.g. what a validating
     *                  proxy in front of Consul requires
     * @throws IllegalStateException when {@code extraMeta} tries to override a key CAPI sets
     */
    public AdminNodeRegistration(CAPIConfiguration.HostConfig host, Supplier<HttpClient> httpClient,
                                 String serviceId, String cluster, String version, String address,
                                 int adminPort, String scheme, int mcpPort, Map<String, String> extraMeta) {
        for (String key : extraMeta.keySet()) {
            if (RESERVED_META.contains(key)) {
                throw new IllegalStateException("admin.mcp.consul.meta must not set '" + key
                        + "' — CAPI sets it for peer discovery (reserved: " + RESERVED_META + ")");
            }
        }
        this.host = host;
        this.httpClient = httpClient;
        this.serviceId = serviceId;
        this.body = registrationBody(serviceId, cluster, version, address, adminPort, scheme, mcpPort, extraMeta);
    }

    private ScheduledExecutorService heartbeatExecutor;

    public String getServiceId() {
        return serviceId;
    }

    /** Registers now and heartbeats every {@link #HEARTBEAT_INTERVAL} on a dedicated daemon thread. */
    public synchronized void start() {
        if (heartbeatExecutor != null) {
            return;
        }
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "capi-admin-registration");
            t.setDaemon(true);
            return t;
        });
        heartbeatExecutor.scheduleWithFixedDelay(() -> {
            try {
                heartbeat();
            } catch (Throwable t) {
                // Throwable, not Exception: anything escaping here makes the executor cancel the
                // task silently and forever — no registration, no heartbeat, no log.
                log.error("capi-admin registration error on {}", host.getEndpoint(), t);
            }
        }, 0, HEARTBEAT_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
        log.info("capi-admin registration of {} on {} scheduled (every {}s)",
                serviceId, host.getEndpoint(), HEARTBEAT_INTERVAL.toSeconds());
    }

    public synchronized void stop() {
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
        }
    }

    /** Registers if not yet registered, else passes the TTL check. Safe to call repeatedly. */
    public void heartbeat() {
        try {
            if (!registered) {
                register();
            } else if (!pass()) {
                // The agent forgot us (restarted, or the entry was reaped). Re-register now
                // rather than waiting a tick, so the node is not missing for a whole interval.
                registered = false;
                register();
            }
        } catch (InterruptedException e) {
            log.warn("capi-admin registration on {} interrupted", host.getEndpoint());
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            registered = false;
            // toString, not getMessage: several JDK HttpClient exceptions carry a null message.
            log.warn("capi-admin registration on {} failed: {}", host.getEndpoint(), e.toString());
        }
    }

    /** Best-effort removal on shutdown; a node that dies without it ages out through the TTL check. */
    public void deregister() {
        try {
            HttpResponse<String> response = httpClient.get().send(
                    request("/v1/agent/service/deregister/" + serviceId)
                            .PUT(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("capi-admin deregistration on {} returned {}", host.getEndpoint(), response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("capi-admin deregistration on {} failed: {}", host.getEndpoint(), e.getMessage());
        }
        registered = false;
    }

    private void register() throws Exception {
        HttpResponse<String> response = httpClient.get().send(
                request("/v1/agent/service/register")
                        .PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 == 2) {
            registered = true;
            // Pass straight away: a fresh TTL check starts critical, and ?passing would hide this
            // node for a whole heartbeat interval otherwise.
            pass();
            log.info("Registered {} as {} on {}", serviceId, Constants.CAPI_ADMIN_SERVICE_NAME, host.getEndpoint());
        } else {
            log.warn("capi-admin registration on {} returned {} — does the Consul token have service:write on {}?",
                    host.getEndpoint(), response.statusCode(), Constants.CAPI_ADMIN_SERVICE_NAME);
        }
    }

    private boolean pass() throws Exception {
        HttpResponse<String> response = httpClient.get().send(
                request("/v1/agent/check/pass/service:" + serviceId)
                        .PUT(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        return response.statusCode() / 100 == 2;
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(host.getEndpoint()) + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json");
        if (host.getToken() != null && !host.getToken().isEmpty()) {
            builder.header(Constants.AUTHORIZATION_HEADER,
                    Constants.BEARER + host.getToken().replaceAll("(\r\n|\n)", ""));
        }
        return builder;
    }

    String registrationBody(String serviceId, String cluster, String version, String address,
                            int adminPort, String scheme, int mcpPort, Map<String, String> extraMeta) {
        // Operator meta first, CAPI's own keys after (reserved keys are rejected in the constructor).
        Map<String, Object> meta = new LinkedHashMap<>(extraMeta);
        meta.put(Constants.CAPI_ADMIN_META_CLUSTER, cluster);
        meta.put(Constants.CAPI_ADMIN_META_VERSION, version != null ? version : "unknown");
        meta.put(Constants.CAPI_ADMIN_META_SCHEME, scheme);
        meta.put(Constants.CAPI_ADMIN_META_MCP_PORT, String.valueOf(mcpPort));

        Map<String, Object> check = new LinkedHashMap<>();
        check.put("TTL", TTL);
        check.put("DeregisterCriticalServiceAfter", DEREGISTER_AFTER);

        // No tags, and no routing meta from CAPI. An operator may add "group" through
        // admin.mcp.consul.meta (a validating proxy may require it): this version drops capi-admin
        // by NAME before reading any meta, so it stays unroutable here.
        Map<String, Object> registration = new LinkedHashMap<>();
        registration.put("ID", serviceId);
        registration.put("Name", Constants.CAPI_ADMIN_SERVICE_NAME);
        registration.put("Address", address);
        registration.put("Port", adminPort);
        registration.put("Meta", meta);
        registration.put("Check", check);
        try {
            return objectMapper.writeValueAsString(registration);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise capi-admin registration", e);
        }
    }

    /** {@code capi.instanceName}, or {@code default} — compared by exact equality, so any characters work. */
    public static String clusterName(String instanceName) {
        return instanceName == null || instanceName.isBlank() ? Constants.CAPI_ADMIN_DEFAULT_CLUSTER : instanceName.trim();
    }

    /** {@code capi-admin-<cluster>-<host>}, reduced to the characters Consul accepts in a service ID path segment. */
    public static String serviceId(String cluster, String hostName) {
        String raw = Constants.CAPI_ADMIN_SERVICE_NAME + "-" + cluster + "-" + hostName;
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public static String localHostName() {
        String fromEnv = System.getenv("HOSTNAME");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }

    /** The configured advertise address, else this host's resolved address (a pod IP on Kubernetes). */
    public static String resolveAdvertiseAddress(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            log.warn("Could not resolve this host's address for capi-admin registration; falling back to 127.0.0.1. "
                    + "Set capi.admin.mcp.advertiseAddress so peers can reach this node.");
            return "127.0.0.1";
        }
    }

    static String stripTrailingSlash(String endpoint) {
        return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    }
}
