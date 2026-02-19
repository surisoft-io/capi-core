package io.surisoft.capi.configuration;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import io.surisoft.capi.schema.ThrottleServiceObject;

public class HazelcastCacheConfiguration {

    private static final String CLUSTER_NAME = "capi-throttle";
    private static final String MAP_NAME = "throttle-cache";

    private HazelcastCacheConfiguration() {}

    public static IMap<String, ThrottleServiceObject> createThrottleCache() {
        Config config = new Config();
        config.setClusterName(CLUSTER_NAME);
        HazelcastInstance instance = Hazelcast.newHazelcastInstance(config);
        return instance.getMap(MAP_NAME);
    }
}
