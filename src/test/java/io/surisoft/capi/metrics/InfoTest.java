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

    @Test
    void getInfo_restConfig() {
        CAPIConfiguration.Rest rest = new CAPIConfiguration.Rest();
        rest.setPort(8380);
        rest.setContextPath("/api/v1");
        configuration.setRest(rest);

        CapiInfo capiInfo = new Info(configuration, 5).getInfo();

        assertEquals(8380, capiInfo.getRestPort());
        assertEquals("/api/v1", capiInfo.getRoutesContextPath());
        assertEquals(5, capiInfo.getTotalRoutes());
    }

    @Test
    void getInfo_restNullContextPath() {
        CAPIConfiguration.Rest rest = new CAPIConfiguration.Rest();
        rest.setPort(8380);
        rest.setContextPath(null);
        configuration.setRest(rest);

        CapiInfo capiInfo = new Info(configuration, 0).getInfo();
        assertEquals(8380, capiInfo.getRestPort());
        assertNull(capiInfo.getRoutesContextPath());
    }

    @Test
    void getInfo_restNull() {
        configuration.setRest(null);
        CapiInfo capiInfo = new Info(configuration, 0).getInfo();
        assertEquals(0, capiInfo.getRestPort());
    }

    @Test
    void getInfo_opaConfig() {
        CAPIConfiguration.Opa opa = new CAPIConfiguration.Opa();
        opa.setEnabled(true);
        opa.setWasmBundleUrl("http://opa-bundle-server:8080/bundles/");
        configuration.setOpa(opa);

        CapiInfo capiInfo = new Info(configuration, 0).getInfo();

        assertTrue(capiInfo.isOpaEnabled());
        assertEquals("http://opa-bundle-server:8080/bundles/", capiInfo.getOpaWasmBundleUrl());
    }

    @Test
    void getInfo_opaNull() {
        configuration.setOpa(null);
        CapiInfo capiInfo = new Info(configuration, 0).getInfo();
        assertFalse(capiInfo.isOpaEnabled());
    }

    @Test
    void getInfo_websocketConfig() {
        CAPIConfiguration.Websocket ws = new CAPIConfiguration.Websocket();
        ws.setEnabled(true);
        ws.setPort(8382);
        configuration.setWebsocket(ws);

        CapiInfo capiInfo = new Info(configuration, 0).getInfo();

        assertTrue(capiInfo.isWebsocketEnabled());
        assertEquals(8382, capiInfo.getWebsocketPort());
    }

    @Test
    void getInfo_websocketNull() {
        configuration.setWebsocket(null);
        CapiInfo capiInfo = new Info(configuration, 0).getInfo();
        assertFalse(capiInfo.isWebsocketEnabled());
    }

    @Test
    void getInfo_mcpConfig() {
        CAPIConfiguration.Mcp mcp = new CAPIConfiguration.Mcp();
        mcp.setEnabled(true);
        mcp.setPort(8383);
        configuration.setMcp(mcp);

        CapiInfo capiInfo = new Info(configuration, 0).getInfo();

        assertTrue(capiInfo.isMcpEnabled());
        assertEquals(8383, capiInfo.getMcpPort());
    }

    @Test
    void getInfo_mcpNull() {
        configuration.setMcp(null);
        CapiInfo capiInfo = new Info(configuration, 0).getInfo();
        assertFalse(capiInfo.isMcpEnabled());
    }

    @Test
    void getInfo_grpcConfig() {
        CAPIConfiguration.Grpc grpc = new CAPIConfiguration.Grpc();
        grpc.setEnabled(true);
        grpc.setPort(8384);
        configuration.setGrpc(grpc);

        CapiInfo capiInfo = new Info(configuration, 0).getInfo();

        assertTrue(capiInfo.isGrpcEnabled());
        assertEquals(8384, capiInfo.getGrpcPort());
    }

    @Test
    void getInfo_grpcNull() {
        configuration.setGrpc(null);
        CapiInfo capiInfo = new Info(configuration, 0).getInfo();
        assertFalse(capiInfo.isGrpcEnabled());
    }

    @Test
    void getInfo_throttleEnabled() {
        CAPIConfiguration.Throttle throttle = new CAPIConfiguration.Throttle();
        throttle.setEnabled(true);
        configuration.setThrottle(throttle);

        CapiInfo capiInfo = new Info(configuration, 0).getInfo();
        assertTrue(capiInfo.isThrottleEnabled());
    }

    @Test
    void getInfo_throttleNull() {
        configuration.setThrottle(null);
        CapiInfo capiInfo = new Info(configuration, 0).getInfo();
        assertFalse(capiInfo.isThrottleEnabled());
    }

    @Test
    void getInfo_trustStoreEnabled() {
        CAPIConfiguration.TrustStore ts = new CAPIConfiguration.TrustStore();
        ts.setEnabled(true);
        configuration.setTrustStore(ts);

        CapiInfo capiInfo = new Info(configuration, 0).getInfo();
        assertTrue(capiInfo.isTrustStoreEnabled());
    }

    @Test
    void getInfo_trustStoreNull() {
        configuration.setTrustStore(null);
        CapiInfo capiInfo = new Info(configuration, 0).getInfo();
        assertFalse(capiInfo.isTrustStoreEnabled());
    }

    @Test
    void getInfo_sslEnabled() {
        CAPIConfiguration.Ssl ssl = new CAPIConfiguration.Ssl();
        ssl.setEnabled(true);
        configuration.setSsl(ssl);

        CapiInfo capiInfo = new Info(configuration, 0).getInfo();
        assertTrue(capiInfo.isSslEnabled());
    }

    @Test
    void getInfo_sslNull() {
        configuration.setSsl(null);
        CapiInfo capiInfo = new Info(configuration, 0).getInfo();
        assertFalse(capiInfo.isSslEnabled());
    }

    @Test
    void getInfo_apiKeyStoreEnabled() {
        CAPIConfiguration.ApiKeyStore aks = new CAPIConfiguration.ApiKeyStore();
        aks.setEnabled(true);
        configuration.setApiKeyStore(aks);

        CapiInfo capiInfo = new Info(configuration, 0).getInfo();
        assertTrue(capiInfo.isApiKeyStoreEnabled());
    }

    @Test
    void getInfo_apiKeyStoreNull() {
        configuration.setApiKeyStore(null);
        CapiInfo capiInfo = new Info(configuration, 0).getInfo();
        assertFalse(capiInfo.isApiKeyStoreEnabled());
    }

    @Test
    void getInfo_reverseProxyHost() {
        configuration.setReverseProxyHost("proxy.example.com");

        CapiInfo capiInfo = new Info(configuration, 0).getInfo();
        assertEquals("proxy.example.com", capiInfo.getReverseProxyHost());
    }

    @Test
    void getInfo_corsEnabled() {
        configuration.setCorsEnabled(true);

        CapiInfo capiInfo = new Info(configuration, 0).getInfo();
        assertTrue(capiInfo.isCorsEnabled());
    }

    @Test
    void getInfo_adminPort() {
        configuration.setAdminPort(8381);

        CapiInfo capiInfo = new Info(configuration, 0).getInfo();
        assertEquals(8381, capiInfo.getAdminPort());
    }

    @Test
    void getInfo_metricsContextPath() {
        CapiInfo capiInfo = new Info(configuration, 0).getInfo();
        assertEquals("/info/metrics", capiInfo.getMetricsContextPath());
    }

    @Test
    void getInfo_uptimeAndStartTimestamp() {
        CapiInfo capiInfo = new Info(configuration, 0).getInfo();
        assertNotNull(capiInfo.getUptime());
        assertNotNull(capiInfo.getStartTimestamp());
    }

    @Test
    void getInfo_oauth2WithCookieName() {
        CAPIConfiguration.Oauth2 oauth2 = new CAPIConfiguration.Oauth2();
        oauth2.setEnabled(true);
        oauth2.setCookieName("my-auth-cookie");
        configuration.setOauth2(oauth2);

        CapiInfo capiInfo = new Info(configuration, 0).getInfo();
        assertEquals("my-auth-cookie", capiInfo.getOauth2CookieName());
    }

    @Test
    void getInfo_oauth2WithNullKeys() {
        CAPIConfiguration.Oauth2 oauth2 = new CAPIConfiguration.Oauth2();
        oauth2.setEnabled(false);
        oauth2.setKeys(null);
        configuration.setOauth2(oauth2);

        CapiInfo capiInfo = new Info(configuration, 0).getInfo();
        assertFalse(capiInfo.isOauth2Enabled());
        assertNull(capiInfo.getOauth2Endpoint());
    }

    @Test
    void getInfo_consulTimerInterval() {
        configuration.setConsulCatalogDiscoverInterval(5000);
        CapiInfo capiInfo = new Info(configuration, 0).getInfo();
        assertEquals(5000, capiInfo.getConsulTimerInterval());
    }
}
