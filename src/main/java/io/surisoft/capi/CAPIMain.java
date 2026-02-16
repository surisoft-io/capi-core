package io.surisoft.capi;

import io.surisoft.capi.builder.ErrorRoute;
import io.surisoft.capi.builder.PrimaryRoute;
import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.configuration.CamelStartupListener;
import io.surisoft.capi.undertow.AdminGateway;
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

public class CAPIMain {

    private static final Logger log = LoggerFactory.getLogger(CAPIMain.class);
    private final CAPIConfiguration capiConfiguration;

    public static void main(String[] args) {
        new CAPIMain();
    }

    public CAPIMain() {

        //General configuration
        log.info("Getting CAPI Configuration");
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
        } catch (IOException e) {
            log.error(e.getMessage(), e);
            throw new RuntimeException("Failed to load CAPI Configuration File");
        }

        log.info("Starting CAPI Camel Context");
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
            adminGateway.start();

            camelContext.getRegistry().bind("consulNodeDiscovery", startup.getConsulNodeDiscovery());
            if(capiConfiguration.getConsulStore() != null && capiConfiguration.getConsulStore().isEnabled()) {
                camelContext.getRegistry().bind("consulStore", startup.getConsulStore());
            }
            camelContext.getRegistry().bind("routeConsistencyChecker", startup.getRouteConsistencyChecker());
            camelContext.addRoutes(new ErrorRoute(startup.getHttpUtils()));

            if(capiConfiguration.getRest() != null
                    && capiConfiguration.getRest().isEnabled()
                    && capiConfiguration.getRest().getContextPath() != null
                    && !capiConfiguration.getRest().getContextPath().isEmpty()) {
                boolean sslEnabled = capiConfiguration.getSsl() != null && capiConfiguration.getSsl().isEnabled();
                camelContext.addRoutes(new PrimaryRoute(startup.getRouteUtils(), capiConfiguration.getRest().getPort(), capiConfiguration.getRest().getListeningAddress(), capiConfiguration.getRest().getContextPath(), sslEnabled, capiConfiguration.isCorsEnabled(), managedHeaders, startup.getServiceCache()));
            }

            camelContext.addStartupListener(new CamelStartupListener(capiConfiguration.getConsulCatalogDiscoverInterval(), capiConfiguration.getConsulStore().isEnabled(), capiConfiguration.getTrustStore().isEnabled()));
            camelContext.start();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down CAPI Gateway...");
                if(websocketGateway != null) {
                    websocketGateway.stop();
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
        if(capiConfiguration.getWebsocket() != null
                && capiConfiguration.getWebsocket().isEnabled()
                && capiConfiguration.getWebsocket().getContextPath() != null
                && !capiConfiguration.getWebsocket().getContextPath().isEmpty()) {
            websocketGateway = new WebsocketGateway(capiConfiguration.getWebsocket().getPort(), startup.getWebSocketClientMap(), startup.getWebsocketUtils(), startup.getUndertowSslContext(), new ArrayList<>(), "cookiw");
            websocketGateway.runProxy();
        }
        return websocketGateway;
    }
}
