package io.surisoft.capi.metrics;

import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.schema.CapiInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InfoTest {

    private CAPIConfiguration configuration;

    @BeforeEach
    void setUp() {
        configuration = new CAPIConfiguration();
        configuration.setVersion("1.0.0");
        configuration.setInstanceName("test-instance");
    }

    @Test
    void getInfo_basicFields() {
        Info info = new Info(configuration, 0);
        CapiInfo capiInfo = info.getInfo();

        assertEquals("1.0.0", capiInfo.getCapiVersion());
        assertEquals("test-instance", capiInfo.getCapiNameSpace());
        assertEquals(0, capiInfo.getTotalRoutes());
        assertNotNull(capiInfo.getJavaVersion());
    }

    @Test
    void getInfo_oauth2Enabled() {
        CAPIConfiguration.Oauth2 oauth2 = new CAPIConfiguration.Oauth2();
        oauth2.setEnabled(true);
        oauth2.setKeys(List.of("http://keycloak/certs", "http://keycloak2/certs"));
        configuration.setOauth2(oauth2);

        CapiInfo capiInfo = new Info(configuration, 0).getInfo();

        assertTrue(capiInfo.isOauth2Enabled());
        assertEquals("http://keycloak/certs,http://keycloak2/certs", capiInfo.getOauth2Endpoint());
    }

    @Test
    void getInfo_oauth2Null() {
        configuration.setOauth2(null);
        CapiInfo capiInfo = new Info(configuration, 0).getInfo();
        assertFalse(capiInfo.isOauth2Enabled());
    }

    @Test
    void getInfo_consulHostsPresent() {
        CAPIConfiguration.HostConfig host = new CAPIConfiguration.HostConfig();
        host.setEndpoint("http://consul:8500");
        configuration.setConsulHosts(List.of(host));

        CapiInfo capiInfo = new Info(configuration, 0).getInfo();

        assertTrue(capiInfo.isConsulEnabled());
        assertEquals(List.of("http://consul:8500"), capiInfo.getConsulHosts());
    }

    @Test
    void getInfo_consulHostsNull() {
        configuration.setConsulHosts(null);
        CapiInfo capiInfo = new Info(configuration, 0).getInfo();

        assertFalse(capiInfo.isConsulEnabled());
        assertTrue(capiInfo.getConsulHosts().isEmpty());
    }

    @Test
    void getInfo_tracesEnabled() {
        CAPIConfiguration.Traces traces = new CAPIConfiguration.Traces();
        traces.setEnabled(true);
        traces.setEndpoint("http://zipkin:9411");
        configuration.setTraces(traces);

        CapiInfo capiInfo = new Info(configuration, 0).getInfo();

        assertTrue(capiInfo.isTracesEnabled());
        assertEquals("http://zipkin:9411", capiInfo.getTracesEndpoint());
    }

    @Test
    void getInfo_tracesNull() {
        configuration.setTraces(null);
        CapiInfo capiInfo = new Info(configuration, 0).getInfo();
        assertFalse(capiInfo.isTracesEnabled());
    }
}
