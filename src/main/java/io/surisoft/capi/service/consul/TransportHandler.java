package io.surisoft.capi.service.consul;

import io.surisoft.capi.schema.Service;

public interface TransportHandler {

    boolean supports(Service service);

    void onAppear(Service service);

    void onChange(Service oldSvc, Service newSvc);

    void onDisappear(Service service);

    /**
     * Called once at the end of every Consul reconcile cycle, after all onAppear /
     * onChange / onDisappear callbacks have run. Handlers that publish a snapshot
     * of their state to readers (e.g. RestTransportHandler -> RestClientSnapshot)
     * use this as the commit point.
     */
    default void afterCycle() {}
}