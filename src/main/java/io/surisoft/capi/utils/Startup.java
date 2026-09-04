package io.surisoft.capi.utils;

import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.surisoft.capi.CAPIMain;
import io.surisoft.capi.configuration.*;
import io.surisoft.capi.oidc.Oauth2Provider;
import io.surisoft.capi.processor.*;
import io.surisoft.capi.schema.*;
import io.surisoft.capi.service.*;
import io.surisoft.capi.service.consul.*;
import io.surisoft.capi.configuration.LocalCacheConfiguration;
import io.surisoft.capi.undertow.CAPILoadBalancerProxyClient;
import io.surisoft.capi.tracer.TracingBootstrap;
import jakarta.annotation.Nullable;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.apache.hc.core5.ssl.TrustStrategy;
import org.cache2k.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.*;
import java.net.http.HttpClient;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;

public class Startup {

    private static final Logger log = LoggerFactory.getLogger(CAPIMain.class);
    private final CAPIConfiguration configuration;
    private ServiceUtils serviceUtils;
    private RouteUtils routeUtils;
    private Cache<String, Service> serviceCache;
    private HttpUtils httpUtils;
    private ConsulCatalogService consulCatalogService;
    private ThrottleProcessor throttleProcessor;
    private HttpClient consulHttpClient;

    @Nullable
    private Oauth2Provider oauth2Provider;
    @Nullable
    private List<DefaultJWTProcessor<SecurityContext>> jwtProcessorList;
    @Nullable
    private CapiSslContextHolder capiSslContextHolder;
    @Nullable
    private io.opentelemetry.api.trace.Tracer openTelemetryTracer;
    // Held so the shutdown hook can flush queued spans — BatchSpanProcessor drops them otherwise.
    private io.opentelemetry.sdk.OpenTelemetrySdk openTelemetrySdk;
    @Nullable
    private OpaWasmService opaWasmService;
    @Nullable
    private WebsocketUtils websocketUtils;
    @Nullable
    private GrpcUtils grpcUtils;
    private final Map<String, WebsocketClient> webSocketClientMap = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, RestClient> restClientMap = new java.util.concurrent.ConcurrentHashMap<>();
    private final RestClientSnapshot restClientSnapshot = new RestClientSnapshot();
    private final Map<String, GrpcClient> grpcClientMap = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, InvalidService> invalidServiceMap = new java.util.concurrent.ConcurrentHashMap<>();
    @Nullable
    private io.surisoft.capi.observability.JvmObservability jvmObservability;
    @Nullable
    private SSLContext undertowSslContext;
    @Nullable
    private CapiTrustManager capiTrustManager;
    @Nullable
    private Cache<String, ConsulKeyStoreEntry> consulStoreCache;
    @Nullable
    private ConsulStore consulStore;
    @Nullable
    private Cache<String, ApiKeyStoreEntry> apiKeyCache;
    @Nullable
    private ApiKeyStore apiKeyStore;
    private RouteConsistencyChecker routeConsistencyChecker;
    @Nullable
    private McpToolRegistry mcpToolRegistry;
    @Nullable
    private McpSessionStore mcpSessionStore;
    @Nullable
    private McpServerClient mcpServerClient;
    @Nullable
    private McpBackendLoadBalancer mcpLoadBalancer;

    private CompositeMeterRegistry meterRegistry;
    private PrometheusMeterRegistry prometheusRegistry;

    public Startup(CAPIConfiguration capiConfiguration) {
        this.configuration = capiConfiguration;
    }

    public void start() {
        log.info("Starting CAPI Gateway version {}", configuration.getVersion());
        startMetrics();
        createServiceCache();
        createSslContextHolder();
        startConsulHttpClient();
        configureUndertowSsl();
        startOauth2Service();
        startHttpUtils();
        startTraceService();
        startWebsocketUtils();
        startGrpcUtils();
        createRouteProcessors();
        startRouteUtils();
        startServiceUtils();
        startOpaWasmService();
        startConsulCatalogService();

        if(configuration.getConsulStore().isEnabled()) {
            startConsulStore();
        }
        startApiKeyStore();
        startRouteConsistencyChecker();
        startMcpService();
        startJvmObservability();
    }

