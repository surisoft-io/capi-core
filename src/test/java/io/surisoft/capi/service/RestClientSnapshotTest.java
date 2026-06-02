package io.surisoft.capi.service;

import io.surisoft.capi.schema.RestClient;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RestClientSnapshotTest {

    @Test
    void initialSnapshot_isEmpty() {
        RestClientSnapshot snap = new RestClientSnapshot();
        assertTrue(snap.current().isEmpty(), "fresh snapshot must start empty");
    }

    @Test
    void publish_replacesPreviousSnapshot() {
        RestClientSnapshot snap = new RestClientSnapshot();
        Map<String, RestClient> first = new HashMap<>();
        first.put("/svc-a/v1", new RestClient());
        snap.publish(first);

        assertEquals(1, snap.current().size());
        assertTrue(snap.current().containsKey("/svc-a/v1"));

        Map<String, RestClient> second = new HashMap<>();
        second.put("/svc-b/v1", new RestClient());
        snap.publish(second);

        assertEquals(1, snap.current().size());
        assertFalse(snap.current().containsKey("/svc-a/v1"),
                "previous snapshot's entries must be gone after publish");
        assertTrue(snap.current().containsKey("/svc-b/v1"));
    }

    @Test
    void publish_null_yieldsEmptyImmutableSnapshot() {
        RestClientSnapshot snap = new RestClientSnapshot();
        Map<String, RestClient> populated = new HashMap<>();
        populated.put("/svc-a/v1", new RestClient());
        snap.publish(populated);
        assertFalse(snap.current().isEmpty());

        snap.publish(null);
        assertTrue(snap.current().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> snap.current().put("/x", new RestClient()),
                "snapshot must be immutable even when seeded from null");
    }

    @Test
    void current_isImmutable() {
        RestClientSnapshot snap = new RestClientSnapshot();
        Map<String, RestClient> source = new HashMap<>();
        source.put("/svc/v1", new RestClient());
        snap.publish(source);

        Map<String, RestClient> view = snap.current();
        assertThrows(UnsupportedOperationException.class,
                () -> view.put("/another/v1", new RestClient()),
                "readers must not be able to mutate the snapshot they observe");
    }

    @Test
    void writerMutatesSourceAfterPublish_doesNotAffectSnapshot() {
        // Crucial invariant: publish() copies. The catalog cycle keeps mutating its
        // live ConcurrentHashMap after the publish moment; the snapshot readers see
        // must NOT reflect those subsequent mutations.
        RestClientSnapshot snap = new RestClientSnapshot();
        Map<String, RestClient> live = new HashMap<>();
        live.put("/svc-a/v1", new RestClient());
        snap.publish(live);

        live.put("/svc-b/v1", new RestClient());
        live.remove("/svc-a/v1");

        Map<String, RestClient> view = snap.current();
        assertEquals(1, view.size());
        assertTrue(view.containsKey("/svc-a/v1"),
                "snapshot must reflect state at publish time, not post-publish mutations");
        assertFalse(view.containsKey("/svc-b/v1"));
    }

    @Test
    void concurrentReadsWhilePublishing_alwaysSeeAFullSnapshot() throws Exception {
        // The snapshot's contract: readers either see the previous fully-published
        // snapshot or the next fully-published snapshot — never a half-built one.
        RestClientSnapshot snap = new RestClientSnapshot();
        Map<String, RestClient> v1 = new HashMap<>();
        for (int i = 0; i < 100; i++) v1.put("/svc-" + i + "/v1", new RestClient());
        snap.publish(v1);

        AtomicReference<String> failure = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            for (int i = 0; i < 5_000 && failure.get() == null; i++) {
                int size = snap.current().size();
                if (size != 100) {
                    failure.set("reader observed size=" + size + " (expected 100)");
                }
            }
        });
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 5_000; i++) {
                Map<String, RestClient> next = new HashMap<>();
                for (int j = 0; j < 100; j++) next.put("/svc-" + j + "/cycle" + i, new RestClient());
                snap.publish(next);
            }
        });

        reader.start();
        writer.start();
        reader.join(10_000);
        writer.join(10_000);

        assertNull(failure.get(), failure.get());
    }
}