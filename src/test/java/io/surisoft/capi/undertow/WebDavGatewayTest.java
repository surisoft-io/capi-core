package io.surisoft.capi.undertow;

import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.schema.WebDavClient;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

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
        assertFalse(c.getBasicAuth().isEnabled());
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
}
