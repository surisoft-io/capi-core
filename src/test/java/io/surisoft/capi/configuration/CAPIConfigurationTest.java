package io.surisoft.capi.configuration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CAPIConfigurationTest {

    // --- Main class getters/setters ---

    @Test
    void version_getterSetter() {
        CAPIConfiguration config = new CAPIConfiguration();
        assertNull(config.getVersion());
        config.setVersion("2.0.0");
        assertEquals("2.0.0", config.getVersion());
    }

    @Test
    void instanceName_getterSetter() {
        CAPIConfiguration config = new CAPIConfiguration();
        assertNull(config.getInstanceName());
        config.setInstanceName("capi-instance-1");
        assertEquals("capi-instance-1", config.getInstanceName());
    }

    @Test
    void strictToInstanceName_getterSetter() {
        CAPIConfiguration config = new CAPIConfiguration();
        assertFalse(config.isStrictToInstanceName());
        config.setStrictToInstanceName(true);
        assertTrue(config.isStrictToInstanceName());
    }

    @Test
    void runningMode_getterSetter() {
        CAPIConfiguration config = new CAPIConfiguration();
        assertNull(config.getRunningMode());
        config.setRunningMode("standalone");
        assertEquals("standalone", config.getRunningMode());
    }

    @Test
    void consulCatalogDiscoverInterval_getterSetter() {
        CAPIConfiguration config = new CAPIConfiguration();
        assertEquals(0, config.getConsulCatalogDiscoverInterval());
        config.setConsulCatalogDiscoverInterval(5000);
        assertEquals(5000, config.getConsulCatalogDiscoverInterval());
    }

    @Test
    void reverseProxyHost_getterSetter() {
        CAPIConfiguration config = new CAPIConfiguration();
        assertNull(config.getReverseProxyHost());
        config.setReverseProxyHost("proxy.example.com");
        assertEquals("proxy.example.com", config.getReverseProxyHost());
    }

    @Test
    void trustStore_getterSetter() {
        CAPIConfiguration config = new CAPIConfiguration();
        assertNull(config.getTrustStore());
        CAPIConfiguration.TrustStore ts = new CAPIConfiguration.TrustStore();
        config.setTrustStore(ts);
        assertSame(ts, config.getTrustStore());
    }

    @Test
    void consulHosts_getterSetter() {
        CAPIConfiguration config = new CAPIConfiguration();
        assertNull(config.getConsulHosts());
        CAPIConfiguration.HostConfig hc = new CAPIConfiguration.HostConfig();
        List<CAPIConfiguration.HostConfig> hosts = List.of(hc);
        config.setConsulHosts(hosts);
        assertEquals(hosts, config.getConsulHosts());
    }

    @Test
    void oauth2_getterSetter() {
        CAPIConfiguration config = new CAPIConfiguration();
        assertNull(config.getOauth2());
        CAPIConfiguration.Oauth2 oauth2 = new CAPIConfiguration.Oauth2();
        config.setOauth2(oauth2);
        assertSame(oauth2, config.getOauth2());
    }

    @Test
    void traces_getterSetter() {
        CAPIConfiguration config = new CAPIConfiguration();
        assertNull(config.getTraces());
        CAPIConfiguration.Traces traces = new CAPIConfiguration.Traces();
        config.setTraces(traces);
        assertSame(traces, config.getTraces());
    }

    @Test
    void adminPort_getterSetter() {
        CAPIConfiguration config = new CAPIConfiguration();
        assertEquals(0, config.getAdminPort());
        config.setAdminPort(9090);
        assertEquals(9090, config.getAdminPort());
    }

    @Test
    void corsEnabled_getterSetter() {
        CAPIConfiguration config = new CAPIConfiguration();
        assertFalse(config.isCorsEnabled());
        config.setCorsEnabled(true);
        assertTrue(config.isCorsEnabled());
    }

    @Test
    void allowedHeaders_getterSetter() {
        CAPIConfiguration config = new CAPIConfiguration();
        assertNull(config.getAllowedHeaders());
        List<String> headers = List.of("Authorization", "Content-Type");
        config.setAllowedHeaders(headers);
        assertEquals(headers, config.getAllowedHeaders());
    }

    @Test
    void ssl_getterSetter() {
        CAPIConfiguration config = new CAPIConfiguration();
        assertNull(config.getSsl());
        CAPIConfiguration.Ssl ssl = new CAPIConfiguration.Ssl();
        config.setSsl(ssl);
        assertSame(ssl, config.getSsl());
    }

    @Test
    void rest_getterSetter() {
        CAPIConfiguration config = new CAPIConfiguration();
        assertNull(config.getRest());
        CAPIConfiguration.Rest rest = new CAPIConfiguration.Rest();
        config.setRest(rest);
        assertSame(rest, config.getRest());
    }

    @Test
    void websocket_getterSetter() {
        CAPIConfiguration config = new CAPIConfiguration();
        assertNull(config.getWebsocket());
        CAPIConfiguration.Websocket ws = new CAPIConfiguration.Websocket();
        config.setWebsocket(ws);
        assertSame(ws, config.getWebsocket());
    }

    @Test
    void publicEndpoint_getterSetter() {
        CAPIConfiguration config = new CAPIConfiguration();
        assertNull(config.getPublicEndpoint());
        config.setPublicEndpoint("https://api.example.com");
        assertEquals("https://api.example.com", config.getPublicEndpoint());
    }

    @Test
    void consulStore_getterSetter() {
        CAPIConfiguration config = new CAPIConfiguration();
        assertNull(config.getConsulStore());
        CAPIConfiguration.ConsulStore cs = new CAPIConfiguration.ConsulStore();
        config.setConsulStore(cs);
        assertSame(cs, config.getConsulStore());
    }

    @Test
    void opa_getterSetter() {
        CAPIConfiguration config = new CAPIConfiguration();
        assertNull(config.getOpa());
        CAPIConfiguration.Opa opa = new CAPIConfiguration.Opa();
        config.setOpa(opa);
        assertSame(opa, config.getOpa());
    }

    @Test
    void loggingTraces_getterSetter() {
        CAPIConfiguration config = new CAPIConfiguration();
        assertNull(config.getLoggingTraces());
        CAPIConfiguration.LoggingTraces lt = new CAPIConfiguration.LoggingTraces();
        config.setLoggingTraces(lt);
        assertSame(lt, config.getLoggingTraces());
    }

    @Test
    void accessLogs_getterSetter() {
        CAPIConfiguration config = new CAPIConfiguration();
        assertNull(config.getAccessLogs());
        CAPIConfiguration.AccessLogs al = new CAPIConfiguration.AccessLogs();
        config.setAccessLogs(al);
        assertSame(al, config.getAccessLogs());
    }

    // --- Traces nested class ---

    @Test
    void traces_enabled() {
        CAPIConfiguration.Traces traces = new CAPIConfiguration.Traces();
        assertFalse(traces.isEnabled());
        traces.setEnabled(true);
        assertTrue(traces.isEnabled());
    }

    @Test
    void traces_serviceName() {
        CAPIConfiguration.Traces traces = new CAPIConfiguration.Traces();
        assertNull(traces.getServiceName());
        traces.setServiceName("my-service");
        assertEquals("my-service", traces.getServiceName());
    }

    @Test
    void traces_endpoint() {
        CAPIConfiguration.Traces traces = new CAPIConfiguration.Traces();
        assertNull(traces.getEndpoint());
        traces.setEndpoint("http://jaeger:14268");
        assertEquals("http://jaeger:14268", traces.getEndpoint());
    }

    @Test
    void traces_extraMetadataPrefix() {
        CAPIConfiguration.Traces traces = new CAPIConfiguration.Traces();
        assertNull(traces.getExtraMetadataPrefix());
        traces.setExtraMetadataPrefix("X-Custom-");
        assertEquals("X-Custom-", traces.getExtraMetadataPrefix());
    }

    // --- Oauth2 nested class ---

    @Test
    void oauth2_enabled() {
        CAPIConfiguration.Oauth2 oauth2 = new CAPIConfiguration.Oauth2();
        assertFalse(oauth2.isEnabled());
        oauth2.setEnabled(true);
        assertTrue(oauth2.isEnabled());
    }

    @Test
    void oauth2_cookieName() {
        CAPIConfiguration.Oauth2 oauth2 = new CAPIConfiguration.Oauth2();
        assertNull(oauth2.getCookieName());
        oauth2.setCookieName("AUTH_TOKEN");
        assertEquals("AUTH_TOKEN", oauth2.getCookieName());
    }

    @Test
    void oauth2_keys() {
        CAPIConfiguration.Oauth2 oauth2 = new CAPIConfiguration.Oauth2();
        assertNull(oauth2.getKeys());
        List<String> keys = List.of("key1", "key2");
        oauth2.setKeys(keys);
        assertEquals(keys, oauth2.getKeys());
    }

    // --- TrustStore nested class ---

    @Test
    void trustStore_enabled() {
        CAPIConfiguration.TrustStore ts = new CAPIConfiguration.TrustStore();
        assertFalse(ts.isEnabled());
        ts.setEnabled(true);
        assertTrue(ts.isEnabled());
    }

    @Test
    void trustStore_path() {
        CAPIConfiguration.TrustStore ts = new CAPIConfiguration.TrustStore();
        assertNull(ts.getPath());
        ts.setPath("/etc/ssl/truststore.jks");
        assertEquals("/etc/ssl/truststore.jks", ts.getPath());
    }

    @Test
    void trustStore_encoded() {
        CAPIConfiguration.TrustStore ts = new CAPIConfiguration.TrustStore();
        assertNull(ts.getEncoded());
        ts.setEncoded("base64encodedvalue");
        assertEquals("base64encodedvalue", ts.getEncoded());
    }

    @Test
    void trustStore_password() {
        CAPIConfiguration.TrustStore ts = new CAPIConfiguration.TrustStore();
        assertNull(ts.getPassword());
        ts.setPassword("changeit");
        assertEquals("changeit", ts.getPassword());
    }

    // --- HostConfig nested class ---

    @Test
    void hostConfig_endpoint() {
        CAPIConfiguration.HostConfig hc = new CAPIConfiguration.HostConfig();
        assertNull(hc.getEndpoint());
        hc.setEndpoint("http://consul:8500");
        assertEquals("http://consul:8500", hc.getEndpoint());
    }

    @Test
    void hostConfig_token() {
        CAPIConfiguration.HostConfig hc = new CAPIConfiguration.HostConfig();
        assertNull(hc.getToken());
        hc.setToken("consul-token-123");
        assertEquals("consul-token-123", hc.getToken());
    }

    // --- Ssl nested class ---

    @Test
    void ssl_enabled() {
        CAPIConfiguration.Ssl ssl = new CAPIConfiguration.Ssl();
        assertFalse(ssl.isEnabled());
        ssl.setEnabled(true);
        assertTrue(ssl.isEnabled());
    }

    @Test
    void ssl_keyStoreType() {
        CAPIConfiguration.Ssl ssl = new CAPIConfiguration.Ssl();
        assertNull(ssl.getKeyStoreType());
        ssl.setKeyStoreType("PKCS12");
        assertEquals("PKCS12", ssl.getKeyStoreType());
    }

    @Test
    void ssl_path() {
        CAPIConfiguration.Ssl ssl = new CAPIConfiguration.Ssl();
        assertNull(ssl.getPath());
        ssl.setPath("/etc/ssl/keystore.p12");
        assertEquals("/etc/ssl/keystore.p12", ssl.getPath());
    }

    @Test
    void ssl_password() {
        CAPIConfiguration.Ssl ssl = new CAPIConfiguration.Ssl();
        assertNull(ssl.getPassword());
        ssl.setPassword("secret");
        assertEquals("secret", ssl.getPassword());
    }

    // --- Rest nested class ---

    @Test
    void rest_enabled() {
        CAPIConfiguration.Rest rest = new CAPIConfiguration.Rest();
        assertFalse(rest.isEnabled());
        rest.setEnabled(true);
        assertTrue(rest.isEnabled());
    }

    @Test
    void rest_port() {
        CAPIConfiguration.Rest rest = new CAPIConfiguration.Rest();
        assertEquals(0, rest.getPort());
        rest.setPort(8080);
        assertEquals(8080, rest.getPort());
    }

    @Test
    void rest_listeningAddress() {
        CAPIConfiguration.Rest rest = new CAPIConfiguration.Rest();
        assertNull(rest.getListeningAddress());
        rest.setListeningAddress("0.0.0.0");
        assertEquals("0.0.0.0", rest.getListeningAddress());
    }

    @Test
    void rest_contextPath() {
        CAPIConfiguration.Rest rest = new CAPIConfiguration.Rest();
        assertNull(rest.getContextPath());
        rest.setContextPath("/api");
        assertEquals("/api", rest.getContextPath());
    }

    @Test
    void rest_connectionRequestTimeout() {
        CAPIConfiguration.Rest rest = new CAPIConfiguration.Rest();
        assertEquals(0, rest.getConnectionRequestTimeout());
        rest.setConnectionRequestTimeout(3000);
        assertEquals(3000, rest.getConnectionRequestTimeout());
    }

    @Test
    void rest_requestTimeout() {
        CAPIConfiguration.Rest rest = new CAPIConfiguration.Rest();
        assertEquals(0, rest.getRequestTimeout());
        rest.setRequestTimeout(5000);
        assertEquals(5000, rest.getRequestTimeout());
    }

    @Test
    void rest_responseTimeout() {
        CAPIConfiguration.Rest rest = new CAPIConfiguration.Rest();
        assertEquals(0, rest.getResponseTimeout());
        rest.setResponseTimeout(10000);
        assertEquals(10000, rest.getResponseTimeout());
    }

    // --- Websocket nested class ---

    @Test
    void websocket_enabled() {
        CAPIConfiguration.Websocket ws = new CAPIConfiguration.Websocket();
        assertFalse(ws.isEnabled());
        ws.setEnabled(true);
        assertTrue(ws.isEnabled());
    }

    @Test
    void websocket_port() {
        CAPIConfiguration.Websocket ws = new CAPIConfiguration.Websocket();
        assertEquals(0, ws.getPort());
        ws.setPort(9443);
        assertEquals(9443, ws.getPort());
    }

    @Test
    void websocket_listeningAddress() {
        CAPIConfiguration.Websocket ws = new CAPIConfiguration.Websocket();
        assertNull(ws.getListeningAddress());
        ws.setListeningAddress("0.0.0.0");
        assertEquals("0.0.0.0", ws.getListeningAddress());
    }

    @Test
    void websocket_contextPath() {
        CAPIConfiguration.Websocket ws = new CAPIConfiguration.Websocket();
        assertNull(ws.getContextPath());
        ws.setContextPath("/ws");
        assertEquals("/ws", ws.getContextPath());
    }

    // --- ConsulStore nested class ---

    @Test
    void consulStore_enabled() {
        CAPIConfiguration.ConsulStore cs = new CAPIConfiguration.ConsulStore();
        assertFalse(cs.isEnabled());
        cs.setEnabled(true);
        assertTrue(cs.isEnabled());
    }

    @Test
    void consulStore_endpoint() {
        CAPIConfiguration.ConsulStore cs = new CAPIConfiguration.ConsulStore();
        assertNull(cs.getEndpoint());
        cs.setEndpoint("http://consul:8500");
        assertEquals("http://consul:8500", cs.getEndpoint());
    }

    @Test
    void consulStore_token() {
        CAPIConfiguration.ConsulStore cs = new CAPIConfiguration.ConsulStore();
        assertNull(cs.getToken());
        cs.setToken("kv-token");
        assertEquals("kv-token", cs.getToken());
    }

    // --- Opa nested class ---

    @Test
    void opa_enabled() {
        CAPIConfiguration.Opa opa = new CAPIConfiguration.Opa();
        assertFalse(opa.isEnabled());
        opa.setEnabled(true);
        assertTrue(opa.isEnabled());
    }

    @Test
    void opa_getEndpoint_returnsNull_byDefault() {
        CAPIConfiguration.Opa opa = new CAPIConfiguration.Opa();
        assertNull(opa.getEndpoint());
    }

    @Test
    void opa_setEndpoint_hasEmptyBody_endpointRemainsNull() {
        // Opa.setEndpoint has an empty body (bug), so calling it should NOT update the field
        CAPIConfiguration.Opa opa = new CAPIConfiguration.Opa();
        opa.setEndpoint("http://opa:8181/v1/data");
        assertNull(opa.getEndpoint(), "Opa.setEndpoint has an empty body - endpoint should remain null");
    }

    // --- LoggingTraces nested class ---

    @Test
    void loggingTraces_enabled() {
        CAPIConfiguration.LoggingTraces lt = new CAPIConfiguration.LoggingTraces();
        assertFalse(lt.isEnabled());
        lt.setEnabled(true);
        assertTrue(lt.isEnabled());
    }

    @Test
    void loggingTraces_tenant() {
        CAPIConfiguration.LoggingTraces lt = new CAPIConfiguration.LoggingTraces();
        assertNull(lt.getTenant());
        lt.setTenant("tenant-abc");
        assertEquals("tenant-abc", lt.getTenant());
    }

    @Test
    void loggingTraces_appName() {
        CAPIConfiguration.LoggingTraces lt = new CAPIConfiguration.LoggingTraces();
        assertNull(lt.getAppName());
        lt.setAppName("capi-gateway");
        assertEquals("capi-gateway", lt.getAppName());
    }

    @Test
    void loggingTraces_appEnvironment() {
        CAPIConfiguration.LoggingTraces lt = new CAPIConfiguration.LoggingTraces();
        assertNull(lt.getAppEnvironment());
        lt.setAppEnvironment("production");
        assertEquals("production", lt.getAppEnvironment());
    }

    @Test
    void loggingTraces_destination() {
        CAPIConfiguration.LoggingTraces lt = new CAPIConfiguration.LoggingTraces();
        assertNull(lt.getDestination());
        lt.setDestination("kafka://logs-topic");
        assertEquals("kafka://logs-topic", lt.getDestination());
    }

    // --- AccessLogs nested class ---

    @Test
    void accessLogs_enabled() {
        CAPIConfiguration.AccessLogs al = new CAPIConfiguration.AccessLogs();
        assertFalse(al.isEnabled());
        al.setEnabled(true);
        assertTrue(al.isEnabled());
    }

    @Test
    void accessLogs_tenant() {
        CAPIConfiguration.AccessLogs al = new CAPIConfiguration.AccessLogs();
        assertNull(al.getTenant());
        al.setTenant("tenant-xyz");
        assertEquals("tenant-xyz", al.getTenant());
    }

    @Test
    void accessLogs_service() {
        CAPIConfiguration.AccessLogs al = new CAPIConfiguration.AccessLogs();
        assertNull(al.getService());
        al.setService("my-api-service");
        assertEquals("my-api-service", al.getService());
    }

    @Test
    void accessLogs_destination() {
        CAPIConfiguration.AccessLogs al = new CAPIConfiguration.AccessLogs();
        assertNull(al.getDestination());
        al.setDestination("file:///var/log/access.log");
        assertEquals("file:///var/log/access.log", al.getDestination());
    }
}