    private void startJvmObservability() {
        CAPIConfiguration.Observability.Jvm jvm = configuration.getObservability() != null
                ? configuration.getObservability().getJvm() : null;
        if (jvm == null || !jvm.isEnabled()) {
            log.info("JVM observability disabled");
            return;
        }
        jvmObservability = new io.surisoft.capi.observability.JvmObservability(
                true,
                java.time.Duration.ofMillis(jvm.getSampleIntervalMs()),
                jvm.getRetentionSamples());
        jvmObservability.start();
    }

    private void configureUndertowSsl() {
        CAPIConfiguration.Ssl ssl = configuration.getSsl();
        if(ssl == null || !ssl.isEnabled()) {
            return;
        }
        log.info("Configuring Undertow SSL");
        try {
            KeyStore keyStore = KeyStore.getInstance(ssl.getKeyStoreType());
            try(FileInputStream fis = new FileInputStream(ssl.getPath())) {
                keyStore.load(fis, ssl.getPassword().toCharArray());
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, ssl.getPassword().toCharArray());
            undertowSslContext = SSLContext.getInstance("TLS");
            undertowSslContext.init(kmf.getKeyManagers(), null, null);
        } catch(Exception e) {
            throw new RuntimeException("Failed to create SSL context for Undertow", e);
        }
    }

    private void startMetrics() {
        log.info("Configuring CAPI Metrics");
        meterRegistry = MetricsConfiguration.createMetricsRegistry();
        prometheusRegistry = MetricsConfiguration.createPrometheusMeterRegistry(meterRegistry);
    }

    private void startWebsocketUtils() {
        List<DefaultJWTProcessor<SecurityContext>> jwtProcessors = null;
        if (oauth2Provider != null && oauth2Provider.getJwtProcessorList() != null) {
            jwtProcessors = oauth2Provider.getJwtProcessorList();
        }
        websocketUtils = new WebsocketUtils(configuration.getWebsocket(), jwtProcessors, capiSslContextHolder, backendPoolSettings());
    }

    private void startGrpcUtils() {
        if(configuration.getGrpc() != null && configuration.getGrpc().isEnabled()) {
            grpcUtils = new GrpcUtils(capiSslContextHolder, backendPoolSettings());
        }
    }

    /** Backend pool policy is a property of the network path, so every transport shares one. */
    private CAPILoadBalancerProxyClient.PoolSettings backendPoolSettings() {
        CAPIConfiguration.Rest rest = configuration.getRest();
        if (rest == null) {
            return CAPILoadBalancerProxyClient.PoolSettings.DEFAULTS;
        }
        return new CAPILoadBalancerProxyClient.PoolSettings(
                rest.getProxyPoolSize(), rest.getProxyMaxPoolSize(), rest.getConnectionIdleTimeout(),
                rest.getConnectTimeout());
    }

    private void startConsulStore() {
        if(configuration.getConsulStore().isEnabled() && configuration.getTrustStore().isEnabled()) {
            consulStore = new ConsulStore(consulStoreCache, routeUtils, configuration.getConsulStore().getEndpoint(), configuration.getConsulStore().getToken(), configuration.getTrustStore().getPassword(), capiSslContextHolder, consulHttpClient);
            consulStore.setWebsocketUtils(websocketUtils);
            consulStore.setHttpUtils(httpUtils);
            consulStore.setCapiTrustManager(capiTrustManager);
            consulStore.setServiceCache(serviceCache);
            consulStore.setRestClientMap(restClientMap);
            consulStore.setWebsocketClientMap(webSocketClientMap);
            if (configuration.getRest() != null && configuration.getRest().getResponseTimeout() > 0) {
                consulStore.setGlobalResponseTimeout(configuration.getRest().getResponseTimeout());
            }
            if (grpcUtils != null) {
                consulStore.setGrpcUtils(grpcUtils);
                consulStore.setGrpcClientMap(grpcClientMap);
            }
            consulStore.setTrustStoreReloadedCallback(this::rebuildConsulHttpClient);
        }
    }

