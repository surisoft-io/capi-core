package io.surisoft.capi.observability;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProfileSamplerTest {

    @Test
    void empty_returnsEmptyTop() {
        ProfileSampler s = new ProfileSampler(100, 10);
        assertEquals(0, s.size());
        assertTrue(s.topByCpu(Duration.ofSeconds(60), 10).isEmpty());
    }

    @Test
    void aggregatesByTopFrame_andComputesPercentages() {
        ProfileSampler s = new ProfileSampler(100, 10);
        for (int i = 0; i < 7; i++) s.onSample(sampleEvent("hot.method", "caller.a", "caller.b"));
        for (int i = 0; i < 3; i++) s.onSample(sampleEvent("cool.method", "caller.x"));

        List<ProfileSampler.TopFrame> top = s.topByCpu(Duration.ofSeconds(60), 10);
        assertEquals(2, top.size());
        assertEquals("hot.method", top.get(0).frame());
        assertEquals(7, top.get(0).samples());
        assertEquals(70.0, top.get(0).pct());
        assertEquals("cool.method", top.get(1).frame());
        assertEquals(3, top.get(1).samples());
        assertEquals(30.0, top.get(1).pct());
    }

    @Test
    void respectsLimit() {
        ProfileSampler s = new ProfileSampler(100, 10);
        for (int i = 0; i < 5; i++) s.onSample(sampleEvent("a"));
        for (int i = 0; i < 4; i++) s.onSample(sampleEvent("b"));
        for (int i = 0; i < 3; i++) s.onSample(sampleEvent("c"));
        for (int i = 0; i < 2; i++) s.onSample(sampleEvent("d"));

        List<ProfileSampler.TopFrame> top = s.topByCpu(Duration.ofSeconds(60), 2);
        assertEquals(2, top.size());
        assertEquals("a", top.get(0).frame());
        assertEquals("b", top.get(1).frame());
    }

    @Test
    void filtersBySampleWindow() {
        ProfileSampler s = new ProfileSampler(100, 10);
        // "old" sample, well before the 1-second window
        s.onSample(sampleEventAt("ancient.method", Instant.now().minus(Duration.ofMinutes(10))));
        // recent samples inside the 1-second window
        for (int i = 0; i < 4; i++) s.onSample(sampleEvent("recent.method"));

        List<ProfileSampler.TopFrame> top = s.topByCpu(Duration.ofSeconds(1), 10);
        assertEquals(1, top.size());
        assertEquals("recent.method", top.get(0).frame());
        assertEquals(4, top.get(0).samples());
        assertEquals(100.0, top.get(0).pct());
    }

    @Test
    void ringBufferEvictsOldestWhenOverCapacity() {
        ProfileSampler s = new ProfileSampler(3, 10);
        s.onSample(sampleEvent("a"));
        s.onSample(sampleEvent("b"));
        s.onSample(sampleEvent("c"));
        s.onSample(sampleEvent("d"));
        // "a" should have been evicted
        assertEquals(3, s.size());
        List<ProfileSampler.TopFrame> top = s.topByCpu(Duration.ofSeconds(60), 10);
        assertEquals(3, top.size());
        assertTrue(top.stream().noneMatch(t -> "a".equals(t.frame())));
    }

    @Test
    void sampleStackIsCapturedForResponse() {
        ProfileSampler s = new ProfileSampler(100, 5);
        s.onSample(sampleEvent("hot.method", "caller.a", "caller.b", "caller.c"));

        List<ProfileSampler.TopFrame> top = s.topByCpu(Duration.ofSeconds(60), 10);
        assertEquals(1, top.size());
        assertEquals(List.of("hot.method", "caller.a", "caller.b", "caller.c"), top.get(0).sampleStack());
    }

    @Test
    void truncatesStackToMaxFrames() {
        ProfileSampler s = new ProfileSampler(100, 2);
        s.onSample(sampleEvent("top", "a", "b", "c", "d", "e"));

        List<ProfileSampler.TopFrame> top = s.topByCpu(Duration.ofSeconds(60), 10);
        assertEquals(List.of("top", "a"), top.get(0).sampleStack());
    }

    @Test
    void nullStackTrace_ignored() {
        ProfileSampler s = new ProfileSampler(100, 10);
        RecordedEvent e = mock(RecordedEvent.class);
        lenient().when(e.getStackTrace()).thenReturn(null);
        s.onSample(e);
        assertEquals(0, s.size());
    }

    @Test
    void emptyStackTrace_ignored() {
        ProfileSampler s = new ProfileSampler(100, 10);
        RecordedEvent e = mock(RecordedEvent.class);
        RecordedStackTrace st = mock(RecordedStackTrace.class);
        lenient().when(st.getFrames()).thenReturn(List.of());
        lenient().when(e.getStackTrace()).thenReturn(st);
        s.onSample(e);
        assertEquals(0, s.size());
    }

    @Test
    void nullMethod_renderedAsUnknown() {
        ProfileSampler s = new ProfileSampler(100, 10);
        RecordedFrame frame = mock(RecordedFrame.class);
        lenient().when(frame.getMethod()).thenReturn(null);
        RecordedStackTrace st = mock(RecordedStackTrace.class);
        lenient().when(st.getFrames()).thenReturn(List.of(frame));
        RecordedEvent e = mock(RecordedEvent.class);
        lenient().when(e.getStackTrace()).thenReturn(st);
        lenient().when(e.getStartTime()).thenReturn(Instant.now());
        s.onSample(e);

        List<ProfileSampler.TopFrame> top = s.topByCpu(Duration.ofSeconds(60), 10);
        assertEquals(1, top.size());
        assertEquals("<unknown>", top.get(0).frame());
    }

    // ---- helpers ----

    private static RecordedEvent sampleEvent(String topFrame, String... otherFrames) {
        return sampleEventAt(topFrame, Instant.now(), otherFrames);
    }

    private static RecordedEvent sampleEventAt(String topFrame, Instant at, String... otherFrames) {
        RecordedEvent e = mock(RecordedEvent.class);
        RecordedStackTrace st = mock(RecordedStackTrace.class);
        java.util.List<RecordedFrame> frames = new java.util.ArrayList<>();
        frames.add(frame(topFrame));
        for (String f : otherFrames) frames.add(frame(f));
        lenient().when(st.getFrames()).thenReturn(frames);
        lenient().when(e.getStackTrace()).thenReturn(st);
        lenient().when(e.getStartTime()).thenReturn(at);
        return e;
    }

    private static RecordedFrame frame(String fullyQualified) {
        int dot = fullyQualified.lastIndexOf('.');
        String type = (dot < 0) ? "" : fullyQualified.substring(0, dot);
        String name = (dot < 0) ? fullyQualified : fullyQualified.substring(dot + 1);

        RecordedFrame f = mock(RecordedFrame.class);
        RecordedMethod m = mock(RecordedMethod.class);
        jdk.jfr.consumer.RecordedClass cls = mock(jdk.jfr.consumer.RecordedClass.class);
        lenient().when(cls.getName()).thenReturn(type);
        lenient().when(m.getType()).thenReturn(cls);
        lenient().when(m.getName()).thenReturn(name);
        lenient().when(f.getMethod()).thenReturn(m);
        return f;
    }
}