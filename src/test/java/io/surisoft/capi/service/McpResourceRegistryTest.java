package io.surisoft.capi.service;

import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import io.surisoft.capi.utils.Constants;
import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpResourceRegistryTest {

    private Cache<String, Service> serviceCache;
    private McpServerClient mcpServerClient;
    private McpResourceRegistry registry;

    @BeforeEach
    void setUp() {
        serviceCache = new Cache2kBuilder<String, Service>() {}
                .name("testResReg-" + System.nanoTime())
                .eternal(true).entryCapacity(100).storeByReference(true).build();
        mcpServerClient = Mockito.mock(McpServerClient.class);
        registry = new McpResourceRegistry(serviceCache, mcpServerClient);
    }

    @AfterEach
    void tearDown() {
        serviceCache.close();
    }

    @Test
    void emptyCache_returnsEmptyList() {
        assertTrue(registry.getAllResources().isEmpty());
    }

    @Test
    void serviceWithoutMcpServer_isSkipped() {
        Service plain = mcpService("plain");
        // mcp-type stays "rest" → not an MCP server
        plain.getServiceMeta().handleUnknown(Constants.MCP_META_TYPE, "rest");
        serviceCache.put("plain", plain);
        assertTrue(registry.getAllResources().isEmpty());
    }

    @Test
    void singleServer_resourcesNamespacedByServiceId() throws Exception {
        Service math = mcpServerService("math-mcp");
        serviceCache.put("math-mcp", math);

        Mockito.when(mcpServerClient.forwardResourcesList(Mockito.eq(math), Mockito.anyInt()))
                .thenReturn(List.of(
                        Map.of("uri", "file:///docs/algebra.md", "name", "Algebra docs", "mimeType", "text/markdown"),
                        Map.of("uri", "function://reference", "name", "Function ref")
                ));

        List<Map<String, Object>> all = registry.getAllResources();
        assertEquals(2, all.size());
        // URIs are namespaced
        assertEquals("math-mcp:file:///docs/algebra.md", all.get(0).get("uri"));
        assertEquals("math-mcp:function://reference", all.get(1).get("uri"));
        // Other fields are preserved
        assertEquals("Algebra docs", all.get(0).get("name"));
        assertEquals("text/markdown", all.get(0).get("mimeType"));
    }

    @Test
    void multipleServers_resourcesAggregated() throws Exception {
        Service math = mcpServerService("math-mcp");
        Service kb = mcpServerService("kb-mcp");
        serviceCache.put("math-mcp", math);
        serviceCache.put("kb-mcp", kb);

        Mockito.when(mcpServerClient.forwardResourcesList(Mockito.eq(math), Mockito.anyInt()))
                .thenReturn(List.of(Map.of("uri", "file:///math.md", "name", "Math")));
        Mockito.when(mcpServerClient.forwardResourcesList(Mockito.eq(kb), Mockito.anyInt()))
                .thenReturn(List.of(Map.of("uri", "kb://faq", "name", "FAQ")));

        List<Map<String, Object>> all = registry.getAllResources();
        assertEquals(2, all.size());
        assertTrue(all.stream().anyMatch(r -> "math-mcp:file:///math.md".equals(r.get("uri"))));
        assertTrue(all.stream().anyMatch(r -> "kb-mcp:kb://faq".equals(r.get("uri"))));
    }

    @Test
    void backendFailure_excludesThatBackendButOthersStillSurfaced() throws Exception {
        Service math = mcpServerService("math-mcp");
        Service broken = mcpServerService("broken-mcp");
        serviceCache.put("math-mcp", math);
        serviceCache.put("broken-mcp", broken);

        Mockito.when(mcpServerClient.forwardResourcesList(Mockito.eq(math), Mockito.anyInt()))
                .thenReturn(List.of(Map.of("uri", "file:///ok.md")));
        Mockito.when(mcpServerClient.forwardResourcesList(Mockito.eq(broken), Mockito.anyInt()))
                .thenThrow(new RuntimeException("backend exploded"));

        List<Map<String, Object>> all = registry.getAllResources();
        assertEquals(1, all.size());
        assertEquals("math-mcp:file:///ok.md", all.get(0).get("uri"));
    }

    @Test
    void resolveResourceByUri_validNamespace_returnsService() {
        Service math = mcpServerService("math-mcp");
        serviceCache.put("math-mcp", math);

        McpResourceRegistry.Resolved r = registry.resolveResourceByUri("math-mcp:file:///x.md");
        assertNotNull(r);
        assertEquals("math-mcp", r.getService().getId());
        assertEquals("file:///x.md", r.getOriginalUri());
    }

    @Test
    void resolveResourceByUri_unknownService_returnsNull() {
        assertNull(registry.resolveResourceByUri("ghost:file://x"));
    }

    @Test
    void resolveResourceByUri_noNamespace_returnsNull() {
        assertNull(registry.resolveResourceByUri("file:///plain"));
    }

    @Test
    void resolveResourceByUri_serviceExistsButNotMcpServer_returnsNull() {
        Service plain = mcpService("plain");
        plain.getServiceMeta().handleUnknown(Constants.MCP_META_TYPE, "rest");
        serviceCache.put("plain", plain);
        assertNull(registry.resolveResourceByUri("plain:file://x"));
    }

    @Test
    void readResource_callsForwardWithOriginalUri() throws Exception {
        Service math = mcpServerService("math-mcp");
        serviceCache.put("math-mcp", math);
        Mockito.when(mcpServerClient.forwardResourcesRead(Mockito.eq(math), Mockito.eq("file:///x.md"), Mockito.anyInt()))
                .thenReturn(Map.of("contents", List.of(Map.of("uri", "file:///x.md", "text", "hello"))));

        McpResourceRegistry.Resolved r = registry.resolveResourceByUri("math-mcp:file:///x.md");
        Object out = registry.readResource(r, 5000);
        assertNotNull(out);
        Mockito.verify(mcpServerClient).forwardResourcesRead(math, "file:///x.md", 5000);
    }

    private Service mcpService(String id) {
        Service s = new Service();
        s.setId(id);
        s.setName(id);
        ServiceMeta meta = new ServiceMeta();
        meta.handleUnknown(Constants.MCP_META_ENABLED, "true");
        s.setServiceMeta(meta);
        return s;
    }

    private Service mcpServerService(String id) {
        Service s = mcpService(id);
        s.getServiceMeta().handleUnknown(Constants.MCP_META_TYPE, "server");
        return s;
    }
}