package io.surisoft.capi.utils;

import io.surisoft.capi.schema.OpaResult;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import io.surisoft.capi.service.OpaWasmService;
import io.surisoft.capi.utils.HttpUtils.AuthorizationOutcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/**
 * F8 — the OPA gate reached from the OpenAPI operation-security path must not silently downgrade.
 *
 * <p>It used to return a boolean, and when a service declared an OPA policy the engine could not
 * evaluate it fell through to the subscription check. An OPA-governed service was then authorized by
 * group membership alone whenever the bundle server was down, the pool was still loading, or
 * {@code opa-rego} named a policy the bundle did not declare — silently, with no log. These tests
 * pin the three "cannot decide" cases to outcomes the caller maps to 503/403, matching the main gate.
 */
class AuthorizeOutcomeTest {

    private final HttpUtils httpUtils = new HttpUtils(null, null);

    private static Service serviceWithPolicy(String opaRego, String subscriptionGroup) {
        ServiceMeta meta = new ServiceMeta();
        meta.setOpaRego(opaRego);
        meta.setSubscriptionGroup(subscriptionGroup);
        Service service = new Service();
        service.setId("svc:v1");
        service.setName("svc");
        service.setServiceMeta(meta);
        return service;
    }

    // ---- the fail-open cases this finding is about ----

    @Test
    void engineNotReady_isUnavailable_notASubscriptionFallback() {
        OpaWasmService opa = mock(OpaWasmService.class);
        when(opa.isReady()).thenReturn(false);

        // A subscription group is set, so the old code would have fallen through to it and could
        // have ALLOWED the request. The policy must decide, or nothing does.
        AuthorizationOutcome outcome =
                httpUtils.authorize("token", "svc:v1", serviceWithPolicy("capi/policy", "some-group"), opa);

        assertEquals(AuthorizationOutcome.POLICY_UNAVAILABLE, outcome);
        verify(opa, never()).evaluate(any(), any(), any(), anyBoolean());
    }

    @Test
    void opaServiceAbsentEntirely_isUnavailable() {
        AuthorizationOutcome outcome =
                httpUtils.authorize("token", "svc:v1", serviceWithPolicy("capi/policy", "some-group"), null);
        assertEquals(AuthorizationOutcome.POLICY_UNAVAILABLE, outcome);
    }

    @Test
    void policyNotInBundle_isUnknown_notASubscriptionFallback() {
        OpaWasmService opa = mock(OpaWasmService.class);
        when(opa.isReady()).thenReturn(true);
        when(opa.hasPolicy("capi/typo")).thenReturn(false);

        AuthorizationOutcome outcome =
                httpUtils.authorize("token", "svc:v1", serviceWithPolicy("capi/typo", "some-group"), opa);

        assertEquals(AuthorizationOutcome.POLICY_UNKNOWN, outcome);
        verify(opa, never()).evaluate(any(), any(), any(), anyBoolean());
    }

    // ---- normal decisions ----

    @Test
    void policyAllows_isAllowed() {
        OpaWasmService opa = mock(OpaWasmService.class);
        when(opa.isReady()).thenReturn(true);
        when(opa.hasPolicy("capi/policy")).thenReturn(true);
        OpaResult allowed = new OpaResult();
        allowed.setResult(true);
        when(opa.evaluate(eq("svc:v1"), eq("capi/policy"), eq("token"), eq(true))).thenReturn(allowed);

        assertEquals(AuthorizationOutcome.ALLOWED,
                httpUtils.authorize("token", "svc:v1", serviceWithPolicy("capi/policy", null), opa));
    }

    @Test
    void policyDenies_isDenied() {
        OpaWasmService opa = mock(OpaWasmService.class);
        when(opa.isReady()).thenReturn(true);
        when(opa.hasPolicy("capi/policy")).thenReturn(true);
        OpaResult denied = new OpaResult();
        denied.setResult(false);
        when(opa.evaluate(any(), any(), any(), anyBoolean())).thenReturn(denied);

        assertEquals(AuthorizationOutcome.DENIED,
                httpUtils.authorize("token", "svc:v1", serviceWithPolicy("capi/policy", null), opa));
    }

    @Test
    void nullEvaluationResult_isDenied_notAllowed() {
        OpaWasmService opa = mock(OpaWasmService.class);
        when(opa.isReady()).thenReturn(true);
        when(opa.hasPolicy("capi/policy")).thenReturn(true);
        when(opa.evaluate(any(), any(), any(), anyBoolean())).thenReturn(null);

        assertEquals(AuthorizationOutcome.DENIED,
                httpUtils.authorize("token", "svc:v1", serviceWithPolicy("capi/policy", null), opa));
    }

    // ---- services with no policy still use the subscription gate ----

    @Test
    void noPolicyDeclared_fallsToSubscriptionCheck_andDeniesAnUnverifiableToken() {
        // No JWT processors are configured here, so the token cannot be verified and the
        // subscription check must refuse. The point is that this path is still reached for a
        // service that declares no opa-rego — the fix must not turn every service into OPA.
        OpaWasmService opa = mock(OpaWasmService.class);

        assertEquals(AuthorizationOutcome.DENIED,
                httpUtils.authorize("token", "svc:v1", serviceWithPolicy(null, "some-group"), opa));
        verifyNoInteractions(opa);
    }

    @Test
    void noServiceMeta_isTreatedAsNoPolicy() {
        Service service = new Service();
        service.setId("svc:v1");
        assertEquals(AuthorizationOutcome.DENIED,
                httpUtils.authorize("token", "svc:v1", service, mock(OpaWasmService.class)));
    }
}
