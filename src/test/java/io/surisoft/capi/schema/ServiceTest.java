package io.surisoft.capi.schema;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ServiceTest {

    private Service service;

    @BeforeEach
    void setUp() {
        service = new Service();
    }

    @Test
    void id_getterSetter() {
        service.setId("svc-1");
        assertEquals("svc-1", service.getId());
    }

    @Test
    void name_getterSetter() {
        service.setName("my-service");
        assertEquals("my-service", service.getName());
    }

    @Test
    void context_getterSetter() {
        service.setContext("/api/v1");
        assertEquals("/api/v1", service.getContext());
    }

    @Test
    void mappingList_getterSetter() {
        Set<Mapping> mappings = new HashSet<>();
        service.setMappingList(mappings);
        assertSame(mappings, service.getMappingList());
    }

    @Test
    void mappingList_defaultIsEmpty() {
        assertNotNull(service.getMappingList());
        assertTrue(service.getMappingList().isEmpty());
    }

    @Test
    void serviceMeta_getterSetter() {
        ServiceMeta meta = new ServiceMeta();
        service.setServiceMeta(meta);
        assertSame(meta, service.getServiceMeta());
    }

    @Test
    void roundRobinEnabled_getterSetter() {
        service.setRoundRobinEnabled(true);
        assertTrue(service.isRoundRobinEnabled());
    }

    @Test
    void failOverEnabled_getterSetter() {
        service.setFailOverEnabled(true);
        assertTrue(service.isFailOverEnabled());
    }

    @Test
    void matchOnUriPrefix_getterSetter() {
        service.setMatchOnUriPrefix(true);
        assertTrue(service.isMatchOnUriPrefix());
    }

    @Test
    void forwardPrefix_getterSetter() {
        service.setForwardPrefix(true);
        assertTrue(service.isForwardPrefix());
    }

    @Test
    void registeredBy_getterSetter() {
        service.setRegisteredBy("consul");
        assertEquals("consul", service.getRegisteredBy());
    }

    @Test
    void openAPI_getterSetter() {
        service.setOpenAPI(null);
        assertNull(service.getOpenAPI());
    }

    @Test
    void serviceCapiInstances_getterSetter() {
        ServiceCapiInstances instances = new ServiceCapiInstances();
        service.setServiceCapiInstances(instances);
        assertSame(instances, service.getServiceCapiInstances());
    }
}
