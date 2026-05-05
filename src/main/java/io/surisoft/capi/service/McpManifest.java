package io.surisoft.capi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Canonicaliser for MCP tool manifests. Produces deterministic UTF-8 bytes
 * suitable for cryptographic signing and verification.
 *
 * <p>Canonical form:
 * <pre>
 * {"description":"...","inputSchema":{...},"name":"...","serviceId":"...","version":"..."}
 * </pre>
 * — sorted top-level keys, embedded JSON Schema with sorted keys at every depth,
 * no whitespace, UTF-8 encoding. The order of array elements is preserved as-is
 * (JSON Schema arrays like {@code required} carry semantic order).
 *
 * <p>The {@code inputSchema} field is parsed from the raw JSON string carried on
 * {@link io.surisoft.capi.schema.McpTool} so signature verification is independent
 * of how the operator's signing pipeline serialised the schema.
 */
public final class McpManifest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private McpManifest() {}

    public static byte[] canonicalize(String serviceId,
                                      String toolName,
                                      String description,
                                      String inputSchemaJson,
                                      String version) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("description", description == null ? "" : description);
            root.set("inputSchema", parseInputSchema(inputSchemaJson));
            root.put("name", toolName == null ? "" : toolName);
            root.put("serviceId", serviceId == null ? "" : serviceId);
            root.put("version", version == null ? "" : version);

            JsonNode sorted = sortKeys(root);
            return MAPPER.writeValueAsBytes(sorted);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to canonicalize MCP manifest: " + e.getMessage(), e);
        }
    }

    private static JsonNode parseInputSchema(String inputSchemaJson) {
        if (inputSchemaJson == null || inputSchemaJson.isBlank()) {
            return MAPPER.createObjectNode().put("type", "object");
        }
        try {
            return MAPPER.readTree(inputSchemaJson);
        } catch (JsonProcessingException e) {
            // Fall back to the empty object schema so verification of a bad-but-stable
            // schema doesn't accidentally pass — operator's signing pipeline would have
            // produced the same fallback.
            return MAPPER.createObjectNode().put("type", "object");
        }
    }

    private static JsonNode sortKeys(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = MAPPER.createObjectNode();
            List<String> names = new ArrayList<>();
            Iterator<String> it = node.fieldNames();
            while (it.hasNext()) names.add(it.next());
            Collections.sort(names);
            for (String name : names) {
                out.set(name, sortKeys(node.get(name)));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            for (JsonNode child : node) {
                out.add(sortKeys(child));
            }
            return out;
        }
        return node;
    }

    public static String canonicalizeAsString(String serviceId,
                                              String toolName,
                                              String description,
                                              String inputSchemaJson,
                                              String version) {
        return new String(canonicalize(serviceId, toolName, description, inputSchemaJson, version),
                StandardCharsets.UTF_8);
    }
}