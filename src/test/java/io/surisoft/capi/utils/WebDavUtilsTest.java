package io.surisoft.capi.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code webdav-host} normalisation.
 *
 * <p>A port in this value is a natural mistake — it looks like an address — but the gateway compares
 * it against the incoming {@code Host} header with the port removed, so a configured {@code host:port}
 * matched nothing and every request to that service returned 404 with no hint why. Both sides are
 * normalised identically now.
 */
class WebDavUtilsTest {

    @Test
    void portIsStrippedSoItCanMatchTheHostHeader() {
        assertEquals("10.226.42.106", WebDavUtils.normaliseWebdavHost("10.226.42.106:500", "files:v1"));
        assertEquals("dav-files.corp", WebDavUtils.normaliseWebdavHost("DAV-Files.corp:8385", "files:v1"));
    }

    @Test
    void valueIsTrimmedAndLowercased() {
        assertEquals("dav-files.corp", WebDavUtils.normaliseWebdavHost("  DAV-Files.CORP  ", "files:v1"));
    }

    @Test
    void ipv6KeepsItsBrackets() {
        // Splitting an IPv6 literal on the first colon would mangle it into "[2001".
        assertEquals("[2001:db8::1]", WebDavUtils.normaliseWebdavHost("[2001:db8::1]:8385", "files:v1"));
        assertEquals("[2001:db8::1]", WebDavUtils.normaliseWebdavHost("[2001:db8::1]", "files:v1"));
    }

    @Test
    void absentMeansPathRouted() {
        assertNull(WebDavUtils.normaliseWebdavHost(null, "files:v1"));
        assertNull(WebDavUtils.normaliseWebdavHost("   ", "files:v1"));
        assertNull(WebDavUtils.normaliseWebdavHost("", "files:v1"));
    }

    @Test
    void aPlainHostnameIsUnchanged() {
        assertEquals("dav-files.corp", WebDavUtils.normaliseWebdavHost("dav-files.corp", "files:v1"));
        assertEquals("10.226.42.106", WebDavUtils.normaliseWebdavHost("10.226.42.106", "files:v1"));
    }
}
