package io.surisoft.capi.undertow;

import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.proxy.ProxyCallback;
import io.undertow.server.handlers.proxy.ProxyConnection;
import io.undertow.server.handlers.proxy.LoadBalancingProxyClient;
import io.undertow.util.AttachmentKey;
import io.undertow.util.HttpString;
import org.xnio.IoFuture;

import java.util.concurrent.TimeUnit;


public class CAPILoadBalancerProxyClient extends LoadBalancingProxyClient {

    public static final AttachmentKey<String> SELECTED_HOST_KEY = AttachmentKey.create(String.class);
    public static final AttachmentKey<String> SELECTED_SCHEME_KEY = AttachmentKey.create(String.class);
    public static final AttachmentKey<Throwable> CONNECTION_ERROR_KEY = AttachmentKey.create(Throwable.class);

    public Host selectHost(HttpServerExchange exchange) {
        Host host = super.selectHost(exchange);
        if(host != null) {
            String hostName = host.getUri().getHost();
            exchange.putAttachment(SELECTED_HOST_KEY, hostName);
            exchange.putAttachment(SELECTED_SCHEME_KEY, host.getUri().getScheme());
            exchange.getRequestHeaders().put(HttpString.tryFromString("CapiSelectedHost"), hostName);
            return host;
        }
        //no available hosts
        return null;
    }

    public static String getSelectedHost(HttpServerExchange exchange) {
        return exchange.getAttachment(SELECTED_HOST_KEY);
    }

    @Override
    public void getConnection(ProxyTarget target, HttpServerExchange exchange, ProxyCallback<ProxyConnection> callback, long timeout, TimeUnit timeUnit) {
        try {
            super.getConnection(target, exchange, callback, timeout, timeUnit);
        } catch (Exception e) {
            // Capture the actual exception for error reporting
            exchange.putAttachment(CONNECTION_ERROR_KEY, e);
            callback.failed(exchange);
        }
    }
}
