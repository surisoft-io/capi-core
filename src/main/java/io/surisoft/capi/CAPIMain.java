package io.surisoft.capi;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.service.McpBackendLoadBalancer;
import io.surisoft.capi.tracer.CapiTracer;
import io.surisoft.capi.undertow.*;
import io.surisoft.capi.utils.Constants;
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
    private io.surisoft.capi.service.McpTrustStore mcpTrustStore;

    public static void main(String[] args) {
        new CAPIMain();
    }

    public CAPIMain() {
        capiConfiguration = loadConfiguration();

        log.info("Starting CAPI Gateway");

        Startup startup = new Startup(capiConfiguration);
        startup.start();

        try {
            Map<String, String> managedHeaders = buildManagedHeaders();

            WebsocketGateway websocketGateway = getWebsocketGateway(startup);
            GrpcGateway grpcGateway = getGrpcGateway(startup);
            AdminGateway adminGateway = configureAdminGateway(startup);
            McpGateway mcpGateway = getMcpGateway(startup);
            RestGateway restGateway = getRestGateway(startup, managedHeaders);

            ScheduledExecutorService scheduler = startSchedulers(startup);

            registerShutdownHook(websocketGateway, grpcGateway, mcpGateway, restGateway, scheduler, adminGateway, startup);

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
        AdminGateway adminGateway = new AdminGateway(capiConfiguration.getAdminPort(), startup.getPrometheusRegistry(), capiConfiguration, startup.getServiceCache(), startup.getUndertowSslContext(), startup.getCapiTrustManager(), startup.getInvalidServiceMap());
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
        if(startup.getConsulStore() != null) {
            adminGateway.setConsulStore(startup.getConsulStore());
        }
        if(startup.getJvmObservability() != null) {
            adminGateway.setJvmObservability(startup.getJvmObservability());
        }
        adminGateway.start();
        return adminGateway;
    }

    private static void registerShutdownHook(@Nullable WebsocketGateway websocketGateway, @Nullable GrpcGateway grpcGateway, @Nullable McpGateway mcpGateway, @Nullable RestGateway restGateway, ScheduledExecutorService scheduler, AdminGateway adminGateway, Startup startup) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down CAPI Gateway...");
            if(websocketGateway != null) websocketGateway.stop();
            if(grpcGateway != null) grpcGateway.stop();
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
            if (startup.getOpaWasmService() != null) gateway.setOpaWasmService(startup.getOpaWasmService());
            if (startup.getThrottleProcessor() != null) gateway.setThrottleProcessor(startup.getThrottleProcessor());
            if (startup.getApiKeyCache() != null) gateway.setApiKeyCache(startup.getApiKeyCache());
            if (startup.getOpenTelemetryTracer() != null) {
                gateway.setRestTracer(new CapiTracer(
                        startup.getOpenTelemetryTracer(),
                        startup.getHttpUtils(),
                        startup.getServiceCache(),
                        capiConfiguration.getInstanceName()
                ));
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
            gateway.runProxy();
            return gateway;
        }
        return null;
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