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
        WEBDAV_UNSHARED_MULTI_INSTANCE }
}