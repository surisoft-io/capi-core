package io.surisoft.capi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.surisoft.capi.schema.McpTool;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import io.surisoft.capi.utils.Constants;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Promotes OpenAPI operations attached to a {@link Service} into MCP tools.
 *
 * Phase 1: discovery only. The produced {@link McpTool} carries {@code httpMethod}
 * and {@code httpPathTemplate} so a future dispatcher can invoke them, but this
 * class does not execute calls.
 *
 * A service opts in via the Consul tag {@code mcp-from-openapi=true}. Optional
 * {@code mcp-promote-include} / {@code mcp-promote-exclude} comma-separated tags
 * filter by operationId or by the synthesized fallback name.
 */
public class OpenApiToMcpPromoter {

    private static final Logger log = LoggerFactory.getLogger(OpenApiToMcpPromoter.class);
    private static final String DEFAULT_INPUT_SCHEMA = "{\"type\":\"object\"}";
    private static final String JSON_CONTENT_TYPE = "application/json";

    private final ObjectMapper objectMapper;

    public OpenApiToMcpPromoter() {
        this(new ObjectMapper());
    }

    public OpenApiToMcpPromoter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public static boolean isOptedIn(Service service) {
        if (service == null || service.getServiceMeta() == null) return false;
        Map<String, String> props = service.getServiceMeta().getUnknownProperties();
        return props != null && "true".equalsIgnoreCase(props.get(Constants.MCP_META_FROM_OPENAPI));
    }

    public List<McpTool> promote(Service service) {
        List<McpTool> result = new ArrayList<>();
        if (!isOptedIn(service)) return result;
        OpenAPI api = service.getOpenAPI();
        if (api == null || api.getPaths() == null || api.getPaths().isEmpty()) return result;

        ServiceMeta meta = service.getServiceMeta();
        Map<String, String> props = meta.getUnknownProperties();

        Set<String> include = parseCsv(props.get(Constants.MCP_META_PROMOTE_INCLUDE));
        Set<String> exclude = parseCsv(props.get(Constants.MCP_META_PROMOTE_EXCLUDE));
        String prefix = props.getOrDefault(Constants.MCP_META_TOOL_PREFIX, "");
        String category = props.getOrDefault(Constants.MCP_META_CATEGORY, "");
        int defaultTimeout = parseTimeout(props.getOrDefault(Constants.MCP_META_TIMEOUT, "0"));

        for (Map.Entry<String, PathItem> entry : api.getPaths().entrySet()) {
            String path = entry.getKey();
            PathItem item = entry.getValue();
            if (item == null) continue;

            for (Map.Entry<PathItem.HttpMethod, Operation> opEntry : item.readOperationsMap().entrySet()) {
                PathItem.HttpMethod method = opEntry.getKey();
                Operation operation = opEntry.getValue();
                if (operation == null) continue;

                String baseName = toolNameFor(operation, method, path);
                if (!include.isEmpty() && !include.contains(baseName)) continue;
                if (exclude.contains(baseName)) continue;

                String qualified = prefix.isEmpty() ? baseName : prefix + "_" + baseName;

                McpTool tool = new McpTool();
                tool.setName(qualified);
                tool.setServiceId(service.getId());
                tool.setCategory(category);
                tool.setTimeout(defaultTimeout);
                tool.setHttpMethod(method.name());
                tool.setHttpPathTemplate(path);
                tool.setDescription(buildDescription(operation, method, path));
                tool.setInputSchema(buildInputSchema(operation, item));
                result.add(tool);
            }
        }
        return result;
    }

    private String toolNameFor(Operation operation, PathItem.HttpMethod method, String path) {
        if (operation.getOperationId() != null && !operation.getOperationId().isBlank()) {
            return sanitize(operation.getOperationId());
        }
        return sanitize(method.name().toLowerCase(Locale.ROOT) + "_" + path);
    }

