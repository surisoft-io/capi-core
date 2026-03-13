package io.surisoft.capi.configuration;

import io.surisoft.capi.service.ConsulNodeDiscovery;
import org.apache.camel.BeanInject;
import org.apache.camel.CamelContext;
import org.apache.camel.ExtendedStartupListener;
import org.apache.camel.builder.RouteBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CamelStartupListener implements ExtendedStartupListener {

    private static final Logger log = LoggerFactory.getLogger(CamelStartupListener.class);
    private final long consulTimerInterval;
    private final boolean consulStoreEnabled;
    private final boolean trustStoreEnabled;
    private final boolean mcpServerEnabled;
    private final boolean apiKeyStoreEnabled;

    @BeanInject("consulNodeDiscovery")
    private ConsulNodeDiscovery consulNodeDiscovery;

    public CamelStartupListener(long consulTimerInterval, boolean consulStoreEnabled, boolean trustStoreEnabled) {
        this(consulTimerInterval, consulStoreEnabled, trustStoreEnabled, false, false);
    }

    public CamelStartupListener(long consulTimerInterval, boolean consulStoreEnabled, boolean trustStoreEnabled, boolean mcpServerEnabled) {
        this(consulTimerInterval, consulStoreEnabled, trustStoreEnabled, mcpServerEnabled, false);
    }

    public CamelStartupListener(long consulTimerInterval, boolean consulStoreEnabled, boolean trustStoreEnabled, boolean mcpServerEnabled, boolean apiKeyStoreEnabled) {
        this.consulTimerInterval = consulTimerInterval;
        this.consulStoreEnabled = consulStoreEnabled;
        this.trustStoreEnabled = trustStoreEnabled;
        this.mcpServerEnabled = mcpServerEnabled;
        this.apiKeyStoreEnabled = apiKeyStoreEnabled;
    }

    @Override
    public void onCamelContextStarted(CamelContext context, boolean alreadyStarted) throws Exception {
    }

    @Override
    public void onCamelContextFullyStarted(CamelContext context, boolean alreadyStarted) throws Exception {
        context.addRoutes(consulDiscoveryRouteBuilder());
        if(consulStoreEnabled && trustStoreEnabled) {
            context.addRoutes(consulStoreRouteBuilder());
        }
        if(apiKeyStoreEnabled) {
            context.addRoutes(apiKeyStoreRouteBuilder());
        }
        context.addRoutes(consistencyCheckRouteBuilder());
    }

    public RouteBuilder consulDiscoveryRouteBuilder() {
        log.debug("Creating Capi Consul Node Discovery");
        return new RouteBuilder() {
            @Override
            public void configure() {
                if (mcpServerEnabled) {
                    from("timer:consul-inspect?period=" + consulTimerInterval)
                            .to("bean:consulNodeDiscovery?method=processInfo")
                            .to("bean:mcpServerClient?method=refreshMcpServerTools")
                            .routeId("consul-discovery-service");
                } else {
                    from("timer:consul-inspect?period=" + consulTimerInterval)
                            .to("bean:consulNodeDiscovery?method=processInfo")
                            .routeId("consul-discovery-service");
                }
            }
        };
    }

    public RouteBuilder consulStoreRouteBuilder() {
        log.debug("Creating Capi Consul Store");
        return new RouteBuilder() {
            @Override
            public void configure() {
                from("timer:consul-inspect?period=" + consulTimerInterval)
                        .to("bean:consulStore?method=process")
                        .routeId("consul-store-service");
            }
        };
    }

    public RouteBuilder apiKeyStoreRouteBuilder() {
        log.debug("Creating Capi API Key Store");
        return new RouteBuilder() {
            @Override
            public void configure() {
                from("timer:api-key-store-inspect?period=" + consulTimerInterval)
                        .to("bean:apiKeyStore?method=process")
                        .routeId("api-key-store-service");
            }
        };
    }

    public RouteBuilder consistencyCheckRouteBuilder() {
        return new RouteBuilder() {
            @Override
            public void configure() {
                log.debug("Creating CAPI Route Consistency Checker");
                from("timer:consistency-checker?period=60000")
                        .to("bean:routeConsistencyChecker?method=process")
                        .routeId("route-consistency-checker-service");
            }
        };
    }

}