    private void rebuildConsulHttpClient() {
        HttpClient previous = consulHttpClient;
        startConsulHttpClient();
        HttpClient client = consulHttpClient;
        if (consulStore != null) consulStore.setHttpClient(client);
        if (consulCatalogService != null) consulCatalogService.setHttpClient(client);
        if (opaWasmService != null) opaWasmService.setHttpClient(client);
        if (apiKeyStore != null) apiKeyStore.setHttpClient(client);
        log.info("Consul HttpClient rebuilt with updated SSLContext and re-injected into consumers");
        // Release the previous client: a discarded HttpClient pins its selector-manager thread,
        // selector FDs, virtual-thread executor and pooled sockets until GC — which rarely runs
        // here. shutdown() is a graceful, non-blocking close: in-flight discovery requests finish,
        // then the client and its resources are released.
        if (previous != null && previous != client) {
            try {
                previous.shutdown();
            } catch (Exception e) {
                log.warn("Failed to shut down previous Consul HttpClient: {}", e.getMessage());
            }
        }
    }

    private void startApiKeyStore() {
        if(configuration.getApiKeyStore() != null && configuration.getApiKeyStore().isEnabled() && apiKeyCache != null) {
            if(configuration.getConsulStore() == null || !configuration.getConsulStore().isEnabled()) {
                log.warn("API Key Store requires consulStore to be enabled, skipping.");
                return;
            }
            log.info("Configuring API Key Store");
            apiKeyStore = new ApiKeyStore(apiKeyCache, configuration.getConsulStore().getEndpoint(), configuration.getConsulStore().getToken(), consulHttpClient);
        }
    }

    private void startRouteConsistencyChecker() {
        routeConsistencyChecker = new RouteConsistencyChecker(serviceCache);
        routeConsistencyChecker.setRestClientMap(restClientMap);
        routeConsistencyChecker.setRestClientSnapshot(restClientSnapshot);
    }

    private void startConsulCatalogService() {
        int globalResponseTimeout = (configuration.getRest() != null && configuration.getRest().getResponseTimeout() > 0)
                ? configuration.getRest().getResponseTimeout()
                : 120000;

        List<TransportHandler> handlers = new ArrayList<>();
        if (configuration.getRest() != null) {
            handlers.add(new RestTransportHandler(
                    configuration.getRest().isEnabled(),
                    restClientMap,
                    websocketUtils,
                    httpUtils,
                    opaWasmService,
                    globalResponseTimeout,
                    restClientSnapshot));
        }
        if (configuration.getWebsocket() != null) {
            handlers.add(new WebsocketTransportHandler(
                    configuration.getWebsocket().isEnabled(),
                    webSocketClientMap,
                    websocketUtils));
        }
        if (configuration.getGrpc() != null) {
            handlers.add(new GrpcTransportHandler(
                    configuration.getGrpc().isEnabled(),
                    grpcClientMap,
                    grpcUtils));
        }

        String extrasPrefix = configuration.getTraces() != null
                ? configuration.getTraces().getExtraMetadataPrefix()
                : null;

        consulCatalogService = new ConsulCatalogService(
                configuration.getConsulHosts(),
                serviceCache,
                serviceUtils,
                handlers,
                consulHttpClient,
                configuration.getInstanceName(),
                configuration.isStrictToInstanceName(),
                extrasPrefix,
                invalidServiceMap);
    }

    private void createServiceCache() {
        log.info("Creating Service Cache");
        serviceCache = LocalCacheConfiguration.serviceCache();
        if(configuration.getConsulStore().isEnabled()) {
            consulStoreCache = LocalCacheConfiguration.consulStoreCache();
        }
        if(configuration.getApiKeyStore() != null && configuration.getApiKeyStore().isEnabled()) {
            apiKeyCache = LocalCacheConfiguration.apiKeyCache();
        }
    }

    private void startHttpUtils() {
        log.info("Configuring HTTP Utils");
        httpUtils = new HttpUtils(configuration.getOauth2().getCookieName(), jwtProcessorList);
    }

    private void startOauth2Service() {
        if(configuration.getOauth2().isEnabled()) {
            log.info("Configuring oauth2 Support");
            oauth2Provider = new Oauth2Provider(configuration.getOauth2().getKeys());
            jwtProcessorList = oauth2Provider.getJwtProcessor(capiSslContextHolder);
        }
    }

