package io.surisoft.capi.service.consul;

import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.schema.ConsulObject;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record HostResult(
        CAPIConfiguration.HostConfig host,
        Set<String> catalogNames,
        Map<String, List<ConsulObject>> perServiceObjects,
        Set<String> emptyFromConsul,
        int failedServiceLookups,
        boolean catalogFetchFailed
) {
    public static HostResult failed(CAPIConfiguration.HostConfig host) {
        return new HostResult(host, Set.of(), Map.of(), Set.of(), 0, true);
    }

    public boolean clean() {
        return !catalogFetchFailed && failedServiceLookups == 0;
    }
}