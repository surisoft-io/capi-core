package io.surisoft.capi.service.consul;

import io.surisoft.capi.schema.ConsulObject;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One discovery cycle across every Consul host.
 *
 * @param failedNames           services whose lookup failed on at least one host; their routes are
 *                              preserved by cleanup rather than removed
 * @param transportFailures     failed lookups where a host could not be asked (see {@link HostResult})
 * @param invalidRegistrations  service name → why its registration cannot be used
 */
public record CycleResult(
        Set<String> catalogUnion,
        Map<String, List<ConsulObject>> reconciledObjects,
        Set<String> emptyFromConsul,
        int totalFailedLookups,
        int hostsFailed,
        int hostsTotal,
        Set<String> failedNames,
        int transportFailures,
        Map<String, String> invalidRegistrations
) {
    /** Nothing failed at all. */
    public boolean clean() {
        return hostsFailed == 0 && totalFailedLookups == 0;
    }

    /**
     * Every host's catalog was read, so the set of service names is complete and cleanup can
     * remove what is gone — preserving only the services in {@link #failedNames}.
     */
    public boolean catalogComplete() {
        return hostsFailed == 0;
    }

    /**
     * The node's view of Consul is complete enough to report ready: every catalog read, and every
     * lookup answered. A registration Consul answered with unusable data is that service's problem
     * and does not count; a lookup that could not be made does.
     */
    public boolean readyForTraffic() {
        return hostsFailed == 0 && transportFailures == 0;
    }
}
