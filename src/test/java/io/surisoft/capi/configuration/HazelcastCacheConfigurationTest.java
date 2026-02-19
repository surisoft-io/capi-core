package io.surisoft.capi.configuration;

import com.hazelcast.config.Config;
import com.hazelcast.config.KubernetesConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HazelcastCacheConfigurationTest {

    @Test
    void buildConfig_noKubernetesServiceName_defaultJoinConfig() {
        CAPIConfiguration.Throttle throttle = new CAPIConfiguration.Throttle();

        Config config = HazelcastCacheConfiguration.buildConfig(throttle);

        assertEquals("capi-throttle", config.getClusterName());
        // Kubernetes discovery should NOT be enabled
        assertFalse(config.getNetworkConfig().getJoin().getKubernetesConfig().isEnabled());
    }

    @Test
    void buildConfig_emptyKubernetesServiceName_defaultJoinConfig() {
        CAPIConfiguration.Throttle throttle = new CAPIConfiguration.Throttle();
        throttle.setKubernetesServiceName("");

        Config config = HazelcastCacheConfiguration.buildConfig(throttle);

        assertFalse(config.getNetworkConfig().getJoin().getKubernetesConfig().isEnabled());
    }

    @Test
    void buildConfig_withServiceName_kubernetesEnabledMulticastDisabled() {
        CAPIConfiguration.Throttle throttle = new CAPIConfiguration.Throttle();
        throttle.setKubernetesServiceName("capi");

        Config config = HazelcastCacheConfiguration.buildConfig(throttle);

        assertFalse(config.getNetworkConfig().getJoin().getMulticastConfig().isEnabled());
        KubernetesConfig k8s = config.getNetworkConfig().getJoin().getKubernetesConfig();
        assertTrue(k8s.isEnabled());
        assertEquals("capi", k8s.getProperty("service-name"));
    }

    @Test
    void buildConfig_withServiceNameAndNamespace_setsNamespaceProperty() {
        CAPIConfiguration.Throttle throttle = new CAPIConfiguration.Throttle();
        throttle.setKubernetesServiceName("capi");
        throttle.setKubernetesNamespace("production");

        Config config = HazelcastCacheConfiguration.buildConfig(throttle);

        KubernetesConfig k8s = config.getNetworkConfig().getJoin().getKubernetesConfig();
        assertTrue(k8s.isEnabled());
        assertEquals("capi", k8s.getProperty("service-name"));
        assertEquals("production", k8s.getProperty("namespace"));
    }

    @Test
    void buildConfig_withServiceNameAndEmptyNamespace_noNamespaceProperty() {
        CAPIConfiguration.Throttle throttle = new CAPIConfiguration.Throttle();
        throttle.setKubernetesServiceName("capi");
        throttle.setKubernetesNamespace("");

        Config config = HazelcastCacheConfiguration.buildConfig(throttle);

        KubernetesConfig k8s = config.getNetworkConfig().getJoin().getKubernetesConfig();
        assertTrue(k8s.isEnabled());
        assertEquals("capi", k8s.getProperty("service-name"));
        assertNull(k8s.getProperty("namespace"));
    }
}
