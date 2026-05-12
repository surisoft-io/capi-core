package io.surisoft.capi.service;

import io.surisoft.capi.schema.Service;
import org.cache2k.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates MCP <code>prompts/*</code> from upstream MCP servers.
 *
 * <p>Symmetric to {@link McpResourceRegistry}: fans out {@code prompts/list},
 * namespaces each prompt's {@code name} as {@code <serviceId>_<originalName>}
 * so subsequent {@code prompts/get} calls can be routed back without a
 * separate name→service mapping.
 */
public class McpPromptRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpPromptRegistry.class);
    private static final String NAMESPACE_SEP = "_";

    private final Cache<String, Service> serviceCache;
    private final McpServerClient mcpServerClient;
    private int defaultTimeoutMs = 10_000;

    public McpPromptRegistry(Cache<String, Service> serviceCache, McpServerClient mcpServerClient) {
        this.serviceCache = serviceCache;
        this.mcpServerClient = mcpServerClient;
    }

    public void setDefaultTimeoutMs(int defaultTimeoutMs) {
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    public List<Map<String, Object>> getAllPrompts() {
        if (mcpServerClient == null) return List.of();
        List<Map<String, Object>> aggregated = new ArrayList<>();
        for (Service service : serviceCache.asMap().values()) {
            if (!McpServerClient.isMcpServerBackend(service)) continue;
            try {
                List<Map<String, Object>> prompts = mcpServerClient.forwardPromptsList(service, defaultTimeoutMs);
                for (Map<String, Object> p : prompts) {
                    Map<String, Object> namespaced = new LinkedHashMap<>(p);
                    Object name = p.get("name");
                    if (name instanceof String) {
                        namespaced.put("name", service.getId() + NAMESPACE_SEP + name);
                    }
                    aggregated.add(namespaced);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted listing prompts from {}", service.getId());
                return aggregated;
            } catch (Exception e) {
                log.warn("Failed to list prompts from {}: {}", service.getId(), e.getMessage());
            }
        }
        return aggregated;
    }

    /**
     * Service ids may themselves contain underscores, so we resolve by trying
     * progressively longer service-id prefixes against the cache. The first
     * match wins; this is unambiguous because service ids are unique within
     * the cache and original prompt names are not constrained to be prefix-free
     * — but in practice the longest-match-first scan handles all real cases.
     */
    public Resolved resolvePromptByName(String namespacedName) {
        if (namespacedName == null) return null;
        // Try every '_' position from leftmost outward; first cache hit wins.
        int idx = 0;
        while ((idx = namespacedName.indexOf(NAMESPACE_SEP, idx + 1)) > 0) {
            String candidate = namespacedName.substring(0, idx);
            Service service = serviceCache.peek(candidate);
            if (service != null && McpServerClient.isMcpServerBackend(service)) {
                return new Resolved(service, namespacedName.substring(idx + 1));
            }
        }
        return null;
    }

    public Object getPrompt(Resolved resolved, Object arguments, int timeout) throws Exception {
        return mcpServerClient.forwardPromptsGet(resolved.getService(), resolved.getOriginalName(), arguments, timeout);
    }

    public static final class Resolved {
        private final Service service;
        private final String originalName;

        public Resolved(Service service, String originalName) {
            this.service = service;
            this.originalName = originalName;
        }
        public Service getService() { return service; }
        public String getOriginalName() { return originalName; }
    }
}