package io.surisoft.capi.utils;

import io.surisoft.capi.schema.GrpcClient;
import io.surisoft.capi.schema.HttpProtocol;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.undertow.CAPILoadBalancerProxyClient;
import io.surisoft.capi.undertow.CAPIProxyHandler;
import io.undertow.UndertowOptions;
import io.undertow.protocols.ssl.UndertowXnioSsl;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.ResponseCodeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.OptionMap;
import org.xnio.Xnio;
import org.xnio.ssl.XnioSsl;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.Base64;

public class GrpcUtils {

    private static final Logger log = LoggerFactory.getLogger(GrpcUtils.class);
    private final boolean capiTrustStoreEnabled;
    private final String capiTrustStorePath;
    private final String capiTrustStorePassword;
    private final String capiTrustStoreEncoded;
    private XnioSsl xnioSsl;

    public GrpcUtils(boolean capiTrustStoreEnabled,
                     String capiTrustStorePath,
                     String capiTrustStorePassword,
                     String capiTrustStoreEncoded) {
        this.capiTrustStoreEnabled = capiTrustStoreEnabled;
        this.capiTrustStorePath = capiTrustStorePath;
        this.capiTrustStorePassword = capiTrustStorePassword;
        this.capiTrustStoreEncoded = capiTrustStoreEncoded;

        if(capiTrustStoreEnabled) {
            this.xnioSsl = createXnioSsl();
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
            if(capiTrustStoreEnabled) {
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

    public XnioSsl createXnioSsl() {
        try {
            KeyStore trustStore = KeyStore.getInstance("JKS");
            if(capiTrustStoreEncoded != null && !capiTrustStoreEncoded.isEmpty()) {
                InputStream trustStoreInputStream = new ByteArrayInputStream(Base64.getDecoder().decode(capiTrustStoreEncoded.getBytes()));
                trustStore.load(trustStoreInputStream, this.capiTrustStorePassword.toCharArray());
            } else {
                FileInputStream trustStoreFile = new FileInputStream(capiTrustStorePath);
                trustStore.load(trustStoreFile, capiTrustStorePassword.toCharArray());
            }

            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagerFactory.getTrustManagers(), null);

            Xnio xnio = Xnio.getInstance();
            OptionMap optionMap = OptionMap.EMPTY;
            return new UndertowXnioSsl(xnio, optionMap, sslContext);
        } catch (NoSuchAlgorithmException | KeyStoreException | IOException | CertificateException |
                 KeyManagementException e) {
            log.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }
}
