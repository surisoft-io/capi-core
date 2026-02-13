package io.surisoft.capi.utils;

import io.surisoft.capi.schema.Mapping;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ServiceUtilsTest {

    private ServiceUtils serviceUtils;

    @BeforeEach
    void setUp() {
        HttpUtils httpUtils = new HttpUtils(null, null);
        serviceUtils = new ServiceUtils(httpUtils, null, null, null, null, null, "full");
    }

    @Test
    void getServiceId_returnsNameColonGroup() {
        Service service = createService("my-service", "dev");
        assertEquals("my-service:dev", serviceUtils.getServiceId(service));
    }

    @Test
    void getServiceIdFromPath_validPath() {
        assertEquals("sample-service:dev", serviceUtils.getServiceIdFromPath("/sample-service/dev/some/path"));
    }

    @Test
    void getServiceIdFromPath_exactTwoSegments() {
        assertEquals("sample-service:dev", serviceUtils.getServiceIdFromPath("/sample-service/dev"));
    }

    @Test
    void getServiceIdFromPath_withoutLeadingSlash() {
        assertEquals("sample-service:dev", serviceUtils.getServiceIdFromPath("sample-service/dev"));
    }

    @Test
    void getServiceIdFromPath_singleSegment_returnsNull() {
        assertNull(serviceUtils.getServiceIdFromPath("/single"));
    }

    @Test
    void getServiceIdFromPath_null_returnsNull() {
        assertNull(serviceUtils.getServiceIdFromPath(null));
    }

    @Test
    void getServiceIdFromPath_empty_returnsNull() {
        assertNull(serviceUtils.getServiceIdFromPath(""));
    }

    @Test
    void validateServiceType_nullType_setsRest() {
        Service service = createService("svc", "dev");
        service.getServiceMeta().setType(null);
        serviceUtils.validateServiceType(service);
        assertEquals("rest", service.getServiceMeta().getType());
    }

    @Test
    void validateServiceType_existingType_unchanged() {
        Service service = createService("svc", "dev");
        service.getServiceMeta().setType("websocket");
        serviceUtils.validateServiceType(service);
        assertEquals("websocket", service.getServiceMeta().getType());
    }

    @Test
    void didServiceChange_sameMappings_returnsFalse() {
        Service existing = createServiceWithMapping("svc", "dev", "host1", 8080);
        Service incoming = createServiceWithMapping("svc", "dev", "host1", 8080);
        assertFalse(serviceUtils.didServiceChange(existing, incoming));
    }

    @Test
    void didServiceChange_differentMappingSize_returnsTrue() {
        Service existing = createServiceWithMapping("svc", "dev", "host1", 8080);
        Service incoming = createService("svc", "dev");
        incoming.setMappingList(new HashSet<>());
        assertTrue(serviceUtils.didServiceChange(existing, incoming));
    }

    @Test
    void didServiceChange_securedChanged_returnsTrue() {
        Service existing = createServiceWithMapping("svc", "dev", "host1", 8080);
        Service incoming = createServiceWithMapping("svc", "dev", "host1", 8080);
        incoming.getServiceMeta().setSecured(true);
        assertTrue(serviceUtils.didServiceChange(existing, incoming));
    }

    @Test
    void isMappingChanged_sameList_returnsFalse() {
        Mapping m = new Mapping();
        m.setHostname("host1");
        m.setPort(8080);
        m.setRootContext("/");
        assertFalse(serviceUtils.isMappingChanged(List.of(m), List.of(m)));
    }

    @Test
    void isMappingChanged_differentSize_returnsTrue() {
        Mapping m = new Mapping();
        m.setHostname("host1");
        m.setPort(8080);
        m.setRootContext("/");
        assertTrue(serviceUtils.isMappingChanged(List.of(m), List.of()));
    }

    private Service createService(String name, String group) {
        Service service = new Service();
        service.setName(name);
        ServiceMeta meta = new ServiceMeta();
        meta.setGroup(group);
        service.setServiceMeta(meta);
        return service;
    }

    private Service createServiceWithMapping(String name, String group, String host, int port) {
        Service service = createService(name, group);
        Mapping mapping = new Mapping();
        mapping.setHostname(host);
        mapping.setPort(port);
        mapping.setRootContext("/");
        Set<Mapping> mappings = new HashSet<>();
        mappings.add(mapping);
        service.setMappingList(mappings);
        return service;
    }
}