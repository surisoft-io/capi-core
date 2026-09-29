package io.surisoft.capi.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import io.surisoft.capi.exception.AuthorizationException;
import io.surisoft.capi.oidc.Oauth2Constants;
import io.surisoft.capi.schema.CapiRestError;
import io.surisoft.capi.schema.OpaResult;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.service.OpaWasmService;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.HeaderValues;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import jakarta.annotation.Nullable;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpCookie;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Stream;

public class HttpUtils {
    private static final Logger log = LoggerFactory.getLogger(HttpUtils.class);

    private final String authorizationCookieName;
    /**
     * Whether {@code ?access_token=} is accepted ({@code capi.oauth2.allowQueryParameterToken}).
     *
     * <p>Defaults to true: unlike a config-driven change, the clients relying on this cannot be
     * enumerated from configuration, only from traffic. Watch
     * {@code capi_query_token_requests_total} before turning it off.
     */
    private boolean allowQueryParameterToken = true;
    private final List<DefaultJWTProcessor<SecurityContext>> jwtProcessorList;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HttpUtils(@Nullable String authorizationCookieName,
                     @Nullable List<DefaultJWTProcessor<SecurityContext>> jwtProcessorList) {
        this.authorizationCookieName = authorizationCookieName;
        this.jwtProcessorList = jwtProcessorList;
    }

    public String setHttpConnectTimeout(String endpoint, int timeout) {
        return prepareEndpoint(endpoint) + Constants.HTTP_CONNECT_TIMEOUT + timeout;
    }

    public String setHttpSocketTimeout(String endpoint, int timeout) {
        return prepareEndpoint(endpoint) + Constants.HTTP_SOCKET_TIMEOUT + timeout;
    }

    public String setIngressEndpoint(String endpoint, String hostName) {
        return prepareEndpoint(endpoint) + Constants.CUSTOM_HOST_HEADER + hostName;
    }

    public String getCapiContext(String context) {
        return context.substring(0, context.indexOf("/*"));
    }

    private String prepareEndpoint(String endpoint) {
        if(endpoint.contains("?")) {
            if (!endpoint.endsWith("&")) {
                endpoint = endpoint + "&";
            }
        } else {
            endpoint = endpoint + "?";
        }
        return endpoint;
    }

    public String getBearerTokenFromHeader(String authorizationHeader) throws AuthorizationException {
        try {
            return authorizationHeader.substring(7);
        } catch(Exception e) {
            throw new AuthorizationException("Invalid authorization provided");
        }
    }

    public JWTClaimsSet authorizeRequest(String accessToken) throws AuthorizationException {
        Exception exception = null;
        if(jwtProcessorList != null) {
            for(DefaultJWTProcessor<SecurityContext> jwtProcessor : jwtProcessorList) {
                try {
                    return jwtProcessor.process(accessToken, null);
                } catch(BadJOSEException | ParseException | JOSEException e)  {
                    exception = e;
                }
            }
            if(exception != null) {
                throw new AuthorizationException(exception.getMessage());
            }
        }
        return null;
    }

