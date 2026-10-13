package io.surisoft.capi.service.consul;

import io.surisoft.capi.schema.*;
import io.surisoft.capi.utils.WebDavUtils;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The shared-state guard. This is the only thing standing between a multi-instance WebDAV service
 * with independent storage and silent file corruption, so it is tested as a decision in its own right
 * rather than only through the handler.
 */
class WebDavTransportHandlerTest {

    private static Mapping mapping(String host, int port) {
        Mapping m = new Mapping();
        m.setHostname(host);
        m.setPort(port);
        m.setRootContext("/");
        return m;
    }

    private static Service service(boolean sharedState, Mapping... mappings) {
        ServiceMeta meta = new ServiceMeta();
        meta.setType("webdav");
        meta.setGroup("v1");
        meta.setWebdavSharedState(sharedState);
        Service s = new Service();
        s.setId("files:v1");
        s.setName("files");
        s.setContext("/files/v1");
        s.setServiceMeta(meta);
        Set<Mapping> set = new LinkedHashSet<>();
        for (Mapping m : mappings) {
            set.add(m);
        }
        s.setMappingList(set);
        return s;
    }

    // ---- the guard itself ----

    @Test
    void singleInstance_isAlwaysAllowed() {
        // One instance cannot disagree with itself about storage or locks.
        assertNull(WebDavTransportHandler.sharedStateViolation(
                service(false, mapping("dav-1", 80))));
    }

    @Test
    void multiInstanceWithoutAssertion_isRefused() {
        String violation = WebDavTransportHandler.sharedStateViolation(
                service(false, mapping("dav-1", 80), mapping("dav-2", 80)));
        assertNotNull(violation, "a multi-instance webdav service must not publish silently");
        assertTrue(violation.contains("webdav-shared-state"),
                "the message has to name the key the owner needs to set: " + violation);
        assertTrue(violation.contains("2 instances"), "it should say how many it saw: " + violation);
    }

    @Test
    void multiInstanceWithAssertion_isAllowed() {
        assertNull(WebDavTransportHandler.sharedStateViolation(
                service(true, mapping("dav-1", 80), mapping("dav-2", 80))));
    }

    // ---- the handler acts on it ----

    @Test
    void refusedService_isNotRegisteredAndIsReported() {
        Map<String, WebDavClient> clients = new ConcurrentHashMap<>();
        Map<String, InvalidService> invalid = new ConcurrentHashMap<>();
        WebDavUtils utils = mock(WebDavUtils.class);
        WebDavTransportHandler h = new WebDavTransportHandler(true, clients, utils, invalid);

        h.onAppear(service(false, mapping("dav-1", 80), mapping("dav-2", 80)));

        assertTrue(clients.isEmpty(), "the service must not be routable");
        verify(utils, never()).createWebDavClient(any());
        assertEquals(1, invalid.size());
        assertEquals(InvalidService.Reason.WEBDAV_UNSHARED_MULTI_INSTANCE,
                invalid.get("files:v1").reason());
    }

    @Test
    void allowedService_isRegistered() {
        Map<String, WebDavClient> clients = new ConcurrentHashMap<>();
        Map<String, InvalidService> invalid = new ConcurrentHashMap<>();
        WebDavUtils utils = mock(WebDavUtils.class);
        WebDavClient built = new WebDavClient();
        built.setServiceId("/files/v1");
        when(utils.createWebDavClient(any())).thenReturn(built);
        WebDavTransportHandler h = new WebDavTransportHandler(true, clients, utils, invalid);

        h.onAppear(service(true, mapping("dav-1", 80), mapping("dav-2", 80)));

        assertEquals(1, clients.size());
        assertTrue(invalid.isEmpty());
    }

    /**
     * invalidServiceMap is cleared at the start of every reconcile cycle while onAppear only fires on
     * a delta, so without re-asserting in afterCycle a refusal would be reported once and then vanish
     * while the cause persisted.
     */
    @Test
    void refusalSurvivesTheNextCycleClear() {
        Map<String, InvalidService> invalid = new ConcurrentHashMap<>();
        WebDavTransportHandler h = new WebDavTransportHandler(
                true, new ConcurrentHashMap<>(), mock(WebDavUtils.class), invalid);

        h.onAppear(service(false, mapping("dav-1", 80), mapping("dav-2", 80)));
        assertEquals(1, invalid.size());

        invalid.clear();              // what ConsulCatalogService does at the top of every cycle
        h.afterCycle();               // runs at the end of it

        assertEquals(1, invalid.size(), "the refusal must still be visible at /info/invalid-services");
    }

    @Test
    void refusalClearsWhenTheServiceGoesAway() {
        Map<String, InvalidService> invalid = new ConcurrentHashMap<>();
        WebDavTransportHandler h = new WebDavTransportHandler(
                true, new ConcurrentHashMap<>(), mock(WebDavUtils.class), invalid);
        Service svc = service(false, mapping("dav-1", 80), mapping("dav-2", 80));

        h.onAppear(svc);
        h.onDisappear(svc);
        h.afterCycle();

        assertTrue(invalid.isEmpty(), "a service that is gone must stop being reported");
    }

