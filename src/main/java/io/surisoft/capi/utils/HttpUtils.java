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
import java.util.stream.Stream;

public class HttpUtils {
    private static final Logger log = LoggerFactory.getLogger(HttpUtils.class);

    private final String authorizationCookieName;
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
        if (httpServerExchange.getRequestHeaders().contains(Constants.AUTHORIZATION_REQUEST_PARAMETER)) {
            return httpServerExchange.getRequestHeaders().get(Constants.AUTHORIZATION_REQUEST_PARAMETER).getFirst();
        }
        // Try query parameter (e.g. ?access_token=...)
        java.util.Deque<String> queryToken = httpServerExchange.getQueryParameters().get(Constants.AUTHORIZATION_REQUEST_PARAMETER);
        if (queryToken != null && !queryToken.isEmpty()) {
            return queryToken.getFirst();
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

    public boolean isAuthorized(String accessToken, String contextPath, Service service, OpaWasmService opaWasmService) {
        try {
            if(service.getServiceMeta().getOpaRego() != null && opaWasmService != null && opaWasmService.isReady(service.getServiceMeta().getOpaRego()) ) {
                OpaResult opaResult = opaWasmService.evaluate(service.getServiceMeta().getOpaRego(), accessToken, true);
                if (opaResult == null || !opaResult.isAllowed()) {
                    return false;
                }
            } else {
                JWTClaimsSet jwtClaimsSet = authorizeRequest(accessToken);
                if(!isApiSubscribed(jwtClaimsSet, contextToRole(contextPath))) {
                    if(!isTokenInGroup(jwtClaimsSet, service.getServiceMeta().getSubscriptionGroup())) {
                        //Not subscribed
                        return false;
                    }
                }
            }
        } catch (AuthorizationException | ParseException | IOException e) {
            log.debug(e.getMessage());
            //General Exception
            return false;
        }
        return true;
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