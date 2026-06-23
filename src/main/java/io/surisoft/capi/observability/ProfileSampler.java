package io.surisoft.capi.observability;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Rolling-window aggregator over JFR {@code jdk.ExecutionSample} events.
 *
 * <p>Each sample captured by JFR represents one moment in time where some thread
 * was {@code RUNNABLE} on a CPU. Threads that were parked, waiting, or blocked
 * are not sampled — so the aggregation answers "what is burning CPU?" not
 * "what are threads doing?".
 *
 * <p>Samples are kept in a bounded ring (default ~15 000 entries which, at a
 * 20 ms sampling cadence, covers the last ~5 minutes). On {@code topByCpu(...)},
 * the ring is filtered by the requested time window, grouped by the top stack
 * frame, sorted by sample count, and the top-K returned with a representative
 * stack so callers can see where the hot frame is being called from.
 */
public class ProfileSampler {

    private final int capacity;
    private final int maxStackFrames;
    private final Deque<Sample> samples = new ConcurrentLinkedDeque<>();

    public ProfileSampler(int capacity, int maxStackFrames) {
        this.capacity = capacity;
        this.maxStackFrames = maxStackFrames;
    }

    /** Default sizing: 15 000 samples × ~600 bytes ≈ 9 MiB resident; 10-frame stacks. */
    public ProfileSampler() {
        this(15_000, 10);
    }

    /** Called by the JFR RecordingStream subscriber. Safe to call concurrently. */
    public void onSample(RecordedEvent event) {
        RecordedStackTrace stack = event.getStackTrace();
        if (stack == null) return;
        List<RecordedFrame> frames = stack.getFrames();
        if (frames.isEmpty()) return;

        String topFrame = formatFrame(frames.get(0));
        List<String> stackPreview = new ArrayList<>(Math.min(frames.size(), maxStackFrames));
        for (int i = 0; i < Math.min(frames.size(), maxStackFrames); i++) {
            stackPreview.add(formatFrame(frames.get(i)));
        }

        samples.addFirst(new Sample(event.getStartTime(), topFrame, stackPreview));
        while (samples.size() > capacity) {
            samples.pollLast();
        }
    }

    /**
     * Top-K stack frames by sample count over the given trailing window.
     * Returns empty list if no samples landed in the window.
     */
    public List<TopFrame> topByCpu(Duration window, int limit) {
        Instant cutoff = Instant.now().minus(window);
        Map<String, Long> counts = new HashMap<>();
        Map<String, List<String>> firstStackByFrame = new HashMap<>();
        long total = 0;
        for (Sample s : samples) {
            if (s.at.isBefore(cutoff)) continue;
            counts.merge(s.topFrame, 1L, Long::sum);
            firstStackByFrame.putIfAbsent(s.topFrame, s.stack);
            total++;
        }
        if (total == 0) return List.of();

        long totalFinal = total;
        return counts.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry<String, Long>::getValue).reversed())
                .limit(limit)
                .map(e -> new TopFrame(
                        e.getKey(),
                        e.getValue(),
                        Math.round(1000.0 * e.getValue() / totalFinal) / 10.0,
                        firstStackByFrame.getOrDefault(e.getKey(), List.of())))
                .toList();
    }

    /** Current number of retained samples. Exposed for tests / debugging. */
    public int size() {
        return samples.size();
    }

    private static String formatFrame(RecordedFrame frame) {
        RecordedMethod method = frame.getMethod();
        if (method == null) return "<unknown>";
        String type = method.getType() != null ? method.getType().getName() : "";
        String name = method.getName();
        if (type.isEmpty()) return (name == null || name.isEmpty()) ? "<unknown>" : name;
        return type + "." + name;
    }

    /** Internal sample record. */
    private record Sample(Instant at, String topFrame, List<String> stack) {}

    /** Response shape. {@code pct} is sample share within the queried window. */
    public record TopFrame(String frame, long samples, double pct, List<String> sampleStack) {}
}