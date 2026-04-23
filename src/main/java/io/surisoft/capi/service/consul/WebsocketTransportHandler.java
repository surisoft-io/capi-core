package io.surisoft.capi.service.consul;

import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.State;
import io.surisoft.capi.schema.WebsocketClient;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.WebsocketUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class WebsocketTransportHandler implements TransportHandler {

    private static final Logger log = LoggerFactory.getLogger(WebsocketTransportHandler.class);

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
        websocketUtils.removeClientFromMap(websocketClientMap, oldSvc);
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
        websocketUtils.removeClientFromMap(websocketClientMap, service);
        log.info("WebSocket/SSE client removed: {}", service.getContext());
    }

    private boolean isPublished(Service service) {
        return service.getServiceMeta().getState() == null
                || service.getServiceMeta().getState().equals(State.PUBLISHED);
    }
}