package io.surisoft.capi.service;

import io.surisoft.capi.schema.McpTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpManifestVerifierTest {

    private McpTrustStore trustStore;
    private McpManifestVerifier verifier;
    private KeyPair rsaKeyPair;
    private KeyPair ecKeyPair;

    @BeforeEach
    void setUp() throws Exception {
        trustStore = Mockito.mock(McpTrustStore.class);
        verifier = new McpManifestVerifier(trustStore);

        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        rsaKeyPair = rsa.generateKeyPair();

        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(256);
        ecKeyPair = ec.generateKeyPair();
    }

    @Test
    void verify_rsaSignature_passes() throws Exception {
        McpTool tool = makeTool("svc-1", "doThing", "Does the thing", "{\"type\":\"object\"}");
        Mockito.when(trustStore.get("ops-2026")).thenReturn(rsaKeyPair.getPublic());
        String sig = signAs("SHA256withRSA", rsaKeyPair, tool, "1");

        McpManifestVerifier.Result r = verifier.verify(tool, sig, "ops-2026", "1");
        assertTrue(r.isOk(), () -> "expected ok, got: " + r.getReason());
        assertEquals("ops-2026", r.getKeyId());
    }

    @Test
    void verify_ecSignature_passes() throws Exception {
        McpTool tool = makeTool("svc-1", "doThing", "d", "{\"type\":\"object\"}");
        Mockito.when(trustStore.get("ops-ec")).thenReturn(ecKeyPair.getPublic());
        String sig = signAs("SHA256withECDSA", ecKeyPair, tool, "1");

        McpManifestVerifier.Result r = verifier.verify(tool, sig, "ops-ec", "1");
        assertTrue(r.isOk(), () -> "expected ok, got: " + r.getReason());
    }

    @Test
    void verify_tamperedDescription_fails() throws Exception {
        McpTool tool = makeTool("svc-1", "doThing", "Original description", "{\"type\":\"object\"}");
        Mockito.when(trustStore.get("ops-2026")).thenReturn(rsaKeyPair.getPublic());
        String sigOverOriginal = signAs("SHA256withRSA", rsaKeyPair, tool, "1");

        // Operator's signature was over the original description; now tamper.
        tool.setDescription("Tampered description");
        McpManifestVerifier.Result r = verifier.verify(tool, sigOverOriginal, "ops-2026", "1");
        assertFalse(r.isOk());
        assertEquals("signature_invalid", r.getReason());
    }

    @Test
    void verify_unknownKeyId_fails() {
        McpTool tool = makeTool("svc", "t", "d", "{\"type\":\"object\"}");
        Mockito.when(trustStore.get("nope")).thenReturn(null);
        McpManifestVerifier.Result r = verifier.verify(tool, "AAAA", "nope", "1");
        assertFalse(r.isOk());
        assertEquals("unknown_keyid", r.getReason());
        assertEquals("nope", r.getKeyId());
    }

    @Test
    void verify_missingSignature_fails() {
        McpTool tool = makeTool("svc", "t", "d", "{\"type\":\"object\"}");
        McpManifestVerifier.Result r = verifier.verify(tool, "", "ops-2026", "1");
        assertFalse(r.isOk());
        assertEquals("unsigned", r.getReason());
    }

    @Test
    void verify_missingKeyId_fails() {
        McpTool tool = makeTool("svc", "t", "d", "{\"type\":\"object\"}");
        McpManifestVerifier.Result r = verifier.verify(tool, "AAAA", null, "1");
        assertFalse(r.isOk());
        assertEquals("missing_keyid", r.getReason());
    }

    @Test
    void verify_badBase64Signature_fails() throws Exception {
        McpTool tool = makeTool("svc", "t", "d", "{\"type\":\"object\"}");
        Mockito.when(trustStore.get("ops-2026")).thenReturn(rsaKeyPair.getPublic());
        // "%%%" is not valid base64.
        McpManifestVerifier.Result r = verifier.verify(tool, "%%%", "ops-2026", "1");
        assertFalse(r.isOk());
        assertEquals("bad_signature_encoding", r.getReason());
    }

    @Test
    void verify_versionDrift_fails() throws Exception {
        // Operator signs version=1; CAPI sees version=2 from Consul (someone bumped it
        // without re-signing). Verification should fail.
        McpTool tool = makeTool("svc", "t", "d", "{\"type\":\"object\"}");
        Mockito.when(trustStore.get("k")).thenReturn(rsaKeyPair.getPublic());
        String sigForV1 = signAs("SHA256withRSA", rsaKeyPair, tool, "1");

        McpManifestVerifier.Result r = verifier.verify(tool, sigForV1, "k", "2");
        assertFalse(r.isOk());
        assertEquals("signature_invalid", r.getReason());
    }

    @Test
    void sigAlgorithmFor_rsa() {
        assertEquals("SHA256withRSA", McpManifestVerifier.sigAlgorithmFor(rsaKeyPair.getPublic()));
    }

    @Test
    void sigAlgorithmFor_ec() {
        assertEquals("SHA256withECDSA", McpManifestVerifier.sigAlgorithmFor(ecKeyPair.getPublic()));
    }

    private McpTool makeTool(String svc, String name, String desc, String schema) {
        McpTool t = new McpTool();
        t.setServiceId(svc);
        t.setName(name);
        t.setDescription(desc);
        t.setInputSchema(schema);
        return t;
    }

    private String signAs(String algo, KeyPair kp, McpTool tool, String version) throws Exception {
        byte[] manifest = McpManifest.canonicalize(
                tool.getServiceId(), tool.getName(), tool.getDescription(), tool.getInputSchema(), version);
        Signature s = Signature.getInstance(algo);
        s.initSign(kp.getPrivate());
        s.update(manifest);
        return Base64.getEncoder().encodeToString(s.sign());
    }
}