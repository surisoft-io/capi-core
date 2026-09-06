package io.surisoft.capi.service.consul;

import io.surisoft.capi.schema.InvalidService;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import io.surisoft.capi.utils.ServiceUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * The {@code match-openapi-version} state machine: what CAPI remembers between cycles about a
 * service whose fetched spec did not match its announced version.
 *
 * <p>Exercised through reflection because the state deliberately lives outside
 * {@code invalidServiceMap} (which is cleared every cycle) and there is no public seam for it.
 */
class ConsulCatalogServiceVersionMismatchTest {

    private static final int ATTEMPTS_BEFORE_BACKOFF = 3;

    private ConsulCatalogService catalogService;
    private Map<String, InvalidService> invalidServiceMap;

    @BeforeEach
    void setUp() {
        invalidServiceMap = new HashMap<>();
        catalogService = new ConsulCatalogService(
                List.of(), null, mock(ServiceUtils.class), List.of(), null,
                "capi-1", false, null, invalidServiceMap);
    }

    @AfterEach
    void tearDown() {
        catalogService.shutdown();
    }

    private static Service service(String id) {
        ServiceMeta meta = new ServiceMeta();
        meta.setGroup("v1");
        meta.setVersion("2.0.1");
        meta.setMatchOpenApiVersion(true);
        meta.setOpenApiEndpoint("http://svc/openapi.json");
        Service svc = new Service();
        svc.setId(id);
        svc.setServiceMeta(meta);
        return svc;
    }

    /** Invokes the private recorder with whatever state is currently remembered for the service. */
    private void recordMismatch(Service svc, String detail) throws Exception {
        Object previous = mismatchState().get(svc.getId());
        Method m = ConsulCatalogService.class.getDeclaredMethod(
                "recordVersionMismatch", Service.class, String.class,
                Class.forName("io.surisoft.capi.service.consul.ConsulCatalogService$VersionMismatch"));
        m.setAccessible(true);
        m.invoke(catalogService, svc, detail, previous);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mismatchState() throws Exception {
        Field f = ConsulCatalogService.class.getDeclaredField("versionMismatches");
        f.setAccessible(true);
        return (Map<String, Object>) f.get(catalogService);
    }

    private Object component(Object record, String name) throws Exception {
        Method m = record.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(record);
    }

    @Test
    void firstMismatch_isPublishedWithTheVersionMismatchReason() throws Exception {
        recordMismatch(service("svc:v1"), "meta version 2.0.1, spec info.version 1.0.0");

        InvalidService published = invalidServiceMap.get("svc:v1");
        assertNotNull(published);
        assertEquals(InvalidService.Reason.OPENAPI_VERSION_MISMATCH, published.reason());
        assertTrue(published.detail().contains("2.0.1") && published.detail().contains("1.0.0"),
                "both versions must be diagnosable from /info/invalid-services: " + published.detail());
    }

    @Test
    void firstMismatchesDoNotBackOff_soAShortRolloutConvergesAtCycleRate() throws Exception {
        Service svc = service("svc:v1");
        recordMismatch(svc, "mismatch");

        Instant nextAttempt = (Instant) component(mismatchState().get("svc:v1"), "nextAttemptAt");
        assertFalse(nextAttempt.isAfter(Instant.now().plusSeconds(1)),
                "the next cycle must be free to retry immediately");
    }

    @Test
    void backsOffOnceMismatchesPersist() throws Exception {
        Service svc = service("svc:v1");
        for (int i = 0; i < ATTEMPTS_BEFORE_BACKOFF; i++) {
            recordMismatch(svc, "mismatch");
        }

        Object state = mismatchState().get("svc:v1");
        assertEquals(ATTEMPTS_BEFORE_BACKOFF, component(state, "consecutive"));
        Instant nextAttempt = (Instant) component(state, "nextAttemptAt");
        assertTrue(nextAttempt.isAfter(Instant.now().plus(Duration.ofMinutes(4))),
                "re-fetch should be backed off by minutes, not retried every cycle");
    }

    @Test
    void firstSeenSurvivesRepeatedMismatches_soTheAgeIsReportable() throws Exception {
        Service svc = service("svc:v1");
        recordMismatch(svc, "mismatch");
        Instant firstSeen = (Instant) component(mismatchState().get("svc:v1"), "firstSeen");

        recordMismatch(svc, "mismatch");
        recordMismatch(svc, "mismatch");

        assertEquals(firstSeen, component(mismatchState().get("svc:v1"), "firstSeen"),
                "age is what separates 'rollout in flight' from 'frozen and misconfigured'");
        assertTrue(invalidServiceMap.get("svc:v1").detail().contains("attempt"),
                "the published entry should say how many attempts have failed");
    }

    @Test
    void stateIsPerService() throws Exception {
        recordMismatch(service("a:v1"), "mismatch");
        recordMismatch(service("b:v1"), "mismatch");
        recordMismatch(service("b:v1"), "mismatch");

        assertEquals(1, component(mismatchState().get("a:v1"), "consecutive"));
        assertEquals(2, component(mismatchState().get("b:v1"), "consecutive"));
    }

    @Test
    void mismatchStateIsPrunedForServicesThatDisappear() throws Exception {
        recordMismatch(service("gone:v1"), "mismatch");
        assertTrue(mismatchState().containsKey("gone:v1"));

        // What the cycle does once the service is no longer in the catalog.
        mismatchState().keySet().retainAll(java.util.Set.of("still-here:v1"));

        assertFalse(mismatchState().containsKey("gone:v1"),
                "a deregistered service must not keep a backoff entry alive forever");
    }
}
