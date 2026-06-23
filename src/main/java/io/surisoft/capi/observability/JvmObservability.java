package io.surisoft.capi.observability;

import jdk.jfr.consumer.RecordingStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Owns the JFR {@link RecordingStream} that feeds in-memory aggregators for the
 * {@code /info/jvm/*} admin endpoints. Subscribes to a small, fixed set of JFR
 * events — currently just {@code jdk.ExecutionSample} for the CPU profile view.
 *
 * <p>Lifecycle: created at startup if {@code capi.observability.jvm.enabled}, and
 * {@code start()} called once {@link AdminGateway} is wired. {@code stop()} is
 * invoked from the JVM shutdown hook. Safe to call {@code stop()} multiple
 * times.
 *
 * <p>Overhead: {@code jdk.ExecutionSample} at a 20 ms period is roughly the same
 * cost as a continuous {@code -XX:StartFlightRecording=settings=profile} run.
 * Typically &lt;1% CPU on a modern JVM.
 */
public class JvmObservability {

    private static final Logger log = LoggerFactory.getLogger(JvmObservability.class);

    private final boolean enabled;
    private final Duration sampleInterval;
    private final ProfileSampler profileSampler;

    private volatile RecordingStream stream;

    public JvmObservability(boolean enabled, Duration sampleInterval, int retentionSamples) {
        this.enabled = enabled;
        this.sampleInterval = sampleInterval;
        this.profileSampler = new ProfileSampler(retentionSamples, 10);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public ProfileSampler getProfileSampler() {
        return profileSampler;
    }

    /** Starts the JFR subscription. No-op if {@code enabled} is false. */
    public synchronized void start() {
        if (!enabled) {
            log.info("JVM observability disabled (capi.observability.jvm.enabled=false)");
            return;
        }
        if (stream != null) {
            return;
        }
        try {
            RecordingStream s = new RecordingStream();
            s.enable("jdk.ExecutionSample").withPeriod(sampleInterval);
            s.onEvent("jdk.ExecutionSample", profileSampler::onSample);
            s.startAsync();
            this.stream = s;
            log.info("JVM observability started: ExecutionSample period={}ms, retention={} samples",
                    sampleInterval.toMillis(), profileSampler.size());
        } catch (Exception e) {
            log.warn("Failed to start JVM observability: {}", e.getMessage(), e);
        }
    }

    /** Stops the JFR subscription. Safe to call when not started. */
    public synchronized void stop() {
        RecordingStream s = stream;
        if (s == null) return;
        try {
            s.close();
        } catch (Exception e) {
            log.warn("Error closing JVM observability stream: {}", e.getMessage());
        } finally {
            stream = null;
        }
    }
}