package io.surisoft.capi.undertow;

import io.surisoft.capi.exception.AuthorizationException;
import io.surisoft.capi.schema.GrpcClient;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.HttpUtils;
import io.surisoft.capi.utils.ErrorMessage;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.util.HeaderValues;
import io.undertow.util.HttpString;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.util.Map;

public class GrpcGateway {

    private static final Logger log = LoggerFactory.getLogger(GrpcGateway.class);
    private static final HttpString GRPC_SERVICE_HTTP_STRING = new HttpString(Constants.GRPC_SERVICE_HEADER);
    /** gRPC status codes (google.rpc.Code): 16 UNAUTHENTICATED, 7 PERMISSION_DENIED. */
    private static final int GRPC_STATUS_UNAUTHENTICATED = 16;
    private static final int GRPC_STATUS_PERMISSION_DENIED = 7;
    private final int grpcPort;
    private final Map<String, GrpcClient> grpcClients;
    private final SSLContext sslContext;
    private HttpUtils httpUtils;
    private Undertow server;

    public GrpcGateway(int grpcPort,
                       Map<String, GrpcClient> grpcClients,
                       SSLContext sslContext) {
        this.grpcPort = grpcPort;
        this.grpcClients = grpcClients;
        this.sslContext = sslContext;
    }

    /**
     * Token validation for {@code secured} gRPC services. Set by the bootstrap; when absent, a
     * secured service is refused rather than passed through — a gateway that cannot check a
     * credential must not act as though the credential were valid.
     */
    public void setHttpUtils(HttpUtils httpUtils) {
        this.httpUtils = httpUtils;
    }

    /**
     * Refuses a call the way a gRPC client can actually read.
     *
     * <p>A gRPC client does not interpret an HTTP status; it reads {@code grpc-status}. A plain 401
     * surfaces as an opaque transport error, so this sends a trailers-only response: HTTP 200 with
     * the real outcome in {@code grpc-status} (16 UNAUTHENTICATED, 7 PERMISSION_DENIED).
     */
    private static void sendGrpcStatus(io.undertow.server.HttpServerExchange exchange, int grpcStatus, String message) {
        exchange.setStatusCode(HttpServletResponse.SC_OK);
        exchange.getResponseHeaders().put(new HttpString("content-type"), "application/grpc");
        exchange.getResponseHeaders().put(new HttpString("grpc-status"), String.valueOf(grpcStatus));
        exchange.getResponseHeaders().put(new HttpString("grpc-message"), message);
        exchange.endExchange();
    }

    public void runProxy() {
        Undertow.Builder builder = Undertow.builder();

        if(sslContext != null) {
            builder.addHttpsListener(grpcPort, Constants.UNDERTOW_LISTENING_ADDRESS, sslContext);
        } else {
            builder.addHttpListener(grpcPort, Constants.UNDERTOW_LISTENING_ADDRESS);
        }

        builder.setServerOption(UndertowOptions.ENABLE_HTTP2, true);
        builder.setHandler(httpServerExchange -> {
            String requestPath = httpServerExchange.getRequestPath();

            if(requestPath.equals(Constants.CAPI_HEALTH_PATH)) {
                httpServerExchange.setStatusCode(HttpServletResponse.SC_OK);
                httpServerExchange.endExchange();
                return;
            }

            HeaderValues headerValues = httpServerExchange.getRequestHeaders().get(GRPC_SERVICE_HTTP_STRING);
            if(headerValues == null || headerValues.isEmpty()) {
                log.debug("Missing {} header", Constants.GRPC_SERVICE_HEADER);
                httpServerExchange.setStatusCode(Constants.BAD_REQUEST_CODE);
                httpServerExchange.endExchange();
                return;
            }

            String serviceHeader = headerValues.getFirst();
            String grpcClientId = "/" + serviceHeader;
            if(!grpcClients.containsKey(grpcClientId)) {
                log.debug(ErrorMessage.IS_NOT_PRESENT, serviceHeader);
                httpServerExchange.setStatusCode(Constants.NOT_FOUND_CODE);
                httpServerExchange.endExchange();
                return;
            }

            GrpcClient grpcClient = grpcClients.get(grpcClientId);

            // REST and WebSocket both gate `secured` services; this listener dispatched straight to
            // the backend, so any client that could reach the port could invoke RPC methods on
            // internal microservices. The subscription check mirrors the WebSocket gateway.
            if (grpcClient.requiresSubscription()) {
                if (httpUtils == null) {
                    log.error("gRPC service {} is secured but no token validator is configured; refusing", serviceHeader);
                    sendGrpcStatus(httpServerExchange, GRPC_STATUS_UNAUTHENTICATED, "Authorization not available");
                    return;
                }
                String accessToken;
                try {
                    accessToken = httpUtils.processAuthorizationAccessToken(httpServerExchange);
                } catch (AuthorizationException e) {
                    sendGrpcStatus(httpServerExchange, GRPC_STATUS_UNAUTHENTICATED, "Invalid authorization");
                    return;
                }
                if (accessToken == null) {
                    sendGrpcStatus(httpServerExchange, GRPC_STATUS_UNAUTHENTICATED, "Authorization required");
                    return;
                }
                if (!httpUtils.isAuthorized(accessToken, grpcClient.getSubscriptionRole())) {
                    log.debug("gRPC call to {} denied: token not in subscription group", serviceHeader);
                    sendGrpcStatus(httpServerExchange, GRPC_STATUS_PERMISSION_DENIED, "Not subscribed");
                    return;
                }
            }

            grpcClient.getHttpHandler().handleRequest(httpServerExchange);
        });

        server = builder.build();
        server.start();
        log.info("gRPC Gateway started on port {}", grpcPort);
    }

    public void stop() {
        if(server != null) {
            log.info("Stopping gRPC Gateway on port {}", grpcPort);
            server.stop();
        }
    }
}
