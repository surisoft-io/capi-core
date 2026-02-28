package io.surisoft.capi.undertow;

import io.surisoft.capi.schema.GrpcClient;
import io.surisoft.capi.utils.Constants;
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
    private final int grpcPort;
    private final Map<String, GrpcClient> grpcClients;
    private final SSLContext sslContext;
    private Undertow server;

    public GrpcGateway(int grpcPort,
                       Map<String, GrpcClient> grpcClients,
                       SSLContext sslContext) {
        this.grpcPort = grpcPort;
        this.grpcClients = grpcClients;
        this.sslContext = sslContext;
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

            if(requestPath.equals(Constants.UNDERSTOW_HEALTH_PATH)) {
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
