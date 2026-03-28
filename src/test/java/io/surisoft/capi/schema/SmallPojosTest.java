package io.surisoft.capi.schema;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SmallPojosTest {

    // AliasInfo
    @Test
    void aliasInfo_gettersSetters() {
        AliasInfo info = new AliasInfo();
        Date now = new Date();
        info.setAlias("my-alias");
        info.setIssuerDN("CN=Issuer");
        info.setSubjectDN("CN=Subject");
        info.setAdditionalInfo("extra");
        info.setServiceId("svc-1");
        info.setNotBefore(now);
        info.setNotAfter(now);

        assertEquals("my-alias", info.getAlias());
        assertEquals("CN=Issuer", info.getIssuerDN());
        assertEquals("CN=Subject", info.getSubjectDN());
        assertEquals("extra", info.getAdditionalInfo());
        assertEquals("svc-1", info.getServiceId());
        assertEquals(now, info.getNotBefore());
        assertEquals(now, info.getNotAfter());
    }

    // ConsulKeyStoreEntry
    @Test
    void consulKeyStoreEntry_gettersSetters() {
        ConsulKeyStoreEntry entry = new ConsulKeyStoreEntry();
        Set<String> processed = new HashSet<>(List.of("svc-1", "svc-2"));
        entry.setLocalIndex(1);
        entry.setKey("store-key");
        entry.setFlags(0);
        entry.setValue("base64value");
        entry.setCreateIndex(10);
        entry.setModifyIndex(20);
        entry.setServicesProcessed(processed);

        assertEquals(1, entry.getLocalIndex());
        assertEquals("store-key", entry.getKey());
        assertEquals(0, entry.getFlags());
        assertEquals("base64value", entry.getValue());
        assertEquals(10, entry.getCreateIndex());
        assertEquals(20, entry.getModifyIndex());
        assertEquals(processed, entry.getServicesProcessed());
    }

    // ConsulObject
    @Test
    void consulObject_gettersSetters() {
        ConsulObject obj = new ConsulObject();
        ServiceMeta meta = new ServiceMeta();
        obj.setID("id-1");
        obj.setServiceName("svc-name");
        obj.setServiceMeta(meta);
        obj.setServiceAddress("10.0.0.1");
        obj.setServicePort(8080);
        obj.setModifyIndex(5);

        assertEquals("id-1", obj.getID());
        assertEquals("svc-name", obj.getServiceName());
        assertSame(meta, obj.getServiceMeta());
        assertEquals("10.0.0.1", obj.getServiceAddress());
        assertEquals(8080, obj.getServicePort());
        assertEquals(5, obj.getModifyIndex());
    }

    // CapiRestError
    @Test
    void capiRestError_gettersSetters() {
        CapiRestError error = new CapiRestError();
        error.setRouteID("route-1");
        error.setErrorMessage("Something failed");
        error.setErrorCode(500);
        error.setHttpUri("/api/test");
        error.setTraceID("trace-123");

        assertEquals("route-1", error.getRouteID());
        assertEquals("Something failed", error.getErrorMessage());
        assertEquals(500, error.getErrorCode());
        assertEquals("/api/test", error.getHttpUri());
        assertEquals("trace-123", error.getTraceID());
    }

    // OpaResult
    @Test
    void opaResult_gettersSetters() {
        OpaResult result = new OpaResult();
        result.setResult(true);
        result.setTotalCallsAllowed(100);
        result.setDuration(5000);
        result.setConsumerKey("consumer-1");

        assertTrue(result.isAllowed());
        assertEquals(100, result.getTotalCallsAllowed());
        assertEquals(5000, result.getDuration());
        assertEquals("consumer-1", result.getConsumerKey());
    }

    @Test
    void opaResult_notAllowed() {
        OpaResult result = new OpaResult();
        result.setResult(false);
        assertFalse(result.isAllowed());
    }

    // OIDCClient
    @Test
    void oidcClient_gettersSetters() {
        OIDCClient client = new OIDCClient();
        client.setName("my-client");
        client.setClientId("client-id");
        client.setSecret("client-secret");
        client.setServiceAccountsEnabled(true);

        assertEquals("my-client", client.getName());
        assertEquals("client-id", client.getClientId());
        assertEquals("client-secret", client.getSecret());
        assertTrue(client.isServiceAccountsEnabled());
    }

    // MappingId
    @Test
    void mappingId_gettersSetters() {
        MappingId id = new MappingId();
        id.setRootContext("/api");
        id.setHostname("host1");
        id.setPort(8080);

        assertEquals("/api", id.getRootContext());
        assertEquals("host1", id.getHostname());
        assertEquals(8080, id.getPort());
    }

    // Group
    @Test
    void group_gettersSetters() {
        Group group = new Group();
        group.setId("g-1");
        group.setName("developers");
        group.setPath("/dev");

        assertEquals("g-1", group.getId());
        assertEquals("developers", group.getName());
        assertEquals("/dev", group.getPath());
    }

    // RunningTenant
    @Test
    void runningTenant_constructor() {
        RunningTenant tenant = new RunningTenant("tenant-1", 3);
        assertEquals("tenant-1", tenant.getTenant());
        assertEquals(3, tenant.getNodeIndex());
    }

    @Test
    void runningTenant_setters() {
        RunningTenant tenant = new RunningTenant("t", 0);
        tenant.setTenant("tenant-2");
        tenant.setNodeIndex(5);
        assertEquals("tenant-2", tenant.getTenant());
        assertEquals(5, tenant.getNodeIndex());
    }

    // SubscriptionGroup
    @Test
    void subscriptionGroup_gettersSetters() {
        SubscriptionGroup sg = new SubscriptionGroup();
        Map<String, Set<String>> services = new HashMap<>();
        services.put("svc-1", Set.of("role-a", "role-b"));
        sg.setServices(services);
        assertEquals(services, sg.getServices());
    }

    // State enum
    @Test
    void state_values() {
        assertEquals(3, State.values().length);
        assertNotNull(State.valueOf("PUBLISHED"));
        assertNotNull(State.valueOf("CREATED"));
        assertNotNull(State.valueOf("SUSPENDED"));
    }

    // HttpProtocol enum
    @Test
    void httpProtocol_values() {
        assertEquals("http", HttpProtocol.HTTP.getProtocol());
        assertEquals("https", HttpProtocol.HTTPS.getProtocol());
    }

    @Test
    void httpProtocol_setProtocol() {
        HttpProtocol proto = HttpProtocol.HTTP;
        proto.setProtocol("custom");
        assertEquals("custom", proto.getProtocol());
        // Reset
        proto.setProtocol("http");
    }

    // HttpMethod enum
    @Test
    void httpMethod_values() {
        assertEquals("all", HttpMethod.ALL.getMethod());
        assertEquals("get", HttpMethod.GET.getMethod());
        assertEquals("post", HttpMethod.POST.getMethod());
        assertEquals("put", HttpMethod.PUT.getMethod());
        assertEquals("delete", HttpMethod.DELETE.getMethod());
        assertEquals("patch", HttpMethod.PATCH.getMethod());
    }

    @Test
    void httpMethod_setMethod() {
        HttpMethod method = HttpMethod.GET;
        method.setMethod("custom");
        assertEquals("custom", method.getMethod());
        method.setMethod("get");
    }

    // GrpcClient
    @Test
    void grpcClient_gettersSetters() {
        GrpcClient client = new GrpcClient();
        Set<Mapping> mappings = new HashSet<>();
        client.setServiceId("grpc-svc");
        client.setPath("/grpc");
        client.setMappingList(mappings);
        client.setRequiresSubscription(true);
        client.setSubscriptionRole("admin");
        client.setRootContext("/root");

        assertEquals("grpc-svc", client.getServiceId());
        assertEquals("/grpc", client.getPath());
        assertSame(mappings, client.getMappingList());
        assertTrue(client.isRequiresSubscription());
        assertEquals("admin", client.getSubscriptionRole());
        assertEquals("/root", client.getRootContext());
    }

    // WebsocketClient
    @Test
    void websocketClient_gettersSetters() {
        WebsocketClient client = new WebsocketClient();
        Set<Mapping> mappings = new HashSet<>();
        client.setServiceId("ws-svc");
        client.setPath("/ws");
        client.setMappingList(mappings);
        client.setRequiresSubscription(true);
        client.setSubscriptionRole("user");
        client.setRootContext("/root");

        assertEquals("ws-svc", client.getServiceId());
        assertEquals("/ws", client.getPath());
        assertSame(mappings, client.getMappingList());
        assertTrue(client.isRequiresSubscription());
        assertEquals("user", client.getSubscriptionRole());
        assertEquals("/root", client.getRootContext());
    }

    // Mapping
    @Test
    void mapping_gettersSetters() {
        Mapping mapping = new Mapping();
        mapping.setRootContext("/api");
        mapping.setHostname("host1");
        mapping.setPort(8080);
        mapping.setIngress(true);
        mapping.setTenandId("tenant-1");

        assertEquals("/api", mapping.getRootContext());
        assertEquals("host1", mapping.getHostname());
        assertEquals(8080, mapping.getPort());
        assertTrue(mapping.isIngress());
        assertEquals("tenant-1", mapping.getTenandId());
    }

    @Test
    void mapping_defaultPort() {
        Mapping mapping = new Mapping();
        assertEquals(-1, mapping.getPort());
    }

    @Test
    void mapping_equals_sameValues() {
        Mapping m1 = new Mapping();
        m1.setRootContext("/api");
        m1.setHostname("host1");
        m1.setPort(8080);

        Mapping m2 = new Mapping();
        m2.setRootContext("/api");
        m2.setHostname("host1");
        m2.setPort(8080);

        assertEquals(m1, m2);
        assertEquals(m1.hashCode(), m2.hashCode());
    }

    @Test
    void mapping_equals_differentValues() {
        Mapping m1 = new Mapping();
        m1.setRootContext("/api");
        m1.setHostname("host1");
        m1.setPort(8080);

        Mapping m2 = new Mapping();
        m2.setRootContext("/api");
        m2.setHostname("host2");
        m2.setPort(8080);

        assertNotEquals(m1, m2);
    }

    @Test
    void mapping_equals_null() {
        Mapping m1 = new Mapping();
        m1.setRootContext("/api");
        m1.setHostname("host1");
        m1.setPort(8080);
        assertNotEquals(m1, null);
    }

    @Test
    void mapping_equals_differentType() {
        Mapping m1 = new Mapping();
        m1.setRootContext("/api");
        m1.setHostname("host1");
        m1.setPort(8080);
        assertNotEquals(m1, "not a mapping");
    }

    // ServiceCapiInstances
    @Test
    void serviceCapiInstances_gettersSetters() {
        ServiceCapiInstances sci = new ServiceCapiInstances();
        assertNotNull(sci.getInstances());
        assertTrue(sci.getInstances().isEmpty());

        Map<String, ServiceCapiInstances.Instance> instances = new HashMap<>();
        instances.put("inst-1", new ServiceCapiInstances.Instance());
        sci.setSections(instances);
        assertEquals(1, sci.getInstances().size());
    }

    @Test
    void serviceCapiInstances_instance_gettersSetters() {
        ServiceCapiInstances.Instance inst = new ServiceCapiInstances.Instance();
        inst.setSecured(true);
        inst.setOpenApi("/v3/api-docs");
        inst.setRouteGroupFirst(true);
        inst.setScheme("https");
        inst.setIgnoreOpenApi(true);
        inst.setAssumeParentSecured(false);
        inst.setAssumeParentRouteGroupFirst(false);

        assertTrue(inst.isSecured());
        assertEquals("/v3/api-docs", inst.getOpenApi());
        assertTrue(inst.isRouteGroupFirst());
        assertEquals("https", inst.getScheme());
        assertTrue(inst.isIgnoreOpenApi());
        assertFalse(inst.isAssumeParentSecured());
        assertFalse(inst.isAssumeParentRouteGroupFirst());
    }

    @Test
    void serviceCapiInstances_instance_defaults() {
        ServiceCapiInstances.Instance inst = new ServiceCapiInstances.Instance();
        assertTrue(inst.isAssumeParentSecured());
        assertTrue(inst.isAssumeParentRouteGroupFirst());
        assertFalse(inst.isSecured());
        assertFalse(inst.isRouteGroupFirst());
        assertFalse(inst.isIgnoreOpenApi());
    }

    // CapiInfo (already partially tested but let's cover remaining fields)
    @Test
    void capiInfo_basic() {
        CapiInfo info = new CapiInfo();
        info.setCapiVersion("1.0");
        assertEquals("1.0", info.getCapiVersion());
    }

    @Test
    void capiInfo_javaVersion() {
        CapiInfo info = new CapiInfo();
        info.setJavaVersion("21.0.1");
        assertEquals("21.0.1", info.getJavaVersion());
    }

    @Test
    void capiInfo_startTimestamp() {
        CapiInfo info = new CapiInfo();
        Date now = new Date();
        info.setStartTimestamp(now);
        assertEquals(now, info.getStartTimestamp());
    }

    @Test
    void capiInfo_totalRoutes() {
        CapiInfo info = new CapiInfo();
        info.setTotalRoutes(42);
        assertEquals(42, info.getTotalRoutes());
    }

    @Test
    void capiInfo_uptime() {
        CapiInfo info = new CapiInfo();
        info.setUptime("2h 30m");
        assertEquals("2h 30m", info.getUptime());
    }

    @Test
    void capiInfo_capiNameSpace() {
        CapiInfo info = new CapiInfo();
        info.setCapiNameSpace("default");
        assertEquals("default", info.getCapiNameSpace());
    }

    @Test
    void capiInfo_oauth2Enabled() {
        CapiInfo info = new CapiInfo();
        info.setOauth2Enabled(true);
        assertTrue(info.isOauth2Enabled());
        info.setOauth2Enabled(false);
        assertFalse(info.isOauth2Enabled());
    }

    @Test
    void capiInfo_oauth2Endpoint() {
        CapiInfo info = new CapiInfo();
        info.setOauth2Endpoint("http://keycloak/realms/capi");
        assertEquals("http://keycloak/realms/capi", info.getOauth2Endpoint());
    }

    @Test
    void capiInfo_opaEnabled() {
        CapiInfo info = new CapiInfo();
        info.setOpaEnabled(true);
        assertTrue(info.isOpaEnabled());
        info.setOpaEnabled(false);
        assertFalse(info.isOpaEnabled());
    }

    @Test
    void capiInfo_opaEndpoint() {
        CapiInfo info = new CapiInfo();
        info.setOpaEndpoint("http://opa:8181/v1/data");
        assertEquals("http://opa:8181/v1/data", info.getOpaEndpoint());
    }

    @Test
    void capiInfo_consulEnabled() {
        CapiInfo info = new CapiInfo();
        info.setConsulEnabled(true);
        assertTrue(info.isConsulEnabled());
        info.setConsulEnabled(false);
        assertFalse(info.isConsulEnabled());
    }

    @Test
    void capiInfo_consulHosts() {
        CapiInfo info = new CapiInfo();
        List<String> hosts = List.of("http://consul1:8500", "http://consul2:8500");
        info.setConsulHosts(hosts);
        assertEquals(hosts, info.getConsulHosts());
    }

    @Test
    void capiInfo_consulTimerInterval() {
        CapiInfo info = new CapiInfo();
        info.setConsulTimerInterval(5000);
        assertEquals(5000, info.getConsulTimerInterval());
    }

    @Test
    void capiInfo_routesContextPath() {
        CapiInfo info = new CapiInfo();
        info.setRoutesContextPath("/capi/routes");
        assertEquals("/capi/routes", info.getRoutesContextPath());
    }

    @Test
    void capiInfo_metricsContextPath() {
        CapiInfo info = new CapiInfo();
        info.setMetricsContextPath("/capi/metrics");
        assertEquals("/capi/metrics", info.getMetricsContextPath());
    }

    @Test
    void capiInfo_tracesEnabled() {
        CapiInfo info = new CapiInfo();
        info.setTracesEnabled(true);
        assertTrue(info.isTracesEnabled());
        info.setTracesEnabled(false);
        assertFalse(info.isTracesEnabled());
    }

    @Test
    void capiInfo_tracesEndpoint() {
        CapiInfo info = new CapiInfo();
        info.setTracesEndpoint("http://jaeger:14268/api/traces");
        assertEquals("http://jaeger:14268/api/traces", info.getTracesEndpoint());
    }

    @Test
    void capiInfo_allFieldsSetAndRetrieved() {
        CapiInfo info = new CapiInfo();
        Date now = new Date();

        info.setJavaVersion("21");
        info.setCapiVersion("4.0");
        info.setStartTimestamp(now);
        info.setTotalRoutes(50);
        info.setUptime("5d 3h");
        info.setCapiNameSpace("prod");
        info.setOauth2Enabled(true);
        info.setOauth2Endpoint("http://auth.example.com");
        info.setOpaEnabled(false);
        info.setOpaEndpoint(null);
        info.setConsulEnabled(true);
        info.setConsulHosts(List.of("consul-1:8500"));
        info.setConsulTimerInterval(10000);
        info.setRoutesContextPath("/routes");
        info.setMetricsContextPath("/metrics");
        info.setTracesEnabled(true);
        info.setTracesEndpoint("http://traces.example.com");

        assertEquals("21", info.getJavaVersion());
        assertEquals("4.0", info.getCapiVersion());
        assertEquals(now, info.getStartTimestamp());
        assertEquals(50, info.getTotalRoutes());
        assertEquals("5d 3h", info.getUptime());
        assertEquals("prod", info.getCapiNameSpace());
        assertTrue(info.isOauth2Enabled());
        assertEquals("http://auth.example.com", info.getOauth2Endpoint());
        assertFalse(info.isOpaEnabled());
        assertNull(info.getOpaEndpoint());
        assertTrue(info.isConsulEnabled());
        assertEquals(List.of("consul-1:8500"), info.getConsulHosts());
        assertEquals(10000, info.getConsulTimerInterval());
        assertEquals("/routes", info.getRoutesContextPath());
        assertEquals("/metrics", info.getMetricsContextPath());
        assertTrue(info.isTracesEnabled());
        assertEquals("http://traces.example.com", info.getTracesEndpoint());
    }

    @Test
    void capiInfo_defaultValues() {
        CapiInfo info = new CapiInfo();
        assertNull(info.getJavaVersion());
        assertNull(info.getCapiVersion());
        assertNull(info.getStartTimestamp());
        assertNull(info.getTotalRoutes());
        assertNull(info.getUptime());
        assertNull(info.getCapiNameSpace());
        assertFalse(info.isOauth2Enabled());
        assertNull(info.getOauth2Endpoint());
        assertFalse(info.isOpaEnabled());
        assertNull(info.getOpaEndpoint());
        assertFalse(info.isConsulEnabled());
        assertNull(info.getConsulHosts());
        assertEquals(0, info.getConsulTimerInterval());
        assertNull(info.getRoutesContextPath());
        assertNull(info.getMetricsContextPath());
        assertFalse(info.isTracesEnabled());
        assertNull(info.getTracesEndpoint());
    }
}