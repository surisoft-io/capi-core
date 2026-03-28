package io.surisoft.capi.metrics;

import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.schema.CapiInfo;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class Info {

    private final CAPIConfiguration configuration;
    private final int totalRoutes;

    public Info(CAPIConfiguration configuration, int totalRoutes) {
        this.configuration = configuration;
        this.totalRoutes = totalRoutes;
    }

    public CapiInfo getInfo() {
        CapiInfo capiInfo = new CapiInfo();

        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        capiInfo.setUptime(Duration.ofMillis(uptimeMs).toString());
        capiInfo.setStartTimestamp(new java.util.Date(ManagementFactory.getRuntimeMXBean().getStartTime()));
        capiInfo.setTotalRoutes(totalRoutes);
        capiInfo.setCapiVersion(configuration.getVersion());
        capiInfo.setCapiNameSpace(configuration.getInstanceName());
        capiInfo.setJavaVersion(String.valueOf(Runtime.version()));

        // Ports
        capiInfo.setAdminPort(configuration.getAdminPort());
        if(configuration.getRest() != null) {
            capiInfo.setRestPort(configuration.getRest().getPort());
            if(configuration.getRest().getContextPath() != null) {
                capiInfo.setRoutesContextPath(configuration.getRest().getContextPath());
            }
        }

        // OAuth2
        if(configuration.getOauth2() != null) {
            capiInfo.setOauth2Enabled(configuration.getOauth2().isEnabled());
            if(configuration.getOauth2().getKeys() != null) {
                capiInfo.setOauth2Endpoint(String.join(",", configuration.getOauth2().getKeys()));
            }
            if(configuration.getOauth2().getCookieName() != null) {
                capiInfo.setOauth2CookieName(configuration.getOauth2().getCookieName());
            }
        }

        // OPA
        if(configuration.getOpa() != null) {
            capiInfo.setOpaEnabled(configuration.getOpa().isEnabled());
            capiInfo.setOpaEndpoint(configuration.getOpa().getEndpoint());
        }

        // Consul
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
        capiInfo.setConsulTimerInterval(configuration.getConsulCatalogDiscoverInterval());

        // Traces
        if(configuration.getTraces() != null) {
            capiInfo.setTracesEnabled(configuration.getTraces().isEnabled());
            capiInfo.setTracesEndpoint(configuration.getTraces().getEndpoint());
        }

        // Throttle
        capiInfo.setThrottleEnabled(configuration.getThrottle() != null && configuration.getThrottle().isEnabled());

        // Trust store
        capiInfo.setTrustStoreEnabled(configuration.getTrustStore() != null && configuration.getTrustStore().isEnabled());

        // SSL
        capiInfo.setSslEnabled(configuration.getSsl() != null && configuration.getSsl().isEnabled());

        // WebSocket
        if(configuration.getWebsocket() != null) {
            capiInfo.setWebsocketEnabled(configuration.getWebsocket().isEnabled());
            capiInfo.setWebsocketPort(configuration.getWebsocket().getPort());
        }

        // MCP
        if(configuration.getMcp() != null) {
            capiInfo.setMcpEnabled(configuration.getMcp().isEnabled());
            capiInfo.setMcpPort(configuration.getMcp().getPort());
        }

        // gRPC
        if(configuration.getGrpc() != null) {
            capiInfo.setGrpcEnabled(configuration.getGrpc().isEnabled());
            capiInfo.setGrpcPort(configuration.getGrpc().getPort());
        }

        // API Key Store
        capiInfo.setApiKeyStoreEnabled(configuration.getApiKeyStore() != null && configuration.getApiKeyStore().isEnabled());

        // Reverse proxy
        capiInfo.setReverseProxyHost(configuration.getReverseProxyHost());

        // CORS
        capiInfo.setCorsEnabled(configuration.isCorsEnabled());

        // Metrics
        capiInfo.setMetricsContextPath("/info/metrics");

        return capiInfo;
    }
}