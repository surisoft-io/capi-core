package io.surisoft.capi.utils;

import io.surisoft.capi.configuration.CAPIConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StartupTest {

    private CAPIConfiguration configuration;
    private Startup startup;

    @BeforeEach
    void setUp() {
        configuration = buildMinimalConfiguration();
    }

    @Test
    void start_minimalConfiguration_createsBasicComponents() {
        startup = new Startup(configuration);
        startup.start();

        assertNotNull(startup.getServiceCache());
        assertNotNull(startup.getHttpUtils());
        assertNotNull(startup.getRouteUtils());
        assertNotNull(startup.getConsulNodeDiscovery());
        assertNotNull(startup.getPrometheusRegistry());
        assertNotNull(startup.getRouteConsistencyChecker());
    }

    @Test
    void start_minimalConfig_oauth2ProviderIsNull() {
        startup = new Startup(configuration);
        startup.start();

        assertNull(startup.getOauth2Provider());
    }

    @Test
    void start_minimalConfig_websocketUtilsIsNotNull() {
        startup = new Startup(configuration);
        startup.start();

        // WebsocketUtils is always created (needed by RestGateway for proxy handlers)
        assertNotNull(startup.getWebsocketUtils());
    }

    @Test
    void start_minimalConfig_consulStoreIsNull() {
        startup = new Startup(configuration);
        startup.start();

        assertNull(startup.getConsulStore());
    }

    @Test
    void start_minimalConfig_opaServiceIsNull() {
        startup = new Startup(configuration);
        startup.start();

        assertNull(startup.getOpaService());
    }

    @Test
    void start_minimalConfig_undertowSslContextIsNull() {
        startup = new Startup(configuration);
        startup.start();

        assertNull(startup.getUndertowSslContext());
    }

    @Test
    void start_minimalConfig_capiTrustManagerIsNull() {
        startup = new Startup(configuration);
        startup.start();

        assertNull(startup.getCapiTrustManager());
    }

    @Test
    void start_minimalConfig_webSocketClientMapIsEmpty() {
        startup = new Startup(configuration);
        startup.start();

        assertNotNull(startup.getWebSocketClientMap());
        assertTrue(startup.getWebSocketClientMap().isEmpty());
    }

    @Test
    void start_withCorsEnabled() {
        configuration.setCorsEnabled(true);
        configuration.setAllowedHeaders(List.of("Content-Type", "Authorization"));

        startup = new Startup(configuration);
        startup.start();

        // Just verify it doesn't throw - cors filter strategy is now internal
        assertNotNull(startup.getRouteUtils());
    }

    @Test
    void start_withCorsDisabled() {
        configuration.setCorsEnabled(false);

        startup = new Startup(configuration);
        startup.start();

        assertNotNull(startup.getRouteUtils());
    }

    @Test
    void start_withReverseProxyHost() {
        configuration.setReverseProxyHost("proxy.example.com");

        startup = new Startup(configuration);
        startup.start();

        assertNotNull(startup.getConsulNodeDiscovery());
    }

    @Test
    void start_withInstanceName() {
        configuration.setInstanceName("my-instance");

        startup = new Startup(configuration);
        startup.start();

        assertNotNull(startup.getConsulNodeDiscovery());
    }

    @Test
    void start_withContextPath() {
        CAPIConfiguration.Rest rest = configuration.getRest();
        rest.setContextPath("/api");

        startup = new Startup(configuration);
        startup.start();

        assertNotNull(startup.getConsulNodeDiscovery());
    }

    @Test
    void start_withExtraMetadataPrefix() {
        CAPIConfiguration.Traces traces = configuration.getTraces();
        traces.setExtraMetadataPrefix("capi-extra-");

        startup = new Startup(configuration);
        startup.start();

        assertNotNull(startup.getConsulNodeDiscovery());
    }

    @Test
    void start_withOpaEnabled() {
        CAPIConfiguration.Opa opa = new CAPIConfiguration.Opa();
        opa.setEnabled(true);
        opa.setEndpoint("http://opa:8181");
        configuration.setOpa(opa);

        startup = new Startup(configuration);
        startup.start();

        assertNotNull(startup.getOpaService());
    }

    @Test
    void start_withSslDisabled() {
        CAPIConfiguration.Ssl ssl = new CAPIConfiguration.Ssl();
        ssl.setEnabled(false);
        configuration.setSsl(ssl);

        startup = new Startup(configuration);
        startup.start();

        assertNull(startup.getUndertowSslContext());
    }

    @Test
    void start_withNullSsl() {
        configuration.setSsl(null);

        startup = new Startup(configuration);
        startup.start();

        assertNull(startup.getUndertowSslContext());
    }

    @Test
    void start_withStrictToInstanceName() {
        configuration.setStrictToInstanceName(true);
        configuration.setInstanceName("strict-inst");

        startup = new Startup(configuration);
        startup.start();

        assertNotNull(startup.getConsulNodeDiscovery());
    }

    @Test
    void start_withEmptyReverseProxyHost() {
        configuration.setReverseProxyHost("");

        startup = new Startup(configuration);
        startup.start();

        assertNotNull(startup.getConsulNodeDiscovery());
    }

    @Test
    void start_withEmptyContextPath() {
        CAPIConfiguration.Rest rest = configuration.getRest();
        rest.setContextPath("");

        startup = new Startup(configuration);
        startup.start();

        assertNotNull(startup.getConsulNodeDiscovery());
    }

    @Test
    void start_withNullContextPath() {
        CAPIConfiguration.Rest rest = configuration.getRest();
        rest.setContextPath(null);

        startup = new Startup(configuration);
        startup.start();

        assertNotNull(startup.getConsulNodeDiscovery());
    }

    @Test
    void start_withNullInstanceName() {
        configuration.setInstanceName(null);

        startup = new Startup(configuration);
        startup.start();

        assertNotNull(startup.getConsulNodeDiscovery());
    }

    @Test
    void start_withNullExtraMetadataPrefix() {
        CAPIConfiguration.Traces traces = configuration.getTraces();
        traces.setExtraMetadataPrefix(null);

        startup = new Startup(configuration);
        startup.start();

        assertNotNull(startup.getConsulNodeDiscovery());
    }

    @Test
    void start_withConsulStoreEnabledButTrustStoreDisabled_consulStoreIsNull() {
        CAPIConfiguration.ConsulStore consulStore = new CAPIConfiguration.ConsulStore();
        consulStore.setEnabled(true);
        consulStore.setEndpoint("http://consul:8500/v1/kv/store");
        consulStore.setToken("token");
        configuration.setConsulStore(consulStore);

        startup = new Startup(configuration);
        startup.start();

        // TrustStore is disabled so ConsulStore won't be created
        assertNull(startup.getConsulStore());
    }

    @Test
    void start_withMultipleConsulHosts() {
        CAPIConfiguration.HostConfig host1 = new CAPIConfiguration.HostConfig();
        host1.setEndpoint("http://consul-1:8500");
        CAPIConfiguration.HostConfig host2 = new CAPIConfiguration.HostConfig();
        host2.setEndpoint("http://consul-2:8500");
        configuration.setConsulHosts(List.of(host1, host2));

        startup = new Startup(configuration);
        startup.start();

        assertNotNull(startup.getConsulNodeDiscovery());
    }

    @Test
    void start_withAllOptionalFeaturesDisabled() {
        configuration.setCorsEnabled(false);
        configuration.setReverseProxyHost(null);
        configuration.setInstanceName(null);
        CAPIConfiguration.Traces traces = configuration.getTraces();
        traces.setExtraMetadataPrefix(null);
        CAPIConfiguration.Rest rest = configuration.getRest();
        rest.setContextPath(null);

        startup = new Startup(configuration);
        startup.start();

        assertNotNull(startup.getServiceCache());
        assertNotNull(startup.getHttpUtils());
        assertNotNull(startup.getRouteUtils());
        assertNull(startup.getOauth2Provider());
        assertNotNull(startup.getWebsocketUtils());
        assertNull(startup.getConsulStore());
        assertNull(startup.getOpaService());
        assertNull(startup.getCapiTrustManager());
        assertNull(startup.getUndertowSslContext());
    }

    @Test
    void start_withDifferentRunningMode() {
        configuration.setRunningMode("websocket");

        startup = new Startup(configuration);
        startup.start();

        assertNotNull(startup.getConsulNodeDiscovery());
    }

    @Test
    void start_withCustomTimeouts() {
        CAPIConfiguration.Rest rest = configuration.getRest();
        rest.setResponseTimeout(60000);
        rest.setConnectionRequestTimeout(10000);
        rest.setRequestTimeout(15000);

        startup = new Startup(configuration);
        startup.start();

        assertNotNull(startup.getRouteUtils());
    }

    @Test
    void start_withMcpEnabled_createsMcpComponents() {
        CAPIConfiguration.Mcp mcp = new CAPIConfiguration.Mcp();
        mcp.setEnabled(true);
        mcp.setPort(8383);
        mcp.setSessionTtl(1800000);
        mcp.setToolCallTimeout(30000);
        mcp.setCircuitBreakerCooldownMs(30000);
        mcp.setMcpServerDiscoveryTimeoutMs(10000);
        configuration.setMcp(mcp);

        startup = new Startup(configuration);
        startup.start();

        assertNotNull(startup.getMcpToolRegistry());
        assertNotNull(startup.getMcpSessionStore());
        assertNotNull(startup.getMcpServerClient());
        assertNotNull(startup.getMcpLoadBalancer());
    }

    @Test
    void start_withMcpDisabled_mcpComponentsAreNull() {
        CAPIConfiguration.Mcp mcp = new CAPIConfiguration.Mcp();
        mcp.setEnabled(false);
        configuration.setMcp(mcp);

        startup = new Startup(configuration);
        startup.start();

        assertNull(startup.getMcpToolRegistry());
        assertNull(startup.getMcpSessionStore());
        assertNull(startup.getMcpServerClient());
        assertNull(startup.getMcpLoadBalancer());
    }

    @Test
    void start_withNullMcp_mcpComponentsAreNull() {
        configuration.setMcp(null);

        startup = new Startup(configuration);
        startup.start();

        assertNull(startup.getMcpToolRegistry());
        assertNull(startup.getMcpSessionStore());
    }

    private CAPIConfiguration buildMinimalConfiguration() {
        CAPIConfiguration config = new CAPIConfiguration();
        config.setVersion("1.0.0");
        config.setRunningMode("full");
        config.setCorsEnabled(false);
        config.setStrictToInstanceName(false);

        CAPIConfiguration.TrustStore trustStore = new CAPIConfiguration.TrustStore();
        trustStore.setEnabled(false);
        config.setTrustStore(trustStore);

        CAPIConfiguration.Oauth2 oauth2 = new CAPIConfiguration.Oauth2();
        oauth2.setEnabled(false);
        oauth2.setCookieName(null);
        config.setOauth2(oauth2);

        CAPIConfiguration.Traces traces = new CAPIConfiguration.Traces();
        traces.setEnabled(false);
        config.setTraces(traces);

        CAPIConfiguration.Websocket websocket = new CAPIConfiguration.Websocket();
        websocket.setEnabled(false);
        config.setWebsocket(websocket);

        CAPIConfiguration.Rest rest = new CAPIConfiguration.Rest();
        rest.setEnabled(true);
        rest.setResponseTimeout(120000);
        rest.setConnectionRequestTimeout(5000);
        rest.setRequestTimeout(5000);
        config.setRest(rest);

        CAPIConfiguration.HostConfig hostConfig = new CAPIConfiguration.HostConfig();
        hostConfig.setEndpoint("http://consul-host:8500");
        config.setConsulHosts(List.of(hostConfig));

        CAPIConfiguration.ConsulStore consulStore = new CAPIConfiguration.ConsulStore();
        consulStore.setEnabled(false);
        config.setConsulStore(consulStore);

        CAPIConfiguration.Opa opa = new CAPIConfiguration.Opa();
        opa.setEnabled(false);
        config.setOpa(opa);

        return config;
    }
}
