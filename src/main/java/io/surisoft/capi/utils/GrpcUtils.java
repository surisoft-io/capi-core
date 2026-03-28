package io.surisoft.capi.utils;

import io.surisoft.capi.configuration.CapiSslContextHolder;
import io.surisoft.capi.schema.GrpcClient;
import io.surisoft.capi.schema.HttpProtocol;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.undertow.CAPILoadBalancerProxyClient;
import io.surisoft.capi.undertow.CAPIProxyHandler;
import io.undertow.UndertowOptions;
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

public class GrpcUtils {

    private static final Logger log = LoggerFactory.getLogger(GrpcUtils.class);
    @Nullable
    private final CapiSslContextHolder capiSslContextHolder;
    private volatile XnioSsl xnioSsl;

    public GrpcUtils(@Nullable CapiSslContextHolder capiSslContextHolder) {
        this.capiSslContextHolder = capiSslContextHolder;

        if(capiSslContextHolder != null && capiSslContextHolder.getSslContext() != null) {
            this.xnioSsl = createXnioSsl(capiSslContextHolder.getSslContext());
        }
    }

    public HttpHandler createClientHttpHandler(GrpcClient grpcClient, Service service) {
        CAPILoadBalancerProxyClient loadBalancingProxyClient = new CAPILoadBalancerProxyClient();
        OptionMap http2Options = OptionMap.create(UndertowOptions.ENABLE_HTTP2, true);
        grpcClient.getMappingList().forEach((m) -> {
            String configuredScheme = service.getServiceMeta().getScheme() == null ? HttpProtocol.HTTP.getProtocol() : service.getServiceMeta().getScheme();
            // gRPC requires HTTP/2. For plaintext, use h2c-prior (prior knowledge) instead of http.
            String scheme = configuredScheme.equalsIgnoreCase(HttpProtocol.HTTPS.getProtocol()) ? HttpProtocol.HTTPS.getProtocol() : "h2c-prior";
            URI hostUri = URI.create(scheme + "://" + m.getHostname() + ":" + m.getPort());
            if(xnioSsl != null) {
                loadBalancingProxyClient.addHost(hostUri, null, xnioSsl, http2Options);
            } else {
                loadBalancingProxyClient.addHost(hostUri, null, null, http2Options);
            }
        });
        return CAPIProxyHandler
                .builder()
                .setProxyClient(loadBalancingProxyClient)
                .setNext(ResponseCodeHandler.HANDLE_404)
                .build();
    }

    public GrpcClient createGrpcClient(Service service) {
        GrpcClient grpcClient = new GrpcClient();

        String rootContext = service.getMappingList().stream().toList().get(0).getRootContext();
        if(rootContext != null && !rootContext.isEmpty() && !rootContext.equals("/") && !rootContext.equals("*")) {
            grpcClient.setRootContext(rootContext);
        }

        grpcClient.setServiceId(service.getContext());
        grpcClient.setMappingList(service.getMappingList());
        grpcClient.setPath(service.getContext());
        grpcClient.setRequiresSubscription(service.getServiceMeta().isSecured());
        grpcClient.setHttpHandler(createClientHttpHandler(grpcClient, service));
        return grpcClient;
    }

    private XnioSsl createXnioSsl(javax.net.ssl.SSLContext sslContext) {
        try {
            Xnio xnio = Xnio.getInstance();
            return new UndertowXnioSsl(xnio, OptionMap.EMPTY, sslContext);
        } catch (Exception e) {
            log.error("Failed to create XnioSsl: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public void refreshXnioSsl() {
        if (capiSslContextHolder != null && capiSslContextHolder.getSslContext() != null) {
            log.info("Refreshing XnioSsl from updated SSLContext");
            this.xnioSsl = createXnioSsl(capiSslContextHolder.getSslContext());
        }
    }
}
