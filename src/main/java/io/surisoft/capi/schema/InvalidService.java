package io.surisoft.capi.schema;

import java.time.Instant;

public record InvalidService(
        String serviceId,
        String group,
        String openApiEndpoint,
        Reason reason,
        String detail,
        Instant detectedAt
) {
    public enum Reason { OPENAPI_FETCH_FAILED, OPENAPI_INVALID_SPEC, OPENAPI_VERSION_MISMATCH,
        /** Spec fetched and parsed, but its info.title does not identify this service. */
        OPENAPI_IDENTITY_MISMATCH,
        /**
         * A webdav service registered more than one instance without asserting
         * {@code webdav-shared-state}. CAPI round-robins across instances and cannot replicate files,
         * so with independent storage a PUT to one is absent from the next and a LOCK on one is
         * unknown to the others — which corrupts files rather than failing cleanly.
         */
        WEBDAV_UNSHARED_MULTI_INSTANCE,
        /**
         * Two or more webdav services declared the same {@code webdav-host}. Only one can own a
         * hostname, and resolving it by map iteration order would silently serve one service's
         * collection from another's backend, so all but one are refused.
         */
        WEBDAV_HOST_COLLISION,
        /**
         * Consul answered the lookup, but the registration cannot be used as registered: a meta
         * value of the wrong type, a 4xx such as an ACL refusal, or a malformed
         * {@code capi-instance-*} key. Keyed by service name, without a group, because the group
         * may be exactly what could not be read. A route the service already had is kept.
         */
        CONSUL_INVALID_REGISTRATION }
}