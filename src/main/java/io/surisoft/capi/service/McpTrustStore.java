package io.surisoft.capi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.surisoft.capi.schema.ConsulKeyStoreEntry;
import io.surisoft.capi.utils.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the set of trusted public keys used to verify MCP tool manifests.
 *
 * <p>Mirrors {@link ConsulStore}'s polling pattern. Reads Consul KV under the
 * prefix {@link Constants#CONSUL_CAPI_MCP_TRUST_KEYS_PREFIX} via the recurse
 * API; each KV entry's value is a PEM-encoded SubjectPublicKeyInfo (RSA or EC).
 * The map of trusted keys is replaced atomically on each successful poll so
 * verification reads are lock-free.
 *
 * <p>Failures during poll preserve the previous map — a transient Consul outage
 * will not strip the gateway of all trust.
 */
public class McpTrustStore {

    private static final Logger log = LoggerFactory.getLogger(McpTrustStore.class);

    private final String consulKvHost;
    private final String consulKvToken;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<Map<String, PublicKey>> keys = new AtomicReference<>(Collections.emptyMap());

    public McpTrustStore(String consulKvHost, String consulKvToken, HttpClient httpClient) {
        this.consulKvHost = consulKvHost;
        this.consulKvToken = consulKvToken;
        this.httpClient = httpClient;
    }

    /** Trigger a one-shot refresh from Consul. Safe to call from a scheduler. */
    public void process() {
        if (consulKvHost == null) return;
        try {
            HttpResponse<String> response = httpClient.send(buildRequest(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                // No keys configured yet — leave the current set untouched (clearing on 404
                // would be hostile to operators bringing the trust store online incrementally).
                log.debug("No MCP trust keys found at Consul KV prefix {} (404)", Constants.CONSUL_CAPI_MCP_TRUST_KEYS_PREFIX);
                return;
            }
            if (response.statusCode() != 200) {
                log.warn("Failed to read MCP trust keys from Consul KV: status {}", response.statusCode());
                return;
            }
            ConsulKeyStoreEntry[] entries = objectMapper.readValue(response.body(), ConsulKeyStoreEntry[].class);
            Map<String, PublicKey> next = new HashMap<>();
            for (ConsulKeyStoreEntry entry : entries) {
                String keyId = stripPrefix(entry.getKey());
                if (keyId == null || keyId.isBlank()) continue;
                String pem = decodeBase64(entry.getValue());
                if (pem == null) continue;
                PublicKey pk = parsePublicKey(pem);
                if (pk != null) {
                    next.put(keyId, pk);
                } else {
                    log.warn("Skipped MCP trust key '{}' — could not parse PEM as RSA or EC public key", keyId);
                }
            }
            keys.set(Collections.unmodifiableMap(next));
            log.debug("MCP trust store refreshed with {} key(s)", next.size());
        } catch (Exception e) {
            log.warn("MCP trust store refresh failed: {}", e.getMessage());
        }
    }

    public PublicKey get(String keyId) {
        return keys.get().get(keyId);
    }

    public int size() {
        return keys.get().size();
    }

    private HttpRequest buildRequest() {
        URI uri = URI.create(consulKvHost
                + Constants.CONSUL_KV_STORE_API
                + Constants.CONSUL_CAPI_MCP_TRUST_KEYS_PREFIX
                + "?recurse=true");
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(10))
                .GET();
        if (consulKvToken != null && !consulKvToken.isEmpty()) {
            builder.header(Constants.AUTHORIZATION_HEADER,
                    Constants.BEARER + consulKvToken.replaceAll("(\r\n|\n)", ""));
        }
        return builder.build();
    }

    private static String stripPrefix(String key) {
        if (key == null) return null;
        if (key.startsWith(Constants.CONSUL_CAPI_MCP_TRUST_KEYS_PREFIX)) {
            return key.substring(Constants.CONSUL_CAPI_MCP_TRUST_KEYS_PREFIX.length());
        }
        return key;
    }

    private static String decodeBase64(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return new String(Base64.getDecoder().decode(value), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static PublicKey parsePublicKey(String pem) {
        String stripped = pem.replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
        byte[] der;
        try {
            der = Base64.getDecoder().decode(stripped);
        } catch (IllegalArgumentException e) {
            return null;
        }
        X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
        for (String algo : new String[]{"RSA", "EC", "Ed25519"}) {
            try {
                return KeyFactory.getInstance(algo).generatePublic(spec);
            } catch (Exception ignored) {
                // try next
            }
        }
        return null;
    }
}