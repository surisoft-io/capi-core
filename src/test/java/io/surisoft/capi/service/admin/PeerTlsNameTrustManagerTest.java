package io.surisoft.capi.service.admin;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.surisoft.capi.TestPorts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real TLS, real certificates (generated with keytool), servers on 127.0.0.1 — the exact situation
 * of a peer dialled by IP whose certificate is issued to a DNS name.
 */
class PeerTlsNameTrustManagerTest {

    private static final char[] PASS = "changeit".toCharArray();
    private static final String TOKEN = "Bearer secret-admin-token";

    @TempDir
    static Path dir;
    private static KeyStore admin;      // SAN dns:capi-admin.test         — trusted
    private static KeyStore wildcard;   // SAN dns:*.capi.test             — trusted
    private static KeyStore cnOnly;     // CN=capi-admin.test, SAN dns:other.test — trusted
    private static KeyStore rogue;      // SAN dns:capi-admin.test         — NOT trusted
    private static X509TrustManager trust;

    private final List<HttpsServer> servers = new ArrayList<>();
    private final List<String> authSeen = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void certificates() throws Exception {
        admin = generate("admin", "CN=capi-admin", "dns:capi-admin.test");
        wildcard = generate("wildcard", "CN=wildcard", "dns:*.capi.test");
        cnOnly = generate("cnonly", "CN=capi-admin.test", "dns:other.test");
        rogue = generate("rogue", "CN=capi-admin", "dns:capi-admin.test");

        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry("admin", admin.getCertificate("admin"));
        trustStore.setCertificateEntry("wildcard", wildcard.getCertificate("wildcard"));
        trustStore.setCertificateEntry("cnonly", cnOnly.getCertificate("cnonly"));
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        trust = (X509TrustManager) tmf.getTrustManagers()[0];
    }

    private static KeyStore generate(String alias, String dname, String san) throws Exception {
        Path file = dir.resolve(alias + ".p12");
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", alias, "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", dname, "-ext", "SAN=" + san, "-validity", "2",
                "-storetype", "PKCS12", "-keystore", file.toString(),
                "-storepass", "changeit", "-keypass", "changeit")
                .redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), "keytool failed: " + output);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(file.toFile())) {
            ks.load(in, PASS);
        }
        return ks;
    }

    /** HTTPS server on 127.0.0.1 presenting {@code keyStore}; records the Authorization it receives. */
    private int serve(KeyStore keyStore) throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, PASS);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        int port = TestPorts.next();
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ctx));
        server.createContext("/", ex -> {
            authSeen.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            byte[] body = "{\"ok\":true}".getBytes();
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        servers.add(server);
        return port;
    }

    @AfterEach
    void stop() {
        servers.forEach(s -> s.stop(0));
    }

    private static HttpResponse<String> get(HttpClient client, int port) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + port + "/info/capi"))
                .timeout(Duration.ofSeconds(5)).header("Authorization", TOKEN).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void baseline_standardVerificationRejectsAnIp_evenWithTheCertTrusted() throws Exception {
        int port = serve(admin);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new javax.net.ssl.TrustManager[]{trust}, null);
        HttpClient standard = HttpClient.newBuilder().sslContext(ctx).build();
        Exception e = assertThrows(Exception.class, () -> get(standard, port));
        assertTrue(String.valueOf(e.getMessage()).toLowerCase().contains("subject alternative name")
                        || e instanceof javax.net.ssl.SSLHandshakeException, e.toString());
        assertTrue(authSeen.isEmpty());
    }

    @Test
    void peerTlsName_acceptsTheIp_whenTheCertCoversTheName() throws Exception {
        int port = serve(admin);
        HttpResponse<String> r = get(AdminFanOut.peerClient(trust, "capi-admin.test", Duration.ofSeconds(5)), port);
        assertEquals(200, r.statusCode());
        assertEquals(List.of(TOKEN), authSeen);
    }

    @Test
    void wrongName_failsTheHandshake_andTheTokenIsNeverSent() throws Exception {
        int port = serve(admin);
        HttpClient client = AdminFanOut.peerClient(trust, "someone-else.test", Duration.ofSeconds(5));
        assertThrows(javax.net.ssl.SSLHandshakeException.class, () -> get(client, port));
        assertTrue(authSeen.isEmpty(), "the bearer token must not reach a peer that failed the name check");
    }

    @Test
    void untrustedCert_withTheRightName_isRejected_andTheTokenIsNeverSent() throws Exception {
        int port = serve(rogue);
        HttpClient client = AdminFanOut.peerClient(trust, "capi-admin.test", Duration.ofSeconds(5));
        assertThrows(javax.net.ssl.SSLHandshakeException.class, () -> get(client, port));
        assertTrue(authSeen.isEmpty(), "the chain is still validated against the trust store");
    }

    @Test
    void wildcard_coversExactlyOneLabel() throws Exception {
        int port = serve(wildcard);
        assertEquals(200, get(AdminFanOut.peerClient(trust, "node-1.capi.test", Duration.ofSeconds(5)), port).statusCode());
        X509Certificate cert = (X509Certificate) wildcard.getCertificate("wildcard");
        assertTrue(PeerTlsNameTrustManager.covers(cert, "NODE-1.capi.test"), "case-insensitive");
        assertFalse(PeerTlsNameTrustManager.covers(cert, "a.b.capi.test"));
        assertFalse(PeerTlsNameTrustManager.covers(cert, "capi.test"));
    }

    @Test
    void commonNameIsNeverConsulted() throws Exception {
        X509Certificate cert = (X509Certificate) cnOnly.getCertificate("cnonly");
        assertFalse(PeerTlsNameTrustManager.covers(cert, "capi-admin.test"), "CN=capi-admin.test must not count");
        assertTrue(PeerTlsNameTrustManager.covers(cert, "other.test"));
    }

    @Test
    void emptyName_isRefused() {
        assertThrows(IllegalArgumentException.class, () -> new PeerTlsNameTrustManager(trust, " "));
    }
}
