package io.surisoft.capi.service.consul;

import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.State;
import io.surisoft.capi.schema.WebsocketClient;
import io.surisoft.capi.undertow.CAPILoadBalancerProxyClient;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.WebsocketUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class WebsocketTransportHandler implements TransportHandler {

    private static final Logger log = LoggerFactory.getLogger(WebsocketTransportHandler.class);

    /** Grace basis when the service declares no responseTimeout. WS/SSE tunnels are long-lived, but
     *  the drain only sweeps idle backend connections and marks the pool closed (live tunnels are
     *  untouched and close-not-re-pool), so this need only cover a normal upgrade handshake. */
    private static final long DEFAULT_MAX_REQUEST_TIME = 180_000L;

    private final boolean enabled;
    private final Map<String, WebsocketClient> websocketClientMap;
    private final WebsocketUtils websocketUtils;

    public WebsocketTransportHandler(boolean enabled,
                                     Map<String, WebsocketClient> websocketClientMap,
                                     WebsocketUtils websocketUtils) {
        this.enabled = enabled;
        this.websocketClientMap = websocketClientMap;
        this.websocketUtils = websocketUtils;
    }

    @Override
    public boolean supports(Service service) {
        if (!enabled || websocketClientMap == null || websocketUtils == null) {
            return false;
        }
        String type = service.getServiceMeta().getType();
        return Constants.WEBSOCKET_TYPE.equalsIgnoreCase(type)
                || Constants.SSE_TYPE.equalsIgnoreCase(type);
    }

    @Override
    public void onAppear(Service service) {
        if (!isPublished(service)) {
            return;
        }
        WebsocketClient client = websocketUtils.createWebsocketClient(service);
        if (client != null) {
            websocketClientMap.put(client.getServiceId(), client);
            log.info("WebSocket/SSE client registered: {}", client.getServiceId());
        }
    }

    @Override
    public void onChange(Service oldSvc, Service newSvc) {
        WebsocketClient removed = websocketClientMap.remove(oldSvc.getContext());
        drainOldClient(removed, oldSvc);
        if (isPublished(newSvc)) {
            WebsocketClient client = websocketUtils.createWebsocketClient(newSvc);
            if (client != null) {
                websocketClientMap.put(client.getServiceId(), client);
                log.info("WebSocket/SSE client re-registered: {}", client.getServiceId());
            }
        }
    }

    @Override
    public void onDisappear(Service service) {
        WebsocketClient removed = websocketClientMap.remove(service.getContext());
        if (removed != null) {
            drainOldClient(removed, service);
            log.info("WebSocket/SSE client removed: {}", service.getContext());
        }
    }

    /** Reclaim the orphaned WS/SSE client's backend sockets so they don't leak file descriptors
     *  (mirrors RestTransportHandler; no-op if the handler isn't a CAPILoadBalancerProxyClient). */
    private void drainOldClient(WebsocketClient removed, Service svc) {
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