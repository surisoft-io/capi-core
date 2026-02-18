package io.surisoft.capi.utils;

import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.service.ConsulNodeDiscovery;
import org.apache.camel.CamelContext;
import org.apache.camel.component.http.HttpComponent;
import org.apache.camel.spi.Registry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StartupTest {

    @Mock
    private CamelContext camelContext;

    @Mock
    private Registry registry;

    private CAPIConfiguration configuration;
    private Startup startup;

    @BeforeEach
    void setUp() {
        configuration = buildMinimalConfiguration();
    }

    @Test
    void start_minimalConfiguration_createsBasicComponents() {
        when(camelContext.getRegistry()).thenReturn(registry);

        startup = new Startup(configuration, camelContext);
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
        when(camelContext.getRegistry()).thenReturn(registry);

        startup = new Startup(configuration, camelContext);
        startup.start();

        assertNull(startup.getOauth2Provider());
    }

    @Test
    void start_minimalConfig_websocketUtilsIsNull() {
        when(camelContext.getRegistry()).thenReturn(registry);

        startup = new Startup(configuration, camelContext);
        startup.start();

        assertNull(startup.getWebsocketUtils());
    }

    @Test
    void start_minimalConfig_consulStoreIsNull() {
        when(camelContext.getRegistry()).thenReturn(registry);

        startup = new Startup(configuration, camelContext);
        startup.start();

        assertNull(startup.getConsulStore());
    }

    @Test
    void start_minimalConfig_opaServiceIsNull() {
        when(camelContext.getRegistry()).thenReturn(registry);

        startup = new Startup(configuration, camelContext);
        startup.start();

        assertNull(startup.getOpaService());
    }

    @Test
    void start_minimalConfig_undertowSslContextIsNull() {
        when(camelContext.getRegistry()).thenReturn(registry);

        startup = new Startup(configuration, camelContext);
        startup.start();

        assertNull(startup.getUndertowSslContext());
    }

    @Test
    void start_minimalConfig_capiTrustManagerIsNull() {
        when(camelContext.getRegistry()).thenReturn(registry);

        startup = new Startup(configuration, camelContext);
        startup.start();

        assertNull(startup.getCapiTrustManager());
    }

    @Test
    void start_minimalConfig_webSocketClientMapIsEmpty() {
        when(camelContext.getRegistry()).thenReturn(registry);

        startup = new Startup(configuration, camelContext);
        startup.start();

        assertNotNull(startup.getWebSocketClientMap());
        assertTrue(startup.getWebSocketClientMap().isEmpty());
    }

    @Test
    void start_withCorsEnabled() {
        configuration.setCorsEnabled(true);
        configuration.setAllowedHeaders(List.of("Content-Type", "Authorization"));
        when(camelContext.getRegistry()).thenReturn(registry);

        startup = new Startup(configuration, camelContext);
        startup.start();

        verify(registry).bind(eq("capiCorsFilterStrategy"), any());
    }

    @Test
    void start_withCorsDisabled() {
        configuration.setCorsEnabled(false);
        when(camelContext.getRegistry()).thenReturn(registry);

        startup = new Startup(configuration, camelContext);
        startup.start();

        verify(registry, never()).bind(eq("capiCorsFilterStrategy"), any());
    }

    @Test
    void start_withReverseProxyHost() {
        configuration.setReverseProxyHost("proxy.example.com");
        when(camelContext.getRegistry()).thenReturn(registry);

        startup = new Startup(configuration, camelContext);
        startup.start();

        assertNotNull(startup.getConsulNodeDiscovery());
    }

    @Test
    void start_withInstanceName() {
        configuration.setInstanceName("my-instance");
        when(camelContext.getRegistry()).thenReturn(registry);

        startup = new Startup(configuration, camelContext);
        startup.start();

        assertNotNull(startup.getConsulNodeDiscovery());
    }

    @Test
    void start_withContextPath() {
        CAPIConfiguration.Rest rest = configuration.getRest();
        rest.setContextPath("/api");
        when(camelContext.getRegistry()).thenReturn(registry);

        startup = new Startup(configuration, camelContext);
        startup.start();

        assertNotNull(startup.getConsulNodeDiscovery());
    }

    @Test
    void start_withExtraMetadataPrefix() {
        CAPIConfiguration.Traces traces = configuration.getTraces();
        traces.setExtraMetadataPrefix("capi-extra-");
        when(camelContext.getRegistry()).thenReturn(registry);

        startup = new Startup(configuration, camelContext);
        startup.start();

        assertNotNull(startup.getConsulNodeDiscovery());
    }

    @Test
    void start_withOpaEnabled() {
        CAPIConfiguration.Opa opa = new CAPIConfiguration.Opa();
        opa.setEnabled(true);
        opa.setEndpoint("http://opa:8181");
        configuration.setOpa(opa);
        when(camelContext.getRegistry()).thenReturn(registry);

        startup = new Startup(configuration, camelContext);
        startup.start();

        assertNotNull(startup.getOpaService());
    }

    @Test
    void start_withSslDisabled() {
        CAPIConfiguration.Ssl ssl = new CAPIConfiguration.Ssl();
        ssl.setEnabled(false);
        configuration.setSsl(ssl);
        when(camelContext.getRegistry()).thenReturn(registry);

        startup = new Startup(configuration, camelContext);
        startup.start();

        assertNull(startup.getUndertowSslContext());
    }

    @Test
    void start_withNullSsl() {
        configuration.setSsl(null);
        when(camelContext.getRegistry()).thenReturn(registry);

        startup = new Startup(configuration, camelContext);
        startup.start();

        assertNull(startup.getUndertowSslContext());
    }

    @Test
    void start_withStrictToInstanceName() {
        configuration.setStrictToInstanceName(true);
        configuration.setInstanceName("strict-inst");
        when(camelContext.getRegistry()).thenReturn(registry);

        startup = new Startup(configuration, camelContext);
        startup.start();

        assertNotNull(startup.getConsulNodeDiscovery());
    }

    @Test
    void start_withEmptyReverseProxyHost() {
        configuration.setReverseProxyHost("");
        when(camelContext.getRegistry()).thenReturn(registry);

        startup = new Startup(configuration, camelContext);
        startup.start();

        assertNotNull(startup.getConsulNodeDiscovery());
    }

    @Test
    void start_withEmptyContextPath() {
        CAPIConfiguration.Rest rest = configuration.getRest();
        rest.setContextPath("");
        when(camelContext.getRegistry()).thenReturn(registry);

        startup = new Startup(configuration, camelContext);
        startup.start();

        assertNotNull(startup.getConsulNodeDiscovery());
    }

    @Test
    void start_withNullContextPath() {
        CAPIConfiguration.Rest rest = configuration.getRest();
        rest.setContextPath(null);
        when(camelContext.getRegistry()).thenReturn(registry);

        startup = new Startup(configuration, camelContext);
        startup.start();

        assertNotNull(startup.getConsulNodeDiscovery());
    }

    @Test
    void start_withNullInstanceName() {
        configuration.setInstanceName(null);
        when(camelContext.getRegistry()).thenReturn(registry);

        startup = new Startup(configuration, camelContext);
        startup.start();

        assertNotNull(startup.getConsulNodeDiscovery());
    }

    @Test
    void start_withNullExtraMetadataPrefix() {
        CAPIConfiguration.Traces traces = configuration.getTraces();
        traces.setExtraMetadataPrefix(null);
        when(camelContext.getRegistry()).thenReturn(registry);

        startup = new Startup(configuration, camelContext);
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

        when(camelContext.getRegistry()).thenReturn(registry);

        startup = new Startup(configuration, camelContext);
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
        when(camelContext.getRegistry()).thenReturn(registry);

        startup = new Startup(configuration, camelContext);
        startup.start();

        assertNotNull(startup.getConsulNodeDiscovery());
    }

    @Test
    void start_withAllOptionalFeaturesDisabled() {
        // Ensure all optional features are explicitly disabled
        configuration.setCorsEnabled(false);
        configuration.setReverseProxyHost(null);
        configuration.setInstanceName(null);
        CAPIConfiguration.Traces traces = configuration.getTraces();
        traces.setExtraMetadataPrefix(null);
        CAPIConfiguration.Rest rest = configuration.getRest();
        rest.setContextPath(null);

        when(camelContext.getRegistry()).thenReturn(registry);

        startup = new Startup(configuration, camelContext);
        startup.start();

        assertNotNull(startup.getServiceCache());
        assertNotNull(startup.getHttpUtils());
        assertNotNull(startup.getRouteUtils());
        assertNull(startup.getOauth2Provider());
        assertNull(startup.getWebsocketUtils());
        assertNull(startup.getConsulStore());
        assertNull(startup.getOpaService());
        assertNull(startup.getCapiTrustManager());
        assertNull(startup.getUndertowSslContext());
    }

    @Test
    void start_withDifferentRunningMode() {
        configuration.setRunningMode("websocket");
        when(camelContext.getRegistry()).thenReturn(registry);

        startup = new Startup(configuration, camelContext);
        startup.start();

        assertNotNull(startup.getConsulNodeDiscovery());
    }

    @Test
    void start_withCustomTimeouts() {
        CAPIConfiguration.Rest rest = configuration.getRest();
        rest.setResponseTimeout(60000);
        rest.setConnectionRequestTimeout(10000);
        rest.setRequestTimeout(15000);
        when(camelContext.getRegistry()).thenReturn(registry);

        startup = new Startup(configuration, camelContext);
        startup.start();

        assertNotNull(startup.getRouteUtils());
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
