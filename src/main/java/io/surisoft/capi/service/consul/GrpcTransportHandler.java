package io.surisoft.capi.service.consul;

import io.surisoft.capi.schema.GrpcClient;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.State;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.GrpcUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class GrpcTransportHandler implements TransportHandler {

    private static final Logger log = LoggerFactory.getLogger(GrpcTransportHandler.class);

    private final boolean enabled;
    private final Map<String, GrpcClient> grpcClientMap;
    private final GrpcUtils grpcUtils;

    public GrpcTransportHandler(boolean enabled,
                                Map<String, GrpcClient> grpcClientMap,
                                GrpcUtils grpcUtils) {
        this.enabled = enabled;
        this.grpcClientMap = grpcClientMap;
        this.grpcUtils = grpcUtils;
    }

    @Override
    public boolean supports(Service service) {
        if (!enabled || grpcClientMap == null || grpcUtils == null) {
            return false;
        }
        return Constants.GRPC_TYPE.equalsIgnoreCase(service.getServiceMeta().getType());
    }

    @Override
    public void onAppear(Service service) {
        if (!isPublished(service)) {
            return;
        }
        GrpcClient client = grpcUtils.createGrpcClient(service);
        if (client != null) {
            grpcClientMap.put(client.getServiceId(), client);
            log.info("gRPC client registered: {}", client.getServiceId());
        }
    }

    @Override
    public void onChange(Service oldSvc, Service newSvc) {
        grpcClientMap.remove(oldSvc.getContext());
        if (isPublished(newSvc)) {
            GrpcClient client = grpcUtils.createGrpcClient(newSvc);
            if (client != null) {
                grpcClientMap.put(client.getServiceId(), client);
                log.info("gRPC client re-registered: {}", client.getServiceId());
            }
        }
    }

    @Override
    public void onDisappear(Service service) {
        if (grpcClientMap.remove(service.getContext()) != null) {
            log.info("gRPC client removed: {}", service.getContext());
        }
    }

    private boolean isPublished(Service service) {
        return service.getServiceMeta().getState() == null
                || service.getServiceMeta().getState().equals(State.PUBLISHED);
    }
}