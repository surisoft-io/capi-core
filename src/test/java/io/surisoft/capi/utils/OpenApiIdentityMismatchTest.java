package io.surisoft.capi.utils;

import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The spec-identity rule behind capi.matchOpenApiSpec. */
class OpenApiIdentityMismatchTest {

    private final ServiceUtils serviceUtils =
            new ServiceUtils(null, java.util.Optional.empty(), null, java.util.Optional.empty(), "full");

    private static Service service(String name, String title, String version) {
        Service svc = new Service();
        svc.setName(name);
        svc.setId(name + ":v1");
        svc.setServiceMeta(new ServiceMeta());
        if (title != null || version != null) {
            OpenAPI openAPI = new OpenAPI();
            openAPI.setInfo(new Info().title(title).version(version));
            svc.setOpenAPI(openAPI);
        }
        return svc;
    }

    @Test
    void titleMatchingServiceName_isCompliant() {
        assertNull(serviceUtils.openApiIdentityMismatch(service("sample-service", "sample-service", "1.0.0")));
    }

    @Test
    void titleNotMatching_isReported() {
        String mismatch = serviceUtils.openApiIdentityMismatch(service("sample-service", "another-service", "1.0.0"));
        assertNotNull(mismatch);
        // The message has to name both sides, or the report is not actionable.
        assertTrue(mismatch.contains("another-service"), mismatch);
        assertTrue(mismatch.contains("sample-service"), mismatch);
    }

    @Test
    void matchIsExact_noCaseOrSeparatorFolding() {
        // Deliberate: a governance rule people have to guess at is not one.
        assertNotNull(serviceUtils.openApiIdentityMismatch(service("sample-service", "Sample-Service", "1.0.0")));
        assertNotNull(serviceUtils.openApiIdentityMismatch(service("sample-service", "sample_service", "1.0.0")));
        assertNotNull(serviceUtils.openApiIdentityMismatch(service("sample-service", "sampleservice", "1.0.0")));
        // ...but surrounding whitespace is not meaningful in YAML/JSON authoring.
        assertNull(serviceUtils.openApiIdentityMismatch(service("sample-service", "  sample-service  ", "1.0.0")));
    }

    @Test
    void substringOrPrefixTitles_doNotMatch() {
        assertNotNull(serviceUtils.openApiIdentityMismatch(service("sample-service", "sample", "1.0.0")));
        assertNotNull(serviceUtils.openApiIdentityMismatch(service("sample-service", "sample-service-v2", "1.0.0")));
    }

    @Test
    void missingVersion_isReported() {
        String mismatch = serviceUtils.openApiIdentityMismatch(service("sample-service", "sample-service", null));
        assertNotNull(mismatch);
        assertTrue(mismatch.contains("info.version"), mismatch);
    }

    @Test
    void blankTitle_isReported() {
        String mismatch = serviceUtils.openApiIdentityMismatch(service("sample-service", "   ", "1.0.0"));
        assertNotNull(mismatch);
        assertTrue(mismatch.contains("info.title"), mismatch);
    }

    @Test
    void missingInfoBlock_isReported() {
        Service svc = new Service();
        svc.setName("sample-service");
        svc.setOpenAPI(new OpenAPI());   // no info
        String mismatch = serviceUtils.openApiIdentityMismatch(svc);
        assertNotNull(mismatch);
        assertTrue(mismatch.contains("info"), mismatch);
    }

    @Test
    void noSpecAtAll_isNotAMismatch() {
        // Spec-less services are first-class; the rule simply does not apply to them.
        assertNull(serviceUtils.openApiIdentityMismatch(service("sample-service", null, null)));
    }
}
