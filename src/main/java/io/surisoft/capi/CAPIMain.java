package io.surisoft.capi;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.service.McpBackendLoadBalancer;
import io.surisoft.capi.tracer.CapiTracer;
import io.surisoft.capi.undertow.*;
import io.surisoft.capi.utils.CorsPolicy;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.PathBlocklist;
import io.surisoft.capi.utils.Startup;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CAPIMain {

    private static Logger log;
    private final CAPIConfiguration capiConfiguration;
    private final PathBlocklist pathBlocklist;
    private io.surisoft.capi.service.McpTrustStore mcpTrustStore;
    private io.surisoft.capi.service.admin.AdminNodeRegistration adminNodeRegistration;

    public static void main(String[] args) {
        new CAPIMain();
    }

    public CAPIMain() {
        capiConfiguration = loadConfiguration();
        // Built before anything starts: an invalid rule must stop startup, not be skipped.
        pathBlocklist = PathBlocklist.of(capiConfiguration.getBlockedPaths());

        log.info("Starting CAPI Gateway");
        if (!pathBlocklist.isEmpty()) {
            log.warn("capi.blockedPaths is active — requests matching {} get 404 on the REST, WebSocket and WebDAV listeners",
                    pathBlocklist.describe());
        }

        Startup startup = new Startup(capiConfiguration);
        startup.start();

        try {
            warnOnInertCorsConfig();
            Map<String, String> managedHeaders = buildManagedHeaders();

            WebsocketGateway websocketGateway = getWebsocketGateway(startup);
            GrpcGateway grpcGateway = getGrpcGateway(startup);
            WebDavGateway webDavGateway = getWebDavGateway(startup);
            AdminGateway adminGateway = configureAdminGateway(startup);
            AdminMcpGateway adminMcpGateway = getAdminMcpGateway(startup);
            McpGateway mcpGateway = getMcpGateway(startup);
            RestGateway restGateway = getRestGateway(startup, managedHeaders);

            ScheduledExecutorService scheduler = startSchedulers(startup);

            registerShutdownHook(websocketGateway, grpcGateway, webDavGateway, mcpGateway, restGateway, scheduler, adminGateway, startup);
            registerAdminMcpShutdownHook(adminMcpGateway, adminNodeRegistration);

            log.info("CAPI Gateway started successfully.");
            Thread.currentThread().join();

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static CAPIConfiguration loadConfiguration() {
        String configurationPath = System.getenv().get("CAPI_CONFIG_FILE");
        if(configurationPath == null) {
            throw new RuntimeException("CAPI_CONFIG_FILE environment variable is not set");
        }
        Yaml yaml = new Yaml();
        try (InputStream inputStream = new FileInputStream(configurationPath)) {
            Map<String, Object> root = yaml.load(inputStream);

            LoaderOptions options = new LoaderOptions();
            Constructor constructor = new Constructor(CAPIConfiguration.class, options);
            Yaml capiYaml = new Yaml(constructor);
            CAPIConfiguration config = capiYaml.load(yaml.dump(root.get("capi")));
            if(config.getConsulHosts() == null || config.getConsulHosts().isEmpty()) {
                throw new RuntimeException("Failed to start CAPI, it needs at least one Consul instance.");
            } else if(config.getConsulHosts().getFirst().getEndpoint() == null || config.getConsulHosts().getFirst().getEndpoint().isEmpty()) {
                throw new RuntimeException("Failed to start CAPI, it needs at least one Consul instance.");
            }

            initializeLogs(config);
            return config;
        } catch (IOException e) {
            log.error(e.getMessage(), e);
            throw new RuntimeException("Failed to load CAPI Configuration File");
        }
    }

    /**
     * Warns when origins are configured but the master switch is off, so "I set allowedOrigins and
     * nothing happens" shows up in the log rather than in a debugging session.
     */
    private void warnOnInertCorsConfig() {
        if (!capiConfiguration.isCorsEnabled()
                && capiConfiguration.getAllowedOrigins() != null
                && !capiConfiguration.getAllowedOrigins().isEmpty()) {
            log.warn("capi.allowedOrigins is set but capi.corsEnabled is false — no CORS header will "
                   + "be sent, and any per-service allowed-origins metadata is ignored too. "
                   + "Set capi.corsEnabled: true to activate them.");
        }
    }

    private Map<String, String> buildManagedHeaders() {
        Map<String, String> managedHeaders = new HashMap<>(Constants.CAPI_CORS_MANAGED_HEADERS);
        if(capiConfiguration.getAllowedHeaders() != null && !capiConfiguration.getAllowedHeaders().isEmpty()) {
            if(capiConfiguration.getOauth2() != null && capiConfiguration.getOauth2().getCookieName() != null && !capiConfiguration.getOauth2().getCookieName().isEmpty()) {
                capiConfiguration.getAllowedHeaders().add(capiConfiguration.getOauth2().getCookieName());
            }
            managedHeaders.put("Access-Control-Allow-Headers", String.join(",", capiConfiguration.getAllowedHeaders()));
        }
        return managedHeaders;
    }

    private AdminGateway configureAdminGateway(Startup startup) {
        // The whole admin block is optional in config.yaml, so a missing section reads as the
        // defaults (unprotected) rather than an NPE at startup.
        CAPIConfiguration.Admin admin = capiConfiguration.getAdmin() != null
                ? capiConfiguration.getAdmin()
                : new CAPIConfiguration.Admin();
        boolean adminProtected = admin.isProtectedEnabled();
        if (adminProtected && (admin.getGroup() == null || admin.getGroup().isBlank())) {
            // Starting here would 403 every admin call, including an operator's own, with no hint
            // as to why. The intent is explicit and contradictory, so fail loudly rather than
            // quietly serving the listener open.
            throw new IllegalStateException(
                    "admin.protected is true but admin.group is not set — set capi.admin.group to the "
                  + "subscription group allowed on the admin listener, or set admin.protected: false "
                  + "to opt out (read-only; trust store changes stay refused).");
        }
        if (admin.isUnset()) {
            // No admin block at all: keep the pre-2.22 behaviour rather than refusing to start and
            // crash-looping a deployment that upgraded without touching its config.
            log.warn("No capi.admin block configured — the admin listener on port {} is "
                   + "UNAUTHENTICATED and exposes /info/routes, /info/mcp/sessions and /info/jvm/*. "
                   + "Set capi.admin.protected: true with capi.admin.group to require a token. "
                   + "Trust store mutation is refused on an unprotected listener either way.",
                    capiConfiguration.getAdminPort());
        } else if (!adminProtected) {
            log.warn("Admin listener on port {} is UNAUTHENTICATED (admin.protected: false). "
                   + "Restrict it at the network layer; trust store mutation is refused on this listener.",
                    capiConfiguration.getAdminPort());
        }
        AdminGateway adminGateway = new AdminGateway(capiConfiguration.getAdminPort(), startup.getPrometheusRegistry(), capiConfiguration, startup.getServiceCache(), startup.getUndertowSslContext(), startup.getCapiTrustManager(), startup.getInvalidServiceMap(), adminProtected, admin.getGroup(), startup.getHttpUtils());
        if(startup.getWebSocketClientMap() != null) {
            adminGateway.setWebsocketClients(startup.getWebSocketClientMap());
        }
        if(startup.getMcpToolRegistry() != null) {
            adminGateway.setMcpToolRegistry(startup.getMcpToolRegistry());
        }
        if(startup.getMcpSessionStore() != null) {
            adminGateway.setMcpSessionStore(startup.getMcpSessionStore());
        }
        adminGateway.setRestClients(startup.getRestClientMap());
        adminGateway.setWebDavClients(startup.getWebDavClientMap());
        adminGateway.setSpecComplianceSource(startup.getConsulCatalogService(),
                capiConfiguration.getMatchOpenApiSpec() != null && capiConfiguration.getMatchOpenApiSpec().isEnabled());
        if(startup.getConsulStore() != null) {
            adminGateway.setConsulStore(startup.getConsulStore());
        }
        if(startup.getJvmObservability() != null) {
            adminGateway.setJvmObservability(startup.getJvmObservability());
        }
        if(startup.getOauth2Provider() != null) {
            adminGateway.setOauth2Provider(startup.getOauth2Provider());
        }
        if (admin.getMcp() != null && admin.getMcp().isEnabled() && startup.getPrometheusRegistry() != null) {
            // Each node keeps a short history of its own metrics for the admin MCP metrics tools.
            CAPIConfiguration.AdminMcp.History h = admin.getMcp().getHistory() != null
                    ? admin.getMcp().getHistory() : new CAPIConfiguration.AdminMcp.History();
            io.surisoft.capi.observability.MetricsHistory history = new io.surisoft.capi.observability.MetricsHistory(
                    startup.getPrometheusRegistry(),
                    java.time.Duration.ofSeconds(Math.max(5, h.getIntervalSeconds())),
                    java.time.Duration.ofMinutes(Math.max(1, h.getRetentionMinutes())));
            history.start();
            adminGateway.setMetricsHistory(history);
        }
        adminGateway.start();
        return adminGateway;
    }

    /**
     * Admin MCP: a read-only MCP server over the admin API that fans out to every CAPI node of this
     * cluster, found through the node's own {@code capi-admin} registration in Consul.
     * See ADMIN-MCP-DESIGN.md.
     */
    private @Nullable AdminMcpGateway getAdminMcpGateway(Startup startup) {
        CAPIConfiguration.Admin admin = capiConfiguration.getAdmin();
        if (admin == null || admin.getMcp() == null || !admin.getMcp().isEnabled()) {
            return null;
        }
        if (!admin.isProtectedEnabled()) {
            // Explicit opt-in to a cluster-wide view with no auth in front of it is a contradiction;
            // fail like admin.protected without a group does, rather than serve it open.
            throw new IllegalStateException(
                    "admin.mcp.enabled is true but admin.protected is not — the admin MCP server requires "
                  + "capi.admin.protected: true and capi.admin.group.");
        }
        CAPIConfiguration.AdminMcp cfg = admin.getMcp();
        CAPIConfiguration.AdminConsul adminConsul = cfg.getConsul();
        if (adminConsul == null || adminConsul.getEndpoint() == null || adminConsul.getEndpoint().isBlank()) {
            // No default from consulHosts: CAPI may read several catalogs owned by others, and
            // picking one to write into would be a guess.
            throw new IllegalStateException(
                    "admin.mcp.enabled is true but admin.mcp.consul.endpoint is not set — set the Consul "
                  + "this node registers in as 'capi-admin' and finds its peers in (token needs service:write on capi-admin).");
        }
        java.time.Duration nodeTimeout = java.time.Duration.ofMillis(Math.max(500, cfg.getNodeTimeoutMs()));
        String cluster = io.surisoft.capi.service.admin.AdminNodeRegistration.clusterName(capiConfiguration.getInstanceName());
        String serviceId = io.surisoft.capi.service.admin.AdminNodeRegistration.serviceId(
                cluster, io.surisoft.capi.service.admin.AdminNodeRegistration.localHostName());
        String address = io.surisoft.capi.service.admin.AdminNodeRegistration.resolveAdvertiseAddress(cfg.getAdvertiseAddress());
        String scheme = startup.getUndertowSslContext() != null ? "https" : "http";

        adminNodeRegistration = new io.surisoft.capi.service.admin.AdminNodeRegistration(
                adminConsul, startup::getConsulHttpClient, serviceId, cluster,
                capiConfiguration.getVersion(), address, capiConfiguration.getAdminPort(), scheme, cfg.getPort(),
                adminConsul.metaAsMap());

        io.surisoft.capi.service.admin.AdminNode self = new io.surisoft.capi.service.admin.AdminNode(
                serviceId, address, capiConfiguration.getAdminPort(), scheme, true);
        io.surisoft.capi.service.admin.AdminNodeDirectory directory = new io.surisoft.capi.service.admin.AdminNodeDirectory(
                adminConsul, startup::getConsulHttpClient, cluster, self, nodeTimeout);
        io.surisoft.capi.service.admin.AdminFanOut fanOut =
                new io.surisoft.capi.service.admin.AdminFanOut(peerHttpClient(startup, cfg, scheme, address), nodeTimeout);
        AdminMcpGateway gateway = new AdminMcpGateway(cfg.getPort(), startup.getUndertowSslContext(),
                new io.surisoft.capi.service.admin.AdminMcpTools(directory, fanOut),
                startup.getHttpUtils(), admin.getGroup(), capiConfiguration);
        gateway.start();
        // Own thread, not the shared scheduler: a slow Consul agent must not delay discovery.
        adminNodeRegistration.start();
        log.info("Admin MCP node {} (cluster {}, advertised {}://{}:{}) — Consul registration runs on thread capi-admin-registration",
                serviceId, cluster, scheme, address, capiConfiguration.getAdminPort());
        return gateway;
    }

    /**
     * The client the admin MCP uses to call peers' admin APIs. Over HTTPS peers are dialled by IP, so
     * with {@code admin.mcp.peerTlsName} set the certificate is checked against that name instead of
     * the IP (chain still validated by CAPI's trust store). Otherwise the shared client, with standard
     * hostname verification — which fails for an IP unless the certificate carries that exact IP.
     */
    private java.util.function.Supplier<java.net.http.HttpClient> peerHttpClient(Startup startup, CAPIConfiguration.AdminMcp cfg, String scheme, String address) {
        String peerTlsName = cfg.getPeerTlsName() == null ? "" : cfg.getPeerTlsName().trim();
        if (!"https".equals(scheme)) {
            if (!peerTlsName.isEmpty()) {
                log.info("admin.mcp.peerTlsName is ignored: the admin listener is plain HTTP");
            }
            // Per call, never captured: the shared client is replaced on trust-store reload.
            return startup::getConsulHttpClient;
        }
        if (peerTlsName.isEmpty()) {
            if (address.matches("[0-9.]+") || address.contains(":")) {
                log.warn("Admin listener is HTTPS and peers are reached by IP ({}), but admin.mcp.peerTlsName is not set — "
                       + "standard hostname verification only accepts an IP SAN for that exact IP, so every node "
                       + "(this one included) will show as unreachable. Set admin.mcp.peerTlsName to the DNS name "
                       + "in the admin certificate.", address);
            }
            return startup::getConsulHttpClient;
        }
        log.info("Admin MCP verifies peer certificates against '{}' (chain: {})", peerTlsName,
                startup.getCapiTrustManager() != null ? "CAPI trust store" : "JVM default trust store");
        // Built once and kept: it is ours (never shut down by a reload), and its trust manager reads
        // CapiTrustManager live, which reloads in place.
        java.net.http.HttpClient peerClient = io.surisoft.capi.service.admin.AdminFanOut.peerClient(
                startup.getCapiTrustManager(), peerTlsName, java.time.Duration.ofSeconds(10));
        return () -> peerClient;
    }

    private static void registerAdminMcpShutdownHook(@Nullable AdminMcpGateway gateway,
                                                     io.surisoft.capi.service.admin.@Nullable AdminNodeRegistration registration) {
        if (gateway == null && registration == null) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Deregister first so peers stop fanning out to a node that is going away.
            if (registration != null) {
                registration.stop();
                registration.deregister();
            }
            if (gateway != null) gateway.stop();
        }));
    }

    private static void registerShutdownHook(@Nullable WebsocketGateway websocketGateway, @Nullable GrpcGateway grpcGateway, @Nullable WebDavGateway webDavGateway, @Nullable McpGateway mcpGateway, @Nullable RestGateway restGateway, ScheduledExecutorService scheduler, AdminGateway adminGateway, Startup startup) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down CAPI Gateway...");
            if(websocketGateway != null) websocketGateway.stop();
            if(grpcGateway != null) grpcGateway.stop();
            if(webDavGateway != null) webDavGateway.stop();
            if(mcpGateway != null) mcpGateway.stop();
            if(restGateway != null) restGateway.stop();
            scheduler.shutdownNow();
            adminGateway.stop();
            if(startup.getJvmObservability() != null) startup.getJvmObservability().stop();
            // Last: gateways are stopped, so this flushes every span they produced. BatchSpanProcessor
            // queues spans off-thread, so without this close() anything still queued at shutdown is lost.
            if(startup.getOpenTelemetrySdk() != null) {
                try {
                    startup.getOpenTelemetrySdk().close();
                } catch (Exception e) {
                    log.warn("Failed to flush OpenTelemetry spans on shutdown", e);
                }
            }
            log.info("CAPI Gateway stopped.");
        }));
    }

    private ScheduledExecutorService startSchedulers(Startup startup) {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        long interval = capiConfiguration.getConsulCatalogDiscoverInterval();

        // Consul service discovery
        scheduler.scheduleAtFixedRate(() -> {
            try {
                startup.getConsulCatalogService().runCycle();
                if (startup.getMcpServerClient() != null) {
                    startup.getMcpServerClient().refreshMcpServerTools();
                }
            } catch (Exception e) {
                log.error("Consul discovery error: {}", e.getMessage());
            }
        }, 0, interval, TimeUnit.MILLISECONDS);

        // Consul KV store
        if (capiConfiguration.getConsulStore().isEnabled() && startup.getConsulStore() != null) {
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    startup.getConsulStore().process();
                } catch (Exception e) {
                    log.error("Consul store error: {}", e.getMessage());
                }
            }, 0, interval, TimeUnit.MILLISECONDS);
        }

        // MCP manifest trust keys (only registered if signing is enabled)
        if (mcpTrustStore != null) {
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    mcpTrustStore.process();
                } catch (Exception e) {
                    log.error("MCP trust store refresh error: {}", e.getMessage());
                }
            }, interval, interval, TimeUnit.MILLISECONDS);
        }

        // API key store
        if (startup.getApiKeyStore() != null) {
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    startup.getApiKeyStore().process();
                } catch (Exception e) {
                    log.error("API key store error: {}", e.getMessage());
                }
            }, 0, interval, TimeUnit.MILLISECONDS);
        }

        // Route consistency checker
        scheduler.scheduleAtFixedRate(() -> {
            try {
                startup.getRouteConsistencyChecker().process();
            } catch (Exception e) {
                log.error("Consistency checker error: {}", e.getMessage());
            }
        }, 60, 60, TimeUnit.SECONDS);

        // OPA Wasm bundle polling (initial delay lets Consul discovery register policies first)
        if (startup.getOpaWasmService() != null) {
            int pollSeconds = capiConfiguration.getOpa().getWasmBundlePollIntervalSeconds();
            long initialDelaySeconds = Math.max(5, (interval / 1000) + 2);
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    startup.getOpaWasmService().pollBundles();
                } catch (Exception e) {
                    log.error("OPA Wasm bundle poll error: {}", e.getMessage());
                }
            }, initialDelaySeconds, pollSeconds, TimeUnit.SECONDS);
        }

        log.info("Schedulers started (consul interval: {}ms)", interval);
        return scheduler;
    }

    private @Nullable RestGateway getRestGateway(Startup startup, Map<String, String> managedHeaders) {
        if (capiConfiguration.getRest().isEnabled()) {
            List<String> allowedHeaders = capiConfiguration.getAllowedHeaders() != null ? capiConfiguration.getAllowedHeaders() : new ArrayList<>();
            String cookieName = capiConfiguration.getOauth2() != null && capiConfiguration.getOauth2().getCookieName() != null ? capiConfiguration.getOauth2().getCookieName() : "";
            RestGateway gateway = new RestGateway(
                    capiConfiguration.getRest().getPort(),
                    capiConfiguration.getRest().getIoThreads(),
                    capiConfiguration.getRest().getContextPath(),
                    startup.getRestClientMap(),
                    startup.getHttpUtils(),
                    startup.getServiceCache(),
                    startup.getUndertowSslContext(),
                    allowedHeaders,
                    cookieName
            );
            gateway.setCorsEnabled(capiConfiguration.isCorsEnabled());
            gateway.setRejectDotSegments(capiConfiguration.getRest().isRejectDotSegments());
            gateway.setPathBlocklist(pathBlocklist);
            gateway.setMaxRequestSize(capiConfiguration.getRest().getMaxRequestSize());
            gateway.setCorsPolicy(new CorsPolicy(capiConfiguration.getAllowedOrigins()));
            if (startup.getOpaWasmService() != null) gateway.setOpaWasmService(startup.getOpaWasmService());
            if (startup.getThrottleProcessor() != null) gateway.setThrottleProcessor(startup.getThrottleProcessor());
            if (startup.getApiKeyCache() != null) gateway.setApiKeyCache(startup.getApiKeyCache());
            if (startup.getOpenTelemetryTracer() != null) {
                CapiTracer restTracer = new CapiTracer(
                        startup.getOpenTelemetryTracer(),
                        startup.getHttpUtils(),
                        startup.getServiceCache(),
                        capiConfiguration.getInstanceName()
                );
                restTracer.setTraceSubject(capiConfiguration.getTraces() != null
                        && capiConfiguration.getTraces().isTraceSubject());
                gateway.setRestTracer(restTracer);
            }
            if (startup.getWebsocketUtils() != null) {
                gateway.setWebsocketUtils(startup.getWebsocketUtils());
            }
            if (capiConfiguration.getReverseProxyHost() != null && !capiConfiguration.getReverseProxyHost().isEmpty()) {
                gateway.setReverseProxyHost(capiConfiguration.getReverseProxyHost());
            }
            String publicEndpoint = capiConfiguration.getPublicEndpoint();
            if (publicEndpoint != null && !publicEndpoint.isEmpty()) {
                try {
                    String scheme = java.net.URI.create(publicEndpoint).getScheme();
                    if (scheme != null) {
                        gateway.setPublicEndpointScheme(scheme);
                    }
                } catch (IllegalArgumentException e) {
                    log.warn("Invalid capi.publicEndpoint, ignoring for X-Forwarded-Proto: {}", publicEndpoint);
                }
                gateway.setPublicEndpoint(publicEndpoint);
            }
            gateway.setMeterRegistry(startup.getPrometheusRegistry());
            gateway.setRestClientSnapshot(startup.getRestClientSnapshot());
            gateway.runProxy();
            return gateway;
        }
        return null;
    }

    private @Nullable WebsocketGateway getWebsocketGateway(Startup startup) {
        WebsocketGateway websocketGateway = null;
        if(capiConfiguration.getWebsocket().isEnabled()
                && capiConfiguration.getWebsocket().getContextPath() != null
                && !capiConfiguration.getWebsocket().getContextPath().isEmpty()) {
            List<String> allowedHeaders = capiConfiguration.getAllowedHeaders() != null ? capiConfiguration.getAllowedHeaders() : new ArrayList<>();
            String cookieName = capiConfiguration.getOauth2() != null && capiConfiguration.getOauth2().getCookieName() != null ? capiConfiguration.getOauth2().getCookieName() : "";
            websocketGateway = new WebsocketGateway(capiConfiguration.getWebsocket().getPort(), capiConfiguration.getWebsocket().getIoThreads(), startup.getWebSocketClientMap(), startup.getWebsocketUtils(), startup.getUndertowSslContext(), allowedHeaders, cookieName);
            websocketGateway.setCorsPolicy(new CorsPolicy(capiConfiguration.getAllowedOrigins()));
            websocketGateway.setEnforceOriginCheck(capiConfiguration.getWebsocket().isEnforceOriginCheck());
            websocketGateway.setMeterRegistry(startup.getPrometheusRegistry());
            websocketGateway.setPathBlocklist(pathBlocklist);
            websocketGateway.setHttpUtils(startup.getHttpUtils());
            websocketGateway.setOpaWasmService(startup.getOpaWasmService());
            websocketGateway.runProxy();
        }
        return websocketGateway;
    }

    private @Nullable GrpcGateway getGrpcGateway(Startup startup) {
        if(capiConfiguration.getGrpc() != null
                && capiConfiguration.getGrpc().isEnabled()
                && startup.getGrpcUtils() != null) {
            GrpcGateway gateway = new GrpcGateway(
                    capiConfiguration.getGrpc().getPort(),
                    startup.getGrpcClientMap(),
                    startup.getUndertowSslContext()
            );
            gateway.setHttpUtils(startup.getHttpUtils());
            gateway.setOpaWasmService(startup.getOpaWasmService());
            gateway.setMaxRequestSize(capiConfiguration.getGrpc().getMaxRequestSize());
            gateway.runProxy();
            return gateway;
        }
        return null;
    }

    /**
     * WebDAV listener. Its own port, and — unlike the other transports — a {@code 401} challenge on a
     * missing credential rather than {@code 403}, because a {@code 403} does not make Explorer or
     * Finder prompt. See WEBDAV-DESIGN.md.
     */
    private @Nullable WebDavGateway getWebDavGateway(Startup startup) {
        if(capiConfiguration.getWebdav() != null
                && capiConfiguration.getWebdav().isEnabled()
                && startup.getWebDavUtils() != null) {
            WebDavGateway gateway = new WebDavGateway(
                    capiConfiguration.getWebdav().getPort(),
                    startup.getWebDavClientMap(),
                    startup.getUndertowSslContext(),
                    capiConfiguration.getWebdav()
            );
            gateway.setHttpUtils(startup.getHttpUtils());
            gateway.setOpaWasmService(startup.getOpaWasmService());
            gateway.setMeterRegistry(startup.getPrometheusRegistry());
            gateway.setPathBlocklist(pathBlocklist);
            configureWebDavStores(gateway);
            gateway.runProxy();
            return gateway;
        }
        return null;
    }

    /**
     * Ticket and session stores, local or cluster-wide.
     *
     * <p>Per-process is fine on a single node. Behind a load balancer it is not: a ticket is minted by
     * the web application and redeemed by the document client, so the two halves routinely land on
     * different nodes and the ticket is unknown.
     */
    private void configureWebDavStores(WebDavGateway gateway) {
        CAPIConfiguration.WebDav webdav = capiConfiguration.getWebdav();
        boolean distributed = webdav.isDistributedSessions();

        if (distributed) {
            gateway.setSessionStore(new io.surisoft.capi.service.HazelcastWebDavSessionStore(
                    io.surisoft.capi.configuration.HazelcastCacheConfiguration
                            .createWebDavSessionMap(capiConfiguration.getThrottle()),
                    webdav.getSessionTtlSeconds(), webdav.getMaxSessions()));
        } else {
            gateway.setSessionStore(new io.surisoft.capi.service.LocalWebDavSessionStore(
                    webdav.getSessionTtlSeconds(), webdav.getMaxSessions()));
        }

        if (webdav.getTicket() != null && webdav.getTicket().isEnabled()) {
            if (distributed) {
                gateway.setTicketStore(new io.surisoft.capi.service.HazelcastWebDavTicketStore(
                        io.surisoft.capi.configuration.HazelcastCacheConfiguration
                                .createWebDavTicketMap(capiConfiguration.getThrottle()),
                        webdav.getTicket().getTtlSeconds(), webdav.getTicket().getMaxOutstanding()));
            } else {
                gateway.setTicketStore(new io.surisoft.capi.service.LocalWebDavTicketStore(
                        webdav.getTicket().getTtlSeconds(), webdav.getTicket().getMaxOutstanding()));
            }
        }
        log.info("WebDAV ticket/session stores are {}", distributed ? "CLUSTER-WIDE (Hazelcast)" : "local to this node");
    }

    private @Nullable McpGateway getMcpGateway(Startup startup) {
        if(capiConfiguration.getMcp() != null && capiConfiguration.getMcp().isEnabled()
                && startup.getMcpToolRegistry() != null
                && startup.getMcpSessionStore() != null) {
            // Share the consulHttpClient so MCP OpenAPI-promoted tool dispatch uses
            // the same trust material as every other JDK-HttpClient consumer. A
            // freshly-built HttpClient here would default to JVM cacerts and silently
            // fail PKIX validation against internal-CA backends.
            java.net.http.HttpClient mcpHttpClient = startup.getConsulHttpClient();
            McpBackendLoadBalancer loadBalancer = startup.getMcpLoadBalancer() != null
                    ? startup.getMcpLoadBalancer()
                    : new McpBackendLoadBalancer(capiConfiguration.getMcp().getCircuitBreakerCooldownMs());
            McpGateway mcpGateway = new McpGateway(
                    capiConfiguration.getMcp().getPort(),
                    startup.getUndertowSslContext(),
                    startup.getMcpToolRegistry(),
                    startup.getHttpUtils(),
                    startup.getOpaWasmService(),
                    mcpHttpClient,
                    startup.getMcpSessionStore(),
                    capiConfiguration,
                    loadBalancer,
                    startup.getMcpServerClient()
            );
            // Resources / Prompts passthrough — only meaningful when at least one
            // upstream MCP server is registered (mcp-type=server). Wiring here is
            // unconditional; the registries return empty when no such service exists.
            if (startup.getMcpServerClient() != null && startup.getServiceCache() != null) {
                io.surisoft.capi.service.McpResourceRegistry resourceRegistry =
                        new io.surisoft.capi.service.McpResourceRegistry(
                                startup.getServiceCache(), startup.getMcpServerClient());
                resourceRegistry.setDefaultTimeoutMs(capiConfiguration.getMcp().getMcpServerDiscoveryTimeoutMs());
                io.surisoft.capi.service.McpPromptRegistry promptRegistry =
                        new io.surisoft.capi.service.McpPromptRegistry(
                                startup.getServiceCache(), startup.getMcpServerClient());
                promptRegistry.setDefaultTimeoutMs(capiConfiguration.getMcp().getMcpServerDiscoveryTimeoutMs());
                mcpGateway.setResourceRegistry(resourceRegistry);
                mcpGateway.setPromptRegistry(promptRegistry);
            }
            if (startup.getOpenTelemetryTracer() != null
                    && capiConfiguration.getMcp().getObservability() != null
                    && capiConfiguration.getMcp().getObservability().getGenAi() != null
                    && capiConfiguration.getMcp().getObservability().getGenAi().isEnabled()) {
                io.surisoft.capi.tracer.McpTracer mcpTracer = new io.surisoft.capi.tracer.McpTracer(
                        startup.getOpenTelemetryTracer(),
                        capiConfiguration.getInstanceName(),
                        true
                );
                mcpGateway.setMcpTracer(mcpTracer);
                if (startup.getMcpServerClient() != null) {
                    startup.getMcpServerClient().setMcpTracer(mcpTracer);
                }
                log.info("MCP GenAI tracing enabled");
            }

            String signingMode = capiConfiguration.getMcp().getSigning() != null
                    ? capiConfiguration.getMcp().getSigning().getMode() : "off";
            if (signingMode != null && !"off".equalsIgnoreCase(signingMode.trim())
                    && capiConfiguration.getConsulStore() != null
                    && capiConfiguration.getConsulStore().isEnabled()) {
                mcpTrustStore = new io.surisoft.capi.service.McpTrustStore(
                        capiConfiguration.getConsulStore().getEndpoint(),
                        capiConfiguration.getConsulStore().getToken(),
                        startup.getConsulHttpClient()
                );
                mcpTrustStore.process();
                io.surisoft.capi.service.McpManifestVerifier verifier =
                        new io.surisoft.capi.service.McpManifestVerifier(mcpTrustStore);
                startup.getMcpToolRegistry().setManifestVerifier(verifier);
                startup.getMcpToolRegistry().setSigningMode(signingMode);
                log.info("MCP manifest signing mode: {} (trust keys loaded: {})", signingMode, mcpTrustStore.size());
            }
            mcpGateway.start();
            return mcpGateway;
        }
        return null;
    }

    private static void initializeLogs(CAPIConfiguration configuration) {
        if(configuration.getLoggingTraces().isEnabled()) {
            String destination = configuration.getLoggingTraces().getDestination();
            if(destination != null && !destination.isEmpty()) {
                System.setProperty("logging.logback.logs.enabled", Boolean.toString(true));
                System.setProperty("logging.logback.logs.tenant", configuration.getLoggingTraces().getTenant());
                System.setProperty("logging.logback.logs.appName", configuration.getLoggingTraces().getAppName());
                System.setProperty("logging.logback.logs.appEnvironment", configuration.getLoggingTraces().getAppEnvironment());
                System.setProperty("logging.logback.logs.destination", destination);
            }
            if(configuration.getLoggingTraces().getFilePath() != null && !configuration.getLoggingTraces().getFilePath().isEmpty()) {
                System.setProperty("logging.logback.logs.fileEnabled", Boolean.toString(true));
                System.setProperty("logging.logback.logs.filePath", configuration.getLoggingTraces().getFilePath());
            }
        }

        if(configuration.getAccessLogs().isEnabled()) {
            String accessDestination = configuration.getAccessLogs().getDestination();
            if(accessDestination != null && !accessDestination.isEmpty()) {
                System.setProperty("logging.logback.access.enabled", Boolean.toString(true));
                System.setProperty("logging.logback.access.tenant", configuration.getAccessLogs().getTenant());
                System.setProperty("logging.logback.access.service", configuration.getAccessLogs().getService());
                System.setProperty("logging.logback.access.destination", accessDestination);
            }
            if(configuration.getAccessLogs().getFilePath() != null && !configuration.getAccessLogs().getFilePath().isEmpty()) {
                System.setProperty("logging.logback.access.fileEnabled", Boolean.toString(true));
                System.setProperty("logging.logback.access.filePath", configuration.getAccessLogs().getFilePath());
            }
        }

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.reset();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        try {
            configurator.doConfigure(
                    Objects.requireNonNull(CAPIMain.class.getClassLoader().getResource("logback.xml")));
            log = LoggerFactory.getLogger(CAPIMain.class);
            log.info("Logback configuration loaded successfully");
        } catch (JoranException e) {
            throw new RuntimeException("Failed to load logback configuration file");
        }
    }
}