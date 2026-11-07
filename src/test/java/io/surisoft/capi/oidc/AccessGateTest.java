package io.surisoft.capi.oidc;

import com.nimbusds.jose.jwk.RSAKey;
import io.surisoft.capi.schema.OpaResult;
import io.surisoft.capi.service.OpaWasmService;
import io.surisoft.capi.utils.HttpUtils;
import io.surisoft.capi.utils.TestJwts;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.surisoft.capi.oidc.AccessGate.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The authorization rules shared by the REST, WebSocket and gRPC listeners. Real signed tokens throughout. */
class AccessGateTest {

    private static final String SERVICE = "orders:v1";
    private static final String POLICY = "capi/orders";

    private RSAKey trusted;
    private HttpUtils httpUtils;
    private OpaWasmService opa;

    @BeforeEach
    void setUp() {
        trusted = TestJwts.newKey();
        httpUtils = new HttpUtils(null, List.of(TestJwts.processorFor(trusted)));
        opa = mock(OpaWasmService.class);
        when(opa.isReady()).thenReturn(true);
        when(opa.hasPolicy(POLICY)).thenReturn(true);
        allowByPolicy(true);
    }

    private void allowByPolicy(boolean allowed) {
        OpaResult result = new OpaResult();
        result.setResult(allowed);
        when(opa.evaluate(anyString(), eq(POLICY), anyString(), anyBoolean())).thenReturn(result);
    }

    private String token(String... groups) {
        return TestJwts.tokenWithSubscriptions(trusted, "user-1", 300, groups);
    }

    private AccessGate.Outcome decide(String group, String rego, String token) {
        return new AccessGate(httpUtils, opa).authorize(SERVICE, group, rego, token).outcome();
    }

    // ------------------------------------------------------------ requiresAuthorization

    @Test
    void secured_alwaysRequiresAuthorization_whateverTheSpecSays() {
        assertTrue(AccessGate.requiresAuthorization(true, false, false, null));
        assertTrue(AccessGate.requiresAuthorization(true, true, false, null), "secured ignores an anonymous operation");
    }

    @Test
    void notSecured_withoutSpec_isAnonymous_unlessAPolicyIsDeclared() {
        assertFalse(AccessGate.requiresAuthorization(false, false, false, null));
        assertTrue(AccessGate.requiresAuthorization(false, false, false, POLICY),
                "opa-rego with secured: false is a misconfiguration, never an open service");
    }

    @Test
    void notSecured_withSpec_followsTheOperation_evenWithAPolicy() {
        assertTrue(AccessGate.requiresAuthorization(false, true, true, null));
        assertFalse(AccessGate.requiresAuthorization(false, true, false, null));
        assertFalse(AccessGate.requiresAuthorization(false, true, false, POLICY),
                "an operation the spec leaves anonymous stays anonymous on an opa-rego service");
    }

    // ------------------------------------------------------------ operationRequiresSecurity

    private static SecurityRequirement bearer() {
        return new SecurityRequirement().addList("bearer");
    }

    @Test
    void operationSecurity_ownRequirement() {
        Operation op = new Operation().security(List.of(bearer()));
        assertTrue(AccessGate.operationRequiresSecurity(new OpenAPI(), op));
    }

    @Test
    void operationSecurity_inheritsTheGlobalRequirement() {
        OpenAPI spec = new OpenAPI().security(List.of(bearer()));
        assertTrue(AccessGate.operationRequiresSecurity(spec, new Operation()),
                "an operation without its own security inherits the spec's");
    }

    @Test
    void operationSecurity_emptyListOverridesTheGlobalRequirement() {
        OpenAPI spec = new OpenAPI().security(List.of(bearer()));
        assertFalse(AccessGate.operationRequiresSecurity(spec, new Operation().security(List.of())));
    }

    @Test
    void operationSecurity_anEmptyRequirementObjectMeansOptional() {
        Operation op = new Operation().security(List.of(bearer(), new SecurityRequirement()));
        assertFalse(AccessGate.operationRequiresSecurity(new OpenAPI(), op));
    }

    @Test
    void operationSecurity_noneAnywhere_isAnonymous() {
        assertFalse(AccessGate.operationRequiresSecurity(new OpenAPI(), new Operation()));
    }

    // ------------------------------------------------------------ authorize

