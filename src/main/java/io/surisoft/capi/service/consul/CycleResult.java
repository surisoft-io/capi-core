package io.surisoft.capi.service.consul;

import io.surisoft.capi.schema.ConsulObject;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record CycleResult(
        Set<String> catalogUnion,
        Map<String, List<ConsulObject>> reconciledObjects,
        Set<String> emptyFromConsul,
        int totalFailedLookups,
        int hostsFailed,
        int hostsTotal
) {
    public boolean clean() {
        return hostsFailed == 0 && totalFailedLookups == 0;
    }
}