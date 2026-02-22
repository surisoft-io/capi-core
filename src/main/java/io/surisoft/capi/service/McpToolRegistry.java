package io.surisoft.capi.service;

import io.surisoft.capi.schema.McpTool;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.utils.Constants;
import org.cache2k.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class McpToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistry.class);
    private static final String TOOLS_DOT = "tools-";
    private final Cache<String, Service> serviceCache;

    public McpToolRegistry(Cache<String, Service> serviceCache) {
        this.serviceCache = serviceCache;
    }

    public List<McpTool> getAllTools() {
        List<McpTool> tools = new ArrayList<>();
        for (Service service : serviceCache.asMap().values()) {
            if (service.getServiceMeta() == null) {
                continue;
            }
            Map<String, String> props = service.getServiceMeta().getUnknownProperties();
            if (props == null || !"true".equalsIgnoreCase(props.get(Constants.MCP_META_ENABLED))) {
                continue;
            }
            tools.addAll(extractTools(service));
        }
        return tools;
    }

    public McpToolResolution resolveToolByName(String toolName) {
        for (Service service : serviceCache.asMap().values()) {
            if (service.getServiceMeta() == null) {
                continue;
            }
            Map<String, String> props = service.getServiceMeta().getUnknownProperties();
            if (props == null || !"true".equalsIgnoreCase(props.get(Constants.MCP_META_ENABLED))) {
                continue;
            }
            for (McpTool tool : extractTools(service)) {
                if (tool.getName().equals(toolName)) {
                    return new McpToolResolution(tool, service);
                }
            }
        }
        return null;
    }

    private List<McpTool> extractTools(Service service) {
        List<McpTool> tools = new ArrayList<>();
        Map<String, String> props = service.getServiceMeta().getUnknownProperties();

        String toolNames = props.get(Constants.MCP_META_TOOLS);
        if (toolNames == null || toolNames.isEmpty()) {
            return tools;
        }

        String prefix = props.getOrDefault(Constants.MCP_META_TOOL_PREFIX, "");
        String streamingNames = props.getOrDefault(Constants.MCP_META_STREAMING, "");
        String category = props.getOrDefault(Constants.MCP_META_CATEGORY, "");
        int timeout = parseTimeout(props.getOrDefault(Constants.MCP_META_TIMEOUT, "0"));

        List<String> streamingList = List.of(streamingNames.split(","));

        for (String rawName : toolNames.split(",")) {
            String name = rawName.trim();
            if (name.isEmpty()) {
                continue;
            }

            McpTool tool = new McpTool();
            String qualifiedName = prefix.isEmpty() ? name : prefix + "." + name;
            tool.setName(qualifiedName);
            tool.setServiceId(service.getId());
            tool.setCategory(category);
            tool.setTimeout(timeout);
            tool.setStreaming(streamingList.contains(name));

            String mcpType = props.getOrDefault(Constants.MCP_META_TYPE, "rest");
            tool.setMcpServer("server".equalsIgnoreCase(mcpType));

            String descKey = Constants.MCP_META_PREFIX + TOOLS_DOT + name + "-description";
            tool.setDescription(props.getOrDefault(descKey, qualifiedName));

            String schemaKey = Constants.MCP_META_PREFIX + TOOLS_DOT + name + "-inputSchema";
            tool.setInputSchema(props.getOrDefault(schemaKey, "{\"type\":\"object\"}"));

            // Per-tool timeout override
            String toolTimeoutKey = Constants.MCP_META_PREFIX + TOOLS_DOT + name + "-timeout";
            String toolTimeout = props.get(toolTimeoutKey);
            if (toolTimeout != null) {
                tool.setTimeout(parseTimeout(toolTimeout));
            }

            tools.add(tool);
        }
        return tools;
    }

    private int parseTimeout(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static class McpToolResolution {
        private final McpTool tool;
        private final Service service;

        public McpToolResolution(McpTool tool, Service service) {
            this.tool = tool;
            this.service = service;
        }

        public McpTool getTool() {
            return tool;
        }

        public Service getService() {
            return service;
        }
    }
}
