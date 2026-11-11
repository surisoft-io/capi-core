package io.surisoft.capi.processor;

import io.surisoft.capi.schema.ServiceCapiInstances;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ServiceCapiInstanceMapperTest {

    private final ServiceCapiInstanceMapper mapper = new ServiceCapiInstanceMapper();

    @Test
    void convert_singleInstance_allProperties() {
        Map<String, String> flat = new HashMap<>();
        flat.put("capi-instance-prod-secured", "true");
        flat.put("capi-instance-prod-scheme", "https");
        flat.put("capi-instance-prod-open-api", "http://spec.example.com");
        flat.put("capi-instance-prod-route-group-first", "true");
        flat.put("capi-instance-prod-ignore-open-api", "false");

        ServiceCapiInstances result = mapper.convert(flat);

        assertTrue(result.getInstances().containsKey("prod"));
        ServiceCapiInstances.Instance instance = result.getInstances().get("prod");
        assertTrue(instance.isSecured());
        assertFalse(instance.isAssumeParentSecured());
        assertEquals("https", instance.getScheme());
        assertEquals("http://spec.example.com", instance.getOpenApi());
        assertTrue(instance.isRouteGroupFirst());
        assertFalse(instance.isAssumeParentRouteGroupFirst());
        assertFalse(instance.isIgnoreOpenApi());
    }

    @Test
    void convert_multipleInstances() {
        Map<String, String> flat = new HashMap<>();
        flat.put("capi-instance-prod-secured", "true");
        flat.put("capi-instance-dev-secured", "false");
        flat.put("capi-instance-dev-scheme", "http");

        ServiceCapiInstances result = mapper.convert(flat);

        assertEquals(2, result.getInstances().size());
        assertTrue(result.getInstances().get("prod").isSecured());
        assertFalse(result.getInstances().get("dev").isSecured());
        assertEquals("http", result.getInstances().get("dev").getScheme());
    }

    @Test
    void convert_emptyMap() {
        ServiceCapiInstances result = mapper.convert(new HashMap<>());
        assertTrue(result.getInstances().isEmpty());
    }

    @Test
    void convert_nonMatchingPrefix_ignored() {
        Map<String, String> flat = new HashMap<>();
        flat.put("some-other-key", "value");
        flat.put("capi-instance-prod-secured", "true");

        ServiceCapiInstances result = mapper.convert(flat);

        assertEquals(1, result.getInstances().size());
    }

    @Test
    void convert_unknownProperty_noError() {
        Map<String, String> flat = new HashMap<>();
        flat.put("capi-instance-prod-unknown-prop", "value");

        ServiceCapiInstances result = mapper.convert(flat);

        assertTrue(result.getInstances().containsKey("prod"));
    }

    @Test
    void convert_securedOverridesParentAssumption() {
        Map<String, String> flat = new HashMap<>();
        flat.put("capi-instance-test-secured", "false");

        ServiceCapiInstances.Instance instance = mapper.convert(flat).getInstances().get("test");

        assertFalse(instance.isSecured());
        assertFalse(instance.isAssumeParentSecured());
    }

    @Test
    void convert_opaRego_overridesPolicy() {
        Map<String, String> flat = new HashMap<>();
        flat.put("capi-instance-prod-opa-rego", "capi/deny_external");

        ServiceCapiInstances.Instance instance = mapper.convert(flat).getInstances().get("prod");

        assertTrue(instance.isOpaRegoOverridden());
        assertEquals("capi/deny_external", instance.getOpaRego());
    }

    @Test
    void convert_opaRegoBlank_removesPolicy() {
        Map<String, String> flat = new HashMap<>();
        flat.put("capi-instance-dev-opa-rego", " ");

        ServiceCapiInstances.Instance instance = mapper.convert(flat).getInstances().get("dev");

        assertTrue(instance.isOpaRegoOverridden(), "a declared blank value is an explicit override");
        assertNull(instance.getOpaRego());
    }

    @Test
    void convert_opaRegoAbsent_notOverridden() {
        Map<String, String> flat = new HashMap<>();
        flat.put("capi-instance-prod-secured", "true");

        ServiceCapiInstances.Instance instance = mapper.convert(flat).getInstances().get("prod");

        assertFalse(instance.isOpaRegoOverridden());
        assertNull(instance.getOpaRego());
    }

    @Test
    void malformedKeys_areSkipped_neverThrown() {
        // "capi-instance-default" used to throw StringIndexOutOfBounds ("Range [0, -1) out of
        // bounds for length 7") and fail the whole service lookup on every node.
        Map<String, String> flat = new HashMap<>();
        flat.put("capi-instance-default", "true");
        flat.put("capi-instance-", "x");
        flat.put("capi-instance--secured", "true");
        flat.put("capi-instance-internal-", "x");
        flat.put("capi-instance-internal-secured", "true");

        ServiceCapiInstances result = assertDoesNotThrow(() -> new ServiceCapiInstanceMapper().convert(flat));

        assertEquals(java.util.Set.of("internal"), result.getInstances().keySet());
        assertTrue(result.getInstances().get("internal").isSecured());
    }

    @Test
    void split_andIsMalformed() {
        assertArrayEquals(new String[]{"internal", "opa-rego"}, ServiceCapiInstanceMapper.split("capi-instance-internal-opa-rego"));
        assertNull(ServiceCapiInstanceMapper.split("capi-instance-default"));
        assertNull(ServiceCapiInstanceMapper.split("capi-instance--secured"));
        assertNull(ServiceCapiInstanceMapper.split("capi-instance-internal-"));
        assertTrue(ServiceCapiInstanceMapper.isMalformed("capi-instance-default"));
        assertFalse(ServiceCapiInstanceMapper.isMalformed("capi-instance-internal-secured"));
        assertFalse(ServiceCapiInstanceMapper.isMalformed("capi-instance"), "the single-instance key is not a per-instance key");
        assertFalse(ServiceCapiInstanceMapper.isMalformed("group"));
    }
}
