package io.surisoft.capi.oidc;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import io.surisoft.capi.schema.WebsocketClient;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.HttpString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.text.ParseException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebsocketAuthorizationTest {

    @Mock
    private DefaultJWTProcessor<SecurityContext> jwtProcessor;
    @Mock
    private HttpServerExchange httpServerExchange;
    @Mock
    private WebsocketClient websocketClient;

    private WebsocketAuthorization authorization;

    @BeforeEach
    void setUp() {
        authorization = new WebsocketAuthorization(List.of(jwtProcessor));
    }

    // ---------------------------------------------------------------
    // getBearerTokenFromHeader
    // ---------------------------------------------------------------

    @Test
    void getBearerTokenFromHeader_extractsToken() {
        String result = authorization.getBearerTokenFromHeader("Bearer my-token-value");
        assertEquals("my-token-value", result);
    }

    // ---------------------------------------------------------------
    // isAuthorized - no subscription required
    // ---------------------------------------------------------------

    @Test
    void isAuthorized_noSubscriptionRequired_returnsTrue() {
        when(websocketClient.requiresSubscription()).thenReturn(false);

        boolean result = authorization.isAuthorized(websocketClient, httpServerExchange);
        assertTrue(result);
    }

    // ---------------------------------------------------------------
    // isAuthorized - subscription required, no authorization
    // ---------------------------------------------------------------

    @Test
    void isAuthorized_subscriptionRequired_noAuthHeader_noQueryParam_returnsFalse() {
        when(websocketClient.requiresSubscription()).thenReturn(true);

        HeaderMap headerMap = new HeaderMap();
        when(httpServerExchange.getRequestHeaders()).thenReturn(headerMap);
        when(httpServerExchange.getQueryParameters()).thenReturn(new LinkedHashMap<>());

        boolean result = authorization.isAuthorized(websocketClient, httpServerExchange);
        assertFalse(result);
    }

    // ---------------------------------------------------------------
    // isAuthorized - subscription required, with Authorization header
    // ---------------------------------------------------------------

    @Test
    void isAuthorized_withAuthHeader_validToken_matchingRole_returnsTrue() throws Exception {
        when(websocketClient.requiresSubscription()).thenReturn(true);
        when(websocketClient.getSubscriptionRole()).thenReturn("admin");

        HeaderMap headerMap = new HeaderMap();
        headerMap.put(new HttpString("Authorization"), "Bearer test-token");
        when(httpServerExchange.getRequestHeaders()).thenReturn(headerMap);

        Map<String, Object> realmAccess = new HashMap<>();
        realmAccess.put("roles", List.of("admin", "user"));

        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .claim("realm_access", realmAccess)
                .build();

        when(jwtProcessor.process(eq("test-token"), isNull())).thenReturn(claimsSet);

        boolean result = authorization.isAuthorized(websocketClient, httpServerExchange);
        assertTrue(result);
    }

    @Test
    void isAuthorized_withAuthHeader_validToken_noMatchingRole_butInCapiGroup_returnsTrue() throws Exception {
        when(websocketClient.requiresSubscription()).thenReturn(true);
        when(websocketClient.getSubscriptionRole()).thenReturn("nonexistent-role");

        HeaderMap headerMap = new HeaderMap();
        headerMap.put(new HttpString("Authorization"), "Bearer test-token");
        when(httpServerExchange.getRequestHeaders()).thenReturn(headerMap);

        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .claim("subscriptions", List.of("capi"))
                .build();

        when(jwtProcessor.process(eq("test-token"), isNull())).thenReturn(claimsSet);

        boolean result = authorization.isAuthorized(websocketClient, httpServerExchange);
        assertTrue(result);
    }

    @Test
    void isAuthorized_withAuthHeader_validToken_noRoleAndNoGroup_returnsFalse() throws Exception {
        when(websocketClient.requiresSubscription()).thenReturn(true);
        when(websocketClient.getSubscriptionRole()).thenReturn("admin");

        HeaderMap headerMap = new HeaderMap();
        headerMap.put(new HttpString("Authorization"), "Bearer test-token");
        when(httpServerExchange.getRequestHeaders()).thenReturn(headerMap);

        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .build();

        when(jwtProcessor.process(eq("test-token"), isNull())).thenReturn(claimsSet);

        boolean result = authorization.isAuthorized(websocketClient, httpServerExchange);
        assertFalse(result);
    }

    @Test
    void isAuthorized_withAuthHeader_invalidToken_returnsFalse() throws Exception {
        when(websocketClient.requiresSubscription()).thenReturn(true);
        when(websocketClient.getSubscriptionRole()).thenReturn("admin");

        HeaderMap headerMap = new HeaderMap();
        headerMap.put(new HttpString("Authorization"), "Bearer invalid-token");
        when(httpServerExchange.getRequestHeaders()).thenReturn(headerMap);

        when(jwtProcessor.process(eq("invalid-token"), isNull())).thenThrow(new BadJOSEException("bad"));

        boolean result = authorization.isAuthorized(websocketClient, httpServerExchange);
        assertFalse(result);
    }

    // ---------------------------------------------------------------
    // isAuthorized - subscription required, with query parameter
    // ---------------------------------------------------------------

    @Test
    void isAuthorized_withQueryParam_validToken_matchingRole_returnsTrue() throws Exception {
        when(websocketClient.requiresSubscription()).thenReturn(true);
        when(websocketClient.getSubscriptionRole()).thenReturn("viewer");

        HeaderMap headerMap = new HeaderMap();
        when(httpServerExchange.getRequestHeaders()).thenReturn(headerMap);

        Deque<String> tokenDeque = new ArrayDeque<>();
        tokenDeque.add("query-token");

        Map<String, Deque<String>> queryParams = new LinkedHashMap<>();
        queryParams.put("access_token", tokenDeque);
        when(httpServerExchange.getQueryParameters()).thenReturn(queryParams);

        Map<String, Object> realmAccess = new HashMap<>();
        realmAccess.put("roles", List.of("viewer"));

        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .claim("realm_access", realmAccess)
                .build();

        when(jwtProcessor.process(eq("query-token"), isNull())).thenReturn(claimsSet);

        boolean result = authorization.isAuthorized(websocketClient, httpServerExchange);
        assertTrue(result);
    }

    // ---------------------------------------------------------------
    // isAuthorized - token returns null claims
    // ---------------------------------------------------------------

    @Test
    void isAuthorized_nullClaims_returnsFalse() throws Exception {
        when(websocketClient.requiresSubscription()).thenReturn(true);
        when(websocketClient.getSubscriptionRole()).thenReturn("admin");

        HeaderMap headerMap = new HeaderMap();
        headerMap.put(new HttpString("Authorization"), "Bearer test-token");
        when(httpServerExchange.getRequestHeaders()).thenReturn(headerMap);

        when(jwtProcessor.process(eq("test-token"), isNull())).thenThrow(new ParseException("parse error", 0));

        boolean result = authorization.isAuthorized(websocketClient, httpServerExchange);
        assertFalse(result);
    }

    // ---------------------------------------------------------------
    // isAuthorized - realm_access with roles, role does not match
    // ---------------------------------------------------------------

    @Test
    void isAuthorized_withRealmAccess_roleDoesNotMatch_noGroup_returnsFalse() throws Exception {
        when(websocketClient.requiresSubscription()).thenReturn(true);
        when(websocketClient.getSubscriptionRole()).thenReturn("admin");

        HeaderMap headerMap = new HeaderMap();
        headerMap.put(new HttpString("Authorization"), "Bearer test-token");
        when(httpServerExchange.getRequestHeaders()).thenReturn(headerMap);

        Map<String, Object> realmAccess = new HashMap<>();
        realmAccess.put("roles", List.of("user", "viewer"));

        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .claim("realm_access", realmAccess)
                .build();

        when(jwtProcessor.process(eq("test-token"), isNull())).thenReturn(claimsSet);

        boolean result = authorization.isAuthorized(websocketClient, httpServerExchange);
        assertFalse(result);
    }

    // ---------------------------------------------------------------
    // isTokenInGroup - subscriptions contains matching group
    // ---------------------------------------------------------------

    @Test
    void isAuthorized_subscriptionGroupMatches_returnsTrue() throws Exception {
        when(websocketClient.requiresSubscription()).thenReturn(true);
        when(websocketClient.getSubscriptionRole()).thenReturn("unmatched-role");

        HeaderMap headerMap = new HeaderMap();
        headerMap.put(new HttpString("Authorization"), "Bearer test-token");
        when(httpServerExchange.getRequestHeaders()).thenReturn(headerMap);

        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .claim("subscriptions", List.of("/capi/"))
                .build();

        when(jwtProcessor.process(eq("test-token"), isNull())).thenReturn(claimsSet);

        boolean result = authorization.isAuthorized(websocketClient, httpServerExchange);
        assertTrue(result);
    }

    // ---------------------------------------------------------------
    // Multiple JWT processors - first fails, second succeeds
    // ---------------------------------------------------------------

    @Test
    void isAuthorized_multipleProcessors_firstFails_secondSucceeds() throws Exception {
        DefaultJWTProcessor<SecurityContext> secondProcessor = mock(DefaultJWTProcessor.class);
        WebsocketAuthorization multiAuth = new WebsocketAuthorization(List.of(jwtProcessor, secondProcessor));

        when(websocketClient.requiresSubscription()).thenReturn(true);
        when(websocketClient.getSubscriptionRole()).thenReturn("admin");

        HeaderMap headerMap = new HeaderMap();
        headerMap.put(new HttpString("Authorization"), "Bearer test-token");
        when(httpServerExchange.getRequestHeaders()).thenReturn(headerMap);

        when(jwtProcessor.process(eq("test-token"), isNull())).thenThrow(new BadJOSEException("bad"));

        Map<String, Object> realmAccess = new HashMap<>();
        realmAccess.put("roles", List.of("admin"));
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .claim("realm_access", realmAccess)
                .build();
        when(secondProcessor.process(eq("test-token"), isNull())).thenReturn(claimsSet);

        boolean result = multiAuth.isAuthorized(websocketClient, httpServerExchange);
        assertTrue(result);
    }
}
