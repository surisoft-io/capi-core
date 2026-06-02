package io.surisoft.capi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.surisoft.capi.schema.OpaResult;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * OPA policy evaluation using a single combined Wasm bundle (Chicory-based, in-process).
 * <p>
 * One bundle ({@code bundleUrl}) contains every policy as a separate entrypoint, plus
 * a merged {@code data.json}. CAPI fetches it on a fixed interval with ETag-based
 * conditional GETs, then re-instantiates the shared pool of {@link com.styra.opa.wasm.OpaPolicy}
 * instances. Each instance can evaluate any of the bundle's entrypoints.
 * <p>
 * Follows OPA's recommended layout — see
 * <a href="https://www.openpolicyagent.org/docs/management-bundles">Bundles</a>.
 */
public class OpaWasmService {

    private static final Logger log = LoggerFactory.getLogger(OpaWasmService.class);

    private final String bundleUrl;
    private final String bundleToken;
    private final int poolSize;
    private volatile HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** The single shared pool of OpaPolicy instances loaded from the bundle. */
    private volatile ConcurrentLinkedQueue<com.styra.opa.wasm.OpaPolicy> sharedPool;
    /** Entrypoints declared in the bundle's .manifest, e.g. "capi/admin_only/allow". */
    private volatile Set<String> bundleEntrypoints = Collections.emptySet();
    /** Last seen ETag for conditional bundle fetches. */
    private volatile String lastETag;
    /** Rego paths registered by service discovery — used for diagnostics only. */
    private final Set<String> registeredPolicies = ConcurrentHashMap.newKeySet();

    public OpaWasmService(String bundleUrl, int poolSize, HttpClient httpClient) {
        this(bundleUrl, null, poolSize, httpClient);
    }

    public OpaWasmService(String bundleUrl, String bundleToken, int poolSize, HttpClient httpClient) {
        this.bundleUrl = bundleUrl;
        this.bundleToken = (bundleToken == null || bundleToken.isBlank()) ? null : bundleToken;
        this.poolSize = poolSize;
        this.httpClient = httpClient;
    }

    public void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Register a rego policy path that some discovered service expects to exist in the
     * bundle. Used only for diagnostics — the single combined bundle is fetched once
     * regardless of which paths are registered.
     */
    public void registerPolicy(String opaRego) {
        if (opaRego != null && !opaRego.isEmpty()) {
            boolean added = registeredPolicies.add(opaRego);
            if (added) {
                log.info("OPA Wasm policy registered: {}", opaRego);
                // If the bundle is already loaded, warn at registration time if the
                // requested entrypoint isn't in it.
                Set<String> ep = bundleEntrypoints;
                if (!ep.isEmpty() && !ep.contains(opaRego + "/allow")) {
                    log.warn("Registered policy {} is not declared in the loaded bundle (entrypoints={})",
                            opaRego, ep);
                }
            }
        }
    }

    /**
     * Fetch the bundle and rebuild the shared pool if it changed. Call from a scheduled timer.
     */
    public void pollBundles() {
        try {
            pollBundle();
        } catch (Exception e) {
            log.error("Failed to poll OPA bundle from {}: {}", bundleUrl, e.getMessage());
        }
    }

    /**
     * Evaluate the given rego path against the supplied principal value.
     * Returns {@code null} when the pool isn't ready, the requested entrypoint isn't
     * in the bundle, or wasm evaluation throws.
     */
    public OpaResult evaluate(String serviceId, String opaRego, String value, boolean isAccessToken) {
        if (!hasPolicy(opaRego)) {
            log.trace("OPA Wasm evaluate skipped — policy {} not in bundle (entrypoints={})",
                    opaRego, bundleEntrypoints);
            return null;
        }
        ConcurrentLinkedQueue<com.styra.opa.wasm.OpaPolicy> pool = sharedPool;
        com.styra.opa.wasm.OpaPolicy policy = pool != null ? pool.poll() : null;
        if (policy == null) {
            log.trace("OPA Wasm evaluate skipped — pool empty for {}", opaRego);
            return null;
        }
        try {
            String input = buildInput(serviceId, value, isAccessToken);
            String entrypoint = opaRego + "/allow";
            log.trace("OPA Wasm evaluate entrypoint={} input={}", entrypoint, input);
            String resultJson = policy.entrypoint(entrypoint).evaluate(input);
            log.trace("OPA Wasm result for {}: {}", entrypoint, resultJson);
            pool.offer(policy);
            return parseResult(resultJson);
        } catch (Exception e) {
            log.error("OPA Wasm evaluation failed for {}: {}, discarding policy instance", opaRego, e.getMessage());
            // Don't return the policy to the pool — its internal state may be corrupted.
            return null;
        }
    }

