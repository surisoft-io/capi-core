package io.surisoft.capi.service.consul;

import io.surisoft.capi.processor.ServiceCapiInstanceMapper;
import io.surisoft.capi.schema.InvalidService;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import io.surisoft.capi.utils.ServiceUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * {@code capi-instance-<name>-opa-rego}: the policy a given CAPI instance applies to a service.
 *
 * <p>Exercised through reflection: {@code applyPerInstanceOverrides} is private and runs as part of
 * building a service from the Consul catalog.
 */
class ConsulCatalogInstanceOpaRegoTest {

    private static final String THIS_INSTANCE = "capi1";

    private ConsulCatalogService catalogService;

    @BeforeEach
    void setUp() {
        catalogService = new ConsulCatalogService(
                List.of(), null, mock(ServiceUtils.class), List.of(), null,
                THIS_INSTANCE, false, null, new HashMap<String, InvalidService>());
    }

    @AfterEach
    void tearDown() {
        catalogService.shutdown();
    }

    private static Service service(String serviceLevelRego, Map<String, String> instanceMeta) {
        ServiceMeta meta = new ServiceMeta();
        meta.setGroup("v1");
        meta.setOpaRego(serviceLevelRego);
        Service svc = new Service();
        svc.setId("orders:v1");
        svc.setServiceMeta(meta);
        svc.setMappingList(new HashSet<>());
        if (!instanceMeta.isEmpty()) {
            svc.setServiceCapiInstances(new ServiceCapiInstanceMapper().convert(instanceMeta));
        }
        return svc;
    }

    private void applyOverrides(Service svc) throws Exception {
        Method m = ConsulCatalogService.class.getDeclaredMethod("applyPerInstanceOverrides", Service.class);
        m.setAccessible(true);
        m.invoke(catalogService, svc);
    }

    @Test
    void thisInstanceOverride_replacesServicePolicy() throws Exception {
        Service svc = service("capi/default", Map.of("capi-instance-capi1-opa-rego", "capi/deny_external"));
        applyOverrides(svc);
        assertEquals("capi/deny_external", svc.getServiceMeta().getOpaRego());
    }

    @Test
    void thisInstanceOverride_addsPolicyWhereServiceHasNone() throws Exception {
        Service svc = service(null, Map.of("capi-instance-capi1-opa-rego", "capi/deny_external"));
        applyOverrides(svc);
        assertEquals("capi/deny_external", svc.getServiceMeta().getOpaRego());
    }

    @Test
    void thisInstanceBlankOverride_removesServicePolicy() throws Exception {
        Service svc = service("capi/default", Map.of("capi-instance-capi1-opa-rego", ""));
        applyOverrides(svc);
        assertNull(svc.getServiceMeta().getOpaRego());
    }

    @Test
    void otherInstanceOverride_leavesServicePolicy() throws Exception {
        Service svc = service("capi/default", Map.of("capi-instance-capi2-opa-rego", "capi/deny_external"));
        applyOverrides(svc);
        assertEquals("capi/default", svc.getServiceMeta().getOpaRego());
    }

    @Test
    void otherOverrideOnThisInstance_leavesServicePolicy() throws Exception {
        Service svc = service("capi/default", Map.of("capi-instance-capi1-scheme", "https"));
        applyOverrides(svc);
        assertEquals("capi/default", svc.getServiceMeta().getOpaRego());
    }
}
