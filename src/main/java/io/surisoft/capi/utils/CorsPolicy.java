package io.surisoft.capi.utils;

import jakarta.annotation.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which browser origins CAPI answers with CORS headers, from {@code capi.allowedOrigins}.
 *
 * <p>Reflecting whatever {@code Origin} the caller sent while also sending
 * {@code Access-Control-Allow-Credentials: true} lets any web page read authenticated responses
 * from CAPI, so an origin now has to be on the list to get any CORS header at all. An empty or
 * absent list denies every origin — the safe default for a gateway that has no browser clients.
 *
 * <p>{@code "*"} is accepted as an explicit opt-out of the allowlist. It emits the literal
 * wildcard and never {@code Allow-Credentials}, because a credentialed wildcard is rejected by
 * every browser anyway; treating it as "reflect the caller's origin" is what made the original
 * behaviour unsafe.
 */
public final class CorsPolicy {

    private static final CorsPolicy DENY_ALL = new CorsPolicy(List.of());
    private static final String WILDCARD = "*";

    private final Set<String> allowedOrigins;
    private final boolean wildcard;

    public CorsPolicy(@Nullable List<String> allowedOrigins) {
        Set<String> normalized = new LinkedHashSet<>();
        boolean anyOrigin = false;
        if (allowedOrigins != null) {
            for (String origin : allowedOrigins) {
                if (origin == null || origin.isBlank()) {
                    continue;
                }
                String trimmed = origin.trim();
                if (WILDCARD.equals(trimmed)) {
                    anyOrigin = true;
                } else {
                    normalized.add(normalize(trimmed));
                }
            }
        }
        this.allowedOrigins = Set.copyOf(normalized);
        this.wildcard = anyOrigin;
    }

    /** No origin is allowed. Used wherever a policy has not been configured. */
    public static CorsPolicy denyAll() {
        return DENY_ALL;
    }

    /**
     * A policy from a comma-separated Consul ServiceMeta value ({@code allowed-origins}), or
     * {@code null} when the service declared none — which means "inherit the gateway default"
     * rather than "deny", so a service owner opts in by registering the key.
     */
    public static @Nullable CorsPolicy fromCsv(@Nullable String csv) {
        if (csv == null || csv.isBlank()) {
            return null;
        }
        return new CorsPolicy(List.of(csv.split(",")));
    }

    /** True when nothing is allowed, so callers can skip the CORS block entirely. */
    public boolean isDenyAll() {
        return !wildcard && allowedOrigins.isEmpty();
    }

    /**
     * The {@code Access-Control-Allow-Origin} value for this request, or {@code null} when the
     * origin is not allowed and no CORS header should be sent.
     */
    public @Nullable String resolveAllowOrigin(@Nullable String requestOrigin) {
        if (requestOrigin == null || requestOrigin.isBlank()) {
            return null;
        }
        if (isAllowedOrigin(requestOrigin)) {
            return requestOrigin.trim();
        }
        return wildcard ? WILDCARD : null;
    }

    /**
     * Credentials are only ever offered to an explicitly listed origin. A wildcard response must
     * not carry them: the pair is invalid per the Fetch standard and browsers drop the response.
     */
    public boolean allowsCredentials(@Nullable String requestOrigin) {
        return isAllowedOrigin(requestOrigin);
    }

    private boolean isAllowedOrigin(@Nullable String requestOrigin) {
        return requestOrigin != null
                && !requestOrigin.isBlank()
                && allowedOrigins.contains(normalize(requestOrigin.trim()));
    }

    /**
     * Origins are compared case-insensitively and without a trailing slash. A browser always
     * sends the ASCII-serialized form, so this only forgives operator typos in config.
     */
    private static String normalize(String origin) {
        String value = origin.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