    /**
     * Whether the shared pool is loaded AND the bundle declares an entrypoint for this rego path.
     * False can mean either: bundle not yet fetched, or bundle loaded but does not contain
     * this policy. Distinguish via {@link #isReady()} and {@link #hasPolicy(String)}.
     */
    public boolean isReady(String opaRego) {
        return isReady() && hasPolicy(opaRego);
    }

    /** Whether the shared pool is loaded (regardless of which entrypoints it declares). */
    public boolean isReady() {
        ConcurrentLinkedQueue<com.styra.opa.wasm.OpaPolicy> pool = sharedPool;
        return pool != null && !pool.isEmpty();
    }

    /** Whether the loaded bundle declares an entrypoint for this rego path. */
    public boolean hasPolicy(String opaRego) {
        return opaRego != null && bundleEntrypoints.contains(opaRego + "/allow");
    }

    public int getLoadedPolicyCount() {
        return bundleEntrypoints.size();
    }

    public Set<String> getBundleEntrypoints() {
        return bundleEntrypoints;
    }

    private void pollBundle() {
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(bundleUrl))
                    .timeout(Duration.ofSeconds(30))
                    .GET();

            if (lastETag != null) {
                requestBuilder.header("If-None-Match", lastETag);
            }
            if (bundleToken != null) {
                requestBuilder.header("Authorization", "Bearer " + bundleToken);
            }

