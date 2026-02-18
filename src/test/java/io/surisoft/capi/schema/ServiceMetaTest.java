package io.surisoft.capi.schema;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServiceMetaTest {

    private ServiceMeta meta;

    @BeforeEach
    void setUp() {
        meta = new ServiceMeta();
    }

    @Test
    void rootContext_getterSetter() {
        meta.setRootContext("/api");
        assertEquals("/api", meta.getRootContext());
    }

    @Test
    void secured_getterSetter() {
        meta.setSecured(true);
        assertTrue(meta.isSecured());
    }

    @Test
    void group_getterSetter() {
        meta.setGroup("dev");
        assertEquals("dev", meta.getGroup());
    }

    @Test
    void b3TraceId_getterSetter() {
        meta.setB3TraceId(true);
        assertTrue(meta.isB3TraceId());
    }

    @Test
    void ingress_getterSetter() {
        meta.setIngress("ingress-host");
        assertEquals("ingress-host", meta.getIngress());
    }

    @Test
    void type_getterSetter() {
        meta.setType("rest");
        assertEquals("rest", meta.getType());
    }

    @Test
    void subscriptionGroup_getterSetter() {
        meta.setSubscriptionGroup("group-1");
        assertEquals("group-1", meta.getSubscriptionGroup());
    }

    @Test
    void allowSubscriptions_getterSetter() {
        meta.setAllowSubscriptions(true);
        assertTrue(meta.isAllowSubscriptions());
    }

    @Test
    void keepGroup_getterSetter() {
        meta.setKeepGroup(true);
        assertTrue(meta.isKeepGroup());
    }

    @Test
    void allowedOrigins_getterSetter() {
        meta.setAllowedOrigins("http://localhost");
        assertEquals("http://localhost", meta.getAllowedOrigins());
    }

    @Test
    void openApiEndpoint_getterSetter() {
        meta.setOpenApiEndpoint("/v3/api-docs");
        assertEquals("/v3/api-docs", meta.getOpenApiEndpoint());
    }

    @Test
    void opaRego_getterSetter() {
        meta.setOpaRego("my-rego");
        assertEquals("my-rego", meta.getOpaRego());
    }

    @Test
    void routeGroupFirst_getterSetter() {
        meta.setRouteGroupFirst(true);
        assertTrue(meta.isRouteGroupFirst());
    }

    @Test
    void throttle_getterSetter() {
        meta.setThrottle(true);
        assertTrue(meta.isThrottle());
    }

    @Test
    void throttleGlobal_getterSetter() {
        meta.setThrottleGlobal(true);
        assertTrue(meta.isThrottleGlobal());
    }

    @Test
    void throttleTotalCalls_getterSetter() {
        meta.setThrottleTotalCalls(100);
        assertEquals(100, meta.getThrottleTotalCalls());
    }

    @Test
    void throttleDuration_getterSetter() {
        meta.setThrottleDuration(60000);
        assertEquals(60000, meta.getThrottleDuration());
    }

    @Test
    void throttleTotalCalls_defaultIsMinusOne() {
        assertEquals(-1, meta.getThrottleTotalCalls());
    }

    @Test
    void throttleDuration_defaultIsMinusOne() {
        assertEquals(-1, meta.getThrottleDuration());
    }

    @Test
    void rateLimit_getterSetter() {
        meta.setRateLimit(true);
        assertTrue(meta.isRateLimit());
    }

    @Test
    void exposeOpenApiDefinition_getterSetter() {
        meta.setExposeOpenApiDefinition(true);
        assertTrue(meta.isExposeOpenApiDefinition());
    }

    @Test
    void secureOpenApiDefinition_getterSetter() {
        meta.setSecureOpenApiDefinition(true);
        assertTrue(meta.isSecureOpenApiDefinition());
    }

    @Test
    void state_getterSetter() {
        meta.setState(State.PUBLISHED);
        assertEquals(State.PUBLISHED, meta.getState());
    }

    @Test
    void version_getterSetter() {
        meta.setVersion("1.0");
        assertEquals("1.0", meta.getVersion());
    }

    @Test
    void schema_getterSetter() {
        meta.setSchema("https");
        assertEquals("https", meta.getSchema());
    }

    @Test
    void capiNamespace_getterSetter() {
        meta.setCapiNamespace("ns-1");
        assertEquals("ns-1", meta.getCapiNamespace());
    }

    // getNamespace() logic branches
    @Test
    void getNamespace_capiNamespaceSet_returnsCapiNamespace() {
        meta.setCapiNamespace("capi-ns");
        meta.setNamespace("fallback-ns");
        assertEquals("capi-ns", meta.getNamespace());
    }

    @Test
    void getNamespace_capiNamespaceEmpty_fallsBackToNamespace() {
        meta.setCapiNamespace("");
        meta.setNamespace("fallback-ns");
        assertEquals("fallback-ns", meta.getNamespace());
    }

    @Test
    void getNamespace_capiNamespaceNull_fallsBackToNamespace() {
        meta.setNamespace("fallback-ns");
        assertEquals("fallback-ns", meta.getNamespace());
    }

    @Test
    void getNamespace_bothNull_returnsNull() {
        assertNull(meta.getNamespace());
    }

    @Test
    void getNamespace_bothEmpty_returnsNull() {
        meta.setCapiNamespace("");
        meta.setNamespace("");
        assertNull(meta.getNamespace());
    }

    // getScheme() logic branches
    @Test
    void getScheme_schemeSet_returnsScheme() {
        meta.setScheme("https");
        assertEquals("https", meta.getScheme());
    }

    @Test
    void getScheme_schemeNull_schemaSet_migratesAndReturns() {
        meta.setSchema("http");
        assertEquals("http", meta.getScheme());
        // After migration, schema should be null
        assertNull(meta.getSchema());
    }

    @Test
    void getScheme_bothNull_returnsNull() {
        assertNull(meta.getScheme());
    }

    @Test
    void getScheme_schemeEmpty_schemaSet_migratesAndReturns() {
        meta.setScheme("");
        meta.setSchema("https");
        assertEquals("https", meta.getScheme());
    }

    // handleUnknown / getUnknownProperties
    @Test
    void handleUnknown_storesUnknownProperties() {
        meta.handleUnknown("custom-key", "custom-value");
        assertEquals("custom-value", meta.getUnknownProperties().get("custom-key"));
    }

    @Test
    void getUnknownProperties_emptyByDefault() {
        assertTrue(meta.getUnknownProperties().isEmpty());
    }

    // addExtraServiceMeta
    @Test
    void addExtraServiceMeta_addsToMap() {
        meta.addExtraServiceMeta("key1", "val1");
        assertEquals("val1", meta.getExtraServiceMeta().get("key1"));
    }

    @Test
    void addExtraServiceMeta_multipleEntries() {
        meta.addExtraServiceMeta("k1", "v1");
        meta.addExtraServiceMeta("k2", "v2");
        assertEquals(2, meta.getExtraServiceMeta().size());
    }

    @Test
    void getExtraServiceMeta_emptyByDefault() {
        assertTrue(meta.getExtraServiceMeta().isEmpty());
    }
}