package io.surisoft.capi.undertow;

import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.schema.WebDavClient;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import io.surisoft.capi.service.OpaWasmService;
import io.surisoft.capi.utils.HttpUtils;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Routing and the bounds around it. The authorization gate is covered end to end by
 * {@code demo-e2e/webdav-e2e.sh}; these cover the pure decisions that a live test would not isolate.
 */
class WebDavGatewayTest {

    private static CAPIConfiguration.WebDav config(String routing) {
        CAPIConfiguration.WebDav c = new CAPIConfiguration.WebDav();
        c.setRouting(routing);
        return c;
    }

    private static WebDavClient client(String serviceId, String host) {
        WebDavClient c = new WebDavClient();
        c.setServiceId(serviceId);
        c.setWebdavHost(host);
        return c;
    }

    private static WebDavGateway gateway(CAPIConfiguration.WebDav cfg, WebDavClient... clients) {
        Map<String, WebDavClient> map = new ConcurrentHashMap<>();
        for (WebDavClient c : clients) {
            map.put(c.getServiceId(), c);
        }
        return new WebDavGateway(0, map, null, cfg);
    }

    private static String hostHeader(String raw) throws Exception {
        Method m = WebDavGateway.class.getDeclaredMethod("hostHeader", HttpServerExchange.class);
        m.setAccessible(true);
        HttpServerExchange exchange = new HttpServerExchange(null, new HeaderMap(), new HeaderMap(), 0);
        if (raw != null) {
            exchange.getRequestHeaders().put(Headers.HOST, raw);
        }
        return (String) m.invoke(null, exchange);
    }

    private static Object resolveByPath(WebDavGateway g, String path) throws Exception {
        Method m = WebDavGateway.class.getDeclaredMethod("resolveByPath", String.class);
        m.setAccessible(true);
        return m.invoke(g, path);
    }

    // ---- Host header normalisation: the key host routing matches on ----

    @Test
    void hostHeader_stripsPortAndLowercases() throws Exception {
        assertEquals("dav.corp", hostHeader("DAV.Corp:8385"));
        assertEquals("dav.corp", hostHeader("dav.corp"));
        assertEquals("dav.corp", hostHeader("  DAV.CORP  "));
        assertNull(hostHeader(null));
        assertNull(hostHeader("   "));
    }

    @Test
    void hostHeader_keepsIpv6Brackets() throws Exception {
        // Splitting an IPv6 literal on the first colon would mangle it into "[2001".
        assertEquals("[2001:db8::1]", hostHeader("[2001:db8::1]:8385"));
        assertEquals("[2001:db8::1]", hostHeader("[2001:db8::1]"));
    }

    // ---- path routing ----

    @Test
    void resolveByPath_findsServiceAndGroup() throws Exception {
        WebDavGateway g = gateway(config("both"), client("/files/v1", null));
        assertNotNull(resolveByPath(g, "/files/v1/folder/a.txt"));
        assertNotNull(resolveByPath(g, "/files/v1"));
        assertNull(resolveByPath(g, "/files"));
        assertNull(resolveByPath(g, "/"));
        assertNull(resolveByPath(g, "/files/v2/x"));
    }

    // ---- routing-mode switches actually gate ----

    @Test
    void routingModes_enableTheRightResolvers() {
        assertTrue(config("both").isHostRoutingEnabled());
        assertTrue(config("both").isPathRoutingEnabled());
        assertTrue(config("host").isHostRoutingEnabled());
        assertFalse(config("host").isPathRoutingEnabled());
        assertFalse(config("path").isHostRoutingEnabled());
        assertTrue(config("path").isPathRoutingEnabled());
    }

    @Test
    void defaults_areTheOnesTheDesignArguedFor() {
        CAPIConfiguration.WebDav c = new CAPIConfiguration.WebDav();
        assertEquals(8385, c.getPort());
        assertEquals("both", c.getRouting());
        assertFalse(c.isEnabled(), "a new transport must not turn itself on");
        // Depth ships observing, not enforcing: some sync clients legitimately use Depth: infinity
        // and this listener is internal only.
        assertFalse(c.isEnforceMaxDepth());
        // A large PUT is the normal case here, so the REST-scale timeout would kill an upload.
        assertEquals(3_600_000L, c.getMaxRequestTime());
        // Unlimited: a large PUT is the normal case, and Undertow's own default became 2 MiB in 2.3.21.
        assertEquals(-1L, c.getMaxRequestSize());
        // The one supported auth mechanism, and the minting endpoint that feeds it.
        assertEquals("/s/", c.getSessionPathPrefix());
        assertFalse(c.getTicket().isEnabled(), "minting must be opted into");
        assertFalse(c.isDistributedSessions());
    }

