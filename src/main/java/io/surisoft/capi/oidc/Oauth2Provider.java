package io.surisoft.capi.oidc;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import io.surisoft.capi.configuration.CapiSslContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.ParseException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class Oauth2Provider {

    private static final Logger log = LoggerFactory.getLogger(Oauth2Provider.class);
    private final List<String> providerKeys;
    private List<DefaultJWTProcessor<SecurityContext>> jwtProcessorList;
    /**
     * Per configured JWKS endpoint, the key ids actually loaded (empty = nothing loaded). Keys are
     * fetched once at startup and never refreshed, so two nodes with identical config can trust
     * different keys — a node restarted after a key rotation, or one that started while the IdP was
     * down. Published on /info/capi so that difference is visible.
     */
    private final java.util.Map<String, List<String>> loadedKeyIds = new java.util.LinkedHashMap<>();



    public Oauth2Provider(List<String> providerKeys) {
        this.providerKeys = providerKeys;
    }

    public List<DefaultJWTProcessor<SecurityContext>> getJwtProcessor(CapiSslContextHolder capiSslContextHolder) {
        log.info("Starting CAPI JWT Processor");
        jwtProcessorList = new ArrayList<>();
        for(String jwkEndpoint : providerKeys) {
            HttpClient.Builder httpClientBuilder = HttpClient.newBuilder();
            if(capiSslContextHolder != null) {
                httpClientBuilder.sslContext(capiSslContextHolder.getSslContext());
            }
            httpClientBuilder.connectTimeout(Duration.ofSeconds(10));
            try {
                HttpClient httpClient = httpClientBuilder.build();
                HttpRequest request = HttpRequest.newBuilder().uri(new URI(jwkEndpoint)).build();
                HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() == 200) {
                    InputStream responseInputStream = response.body();
                    DefaultJWTProcessor<SecurityContext> jwtProcessor = new DefaultJWTProcessor<>();
                    JWKSet jwkSet = JWKSet.load(responseInputStream);
                    loadedKeyIds.put(jwkEndpoint, keyIdsOf(jwkSet));
                    ImmutableJWKSet<SecurityContext> keySource = new ImmutableJWKSet<>(jwkSet);
                    JWSAlgorithm expectedJWSAlg = JWSAlgorithm.RS256;
                    JWSKeySelector<SecurityContext> keySelector = new JWSVerificationKeySelector<>(expectedJWSAlg, keySource);
                    jwtProcessor.setJWSKeySelector(keySelector);
                    jwtProcessorList.add(jwtProcessor);
                } else {
                    // Skipped, so tokens from this issuer cannot be verified and every secured or
                    // OPA-protected request carrying one is refused until CAPI restarts.
                    log.warn("JWKS endpoint {} returned HTTP {}; its keys are NOT loaded", jwkEndpoint, response.statusCode());
                    loadedKeyIds.put(jwkEndpoint, List.of());
                }
            } catch (URISyntaxException | InterruptedException | IOException | ParseException e) {
                throw new RuntimeException(e);
            }
        }
        return jwtProcessorList;
    }

    /** Configured JWKS endpoint -> key ids loaded from it (empty when the fetch failed). */
    public java.util.Map<String, List<String>> getLoadedKeyIds() {
        return java.util.Collections.unmodifiableMap(loadedKeyIds);
    }

    /** Sorted key ids; a key without a kid is identified by its RFC 7638 thumbprint. */
    static List<String> keyIdsOf(JWKSet jwkSet) {
        List<String> ids = new ArrayList<>();
        for (com.nimbusds.jose.jwk.JWK jwk : jwkSet.getKeys()) {
            if (jwk.getKeyID() != null) {
                ids.add(jwk.getKeyID());
            } else {
                try {
                    ids.add("thumbprint:" + jwk.computeThumbprint());
                } catch (com.nimbusds.jose.JOSEException e) {
                    ids.add("unidentified-key");
                }
            }
        }
        java.util.Collections.sort(ids);
        return ids;
    }

    public List<DefaultJWTProcessor<SecurityContext>> getJwtProcessorList() {
        return jwtProcessorList;
    }
}