    private String sanitize(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String s = sb.toString();
        // collapse repeated underscores
        s = s.replaceAll("_+", "_");
        // strip leading/trailing underscores
        s = s.replaceAll("^_+|_+$", "");
        return s.isEmpty() ? "tool" : s;
    }

    private String buildDescription(Operation op, PathItem.HttpMethod method, String path) {
        StringBuilder sb = new StringBuilder();
        if (op.getSummary() != null && !op.getSummary().isBlank()) {
            sb.append(op.getSummary().trim());
        }
        if (op.getDescription() != null && !op.getDescription().isBlank()) {
            if (sb.length() > 0) sb.append(" — ");
            sb.append(op.getDescription().trim());
        }
        if (sb.length() == 0) {
            sb.append(method.name()).append(' ').append(path);
        }
        return sb.toString();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private String buildInputSchema(Operation operation, PathItem pathItem) {
        Map<String, Object> schema = new LinkedHashMap<>();
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        addParameters(properties, required, pathItem.getParameters());
        addParameters(properties, required, operation.getParameters());

        RequestBody body = operation.getRequestBody();
        if (body != null && body.getContent() != null) {
            Map<String, Object> bodySchema = extractJsonBodySchema(body.getContent());
            if (bodySchema != null) {
                properties.put("body", bodySchema);
                if (Boolean.TRUE.equals(body.getRequired())) required.add("body");
            }
        }

        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);

        try {
            return objectMapper.writeValueAsString(schema);
        } catch (Exception e) {
            log.warn("Failed to serialize inputSchema for promoted tool, falling back: {}", e.getMessage());
            return DEFAULT_INPUT_SCHEMA;
        }
    }

    private void addParameters(Map<String, Object> properties, List<String> required, List<Parameter> params) {
        if (params == null) return;
        for (Parameter p : params) {
            if (p == null || p.getName() == null) continue;
            // Skip cookie params; rarely useful for MCP tool callers
            if ("cookie".equalsIgnoreCase(p.getIn())) continue;
            Map<String, Object> paramSchema = serializeSchema(p.getSchema());
            if (paramSchema == null) {
                paramSchema = new LinkedHashMap<>();
                paramSchema.put("type", "string");
            }
            if (p.getDescription() != null && !paramSchema.containsKey("description")) {
                paramSchema.put("description", p.getDescription());
            }
            properties.put(p.getName(), paramSchema);
            if (Boolean.TRUE.equals(p.getRequired())) required.add(p.getName());
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Map<String, Object> extractJsonBodySchema(Content content) {
        // Prefer application/json; fall back to first available media type
        MediaType mt = content.get(JSON_CONTENT_TYPE);
        if (mt == null && !content.isEmpty()) {
            mt = content.values().iterator().next();
        }
        if (mt == null || mt.getSchema() == null) return null;
        return serializeSchema(mt.getSchema());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Map<String, Object> serializeSchema(Schema schema) {
        if (schema == null) return null;
        try {
            // Use swagger-core's OpenAPI 3.1-aware mapper. A vanilla ObjectMapper
            // would emit every Schema getter that returns null (turning a 1-property
            // schema into a 60-key wall of `"foo": null`). The older Json.mapper()
            // suppresses nulls but doesn't collapse the model's internal multi-type
            // `types: ["string"]` representation back to `type: "string"`. Json31
            // does both — it's the one to use for any Schema produced by
            // swagger-parser-v3 against an OpenAPI 3.1 spec.
            return io.swagger.v3.core.util.Json31.mapper().convertValue(schema, LinkedHashMap.class);
        } catch (Exception e) {
            log.debug("Could not serialize OpenAPI schema, defaulting to object: {}", e.getMessage());
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("type", "object");
            return fallback;
        }
    }

    private Set<String> parseCsv(String value) {
        if (value == null || value.isBlank()) return Set.of();
        Set<String> out = new HashSet<>();
        for (String s : Arrays.asList(value.split(","))) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    private int parseTimeout(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}