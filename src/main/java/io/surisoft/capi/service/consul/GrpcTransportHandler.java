package io.surisoft.capi.service.consul;

import io.surisoft.capi.schema.GrpcClient;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.State;
import io.surisoft.capi.undertow.CAPILoadBalancerProxyClient;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.GrpcUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class GrpcTransportHandler implements TransportHandler {

    private static final Logger log = LoggerFactory.getLogger(GrpcTransportHandler.class);

    /** Grace basis when the service declares no responseTimeout. Streaming gRPC calls are long-lived,
     *  but the drain only sweeps idle backend connections and marks the pool closed (live streams are
     *  untouched and close-not-re-pool), so this need only cover a normal unary call. */
    private static final long DEFAULT_MAX_REQUEST_TIME = 180_000L;

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
        GrpcClient removed = grpcClientMap.remove(oldSvc.getContext());
        drainOldClient(removed, oldSvc);
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
        GrpcClient removed = grpcClientMap.remove(service.getContext());
        if (removed != null) {
            drainOldClient(removed, service);
            log.info("gRPC client removed: {}", service.getContext());
        }
    }

    /** Reclaim the orphaned gRPC client's backend sockets so they don't leak file descriptors
     *  (mirrors RestTransportHandler; no-op if the handler isn't a CAPILoadBalancerProxyClient). */
    private void drainOldClient(GrpcClient removed, Service svc) {
        if (removed == null) {
            return;
        }
        long maxRequestTime = (svc.getServiceMeta() != null && svc.getServiceMeta().getResponseTimeout() > 0)
                ? svc.getServiceMeta().getResponseTimeout()
                : DEFAULT_MAX_REQUEST_TIME;
        CAPILoadBalancerProxyClient.drainHandler(removed.getHttpHandler(), maxRequestTime);
    }

    private boolean isPublished(Service service) {
        return service.getServiceMeta().getState() == null
                || service.getServiceMeta().getState().equals(State.PUBLISHED);
    }
}