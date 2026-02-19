package io.surisoft.capi.configuration;

import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.KubernetesConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import io.surisoft.capi.schema.ThrottleServiceObject;

public class HazelcastCacheConfiguration {

    private static final String CLUSTER_NAME = "capi-throttle";
    private static final String MAP_NAME = "throttle-cache";

    private HazelcastCacheConfiguration() {}

    public static IMap<String, ThrottleServiceObject> createThrottleCache(CAPIConfiguration.Throttle throttle) {
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

        HazelcastInstance instance = Hazelcast.newHazelcastInstance(config);
        return instance.getMap(MAP_NAME);
    }
}
