package io.surisoft.capi.observability;

import jdk.jfr.FlightRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class JvmObservabilityTest {

    private JvmObservability observability;

    @AfterEach
    void tearDown() {
        if (observability != null) {
            observability.stop();
        }
    }

    private static int openRecordings() {
        return FlightRecorder.getFlightRecorder().getRecordings().size();
    }

    @Test
    void disabled_startIsNoOp() {
        observability = new JvmObservability(false, Duration.ofMillis(20), 100);
        int before = openRecordings();

        observability.start();

        assertFalse(observability.isEnabled());
        assertEquals(before, openRecordings(), "a disabled instance must not open a JFR recording");
    }

    @Test
    void start_thenStop_opensAndClosesRecording() {
        observability = new JvmObservability(true, Duration.ofMillis(20), 100);
        int before = openRecordings();

        observability.start();
        assertEquals(before + 1, openRecordings());

        observability.start();
        assertEquals(before + 1, openRecordings(), "a second start() must not open another recording");

        observability.stop();
        assertEquals(before, openRecordings());

        assertDoesNotThrow(observability::stop, "stop() must be safe to call twice");
    }

    @Test
    void startFailure_closesPartiallyCreatedStream() {
        // A null period makes withPeriod() throw after the RecordingStream has been created,
        // which is the window where the stream used to leak.
        observability = new JvmObservability(true, null, 100);
        int before = openRecordings();

        assertDoesNotThrow(observability::start);

        assertEquals(before, openRecordings(), "the partially created recording must be closed");
    }
}