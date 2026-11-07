package io.surisoft.capi.utils;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import java.util.Date;

/** Real RS256 keys, tokens and a verifying processor, for tests that must not mock verification away. */
public final class TestJwts {

    private TestJwts() {
    }

    public static RSAKey newKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-" + System.nanoTime()).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A processor that trusts {@code key} only — the shape CAPI builds from a JWKS endpoint. */
    public static DefaultJWTProcessor<SecurityContext> processorFor(RSAKey key) {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256,
                new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK()))));
        return processor;
    }

    public static String sign(RSAKey key, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A token for {@code subject} that expires {@code secondsFromNow} from now (negative = already expired). */
    public static String token(RSAKey key, String subject, long secondsFromNow) {
        return sign(key, new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer("http://keycloak/realms/test")
                .expirationTime(new Date(System.currentTimeMillis() + secondsFromNow * 1000))
                .build());
    }

    /** Like {@link #token}, carrying {@code groups} in the {@code subscriptions} claim. */
    public static String tokenWithSubscriptions(RSAKey key, String subject, long secondsFromNow, String... groups) {
        return sign(key, new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer("http://keycloak/realms/test")
                .expirationTime(new Date(System.currentTimeMillis() + secondsFromNow * 1000))
                .claim("subscriptions", java.util.List.of(groups))
                .build());
    }
}