    private void createSslContextHolder() {
        if(configuration.getTrustStore().isEnabled()) {
            try {
                log.info("Configuring CAPI TrustStore");

                if(configuration.getTrustStore().getEncoded() != null && !configuration.getTrustStore().getEncoded().isEmpty()) {
                    InputStream trusStoreInputStream = new ByteArrayInputStream(Base64.getDecoder().decode(configuration.getTrustStore().getEncoded().getBytes()));
                    capiTrustManager = new CapiTrustManager(trusStoreInputStream, null, configuration.getTrustStore().getPassword());
                } else {
                    File filePath = new File(configuration.getTrustStore().getPath());
                    capiTrustManager = new CapiTrustManager(null, filePath.getAbsolutePath(), configuration.getTrustStore().getPassword());
                }

                TrustStrategy trustStrategy = (X509Certificate[] chain, String authType) -> false;
                SSLContext sslContext = null;

                    sslContext = SSLContextBuilder
                            .create()
                            .loadTrustMaterial(capiTrustManager.getKeyStore(), trustStrategy)
                            .build();

                capiSslContextHolder = new CapiSslContextHolder(sslContext);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void startTraceService() {
        if(configuration.getTraces().isEnabled()) {
            openTelemetrySdk = TracingBootstrap.init(
                    configuration.getTraces().getEndpoint(),
                    configuration.getTraces().getServiceName(),
                    configuration.getTraces().getAppEnvironment());
            openTelemetryTracer = openTelemetrySdk.getTracer(configuration.getTraces().getServiceName());
        }
    }

    private void startRouteUtils() {
        routeUtils = new RouteUtils(meterRegistry);
    }

    private void startServiceUtils() {
        serviceUtils = new ServiceUtils(httpUtils, Optional.empty(), routeUtils, Optional.empty(), configuration.getRunningMode());
        serviceUtils.setRestClientMap(restClientMap);
    }

    private void createRouteProcessors() {
        boolean apiKeyStoreEnabled = configuration.getApiKeyStore() != null && configuration.getApiKeyStore().isEnabled();
        if(configuration.getThrottle() != null && configuration.getThrottle().isEnabled()) {
            log.info("Throttling enabled, starting Hazelcast");
            throttleProcessor = new ThrottleProcessor(serviceCache, httpUtils, HazelcastCacheConfiguration.createThrottleCache(configuration.getThrottle()));
        } else if(apiKeyStoreEnabled) {
            log.info("API key store enabled, starting Hazelcast for per-key throttling");
            CAPIConfiguration.Throttle throttleConfig = configuration.getThrottle() != null ? configuration.getThrottle() : new CAPIConfiguration.Throttle();
            throttleProcessor = new ThrottleProcessor(serviceCache, httpUtils, HazelcastCacheConfiguration.createThrottleCache(throttleConfig));
        }
        if(configuration.getOauth2().isEnabled() || apiKeyStoreEnabled) {
        }
    }

    private void startOpaWasmService() {
        if(configuration.getOpa().isEnabled()) {
            if (configuration.getOpa().getWasmBundleUrl() != null) {
                String token = configuration.getOpa().getWasmBundleToken();
                log.info("OPA Wasm enabled, bundle URL: {}, pool size: {}, bundle auth: {}",
                        configuration.getOpa().getWasmBundleUrl(),
                        configuration.getOpa().getWasmPoolSize(),
                        (token != null && !token.isBlank()) ? "bearer-token" : "none");
                opaWasmService = new OpaWasmService(
                        configuration.getOpa().getWasmBundleUrl(),
                        token,
                        configuration.getOpa().getWasmPoolSize(),
                        consulHttpClient
                );
            }
        }
    }

    public PrometheusMeterRegistry getPrometheusRegistry() {
        return prometheusRegistry;
    }

    public Cache<String, Service> getServiceCache() {
        return serviceCache;
    }

    public HttpUtils getHttpUtils() {
        return httpUtils;
    }

    public ConsulCatalogService getConsulCatalogService() {
        return consulCatalogService;
    }

    public RouteUtils getRouteUtils() {
        return routeUtils;
    }

    public @Nullable Oauth2Provider getOauth2Provider() {
        return oauth2Provider;
    }

    public @Nullable WebsocketUtils getWebsocketUtils() {
        return websocketUtils;
    }

    public @Nullable GrpcUtils getGrpcUtils() {
        return grpcUtils;
    }

    public Map<String, WebsocketClient> getWebSocketClientMap() {
        return webSocketClientMap;
    }

    public Map<String, RestClient> getRestClientMap() {
        return restClientMap;
    }

    public RestClientSnapshot getRestClientSnapshot() {
        return restClientSnapshot;
    }

    public @Nullable ThrottleProcessor getThrottleProcessor() {
        return throttleProcessor;
    }

    public @Nullable Cache<String, ApiKeyStoreEntry> getApiKeyCache() {
        return apiKeyCache;
    }

    public Map<String, GrpcClient> getGrpcClientMap() {
        return grpcClientMap;
    }

    public @Nullable SSLContext getUndertowSslContext() {
        return undertowSslContext;
    }

    public @Nullable CapiTrustManager getCapiTrustManager() {
        return capiTrustManager;
    }

    public @Nullable ConsulStore getConsulStore() {
        return consulStore;
    }

    public HttpClient getConsulHttpClient() {
        return consulHttpClient;
    }

    public @Nullable ApiKeyStore getApiKeyStore() {
        return apiKeyStore;
    }

    public @Nullable io.opentelemetry.api.trace.Tracer getOpenTelemetryTracer() {
        return openTelemetryTracer;
    }

    /** Null when tracing is disabled. Close it on shutdown to flush queued spans. */
    public @Nullable io.opentelemetry.sdk.OpenTelemetrySdk getOpenTelemetrySdk() {
        return openTelemetrySdk;
    }

    public @Nullable OpaWasmService getOpaWasmService() {
        return opaWasmService;
    }

    public RouteConsistencyChecker getRouteConsistencyChecker() {
        return routeConsistencyChecker;
    }

    public @Nullable io.surisoft.capi.observability.JvmObservability getJvmObservability() {
        return jvmObservability;
    }

    private void startMcpService() {
        if (configuration.getMcp() != null && configuration.getMcp().isEnabled()) {
            log.info("Configuring MCP Gateway");
            mcpToolRegistry = new McpToolRegistry(serviceCache);
            if (configuration.getThrottle() != null && configuration.getThrottle().isEnabled()) {
                mcpSessionStore = new HazelcastMcpSessionStore(
                        io.surisoft.capi.configuration.HazelcastCacheConfiguration.createMcpSessionMap(configuration.getThrottle()));
            } else {
                mcpSessionStore = new LocalMcpSessionStore(
                        LocalCacheConfiguration.mcpSessionCache(configuration.getMcp().getSessionTtl()));
            }
            mcpLoadBalancer = new McpBackendLoadBalancer(configuration.getMcp().getCircuitBreakerCooldownMs());
            // Share the consulHttpClient so MCP server-backend dispatch uses the same
            // trust material as every other JDK-HttpClient consumer (Consul polling,
            // OPA bundle fetch, etc.). A freshly-built HttpClient here would default
            // to JVM cacerts and silently break against internal-CA backends.
            mcpServerClient = new McpServerClient(serviceCache, mcpLoadBalancer, consulHttpClient, configuration);
        }
    }

    public @Nullable McpToolRegistry getMcpToolRegistry() {
        return mcpToolRegistry;
    }

    public @Nullable McpSessionStore getMcpSessionStore() {
        return mcpSessionStore;
    }

    public @Nullable McpServerClient getMcpServerClient() {
        return mcpServerClient;
    }

    public @Nullable McpBackendLoadBalancer getMcpLoadBalancer() {
        return mcpLoadBalancer;
    }

    private void startConsulHttpClient() {
        HttpClient.Builder httpClientBuilder = HttpClient.newBuilder();
        if(capiSslContextHolder != null) {
            httpClientBuilder.sslContext(capiSslContextHolder.getSslContext());
        }
        httpClientBuilder.connectTimeout(Duration.ofSeconds(10));
        httpClientBuilder.executor(Executors.newVirtualThreadPerTaskExecutor());
        consulHttpClient = httpClientBuilder.build();
    }

    public Map<String, InvalidService> getInvalidServiceMap() {
        return invalidServiceMap;
    }
}