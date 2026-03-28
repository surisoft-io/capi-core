package io.surisoft.capi.undertow;

import io.surisoft.capi.schema.GrpcClient;
import io.surisoft.capi.utils.Constants;
import io.undertow.server.HttpHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GrpcGatewayTest {

    private static final int TEST_PORT = 19384;

    @Test
    void runProxy_andStop_startAndStopServer() {
        Map<String, GrpcClient> clients = new ConcurrentHashMap<>();
        GrpcGateway gateway = new GrpcGateway(TEST_PORT, clients, null);

        assertDoesNotThrow(() -> {
            gateway.runProxy();
            gateway.stop();
        });
    }

    @Test
    void healthEndpoint_returns200() throws Exception {
        Map<String, GrpcClient> clients = new ConcurrentHashMap<>();
        GrpcGateway gateway = new GrpcGateway(TEST_PORT + 1, clients, null);
        gateway.runProxy();

        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + (TEST_PORT + 1) + "/health"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, response.statusCode());
        } finally {
            gateway.stop();
        }
    }

    @Test
    void request_withoutServiceHeader_returns400() throws Exception {
        Map<String, GrpcClient> clients = new ConcurrentHashMap<>();
        GrpcGateway gateway = new GrpcGateway(TEST_PORT + 2, clients, null);
        gateway.runProxy();

        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + (TEST_PORT + 2) + "/some/path"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(400, response.statusCode());
        } finally {
            gateway.stop();
        }
    }

    @Test
    void request_withServiceHeaderNotFound_returns404() throws Exception {
        Map<String, GrpcClient> clients = new ConcurrentHashMap<>();
        GrpcGateway gateway = new GrpcGateway(TEST_PORT + 3, clients, null);
        gateway.runProxy();

        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + (TEST_PORT + 3) + "/some/path"))
                            .header(Constants.GRPC_SERVICE_HEADER, "nonexistent-service")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(404, response.statusCode());
        } finally {
            gateway.stop();
        }
    }

    @Test
    void request_withServiceHeaderFound_delegatesToHandler() throws Exception {
        Map<String, GrpcClient> clients = new ConcurrentHashMap<>();

        GrpcClient grpcClient = new GrpcClient();
        grpcClient.setServiceId("/my-grpc-svc");
        HttpHandler mockHandler = exchange -> {
            exchange.setStatusCode(200);
            exchange.endExchange();
        };
        grpcClient.setHttpHandler(mockHandler);
        clients.put("/my-grpc-svc", grpcClient);

        GrpcGateway gateway = new GrpcGateway(TEST_PORT + 4, clients, null);
        gateway.runProxy();

        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + (TEST_PORT + 4) + "/my-grpc-svc/method"))
                            .header(Constants.GRPC_SERVICE_HEADER, "my-grpc-svc")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, response.statusCode());
        } finally {
            gateway.stop();
        }
    }

    @Test
    void stop_withNullServer_doesNotThrow() {
        Map<String, GrpcClient> clients = new ConcurrentHashMap<>();
        GrpcGateway gateway = new GrpcGateway(TEST_PORT + 5, clients, null);
        // Never called runProxy, so server is null
        assertDoesNotThrow(gateway::stop);
    }
}
