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
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * OPA policy evaluation using Wasm bundles (Chicory-based, in-process).
 * <p>
 * Polls a bundle server for updates using ETag caching.
 * Each OPA rego path (e.g. "capi/allow_all") maps to a separate bundle
 * fetched from {bundleBaseUrl}/{policy_name}.tar.gz.
 * <p>
 * Falls back to HTTP OPA when Wasm evaluation is unavailable.
 */
public class OpaWasmService {

    private static final Logger log = LoggerFactory.getLogger(OpaWasmService.class);
    private final String bundleBaseUrl;
    private final int poolSize;
    private final HttpClient httpClient;
    private final OpaService fallbackOpaService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // rego path → policy pool (e.g. "capi/allow_all" → pool of OpaPolicy instances)
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<com.styra.opa.wasm.OpaPolicy>> policyPools = new ConcurrentHashMap<>();
    // rego path → last ETag for bundle polling
    private final ConcurrentHashMap<String, String> lastETags = new ConcurrentHashMap<>();
    // rego paths to poll — registered when services are discovered
    private final Set<String> registeredPolicies = ConcurrentHashMap.newKeySet();

    public OpaWasmService(String bundleBaseUrl, int poolSize, HttpClient httpClient, OpaService fallbackOpaService) {
        this.bundleBaseUrl = bundleBaseUrl.endsWith("/") ? bundleBaseUrl : bundleBaseUrl + "/";
        this.poolSize = poolSize;
        this.httpClient = httpClient;
        this.fallbackOpaService = fallbackOpaService;
    }

    /**
     * Register a rego policy path for bundle polling.
     * Called when ConsulNodeDiscovery finds a service with an OPA rego.
     * e.g. registerPolicy("capi/allow_all")
     */
    public void registerPolicy(String opaRego) {
        if (opaRego != null && !opaRego.isEmpty()) {
            boolean added = registeredPolicies.add(opaRego);
            if (added) {
                log.info("OPA Wasm policy registered: {}", opaRego);
            }
        }
    }

    /**
     * Poll the bundle server for all registered policies. Call from a scheduled timer.
     */
    public void pollBundles() {
        log.trace("OPA Wasm poll: {} registered policies: {}", registeredPolicies.size(), registeredPolicies);
        for (String rego : registeredPolicies) {
            try {
                pollBundle(rego);
            } catch (Exception e) {
                log.error("Failed to poll OPA bundle for {}: {}", rego, e.getMessage());
            }
        }
    }

    /**
     * Evaluate a policy using Wasm. Falls back to HTTP OPA if Wasm is unavailable.
     */
    public OpaResult evaluate(String opaRego, String value, boolean isAccessToken) {
        ConcurrentLinkedQueue<com.styra.opa.wasm.OpaPolicy> pool = policyPools.get(opaRego);
        com.styra.opa.wasm.OpaPolicy policy = pool != null ? pool.poll() : null;
        if (policy == null) {
            log.trace("OPA Wasm not available for {}, falling back to HTTP", opaRego);
            return fallbackOpaService != null ? fallbackOpaService.callOpa(opaRego, value, isAccessToken) : null;
        }

        try {
            String input = buildInput(value, isAccessToken);
            log.trace("OPA Wasm evaluate for {}: input={}", opaRego, input);
            String resultJson = policy.evaluate(input);
            log.trace("OPA Wasm result for {}: {}", opaRego, resultJson);
            pool.offer(policy);
            return parseResult(resultJson);
        } catch (Exception e) {
            log.error("OPA Wasm evaluation failed for {}: {}, discarding policy instance", opaRego, e.getMessage());
            return fallbackOpaService != null ? fallbackOpaService.callOpa(opaRego, value, isAccessToken) : null;
        }
    }

    public boolean isReady(String opaRego) {
        ConcurrentLinkedQueue<com.styra.opa.wasm.OpaPolicy> pool = policyPools.get(opaRego);
        return pool != null && !pool.isEmpty();
    }

    public boolean isReady() {
        return !policyPools.isEmpty();
    }

    public int getLoadedPolicyCount() {
        return policyPools.size();
    }

    private void pollBundle(String opaRego) {
        // Convert rego path to bundle filename: "capi/allow_all" → "allow_all.tar.gz"
        String policyName = opaRego.contains("/")
                ? opaRego.substring(opaRego.lastIndexOf('/') + 1)
                : opaRego;
        String bundleUrl = bundleBaseUrl + policyName + ".tar.gz";

        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(bundleUrl))
                    .timeout(Duration.ofSeconds(30))
                    .GET();

            String etag = lastETags.get(opaRego);
            if (etag != null) {
                requestBuilder.header("If-None-Match", etag);
            }

            HttpResponse<InputStream> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() == 304) {
                log.trace("OPA Wasm bundle unchanged for {} (ETag: {})", opaRego, etag);
                return;
            }

            if (response.statusCode() != 200) {
                log.warn("OPA bundle server returned {} for {}", response.statusCode(), bundleUrl);
                return;
            }

            String newETag = response.headers().firstValue("ETag").orElse(null);
            byte[] wasmBytes = extractPolicyWasm(response.body());

            if (wasmBytes == null) {
                log.warn("No policy.wasm found in bundle from {}", bundleUrl);
                return;
            }

            reloadPolicy(opaRego, wasmBytes);
            if (newETag != null) {
                lastETags.put(opaRego, newETag);
            }
            log.info("OPA Wasm policy loaded for {} (ETag: {}, size: {} bytes)", opaRego, newETag, wasmBytes.length);

        } catch (Exception e) {
            log.error("Failed to poll OPA bundle for {} from {}: {}", opaRego, bundleUrl, e.getMessage());
        }
    }

    byte[] extractPolicyWasm(InputStream bundleStream) throws IOException {
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
                    return baos.toByteArray();
                }
            }
        }
        return null;
    }

    private void reloadPolicy(String opaRego, byte[] wasmBytes) {
        ConcurrentLinkedQueue<com.styra.opa.wasm.OpaPolicy> newPool = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < poolSize; i++) {
            com.styra.opa.wasm.OpaPolicy policy = com.styra.opa.wasm.OpaPolicy.builder()
                    .withPolicy(new ByteArrayInputStream(wasmBytes))
                    .build();
            newPool.offer(policy);
        }
        policyPools.put(opaRego, newPool);
        log.info("OPA Wasm policy pool created for {} with {} instances", opaRego, poolSize);
    }

    private String buildInput(String value, boolean isAccessToken) {
        try {
            Map<String, Object> input = new java.util.LinkedHashMap<>();
            if (isAccessToken) {
                input.put("token", value);
                // Decode JWT payload for Wasm (io.jwt.decode not available as built-in)
                try {
                    String[] parts = value.split("\\.");
                    if (parts.length >= 2) {
                        String payloadJson = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
                        Map<String, Object> payload = objectMapper.readValue(payloadJson, new com.fasterxml.jackson.core.type.TypeReference<>() {});
                        input.putAll(payload);
                    }
                } catch (Exception e) {
                    log.trace("Could not decode JWT for Wasm input: {}", e.getMessage());
                }
            } else {
                input.put("consumerKey", value);
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