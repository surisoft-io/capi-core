package io.surisoft.capi.schema;

import io.undertow.server.HttpHandler;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class RestClientTest {

    @Test
    void gettersAndSetters() {
        RestClient client = new RestClient();

        client.setServiceId("/my-service/v1");
        assertEquals("/my-service/v1", client.getServiceId());

        Mapping m = new Mapping();
        m.setHostname("host");
        m.setPort(8080);
        m.setRootContext("/");
        client.setMappingList(Set.of(m));
        assertEquals(1, client.getMappingList().size());

        HttpHandler handler = mock(HttpHandler.class);
        client.setHttpHandler(handler);
        assertEquals(handler, client.getHttpHandler());

        client.setRootContext("/root");
        assertEquals("/root", client.getRootContext());

        client.setSecured(true);
        assertTrue(client.isSecured());

        client.setSubscriptionGroup("group1");
        assertEquals("group1", client.getSubscriptionGroup());

        client.setOpaRego("my/policy");
        assertEquals("my/policy", client.getOpaRego());

        client.setApiKeyEnabled(true);
        assertTrue(client.isApiKeyEnabled());

        client.setThrottle(true);
        assertTrue(client.isThrottle());

        client.setThrottleGlobal(true);
        assertTrue(client.isThrottleGlobal());

        client.setThrottleTotalCalls(100);
        assertEquals(100, client.getThrottleTotalCalls());

        client.setThrottleDuration(60000);
        assertEquals(60000, client.getThrottleDuration());

        client.setFailOverEnabled(true);
        assertTrue(client.isFailOverEnabled());

        client.setRoundRobinEnabled(true);
        assertTrue(client.isRoundRobinEnabled());

        client.setB3TraceId(true);
        assertTrue(client.isB3TraceId());

        client.setKeepGroup(true);
        assertTrue(client.isKeepGroup());

        client.setOpenAPI(null);
        assertNull(client.getOpenAPI());
    }
}
