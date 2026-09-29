package io.surisoft.capi.undertow;

import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.schema.OpaResult;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import io.surisoft.capi.service.OpaWasmService;
import io.surisoft.capi.utils.HttpUtils;
import io.undertow.server.HttpServerExchange;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The MCP-side security fixes: the OPA bypass on {@code tools/call}, and the bounds on request body
 * size and session count.
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

    private static boolean isOpaAllowed(McpGateway gateway, HttpServerExchange exchange, Service service) throws Exception {
        Method m = McpGateway.class.getDeclaredMethod("isOpaAllowed", HttpServerExchange.class, Service.class);
        m.setAccessible(true);
        return (boolean) m.invoke(gateway, exchange, service);
    }

    // ---- SEC-01: a missing token must not mean "allowed" ----

    @Test
    void missingToken_isRefused_notAllowed() throws Exception {
        // The bypass: authenticate once to get a session, then drop the Authorization header on
        // tools/call. The session was valid, so the request proceeded — and the policy, which
        // decides on token claims, was never consulted.
        McpGateway gateway = bareGateway();
        HttpUtils httpUtils = mock(HttpUtils.class);
        when(httpUtils.processAuthorizationAccessToken(any(HttpServerExchange.class))).thenReturn(null);
        OpaWasmService opa = mock(OpaWasmService.class);
        set(gateway, "httpUtils", httpUtils);
        set(gateway, "opaWasmService", opa);

        assertFalse(isOpaAllowed(gateway, mock(HttpServerExchange.class), serviceWithPolicy("capi/admin_only")));
        verify(opa, never()).evaluate(any(), any(), any(), anyBoolean());
    }

    @Test
    void tokenPresentAndPolicyAllows_isAllowed() throws Exception {
        McpGateway gateway = bareGateway();
        HttpUtils httpUtils = mock(HttpUtils.class);
        when(httpUtils.processAuthorizationAccessToken(any(HttpServerExchange.class))).thenReturn("tok");
        OpaWasmService opa = mock(OpaWasmService.class);
        OpaResult allowed = new OpaResult();
        allowed.setResult(true);
        when(opa.evaluate("svc:v1", "capi/admin_only", "tok", true)).thenReturn(allowed);
        set(gateway, "httpUtils", httpUtils);
        set(gateway, "opaWasmService", opa);

        assertTrue(isOpaAllowed(gateway, mock(HttpServerExchange.class), serviceWithPolicy("capi/admin_only")));
    }

    @Test
    void tokenPresentAndPolicyDenies_isRefused() throws Exception {
        McpGateway gateway = bareGateway();
        HttpUtils httpUtils = mock(HttpUtils.class);
        when(httpUtils.processAuthorizationAccessToken(any(HttpServerExchange.class))).thenReturn("tok");
        OpaWasmService opa = mock(OpaWasmService.class);
        OpaResult denied = new OpaResult();
        denied.setResult(false);
        when(opa.evaluate(any(), any(), any(), anyBoolean())).thenReturn(denied);
        set(gateway, "httpUtils", httpUtils);
        set(gateway, "opaWasmService", opa);

        assertFalse(isOpaAllowed(gateway, mock(HttpServerExchange.class), serviceWithPolicy("capi/admin_only")));
    }

    @Test
    void nullEvaluation_isRefused() throws Exception {
        McpGateway gateway = bareGateway();
        HttpUtils httpUtils = mock(HttpUtils.class);
        when(httpUtils.processAuthorizationAccessToken(any(HttpServerExchange.class))).thenReturn("tok");
        OpaWasmService opa = mock(OpaWasmService.class);
        when(opa.evaluate(any(), any(), any(), anyBoolean())).thenReturn(null);
        set(gateway, "httpUtils", httpUtils);
        set(gateway, "opaWasmService", opa);

        assertFalse(isOpaAllowed(gateway, mock(HttpServerExchange.class), serviceWithPolicy("capi/admin_only")));
    }

    @Test
    void serviceWithNoPolicy_isUnaffected() throws Exception {
        // No opa-rego declared, so there is no policy to consult and nothing to bypass.
        McpGateway gateway = bareGateway();
        set(gateway, "httpUtils", mock(HttpUtils.class));
        set(gateway, "opaWasmService", mock(OpaWasmService.class));

        assertTrue(isOpaAllowed(gateway, mock(HttpServerExchange.class), serviceWithPolicy(null)));
    }

    @Test
    void opaNotConfiguredAtAll_isUnaffected() throws Exception {
        McpGateway gateway = bareGateway();
        set(gateway, "httpUtils", mock(HttpUtils.class));
        set(gateway, "opaWasmService", null);

        assertTrue(isOpaAllowed(gateway, mock(HttpServerExchange.class), serviceWithPolicy("capi/admin_only")));
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
