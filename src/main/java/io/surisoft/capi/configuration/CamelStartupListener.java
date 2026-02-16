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

    @BeanInject("consulNodeDiscovery")
    private ConsulNodeDiscovery consulNodeDiscovery;

    public CamelStartupListener(long consulTimerInterval, boolean consulStoreEnabled) {
        this.consulTimerInterval = consulTimerInterval;
        this.consulStoreEnabled = consulStoreEnabled;
    }

    @Override
    public void onCamelContextStarted(CamelContext context, boolean alreadyStarted) throws Exception {
    }

    @Override
    public void onCamelContextFullyStarted(CamelContext context, boolean alreadyStarted) throws Exception {
        context.addRoutes(consulDiscoveryRouteBuilder());
        if(consulStoreEnabled) {
            context.addRoutes(consulStoreRouteBuilder());
        }

    }

    public RouteBuilder consulDiscoveryRouteBuilder() {
        log.debug("Creating Capi Consul Node Discovery");
        return new RouteBuilder() {
            @Override
            public void configure() {
                from("timer:consul-inspect?period=" + consulTimerInterval)
                        .to("bean:consulNodeDiscovery?method=processInfo")
                        .routeId("consul-discovery-service");
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
}