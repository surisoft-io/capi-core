package io.surisoft.capi.service;

import com.styra.opa.wasm.OpaPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * The OPA policy pool.
 *
 * <p>Two failures used to reach callers as a wrong answer rather than an error:
 *
 * <ul>
 *   <li>An evaluation that threw cost its instance permanently. Nothing replaced it until the
 *       next bundle publish, so repeated failures drained the pool and every OPA-protected
 *       service began returning 403 "Access denied by policy" — an outage that looked like a
 *       policy decision.</li>
 *   <li>{@code poll()} on an empty queue returns null immediately, so any burst of concurrency
 *       wider than the pool produced the same spurious 403. With the default size of 10 this is
 *       reachable under ordinary load, and is the likeliest explanation for the 69 unexplained
 *       OPA failures recorded at 700 VUs.</li>
 * </ul>
 *
 * <p>The pool takes a supplier so this is testable without a Wasm bundle.
 */
class OpaWasmPoolTest {

    /** Counts how many instances were ever minted. */
    private static final class CountingFactory implements Supplier<OpaPolicy> {
        final AtomicInteger created = new AtomicInteger();
        @Override public OpaPolicy get() {
            created.incrementAndGet();
            return mock(OpaPolicy.class);
        }
    }

    @Test
    void preWarmsToTheConfiguredSize() {
        CountingFactory factory = new CountingFactory();
        OpaWasmService.PolicyPool pool = new OpaWasmService.PolicyPool(factory, 5, 20);

        assertEquals(5, factory.created.get());
        assertEquals(5, pool.liveCount());
        assertEquals(5, pool.idleCount());
    }

    @Test
    void acquireAndReleaseReusesInstances() {
        CountingFactory factory = new CountingFactory();
        OpaWasmService.PolicyPool pool = new OpaWasmService.PolicyPool(factory, 3, 12);

        for (int i = 0; i < 50; i++) {
            OpaPolicy p = pool.acquire();
            assertNotNull(p);
            pool.release(p);
        }
        assertEquals(3, factory.created.get(), "steady-state traffic must not mint anything new");
        assertEquals(3, pool.liveCount());
    }

    // === the discard bug ===

    @Test
    void discardedInstanceIsReplacedRatherThanLostForever() {
        CountingFactory factory = new CountingFactory();
        OpaWasmService.PolicyPool pool = new OpaWasmService.PolicyPool(factory, 2, 8);

        // Two evaluations throw: each instance is acquired, then retired rather than released.
        pool.discard(pool.acquire());
        pool.discard(pool.acquire());
        assertEquals(0, pool.liveCount(), "both instances are accounted for as gone");
        assertEquals(0, pool.idleCount(), "and neither is left sitting in the queue");

        // The pool must still serve — previously this returned null forever.
        assertNotNull(pool.acquire(), "a replacement must be minted");
        assertNotNull(pool.acquire());
        assertEquals(4, factory.created.get(), "2 pre-warmed + 2 replacements");
    }

    @Test
    void repeatedFailuresDoNotDrainThePool() {
        CountingFactory factory = new CountingFactory();
        OpaWasmService.PolicyPool pool = new OpaWasmService.PolicyPool(factory, 4, 16);

        // 100 consecutive failing evaluations — far more than the pool size.
        for (int i = 0; i < 100; i++) {
            OpaPolicy p = pool.acquire();
            assertNotNull(p, "the pool must still serve on failure " + i);
            pool.discard(p);
        }
        assertNotNull(pool.acquire(), "the pool must survive a sustained failure run");
    }

    @Test
    void discardNeverDrivesTheCountNegative() {
        OpaWasmService.PolicyPool pool = new OpaWasmService.PolicyPool(new CountingFactory(), 1, 4);
        for (int i = 0; i < 10; i++) {
            OpaPolicy p = pool.acquire();
            if (p != null) {
                pool.discard(p);
            }
        }
        assertEquals(0, pool.liveCount(), "a negative count would silently raise the growth budget");
    }

    // === the concurrency bug ===

    @Test
    void growsBeyondTheConfiguredSizeWhenEveryInstanceIsBusy() {
        CountingFactory factory = new CountingFactory();
        OpaWasmService.PolicyPool pool = new OpaWasmService.PolicyPool(factory, 2, 8);

        // Hold all of them, as concurrent requests would.
        List<OpaPolicy> held = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            OpaPolicy p = pool.acquire();
            assertNotNull(p, "acquire " + i + " must not fail just because the others are busy");
            held.add(p);
        }
        assertEquals(6, pool.liveCount());
        held.forEach(pool::release);
    }

    @Test
    void growthStopsAtTheCeiling() {
        CountingFactory factory = new CountingFactory();
        OpaWasmService.PolicyPool pool = new OpaWasmService.PolicyPool(factory, 2, 5);

        List<OpaPolicy> held = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            held.add(pool.acquire());
        }
        held.forEach(java.util.Objects::requireNonNull);

        assertNull(pool.acquire(), "past the ceiling the caller must be told, not handed a 6th");
        assertEquals(5, pool.liveCount(), "memory stays bounded");
    }

    @Test
    void ceilingIsNeverBelowTheConfiguredSize() {
        // A misconfigured ceiling must not shrink the pool the operator asked for.
        OpaWasmService.PolicyPool pool = new OpaWasmService.PolicyPool(new CountingFactory(), 6, 2);
        List<OpaPolicy> held = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            held.add(pool.acquire());
        }
        held.forEach(java.util.Objects::requireNonNull);
        assertEquals(6, pool.liveCount());
    }

    @Test
    void concurrentGrowthDoesNotOvershootTheCeiling() throws Exception {
        CountingFactory factory = new CountingFactory();
        int ceiling = 12;
        OpaWasmService.PolicyPool pool = new OpaWasmService.PolicyPool(factory, 1, ceiling);

        int threads = 32;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<OpaPolicy> acquired = java.util.Collections.synchronizedList(new ArrayList<>());
        try {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    try {
                        start.await();
                        OpaPolicy p = pool.acquire();
                        if (p != null) acquired.add(p);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "all acquires should complete");

            assertEquals(ceiling, pool.liveCount(), "CAS must stop growth exactly at the ceiling");
            assertEquals(ceiling, acquired.size(), "no thread should get an instance beyond it");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aFailingFactoryDoesNotLeakAGrowthSlot() {
        Supplier<OpaPolicy> explodingAfterFirst = new Supplier<>() {
            int calls = 0;
            @Override public OpaPolicy get() {
                if (calls++ == 0) return mock(OpaPolicy.class);
                throw new IllegalStateException("wasm instantiation failed");
            }
        };
        OpaWasmService.PolicyPool pool = new OpaWasmService.PolicyPool(explodingAfterFirst, 1, 4);

        OpaPolicy first = pool.acquire();
        assertNotNull(first);

        // Pool is empty and growth will throw; the reserved slot must be released again.
        assertThrows(IllegalStateException.class, pool::acquire);
        assertEquals(1, pool.liveCount(),
                "a failed mint must not permanently consume part of the growth budget");
    }
}
