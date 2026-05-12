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
 * Aggregates MCP <code>resources/*</code> from upstream MCP servers.
 *
 * <p>For every service with {@code mcp-type=server}, the registry fans out a
 * {@code resources/list} call via {@link McpServerClient}, then prefixes each
 * returned URI with the service id so that {@code resources/read} can be
 * routed back to the right backend without a separate URI→service map.
 *
 * <p>Aggregation is per-call (no internal cache) — keeps the implementation
 * simple and lets backends evolve their resource catalogues without a refresh
 * cycle. Latency is acceptable because resources/list happens infrequently
 * relative to read calls.
 */
public class McpResourceRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpResourceRegistry.class);
    private static final String NAMESPACE_SEP = ":";

    private final Cache<String, Service> serviceCache;
    private final McpServerClient mcpServerClient;
    private int defaultTimeoutMs = 10_000;

    public McpResourceRegistry(Cache<String, Service> serviceCache, McpServerClient mcpServerClient) {
        this.serviceCache = serviceCache;
        this.mcpServerClient = mcpServerClient;
    }

    public void setDefaultTimeoutMs(int defaultTimeoutMs) {
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    /**
     * Aggregate {@code resources/list} from every {@code mcp-type=server} service.
     * Each resource's {@code uri} field is prefixed with the service id.
     */
    public List<Map<String, Object>> getAllResources() {
        if (mcpServerClient == null) return List.of();
        List<Map<String, Object>> aggregated = new ArrayList<>();
        for (Service service : serviceCache.asMap().values()) {
            if (!McpServerClient.isMcpServerBackend(service)) continue;
            try {
                List<Map<String, Object>> resources = mcpServerClient.forwardResourcesList(service, defaultTimeoutMs);
                for (Map<String, Object> r : resources) {
                    Map<String, Object> namespaced = new LinkedHashMap<>(r);
                    Object uri = r.get("uri");
                    if (uri instanceof String) {
                        namespaced.put("uri", service.getId() + NAMESPACE_SEP + uri);
                    }
                    aggregated.add(namespaced);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted listing resources from {}", service.getId());
                return aggregated;
            } catch (Exception e) {
                log.warn("Failed to list resources from {}: {}", service.getId(), e.getMessage());
            }
        }
        return aggregated;
    }

    /**
     * Resolve a namespaced URI back to its owning service and original URI.
     *
     * <p>Service IDs in CAPI follow the {@code <name>:<group>} convention so they
     * themselves contain the namespace separator ({@code :}). To disambiguate, we
     * scan {@code :} positions left-to-right and return the first prefix that
     * matches a registered {@code mcp-type=server} service.
     */
    public Resolved resolveResourceByUri(String namespacedUri) {
        if (namespacedUri == null) return null;
        int idx = 0;
        while ((idx = namespacedUri.indexOf(NAMESPACE_SEP, idx + 1)) > 0) {
            String candidate = namespacedUri.substring(0, idx);
            Service service = serviceCache.peek(candidate);
            if (service != null && McpServerClient.isMcpServerBackend(service)) {
                return new Resolved(service, namespacedUri.substring(idx + 1));
            }
        }
        return null;
    }

    public Object readResource(Resolved resolved, int timeout) throws Exception {
        return mcpServerClient.forwardResourcesRead(resolved.getService(), resolved.getOriginalUri(), timeout);
    }

    public static final class Resolved {
        private final Service service;
        private final String originalUri;

        public Resolved(Service service, String originalUri) {
            this.service = service;
            this.originalUri = originalUri;
        }
        public Service getService() { return service; }
        public String getOriginalUri() { return originalUri; }
    }
}