    @Test
    void noGroupAndNoPolicy_isRefused_evenWithAValidToken() {
        assertEquals(FORBIDDEN, decide(null, null, token("any")));
        assertEquals(FORBIDDEN, decide(" ", "", token("any")));
    }

    @Test
    void missingOrUnverifiableToken_isUnauthenticated() {
        assertEquals(UNAUTHENTICATED, decide("premium", null, null));
        assertEquals(UNAUTHENTICATED, decide("premium", null, "garbage"));
        assertEquals(UNAUTHENTICATED, decide("premium", null,
                TestJwts.tokenWithSubscriptions(TestJwts.newKey(), "intruder", 300, "premium")));
        assertEquals(UNAUTHENTICATED, decide("premium", null,
                TestJwts.tokenWithSubscriptions(trusted, "late", -600, "premium")));
    }

    @Test
    void noVerifierConfigured_isUnauthenticated_andOpaIsNeverAsked() {
        AccessGate gate = new AccessGate(new HttpUtils(null, null), opa);
        assertEquals(UNAUTHENTICATED, gate.authorize(SERVICE, null, POLICY, token()).outcome());
        assertEquals(UNAUTHENTICATED, new AccessGate(null, opa).authorize(SERVICE, null, POLICY, token()).outcome());
        verify(opa, never()).evaluate(anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void group_isTheSubscriptionsClaim_only() {
        assertEquals(ALLOWED, decide("premium", null, token("premium")));
        assertEquals(ALLOWED, decide("premium", null, token("/premium")), "a leading slash is normalised, as before");
        assertEquals(FORBIDDEN, decide("premium", null, token("basic")));
        String realmRoleOnly = TestJwts.sign(trusted, new com.nimbusds.jwt.JWTClaimsSet.Builder()
                .subject("u").expirationTime(new java.util.Date(System.currentTimeMillis() + 300_000))
                .claim("realm_access", java.util.Map.of("roles", List.of("premium"))).build());
        assertEquals(FORBIDDEN, decide("premium", null, realmRoleOnly), "realm roles do not count");
    }

    @Test
    void policyAndGroup_bothMustPass() {
        assertEquals(ALLOWED, decide("premium", POLICY, token("premium")));
        assertEquals(FORBIDDEN, decide("premium", POLICY, token("basic")), "in policy but not in group");
        allowByPolicy(false);
        assertEquals(FORBIDDEN, decide("premium", POLICY, token("premium")), "in group but refused by policy");
    }

    @Test
    void policyAlone_isARule() {
        assertEquals(ALLOWED, decide(null, POLICY, token()));
        verify(opa).evaluate(eq(SERVICE), eq(POLICY), anyString(), eq(true));
        allowByPolicy(false);
        assertEquals(FORBIDDEN, decide(null, POLICY, token()));
    }

    @Test
    void groupIsCheckedBeforeThePolicy_soARefusedCallerCostsNoEvaluation() {
        assertEquals(FORBIDDEN, decide("premium", POLICY, token("basic")));
        verify(opa, never()).evaluate(anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void opaDisabled_isRefused_neverSkipped() {
        AccessGate gate = new AccessGate(httpUtils, null);
        AccessGate.Decision decision = gate.authorize(SERVICE, "premium", POLICY, token("premium"));
        assertEquals(FORBIDDEN, decision.outcome(), "a group match must not stand in for an unevaluated policy");
    }

    @Test
    void unknownPolicy_isRefused() {
        when(opa.hasPolicy(POLICY)).thenReturn(false);
        AccessGate.Decision decision = new AccessGate(httpUtils, opa).authorize(SERVICE, null, POLICY, token());
        assertEquals(FORBIDDEN, decision.outcome());
        assertTrue(decision.message().contains(POLICY));
    }

    @Test
    void engineNotReady_isTransient() {
        when(opa.isReady()).thenReturn(false);
        assertEquals(POLICY_UNAVAILABLE, decide(null, POLICY, token()));
    }

    @Test
    void evaluationFailure_isRefused() {
        when(opa.evaluate(anyString(), eq(POLICY), anyString(), anyBoolean())).thenReturn(null);
        assertEquals(FORBIDDEN, decide(null, POLICY, token()));
    }

    @Test
    void allowed_carriesTheVerifiedClaims() throws Exception {
        AccessGate.Decision decision = new AccessGate(httpUtils, opa).authorize(SERVICE, "premium", null, token("premium"));
        assertTrue(decision.allowed());
        assertEquals("user-1", decision.claims().getSubject());
    }
}
