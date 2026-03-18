package io.surisoft.capi.utils;

import io.surisoft.capi.configuration.CapiSslContextHolder;
import io.surisoft.capi.schema.GrpcClient;
import io.surisoft.capi.schema.Mapping;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import io.undertow.server.HttpHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.net.ssl.SSLContext;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GrpcUtilsTest {

    @Test
    void constructor_withNullSslContextHolder_noXnioSsl() {
        GrpcUtils grpcUtils = new GrpcUtils(null);
        assertNotNull(grpcUtils);
    }

    @Test
    void constructor_withSslContextHolder_createsXnioSsl() throws Exception {
        SSLContext sslContext = SSLContext.getDefault();
        CapiSslContextHolder holder = new CapiSslContextHolder(sslContext);
        GrpcUtils grpcUtils = new GrpcUtils(holder);
        assertNotNull(grpcUtils);
    }

    @Test
    void createClientHttpHandler_withoutSsl_returnsHandler() {
        GrpcUtils grpcUtils = new GrpcUtils(null);

        GrpcClient grpcClient = new GrpcClient();
        Mapping mapping = new Mapping();
        mapping.setHostname("localhost");
        mapping.setPort(9090);
        mapping.setRootContext("/");
        grpcClient.setMappingList(Set.of(mapping));

        Service service = new Service();
        ServiceMeta meta = new ServiceMeta();
        service.setServiceMeta(meta);

        HttpHandler handler = grpcUtils.createClientHttpHandler(grpcClient, service);
        assertNotNull(handler);
    }

    @Test
    void createClientHttpHandler_withSsl_returnsHandler() throws Exception {
        SSLContext sslContext = SSLContext.getDefault();
        CapiSslContextHolder holder = new CapiSslContextHolder(sslContext);
        GrpcUtils grpcUtils = new GrpcUtils(holder);

        GrpcClient grpcClient = new GrpcClient();
        Mapping mapping = new Mapping();
        mapping.setHostname("localhost");
        mapping.setPort(9090);
        mapping.setRootContext("/");
        grpcClient.setMappingList(Set.of(mapping));

        Service service = new Service();
        ServiceMeta meta = new ServiceMeta();
        meta.setScheme("https");
        service.setServiceMeta(meta);

        HttpHandler handler = grpcUtils.createClientHttpHandler(grpcClient, service);
        assertNotNull(handler);
    }

    @Test
    void createClientHttpHandler_withNullScheme_usesH2cPrior() {
        GrpcUtils grpcUtils = new GrpcUtils(null);

        GrpcClient grpcClient = new GrpcClient();
        Mapping mapping = new Mapping();
        mapping.setHostname("localhost");
        mapping.setPort(9090);
        mapping.setRootContext("/");
        grpcClient.setMappingList(Set.of(mapping));

        Service service = new Service();
        ServiceMeta meta = new ServiceMeta();
        // scheme is null -> should use h2c-prior
        service.setServiceMeta(meta);

        HttpHandler handler = grpcUtils.createClientHttpHandler(grpcClient, service);
        assertNotNull(handler);
    }

    @Test
    void createClientHttpHandler_withHttpScheme_usesH2cPrior() {
        GrpcUtils grpcUtils = new GrpcUtils(null);

        GrpcClient grpcClient = new GrpcClient();
        Mapping mapping = new Mapping();
        mapping.setHostname("localhost");
        mapping.setPort(9090);
        mapping.setRootContext("/");
        grpcClient.setMappingList(Set.of(mapping));

        Service service = new Service();
        ServiceMeta meta = new ServiceMeta();
        meta.setScheme("http");
        service.setServiceMeta(meta);

        HttpHandler handler = grpcUtils.createClientHttpHandler(grpcClient, service);
        assertNotNull(handler);
    }

    @Test
    void createClientHttpHandler_multipleMappings_addsAllHosts() {
        GrpcUtils grpcUtils = new GrpcUtils(null);

        Mapping m1 = new Mapping();
        m1.setHostname("host1");
        m1.setPort(9090);
        m1.setRootContext("/");

        Mapping m2 = new Mapping();
        m2.setHostname("host2");
        m2.setPort(9091);
        m2.setRootContext("/ctx");

        GrpcClient grpcClient = new GrpcClient();
        grpcClient.setMappingList(Set.of(m1, m2));

        Service service = new Service();
        ServiceMeta meta = new ServiceMeta();
        service.setServiceMeta(meta);

        HttpHandler handler = grpcUtils.createClientHttpHandler(grpcClient, service);
        assertNotNull(handler);
    }

    @Test
    void createGrpcClient_setsFieldsCorrectly() {
        GrpcUtils grpcUtils = new GrpcUtils(null);

        Mapping mapping = new Mapping();
        mapping.setHostname("backend");
        mapping.setPort(9090);
        mapping.setRootContext("/grpc-root");

        Service service = new Service();
        service.setContext("my-grpc-service");
        ServiceMeta meta = new ServiceMeta();
        meta.setSecured(true);
        service.setServiceMeta(meta);
        service.setMappingList(Set.of(mapping));

        GrpcClient client = grpcUtils.createGrpcClient(service);

        assertEquals("my-grpc-service", client.getServiceId());
        assertEquals("my-grpc-service", client.getPath());
        assertEquals("/grpc-root", client.getRootContext());
        assertTrue(client.isRequiresSubscription());
        assertNotNull(client.getHttpHandler());
        assertNotNull(client.getMappingList());
    }

    @Test
    void createGrpcClient_withNullRootContext_doesNotSetRootContext() {
        GrpcUtils grpcUtils = new GrpcUtils(null);

        Mapping mapping = new Mapping();
        mapping.setHostname("backend");
        mapping.setPort(9090);
        mapping.setRootContext(null);

        Service service = new Service();
        service.setContext("svc");
        ServiceMeta meta = new ServiceMeta();
        service.setServiceMeta(meta);
        service.setMappingList(Set.of(mapping));

        GrpcClient client = grpcUtils.createGrpcClient(service);
        assertNull(client.getRootContext());
    }

    @Test
    void createGrpcClient_withEmptyRootContext_doesNotSetRootContext() {
        GrpcUtils grpcUtils = new GrpcUtils(null);

        Mapping mapping = new Mapping();
        mapping.setHostname("backend");
        mapping.setPort(9090);
        mapping.setRootContext("");

        Service service = new Service();
        service.setContext("svc");
        ServiceMeta meta = new ServiceMeta();
        service.setServiceMeta(meta);
        service.setMappingList(Set.of(mapping));

        GrpcClient client = grpcUtils.createGrpcClient(service);
        assertNull(client.getRootContext());
    }

    @Test
    void createGrpcClient_withSlashRootContext_doesNotSetRootContext() {
        GrpcUtils grpcUtils = new GrpcUtils(null);

        Mapping mapping = new Mapping();
        mapping.setHostname("backend");
        mapping.setPort(9090);
        mapping.setRootContext("/");

        Service service = new Service();
        service.setContext("svc");
        ServiceMeta meta = new ServiceMeta();
        service.setServiceMeta(meta);
        service.setMappingList(Set.of(mapping));

        GrpcClient client = grpcUtils.createGrpcClient(service);
        assertNull(client.getRootContext());
    }

    @Test
    void createGrpcClient_withWildcardRootContext_doesNotSetRootContext() {
        GrpcUtils grpcUtils = new GrpcUtils(null);

        Mapping mapping = new Mapping();
        mapping.setHostname("backend");
        mapping.setPort(9090);
        mapping.setRootContext("*");

        Service service = new Service();
        service.setContext("svc");
        ServiceMeta meta = new ServiceMeta();
        service.setServiceMeta(meta);
        service.setMappingList(Set.of(mapping));

        GrpcClient client = grpcUtils.createGrpcClient(service);
        assertNull(client.getRootContext());
    }

    @Test
    void refreshXnioSsl_withSslContext_refreshes() throws Exception {
        SSLContext sslContext = SSLContext.getDefault();
        CapiSslContextHolder holder = new CapiSslContextHolder(sslContext);
        GrpcUtils grpcUtils = new GrpcUtils(holder);

        assertDoesNotThrow(() -> grpcUtils.refreshXnioSsl());
    }

    @Test
    void refreshXnioSsl_withNullHolder_doesNothing() {
        GrpcUtils grpcUtils = new GrpcUtils(null);
        assertDoesNotThrow(() -> grpcUtils.refreshXnioSsl());
    }
}
