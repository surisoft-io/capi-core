package io.surisoft.capi.service;

import io.surisoft.capi.schema.McpTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

/**
 * Verifies an MCP tool manifest signature against a trusted public key.
 *
 * <p>The signature is over the deterministic bytes produced by
 * {@link McpManifest#canonicalize}. Algorithm is inferred from the public key
 * type (RSA → SHA256withRSA, EC → SHA256withECDSA, Ed25519 → Ed25519).
 */
public class McpManifestVerifier {

    private static final Logger log = LoggerFactory.getLogger(McpManifestVerifier.class);

    private final McpTrustStore trustStore;

    public McpManifestVerifier(McpTrustStore trustStore) {
        this.trustStore = trustStore;
    }

    public Result verify(McpTool tool, String signatureBase64, String keyId, String version) {
        if (tool == null) return Result.fail("missing_tool", null);
        if (signatureBase64 == null || signatureBase64.isBlank()) {
            return Result.fail("unsigned", null);
        }
        if (keyId == null || keyId.isBlank()) {
            return Result.fail("missing_keyid", null);
        }
        PublicKey pk = trustStore.get(keyId);
        if (pk == null) {
            return Result.fail("unknown_keyid", keyId);
        }
        byte[] sig;
        try {
            sig = Base64.getDecoder().decode(signatureBase64);
        } catch (IllegalArgumentException e) {
            return Result.fail("bad_signature_encoding", keyId);
        }
        byte[] manifest = McpManifest.canonicalize(
                tool.getServiceId(), tool.getName(),
                tool.getDescription(), tool.getInputSchema(), version);

        String algo = sigAlgorithmFor(pk);
        if (algo == null) {
            return Result.fail("unsupported_key_algorithm", keyId);
        }
        try {
            Signature s = Signature.getInstance(algo);
            s.initVerify(pk);
            s.update(manifest);
            boolean ok = s.verify(sig);
            return ok ? Result.ok(keyId) : Result.fail("signature_invalid", keyId);
        } catch (Exception e) {
            log.debug("Verification error for tool {} keyid {}: {}", tool.getName(), keyId, e.getMessage());
            return Result.fail("verification_error", keyId);
        }
    }

    static String sigAlgorithmFor(PublicKey pk) {
        String algo = pk.getAlgorithm();
        if ("RSA".equalsIgnoreCase(algo)) return "SHA256withRSA";
        if ("EC".equalsIgnoreCase(algo)) return "SHA256withECDSA";
        if ("Ed25519".equalsIgnoreCase(algo) || "EdDSA".equalsIgnoreCase(algo)) return "Ed25519";
        return null;
    }

    public static final class Result {
        private final boolean ok;
        private final String reason;
        private final String keyId;

        private Result(boolean ok, String reason, String keyId) {
            this.ok = ok;
            this.reason = reason;
            this.keyId = keyId;
        }

        public static Result ok(String keyId) { return new Result(true, "ok", keyId); }
        public static Result fail(String reason, String keyId) { return new Result(false, reason, keyId); }

        public boolean isOk() { return ok; }
        public String getReason() { return reason; }
        public String getKeyId() { return keyId; }
    }
}