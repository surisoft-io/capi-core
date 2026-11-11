package io.surisoft.capi.service.consul;

import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.schema.ConsulObject;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What one Consul host answered in one cycle.
 *
 * @param failedNames           every service whose lookup failed on this host, for any reason
 * @param transportFailures     how many of those failed because the host could not be asked —
 *                              timeout, connection error, Consul 5xx, abandoned on the host budget
 * @param invalidRegistrations  service name → why its registration cannot be used: Consul
 *                              answered, but with data CAPI cannot read (bad meta value, a 4xx such
 *                              as an ACL 403, a malformed {@code capi-instance-*} key)
 */
public record HostResult(
        CAPIConfiguration.HostConfig host,
        Set<String> catalogNames,
        Map<String, List<ConsulObject>> perServiceObjects,
        Set<String> emptyFromConsul,
        int failedServiceLookups,
        boolean catalogFetchFailed,
        Set<String> failedNames,
        int transportFailures,
        Map<String, String> invalidRegistrations
) {
    public static HostResult failed(CAPIConfiguration.HostConfig host) {
        return new HostResult(host, Set.of(), Map.of(), Set.of(), 0, true, Set.of(), 0, Map.of());
    }

    public boolean clean() {
        return !catalogFetchFailed && failedServiceLookups == 0;
    }
}
