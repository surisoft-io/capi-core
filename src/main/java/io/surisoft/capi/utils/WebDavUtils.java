package io.surisoft.capi.utils;

import io.surisoft.capi.configuration.CapiSslContextHolder;
import io.surisoft.capi.schema.HttpProtocol;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.WebDavClient;
import io.surisoft.capi.undertow.CAPILoadBalancerProxyClient;
import io.surisoft.capi.undertow.CAPIProxyHandler;
import io.undertow.protocols.ssl.UndertowXnioSsl;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.ResponseCodeHandler;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.OptionMap;
import org.xnio.Xnio;
import org.xnio.ssl.XnioSsl;

import java.net.URI;

/**
 * Builds the proxy handler fronting a WebDAV backend. Mirrors {@link GrpcUtils}, reusing
 * {@link CAPIProxyHandler} and {@link CAPILoadBalancerProxyClient} unmodified.
 *
 * <p>Two differences from the other transports, both deliberate:
 *
 * <ul>
 *   <li>a much larger request-time budget, because a large PUT or GET is the normal case here and
 *       the REST-scale timeout would kill an upload mid-flight;</li>
 *   <li>no HTTP/2 option map — WebDAV clients are overwhelmingly HTTP/1.1, and nothing in the
 *       protocol benefits from h2.</li>
 * </ul>
 */
public class WebDavUtils {

    private static final Logger log = LoggerFactory.getLogger(WebDavUtils.class);

    @Nullable
    private final CapiSslContextHolder capiSslContextHolder;
    private volatile XnioSsl xnioSsl;
    private final CAPILoadBalancerProxyClient.PoolSettings poolSettings;
    private final long maxRequestTime;

    public WebDavUtils(@Nullable CapiSslContextHolder capiSslContextHolder,
                       CAPILoadBalancerProxyClient.PoolSettings poolSettings,
                       long maxRequestTime) {
        this.capiSslContextHolder = capiSslContextHolder;
        this.poolSettings = poolSettings;
        this.maxRequestTime = maxRequestTime;
        if (capiSslContextHolder != null && capiSslContextHolder.getSslContext() != null) {
            this.xnioSsl = createXnioSsl(capiSslContextHolder.getSslContext());
        }
    }

    public HttpHandler createClientHttpHandler(WebDavClient webDavClient, Service service) {
        CAPILoadBalancerProxyClient loadBalancingProxyClient = new CAPILoadBalancerProxyClient();
        loadBalancingProxyClient.applyPoolSettings(poolSettings);
        webDavClient.getMappingList().forEach(m -> {
            String scheme = service.getServiceMeta().getScheme() == null
                    ? HttpProtocol.HTTP.getProtocol()
                    : service.getServiceMeta().getScheme();
            URI hostUri = URI.create(scheme + "://" + m.getHostname() + ":" + m.getPort());
            if (xnioSsl != null) {
                loadBalancingProxyClient.addHost(hostUri, null, xnioSsl, OptionMap.EMPTY);
            } else {
                loadBalancingProxyClient.addHost(hostUri, null, null, OptionMap.EMPTY);
            }
        });
        return CAPIProxyHandler
                .builder()
                .setProxyClient(loadBalancingProxyClient)
                .setConnectTimeout(poolSettings.connectTimeoutMs())
                .setMaxRequestTime((int) Math.min(maxRequestTime, Integer.MAX_VALUE))
                .setNext(ResponseCodeHandler.HANDLE_404)
                .build();
    }

    public WebDavClient createWebDavClient(Service service) {
        WebDavClient client = new WebDavClient();

        String rootContext = service.getMappingList().stream().toList().get(0).getRootContext();
        if (rootContext != null && !rootContext.isEmpty() && !rootContext.equals("/") && !rootContext.equals("*")) {
            client.setRootContext(rootContext);
        }

        client.setServiceId(service.getContext());
        client.setPath(service.getContext());
        client.setMappingList(service.getMappingList());
        client.setRequiresSubscription(service.getServiceMeta().isSecured());
        client.setSubscriptionRole(service.getServiceMeta().getSubscriptionGroup());
        client.setOpaRego(service.getServiceMeta().getOpaRego());
        client.setReadOnly(service.getServiceMeta().isWebdavReadOnly());

        String host = service.getServiceMeta().getWebdavHost();
        if (host != null && !host.isBlank()) {
            client.setWebdavHost(host.trim().toLowerCase());
        }

        client.setHttpHandler(createClientHttpHandler(client, service));
        return client;
    }

    private XnioSsl createXnioSsl(javax.net.ssl.SSLContext sslContext) {
        try {
            Xnio xnio = Xnio.getInstance();
            return new UndertowXnioSsl(xnio, OptionMap.EMPTY, sslContext);
        } catch (Exception e) {
            log.error("Failed to create XnioSsl for the WebDAV transport: {}", e.getMessage(), e);
            return null;
        }
    }
}
