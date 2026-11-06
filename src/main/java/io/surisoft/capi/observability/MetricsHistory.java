package io.surisoft.capi.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * A short, in-memory history of this node's own metrics, so the admin MCP can answer "what is
 * happening now / in the last N minutes" without a Prometheus server. Prometheus counters are totals
 * since start; this samples them every {@code interval} and keeps per-interval DELTAS for
 * {@code retention}. Nothing is persisted: a restart starts a fresh history.
 *
 * <p>Memory stays bounded: a sample holds only services that had traffic in that interval, and
 * samples older than the retention are dropped.
 *
 * <p>Sampling runs on its own daemon thread — never an Undertow thread. {@link #window} is what
 * {@code /info/metrics/history} serves: the window is summed here, on the node, so only a summary
 * crosses the network.
 */
public final class MetricsHistory implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MetricsHistory.class);

    static final String REQUESTS = "capi_requests_total";
    static final String DURATION = "capi_request_duration";
    /** Security counters, each split by its {@code action} tag (observed / rejected). */
    static final List<String> SECURITY_COUNTERS = List.of(
            "capi_blocked_requests_total", "capi_dot_segment_requests_total", "capi_query_token_requests_total",
            "capi_websocket_origin_total", "capi_webdav_depth_total");
    private static final String[] GROUPS = {"2xx", "3xx", "4xx", "5xx"};

    /** Per-service activity in one interval. {@code statusCounts} follows {@link #GROUPS}; index 4 = other. */
    record ServiceDelta(long[] statusCounts, long timedCount, long totalNanos, double maxMs) {
        long requests() {
            long sum = 0;
            for (long c : statusCounts) sum += c;
            return sum;
        }
    }

    record Jvm(long heapUsed, long heapMax, int threads, int peakThreads, double processCpu, long openFds, long maxFds) {}

    record Sample(long epochMs, long intervalMs, Map<String, ServiceDelta> services, Map<String, Long> security, Jvm jvm) {}

    private final MeterRegistry registry;
    private final Duration interval;
    private final Duration retention;
    private final LongSupplier clock;
    private final Deque<Sample> samples = new ArrayDeque<>();
    /** Cumulative values at the previous sample; deltas are taken against these. */
    private Map<String, long[]> previousStatus;
    private Map<String, long[]> previousTimers;
    private Map<String, Long> previousSecurity;
    private long previousAt;
    private ScheduledExecutorService executor;

    public MetricsHistory(MeterRegistry registry, Duration interval, Duration retention) {
        this(registry, interval, retention, System::currentTimeMillis);
    }

    MetricsHistory(MeterRegistry registry, Duration interval, Duration retention, LongSupplier clock) {
        this.registry = registry;
        this.interval = interval;
        this.retention = retention;
        this.clock = clock;
    }

    public synchronized void start() {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "capi-metrics-history");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(() -> {
            try {
                sample();
            } catch (Throwable t) {
                // Throwable: anything escaping would cancel the task silently and forever.
                log.error("Metrics history sample failed", t);
            }
        }, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        log.info("Metrics history started (every {}s, keeping {} min)", interval.toSeconds(), retention.toMinutes());
    }

    @Override
    public synchronized void close() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public Duration getInterval() {
        return interval;
    }

    public Duration getRetention() {
        return retention;
    }

    // ------------------------------------------------------------------ sampling

    /** Takes one sample. The first call only records a baseline: totals since start are not an interval. */
    void sample() {
        long now = clock.getAsLong();
        Map<String, long[]> status = new HashMap<>();
        for (Counter c : registry.find(REQUESTS).counters()) {
            String service = c.getId().getTag("service");
            if (service == null) continue;
            String group = c.getId().getTag("status_group");
            int index = 4;
            for (int i = 0; i < GROUPS.length; i++) {
                if (GROUPS[i].equals(group)) index = i;
            }
            status.computeIfAbsent(service, k -> new long[5])[index] += (long) c.count();
        }
        Map<String, long[]> timers = new HashMap<>();
        Map<String, Double> maxMs = new HashMap<>();
        for (Timer t : registry.find(DURATION).timers()) {
            String service = t.getId().getTag("service");
            if (service == null) continue;
            long[] v = timers.computeIfAbsent(service, k -> new long[2]);
            v[0] += t.count();
            v[1] += (long) t.totalTime(TimeUnit.NANOSECONDS);
            // Timer max is a decaying recent max (~2 min), so sampling it each interval is meaningful.
            maxMs.merge(service, t.max(TimeUnit.MILLISECONDS), Math::max);
        }
        Map<String, Long> security = new TreeMap<>();
        for (String name : SECURITY_COUNTERS) {
            for (Counter c : registry.find(name).counters()) {
                String action = c.getId().getTag("action");
                security.merge(action != null ? name + "{action=" + action + "}" : name, (long) c.count(), Long::sum);
            }
        }
        Jvm jvm = jvm();

        synchronized (this) {
            if (previousStatus != null) {
                Map<String, ServiceDelta> deltas = new HashMap<>();
                java.util.Set<String> services = new java.util.HashSet<>(status.keySet());
                services.addAll(timers.keySet());
                for (String service : services) {
                    long[] cur = status.getOrDefault(service, new long[5]);
                    long[] prev = previousStatus.getOrDefault(service, new long[5]);
                    long[] counts = new long[5];
                    boolean any = false;
                    for (int i = 0; i < 5; i++) {
                        counts[i] = delta(cur[i], prev[i]);
                        any |= counts[i] > 0;
                    }
                    long[] ct = timers.getOrDefault(service, new long[2]);
                    long[] pt = previousTimers.getOrDefault(service, new long[2]);
                    long timed = delta(ct[0], pt[0]);
                    long nanos = delta(ct[1], pt[1]);
                    if (any || timed > 0) {
                        deltas.put(service, new ServiceDelta(counts, timed, nanos, maxMs.getOrDefault(service, 0.0)));
                    }
                }
                Map<String, Long> securityDelta = new TreeMap<>();
                security.forEach((k, v) -> {
                    long d = delta(v, previousSecurity.getOrDefault(k, 0L));
                    if (d > 0) securityDelta.put(k, d);
                });
                samples.addLast(new Sample(now, now - previousAt, deltas, securityDelta, jvm));
                // A sample stamped t covers the interval ending at t, so one stamped exactly at the
                // cutoff lies wholly outside the retention and goes.
                long cutoff = now - retention.toMillis();
                while (!samples.isEmpty() && samples.peekFirst().epochMs() <= cutoff) {
                    samples.removeFirst();
                }
            }
            previousStatus = status;
            previousTimers = timers;
            previousSecurity = security;
            previousAt = now;
        }
    }

    /** A meter that shrank was removed and re-created (or the registry reset): count its new value as the delta. */
    private static long delta(long current, long previous) {
        return current >= previous ? current - previous : current;
    }

    private static Jvm jvm() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        var threads = ManagementFactory.getThreadMXBean();
        double cpu = -1;
        long openFds = -1;
        long maxFds = -1;
        var os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            cpu = sun.getProcessCpuLoad();
        }
        if (os instanceof com.sun.management.UnixOperatingSystemMXBean unix) {
            openFds = unix.getOpenFileDescriptorCount();
            maxFds = unix.getMaxFileDescriptorCount();
        }
        return new Jvm(heap.getUsed(), heap.getMax(), threads.getThreadCount(), threads.getPeakThreadCount(), cpu, openFds, maxFds);
    }

    // ------------------------------------------------------------------ window

    /**
     * The last {@code minutes} summed: totals, per-service figures, a per-interval series, security
     * counter deltas and JVM figures. With {@code service}, services and series are limited to it.
     */
    public Map<String, Object> window(int minutes, String service) {
        List<Sample> selected;
        synchronized (this) {
            long from = clock.getAsLong() - minutes * 60_000L;
            selected = samples.stream().filter(s -> s.epochMs() > from).toList();
        }
        long coveredMs = selected.stream().mapToLong(Sample::intervalMs).sum();

        Map<String, long[]> perService = new TreeMap<>();   // [2xx,3xx,4xx,5xx,other,timed,nanos]
        Map<String, Double> perServiceMax = new HashMap<>();
        Map<String, Long> security = new TreeMap<>();
        List<Map<String, Object>> series = new ArrayList<>();
        for (Sample s : selected) {
            long[] interval = new long[7];
            for (Map.Entry<String, ServiceDelta> e : s.services().entrySet()) {
                if (service != null && !service.equals(e.getKey())) continue;
                long[] acc = perService.computeIfAbsent(e.getKey(), k -> new long[7]);
                ServiceDelta d = e.getValue();
                for (int i = 0; i < 5; i++) {
                    acc[i] += d.statusCounts()[i];
                    interval[i] += d.statusCounts()[i];
                }
                acc[5] += d.timedCount();
                acc[6] += d.totalNanos();
                interval[5] += d.timedCount();
                interval[6] += d.totalNanos();
                perServiceMax.merge(e.getKey(), d.maxMs(), Math::max);
            }
            s.security().forEach((k, v) -> security.merge(k, v, Long::sum));
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("t", Instant.ofEpochMilli(s.epochMs()).toString());
            point.put("requests", interval[0] + interval[1] + interval[2] + interval[3] + interval[4]);
            point.put("4xx", interval[2]);
            point.put("5xx", interval[3]);
            point.put("avgLatencyMs", avgMs(interval[5], interval[6]));
            series.add(point);
        }

        long[] total = new long[7];
        double totalMax = 0;
        List<Map<String, Object>> services = new ArrayList<>();
        for (Map.Entry<String, long[]> e : perService.entrySet()) {
            long[] v = e.getValue();
            for (int i = 0; i < 7; i++) total[i] += v[i];
            double max = perServiceMax.getOrDefault(e.getKey(), 0.0);
            totalMax = Math.max(totalMax, max);
            Map<String, Object> m = counts(v, coveredMs);
            m.put("maxLatencyMsSampled", round(max));
            Map<String, Object> named = new LinkedHashMap<>();
            named.put("service", e.getKey());
            named.putAll(m);
            services.add(named);
        }
        services.sort(Comparator.comparingLong((Map<String, Object> m) -> (Long) m.get("requests")).reversed());

        Map<String, Object> totals = counts(total, coveredMs);
        totals.put("maxLatencyMsSampled", round(totalMax));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("intervalSeconds", interval.toSeconds());
        out.put("retentionMinutes", retention.toMinutes());
        out.put("requestedMinutes", minutes);
        // Less than requested right after a start: the history is only as old as the node.
        out.put("coveredSeconds", coveredMs / 1000);
        out.put("samples", selected.size());
        if (service != null) out.put("service", service);
        out.put("totals", totals);
        out.put("services", services);
        out.put("series", series);
        out.put("security", security);
        out.put("jvm", jvmSummary(selected));
        return out;
    }

    private static Map<String, Object> counts(long[] v, long coveredMs) {
        long requests = v[0] + v[1] + v[2] + v[3] + v[4];
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requests", requests);
        m.put("requestsPerSecond", coveredMs > 0 ? round(requests * 1000.0 / coveredMs) : 0.0);
        m.put("2xx", v[0]);
        m.put("3xx", v[1]);
        m.put("4xx", v[2]);
        m.put("5xx", v[3]);
        m.put("errorRatio5xx", requests > 0 ? round((double) v[3] / requests) : 0.0);
        m.put("avgLatencyMs", avgMs(v[5], v[6]));
        return m;
    }

    private static Map<String, Object> jvmSummary(List<Sample> selected) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (selected.isEmpty()) {
            out.put("latest", jvmMap(jvm()));
            return out;
        }
        Jvm first = selected.getFirst().jvm();
        Jvm last = selected.getLast().jvm();
        out.put("latest", jvmMap(last));
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("heapUsedPctMax", round(selected.stream().mapToDouble(s -> pct(s.jvm().heapUsed(), s.jvm().heapMax())).max().orElse(0)));
        w.put("threadsMin", selected.stream().mapToInt(s -> s.jvm().threads()).min().orElse(0));
        w.put("threadsMax", selected.stream().mapToInt(s -> s.jvm().threads()).max().orElse(0));
        w.put("processCpuMax", round(selected.stream().mapToDouble(s -> s.jvm().processCpu()).max().orElse(-1)));
        w.put("openFdsFirst", first.openFds());
        w.put("openFdsLast", last.openFds());
        w.put("openFdsMax", selected.stream().mapToLong(s -> s.jvm().openFds()).max().orElse(-1));
        w.put("threadsFirst", first.threads());
        w.put("threadsLast", last.threads());
        out.put("window", w);
        return out;
    }

    private static Map<String, Object> jvmMap(Jvm j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("heapUsedMb", j.heapUsed() / (1024 * 1024));
        m.put("heapMaxMb", j.heapMax() / (1024 * 1024));
        m.put("heapUsedPct", round(pct(j.heapUsed(), j.heapMax())));
        m.put("threads", j.threads());
        m.put("peakThreads", j.peakThreads());
        m.put("processCpu", round(j.processCpu()));
        m.put("openFds", j.openFds());
        m.put("maxFds", j.maxFds());
        return m;
    }

    private static double pct(long used, long max) {
        return max > 0 ? used * 100.0 / max : 0;
    }

    private static double avgMs(long count, long nanos) {
        return count > 0 ? round(nanos / 1_000_000.0 / count) : 0.0;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
