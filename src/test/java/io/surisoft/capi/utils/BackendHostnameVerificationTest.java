package io.surisoft.capi.utils;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.surisoft.capi.TestPorts;
import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.configuration.CapiSslContextHolder;
import io.surisoft.capi.exception.HttpErrorHandler;
import io.surisoft.capi.schema.Mapping;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import io.surisoft.capi.schema.WebsocketClient;
import io.undertow.Undertow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Through CAPI's real backend proxy and real TLS: a backend whose certificate does not name the host
 * CAPI dialled is refused. Reported as CAPI-04; it was never exploitable, because Undertow's client
 * verifies hostnames by default. CAPI now sets it explicitly (see {@link BackendTls}) and this test
 * keeps it that way.
 */
class BackendHostnameVerificationTest {

    private static final char[] PASS = "changeit".toCharArray();

    @TempDir
    static Path dir;
    private static KeyStore wrongName;   // SAN dns:backend.test — CAPI dials 127.0.0.1
    private static KeyStore rightName;   // SAN ip:127.0.0.1
    private static SSLContext trustingBoth;

    private final List<Runnable> cleanup = new ArrayList<>();
    private final AtomicInteger backendHits = new AtomicInteger();

    @BeforeAll
    static void certificates() throws Exception {
        wrongName = generate("wrong", "CN=backend.test", "dns:backend.test");
        rightName = generate("right", "CN=backend", "ip:127.0.0.1");
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("wrong", wrongName.getCertificate("wrong"));
        trust.setCertificateEntry("right", rightName.getCertificate("right"));
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        trustingBoth = SSLContext.getInstance("TLS");
        trustingBoth.init(null, tmf.getTrustManagers(), null);
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

    @AfterEach
    void stop() {
        cleanup.forEach(Runnable::run);
    }

    /** An HTTPS backend on 127.0.0.1 presenting {@code keyStore}. */
    private int backend(KeyStore keyStore) throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, PASS);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        int port = TestPorts.next();
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ctx));
        server.createContext("/", ex -> {
            backendHits.incrementAndGet();
            byte[] body = "ok".getBytes();
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        cleanup.add(() -> server.stop(0));
        return port;
    }

    /** CAPI's REST/WebSocket backend proxy, fronted by a plain HTTP listener; returns the status of one GET. */
    private int callThroughCapi(int backendPort) throws Exception {
        WebsocketUtils utils = new WebsocketUtils(new CAPIConfiguration.Websocket(), null, new CapiSslContextHolder(trustingBoth));

        Mapping mapping = new Mapping();
        mapping.setHostname("127.0.0.1");
        mapping.setPort(backendPort);
        mapping.setRootContext("/");
        WebsocketClient client = new WebsocketClient();
        client.setMappingList(Set.of(mapping));
        client.setServiceId("backend:v1");
        ServiceMeta meta = new ServiceMeta();
        meta.setScheme("https");
        Service service = new Service();
        service.setServiceMeta(meta);

        int port = TestPorts.next();
        Undertow front = Undertow.builder().addHttpListener(port, "127.0.0.1")
                .setHandler(utils.createClientHttpHandler(client, service, new HttpErrorHandler(null), 5000)).build();
        front.start();
        cleanup.add(front::stop);

        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/x"))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    void certificateForAnotherHost_isRefused() throws Exception {
        int status = callThroughCapi(backend(wrongName));
        assertTrue(status >= 500, "a trusted certificate for another host must not be accepted, got " + status);
        assertEquals(0, backendHits.get(), "the request never reached the backend");
    }

    @Test
    void certificateForTheDialledHost_isAccepted() throws Exception {
        assertEquals(200, callThroughCapi(backend(rightName)));
    }
}
