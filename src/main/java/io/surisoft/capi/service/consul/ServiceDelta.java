package io.surisoft.capi.service.consul;

import io.surisoft.capi.schema.Service;

import java.util.List;

public record ServiceDelta(
        List<Service> added,
        List<ChangedPair> changed,
        List<Service> gone
) {
    public record ChangedPair(Service oldSvc, Service newSvc) {}

    public static ServiceDelta empty() {
        return new ServiceDelta(List.of(), List.of(), List.of());
    }

    public int total() {
        return added.size() + changed.size() + gone.size();
    }
}