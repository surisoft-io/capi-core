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
                    ImmutableJWKSet<SecurityContext> keySource = new ImmutableJWKSet<>(jwkSet);
                    JWSAlgorithm expectedJWSAlg = JWSAlgorithm.RS256;
                    JWSKeySelector<SecurityContext> keySelector = new JWSVerificationKeySelector<>(expectedJWSAlg, keySource);
                    jwtProcessor.setJWSKeySelector(keySelector);
                    jwtProcessorList.add(jwtProcessor);
                    return jwtProcessorList;
                }
            } catch (URISyntaxException | InterruptedException | IOException | ParseException e) {
                throw new RuntimeException(e);
            }
        }
        return null;
    }

    public List<DefaultJWTProcessor<SecurityContext>> getJwtProcessorList() {
        return jwtProcessorList;
    }
}