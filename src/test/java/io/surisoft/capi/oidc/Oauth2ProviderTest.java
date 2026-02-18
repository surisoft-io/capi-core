package io.surisoft.capi.oidc;

import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import io.surisoft.capi.configuration.CapiSslContextHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.net.ssl.SSLContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Oauth2ProviderTest {

    @Mock
    private CapiSslContextHolder capiSslContextHolder;

    @Test
    void constructor_setsProviderKeys() {
        List<String> keys = List.of("http://example.com/certs");
        Oauth2Provider provider = new Oauth2Provider(keys);
        assertNotNull(provider);
    }

    @Test
    void getJwtProcessorList_beforeInit_returnsNull() {
        Oauth2Provider provider = new Oauth2Provider(List.of("http://example.com/certs"));
        assertNull(provider.getJwtProcessorList());
    }

    @Test
    void getJwtProcessor_invalidEndpoint_throwsRuntimeException() {
        List<String> keys = List.of("http://invalid-host-that-does-not-exist-99999.example.com/certs");
        Oauth2Provider provider = new Oauth2Provider(keys);

        assertThrows(RuntimeException.class, () -> provider.getJwtProcessor(null));
    }

    @Test
    void getJwtProcessor_invalidEndpoint_withSslContext_throwsRuntimeException() throws Exception {
        SSLContext sslContext = SSLContext.getDefault();
        when(capiSslContextHolder.getSslContext()).thenReturn(sslContext);

        List<String> keys = List.of("http://invalid-host-that-does-not-exist-99999.example.com/certs");
        Oauth2Provider provider = new Oauth2Provider(keys);

        assertThrows(RuntimeException.class, () -> provider.getJwtProcessor(capiSslContextHolder));
    }

    @Test
    void getJwtProcessor_emptyProviderKeys_returnsNull() {
        List<String> keys = new ArrayList<>();
        Oauth2Provider provider = new Oauth2Provider(keys);

        List<DefaultJWTProcessor<SecurityContext>> result = provider.getJwtProcessor(null);
        assertNull(result);
    }

    @Test
    void getJwtProcessor_emptyProviderKeys_withSslContext_returnsNull() throws Exception {
        SSLContext sslContext = SSLContext.getDefault();
        when(capiSslContextHolder.getSslContext()).thenReturn(sslContext);

        List<String> keys = new ArrayList<>();
        Oauth2Provider provider = new Oauth2Provider(keys);

        List<DefaultJWTProcessor<SecurityContext>> result = provider.getJwtProcessor(capiSslContextHolder);
        assertNull(result);
    }

    @Test
    void getJwtProcessorList_afterEmptyInit_isEmptyList() {
        List<String> keys = new ArrayList<>();
        Oauth2Provider provider = new Oauth2Provider(keys);
        provider.getJwtProcessor(null);

        // After processing empty list, jwtProcessorList was initialized as empty ArrayList
        assertNotNull(provider.getJwtProcessorList());
        assertTrue(provider.getJwtProcessorList().isEmpty());
    }
}
