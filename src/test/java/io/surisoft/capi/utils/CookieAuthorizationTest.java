package io.surisoft.capi.utils;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cookie-based authorization.
 *
 * <p>This was effectively dead: the only code path read a REQUEST HEADER named after the configured
 * cookie, whose VALUE then named the cookie to look up. No browser, BFF or Office client sends such a
 * header, so an ordinary {@code Cookie: session-auth=<jwt>} never authenticated — the feature looked
 * implemented but could not be reached by a real client.
 */
class CookieAuthorizationTest {

    private static HttpUtils utils() {
        return new HttpUtils("session-auth", new ArrayList<>());
    }

    private static HttpServerExchange exchangeWith(String cookieHeader) {
        HttpServerExchange exchange = new HttpServerExchange(null, new HeaderMap(), new HeaderMap(), 0);
        if (cookieHeader != null) {
            exchange.getRequestHeaders().put(Headers.COOKIE, cookieHeader);
        }
        return exchange;
    }

    @Test
    void plainCookie_authenticates() throws Exception {
        assertEquals("THE_JWT", utils().processAuthorizationAccessToken(
                exchangeWith("session-auth=THE_JWT")));
    }

    @Test
    void cookieAmongOthers_authenticates() throws Exception {
        assertEquals("THE_JWT", utils().processAuthorizationAccessToken(
                exchangeWith("a=1; session-auth=THE_JWT; b=2")));
    }

    @Test
    void wrongCookieName_doesNotAuthenticate() throws Exception {
        assertNull(utils().processAuthorizationAccessToken(exchangeWith("other=THE_JWT")));
    }

    @Test
    void noCookieAtAll_doesNotAuthenticate() throws Exception {
        assertNull(utils().processAuthorizationAccessToken(exchangeWith(null)));
    }

    /** The existing indirection is untouched: it still resolves whatever cookie the header names. */
    @Test
    void existingHeaderIndirection_stillWorks() throws Exception {
        HttpServerExchange exchange = exchangeWith("other-name=THE_JWT");
        exchange.getRequestHeaders().put(new HttpString("session-auth"), "other-name");
        assertEquals("THE_JWT", utils().processAuthorizationAccessToken(exchange));
    }

    /**
     * The case that makes the fix strictly additive. A deployment sending the indirection header must
     * keep resolving the cookie that header names, even when a cookie matching the configured name is
     * also present — otherwise the new direct lookup would silently change which value is trusted.
     */
    @Test
    void headerIndirectionWins_whenBothAreAvailable() throws Exception {
        HttpServerExchange exchange = exchangeWith("session-auth=WRONG; other-name=RIGHT");
        exchange.getRequestHeaders().put(new HttpString("session-auth"), "other-name");
        assertEquals("RIGHT", utils().processAuthorizationAccessToken(exchange),
                "existing behaviour must not change for clients already sending the header");
    }

    @Test
    void authorizationHeaderStillWins() throws Exception {
        HttpServerExchange exchange = exchangeWith("session-auth=COOKIE_JWT");
        exchange.getRequestHeaders().put(Headers.AUTHORIZATION, "Bearer HEADER_JWT");
        assertEquals("HEADER_JWT", utils().processAuthorizationAccessToken(exchange),
                "an explicit Authorization header must take precedence over an ambient cookie");
    }

    @Test
    void cookieAuthOffWhenNoCookieNameConfigured() throws Exception {
        HttpUtils noCookie = new HttpUtils(null, new ArrayList<>());
        assertNull(noCookie.processAuthorizationAccessToken(exchangeWith("session-auth=THE_JWT")));
    }
}