    @Test
    void fixingTheMetadataClearsTheRefusal() {
        Map<String, WebDavClient> clients = new ConcurrentHashMap<>();
        Map<String, InvalidService> invalid = new ConcurrentHashMap<>();
        WebDavUtils utils = mock(WebDavUtils.class);
        WebDavClient built = new WebDavClient();
        built.setServiceId("/files/v1");
        when(utils.createWebDavClient(any())).thenReturn(built);
        WebDavTransportHandler h = new WebDavTransportHandler(true, clients, utils, invalid);

        Service bad = service(false, mapping("dav-1", 80), mapping("dav-2", 80));
        Service good = service(true, mapping("dav-1", 80), mapping("dav-2", 80));
        h.onAppear(bad);
        h.onChange(bad, good);
        h.afterCycle();

        assertEquals(1, clients.size(), "the corrected service must become routable");
        assertTrue(invalid.isEmpty(), "and must stop being reported");
    }

    // ---- type gating: this handler must ignore every other transport ----

    @Test
    void supportsOnlyWebdavAndOnlyWhenEnabled() {
        WebDavTransportHandler on = new WebDavTransportHandler(
                true, new ConcurrentHashMap<>(), mock(WebDavUtils.class), new ConcurrentHashMap<>());
        assertTrue(on.supports(service(true, mapping("d", 80))));

        for (String other : new String[]{"rest", "websocket", "grpc", null}) {
            Service s = service(true, mapping("d", 80));
            s.getServiceMeta().setType(other);
            assertFalse(on.supports(s), "must not claim type=" + other);
        }

        WebDavTransportHandler off = new WebDavTransportHandler(
                false, new ConcurrentHashMap<>(), mock(WebDavUtils.class), new ConcurrentHashMap<>());
        assertFalse(off.supports(service(true, mapping("d", 80))), "disabled must claim nothing");
    }

    // ---- one hostname has exactly one owner ----

    private static Service hostService(String name, String host) {
        Service s = service(false, mapping("dav-" + name, 80));
        s.setId(name + ":v1");
        s.setName(name);
        s.setContext("/" + name + "/v1");
        s.getServiceMeta().setWebdavHost(host);
        return s;
    }

    private static WebDavTransportHandler handlerWithRealClients(Map<String, WebDavClient> clients,
                                                                 Map<String, InvalidService> invalid) {
        WebDavUtils utils = mock(WebDavUtils.class);
        when(utils.createWebDavClient(any())).thenAnswer(inv -> {
            Service s = inv.getArgument(0);
            WebDavClient c = new WebDavClient();
            c.setServiceId(s.getContext());
            c.setWebdavHost(s.getServiceMeta().getWebdavHost());
            return c;
        });
        return new WebDavTransportHandler(true, clients, utils, invalid);
    }

    @Test
    void twoServicesClaimingOneHost_onlyOneIsPublished() {
        Map<String, WebDavClient> clients = new ConcurrentHashMap<>();
        Map<String, InvalidService> invalid = new ConcurrentHashMap<>();
        WebDavTransportHandler h = handlerWithRealClients(clients, invalid);

        h.onAppear(hostService("archive", "10.226.42.106"));
        h.onAppear(hostService("documents", "10.226.42.106"));

        assertEquals(1, clients.size(), "a hostname must resolve to exactly one service");
        assertEquals(1, invalid.size(), "the loser must be reported, not silently dropped");
        assertEquals(InvalidService.Reason.WEBDAV_HOST_COLLISION,
                invalid.values().iterator().next().reason());
    }

    @Test
    void collisionWinnerIsDeterministicRegardlessOfOrder() {
        // The same pair must resolve the same way however Consul happened to report them, otherwise a
        // restart could silently swap which backend serves a hostname.
        Map<String, WebDavClient> a = new ConcurrentHashMap<>();
        WebDavTransportHandler h1 = handlerWithRealClients(a, new ConcurrentHashMap<>());
        h1.onAppear(hostService("archive", "10.226.42.106"));
        h1.onAppear(hostService("documents", "10.226.42.106"));

        Map<String, WebDavClient> b = new ConcurrentHashMap<>();
        WebDavTransportHandler h2 = handlerWithRealClients(b, new ConcurrentHashMap<>());
        h2.onAppear(hostService("documents", "10.226.42.106"));
        h2.onAppear(hostService("archive", "10.226.42.106"));

        assertEquals(a.keySet(), b.keySet(), "winner must not depend on discovery order");
        assertEquals("/archive/v1", a.keySet().iterator().next(), "lexicographically smaller id wins");
    }

    @Test
    void differentHosts_bothPublish() {
        Map<String, WebDavClient> clients = new ConcurrentHashMap<>();
        Map<String, InvalidService> invalid = new ConcurrentHashMap<>();
        WebDavTransportHandler h = handlerWithRealClients(clients, invalid);

        h.onAppear(hostService("archive", "dav-archive.corp"));
        h.onAppear(hostService("documents", "dav-documents.corp"));

        assertEquals(2, clients.size());
        assertTrue(invalid.isEmpty());
    }

    @Test
    void pathRoutedServicesNeverCollide() {
        // No hostname claimed, so any number of them coexist.
        Map<String, WebDavClient> clients = new ConcurrentHashMap<>();
        Map<String, InvalidService> invalid = new ConcurrentHashMap<>();
        WebDavTransportHandler h = handlerWithRealClients(clients, invalid);

        h.onAppear(hostService("archive", null));
        h.onAppear(hostService("documents", null));

        assertEquals(2, clients.size());
        assertTrue(invalid.isEmpty());
    }
}
