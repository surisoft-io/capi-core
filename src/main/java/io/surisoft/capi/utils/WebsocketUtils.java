package io.surisoft.capi.utils;

import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.configuration.CapiSslContextHolder;
import io.surisoft.capi.exception.HttpErrorHandler;
import io.surisoft.capi.exception.CapiUndertowException;
import io.surisoft.capi.oidc.WebsocketAuthorization;
import io.surisoft.capi.schema.HttpProtocol;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.WebsocketClient;
import io.surisoft.capi.undertow.CAPILoadBalancerProxyClient;
import io.surisoft.capi.undertow.CAPIProxyHandler;
import io.undertow.protocols.ssl.UndertowXnioSsl;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.ResponseCodeHandler;
import io.undertow.util.HeaderValues;
import io.undertow.util.HttpString;
import jakarta.annotation.Nullable;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.OptionMap;
import org.xnio.Xnio;
import org.xnio.ssl.XnioSsl;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class WebsocketUtils {

    private static final Logger log = LoggerFactory.getLogger(WebsocketUtils.class);
    private final List<DefaultJWTProcessor<SecurityContext>> defaultJWTProcessor;
    @Nullable
    private final CapiSslContextHolder capiSslContextHolder;
    private volatile XnioSsl xnioSsl;
    private final CAPIConfiguration.Websocket websocketConfiguration;

    public WebsocketUtils(CAPIConfiguration.Websocket websocketConfiguration,
                          List<DefaultJWTProcessor<SecurityContext>> defaultJWTProcessor,
                          @Nullable CapiSslContextHolder capiSslContextHolder) {
        this.websocketConfiguration = websocketConfiguration;
        this.defaultJWTProcessor = defaultJWTProcessor;
        this.capiSslContextHolder = capiSslContextHolder;

        if(capiSslContextHolder != null && capiSslContextHolder.getSslContext() != null) {
            this.xnioSsl = createXnioSsl(capiSslContextHolder.getSslContext());
        }
    }

    public HttpHandler createClientHttpHandler(WebsocketClient webSocketClient, Service service, HttpErrorHandler httpErrorHandler) {
        return createClientHttpHandler(webSocketClient, service, httpErrorHandler, 30000);
    }

    public HttpHandler createClientHttpHandler(WebsocketClient webSocketClient, Service service, HttpErrorHandler httpErrorHandler, int globalTimeoutMs) {
        int timeout = service.getServiceMeta().getResponseTimeout() > 0
                ? service.getServiceMeta().getResponseTimeout()
                : globalTimeoutMs;

        CAPILoadBalancerProxyClient loadBalancingProxyClient = new CAPILoadBalancerProxyClient();
        loadBalancingProxyClient.setConnectionsPerThread(200);
        loadBalancingProxyClient.setSoftMaxConnectionsPerThread(100);
        loadBalancingProxyClient.setMaxQueueSize(500);
        loadBalancingProxyClient.setTtl(30000);
        loadBalancingProxyClient.setProblemServerRetry(10);
        webSocketClient.getMappingList().forEach((m) -> {
            String scheme = service.getServiceMeta().getScheme() == null ? HttpProtocol.HTTP.getProtocol() : service.getServiceMeta().getScheme();
            if(xnioSsl != null) {
                loadBalancingProxyClient.addHost(URI.create(scheme + "://" + m.getHostname() + ":" + m.getPort()), xnioSsl);
            } else {
                loadBalancingProxyClient.addHost(URI.create(scheme + "://" + m.getHostname() + ":" + m.getPort()));
            }
        });
        CAPIProxyHandler.Builder builder = CAPIProxyHandler.builder()
                .setProxyClient(loadBalancingProxyClient)
                .setMaxRequestTime(timeout)
                .setNext(ResponseCodeHandler.HANDLE_404);
        if (httpErrorHandler != null) {
            builder.setHttpErrorHandler(httpErrorHandler);
        }
        return builder.build();
    }

    public WebsocketAuthorization createWebsocketAuthorization() throws CapiUndertowException {
        if(defaultJWTProcessor != null && !defaultJWTProcessor.isEmpty()) {
            return new WebsocketAuthorization(defaultJWTProcessor);
        }
        throw new CapiUndertowException("No OIDC provider enabled, consider enabling OIDC");
    }

    public String normalizePathForForwarding(WebsocketClient websocketClient, String path) {
        String pathWithoutCapiContext = path.replaceFirst(websocketConfiguration.getContextPath(), "/");
        pathWithoutCapiContext = pathWithoutCapiContext.replaceAll(websocketClient.getServiceId(), "");
        if(websocketClient.getRootContext() != null && !websocketClient.getRootContext().isEmpty()) {
            return  websocketClient.getRootContext() + pathWithoutCapiContext;
        }
        return pathWithoutCapiContext.replaceAll(websocketClient.getServiceId(), "");
    }

    public String normalizeBaseContextName() {
        return websocketConfiguration.getContextPath().replaceAll("/", "").replaceAll("\\*", "");
    }

    public String getWebclientId(String originalRequest) {
        String[] pathParts = originalRequest.split("/");
        if(pathParts.length < 4) {
            return null;
        }
        if(!pathParts[1].equals(normalizeBaseContextName())) {
            return null;
        }
        return  "/" + pathParts[2] + "/" + pathParts[3];
    }

    public WebsocketClient createWebsocketClient(Service service) {
        WebsocketClient websocketClient = new WebsocketClient();

        //The path should be the same for all the nodes, so we take the first just to set the path.
        String rootContext = service.getMappingList().stream().toList().get(0).getRootContext();
        if(rootContext != null && !rootContext.isEmpty() && !rootContext.equals("/") && !rootContext.equals("*")) {
            websocketClient.setRootContext(rootContext);
        }
        String websocketContext = normalizeCapiContextPath() + service.getContext() + service.getMappingList().stream().toList().get(0).getRootContext();

        websocketClient.setServiceId(service.getContext());
        websocketClient.setMappingList(service.getMappingList());
        websocketClient.setPath(websocketContext);
        websocketClient.setRequiresSubscription(service.getServiceMeta().isSecured());
        websocketClient.setSubscriptionRole(service.getServiceMeta().getSubscriptionGroup());
        websocketClient.setHttpHandler(createClientHttpHandler(websocketClient, service, null));
        return websocketClient;
    }

    public void removeClientFromMap(Map<String, WebsocketClient> websocketClientMap, Service service) {
        websocketClientMap.remove(service.getContext());
    }

    public String normalizeCapiContextPath() {
        String normalized = websocketConfiguration.getContextPath().replaceAll("/", "").replaceAll("\\*", "");
        return "/" + normalized;
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

    public void handleOptionsRequest(HttpServerExchange exchange,
                                     List<String> accessControlAllowHeaders,
                                     Map<String, String> managedHeaders,
                                     @Nullable String oauth2CookieName) {
        List<String> localHeaders = new ArrayList<>(accessControlAllowHeaders);
        if (oauth2CookieName != null && !oauth2CookieName.isEmpty()
                && !localHeaders.contains(oauth2CookieName)) {
            localHeaders.add(oauth2CookieName);
        }
        exchange.getResponseHeaders().put(HttpString.tryFromString("Access-Control-Max-Age"), Constants.ACCESS_CONTROL_MAX_AGE_VALUE);
        HeaderValues originHeader = exchange.getRequestHeaders().get("Origin");
        if (originHeader != null && !originHeader.isEmpty()) {
            processOrigin(exchange, originHeader.getFirst());
        }
        managedHeaders.forEach((k, v) -> {
            if (k.equals(Constants.ACCESS_CONTROL_ALLOW_HEADERS)) {
                v = StringUtils.join(localHeaders, ",");
            }
            exchange.getResponseHeaders().put(HttpString.tryFromString(k), v);
        });
        exchange.setStatusCode(HttpServletResponse.SC_NO_CONTENT);
        exchange.endExchange();
    }

    private void processOrigin(HttpServerExchange request, String origin) {
        if (isValidOrigin(origin)) {
            request.getResponseHeaders().put(
                    HttpString.tryFromString(Constants.ACCESS_CONTROL_ALLOW_ORIGIN),
                    origin.replaceAll("(\r\n|\n)", ""));
        }
    }

    private boolean isValidOrigin(String origin) {
        try {
            new URL(origin).toURI();
            return true;
        } catch (MalformedURLException | URISyntaxException e) {
            return false;
        }
    }

    public XnioSsl getXnioSsl() {
        return this.xnioSsl;
    }
}