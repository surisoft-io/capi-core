package io.surisoft.capi.metrics;

import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.schema.CapiInfo;
import org.apache.camel.CamelContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InfoTest {

    @Mock
    private CamelContext camelContext;

    private CAPIConfiguration configuration;

    @BeforeEach
    void setUp() {
        when(camelContext.getUptime()).thenReturn(Duration.ofMinutes(5));
        when(camelContext.getVersion()).thenReturn("4.17.0");
        when(camelContext.getRoutesSize()).thenReturn(10);

        configuration = new CAPIConfiguration();
        configuration.setVersion("1.0.0");
        configuration.setInstanceName("test-instance");
    }

    @Test
    void getInfo_basicFields() {
        Info info = new Info(configuration, camelContext);
        CapiInfo capiInfo = info.getInfo();

        assertEquals("1.0.0", capiInfo.getCapiVersion());
        assertEquals("test-instance", capiInfo.getCapiNameSpace());
        assertEquals("4.17.0", capiInfo.getCamelVersion());
        assertEquals(10, capiInfo.getTotalRoutes());
        assertNotNull(capiInfo.getJavaVersion());
        assertNotNull(capiInfo.getUptime());
    }

    @Test
    void getInfo_oauth2Enabled() {
        CAPIConfiguration.Oauth2 oauth2 = new CAPIConfiguration.Oauth2();
        oauth2.setEnabled(true);
        oauth2.setKeys(List.of("http://keycloak/certs", "http://keycloak2/certs"));
        configuration.setOauth2(oauth2);

        CapiInfo capiInfo = new Info(configuration, camelContext).getInfo();

        assertTrue(capiInfo.isOauth2Enabled());
        assertEquals("http://keycloak/certs,http://keycloak2/certs", capiInfo.getOauth2Endpoint());
    }

    @Test
    void getInfo_oauth2Null() {
        configuration.setOauth2(null);
        CapiInfo capiInfo = new Info(configuration, camelContext).getInfo();
        assertFalse(capiInfo.isOauth2Enabled());
    }

    @Test
    void getInfo_consulHostsPresent() {
        CAPIConfiguration.HostConfig host = new CAPIConfiguration.HostConfig();
        host.setEndpoint("http://consul:8500");
        configuration.setConsulHosts(List.of(host));

        CapiInfo capiInfo = new Info(configuration, camelContext).getInfo();

        assertTrue(capiInfo.isConsulEnabled());
        assertEquals(List.of("http://consul:8500"), capiInfo.getConsulHosts());
    }

    @Test
    void getInfo_consulHostsNull() {
        configuration.setConsulHosts(null);
        CapiInfo capiInfo = new Info(configuration, camelContext).getInfo();

        assertFalse(capiInfo.isConsulEnabled());
        assertTrue(capiInfo.getConsulHosts().isEmpty());
    }

    @Test
    void getInfo_tracesEnabled() {
        CAPIConfiguration.Traces traces = new CAPIConfiguration.Traces();
        traces.setEnabled(true);
        traces.setEndpoint("http://zipkin:9411");
        configuration.setTraces(traces);

        CapiInfo capiInfo = new Info(configuration, camelContext).getInfo();

        assertTrue(capiInfo.isTracesEnabled());
        assertEquals("http://zipkin:9411", capiInfo.getTracesEndpoint());
    }

    @Test
    void getInfo_tracesNull() {
        configuration.setTraces(null);
        CapiInfo capiInfo = new Info(configuration, camelContext).getInfo();
        assertFalse(capiInfo.isTracesEnabled());
    }
}