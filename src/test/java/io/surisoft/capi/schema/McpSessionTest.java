package io.surisoft.capi.schema;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpSessionTest {

    @Test
    void constructor_setsFieldsCorrectly() {
        McpSession session = new McpSession("sess-1", "client-abc", 60000);
        assertEquals("sess-1", session.getSessionId());
        assertEquals("client-abc", session.getClientIdentity());
        assertEquals(60000, session.getTtlMillis());
        assertTrue(session.getCreatedAt() > 0);
        assertTrue(session.getLastAccessedAt() > 0);
        assertEquals(session.getCreatedAt(), session.getLastAccessedAt());
    }

    @Test
    void isExpired_notExpired() {
        McpSession session = new McpSession("sess-1", "client", 60000);
        assertFalse(session.isExpired());
    }

    @Test
    void isExpired_expired() {
        McpSession session = new McpSession("sess-1", "client", 1);
        session.setLastAccessedAt(System.currentTimeMillis() - 100);
        assertTrue(session.isExpired());
    }

    @Test
    void touch_updatesLastAccessedAt() throws InterruptedException {
        McpSession session = new McpSession("sess-1", "client", 60000);
        long before = session.getLastAccessedAt();
        Thread.sleep(10);
        session.touch();
        assertTrue(session.getLastAccessedAt() >= before);
    }

    @Test
    void defaultConstructor_works() {
        McpSession session = new McpSession();
        assertNull(session.getSessionId());
        assertNull(session.getClientIdentity());
        assertEquals(0, session.getTtlMillis());
    }

    @Test
    void getterSetters() {
        McpSession session = new McpSession();
        session.setSessionId("s1");
        session.setClientIdentity("c1");
        session.setCreatedAt(100L);
        session.setLastAccessedAt(200L);
        session.setTtlMillis(300L);

        assertEquals("s1", session.getSessionId());
        assertEquals("c1", session.getClientIdentity());
        assertEquals(100L, session.getCreatedAt());
        assertEquals(200L, session.getLastAccessedAt());
        assertEquals(300L, session.getTtlMillis());
    }
}
