package io.surisoft.capi.undertow;

import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.proxy.LoadBalancingProxyClient;
import io.undertow.util.AttachmentKey;
import io.undertow.util.HttpString;


public class CAPILoadBalancerProxyClient extends LoadBalancingProxyClient {

    public static final AttachmentKey<String> SELECTED_HOST_KEY = AttachmentKey.create(String.class);

    public Host selectHost(HttpServerExchange exchange) {
        Host host = super.selectHost(exchange);
        if(host != null) {
            String hostName = host.getUri().getHost();
            exchange.putAttachment(SELECTED_HOST_KEY, hostName);
            exchange.getRequestHeaders().put(HttpString.tryFromString("CapiSelectedHost"), hostName);
            return host;
        }
        //no available hosts
        return null;
    }

    public static String getSelectedHost(HttpServerExchange exchange) {
        return exchange.getAttachment(SELECTED_HOST_KEY);
    }
}
