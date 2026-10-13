package io.surisoft.capi.configuration;

import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.KubernetesConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import io.surisoft.capi.schema.McpSession;
import io.surisoft.capi.schema.ThrottleServiceObject;

public class HazelcastCacheConfiguration {

    private static final String CLUSTER_NAME = "capi-throttle";
    private static final String MAP_NAME = "throttle-cache";

    private HazelcastCacheConfiguration() {}

    public static IMap<String, ThrottleServiceObject> createThrottleCache(CAPIConfiguration.Throttle throttle) {
        Config config = buildConfig(throttle);
        HazelcastInstance instance = Hazelcast.newHazelcastInstance(config);
        return instance.getMap(MAP_NAME);
    }

    private static final String WEBDAV_CLUSTER_NAME = "capi-webdav";
    private static final String WEBDAV_SESSION_MAP = "webdav-session-cache";
    private static final String WEBDAV_TICKET_MAP = "webdav-ticket-cache";
    private static volatile HazelcastInstance webDavInstance;

    /**
     * One Hazelcast instance serving both WebDAV maps.
     *
     * <p>Sessions and tickets are separate maps but share an instance: a second cluster member per
     * map would double the discovery and heartbeat traffic for no benefit.
     */
    private static synchronized HazelcastInstance webDavInstance(CAPIConfiguration.Throttle throttle) {
        if (webDavInstance == null) {
            Config config = new Config();
            config.setClusterName(WEBDAV_CLUSTER_NAME);
            applyKubernetesDiscovery(config, throttle);
            webDavInstance = Hazelcast.newHazelcastInstance(config);
        }
        return webDavInstance;
    }

    /** Kubernetes discovery settings, shared with the MCP session map. */
    private static void applyKubernetesDiscovery(Config config, CAPIConfiguration.Throttle throttle) {
        if (throttle == null) {
            return;
        }
        String serviceName = throttle.getKubernetesServiceName();
        if (serviceName == null || serviceName.isEmpty()) {
            return;
        }
        JoinConfig joinConfig = config.getNetworkConfig().getJoin();
        joinConfig.getMulticastConfig().setEnabled(false);
        KubernetesConfig kubernetesConfig = joinConfig.getKubernetesConfig();
        kubernetesConfig.setEnabled(true);
        kubernetesConfig.setProperty("service-name", serviceName);
        String namespace = throttle.getKubernetesNamespace();
        if (namespace != null && !namespace.isEmpty()) {
            kubernetesConfig.setProperty("namespace", namespace);
        }
    }

    public static IMap<String, String> createWebDavSessionMap(CAPIConfiguration.Throttle throttle) {
        return webDavInstance(throttle).getMap(WEBDAV_SESSION_MAP);
    }

    public static IMap<String, String> createWebDavTicketMap(CAPIConfiguration.Throttle throttle) {
        return webDavInstance(throttle).getMap(WEBDAV_TICKET_MAP);
    }

    private static final String MCP_CLUSTER_NAME = "capi-mcp";
    private static final String MCP_MAP_NAME = "mcp-session-cache";

    public static IMap<String, McpSession> createMcpSessionMap(CAPIConfiguration.Throttle throttle) {
        Config config = new Config();
        config.setClusterName(MCP_CLUSTER_NAME);
        if (throttle != null) {
            String serviceName = throttle.getKubernetesServiceName();
            if (serviceName != null && !serviceName.isEmpty()) {
                JoinConfig joinConfig = config.getNetworkConfig().getJoin();
                joinConfig.getMulticastConfig().setEnabled(false);
                KubernetesConfig kubernetesConfig = joinConfig.getKubernetesConfig();
                kubernetesConfig.setEnabled(true);
                kubernetesConfig.setProperty("service-name", serviceName);
                String namespace = throttle.getKubernetesNamespace();
                if (namespace != null && !namespace.isEmpty()) {
                    kubernetesConfig.setProperty("namespace", namespace);
                }
            }
        }
        HazelcastInstance instance = Hazelcast.newHazelcastInstance(config);
        return instance.getMap(MCP_MAP_NAME);
    }

    static Config buildConfig(CAPIConfiguration.Throttle throttle) {
        Config config = new Config();
        config.setClusterName(CLUSTER_NAME);

        String serviceName = throttle.getKubernetesServiceName();
        if (serviceName != null && !serviceName.isEmpty()) {
            JoinConfig joinConfig = config.getNetworkConfig().getJoin();
            joinConfig.getMulticastConfig().setEnabled(false);

            KubernetesConfig kubernetesConfig = joinConfig.getKubernetesConfig();
            kubernetesConfig.setEnabled(true);
            kubernetesConfig.setProperty("service-name", serviceName);

            String namespace = throttle.getKubernetesNamespace();
            if (namespace != null && !namespace.isEmpty()) {
                kubernetesConfig.setProperty("namespace", namespace);
            }
        }

        return config;
    }
}