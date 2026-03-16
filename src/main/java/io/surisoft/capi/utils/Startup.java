package io.surisoft.capi.utils;

import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.surisoft.capi.CAPIMain;
import io.surisoft.capi.configuration.*;
import io.surisoft.capi.oidc.Oauth2Provider;
import io.surisoft.capi.processor.*;
import io.surisoft.capi.schema.ApiKeyStoreEntry;
import io.surisoft.capi.schema.ConsulKeyStoreEntry;
import io.surisoft.capi.schema.GrpcClient;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.WebsocketClient;
import io.surisoft.capi.service.*;
import io.surisoft.capi.configuration.LocalCacheConfiguration;
import io.surisoft.capi.tracer.CapiTracer;
import io.surisoft.capi.tracer.TracingBootstrap;
import jakarta.annotation.Nullable;
import org.apache.camel.CamelContext;
import org.apache.camel.component.http.HttpComponent;
import org.apache.camel.component.undertow.UndertowComponent;
import org.apache.camel.support.jsse.KeyManagersParameters;
import org.apache.camel.support.jsse.KeyStoreParameters;
import org.apache.camel.support.jsse.SSLContextParameters;
import org.apache.camel.support.jsse.TrustManagersParameters;
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
    private final CamelContext camelContext;
    private ServiceUtils serviceUtils;
    private RouteUtils routeUtils;
    private Cache<String, Service> serviceCache;
    private HttpUtils httpUtils;
    private ConsulNodeDiscovery consulNodeDiscovery;
    private MetricsProcessor metricsProcessor;
    private ContentTypeValidator contentTypeValidator;
    private ThrottleProcessor throttleProcessor;
    private HttpClient consulHttpClient;

    @Nullable
    private Oauth2Provider oauth2Provider;
    @Nullable
    private List<DefaultJWTProcessor<SecurityContext>> jwtProcessorList;
    @Nullable
    private CapiSslContextHolder capiSslContextHolder;
    @Nullable
    private CapiTracer capiTracer;
    @Nullable
    private AuthorizationProcessor authorizationProcessor;
    @Nullable
    private OpaService opaService;
    @Nullable
    private WebsocketUtils websocketUtils;
    @Nullable
    private GrpcUtils grpcUtils;
    private Map<String, WebsocketClient> webSocketClientMap = new java.util.concurrent.ConcurrentHashMap<>();
    private Map<String, GrpcClient> grpcClientMap = new java.util.concurrent.ConcurrentHashMap<>();
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

    public Startup(CAPIConfiguration capiConfiguration, CamelContext camelContext) {
        this.configuration = capiConfiguration;
        this.camelContext = camelContext;
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
        startConsulNodeDiscoveryService();
        if(configuration.isCorsEnabled()) {
            bindCapCorsFilterStrategy(camelContext);
        }
        if(configuration.getConsulStore().isEnabled()) {
            startConsulStore();
        }
        startApiKeyStore();
        startRouteConsistencyChecker();
        startOpaService();
        startMcpService();
    }

    private void configureUndertowSsl() {
        CAPIConfiguration.Ssl ssl = configuration.getSsl();
        if(ssl == null || !ssl.isEnabled()) {
            return;
        }
        log.info("Configuring Undertow SSL");

        // Configure Camel's undertow component
        KeyStoreParameters keyStoreParameters = new KeyStoreParameters();
        keyStoreParameters.setResource(ssl.getPath());
        keyStoreParameters.setPassword(ssl.getPassword());
        keyStoreParameters.setType(ssl.getKeyStoreType());

        KeyManagersParameters keyManagersParameters = new KeyManagersParameters();
        keyManagersParameters.setKeyStore(keyStoreParameters);
        keyManagersParameters.setKeyPassword(ssl.getPassword());

        SSLContextParameters sslContextParameters = new SSLContextParameters();
        sslContextParameters.setKeyManagers(keyManagersParameters);

        UndertowComponent undertowComponent = (UndertowComponent) camelContext.getComponent("undertow");
        undertowComponent.setSslContextParameters(sslContextParameters);

        // Build SSLContext for non-Camel Undertow servers (AdminGateway, etc.)
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
        if(configuration.getWebsocket().isEnabled()) {
            if(oauth2Provider != null && oauth2Provider.getJwtProcessorList() != null) {
                websocketUtils = new WebsocketUtils(configuration.getWebsocket(), oauth2Provider.getJwtProcessorList(), false, null, null, null);
            } else {
                websocketUtils = new WebsocketUtils(configuration.getWebsocket(), null, false, null, null, null);
            }
        }
    }

    private void startGrpcUtils() {
        if(configuration.getGrpc() != null && configuration.getGrpc().isEnabled()) {
            boolean trustStoreEnabled = configuration.getTrustStore() != null && configuration.getTrustStore().isEnabled();
            String trustStorePath = trustStoreEnabled ? configuration.getTrustStore().getPath() : null;
            String trustStorePassword = trustStoreEnabled ? configuration.getTrustStore().getPassword() : null;
            String trustStoreEncoded = trustStoreEnabled ? configuration.getTrustStore().getEncoded() : null;
            grpcUtils = new GrpcUtils(trustStoreEnabled, trustStorePath, trustStorePassword, trustStoreEncoded);
        }
    }

    private void startConsulStore() {
        if(configuration.getConsulStore().isEnabled() && configuration.getTrustStore().isEnabled()) {
            consulStore = new ConsulStore(consulStoreCache, routeUtils, configuration.getConsulStore().getEndpoint(), configuration.getConsulStore().getToken(), configuration.getTrustStore().getPassword(), capiSslContextHolder, camelContext, consulHttpClient);
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
        routeConsistencyChecker = new RouteConsistencyChecker(camelContext, routeUtils, serviceCache);
    }

    private void startConsulNodeDiscoveryService() {
        consulNodeDiscovery = new ConsulNodeDiscovery(camelContext, capiSslContextHolder, configuration.getConsulHosts(), serviceUtils, serviceCache, routeUtils, websocketUtils, consulHttpClient);
        consulNodeDiscovery.setThrottleProcessor(throttleProcessor);
        consulNodeDiscovery.setContentTypeValidator(contentTypeValidator);
        consulNodeDiscovery.setMetricsProcessor(metricsProcessor);
        consulNodeDiscovery.setOpaService(opaService);
        consulNodeDiscovery.setCapiRunningMode(configuration.getRunningMode());
        if(configuration.getRest().getContextPath() != null && !configuration.getRest().getContextPath().isEmpty()) {
            consulNodeDiscovery.setCapiContext(configuration.getRest().getContextPath());
        }

        if(configuration.getReverseProxyHost() != null && !configuration.getReverseProxyHost().isEmpty()) {
            consulNodeDiscovery.setReverseProxyHost(configuration.getReverseProxyHost());
        }

        if(configuration.getInstanceName() != null) {
            consulNodeDiscovery.setCapiInstanceNamespace(configuration.getInstanceName());
        }

        consulNodeDiscovery.setStrictToInstanceName(configuration.isStrictToInstanceName());

        if(configuration.getTraces().getExtraMetadataPrefix() != null) {
            consulNodeDiscovery.setServiceMetaExtrasPrefix(configuration.getTraces().getExtraMetadataPrefix());
        }

        if(websocketUtils != null) {
            consulNodeDiscovery.setWebsocketClientMap(webSocketClientMap);
        }

        if(grpcUtils != null) {
            consulNodeDiscovery.setGrpcUtils(grpcUtils);
            consulNodeDiscovery.setGrpcClientMap(grpcClientMap);
        }
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

                HttpComponent httpComponent = (HttpComponent) camelContext.getComponent("https");

                TrustManagersParameters trustManagersParameters = new TrustManagersParameters();
                trustManagersParameters.setTrustManager(capiTrustManager);

                SSLContextParameters sslContextParameters = new SSLContextParameters();
                sslContextParameters.setTrustManagers(trustManagersParameters);
                sslContextParameters.setSessionTimeout("1");

                sslContextParameters.createSSLContext(camelContext);
                httpComponent.setSslContextParameters(sslContextParameters);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void startTraceService() {
        if(configuration.getTraces().isEnabled()) {
            OpenTelemetry openTelemetry = TracingBootstrap.init(configuration.getTraces().getEndpoint(), configuration.getTraces().getServiceName());
            capiTracer = new CapiTracer(httpUtils, configuration.getInstanceName(), serviceCache, openTelemetry.getTracer(configuration.getTraces().getServiceName()));
            List<String> excludePatterns = new ArrayList<>();
            excludePatterns.add("timer://");
            excludePatterns.add("timer");
            excludePatterns.add("bean://consulNodeDiscovery");
            excludePatterns.add("bean://consistencyChecker");
            excludePatterns.add("direct://");
            excludePatterns.add("error");
            capiTracer.setExcludePatterns(excludePatterns);
            capiTracer.init(camelContext);


        }
    }

    private void startRouteUtils() {
        routeUtils = new RouteUtils(new HttpErrorProcessor(),
                httpUtils,
                meterRegistry,
                capiTracer,
                camelContext,
                authorizationProcessor,
                configuration.isCorsEnabled(),
                capiSslContextHolder,
                configuration.getRest().getResponseTimeout(),
                configuration.getRest().getConnectionRequestTimeout(),
                configuration.getRest().getRequestTimeout());
    }

    private void startServiceUtils() {
        serviceUtils = new ServiceUtils(httpUtils, Optional.empty(), routeUtils, camelContext, Optional.empty(), configuration.getRunningMode());
    }

    private void createRouteProcessors() {
        metricsProcessor = new MetricsProcessor(meterRegistry);
        contentTypeValidator = new ContentTypeValidator();
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
            authorizationProcessor = new AuthorizationProcessor(httpUtils, serviceCache, opaService, apiKeyCache);
        }
    }

    private void startOpaService() {
        if(configuration.getOpa().isEnabled()) {
            opaService = new OpaService(configuration.getOpa().getEndpoint(), consulHttpClient);
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

    public ConsulNodeDiscovery getConsulNodeDiscovery() {
        return consulNodeDiscovery;
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

    public Map<String, GrpcClient> getGrpcClientMap() {
        return grpcClientMap;
    }

    public @Nullable SSLContext getUndertowSslContext() {
        return undertowSslContext;
    }

    private void bindCapCorsFilterStrategy(CamelContext camelContext) {
        camelContext.getRegistry().bind("capiCorsFilterStrategy", new CapiCorsFilterStrategy(configuration.getAllowedHeaders()));
    }

    public @Nullable CapiTrustManager getCapiTrustManager() {
        return capiTrustManager;
    }

    public @Nullable ConsulStore getConsulStore() {
        return consulStore;
    }

    public @Nullable ApiKeyStore getApiKeyStore() {
        return apiKeyStore;
    }

    public @Nullable OpaService getOpaService() {
        return opaService;
    }

    public RouteConsistencyChecker getRouteConsistencyChecker() {
        return routeConsistencyChecker;
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
            HttpClient mcpHttpClient = HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .executor(Executors.newVirtualThreadPerTaskExecutor())
                    .build();
            mcpServerClient = new McpServerClient(serviceCache, mcpLoadBalancer, mcpHttpClient, configuration);
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
}