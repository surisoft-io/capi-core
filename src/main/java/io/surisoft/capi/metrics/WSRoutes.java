package io.surisoft.capi.metrics;

import io.surisoft.capi.schema.WebsocketClient;

import java.util.Map;

public class WSRoutes {

    private final Map<String, WebsocketClient> websocketClientMap;

    public WSRoutes(Map<String, WebsocketClient> websocketClientMap) {
        this.websocketClientMap = websocketClientMap;
    }

    public Map<String, WebsocketClient> getAllWebsocketRoutesInfo() {
        if(websocketClientMap == null) return null;
        return websocketClientMap;
    }
}