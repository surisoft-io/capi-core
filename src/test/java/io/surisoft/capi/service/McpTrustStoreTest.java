package io.surisoft.capi.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.security.KeyFactory;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class McpTrustStoreTest {

    private HttpServer fakeConsul;
    private int port;
    private final AtomicReference<String> responseBody = new AtomicReference<>("[]");
    private final AtomicReference<Integer> statusCode = new AtomicReference<>(200);

    @BeforeEach
    void setUp() throws Exception {
        fakeConsul = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = fakeConsul.getAddress().getPort();
        fakeConsul.createContext("/v1/kv/", exchange -> {
            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        fakeConsul.start();
    }

    @AfterEach
    void tearDown() {
        if (fakeConsul != null) fakeConsul.stop(0);
    }

    @Test
    void process_loadsRsaKeyFromKv() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        String pem = toPem(kp.getPublic());
        responseBody.set(consulKvJson("capi-mcp-trust-keys/ops-2026", pem));

        McpTrustStore store = new McpTrustStore("http://127.0.0.1:" + port, null, HttpClient.newHttpClient());
        store.process();

        PublicKey loaded = store.get("ops-2026");
        assertNotNull(loaded);
        assertEquals("RSA", loaded.getAlgorithm());
        assertEquals(1, store.size());
    }

    @Test
    void process_loadsEcKey() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("EC").generateKeyPair();
        String pem = toPem(kp.getPublic());
        responseBody.set(consulKvJson("capi-mcp-trust-keys/ops-ec", pem));

        McpTrustStore store = new McpTrustStore("http://127.0.0.1:" + port, null, HttpClient.newHttpClient());
        store.process();

        PublicKey loaded = store.get("ops-ec");
        assertNotNull(loaded);
        assertEquals("EC", loaded.getAlgorithm());
    }

    @Test
    void process_skipsMalformedPem() {
        responseBody.set(consulKvJson("capi-mcp-trust-keys/bad-key", "not-a-valid-pem"));
        McpTrustStore store = new McpTrustStore("http://127.0.0.1:" + port, null, HttpClient.newHttpClient());
        store.process();
        assertNull(store.get("bad-key"));
        assertEquals(0, store.size());
    }

    @Test
    void process_404_keepsExistingKeys() throws Exception {
        // First poll loads one key
        KeyPair kp = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        responseBody.set(consulKvJson("capi-mcp-trust-keys/ops", toPem(kp.getPublic())));
        McpTrustStore store = new McpTrustStore("http://127.0.0.1:" + port, null, HttpClient.newHttpClient());
        store.process();
        assertEquals(1, store.size());

        // Second poll: Consul KV prefix becomes empty (404 — common when last key deleted).
        // We deliberately preserve the existing set so a transient outage doesn't strip trust.
        statusCode.set(404);
        store.process();
        assertEquals(1, store.size());
        assertNotNull(store.get("ops"));
    }

    @Test
    void process_atomicReplaceOnSuccess() throws Exception {
        KeyPair kp1 = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        responseBody.set(consulKvJson("capi-mcp-trust-keys/v1", toPem(kp1.getPublic())));
        McpTrustStore store = new McpTrustStore("http://127.0.0.1:" + port, null, HttpClient.newHttpClient());
        store.process();
        assertNotNull(store.get("v1"));

        // Rotate: KV now lists only v2 (operator removed v1, added v2)
        KeyPair kp2 = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        responseBody.set(consulKvJson("capi-mcp-trust-keys/v2", toPem(kp2.getPublic())));
        store.process();
        assertNull(store.get("v1"));
        assertNotNull(store.get("v2"));
    }

    @Test
    void parsePublicKey_handlesRsaAndEc() throws Exception {
        KeyPair rsa = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        KeyPair ec = KeyPairGenerator.getInstance("EC").generateKeyPair();
        assertNotNull(McpTrustStore.parsePublicKey(toPem(rsa.getPublic())));
        assertNotNull(McpTrustStore.parsePublicKey(toPem(ec.getPublic())));
        assertNull(McpTrustStore.parsePublicKey("not pem"));
    }

    private static String toPem(PublicKey key) throws Exception {
        // Round-trip via X509 SPKI to confirm what we put in is what gets parsed back.
        X509EncodedKeySpec spec = new X509EncodedKeySpec(key.getEncoded());
        // Just to validate it reparses; sanity check.
        KeyFactory.getInstance(key.getAlgorithm()).generatePublic(spec);
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(key.getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----\n";
    }

    /** Wrap a single PEM as the JSON shape Consul KV returns for a recurse query. */
    private static String consulKvJson(String key, String pem) {
        String value = Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.UTF_8));
        return "[{\"Key\":\"" + key + "\",\"Value\":\"" + value + "\",\"ModifyIndex\":1}]";
    }
}