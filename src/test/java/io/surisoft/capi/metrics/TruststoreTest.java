package io.surisoft.capi.metrics;

import io.surisoft.capi.schema.AliasInfo;
import io.surisoft.capi.service.CapiTrustManager;
import org.junit.jupiter.api.Test;

import javax.security.auth.x500.X500Principal;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TruststoreTest {

    private static final Date NOW = new Date(1_800_000_000_000L);
    private static final long DAY = 24 * 60 * 60 * 1000L;

    private static X509Certificate certificate(Date notAfter) {
        X509Certificate certificate = mock(X509Certificate.class);
        when(certificate.getIssuerX500Principal()).thenReturn(new X500Principal("CN=issuer"));
        when(certificate.getSubjectX500Principal()).thenReturn(new X500Principal("CN=subject"));
        when(certificate.getNotBefore()).thenReturn(new Date(notAfter.getTime() - 365 * DAY));
        when(certificate.getNotAfter()).thenReturn(notAfter);
        return certificate;
    }

    private static Truststore truststoreWith(KeyStore keyStore) {
        CapiTrustManager trustManager = mock(CapiTrustManager.class);
        when(trustManager.getKeyStore()).thenReturn(keyStore);
        return new Truststore(true, trustManager);
    }

    private static KeyStore emptyKeyStore() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null, null);
        return keyStore;
    }

    @Test
    void getExpired_returnsOnlyCertificatesPastNotAfter() throws Exception {
        KeyStore keyStore = emptyKeyStore();
        keyStore.setCertificateEntry("expired", certificate(new Date(NOW.getTime() - DAY)));
        keyStore.setCertificateEntry("valid", certificate(new Date(NOW.getTime() + DAY)));

        List<AliasInfo> expired = truststoreWith(keyStore).getExpired(NOW);

        assertEquals(1, expired.size());
        assertEquals("expired", expired.get(0).getAlias());
    }

    /** notAfter is the last valid instant, so a certificate expiring exactly now is still valid. */
    @Test
    void getExpired_notAfterEqualToNow_isNotExpired() throws Exception {
        KeyStore keyStore = emptyKeyStore();
        keyStore.setCertificateEntry("boundary", certificate(NOW));

        assertTrue(truststoreWith(keyStore).getExpired(NOW).isEmpty());
    }

    @Test
    void getExpired_noExpiredCertificates_returnsEmptyList() throws Exception {
        KeyStore keyStore = emptyKeyStore();
        keyStore.setCertificateEntry("valid", certificate(new Date(NOW.getTime() + DAY)));

        assertTrue(truststoreWith(keyStore).getExpired(NOW).isEmpty());
    }

    @Test
    void getExpired_unreadableKeyStore_returnsNull() throws Exception {
        // Never loaded, so aliases() throws KeyStoreException, as getTruststore() already handles.
        KeyStore unloaded = KeyStore.getInstance("JKS");

        assertNull(truststoreWith(unloaded).getExpired(NOW));
    }

    @Test
    void getNotExpired_returnsOnlyCertificatesNotPastNotAfter() throws Exception {
        KeyStore keyStore = emptyKeyStore();
        keyStore.setCertificateEntry("expired", certificate(new Date(NOW.getTime() - DAY)));
        keyStore.setCertificateEntry("valid", certificate(new Date(NOW.getTime() + DAY)));
        keyStore.setCertificateEntry("boundary", certificate(NOW));

        List<String> aliases = truststoreWith(keyStore).getNotExpired(NOW).stream().map(AliasInfo::getAlias).sorted().toList();

        assertEquals(List.of("boundary", "valid"), aliases);
    }

    @Test
    void getNotExpired_unreadableKeyStore_returnsNull() throws Exception {
        assertNull(truststoreWith(KeyStore.getInstance("JKS")).getNotExpired(NOW));
    }

    @Test
    void getTruststore_stillListsEveryCertificate() throws Exception {
        KeyStore keyStore = emptyKeyStore();
        keyStore.setCertificateEntry("expired", certificate(new Date(NOW.getTime() - DAY)));
        keyStore.setCertificateEntry("valid", certificate(new Date(NOW.getTime() + DAY)));

        assertEquals(2, truststoreWith(keyStore).getTruststore().size());
    }

    /**
     * Real files, real CapiTrustManager. JKS persists when an alias was added; PKCS12 does not, and Java
     * then returns the load time — reported as is, every alias of a PKCS12 store would look "added today".
     */
    @Test
    void addedAt_isReportedForJks_andUnknownForPkcs12(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        String keytool = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        run(keytool, "-genkeypair", "-alias", "src", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=backend",
                "-ext", "SAN=dns:backend.example.test", "-validity", "2", "-storetype", "PKCS12",
                "-keystore", dir.resolve("src.p12").toString(), "-storepass", "changeit");
        run(keytool, "-exportcert", "-alias", "src", "-keystore", dir.resolve("src.p12").toString(),
                "-storepass", "changeit", "-file", dir.resolve("x.cer").toString());
        for (String type : new String[]{"JKS", "PKCS12"}) {
            run(keytool, "-importcert", "-noprompt", "-alias", "trusted", "-file", dir.resolve("x.cer").toString(),
                    "-storetype", type, "-keystore", dir.resolve("trust." + type).toString(), "-storepass", "changeit");
        }
        Thread.sleep(20);   // so a persisted creation date is strictly before the load

        AliasInfo jks = new Truststore(true, new CapiTrustManager(null, dir.resolve("trust.JKS").toString(), "changeit"))
                .getTruststore().getFirst();
        assertNotNull(jks.getAddedAt(), "JKS keeps the date the alias was added");
        assertEquals(java.util.List.of("backend.example.test"), jks.getDnsNames());

        AliasInfo p12 = new Truststore(true, new CapiTrustManager(null, dir.resolve("trust.PKCS12").toString(), "changeit"))
                .getTruststore().getFirst();
        assertNull(p12.getAddedAt(), "PKCS12 only knows the load time — must be reported as unknown, not as today");
    }

    private static void run(String... command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), String.join(" ", command) + "\n" + output);
    }
}
