package io.surisoft.capi.undertow;

import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.proxy.LoadBalancingProxyClient;
import io.undertow.util.HttpString;


public class CAPILoadBalancerProxyClient extends LoadBalancingProxyClient {

    //private final CapiUndertowTracer capiUndertowTracer;
    private volatile String selectedHost;

   // public CAPILoadBalancerProxyClient(CapiUndertowTracer capiUndertowTracer) {
   //     this.capiUndertowTracer = capiUndertowTracer;
   // }

    public Host selectHost(HttpServerExchange exchange) {
        Host host = super.selectHost(exchange);
        if(host != null) {
            selectedHost = host.getUri().getHost();
            exchange.getRequestHeaders().put(HttpString.tryFromString("CapiSelectedHost"), host.getUri().getHost());
            //if(capiUndertowTracer != null) {
            //    capiUndertowTracer.capiProxyRequest(host.getUri());
            //}
            return host;
        }
        //no available hosts
        return null;
    }

    public String getSelectedHost() {
        return selectedHost;
    }
}