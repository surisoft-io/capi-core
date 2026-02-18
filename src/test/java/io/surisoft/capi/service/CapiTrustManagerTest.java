package io.surisoft.capi.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class CapiTrustManagerTest {

    @TempDir
    Path tempDir;

    private CapiTrustManager capiTrustManager;
    private String trustStorePassword = "changeit";

    @BeforeEach
    void setUp() throws Exception {
        // Create an in-memory keystore and pass it as an InputStream
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, trustStorePassword.toCharArray());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        keyStore.store(baos, trustStorePassword.toCharArray());
        InputStream inputStream = new ByteArrayInputStream(baos.toByteArray());

        capiTrustManager = new CapiTrustManager(inputStream, tempDir.toString() + "/truststore.jks", trustStorePassword);
    }

    @Test
    void constructor_initializesTrustManager() {
        assertNotNull(capiTrustManager);
    }

    @Test
    void getKeyStore_returnsNonNull() {
        assertNotNull(capiTrustManager.getKeyStore());
    }

    @Test
    void getAcceptedIssuers_returnsArray() {
        X509Certificate[] issuers = capiTrustManager.getAcceptedIssuers();
        assertNotNull(issuers);
    }

    @Test
    void checkClientTrusted_withUntrustedCert_throwsException() {
        assertThrows(Exception.class, () -> {
            X509Certificate[] chain = createDummyCertChain();
            capiTrustManager.checkClientTrusted(chain, "RSA");
        });
    }

    @Test
    void checkServerTrusted_withUntrustedCert_throwsException() {
        assertThrows(Exception.class, () -> {
            X509Certificate[] chain = createDummyCertChain();
            capiTrustManager.checkServerTrusted(chain, "RSA");
        });
    }

    @Test
    void reloadTrustManager_withInputStream_reloadsSuccessfully() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, trustStorePassword.toCharArray());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        keyStore.store(baos, trustStorePassword.toCharArray());
        InputStream inputStream = new ByteArrayInputStream(baos.toByteArray());

        assertDoesNotThrow(() -> capiTrustManager.reloadTrustManager(inputStream, trustStorePassword));
        assertNotNull(capiTrustManager.getKeyStore());
    }

    @Test
    void reloadTrustManager_withNullInputStream_loadsFromFile() throws Exception {
        // Create a keystore file in the temp directory
        String trustStorePath = tempDir.toString() + "/truststore.jks";
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, trustStorePassword.toCharArray());
        try (FileOutputStream fos = new FileOutputStream(trustStorePath)) {
            keyStore.store(fos, trustStorePassword.toCharArray());
        }

        CapiTrustManager fileBasedManager = new CapiTrustManager(
                null,
                trustStorePath,
                trustStorePassword
        );

        assertNotNull(fileBasedManager.getKeyStore());
        assertNotNull(fileBasedManager.getAcceptedIssuers());
    }

    @Test
    void reloadTrustManager_withNullInputStream_andInvalidPath_throwsException() {
        assertThrows(Exception.class, () -> {
            new CapiTrustManager(null, "/nonexistent/path/truststore.jks", trustStorePassword);
        });
    }

    @Test
    void reloadTrustManager_multipleReloads_succeeds() throws Exception {
        for (int i = 0; i < 3; i++) {
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, trustStorePassword.toCharArray());

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            keyStore.store(baos, trustStorePassword.toCharArray());
            InputStream inputStream = new ByteArrayInputStream(baos.toByteArray());

            capiTrustManager.reloadTrustManager(inputStream, trustStorePassword);
        }
        assertNotNull(capiTrustManager.getKeyStore());
        assertNotNull(capiTrustManager.getAcceptedIssuers());
    }

    @Test
    void reloadTrustManager_withCertInKeystore_acceptsIssuer() throws Exception {
        // Create a keystore with a trusted cert
        X509Certificate cert = createDummyCertChain()[0];
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, trustStorePassword.toCharArray());
        keyStore.setCertificateEntry("test-cert", cert);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        keyStore.store(baos, trustStorePassword.toCharArray());
        InputStream inputStream = new ByteArrayInputStream(baos.toByteArray());

        capiTrustManager.reloadTrustManager(inputStream, trustStorePassword);

        X509Certificate[] issuers = capiTrustManager.getAcceptedIssuers();
        assertTrue(issuers.length > 0);
    }

    @Test
    void checkServerTrusted_withTrustedCert_doesNotThrow() throws Exception {
        X509Certificate cert = createDummyCertChain()[0];
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, trustStorePassword.toCharArray());
        keyStore.setCertificateEntry("test-cert", cert);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        keyStore.store(baos, trustStorePassword.toCharArray());
        InputStream inputStream = new ByteArrayInputStream(baos.toByteArray());

        capiTrustManager.reloadTrustManager(inputStream, trustStorePassword);

        // This cert is now trusted
        assertDoesNotThrow(() -> capiTrustManager.checkServerTrusted(new X509Certificate[]{cert}, "RSA"));
    }

    @Test
    void checkClientTrusted_withTrustedCert_doesNotThrow() throws Exception {
        X509Certificate cert = createDummyCertChain()[0];
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, trustStorePassword.toCharArray());
        keyStore.setCertificateEntry("test-cert", cert);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        keyStore.store(baos, trustStorePassword.toCharArray());
        InputStream inputStream = new ByteArrayInputStream(baos.toByteArray());

        capiTrustManager.reloadTrustManager(inputStream, trustStorePassword);

        assertDoesNotThrow(() -> capiTrustManager.checkClientTrusted(new X509Certificate[]{cert}, "RSA"));
    }

    /**
     * Creates a self-signed X509 certificate using keytool-generated DER.
     * We use a pre-generated self-signed certificate in DER/PEM format to avoid
     * depending on sun.security.x509 internal APIs.
     */
    private X509Certificate[] createDummyCertChain() throws Exception {
        // Generate a self-signed cert using KeyPairGenerator and CertificateFactory
        // We use a process-based approach via keytool
        String alias = "test";
        String keystorePath = tempDir.toString() + "/temp-keystore.jks";

        ProcessBuilder pb = new ProcessBuilder(
                "keytool", "-genkeypair",
                "-alias", alias,
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-dname", "CN=Test,OU=Test,O=Test,L=Test,ST=Test,C=US",
                "-keystore", keystorePath,
                "-storepass", trustStorePassword,
                "-keypass", trustStorePassword,
                "-validity", "1"
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();
        process.waitFor();

        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        try (InputStream fis = new java.io.FileInputStream(keystorePath)) {
            ks.load(fis, trustStorePassword.toCharArray());
        }

        X509Certificate cert = (X509Certificate) ks.getCertificate(alias);
        return new X509Certificate[]{cert};
    }
}
