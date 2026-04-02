package io.surisoft.capi.undertow;

import io.surisoft.capi.exception.CapiUndertowException;
import io.surisoft.capi.oidc.WebsocketAuthorization;
import io.surisoft.capi.schema.WebsocketClient;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.ErrorMessage;
import io.surisoft.capi.utils.WebsocketUtils;
import io.undertow.Undertow;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HttpString;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.util.List;
import java.util.Map;

public class WebsocketGateway {
    private static final Logger log = LoggerFactory.getLogger(WebsocketGateway.class);
    private static final Logger ACCESS_LOG = LoggerFactory.getLogger("capi.access");
    private final int port;
    private final int ioThreads;
    private final Map<String, WebsocketClient> webSocketClients;
    private WebsocketAuthorization websocketAuthorization;
    private final WebsocketUtils websocketUtils;
    private final SSLContext sslContext;
    //private final Optional<CapiUndertowTracer> capiUndertowTracer;
    private final List<String> accessControlAllowHeaders;
    private final Map<String, String> managedHeaders;
    private final String oauth2CookieName;
    private Undertow server;

    public WebsocketGateway(int port,
                            int ioThreads,
                            Map<String, WebsocketClient> webSocketClients,
                            WebsocketUtils websocketUtils,
                            SSLContext sslContext,
                            //Optional<CapiUndertowTracer> capiUndertowTracer,
                            List<String> accessControlAllowHeaders,
                            String oauth2CookieName) {
        this.port = port;
        this.ioThreads = ioThreads;
        this.webSocketClients = webSocketClients;
        this.websocketUtils = websocketUtils;
        this.sslContext = sslContext;
        //this.capiUndertowTracer = capiUndertowTracer;
        this.accessControlAllowHeaders = accessControlAllowHeaders;
        this.oauth2CookieName = oauth2CookieName;

        managedHeaders = new java.util.HashMap<>(Constants.CAPI_CORS_MANAGED_HEADERS);
        managedHeaders.put("Access-Control-Allow-Headers", StringUtils.join(accessControlAllowHeaders, ","));

    }

    public void runProxy() {
        try {
            websocketAuthorization = websocketUtils.createWebsocketAuthorization();
        } catch (CapiUndertowException e) {
            log.warn(e.getMessage());
        }

        Undertow.Builder builder = Undertow.builder()
                .setIoThreads(ioThreads);

        if(sslContext != null) {
            builder.addHttpsListener(port, Constants.UNDERTOW_LISTENING_ADDRESS, sslContext);
        } else {
            builder.addHttpListener(port, Constants.UNDERTOW_LISTENING_ADDRESS);
        }

        builder
                .setHandler(httpServerExchange -> {
                    long startNanos = System.nanoTime();
                    try {
                        String requestPath = httpServerExchange.getRequestPath();
                        String webClientId = websocketUtils.getWebclientId(requestPath);

                        if(httpServerExchange.getRequestHeaders().contains(Constants.BLUECOAT_HEADER)) {
                            httpServerExchange.getRequestHeaders().remove(Constants.BLUECOAT_HEADER);
                        }

                        if(requestPath.equals(Constants.CAPI_HEALTH_PATH)) {
                            httpServerExchange.setStatusCode(HttpServletResponse.SC_OK);
                            httpServerExchange.endExchange();
                        } else {
                            if(webClientId != null && webSocketClients.containsKey(webClientId)) { //webSocketClients.containsKey(webClientId)
                                WebsocketClient websocketClient = webSocketClients.get(webClientId);
                                if(httpServerExchange.getRequestMethod().equals(HttpString.tryFromString(Constants.OPTIONS_METHODS_VALUE))) {
                                    websocketUtils.handleOptionsRequest(httpServerExchange, accessControlAllowHeaders, managedHeaders, oauth2CookieName);
                                } else {
                                    if (httpServerExchange.getProtocol().equals(Constants.PROTOCOL_HTTP)) {
                                        if (websocketAuthorization != null) {
                                            if (websocketAuthorization.isAuthorized(websocketClient, httpServerExchange)) {
                                                log.debug(ErrorMessage.IS_AUTHORIZED, httpServerExchange.getRequestPath());
                                                httpServerExchange.setRequestURI(websocketUtils.normalizePathForForwarding(websocketClient, requestPath));
                                                httpServerExchange.setRelativePath(websocketUtils.normalizePathForForwarding(websocketClient, requestPath));
                                                websocketClient.getHttpHandler().handleRequest(httpServerExchange);
                                            } else {
                                                log.debug(ErrorMessage.IS_NOT_AUTHORIZED, httpServerExchange.getRequestPath());
                                                httpServerExchange.setStatusCode(Constants.FORBIDDEN_CODE);
                                                httpServerExchange.endExchange();
                                            }
                                        } else {
                                            if (!websocketClient.requiresSubscription()) {
                                                log.debug(ErrorMessage.IS_AUTHORIZED, httpServerExchange.getRequestPath());
                                                httpServerExchange.setRequestURI(websocketUtils.normalizePathForForwarding(websocketClient, requestPath));
                                                httpServerExchange.setRelativePath(websocketUtils.normalizePathForForwarding(websocketClient, requestPath));
                                                websocketClient.getHttpHandler().handleRequest(httpServerExchange);
                                            } else {
                                                log.debug(ErrorMessage.IS_NOT_AUTHORIZED, httpServerExchange.getRequestPath());
                                                httpServerExchange.setStatusCode(Constants.FORBIDDEN_CODE);
                                                httpServerExchange.endExchange();
                                            }
                                        }
                                    }
                                }
                            } else {
                                log.debug(ErrorMessage.IS_NOT_PRESENT, httpServerExchange.getRequestPath());
                                httpServerExchange.setStatusCode(Constants.NOT_FOUND_CODE);
                                httpServerExchange.endExchange();
                            }
                        }
                    } finally {
                        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                        String originalIp = "unknown";
                        java.net.InetAddress addr = httpServerExchange.getSourceAddress().getAddress();
                        if(addr != null) {
                            originalIp = addr.getHostAddress();
                        }
                        if(httpServerExchange.getRequestHeaders().contains("X-Forwarded-For")) {
                            originalIp = httpServerExchange.getRequestHeaders().get("X-Forwarded-For").getFirst();
                        }
                        ACCESS_LOG.info("{} {} {} {}ms {}",
                                httpServerExchange.getRequestMethod(),
                                httpServerExchange.getRequestPath(),
                                httpServerExchange.getStatusCode(),
                                durationMs,
                                originalIp);
                    }

                });
        server = builder.build();
        server.start();
    }

    public void stop() {
        if(server != null) {
            log.info("Stopping WebSocket Gateway on port {}", port);
            server.stop();
        }
    }

}