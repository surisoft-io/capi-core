package io.surisoft.capi.metrics;

import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.schema.CapiInfo;
import org.apache.camel.CamelContext;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class Info {

    private final CAPIConfiguration configuration;
    private final CamelContext camelContext;

    public Info(CAPIConfiguration configuration, CamelContext camelContext) {
        this.configuration = configuration;
        this.camelContext = camelContext;
    }

    public CapiInfo getInfo() {
        CapiInfo capiInfo = new CapiInfo();
        capiInfo.setUptime(camelContext.getUptime().toString());
        capiInfo.setCamelVersion(camelContext.getVersion());
        capiInfo.setTotalRoutes(camelContext.getRoutesSize());
        capiInfo.setCapiVersion(configuration.getVersion());
        capiInfo.setCapiNameSpace(configuration.getInstanceName());
        if(configuration.getRest() != null && configuration.getRest().getContextPath() != null) {
            capiInfo.setRoutesContextPath(configuration.getRest().getContextPath());
        }

        capiInfo.setJavaVersion(String.valueOf(Runtime.version()));

        if(configuration.getOauth2() != null) {
            capiInfo.setOauth2Enabled(configuration.getOauth2().isEnabled());
            if(configuration.getOauth2().getKeys() != null) {
                capiInfo.setOauth2Endpoint(String.join(",", configuration.getOauth2().getKeys()));
            }
        }

        if(configuration.getConsulHosts() != null) {
            capiInfo.setConsulEnabled(true);
            List<String> consulHostsList = configuration.getConsulHosts().stream()
                    .map(CAPIConfiguration.HostConfig::getEndpoint)
                    .collect(Collectors.toList());
            capiInfo.setConsulHosts(consulHostsList);
        } else {
            capiInfo.setConsulEnabled(false);
            capiInfo.setConsulHosts(Collections.emptyList());
        }

        if(configuration.getTraces() != null) {
            capiInfo.setTracesEnabled(configuration.getTraces().isEnabled());
            capiInfo.setTracesEndpoint(configuration.getTraces().getEndpoint());
        }

        return capiInfo;
    }
}