    public static String hashApiKey(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public String processAuthorizationAccessToken(HttpServerExchange httpServerExchange) throws AuthorizationException {
        String authorization = httpServerExchange.getRequestHeaders().contains(Constants.AUTHORIZATION_HEADER)
                ? httpServerExchange.getRequestHeaders().get(Constants.AUTHORIZATION_HEADER).getFirst()
                : null;
        if (authorization != null) {
            return getBearerTokenFromHeader(authorization);
        }
        // `access_token` as a REQUEST HEADER is deliberately no longer accepted. It was never a
        // documented token source, and a header named after a query parameter is a surprising place
        // for a credential to be honoured. Use Authorization, the query parameter, or the cookie.

        // Query parameter (e.g. ?access_token=...). A token in a URL lands in every access log and
        // Referer header along the way, so this source can be switched off — see
        // capi.oauth2.allowQueryParameterToken.
        if (allowQueryParameterToken) {
            java.util.Deque<String> queryToken = httpServerExchange.getQueryParameters().get(Constants.AUTHORIZATION_REQUEST_PARAMETER);
            if (queryToken != null && !queryToken.isEmpty()) {
                return queryToken.getFirst();
            }
        }
        // Try cookie-based authorization
        if (authorizationCookieName != null && httpServerExchange.getRequestHeaders().contains(Constants.COOKIE_HEADER)) {
            String authorizationName = httpServerExchange.getRequestHeaders().contains(authorizationCookieName)
                    ? httpServerExchange.getRequestHeaders().get(authorizationCookieName).getFirst()
                    : null;
            if (authorizationName != null) {
                List<HttpCookie> cookies = getCookiesFromExchange(httpServerExchange);
                return getAuthorizationCookieValue(cookies, authorizationName);
            }
        }
        return null;
    }

    public void setAllowQueryParameterToken(boolean allowQueryParameterToken) {
        this.allowQueryParameterToken = allowQueryParameterToken;
    }

    /** True when the request carried {@code ?access_token=}, whether or not it was used. */
    public static boolean hasQueryParameterToken(HttpServerExchange exchange) {
        String queryString = exchange.getQueryString();
        return queryString != null
                && !queryString.isEmpty()
                && queryString.contains(Constants.AUTHORIZATION_REQUEST_PARAMETER);
    }

    /**
     * Removes {@code access_token} from the query string before the request is forwarded.
     *
     * <p>Without this the token travels on to the backend and lands in its access log — CAPI has
     * already consumed it and put it in the {@code Authorization} header, so the copy in the URL
     * buys nothing. The WebSocket path has always done this; the REST path did not.
     *
     * <p>Operates on the raw query string rather than rebuilding from the parsed map, so every other
     * parameter survives byte-for-byte — including its original encoding, and including repeated
     * parameters, which a rebuild from {@code getQueryParameters()} would collapse to the first value.
     */
    public void stripQueryParameterToken(HttpServerExchange exchange) {
        String queryString = exchange.getQueryString();
        if (queryString == null || queryString.isEmpty()
                || !queryString.contains(Constants.AUTHORIZATION_REQUEST_PARAMETER)) {
            return;
        }
        StringBuilder kept = new StringBuilder(queryString.length());
        for (String pair : queryString.split("&")) {
            int equals = pair.indexOf('=');
            String name = equals >= 0 ? pair.substring(0, equals) : pair;
            if (Constants.AUTHORIZATION_REQUEST_PARAMETER.equals(name)) {
                continue;
            }
            if (!kept.isEmpty()) {
                kept.append('&');
            }
            kept.append(pair);
        }
        exchange.setQueryString(kept.toString());
        // Keep the parsed view consistent with the raw string for anything reading it later.
        exchange.getQueryParameters().remove(Constants.AUTHORIZATION_REQUEST_PARAMETER);
    }

    public void propagateAuthorization(HttpServerExchange exchange) {
        try {
            // Leave non-Bearer schemes (e.g. Basic) untouched — just forward as-is
            String existing = exchange.getRequestHeaders().contains(Constants.AUTHORIZATION_HEADER)
                    ? exchange.getRequestHeaders().get(Constants.AUTHORIZATION_HEADER).getFirst()
                    : null;
            if (existing != null && !existing.startsWith(Constants.BEARER)) {
                return;
            }
            String accessToken = processAuthorizationAccessToken(exchange);
            if (accessToken != null) {
                exchange.getRequestHeaders().put(
                        new HttpString(Constants.AUTHORIZATION_HEADER),
                        Constants.BEARER + accessToken.replaceAll("\\p{Cntrl}", ""));
            }
        } catch (Exception e) {
            log.trace("Could not extract access token for propagation: {}", e.getMessage());
        }
    }

    /**
     * Removes from the outgoing request the credentials CAPI itself terminated: the cookie holding
     * the access token (named by the {@code oauth2.cookieName} header) and CAPI's own session cookie.
     * Every other cookie the client sent is application data and is forwarded untouched.
     * <p>
     * Must run after {@link #propagateAuthorization(HttpServerExchange)}, which copies the token into
     * the Authorization header — the backend still gets the bearer token, just not a second, longer
     * lived copy of the same credential it has no use for. Mirrors what the API key path already does
     * when it drops the Authorization header once the key has been validated.
     */
    public void stripConsumedCredentialCookies(HttpServerExchange exchange) {
        HeaderMap headers = exchange.getRequestHeaders();

        String authCookieName = null;
        if (authorizationCookieName != null && !authorizationCookieName.isEmpty()) {
            HeaderValues namingHeader = headers.get(authorizationCookieName);
            if (namingHeader != null && !namingHeader.isEmpty()) {
                String declared = namingHeader.getFirst();
                if (declared != null && !declared.isBlank()) {
                    authCookieName = declared.trim();
                }
                // The header only tells CAPI which cookie carries the token. The backend has no use
                // for it and it advertises where the credential lives, so it stops here too.
                headers.remove(authorizationCookieName);
            }
        }

        HeaderValues cookieValues = headers.get(Constants.COOKIE_HEADER);
        if (cookieValues == null || cookieValues.isEmpty()) {
            return;
        }

        // Fast path. Most requests carry no cookie CAPI has to remove, and this runs on every
        // forwarded request, so leave the header untouched rather than parse and rebuild it. The
        // scan allocates nothing and cannot miss a real match — a cookie named X implies the
        // substring X. A false positive (the name appearing inside some other cookie's value) only
        // costs a trip through the rebuild below, which compares names exactly.
        if (!mayContainRemovableCookie(cookieValues, authCookieName)) {
            return;
        }

        // HTTP/2 clients may split the cookie field across several header lines, so rebuild every one.
        List<String> rebuilt = new ArrayList<>(cookieValues.size());
        for (int i = 0; i < cookieValues.size(); i++) {
            String kept = removeCookiesFromHeaderValue(cookieValues.get(i), authCookieName);
            if (kept != null && !kept.isEmpty()) {
                rebuilt.add(kept);
            }
        }

        headers.remove(Constants.COOKIE_HEADER);
        for (int i = 0; i < rebuilt.size(); i++) {
            headers.add(Headers.COOKIE, rebuilt.get(i));
        }
    }

    /** Cheap allocation-free pre-check for {@link #stripConsumedCredentialCookies}. */
    private static boolean mayContainRemovableCookie(HeaderValues cookieValues, String authCookieName) {
        for (int i = 0; i < cookieValues.size(); i++) {
            String value = cookieValues.get(i);
            if (value == null) {
                continue;
            }
            if (value.contains(Constants.CAPI_SESSION_COOKIE_NAME)
                    || (authCookieName != null && value.contains(authCookieName))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rebuilds a single Cookie header value without the credential cookies, preserving the order and
     * the original spelling of the pairs that survive. Returns an empty string when nothing is left,
     * so the caller can drop the header rather than forward an empty one.
     */
    private String removeCookiesFromHeaderValue(String cookieHeaderValue, String authCookieName) {
        if (cookieHeaderValue == null || cookieHeaderValue.isEmpty()) {
            return null;
        }
        StringJoiner kept = new StringJoiner("; ");
        for (String cookieString : cookieHeaderValue.split(";")) {
            String trimmed = cookieString.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            String name = separator > -1 ? trimmed.substring(0, separator).trim() : trimmed;
            if (!isCredentialCookie(stripOffSurroundingQuote(name), authCookieName)) {
                kept.add(trimmed);
            }
        }
        return kept.toString();
    }

    /** Cookie names are case sensitive (RFC 6265), so match them exactly. */
    private static boolean isCredentialCookie(String cookieName, String authCookieName) {
        return Constants.CAPI_SESSION_COOKIE_NAME.equals(cookieName)
                || (authCookieName != null && authCookieName.equals(cookieName));
    }

    private List<HttpCookie> getCookiesFromExchange(HttpServerExchange exchange) {
        List<HttpCookie> httpCookieList = new ArrayList<>();
        String cookieHeader = exchange.getRequestHeaders().contains(Constants.COOKIE_HEADER)
                ? exchange.getRequestHeaders().get(Constants.COOKIE_HEADER).getFirst()
                : null;
        if (cookieHeader != null) {
            String[] cookieArray = cookieHeader.split(";");
            for (String cookieString : cookieArray) {
                String[] cookieKeyValue = cookieString.trim().split("=", 2);
                if (cookieKeyValue.length == 2) {
                    HttpCookie httpCookie = new HttpCookie(stripOffSurroundingQuote(cookieKeyValue[0].trim()), stripOffSurroundingQuote(cookieKeyValue[1].trim()));
                    httpCookieList.add(httpCookie);
                }
            }
        }
        return httpCookieList;
    }

    public String processAuthorizationAccessToken(HttpServletRequest httpServletRequest) throws AuthorizationException {
        String authorization = httpServletRequest.getHeader(Constants.AUTHORIZATION_HEADER);
        if(authorization == null) {
            if(httpServletRequest.getHeader(Constants.AUTHORIZATION_REQUEST_PARAMETER) != null) {
                return httpServletRequest.getHeader(Constants.AUTHORIZATION_REQUEST_PARAMETER);
            }
            List<HttpCookie> cookies = getCookiesFromRequest(httpServletRequest);
            String authorizationName = httpServletRequest.getHeader(authorizationCookieName);
            if(authorizationName != null) {
                return getAuthorizationCookieValue(cookies, authorizationName);
            }
        } else {
            return getBearerTokenFromHeader(authorization);
        }
        return null;
    }

    public String normalizeHttpEndpoint(String httpEndpoint) {
        if(httpEndpoint.contains("http://")) {
            return httpEndpoint.replace("http://", "");
        }
        if(httpEndpoint.contains("https://")) {
            return httpEndpoint.replace("https://", "");
        }
        return httpEndpoint;
    }

    public boolean isEndpointSecure(String httpEndpoint) {
        return httpEndpoint.contains("https://");
    }

    public List<HttpCookie> getCookiesFromRequest(HttpServletRequest httpServletRequest) {
        List<HttpCookie> httpCookieList = new ArrayList<>();
        if(httpServletRequest.getHeader(Constants.COOKIE_HEADER) != null) {
            String[] cookieArray = httpServletRequest.getHeader(Constants.COOKIE_HEADER).split(";");
            for (String cookieString : cookieArray) {
                String[] cookieKeyValue = cookieString.split("=");
                HttpCookie httpCookie = new HttpCookie(stripOffSurroundingQuote(cookieKeyValue[0]), stripOffSurroundingQuote(cookieKeyValue[1]));
                httpCookieList.add(httpCookie);
            }
        }
        return httpCookieList;
    }

    public String getAuthorizationCookieValue(List<HttpCookie> httpCookieList, String authorizationCookie) {
        for(HttpCookie httpCookie : httpCookieList) {
            if(httpCookie.getName().equals(authorizationCookie)) {
                return httpCookie.getValue();
            }
        }
        return null;
    }

    private static String stripOffSurroundingQuote(String value) {

        if (value != null && value.length() > 2 &&
                value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            return value.substring(1, value.length() - 1);
        }
        if (value != null && value.length() > 2 &&
                value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\'') {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** Outcome of an authorization decision, so "cannot decide" is distinguishable from "no". */
    public enum AuthorizationOutcome {
        ALLOWED,
        DENIED,
        /** The policy engine has no bundle loaded. Transient — the caller must not fall back. */
        POLICY_UNAVAILABLE,
        /** The service names a policy the loaded bundle does not declare. A config error. */
        POLICY_UNKNOWN
    }

    /**
     * Authorizes a request for a service, using its OPA policy when it declares one and the
     * subscription claim otherwise.
     *
     * <p>Returns an outcome rather than a boolean because "denied" and "could not decide" need
     * different answers. This previously returned {@code boolean} and, when a service declared an
     * OPA policy the engine could not evaluate, silently fell through to the subscription check —
     * so an OPA-governed service was authorized by group membership alone whenever the bundle
     * server was down, the pool was still loading, or {@code opa-rego} named a policy the bundle
     * did not declare. No error, no log: a request that should have been held succeeded against a
     * weaker check than the operator configured.
     *
     * <p>The mapping now matches {@code RestGateway}'s main OPA gate exactly — 503 when the engine
     * is unavailable, 403 for an unknown policy — so the two paths cannot disagree about what
     * "OPA not ready" means.
     *
     * <p>The JWT signature is verified before the token is handed to OPA. The policy decides on
     * decoded claims, so an unverified token would let a caller assert whatever claims it liked.
     * The main gate has always done this; this path did not.
     */
    public AuthorizationOutcome authorize(String accessToken, String contextPath, Service service, OpaWasmService opaWasmService) {
        try {
            String opaRego = service.getServiceMeta() != null ? service.getServiceMeta().getOpaRego() : null;

            if (opaRego == null) {
                // No policy declared, so the subscription claim is the correct and only gate.
                return isSubscribed(accessToken, contextPath, service) ? AuthorizationOutcome.ALLOWED : AuthorizationOutcome.DENIED;
            }
            if (opaWasmService == null || !opaWasmService.isReady()) {
                return AuthorizationOutcome.POLICY_UNAVAILABLE;
            }
            if (!opaWasmService.hasPolicy(opaRego)) {
                return AuthorizationOutcome.POLICY_UNKNOWN;
            }
            // Verify the signature before OPA sees the claims.
            authorizeRequest(accessToken);

            OpaResult opaResult = opaWasmService.evaluate(service.getId(), opaRego, accessToken, true);
            return (opaResult != null && opaResult.isAllowed())
                    ? AuthorizationOutcome.ALLOWED
                    : AuthorizationOutcome.DENIED;
        } catch (AuthorizationException | ParseException | IOException e) {
            log.debug(e.getMessage());
            return AuthorizationOutcome.DENIED;
        }
    }

    private boolean isSubscribed(String accessToken, String contextPath, Service service)
            throws AuthorizationException, ParseException, JsonProcessingException {
        JWTClaimsSet jwtClaimsSet = authorizeRequest(accessToken);
        if (jwtClaimsSet == null) {
            // authorizeRequest returns null rather than throwing when no JWT processor is
            // configured — oauth2 disabled, or no key set resolved at startup. There are no claims
            // to check a subscription against, so this is a refusal. It used to dereference the
            // null and throw NPE, which the caller turned into a 401 by accident rather than design.
            log.debug("No JWT processor available to verify the token; refusing the subscription check");
            return false;
        }
        if (isApiSubscribed(jwtClaimsSet, contextToRole(contextPath))) {
            return true;
        }
        return isTokenInGroup(jwtClaimsSet, service.getServiceMeta() != null
                ? service.getServiceMeta().getSubscriptionGroup() : null);
    }

    public boolean isAuthorized(String accessToken, String subscriptionGroup) {
        try {
            JWTClaimsSet jwtClaimsSet = authorizeRequest(accessToken);
            if(subscriptionGroup == null) {
                return false;
            }
            if(!isTokenInGroup(jwtClaimsSet, subscriptionGroup)) {
                return false;
            }
        } catch (AuthorizationException e) {
            return false;
        }
        return true;
    }

    private boolean isApiSubscribed(JWTClaimsSet jwtClaimsSet, String role) throws ParseException, JsonProcessingException {
        Map<String, Object> claimSetMap = jwtClaimsSet.getJSONObjectClaim(Oauth2Constants.REALMS_CLAIM);
        if(claimSetMap != null && claimSetMap.containsKey(Oauth2Constants.ROLES_CLAIM)) {
            List<String> roleList = (List<String>) claimSetMap.get(Oauth2Constants.ROLES_CLAIM);
            for(String claimRole : roleList) {
                if(claimRole.equals(role)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isTokenInGroup(JWTClaimsSet jwtClaimsSet, String groups) {
        if(groups != null) {
            try {
                List<String> groupList = Stream.of(groups.split(",", -1)).toList();
                List<String> subscriptionGroupList;
                subscriptionGroupList = jwtClaimsSet.getStringListClaim(Oauth2Constants.SUBSCRIPTIONS_CLAIM);
                for(String subscriptionGroup : subscriptionGroupList) {
                    for(String apiGroup : groupList) {
                        if(normalizeGroup(apiGroup).equals(normalizeGroup(subscriptionGroup))) {
                            return true;
                        }
                    }
                }
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    public String contextToRole(String context) {
        if(context.startsWith("/")) {
            context = context.substring(1);
        }
        return context.replace("/", ":");
    }

    private String normalizeGroup(String group) {
        return group.trim().replaceAll("/", "");
    }

    public String proxyErrorMapper(CapiRestError capiRestError) {
        try {
            return objectMapper.writeValueAsString(capiRestError);
        } catch (JsonProcessingException e) {
            return "no-message";
        }
    }

    public static String validateHeaderValue(String value) {
        if (!value.matches("^[a-zA-Z0-9 \\-]+$")) {
            throw new IllegalArgumentException("Invalid header value");
        }
        return value;
    }

    public boolean isSafeUri(URI uri, boolean allowLocalTraffic) {
        String scheme = uri.getScheme();

        if(!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            return false;
        }

        String host = uri.getHost();
        if(host == null)  {
            return false;
        }
        try {
            java.net.InetAddress addr = java.net.InetAddress.getByName(host);
            if(!allowLocalTraffic) {
                if(addr.isAnyLocalAddress() || addr.isLoopbackAddress()) return false;
                if(addr instanceof java.net.Inet4Address ipv4) {
                    byte[] b = ipv4.getAddress();
                    int a = b[0] & 0xFF, c = b[1] & 0xFF;
                    if(a == 10) return false;
                    if(a == 172 && c >= 16 && c <= 31) return false;
                    if(a == 192 && c == 168) return false;
                    return a != 169 || c != 254;
                }
                return true;
            }
            return true;
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }
}