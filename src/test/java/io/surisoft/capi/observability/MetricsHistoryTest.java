package io.surisoft.capi.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.surisoft.capi.configuration.MetricsConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class MetricsHistoryTest {

    private static final long T0 = 1_800_000_000_000L;
    private MeterRegistry registry;
    private final AtomicLong now = new AtomicLong(T0);
    private MetricsHistory history;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        history = new MetricsHistory(registry, Duration.ofSeconds(15), Duration.ofMinutes(10), now::get);
    }

    private void requests(String service, String status, int n, long millisEach) {
        String group = status.charAt(0) + "xx";
        Counter.builder("capi_requests_total").tag("service", service).tag("method", "GET")
                .tag("status", status).tag("status_group", group).register(registry).increment(n);
        Timer timer = Timer.builder("capi_request_duration").tag("service", service).tag("method", "GET").register(registry);
        for (int i = 0; i < n; i++) timer.record(millisEach, TimeUnit.MILLISECONDS);
    }

    private void tick() {
        now.addAndGet(15_000);
        history.sample();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> service(Map<String, Object> window, String name) {
        return ((List<Map<String, Object>>) window.get("services")).stream()
                .filter(s -> name.equals(s.get("service"))).findFirst().orElse(null);
    }

    @Test
    void firstSampleIsOnlyABaseline_trafficBeforeItIsNotCounted() {
        requests("orders:dev", "200", 1000, 5);   // since start, before history existed
        history.sample();
        requests("orders:dev", "200", 10, 5);
        tick();
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) history.window(10, null).get("totals");
        assertEquals(10L, totals.get("requests"), "totals since start are not an interval");
    }

    @Test
    void windowSumsDeltas_perServiceAndStatusGroup_withRatesAndLatency() {
        history.sample();
        requests("orders:dev", "200", 30, 10);
        requests("orders:dev", "503", 10, 50);
        requests("billing:dev", "404", 5, 2);
        tick();
        requests("orders:dev", "200", 60, 10);
        tick();

        Map<String, Object> w = history.window(10, null);
        assertEquals(30L, w.get("coveredSeconds"));
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) w.get("totals");
        assertEquals(105L, totals.get("requests"));
        assertEquals(3.5, totals.get("requestsPerSecond"));
        assertEquals(10L, totals.get("5xx"));
        assertEquals(5L, totals.get("4xx"));

        Map<String, Object> orders = service(w, "orders:dev");
        assertEquals(100L, orders.get("requests"));
        assertEquals(0.1, orders.get("errorRatio5xx"));
        assertEquals(14.0, orders.get("avgLatencyMs"), "(90*10 + 10*50) / 100");
        assertEquals(2, ((List<?>) w.get("series")).size());
    }

    @Test
    void serviceFilter_limitsServicesAndSeries() {
        history.sample();
        requests("orders:dev", "200", 3, 1);
        requests("billing:dev", "500", 7, 1);
        tick();
        Map<String, Object> w = history.window(10, "billing:dev");
        assertEquals(1, ((List<?>) w.get("services")).size());
        @SuppressWarnings("unchecked")
        Map<String, Object> point = ((List<Map<String, Object>>) w.get("series")).getFirst();
        assertEquals(7L, point.get("requests"));
        assertEquals(7L, point.get("5xx"));
    }

    @Test
    void idleServicesAreNotStored() {
        requests("orders:dev", "200", 1, 1);
        history.sample();
        tick();   // no traffic in this interval
        assertNull(service(history.window(10, null), "orders:dev"));
    }

    @Test
    void securityCounters_areDeltasSplitByAction() {
        Counter rejected = Counter.builder("capi_dot_segment_requests_total").tag("action", "rejected").register(registry);
        rejected.increment(100);   // before history: not counted
        history.sample();
        rejected.increment(4);
        Counter.builder("capi_blocked_requests_total").register(registry).increment(2);
        tick();
        @SuppressWarnings("unchecked")
        Map<String, Long> security = (Map<String, Long>) history.window(10, null).get("security");
        assertEquals(4L, security.get("capi_dot_segment_requests_total{action=rejected}"));
        assertEquals(2L, security.get("capi_blocked_requests_total"));
    }

    @Test
    void retentionDropsOldSamples_andWindowOnlyCoversWhatIsKept() {
        history.sample();
        for (int i = 0; i < 60; i++) {   // 15 min of samples, retention is 10
            requests("orders:dev", "200", 1, 1);
            tick();
        }
        Map<String, Object> w = history.window(60, null);
        assertEquals(40, w.get("samples"), "10 min / 15 s");
        assertEquals(600L, w.get("coveredSeconds"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void jvmFiguresArePresent() {
        history.sample();
        tick();
        Map<String, Object> jvm = (Map<String, Object>) history.window(10, null).get("jvm");
        Map<String, Object> latest = (Map<String, Object>) jvm.get("latest");
        assertTrue((Integer) latest.get("threads") > 0);
        assertTrue((Long) latest.get("heapMaxMb") > 0);
        assertNotNull(jvm.get("window"));
    }

    /** The GC metrics were bound inside try-with-resources and therefore never recorded a pause. */
    @Test
    void gcPausesAreRecorded_andFileDescriptorsAreExposed() throws Exception {
        PrometheusMeterRegistry prometheus = MetricsConfiguration.createPrometheusMeterRegistry(
                MetricsConfiguration.createMetricsRegistry());
        long pauses = 0;
        for (int i = 0; i < 10 && pauses == 0; i++) {
            System.gc();
            Thread.sleep(200);
            pauses = prometheus.find("jvm.gc.pause").timers().stream().mapToLong(Timer::count).sum();
        }
        assertTrue(pauses > 0, "jvm_gc_pause must record once the binder stays open");
        String os = System.getProperty("os.name").toLowerCase();
        if (!os.contains("win")) {
            assertNotNull(prometheus.find("process.files.open").gauge(), "open file descriptors exposed");
        }
    }
}
