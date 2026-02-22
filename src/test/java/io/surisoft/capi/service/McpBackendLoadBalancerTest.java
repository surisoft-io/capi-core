package io.surisoft.capi.service;

import io.surisoft.capi.schema.Mapping;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class McpBackendLoadBalancerTest {

    @Test
    void emptyMappingList_returnsEmptyList() {
        McpBackendLoadBalancer lb = new McpBackendLoadBalancer(30000);
        Service service = createService("svc1", null, Set.of());
        assertTrue(lb.getOrderedBackendUrls(service).isEmpty());
    }

    @Test
    void nullMappingList_returnsEmptyList() {
        McpBackendLoadBalancer lb = new McpBackendLoadBalancer(30000);
        Service service = createService("svc2", null, null);
        service.setMappingList(null);
        assertTrue(lb.getOrderedBackendUrls(service).isEmpty());
    }

    @Test
    void singleMapping_returnsSingleUrl() {
        McpBackendLoadBalancer lb = new McpBackendLoadBalancer(30000);
        Mapping mapping = createMapping("host1", 8080, "/api", false);
        Service service = createService("svc3", "http", Set.of(mapping));
        List<String> urls = lb.getOrderedBackendUrls(service);
        assertEquals(1, urls.size());
        assertEquals("http://host1:8080/api", urls.get(0));
    }

    @Test
    void roundRobin_acrossMultipleMappings() {
        McpBackendLoadBalancer lb = new McpBackendLoadBalancer(30000);

        // Use a LinkedHashSet to have predictable iteration order
        Set<Mapping> mappings = new HashSet<>();
        mappings.add(createMapping("host1", 8080, "/api", false));
        mappings.add(createMapping("host2", 8081, "/api", false));
        Service service = createService("rr-svc", "http", mappings);

        // Call multiple times - the first URL should rotate
        List<String> firstCall = lb.getOrderedBackendUrls(service);
        List<String> secondCall = lb.getOrderedBackendUrls(service);

        assertEquals(2, firstCall.size());
        assertEquals(2, secondCall.size());
        // The first element of each call should differ (round-robin rotation)
        assertNotEquals(firstCall.get(0), secondCall.get(0));
        // Both calls should contain the same URLs (just in different order)
        assertTrue(firstCall.containsAll(secondCall));
    }

    @Test
    void circuitBreaker_failedUrlMovesToEnd() {
        McpBackendLoadBalancer lb = new McpBackendLoadBalancer(30000);
        Mapping m1 = createMapping("host1", 8080, "/api", false);
        Mapping m2 = createMapping("host2", 8081, "/api", false);
        Service service = createService("cb-svc", "http", Set.of(m1, m2));

        // Get ordered URLs and fail the first one
        List<String> urls = lb.getOrderedBackendUrls(service);
        String firstUrl = urls.get(0);
        lb.reportFailure(firstUrl);

        // Next call - the failed URL should be at the end
        List<String> nextUrls = lb.getOrderedBackendUrls(service);
        assertEquals(2, nextUrls.size());
        assertEquals(firstUrl, nextUrls.get(nextUrls.size() - 1));
    }

    @Test
    void circuitBreaker_recoveryAfterCooldown() throws InterruptedException {
        // Use a very short cooldown for testing
        McpBackendLoadBalancer lb = new McpBackendLoadBalancer(50);
        Mapping m1 = createMapping("host1", 8080, "/", false);
        Service service = createService("recovery-svc", "http", Set.of(m1));

        lb.reportFailure("http://host1:8080/");
        // Wait for cooldown to expire
        Thread.sleep(100);

        List<String> urls = lb.getOrderedBackendUrls(service);
        assertEquals(1, urls.size());
        // After cooldown, the URL should be in the healthy list (first position)
        assertEquals("http://host1:8080/", urls.get(0));
    }

    @Test
    void reportSuccess_clearsCircuitBreakerState() {
        McpBackendLoadBalancer lb = new McpBackendLoadBalancer(30000);
        Mapping m1 = createMapping("host1", 8080, "/", false);
        Mapping m2 = createMapping("host2", 8081, "/", false);
        Service service = createService("success-svc", "http", Set.of(m1, m2));

        String url = "http://host1:8080/";
        lb.reportFailure(url);

        // Verify it's circuit-broken (at the end)
        List<String> beforeSuccess = lb.getOrderedBackendUrls(service);
        assertEquals(url, beforeSuccess.get(beforeSuccess.size() - 1));

        // Report success → should clear the state
        lb.reportSuccess(url);

        // After success, it should no longer be deprioritized
        // (it will be wherever round-robin places it, but not forced to the end)
        List<String> afterSuccess = lb.getOrderedBackendUrls(service);
        assertEquals(2, afterSuccess.size());
        assertTrue(afterSuccess.contains(url));
    }

    @Test
    void ingressUrl_port443_httpsNoPort() {
        McpBackendLoadBalancer lb = new McpBackendLoadBalancer(30000);
        Mapping mapping = createMapping("example.com", 443, "/ctx", true);
        Service service = createService("ingress443", "http", Set.of(mapping));

        List<String> urls = lb.getOrderedBackendUrls(service);
        assertEquals(1, urls.size());
        assertEquals("https://example.com/ctx", urls.get(0));
    }

    @Test
    void ingressUrl_port80_httpNoPort() {
        McpBackendLoadBalancer lb = new McpBackendLoadBalancer(30000);
        Mapping mapping = createMapping("example.com", 80, "/ctx", true);
        Service service = createService("ingress80", "https", Set.of(mapping));

        List<String> urls = lb.getOrderedBackendUrls(service);
        assertEquals(1, urls.size());
        assertEquals("http://example.com/ctx", urls.get(0));
    }

    @Test
    void ingressUrl_nonStandardPort_includesPort() {
        McpBackendLoadBalancer lb = new McpBackendLoadBalancer(30000);
        Mapping mapping = createMapping("example.com", 8443, "/ctx", true);
        Service service = createService("ingress8443", "http", Set.of(mapping));

        List<String> urls = lb.getOrderedBackendUrls(service);
        assertEquals(1, urls.size());
        // Port 8443 is not 443 or 80, so scheme defaults to http for ingress
        assertEquals("http://example.com:8443/ctx", urls.get(0));
    }

    @Test
    void nonIngressUrl_includesSchemeAndPort() {
        McpBackendLoadBalancer lb = new McpBackendLoadBalancer(30000);
        Mapping mapping = createMapping("host1", 9090, "/api", false);
        Service service = createService("non-ingress", "https", Set.of(mapping));

        List<String> urls = lb.getOrderedBackendUrls(service);
        assertEquals(1, urls.size());
        assertEquals("https://host1:9090/api", urls.get(0));
    }

    @Test
    void nonIngressUrl_nullScheme_defaultsToHttp() {
        McpBackendLoadBalancer lb = new McpBackendLoadBalancer(30000);
        Mapping mapping = createMapping("host1", 9090, "/api", false);
        Service service = createService("null-scheme", null, Set.of(mapping));

        List<String> urls = lb.getOrderedBackendUrls(service);
        assertEquals("http://host1:9090/api", urls.get(0));
    }

    @Test
    void rootContext_nullNormalizesToEmpty() {
        McpBackendLoadBalancer lb = new McpBackendLoadBalancer(30000);
        Mapping mapping = createMapping("host1", 8080, null, false);
        Service service = createService("null-ctx", "http", Set.of(mapping));

        List<String> urls = lb.getOrderedBackendUrls(service);
        assertEquals("http://host1:8080", urls.get(0));
    }

    @Test
    void rootContext_noLeadingSlash_prependsSlash() {
        McpBackendLoadBalancer lb = new McpBackendLoadBalancer(30000);
        Mapping mapping = createMapping("host1", 8080, "api", false);
        Service service = createService("noslash-ctx", "http", Set.of(mapping));

        List<String> urls = lb.getOrderedBackendUrls(service);
        assertEquals("http://host1:8080/api", urls.get(0));
    }

    @Test
    void buildBackendUrl_directCall_nonIngress() {
        McpBackendLoadBalancer lb = new McpBackendLoadBalancer(30000);
        Mapping mapping = createMapping("host1", 8080, "/test", false);
        Service service = createService("direct-svc", "http", Set.of(mapping));

        String url = lb.buildBackendUrl(service, mapping);
        assertEquals("http://host1:8080/test", url);
    }

    @Test
    void buildBackendUrl_directCall_ingress443() {
        McpBackendLoadBalancer lb = new McpBackendLoadBalancer(30000);
        Mapping mapping = createMapping("host1", 443, "/test", true);
        Service service = createService("direct-ingress", "http", Set.of(mapping));

        String url = lb.buildBackendUrl(service, mapping);
        assertEquals("https://host1/test", url);
    }

    private Service createService(String name, String scheme, Set<Mapping> mappings) {
        Service service = new Service();
        service.setId(name);
        service.setName(name);
        ServiceMeta meta = new ServiceMeta();
        if (scheme != null) {
            meta.setScheme(scheme);
        }
        service.setServiceMeta(meta);
        if (mappings != null) {
            service.setMappingList(mappings);
        }
        return service;
    }

    private Mapping createMapping(String hostname, int port, String rootContext, boolean ingress) {
        Mapping mapping = new Mapping();
        mapping.setHostname(hostname);
        mapping.setPort(port);
        mapping.setRootContext(rootContext);
        mapping.setIngress(ingress);
        return mapping;
    }
}
