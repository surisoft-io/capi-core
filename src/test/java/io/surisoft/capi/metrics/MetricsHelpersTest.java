package io.surisoft.capi.metrics;

import io.surisoft.capi.schema.AliasInfo;
import io.surisoft.capi.schema.WebsocketClient;
import io.surisoft.capi.service.CapiTrustManager;
import io.surisoft.capi.utils.Constants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.security.auth.x500.X500Principal;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MetricsHelpersTest {

    @Mock
    private CapiTrustManager capiTrustManager;

    // --- WSRoutes tests ---

    @Test
    void wsRoutes_withNonNullMap_returnsMap() {
        Map<String, WebsocketClient> clientMap = new HashMap<>();
        WebsocketClient client = new WebsocketClient();
        client.setPath("/ws/test");
        clientMap.put("ws-route-1", client);

        WSRoutes wsRoutes = new WSRoutes(clientMap);

        Map<String, WebsocketClient> result = wsRoutes.getAllWebsocketRoutesInfo();
        assertNotNull(result);
        assertEquals(1, result.size());
        assertTrue(result.containsKey("ws-route-1"));
        assertEquals("/ws/test", result.get("ws-route-1").getPath());
    }

    @Test
    void wsRoutes_withEmptyMap_returnsEmptyMap() {
        Map<String, WebsocketClient> clientMap = new HashMap<>();

        WSRoutes wsRoutes = new WSRoutes(clientMap);

        Map<String, WebsocketClient> result = wsRoutes.getAllWebsocketRoutesInfo();
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void wsRoutes_withNullMap_returnsNull() {
        WSRoutes wsRoutes = new WSRoutes(null);

        Map<String, WebsocketClient> result = wsRoutes.getAllWebsocketRoutesInfo();
        assertNull(result);
    }

    // --- Truststore tests ---

    @Test
    void truststore_whenDisabled_returnsListWithInfoMessage() {
        Truststore truststore = new Truststore(false, capiTrustManager);

        List<AliasInfo> result = truststore.getTruststore();

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(Constants.NO_CUSTOM_TRUST_STORE_PROVIDED, result.get(0).getAdditionalInfo());
        assertNull(result.get(0).getAlias());
    }

    @Test
    void truststore_whenEnabled_returnsAliasInfoList() throws Exception {
        KeyStore keyStore = mock(KeyStore.class);
        X509Certificate certificate = mock(X509Certificate.class);

        when(capiTrustManager.getKeyStore()).thenReturn(keyStore);

        Vector<String> aliasVector = new Vector<>();
        aliasVector.add("my-alias");
        when(keyStore.aliases()).thenReturn(aliasVector.elements());
        when(keyStore.getCertificate("my-alias")).thenReturn(certificate);
        when(certificate.getIssuerX500Principal()).thenReturn(new X500Principal("CN=Test Issuer"));
        when(certificate.getSubjectX500Principal()).thenReturn(new X500Principal("CN=Test Subject"));
        when(certificate.getNotBefore()).thenReturn(new Date());
        when(certificate.getNotAfter()).thenReturn(new Date());

        Truststore truststore = new Truststore(true, capiTrustManager);

        List<AliasInfo> result = truststore.getTruststore();

        assertNotNull(result);
        assertEquals(1, result.size());
        AliasInfo info = result.get(0);
        assertEquals("my-alias", info.getAlias());
        assertEquals("CN=Test Issuer", info.getIssuerDN());
        assertEquals("CN=Test Subject", info.getSubjectDN());
        assertNotNull(info.getNotBefore());
        assertNotNull(info.getNotAfter());
    }

    @Test
    void truststore_whenEnabled_multipleAliases_returnsAll() throws Exception {
        KeyStore keyStore = mock(KeyStore.class);
        X509Certificate cert1 = mock(X509Certificate.class);
        X509Certificate cert2 = mock(X509Certificate.class);

        when(capiTrustManager.getKeyStore()).thenReturn(keyStore);

        Vector<String> aliasVector = new Vector<>();
        aliasVector.add("alias-1");
        aliasVector.add("alias-2");
        when(keyStore.aliases()).thenReturn(aliasVector.elements());

        when(keyStore.getCertificate("alias-1")).thenReturn(cert1);
        when(cert1.getIssuerX500Principal()).thenReturn(new X500Principal("CN=Issuer1"));
        when(cert1.getSubjectX500Principal()).thenReturn(new X500Principal("CN=Subject1"));
        when(cert1.getNotBefore()).thenReturn(new Date());
        when(cert1.getNotAfter()).thenReturn(new Date());

        when(keyStore.getCertificate("alias-2")).thenReturn(cert2);
        when(cert2.getIssuerX500Principal()).thenReturn(new X500Principal("CN=Issuer2"));
        when(cert2.getSubjectX500Principal()).thenReturn(new X500Principal("CN=Subject2"));
        when(cert2.getNotBefore()).thenReturn(new Date());
        when(cert2.getNotAfter()).thenReturn(new Date());

        Truststore truststore = new Truststore(true, capiTrustManager);

        List<AliasInfo> result = truststore.getTruststore();

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("alias-1", result.get(0).getAlias());
        assertEquals("alias-2", result.get(1).getAlias());
    }

    @Test
    void truststore_whenEnabled_exceptionThrown_returnsNull() throws Exception {
        when(capiTrustManager.getKeyStore()).thenThrow(new RuntimeException("keystore error"));

        Truststore truststore = new Truststore(true, capiTrustManager);

        List<AliasInfo> result = truststore.getTruststore();

        assertNull(result);
    }
}
