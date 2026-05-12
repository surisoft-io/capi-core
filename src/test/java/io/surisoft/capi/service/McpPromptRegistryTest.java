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

class McpPromptRegistryTest {

    private Cache<String, Service> serviceCache;
    private McpServerClient mcpServerClient;
    private McpPromptRegistry registry;

    @BeforeEach
    void setUp() {
        serviceCache = new Cache2kBuilder<String, Service>() {}
                .name("testPromptReg-" + System.nanoTime())
                .eternal(true).entryCapacity(100).storeByReference(true).build();
        mcpServerClient = Mockito.mock(McpServerClient.class);
        registry = new McpPromptRegistry(serviceCache, mcpServerClient);
    }

    @AfterEach
    void tearDown() {
        serviceCache.close();
    }

    @Test
    void emptyCache_returnsEmptyList() {
        assertTrue(registry.getAllPrompts().isEmpty());
    }

    @Test
    void singleServer_namesNamespacedByServiceId() throws Exception {
        Service math = mcpServerService("mathmcp");
        serviceCache.put("mathmcp", math);

        Mockito.when(mcpServerClient.forwardPromptsList(Mockito.eq(math), Mockito.anyInt()))
                .thenReturn(List.of(
                        Map.of("name", "explain", "description", "Explain a calculation"),
                        Map.of("name", "summarize")
                ));

        List<Map<String, Object>> all = registry.getAllPrompts();
        assertEquals(2, all.size());
        assertEquals("mathmcp_explain", all.get(0).get("name"));
        assertEquals("mathmcp_summarize", all.get(1).get("name"));
        assertEquals("Explain a calculation", all.get(0).get("description"));
    }

    @Test
    void multipleServers_promptsAggregated() throws Exception {
        Service math = mcpServerService("math");
        Service kb = mcpServerService("kb");
        serviceCache.put("math", math);
        serviceCache.put("kb", kb);

        Mockito.when(mcpServerClient.forwardPromptsList(Mockito.eq(math), Mockito.anyInt()))
                .thenReturn(List.of(Map.of("name", "calc")));
        Mockito.when(mcpServerClient.forwardPromptsList(Mockito.eq(kb), Mockito.anyInt()))
                .thenReturn(List.of(Map.of("name", "search")));

        List<Map<String, Object>> all = registry.getAllPrompts();
        assertEquals(2, all.size());
        assertTrue(all.stream().anyMatch(p -> "math_calc".equals(p.get("name"))));
        assertTrue(all.stream().anyMatch(p -> "kb_search".equals(p.get("name"))));
    }

    @Test
    void resolvePrompt_simpleServiceId() {
        Service math = mcpServerService("math");
        serviceCache.put("math", math);

        McpPromptRegistry.Resolved r = registry.resolvePromptByName("math_calc");
        assertNotNull(r);
        assertEquals("math", r.getService().getId());
        assertEquals("calc", r.getOriginalName());
    }

    @Test
    void resolvePrompt_serviceIdContainsUnderscore_resolvesByLeftmostMatch() {
        // The longer prefix wins because the shorter one isn't a registered service
        Service multi = mcpServerService("math_advanced");
        serviceCache.put("math_advanced", multi);

        McpPromptRegistry.Resolved r = registry.resolvePromptByName("math_advanced_explain");
        assertNotNull(r);
        assertEquals("math_advanced", r.getService().getId());
        assertEquals("explain", r.getOriginalName());
    }

    @Test
    void resolvePrompt_unknownService_returnsNull() {
        assertNull(registry.resolvePromptByName("ghost_anything"));
    }

    @Test
    void resolvePrompt_noUnderscore_returnsNull() {
        assertNull(registry.resolvePromptByName("plainname"));
    }

    @Test
    void getPrompt_callsForwardWithOriginalName() throws Exception {
        Service math = mcpServerService("math");
        serviceCache.put("math", math);
        Mockito.when(mcpServerClient.forwardPromptsGet(Mockito.eq(math), Mockito.eq("calc"),
                        Mockito.any(), Mockito.anyInt()))
                .thenReturn(Map.of("messages", List.of(Map.of("role", "user", "content", "..."))));

        McpPromptRegistry.Resolved r = registry.resolvePromptByName("math_calc");
        Object out = registry.getPrompt(r, Map.of("expression", "1+1"), 5000);
        assertNotNull(out);
        Mockito.verify(mcpServerClient).forwardPromptsGet(math, "calc", Map.of("expression", "1+1"), 5000);
    }

    private Service mcpServerService(String id) {
        Service s = new Service();
        s.setId(id);
        s.setName(id);
        ServiceMeta meta = new ServiceMeta();
        meta.handleUnknown(Constants.MCP_META_ENABLED, "true");
        meta.handleUnknown(Constants.MCP_META_TYPE, "server");
        s.setServiceMeta(meta);
        return s;
    }
}