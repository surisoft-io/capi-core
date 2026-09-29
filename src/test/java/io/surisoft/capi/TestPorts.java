package io.surisoft.capi;

import java.io.IOException;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hands out listen ports for tests that start a real server.
 *
 * <p>Replaces two racy patterns that made the suite fail intermittently — enough to block a release
 * now that CI gates on it:
 *
 * <ul>
 *   <li>{@code 19100 + random(100)} with no bind check. Across the ~70 tests in a single class the
 *       birthday paradox makes a repeat near-certain, and a test then talks to a server another test
 *       left listening. Symptoms were a garbled HTTP status line, or a response missing headers the
 *       test expected.</li>
 *   <li>{@code new ServerSocket(0)} closed before the server binds. The OS hands ephemeral ports out
 *       roughly in sequence, so the next caller frequently gets the port just released, and a socket
 *       still in {@code TIME_WAIT} can be bound again while a stale connection lingers.</li>
 * </ul>
 *
 * <p>This allocator never returns the same port twice in a JVM, which removes the whole
 * collide-with-another-test class. The range sits above the usual ephemeral range so the OS does not
 * hand the same numbers to unrelated sockets, and each candidate is probed before being handed out so
 * a port held by another process is skipped.
 *
 * <p>A window remains between the probe and the caller's own bind, and nothing can close that without
 * every caller retrying at its own bind site. It is now narrow and no longer self-inflicted.
 */
public final class TestPorts {

    private static final int RANGE_START = 20000;
    private static final int RANGE_END = 32000;   // below Linux's default ephemeral floor of 32768
    private static final int MAX_ATTEMPTS = 500;

    /** Random base so two JVMs (forked surefire, or a parallel local run) rarely start in the same place. */
    private static final AtomicInteger NEXT =
            new AtomicInteger(RANGE_START + new SecureRandom().nextInt(RANGE_END - RANGE_START - MAX_ATTEMPTS));

    private TestPorts() {
    }

    /** A port no other test in this JVM has been given, verified bindable at this instant. */
    public static int next() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            int port = NEXT.getAndIncrement();
            if (port >= RANGE_END) {
                NEXT.set(RANGE_START);
                continue;
            }
            try (ServerSocket probe = new ServerSocket(port)) {
                return probe.getLocalPort();
            } catch (IOException taken) {
                // Held by something outside this JVM — skip it and move on.
            }
        }
        throw new IllegalStateException(
                "No free port found in " + RANGE_START + "-" + RANGE_END + " after " + MAX_ATTEMPTS + " attempts");
    }
}
