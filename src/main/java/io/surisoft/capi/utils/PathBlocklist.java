package io.surisoft.capi.utils;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.undertow.server.HttpServerExchange;
import jakarta.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Emergency path blocklist ({@code capi.blockedPaths}): refuses any request whose path contains one of
 * the configured segment sequences, on every listener that forwards a client path to a backend.
 *
 * <p>Meant for incidents — a backend exposing something it should not, until its owner fixes it — not
 * as a policy layer. Rules are read once at startup; changing them means a restart.
 *
 * <p>A rule matches <em>whole segments, anywhere in the path</em>, case-insensitively. Through CAPI a
 * backend path arrives as {@code /<service>/<group>/actuator/...}, so a prefix match would never fire.
 * {@code /actuator} matches {@code /svc/v1/actuator} and {@code /svc/v1/actuator/env}, but not
 * {@code /svc/v1/actuators}. A multi-segment rule such as {@code /actuator/env} matches those segments
 * consecutively.
 *
 * <p>The path is canonicalised before matching, because a blocklist that a different spelling walks
 * past is worse than none: percent-decoding (repeated, so double encoding does not slip through),
 * {@code \} treated as a separator, {@code ;parameters} stripped from each segment, empty and
 * {@code .} segments dropped and {@code ..} resolved. Over-matching is the safe direction here.
 */
public final class PathBlocklist {

    public static final PathBlocklist EMPTY = new PathBlocklist(List.of());

    /** Enough for any encoding a backend would plausibly undo; bounds the work on hostile input. */
    private static final int MAX_DECODE_PASSES = 3;

    private final List<Rule> rules;

    private record Rule(String configured, List<String> segments) {}

    private PathBlocklist(List<Rule> rules) {
        this.rules = rules;
    }

    /**
     * Builds the blocklist from configuration, refusing anything ambiguous so that a typo fails startup
     * instead of silently blocking nothing — or everything.
     *
     * @throws IllegalArgumentException when a rule is blank, resolves to the root, or uses wildcard,
     *                                  query, fragment or parameter syntax this matcher does not support
     */
    public static PathBlocklist of(@Nullable List<String> configured) {
        if (configured == null || configured.isEmpty()) {
            return EMPTY;
        }
        List<Rule> rules = new ArrayList<>();
        for (String rule : configured) {
            if (rule == null || rule.isBlank()) {
                throw new IllegalArgumentException("capi.blockedPaths contains a blank rule");
            }
            for (char unsupported : new char[] {'*', '?', '#', ';', '%', '\\'}) {
                if (rule.indexOf(unsupported) >= 0) {
                    throw new IllegalArgumentException("capi.blockedPaths rule '" + rule
                            + "' contains '" + unsupported + "'; rules are plain path segments, e.g. /actuator");
                }
            }
            List<String> segments = new ArrayList<>();
            for (String segment : rule.trim().toLowerCase(Locale.ROOT).split("/")) {
                if (segment.isEmpty()) {
                    continue;
                }
                if (segment.equals(".") || segment.equals("..")) {
                    throw new IllegalArgumentException("capi.blockedPaths rule '" + rule + "' contains a dot segment");
                }
                segments.add(segment);
            }
            if (segments.isEmpty()) {
                throw new IllegalArgumentException("capi.blockedPaths rule '" + rule
                        + "' has no path segment and would block every request");
            }
            rules.add(new Rule(rule.trim(), List.copyOf(segments)));
        }
        return new PathBlocklist(Collections.unmodifiableList(rules));
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /** The rules as configured, for the startup log. */
    public List<String> describe() {
        return rules.stream().map(Rule::configured).toList();
    }

    /**
     * The rule the request matches, or null. Checks both the raw request URI — what CAPI forwards — and
     * the path Undertow decoded, so neither spelling can be used to slip past the other.
     */
    @Nullable
    public String match(HttpServerExchange exchange) {
        if (rules.isEmpty()) {
            return null;
        }
        String rule = match(exchange.getRequestURI());
        return rule != null ? rule : match(exchange.getRequestPath());
    }

    /** The rule {@code path} matches, or null. */
    @Nullable
    public String match(@Nullable String path) {
        if (rules.isEmpty() || path == null || path.isEmpty()) {
            return null;
        }
        List<String> segments = canonicalSegments(path);
        for (Rule rule : rules) {
            if (Collections.indexOfSubList(segments, rule.segments()) >= 0) {
                return rule.configured();
            }
        }
        return null;
    }

    /**
     * Counts a blocked request. Tags are bounded: {@code rule} by configuration, {@code service} by the
     * registered routes, {@code listener} by code.
     */
    public static void count(@Nullable MeterRegistry meterRegistry, String listener, String rule, @Nullable String service) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("capi_blocked_requests_total")
                .tag("listener", listener)
                .tag("rule", rule)
                .tag("service", service != null ? service : "unrouted")
                .register(meterRegistry)
                .increment();
    }

    static List<String> canonicalSegments(String path) {
        String p = path;
        int cut = indexOfAny(p, '?', '#');
        if (cut >= 0) {
            p = p.substring(0, cut);
        }
        for (int i = 0; i < MAX_DECODE_PASSES && p.indexOf('%') >= 0; i++) {
            String decoded = percentDecode(p);
            if (decoded.equals(p)) {
                break;
            }
            p = decoded;
        }
        p = p.replace('\\', '/').toLowerCase(Locale.ROOT);

        List<String> segments = new ArrayList<>();
        for (String segment : p.split("/")) {
            int params = segment.indexOf(';');
            if (params >= 0) {
                segment = segment.substring(0, params);
            }
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (!segments.isEmpty()) {
                    segments.removeLast();
                }
                continue;
            }
            segments.add(segment);
        }
        return segments;
    }

    /** Lenient: a malformed escape is kept literally rather than failing the request. */
    private static String percentDecode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '%' && i + 2 < bytes.length) {
                int hi = Character.digit(bytes[i + 1], 16);
                int lo = Character.digit(bytes[i + 2], 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) + lo);
                    i += 2;
                    continue;
                }
            }
            out.write(bytes[i]);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static int indexOfAny(String s, char a, char b) {
        int ia = s.indexOf(a);
        int ib = s.indexOf(b);
        if (ia < 0) return ib;
        if (ib < 0) return ia;
        return Math.min(ia, ib);
    }
}
