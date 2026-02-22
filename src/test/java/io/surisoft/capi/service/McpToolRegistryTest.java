package io.surisoft.capi.service;

import io.surisoft.capi.schema.McpTool;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McpToolRegistryTest {

    private Cache<String, Service> serviceCache;
    private McpToolRegistry registry;

    @BeforeEach
    void setUp() {
        serviceCache = new Cache2kBuilder<String, Service>() {}
                .name("testMcpRegistry-" + System.currentTimeMillis())
                .eternal(true)
                .entryCapacity(100)
                .storeByReference(true)
                .build();
        registry = new McpToolRegistry(serviceCache);
    }

    @AfterEach
    void tearDown() {
        serviceCache.close();
    }

    @Test
    void getAllTools_emptyCache_returnsEmptyList() {
        List<McpTool> tools = registry.getAllTools();
        assertTrue(tools.isEmpty());
    }

    @Test
    void getAllTools_serviceWithoutMcpEnabled_returnsEmptyList() {
        Service service = createService("svc1");
        serviceCache.put("svc1", service);

        List<McpTool> tools = registry.getAllTools();
        assertTrue(tools.isEmpty());
    }

    @Test
    void getAllTools_serviceWithMcpEnabled_returnsTools() {
        Service service = createMcpService("svc1", "hello,goodbye");
        serviceCache.put("svc1", service);

        List<McpTool> tools = registry.getAllTools();
        assertEquals(2, tools.size());
        assertEquals("hello", tools.get(0).getName());
        assertEquals("goodbye", tools.get(1).getName());
    }

    @Test
    void getAllTools_withPrefix_prefixesToolNames() {
        Service service = createMcpService("svc1", "get,list");
        service.getServiceMeta().handleUnknown("mcp-toolPrefix", "orders");
        serviceCache.put("svc1", service);

        List<McpTool> tools = registry.getAllTools();
        assertEquals(2, tools.size());
        assertEquals("orders_get", tools.get(0).getName());
        assertEquals("orders_list", tools.get(1).getName());
    }

    @Test
    void getAllTools_withDescription_setsDescription() {
        Service service = createMcpService("svc1", "hello");
        service.getServiceMeta().handleUnknown("mcp-tools-hello-description", "Says hello");
        serviceCache.put("svc1", service);

        List<McpTool> tools = registry.getAllTools();
        assertEquals(1, tools.size());
        assertEquals("Says hello", tools.get(0).getDescription());
    }

    @Test
    void getAllTools_withInputSchema_setsSchema() {
        Service service = createMcpService("svc1", "hello");
        service.getServiceMeta().handleUnknown("mcp-tools-hello-inputSchema", "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}");
        serviceCache.put("svc1", service);

        List<McpTool> tools = registry.getAllTools();
        assertEquals(1, tools.size());
        assertTrue(tools.get(0).getInputSchema().contains("name"));
    }

    @Test
    void getAllTools_withStreaming_setsStreamingFlag() {
        Service service = createMcpService("svc1", "stream,normal");
        service.getServiceMeta().handleUnknown("mcp-streaming", "stream");
        serviceCache.put("svc1", service);

        List<McpTool> tools = registry.getAllTools();
        assertEquals(2, tools.size());
        McpTool streamTool = tools.stream().filter(t -> t.getName().equals("stream")).findFirst().orElse(null);
        McpTool normalTool = tools.stream().filter(t -> t.getName().equals("normal")).findFirst().orElse(null);
        assertNotNull(streamTool);
        assertNotNull(normalTool);
        assertTrue(streamTool.isStreaming());
        assertFalse(normalTool.isStreaming());
    }

    @Test
    void getAllTools_withCategoryAndTimeout_setsBoth() {
        Service service = createMcpService("svc1", "hello");
        service.getServiceMeta().handleUnknown("mcp-category", "utils");
        service.getServiceMeta().handleUnknown("mcp-timeout", "5000");
        serviceCache.put("svc1", service);

        List<McpTool> tools = registry.getAllTools();
        assertEquals(1, tools.size());
        assertEquals("utils", tools.get(0).getCategory());
        assertEquals(5000, tools.get(0).getTimeout());
    }

    @Test
    void getAllTools_withPerToolTimeout_overridesGlobal() {
        Service service = createMcpService("svc1", "hello");
        service.getServiceMeta().handleUnknown("mcp-timeout", "5000");
        service.getServiceMeta().handleUnknown("mcp-tools-hello-timeout", "10000");
        serviceCache.put("svc1", service);

        List<McpTool> tools = registry.getAllTools();
        assertEquals(1, tools.size());
        assertEquals(10000, tools.get(0).getTimeout());
    }

    @Test
    void resolveToolByName_found() {
        Service service = createMcpService("svc1", "hello");
        serviceCache.put("svc1", service);

        McpToolRegistry.McpToolResolution resolution = registry.resolveToolByName("hello");
        assertNotNull(resolution);
        assertEquals("hello", resolution.getTool().getName());
        assertEquals("svc1", resolution.getService().getId());
    }

    @Test
    void resolveToolByName_withPrefix() {
        Service service = createMcpService("svc1", "get");
        service.getServiceMeta().handleUnknown("mcp-toolPrefix", "orders");
        serviceCache.put("svc1", service);

        McpToolRegistry.McpToolResolution resolution = registry.resolveToolByName("orders_get");
        assertNotNull(resolution);
        assertEquals("orders_get", resolution.getTool().getName());
    }

    @Test
    void resolveToolByName_notFound() {
        Service service = createMcpService("svc1", "hello");
        serviceCache.put("svc1", service);

        McpToolRegistry.McpToolResolution resolution = registry.resolveToolByName("nonexistent");
        assertNull(resolution);
    }

    @Test
    void getAllTools_serviceWithNullServiceMeta_skipped() {
        Service service = new Service();
        service.setId("svc1");
        serviceCache.put("svc1", service);

        List<McpTool> tools = registry.getAllTools();
        assertTrue(tools.isEmpty());
    }

    @Test
    void getAllTools_emptyToolNames_returnsEmpty() {
        Service service = createService("svc1");
        service.getServiceMeta().handleUnknown("mcp-enabled", "true");
        service.getServiceMeta().handleUnknown("mcp-tools", "");
        serviceCache.put("svc1", service);

        List<McpTool> tools = registry.getAllTools();
        assertTrue(tools.isEmpty());
    }

    @Test
    void getAllTools_setsServiceId() {
        Service service = createMcpService("my-service-123", "hello");
        serviceCache.put("my-service-123", service);

        List<McpTool> tools = registry.getAllTools();
        assertEquals(1, tools.size());
        assertEquals("my-service-123", tools.get(0).getServiceId());
    }

    @Test
    void getAllTools_mcpServerService_setsMcpServerFlag() {
        Service service = createMcpService("svc1", "hello");
        service.getServiceMeta().handleUnknown("mcp-type", "server");
        serviceCache.put("svc1", service);

        List<McpTool> tools = registry.getAllTools();
        assertEquals(1, tools.size());
        assertTrue(tools.get(0).isMcpServer());
    }

    @Test
    void getAllTools_restService_mcpServerFlagIsFalse() {
        Service service = createMcpService("svc1", "hello");
        serviceCache.put("svc1", service);

        List<McpTool> tools = registry.getAllTools();
        assertEquals(1, tools.size());
        assertFalse(tools.get(0).isMcpServer());
    }

    @Test
    void getAllTools_explicitRestType_mcpServerFlagIsFalse() {
        Service service = createMcpService("svc1", "hello");
        service.getServiceMeta().handleUnknown("mcp-type", "rest");
        serviceCache.put("svc1", service);

        List<McpTool> tools = registry.getAllTools();
        assertEquals(1, tools.size());
        assertFalse(tools.get(0).isMcpServer());
    }

    private Service createService(String id) {
        Service service = new Service();
        service.setId(id);
        service.setName(id);
        ServiceMeta meta = new ServiceMeta();
        service.setServiceMeta(meta);
        return service;
    }

    private Service createMcpService(String id, String tools) {
        Service service = createService(id);
        service.getServiceMeta().handleUnknown("mcp-enabled", "true");
        service.getServiceMeta().handleUnknown("mcp-tools", tools);
        return service;
    }
}