            HttpResponse<InputStream> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() == 304) {
                log.info("OPA Wasm bundle unchanged (ETag: {})", lastETag);
                return;
            }

            if (response.statusCode() != 200) {
                log.warn("OPA bundle server returned {} for {}", response.statusCode(), bundleUrl);
                return;
            }

            String newETag = response.headers().firstValue("ETag").orElse(null);
            BundleContents bundle = extractBundle(response.body());

            if (bundle.wasm() == null) {
                log.warn("No policy.wasm found in bundle from {}", bundleUrl);
                return;
            }
            if (bundle.entrypoints().isEmpty()) {
                log.warn("Bundle from {} declares no entrypoints in its .manifest — every evaluate() will return null",
                        bundleUrl);
            }

            reloadBundle(bundle);
            lastETag = newETag;
            log.info("OPA Wasm bundle loaded (ETag: {}, wasm: {} bytes, data: {} bytes, entrypoints: {})",
                    newETag, bundle.wasm().length,
                    bundle.data() == null ? 0 : bundle.data().length,
                    bundle.entrypoints());

            // Flag any registered policies that the freshly-loaded bundle doesn't cover.
            for (String reg : registeredPolicies) {
                if (!bundle.entrypoints().contains(reg + "/allow")) {
                    log.warn("Registered policy {} is not in the loaded bundle — requests using it will be rejected",
                            reg);
                }
            }
        } catch (Exception e) {
            log.error("Failed to poll OPA bundle from {}: {}", bundleUrl, e.getMessage());
        }
    }

    /** What we pull out of an OPA Wasm bundle .tar.gz. */
    record BundleContents(byte[] wasm, byte[] data, Set<String> entrypoints) {}

    BundleContents extractBundle(InputStream bundleStream) throws IOException {
        byte[] wasm = null;
        byte[] data = null;
        byte[] manifest = null;
        try (GzipCompressorInputStream gzipIn = new GzipCompressorInputStream(bundleStream);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(gzipIn)) {

            TarArchiveEntry entry;
            while ((entry = tarIn.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.startsWith("./")) name = name.substring(2);
                if (name.startsWith("/")) name = name.substring(1);
                if (name.equals("policy.wasm")) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    tarIn.transferTo(baos);
                    wasm = baos.toByteArray();
                } else if (name.equals("data.json")) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    tarIn.transferTo(baos);
                    data = baos.toByteArray();
                } else if (name.equals(".manifest")) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    tarIn.transferTo(baos);
                    manifest = baos.toByteArray();
                }
            }
        }
        Set<String> entrypoints = parseEntrypoints(manifest);
        return new BundleContents(wasm, data, entrypoints);
    }

    private Set<String> parseEntrypoints(byte[] manifestBytes) {
        if (manifestBytes == null || manifestBytes.length == 0) {
            return Collections.emptySet();
        }
        try {
            JsonNode root = objectMapper.readTree(manifestBytes);
            JsonNode wasm = root.get("wasm");
            if (wasm == null || !wasm.isArray()) {
                return Collections.emptySet();
            }
            Set<String> out = new LinkedHashSet<>();
            for (JsonNode e : wasm) {
                JsonNode ep = e.get("entrypoint");
                if (ep != null && ep.isTextual()) {
                    out.add(ep.asText());
                }
            }
            return Collections.unmodifiableSet(out);
        } catch (Exception e) {
            log.warn("Failed to parse bundle .manifest: {}", e.getMessage());
            return Collections.emptySet();
        }
    }

    private void reloadBundle(BundleContents bundle) {
        String dataJson = (bundle.data() != null && bundle.data().length > 2)
                ? new String(bundle.data(), StandardCharsets.UTF_8)
                : null;

        ConcurrentLinkedQueue<com.styra.opa.wasm.OpaPolicy> newPool = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < poolSize; i++) {
            com.styra.opa.wasm.OpaPolicy policy = com.styra.opa.wasm.OpaPolicy.builder()
                    .withPolicy(new ByteArrayInputStream(bundle.wasm()))
                    .build();
            if (dataJson != null) {
                policy.data(dataJson);
            }
            newPool.offer(policy);
        }
        // Atomic swap — readers see the old or new pool consistently, never a mixed view.
        this.sharedPool = newPool;
        this.bundleEntrypoints = bundle.entrypoints();
        log.info("OPA Wasm shared pool created with {} instances (data: {}, entrypoints: {})",
                poolSize, dataJson != null ? "present" : "absent", bundle.entrypoints());
    }

    private String buildInput(String serviceId, String value, boolean isAccessToken) {
        try {
            Map<String, Object> input = new java.util.LinkedHashMap<>();
            if (isAccessToken) {
                try {
                    String[] parts = value.split("\\.");
                    if (parts.length >= 2) {
                        String payloadJson = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
                        Map<String, Object> payload = objectMapper.readValue(payloadJson, new com.fasterxml.jackson.core.type.TypeReference<>() {});
                        input.putAll(payload);
                        input.put("service", serviceId);
                    }
                } catch (Exception e) {
                    log.trace("Could not decode JWT for Wasm input: {}", e.getMessage());
                }
            } else {
                input.put("consumerKey", value);
                input.put("service", serviceId);
            }
            return objectMapper.writeValueAsString(input);
        } catch (Exception e) {
            return "{}";
        }
    }

    private OpaResult parseResult(String resultJson) {
        try {
            JsonNode node = objectMapper.readTree(resultJson);
            OpaResult result = new OpaResult();

            if (node.isArray() && !node.isEmpty()) {
                JsonNode first = node.get(0);
                if (first.has("result")) {
                    result.setResult(first.get("result").asBoolean());
                } else {
                    result.setResult(first.asBoolean());
                }
            } else if (node.isObject() && node.has("result")) {
                result.setResult(node.get("result").asBoolean());
            } else if (node.isBoolean()) {
                result.setResult(node.asBoolean());
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to parse OPA Wasm result: {}", e.getMessage());
            return null;
        }
    }
}