    // ---- the dot-segment guard this gateway reuses ----

    @Test
    void dotSegmentGuard_rejectsTraversalButNotFilenamesContainingDots() {
        // WebDAV paths are attacker-influenced filenames, so this matters more here than on REST —
        // and real WebDAV collections are full of names with dots in them.
        assertTrue(RestGateway.containsDotSegment("/files/v1/../../etc/passwd"));
        assertTrue(RestGateway.containsDotSegment("/files/v1/./a.txt"));
        assertFalse(RestGateway.containsDotSegment("/files/v1/report..pdf"));
        assertFalse(RestGateway.containsDotSegment("/files/v1/my.file.name.txt"));
        assertFalse(RestGateway.containsDotSegment("/files/v1/v1.2.3/notes.md"));
    }

    // ---- what "unsecured" actually means ----

    private static boolean authorize(WebDavGateway g, WebDavClient c) throws Exception {
        java.lang.reflect.Method m = WebDavGateway.class
                .getDeclaredMethod("authorize", HttpServerExchange.class, WebDavClient.class);
        m.setAccessible(true);
        // challenge()/deny() write to the exchange, so the refusal path needs real header maps.
        HttpServerExchange exchange = mock(HttpServerExchange.class);
        when(exchange.getResponseHeaders()).thenReturn(new HeaderMap());
        return (boolean) m.invoke(g, exchange, c);
    }

    private static WebDavGateway gatewayWithOpa(boolean opaEnabled, String tokenReturned) throws Exception {
        WebDavGateway g = gateway(config("both"));
        HttpUtils httpUtils = mock(HttpUtils.class);
        when(httpUtils.processAuthorizationAccessToken(any(HttpServerExchange.class))).thenReturn(tokenReturned);
        g.setHttpUtils(httpUtils);
        g.setOpaWasmService(opaEnabled ? mock(OpaWasmService.class) : null);
        return g;
    }

    @Test
    void unsecuredWithNoPolicy_needsNoCredential() throws Exception {
        // secured: false and no opa-rego -> the gate returns before looking for a token at all.
        WebDavClient c = client("/files/v1", null);
        c.setRequiresSubscription(false);
        assertTrue(authorize(gatewayWithOpa(true, null), c));
    }

    /**
     * The nuance: {@code secured: false} does NOT mean "open" if the service also declares
     * {@code opa-rego}. A policy decides on token claims, so no token means no decision, and no
     * decision means refused — the same rule as the MCP gateway after SEC-01.
     */
    @Test
    void unsecuredButPolicyGoverned_stillRequiresAToken() throws Exception {
        WebDavClient c = client("/files/v1", null);
        c.setRequiresSubscription(false);
        c.setOpaRego("capi/files_policy");
        assertFalse(authorize(gatewayWithOpa(true, null), c),
                "opa-rego must gate even when secured is false");
    }

    @Test
    void policyDeclaredButOpaDisabled_isOpen() throws Exception {
        // No OPA engine configured, so there is no policy to consult and nothing to enforce.
        WebDavClient c = client("/files/v1", null);
        c.setRequiresSubscription(false);
        c.setOpaRego("capi/files_policy");
        assertTrue(authorize(gatewayWithOpa(false, null), c));
    }

    @Test
    void readOnlyIsSeparateFromAuth() {
        // Nothing about being unsecured implies writability, and nothing about read-only implies auth.
        WebDavClient c = client("/files/v1", null);
        c.setRequiresSubscription(false);
        assertFalse(c.isReadOnly(), "writes are allowed by default");
        c.setReadOnly(true);
        assertTrue(c.isReadOnly());
    }



    // ---- session id carried in the path ----

    @Test
    void sessionPathPrefixDefaultsOn() {
        // A query string reaches only the first request; the path prefix survives a client deriving
        // a parent-collection URL by truncating the filename.
        assertEquals("/s/", new CAPIConfiguration.WebDav().getSessionPathPrefix());
    }

    @Test
    void sessionPathPrefixCanBeDisabledOrChanged() {
        CAPIConfiguration.WebDav c = new CAPIConfiguration.WebDav();
        c.setSessionPathPrefix("");
        assertTrue(c.getSessionPathPrefix().isBlank(), "blank disables it");
        c.setSessionPathPrefix("/sess/");
        assertEquals("/sess/", c.getSessionPathPrefix());
    }
}
