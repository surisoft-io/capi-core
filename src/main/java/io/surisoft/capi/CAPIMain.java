package io.surisoft.capi;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import io.surisoft.capi.builder.ErrorRoute;
import io.surisoft.capi.builder.PrimaryRoute;
import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.configuration.CamelStartupListener;
import io.surisoft.capi.service.CamelProxyPeerAddressHandler;
import io.surisoft.capi.service.CapiAccessLogReceiver;
import io.surisoft.capi.service.McpBackendLoadBalancer;
import io.surisoft.capi.service.McpServerClient;
import io.surisoft.capi.undertow.AdminGateway;
import io.surisoft.capi.undertow.McpGateway;
import io.surisoft.capi.undertow.WebsocketGateway;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.Startup;
import org.apache.camel.CamelContext;
import org.apache.camel.impl.DefaultCamelContext;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class CAPIMain {

    private static Logger log;
    private final CAPIConfiguration capiConfiguration;

    public static void main(String[] args) {
        new CAPIMain();
    }

    public CAPIMain() {

        //General configuration
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
            capiConfiguration = capiYaml.load(yaml.dump(root.get("capi")));
            if(capiConfiguration.getConsulHosts() == null || capiConfiguration.getConsulHosts().isEmpty()) {
                throw new RuntimeException("Failed to start CAPI, it needs at least one Consul instance.");
            } else if(capiConfiguration.getConsulHosts().getFirst().getEndpoint() == null || capiConfiguration.getConsulHosts().getFirst().getEndpoint().isEmpty()) {
                throw new RuntimeException("Failed to start CAPI, it needs at least one Consul instance.");
            }

            //Initialize Logging
            initializeLogs(capiConfiguration);
        } catch (IOException e) {
            //log.error(e.getMessage(), e);
            throw new RuntimeException("Failed to load CAPI Configuration File");
        }

        //log.info("Starting CAPI Camel Context");
        CamelContext camelContext = new DefaultCamelContext();

        Startup startup = new Startup(capiConfiguration, camelContext);
        startup.start();

        try {
            Map<String, String> managedHeaders = new HashMap<>(Constants.CAPI_CORS_MANAGED_HEADERS);
            if(capiConfiguration.getAllowedHeaders() != null && !capiConfiguration.getAllowedHeaders().isEmpty()) {
                if(capiConfiguration.getOauth2() != null && capiConfiguration.getOauth2().getCookieName() != null && !capiConfiguration.getOauth2().getCookieName().isEmpty()) {
                    capiConfiguration.getAllowedHeaders().add(capiConfiguration.getOauth2().getCookieName());
                }
                managedHeaders.put("Access-Control-Allow-Headers", String.join(",", capiConfiguration.getAllowedHeaders()));
            }

            WebsocketGateway websocketGateway = getWebsocketGateway(startup);

            AdminGateway adminGateway = new AdminGateway(capiConfiguration.getAdminPort(), startup.getPrometheusRegistry(), capiConfiguration, camelContext, startup.getServiceCache(), startup.getUndertowSslContext(), startup.getCapiTrustManager());
            if(startup.getWebSocketClientMap() != null) {
                adminGateway.setWebsocketClients(startup.getWebSocketClientMap());
            }
            if(startup.getMcpToolRegistry() != null) {
                adminGateway.setMcpToolRegistry(startup.getMcpToolRegistry());
            }
            if(startup.getMcpSessionStore() != null) {
                adminGateway.setMcpSessionStore(startup.getMcpSessionStore());
            }
            adminGateway.start();

            McpGateway mcpGateway = getMcpGateway(startup);

            camelContext.getRegistry().bind("consulNodeDiscovery", startup.getConsulNodeDiscovery());
            if(capiConfiguration.getConsulStore().isEnabled()) {
                camelContext.getRegistry().bind("consulStore", startup.getConsulStore());
            }
            camelContext.getRegistry().bind("routeConsistencyChecker", startup.getRouteConsistencyChecker());
            if(startup.getMcpServerClient() != null) {
                camelContext.getRegistry().bind("mcpServerClient", startup.getMcpServerClient());
            }
            camelContext.addRoutes(new ErrorRoute(startup.getHttpUtils()));


            String primaryEndpoint;
            String scheme = "http";
            if(capiConfiguration.getSsl().isEnabled()) {
                scheme = "https";
            }
            CamelProxyPeerAddressHandler proxyPeerAddressHandler = new CamelProxyPeerAddressHandler();
            camelContext.getRegistry().bind("camelProxyPeerAddressHandler", proxyPeerAddressHandler);

            if(capiConfiguration.getAccessLogs().isEnabled()) {
                CapiAccessLogReceiver capiAccessLogReceiver = new CapiAccessLogReceiver();
                camelContext.getRegistry().bind("capiAccessLogReceiver", capiAccessLogReceiver);
                primaryEndpoint = "undertow:" + scheme + "://" + capiConfiguration.getRest().getListeningAddress() + ":" + capiConfiguration.getRest().getPort() + capiConfiguration.getRest().getContextPath() + "?accessLog=true&accessLogReceiver=#capiAccessLogReceiver&handlers=#camelProxyPeerAddressHandler&matchOnUriPrefix=true&optionsEnabled=true&httpMethodRestrict=GET,POST,PUT,DELETE,OPTIONS,PATCH";
            } else {
                primaryEndpoint = "undertow:" + scheme + "://" + capiConfiguration.getRest().getListeningAddress() + ":" + capiConfiguration.getRest().getPort() + capiConfiguration.getRest().getContextPath() + "?matchOnUriPrefix=true&optionsEnabled=true&httpMethodRestrict=GET,POST,PUT,DELETE,OPTIONS,PATCH";
            }

            if(capiConfiguration.getRest().isEnabled()
                    && capiConfiguration.getRest().getContextPath() != null
                    && !capiConfiguration.getRest().getContextPath().isEmpty()) {
                boolean sslEnabled = capiConfiguration.getSsl() != null && capiConfiguration.getSsl().isEnabled();
                camelContext.addRoutes(new PrimaryRoute(startup.getRouteUtils(), capiConfiguration.getRest().getPort(), capiConfiguration.getRest().getListeningAddress(), capiConfiguration.getRest().getContextPath(), sslEnabled, capiConfiguration.isCorsEnabled(), managedHeaders, startup.getServiceCache(), primaryEndpoint));
            }

            boolean mcpServerEnabled = startup.getMcpServerClient() != null;
            camelContext.addStartupListener(new CamelStartupListener(capiConfiguration.getConsulCatalogDiscoverInterval(), capiConfiguration.getConsulStore().isEnabled(), capiConfiguration.getTrustStore().isEnabled(), mcpServerEnabled));
            camelContext.start();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down CAPI Gateway...");
                if(websocketGateway != null) {
                    websocketGateway.stop();
                }
                if(mcpGateway != null) {
                    mcpGateway.stop();
                }
                camelContext.stop();
                adminGateway.stop();
                log.info("CAPI Gateway stopped.");
            }));

            log.info("CAPI Gateway started successfully.");
            Thread.currentThread().join();

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private @Nullable WebsocketGateway getWebsocketGateway(Startup startup) {
        WebsocketGateway websocketGateway = null;
        if(capiConfiguration.getWebsocket().isEnabled()
                && capiConfiguration.getWebsocket().getContextPath() != null
                && !capiConfiguration.getWebsocket().getContextPath().isEmpty()) {
            websocketGateway = new WebsocketGateway(capiConfiguration.getWebsocket().getPort(), startup.getWebSocketClientMap(), startup.getWebsocketUtils(), startup.getUndertowSslContext(), new ArrayList<>(), "cookiw");
            websocketGateway.runProxy();
        }
        return websocketGateway;
    }

    private @Nullable McpGateway getMcpGateway(Startup startup) {
        if(capiConfiguration.getMcp() != null && capiConfiguration.getMcp().isEnabled()
                && startup.getMcpToolRegistry() != null
                && startup.getMcpSessionStore() != null) {
            java.net.http.HttpClient mcpHttpClient = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .build();
            McpBackendLoadBalancer loadBalancer = startup.getMcpLoadBalancer() != null
                    ? startup.getMcpLoadBalancer()
                    : new McpBackendLoadBalancer(capiConfiguration.getMcp().getCircuitBreakerCooldownMs());
            McpGateway mcpGateway = new McpGateway(
                    capiConfiguration.getMcp().getPort(),
                    startup.getUndertowSslContext(),
                    startup.getMcpToolRegistry(),
                    startup.getHttpUtils(),
                    startup.getOpaService(),
                    mcpHttpClient,
                    startup.getMcpSessionStore(),
                    capiConfiguration,
                    loadBalancer,
                    startup.getMcpServerClient()
            );
            mcpGateway.start();
            return mcpGateway;
        }
        return null;
    }

    private void initializeLogs(CAPIConfiguration configuration) {
        if(configuration.getLoggingTraces().isEnabled()) {
            System.setProperty("logging.logback.logs.enabled", Boolean.toString(true));
            System.setProperty("logging.logback.logs.tenant", configuration.getLoggingTraces().getTenant());
            System.setProperty("logging.logback.logs.appName", configuration.getLoggingTraces().getAppName());
            System.setProperty("logging.logback.logs.appEnvironment", configuration.getLoggingTraces().getAppEnvironment());
            System.setProperty("logging.logback.logs.destination", configuration.getLoggingTraces().getDestination());
        }

        if(configuration.getAccessLogs().isEnabled()) {
            System.setProperty("logging.logback.access.enabled", Boolean.toString(true));
            System.setProperty("logging.logback.access.tenant", configuration.getAccessLogs().getTenant());
            System.setProperty("logging.logback.access.service", configuration.getAccessLogs().getService());
            System.setProperty("logging.logback.access.destination", configuration.getAccessLogs().getDestination());
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
