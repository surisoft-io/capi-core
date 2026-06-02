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
    public enum Reason { OPENAPI_FETCH_FAILED, OPENAPI_INVALID_SPEC }
}