package io.surisoft.capi.undertow;

import com.nimbusds.jose.jwk.RSAKey;
import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.oidc.AccessGate;
import io.surisoft.capi.schema.OpaResult;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import io.surisoft.capi.service.OpaWasmService;
import io.surisoft.capi.utils.HttpUtils;
import io.surisoft.capi.utils.TestJwts;
import io.undertow.util.Headers;
import io.undertow.server.HttpServerExchange;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The MCP-side security fixes: token verification and per-service authorization through the shared
 * AccessGate (CAPI-01, and SEC-01 before it), and the bounds on request body size and session count.
 */
class McpSecurityFixesTest {

    /** The gateway has a large constructor; these tests only touch fields, so build it unconstructed. */
    private static McpGateway bareGateway() throws Exception {
        sun.misc.Unsafe unsafe = unsafe();
        return (McpGateway) unsafe.allocateInstance(McpGateway.class);
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (sun.misc.Unsafe) f.get(null);
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = McpGateway.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Service serviceWithPolicy(String opaRego) {
        ServiceMeta meta = new ServiceMeta();
        meta.setOpaRego(opaRego);
        Service service = new Service();
        service.setId("svc:v1");
        service.setServiceMeta(meta);
        return service;
    }

    /** Real RS256 verification: the processor trusts {@link #KEY} only. */
    private static final RSAKey KEY = TestJwts.newKey();
    private static final RSAKey OTHER_KEY = TestJwts.newKey();

    private static HttpUtils verifyingHttpUtils() {
        return new HttpUtils(null, List.of(TestJwts.processorFor(KEY)));
    }

    /** A gateway with the real verifier, the shared AccessGate and the given OPA engine (may be null). */
    private static McpGateway gateway(OpaWasmService opa) throws Exception {
        McpGateway gateway = bareGateway();
        HttpUtils httpUtils = verifyingHttpUtils();
        set(gateway, "httpUtils", httpUtils);
        set(gateway, "opaWasmService", opa);
        set(gateway, "accessGate", new AccessGate(httpUtils, opa));
        return gateway;
    }

    private static OpaWasmService readyOpa(Boolean verdict) {
        OpaWasmService opa = mock(OpaWasmService.class);
        when(opa.isReady()).thenReturn(true);
        when(opa.hasPolicy(any())).thenReturn(true);
        if (verdict != null) {
            OpaResult result = new OpaResult();
            result.setResult(verdict);
            when(opa.evaluate(any(), any(), any(), anyBoolean(), any())).thenReturn(result);
        }
        return opa;
    }

    private static Service service(boolean secured, String subscriptionGroup, String opaRego) {
        ServiceMeta meta = new ServiceMeta();
        meta.setSecured(secured);
        meta.setSubscriptionGroup(subscriptionGroup);
        meta.setOpaRego(opaRego);
        Service service = new Service();
        service.setId("svc:v1");
        service.setServiceMeta(meta);
        return service;
    }

    private static HttpServerExchange exchange(String bearer) {
        HttpServerExchange exchange = new HttpServerExchange(null);
        if (bearer != null) {
            exchange.getRequestHeaders().put(Headers.AUTHORIZATION, "Bearer " + bearer);
        }
        return exchange;
    }

    private static AccessGate.Decision authorize(McpGateway gateway, HttpServerExchange exchange, Service service) throws Exception {
        Method m = McpGateway.class.getDeclaredMethod("authorizeService", HttpServerExchange.class, Service.class);
        m.setAccessible(true);
        return (AccessGate.Decision) m.invoke(gateway, exchange, service);
    }

    private static boolean isVerified(McpGateway gateway, String token) throws Exception {
        Method m = McpGateway.class.getDeclaredMethod("isVerified", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(gateway, token);
    }

    /** An unsigned token whose payload claims whatever the caller wants: what CAPI-01 accepted. */
    private static String forged(String payloadJson) {
        java.util.Base64.Encoder b64 = java.util.Base64.getUrlEncoder().withoutPadding();
        return b64.encodeToString("{\"alg\":\"none\"}".getBytes()) + "."
                + b64.encodeToString(payloadJson.getBytes()) + ".";
    }

    // ---- CAPI-01: a token is verified before anything trusts it ----

    @Test
    void gate_rejectsAnythingThatIsNotAVerifiedToken() throws Exception {
        McpGateway gateway = gateway(null);
        assertFalse(isVerified(gateway, "valid-test-token-1234567890"), "an arbitrary bearer string");
        assertFalse(isVerified(gateway, forged("{\"sub\":\"admin\",\"realm_access\":{\"roles\":[\"admin\"]}}")),
                "an unsigned token with forged claims");
        assertFalse(isVerified(gateway, TestJwts.token(OTHER_KEY, "alice", 300)), "signed by an untrusted key");
        assertFalse(isVerified(gateway, TestJwts.token(KEY, "alice", -60)), "expired");
        assertFalse(isVerified(gateway, null));
        assertTrue(isVerified(gateway, TestJwts.token(KEY, "alice", 300)), "a real, current token");
    }

    @Test
    void forgedClaims_neverReachThePolicy() throws Exception {
        // The CAPI-01 attack: claims that satisfy any policy, in a token nobody signed.
        OpaWasmService opa = readyOpa(true);
        McpGateway gateway = gateway(opa);

        AccessGate.Decision d = authorize(gateway, exchange(forged("{\"azp\":\"trusted-client\"}")),
                service(false, null, "capi/admin_only"));

        assertEquals(AccessGate.Outcome.UNAUTHENTICATED, d.outcome());
        verify(opa, never()).evaluate(any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void verifiedToken_policyAllows_isAllowed() throws Exception {
        String token = TestJwts.token(KEY, "alice", 300);
        OpaWasmService opa = readyOpa(true);

        AccessGate.Decision d = authorize(gateway(opa), exchange(token), service(false, null, "capi/admin_only"));

        assertTrue(d.allowed());
        verify(opa).evaluate(eq("svc:v1"), eq("capi/admin_only"), eq(token), eq(true), any());
    }

    @Test
    void verifiedToken_policyDenies_isRefused() throws Exception {
        AccessGate.Decision d = authorize(gateway(readyOpa(false)), exchange(TestJwts.token(KEY, "alice", 300)),
                service(false, null, "capi/admin_only"));
        assertEquals(AccessGate.Outcome.FORBIDDEN, d.outcome());
    }

    @Test
    void missingToken_onGovernedService_isRefused() throws Exception {
        // Authenticate once, then drop the Authorization header: the session-based revision would
        // otherwise authorize the call by Mcp-Session-Id alone (SEC-01).
        OpaWasmService opa = readyOpa(true);
        AccessGate.Decision d = authorize(gateway(opa), exchange(null), service(false, null, "capi/admin_only"));
        assertEquals(AccessGate.Outcome.UNAUTHENTICATED, d.outcome());
        verify(opa, never()).evaluate(any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void policyDeclared_butNoOpaEngine_isRefused_notOpen() throws Exception {
        // CAPI-01 part 3: this used to return "allowed".
        AccessGate.Decision d = authorize(gateway(null), exchange(TestJwts.token(KEY, "alice", 300)),
                service(false, null, "capi/admin_only"));
        assertEquals(AccessGate.Outcome.FORBIDDEN, d.outcome());
    }

    @Test
    void securedService_subscriptionGroupIsEnforced() throws Exception {
        // CAPI-01: the MCP gateway used to ignore secured and subscription-group altogether.
        McpGateway gateway = gateway(null);
        Service svc = service(true, "orders-readers", null);

        assertEquals(AccessGate.Outcome.FORBIDDEN,
                authorize(gateway, exchange(TestJwts.tokenWithSubscriptions(KEY, "alice", 300, "other")), svc).outcome());
        assertTrue(authorize(gateway, exchange(TestJwts.tokenWithSubscriptions(KEY, "alice", 300, "orders-readers")), svc).allowed());
    }

    @Test
    void securedService_withNoRule_isRefused_likeRest() throws Exception {
        AccessGate.Decision d = authorize(gateway(null), exchange(TestJwts.token(KEY, "alice", 300)), service(true, null, null));
        assertEquals(AccessGate.Outcome.FORBIDDEN, d.outcome());
    }

    @Test
    void serviceWithNoRule_isOpen_andNeedsNoToken() throws Exception {
        OpaWasmService opa = readyOpa(true);
        assertTrue(authorize(gateway(opa), exchange(null), service(false, null, null)).allowed());
        verify(opa, never()).evaluate(any(), any(), any(), anyBoolean(), any());
    }

    // ---- session identity is derived one way, so mint and check agree ----

    @Test
    void clientIdentity_isStableAndDoesNotCarryTheToken() throws Exception {
        Method m = McpGateway.class.getDeclaredMethod("clientIdentityOf", String.class);
        m.setAccessible(true);

        String token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJhbGljZSJ9.sig";
        String first = (String) m.invoke(null, token);
        String second = (String) m.invoke(null, token);

        assertEquals(first, second, "the same token must always yield the same identity");
        assertFalse(first.contains("eyJ"), "the identity must not carry a slice of the raw token");
        assertNotEquals(first, m.invoke(null, token + "x"), "different tokens must differ");
        assertEquals("anonymous", m.invoke(null, (Object) null));
    }

    // ---- F4 / F10: the bounds exist and are sane ----

    @Test
    void mcpDefaults_boundBodySizeAndSessionCount() {
        CAPIConfiguration.Mcp mcp = new CAPIConfiguration.Mcp();
        assertEquals(1_048_576L, mcp.getMaxRequestSize(),
                "the body is buffered whole before auth, so it must be bounded by default");
        assertTrue(mcp.getMaxSessions() > 0, "session creation must be bounded by default");
    }
}
