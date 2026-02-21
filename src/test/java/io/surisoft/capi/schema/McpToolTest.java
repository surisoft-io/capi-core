package io.surisoft.capi.schema;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpToolTest {

    @Test
    void name_getterSetter() {
        McpTool tool = new McpTool();
        assertNull(tool.getName());
        tool.setName("orders.get");
        assertEquals("orders.get", tool.getName());
    }

    @Test
    void description_getterSetter() {
        McpTool tool = new McpTool();
        assertNull(tool.getDescription());
        tool.setDescription("Get order by ID");
        assertEquals("Get order by ID", tool.getDescription());
    }

    @Test
    void inputSchema_getterSetter() {
        McpTool tool = new McpTool();
        assertNull(tool.getInputSchema());
        tool.setInputSchema("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}");
        assertTrue(tool.getInputSchema().contains("object"));
    }

    @Test
    void serviceId_getterSetter() {
        McpTool tool = new McpTool();
        assertNull(tool.getServiceId());
        tool.setServiceId("order-service");
        assertEquals("order-service", tool.getServiceId());
    }

    @Test
    void streaming_getterSetter() {
        McpTool tool = new McpTool();
        assertFalse(tool.isStreaming());
        tool.setStreaming(true);
        assertTrue(tool.isStreaming());
    }

    @Test
    void category_getterSetter() {
        McpTool tool = new McpTool();
        assertNull(tool.getCategory());
        tool.setCategory("commerce");
        assertEquals("commerce", tool.getCategory());
    }

    @Test
    void timeout_getterSetter() {
        McpTool tool = new McpTool();
        assertEquals(0, tool.getTimeout());
        tool.setTimeout(5000);
        assertEquals(5000, tool.getTimeout());
    }
}
