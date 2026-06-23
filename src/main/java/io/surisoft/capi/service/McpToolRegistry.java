package io.surisoft.capi.service;

import io.surisoft.capi.schema.McpTool;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.utils.Constants;
import org.cache2k.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;


public class McpToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistry.class);
    private static final String TOOLS_DOT = "tools-";

    /** Signing modes for tag-defined tool manifests. */
    public static final String SIGNING_OFF = "off";
    public static final String SIGNING_WARN = "warn";
    public static final String SIGNING_ENFORCE = "enforce";

    private final Cache<String, Service> serviceCache;
    private final OpenApiToMcpPromoter promoter;
    private McpManifestVerifier verifier;
    private String signingMode = SIGNING_OFF;

    public McpToolRegistry(Cache<String, Service> serviceCache) {
        this(serviceCache, new OpenApiToMcpPromoter());
    }

    public McpToolRegistry(Cache<String, Service> serviceCache, OpenApiToMcpPromoter promoter) {
        this.serviceCache = serviceCache;
        this.promoter = promoter;
    }

    public void setManifestVerifier(McpManifestVerifier verifier) {
        this.verifier = verifier;
    }

    public void setSigningMode(String mode) {
        if (mode == null) {
            this.signingMode = SIGNING_OFF;
            return;
        }
        String m = mode.trim().toLowerCase(Locale.ROOT);
        if (SIGNING_OFF.equals(m) || SIGNING_WARN.equals(m) || SIGNING_ENFORCE.equals(m)) {
            this.signingMode = m;
        } else {
            log.warn("Unknown MCP signing mode '{}' — falling back to '{}'", mode, SIGNING_OFF);
            this.signingMode = SIGNING_OFF;
        }
    }

    public List<McpTool> getAllTools() {
        List<McpTool> tools = new ArrayList<>();
        for (Service service : serviceCache.asMap().values()) {
            tools.addAll(toolsForService(service));
        }
        return tools;
    }

    public McpToolResolution resolveToolByName(String toolName) {
        for (Service service : serviceCache.asMap().values()) {
            for (McpTool tool : toolsForService(service)) {
                if (tool.getName().equals(toolName)) {
                    return new McpToolResolution(tool, service);
                }
            }
        }
        return null;
    }

    private List<McpTool> toolsForService(Service service) {
        if (service == null || service.getServiceMeta() == null) return List.of();
        Map<String, String> props = service.getServiceMeta().getUnknownProperties();
        if (props == null) return List.of();

        boolean tagsEnabled = "true".equalsIgnoreCase(props.get(Constants.MCP_META_ENABLED));
        boolean openApiEnabled = "true".equalsIgnoreCase(props.get(Constants.MCP_META_FROM_OPENAPI));
        if (!tagsEnabled && !openApiEnabled) return List.of();

        // Tag-defined tools win on name collisions, so insert them last.
        Map<String, McpTool> byName = new LinkedHashMap<>();
        if (openApiEnabled) {
            for (McpTool t : promoter.promote(service)) {
                byName.put(t.getName(), t);
            }
        }
        if (tagsEnabled) {
            for (McpTool t : extractTools(service)) {
                byName.put(t.getName(), t);
            }
        }
        return new ArrayList<>(byName.values());
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
        boolean requireSigned = "true".equalsIgnoreCase(props.get(Constants.MCP_META_REQUIRED_SIGNED));
        String version = service.getServiceMeta().getVersion();

        List<String> streamingList = List.of(streamingNames.split(","));

        for (String rawName : toolNames.split(",")) {
            String name = rawName.trim();
            if (name.isEmpty()) {
                continue;
            }

            McpTool tool = new McpTool();
            String qualifiedName = prefix.isEmpty() ? name : prefix + "_" + name;
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

            if (admitTool(tool, name, props, requireSigned, version)) {
                tools.add(tool);
            }
        }
        return tools;
    }

    private boolean admitTool(McpTool tool,
                              String rawName,
                              Map<String, String> props,
                              boolean requireSigned,
                              String version) {
        if (SIGNING_OFF.equals(signingMode) || verifier == null) {
            return true;
        }

        String sigKey = Constants.MCP_META_PREFIX + TOOLS_DOT + rawName + Constants.MCP_META_TOOLS_SIGNATURE_SUFFIX;
        String keyIdKey = Constants.MCP_META_PREFIX + TOOLS_DOT + rawName + Constants.MCP_META_TOOLS_KEYID_SUFFIX;
        String signature = props.get(sigKey);
        String keyId = props.get(keyIdKey);

        boolean signaturePresent = signature != null && !signature.isBlank();

        if (!signaturePresent) {
            if (requireSigned) {
                if (SIGNING_ENFORCE.equals(signingMode)) {
                    log.warn("MCP signing: dropping unsigned tool '{}' (service '{}', mcp-required-signed=true)",
                            tool.getName(), tool.getServiceId());
                    return false;
                }
                log.warn("MCP signing[warn]: tool '{}' (service '{}') is unsigned but mcp-required-signed=true",
                        tool.getName(), tool.getServiceId());
            }
            return true;
        }

        McpManifestVerifier.Result result = verifier.verify(tool, signature, keyId, version);
        if (result.isOk()) {
            return true;
        }

        if (SIGNING_ENFORCE.equals(signingMode)) {
            log.warn("MCP signing: dropping tool '{}' (service '{}') — {} (keyid={})",
                    tool.getName(), tool.getServiceId(), result.getReason(), result.getKeyId());
            return false;
        }
        log.warn("MCP signing[warn]: tool '{}' (service '{}') failed verification — {} (keyid={})",
                tool.getName(), tool.getServiceId(), result.getReason(), result.getKeyId());
        return true;
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