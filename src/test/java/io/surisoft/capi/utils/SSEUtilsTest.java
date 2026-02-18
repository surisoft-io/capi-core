package io.surisoft.capi.utils;

import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import io.surisoft.capi.schema.*;
import io.undertow.server.HttpHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class SSEUtilsTest {

    @Mock
    private DefaultJWTProcessor<SecurityContext> jwtProcessor;

    private SSEUtils sseUtils;

    @BeforeEach
    void setUp() {
        sseUtils = new SSEUtils("/capi/*", Optional.of(List.of(jwtProcessor)));
    }

    // ---------------------------------------------------------------
    // normalizeBaseContextName
    // ---------------------------------------------------------------

    @Test
    void normalizeBaseContextName_removesSlashesAndStars() {
        assertEquals("capi", sseUtils.normalizeBaseContextName());
    }

    // ---------------------------------------------------------------
    // normalizeCapiContextPath
    // ---------------------------------------------------------------

    @Test
    void normalizeCapiContextPath_returnsNormalizedPath() {
        assertEquals("/capi", sseUtils.normalizeCapiContextPath());
    }

    // ---------------------------------------------------------------
    // normalizePathForForwarding
    // ---------------------------------------------------------------

    @Test
    void normalizePathForForwarding_removesContextAndApiId() {
        SSEClient client = new SSEClient();
        client.setApiId("my-api");

        String result = sseUtils.normalizePathForForwarding(client, "/capi/my-api/endpoint");
        assertNotNull(result);
        assertFalse(result.contains("my-api"));
    }

    // ---------------------------------------------------------------
    // getPathDefinition
    // ---------------------------------------------------------------

    @Test
    void getPathDefinition_validRequest_returnsPath() {
        String result = sseUtils.getPathDefinition("/capi/service-name/version");
        assertEquals("/capi/service-name/version/", result);
    }

    @Test
    void getPathDefinition_tooFewParts_returnsNull() {
        String result = sseUtils.getPathDefinition("/capi/only");
        assertNull(result);
    }

    @Test
    void getPathDefinition_wrongContext_returnsNull() {
        String result = sseUtils.getPathDefinition("/wrong/service-name/version");
        assertNull(result);
    }

    @Test
    void getPathDefinition_singlePart_returnsNull() {
        String result = sseUtils.getPathDefinition("/a");
        assertNull(result);
    }

    // ---------------------------------------------------------------
    // getWebclientId
    // ---------------------------------------------------------------

    @Test
    void getWebclientId_validRequest_returnsId() {
        String result = sseUtils.getWebclientId("/capi/service-name/version");
        assertEquals("/service-name/version", result);
    }

    @Test
    void getWebclientId_tooFewParts_returnsNull() {
        String result = sseUtils.getWebclientId("/capi/only");
        assertNull(result);
    }

    @Test
    void getWebclientId_wrongContext_returnsNull() {
        String result = sseUtils.getWebclientId("/wrong/service-name/version");
        assertNull(result);
    }

    // ---------------------------------------------------------------
    // createClientHttpHandler
    // ---------------------------------------------------------------

    @Test
    void createClientHttpHandler_withHttpScheme_addsHost() {
        Mapping mapping = new Mapping();
        mapping.setHostname("localhost");
        mapping.setPort(8080);
        mapping.setRootContext("/");

        SSEClient client = new SSEClient();
        client.setMappingList(Set.of(mapping));
        client.setApiId("my-api");

        Service service = new Service();
        ServiceMeta meta = new ServiceMeta();
        service.setServiceMeta(meta);

        HttpHandler handler = sseUtils.createClientHttpHandler(client, service);
        assertNotNull(handler);
    }

    @Test
    void createClientHttpHandler_withHttpInHostname_usesHostnameDirectly() {
        Mapping mapping = new Mapping();
        mapping.setHostname("http://myhost");
        mapping.setPort(8080);
        mapping.setRootContext("/");

        SSEClient client = new SSEClient();
        client.setMappingList(Set.of(mapping));
        client.setApiId("my-api");

        Service service = new Service();
        ServiceMeta meta = new ServiceMeta();
        service.setServiceMeta(meta);

        HttpHandler handler = sseUtils.createClientHttpHandler(client, service);
        assertNotNull(handler);
    }

    @Test
    void createClientHttpHandler_withHttpsInHostname_usesHostnameDirectly() {
        Mapping mapping = new Mapping();
        mapping.setHostname("https://securhost");
        mapping.setPort(443);
        mapping.setRootContext("/");

        SSEClient client = new SSEClient();
        client.setMappingList(Set.of(mapping));
        client.setApiId("my-api");

        Service service = new Service();
        ServiceMeta meta = new ServiceMeta();
        service.setServiceMeta(meta);

        HttpHandler handler = sseUtils.createClientHttpHandler(client, service);
        assertNotNull(handler);
    }

    @Test
    void createClientHttpHandler_withSchemeInMeta_usesScheme() {
        Mapping mapping = new Mapping();
        mapping.setHostname("localhost");
        mapping.setPort(8443);
        mapping.setRootContext("/");

        SSEClient client = new SSEClient();
        client.setMappingList(Set.of(mapping));
        client.setApiId("my-api");

        Service service = new Service();
        ServiceMeta meta = new ServiceMeta();
        meta.setScheme("https");
        service.setServiceMeta(meta);

        HttpHandler handler = sseUtils.createClientHttpHandler(client, service);
        assertNotNull(handler);
    }

    // ---------------------------------------------------------------
    // createSSEClient
    // ---------------------------------------------------------------

    @Test
    void createSSEClient_setsAllFields() {
        Mapping mapping = new Mapping();
        mapping.setHostname("localhost");
        mapping.setPort(8080);
        mapping.setRootContext("/api");

        Service service = new Service();
        service.setContext("/my-service/v1");
        service.setMappingList(Set.of(mapping));
        ServiceMeta meta = new ServiceMeta();
        meta.setSecured(true);
        meta.setSubscriptionGroup("test-group");
        service.setServiceMeta(meta);

        SSEClient client = sseUtils.createSSEClient(service);

        assertNotNull(client);
        assertEquals("/my-service/v1", client.getApiId());
        assertTrue(client.requiresSubscription());
        assertNotNull(client.getPath());
        assertNotNull(client.getHttpHandler());
        assertNotNull(client.getMappingList());
        assertEquals("test-group", client.getSubscriptionRole());
    }

    @Test
    void createSSEClient_withoutSubscriptionGroup_doesNotSetRole() {
        Mapping mapping = new Mapping();
        mapping.setHostname("localhost");
        mapping.setPort(8080);
        mapping.setRootContext("/api");

        Service service = new Service();
        service.setContext("/my-service/v1");
        service.setMappingList(Set.of(mapping));
        ServiceMeta meta = new ServiceMeta();
        meta.setSecured(false);
        service.setServiceMeta(meta);

        SSEClient client = sseUtils.createSSEClient(service);

        assertNotNull(client);
        assertNull(client.getSubscriptionRole());
        assertFalse(client.requiresSubscription());
    }
}
