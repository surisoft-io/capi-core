package io.surisoft.capi.service.consul;

import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.schema.InvalidService;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import io.surisoft.capi.utils.ServiceUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * The four spec-compliance verdicts and the report-vs-enforce split. The point of the design is that
 * the verdict is recorded whether or not the switch is on, so an estate can be measured first.
 */
class SpecComplianceTest {

    private ConsulCatalogService catalogService;
    private Map<String, InvalidService> invalidServiceMap;

    @BeforeEach
    void setUp() {
        invalidServiceMap = new HashMap<>();
        catalogService = new ConsulCatalogService(
                List.of(), null, mock(ServiceUtils.class), List.of(), null,
                "capi-1", false, null, invalidServiceMap);
    }

    @AfterEach
    void tearDown() {
        catalogService.shutdown();
    }

    private static Service service(String name) {
        ServiceMeta meta = new ServiceMeta();
        meta.setGroup("v1");
        meta.setOpenApiEndpoint("http://" + name + ":8080/openapi.json");
        Service svc = new Service();
        svc.setName(name);
        svc.setId(name + ":v1");
        svc.setServiceMeta(meta);
        return svc;
    }

    /** @return true when the service must be held back from routing */
    private boolean record(Service svc, String mismatch) throws Exception {
        Method m = ConsulCatalogService.class.getDeclaredMethod(
                "recordSpecCompliance", Service.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(catalogService, svc, mismatch);
    }

    private ConsulCatalogService.SpecCompliance verdictOf(String serviceId) {
        return catalogService.getSpecCompliance().get(serviceId);
    }

    private void enable(boolean enabled, String... exempt) {
        CAPIConfiguration.MatchOpenApiSpec cfg = new CAPIConfiguration.MatchOpenApiSpec();
        cfg.setEnabled(enabled);
        if (exempt.length > 0) {
            cfg.setExempt(List.of(exempt));
        }
        catalogService.setMatchOpenApiSpec(cfg);
    }

    @Test
    void compliant_isRecordedAndNeverBlocked() throws Exception {
        enable(true);
        assertFalse(record(service("sample-service"), null));
        assertEquals(ConsulCatalogService.Verdict.COMPLIANT, verdictOf("sample-service:v1").verdict());
        assertTrue(invalidServiceMap.isEmpty());
    }

    @Test
    void switchOff_recordsNonCompliantButDoesNotBlock() throws Exception {
        enable(false);
        assertFalse(record(service("sample-service"), "title mismatch"),
                "report-only must never hold a service back");
        ConsulCatalogService.SpecCompliance v = verdictOf("sample-service:v1");
        assertEquals(ConsulCatalogService.Verdict.NON_COMPLIANT, v.verdict());
        assertEquals("title mismatch", v.detail());
        // Nothing lands in invalid-services either: report-only must be invisible to routing.
        assertTrue(invalidServiceMap.isEmpty());
    }

    @Test
    void switchOn_blocksAndRecordsTheReason() throws Exception {
        enable(true);
        assertTrue(record(service("sample-service"), "title mismatch"));
        assertEquals(ConsulCatalogService.Verdict.NON_COMPLIANT, verdictOf("sample-service:v1").verdict());
        InvalidService invalid = invalidServiceMap.get("sample-service:v1");
        assertNotNull(invalid);
        assertEquals(InvalidService.Reason.OPENAPI_IDENTITY_MISMATCH, invalid.reason());
        assertEquals("title mismatch", invalid.detail());
    }

    @Test
    void exemptService_keepsRoutingWhileEnforcing() throws Exception {
        enable(true, "legacy-service");
        assertFalse(record(service("legacy-service"), "title mismatch"),
                "an exempt service must keep routing");
        assertEquals(ConsulCatalogService.Verdict.EXEMPT, verdictOf("legacy-service:v1").verdict());
        // The mismatch is still reported, so the exemption stays visible as debt.
        assertEquals("title mismatch", verdictOf("legacy-service:v1").detail());
        assertTrue(invalidServiceMap.isEmpty());
    }

    @Test
    void exemptionIsByServiceName_notServiceId() throws Exception {
        // One spec describes an API registered into several groups, so the exemption is per name.
        enable(true, "legacy-service");
        assertFalse(record(service("legacy-service"), "mismatch"));
        assertEquals(ConsulCatalogService.Verdict.EXEMPT, verdictOf("legacy-service:v1").verdict());
    }

    @Test
    void exemptingOneServiceDoesNotExemptAnother() throws Exception {
        enable(true, "legacy-service");
        assertFalse(record(service("legacy-service"), "mismatch"));
        assertTrue(record(service("other-service"), "mismatch"),
                "an exemption must not leak to other services");
        assertEquals(ConsulCatalogService.Verdict.NON_COMPLIANT, verdictOf("other-service:v1").verdict());
    }

    @Test
    void complianceMapIsExposedForTheAdminReport() {
        assertNotNull(catalogService.getSpecCompliance());
        assertTrue(catalogService.getSpecCompliance().isEmpty());
    }
}
