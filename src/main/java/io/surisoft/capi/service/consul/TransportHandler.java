package io.surisoft.capi.service.consul;

import io.surisoft.capi.schema.Service;

public interface TransportHandler {

    boolean supports(Service service);

    void onAppear(Service service);

    void onChange(Service oldSvc, Service newSvc);

    void onDisappear(Service service);
}