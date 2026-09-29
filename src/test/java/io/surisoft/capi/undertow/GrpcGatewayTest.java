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

    // ---- SEC-04: secured gRPC services must be gated, like REST and WebSocket ----

    private static GrpcClient securedClient(java.util.List<String> reachedBackend) {
        GrpcClient client = new GrpcClient();
        client.setServiceId("/greeter");
        client.setRequiresSubscription(true);
        client.setSubscriptionRole("grpc-group");
        client.setHttpHandler(exchange -> {
            reachedBackend.add(exchange.getRequestPath());
            exchange.setStatusCode(200);
            exchange.endExchange();
        });
        return client;
    }

    private static HttpResponse<String> callGrpc(int port, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/helloworld.Greeter/SayHello"))
                .header(Constants.GRPC_SERVICE_HEADER, "greeter")
                .POST(HttpRequest.BodyPublishers.ofString(""));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void securedService_withoutToken_isRefusedAndNeverReachesTheBackend() throws Exception {
        java.util.List<String> reachedBackend = new java.util.ArrayList<>();
        Map<String, GrpcClient> clients = new ConcurrentHashMap<>();
        clients.put("/greeter", securedClient(reachedBackend));

        io.surisoft.capi.utils.HttpUtils httpUtils = mock(io.surisoft.capi.utils.HttpUtils.class);
        GrpcGateway gateway = new GrpcGateway(TEST_PORT + 20, clients, null);
        gateway.setHttpUtils(httpUtils);
        gateway.runProxy();
        try {
            HttpResponse<String> response = callGrpc(TEST_PORT + 20, null);

            // A gRPC client reads grpc-status, not the HTTP code, so the refusal has to be legible
            // there: 16 = UNAUTHENTICATED.
            assertEquals("16", response.headers().firstValue("grpc-status").orElse(null));
            assertTrue(reachedBackend.isEmpty(), "an unauthenticated call must not reach the backend");
        } finally {
            gateway.stop();
        }
    }

    @Test
    void securedService_withTokenNotInGroup_isPermissionDenied() throws Exception {
        java.util.List<String> reachedBackend = new java.util.ArrayList<>();
        Map<String, GrpcClient> clients = new ConcurrentHashMap<>();
        clients.put("/greeter", securedClient(reachedBackend));

        io.surisoft.capi.utils.HttpUtils httpUtils = mock(io.surisoft.capi.utils.HttpUtils.class);
        when(httpUtils.processAuthorizationAccessToken(any(io.undertow.server.HttpServerExchange.class))).thenReturn("tok");
        when(httpUtils.isAuthorized("tok", "grpc-group")).thenReturn(false);

        GrpcGateway gateway = new GrpcGateway(TEST_PORT + 21, clients, null);
        gateway.setHttpUtils(httpUtils);
        gateway.runProxy();
        try {
            HttpResponse<String> response = callGrpc(TEST_PORT + 21, "Bearer tok");
            assertEquals("7", response.headers().firstValue("grpc-status").orElse(null));   // PERMISSION_DENIED
            assertTrue(reachedBackend.isEmpty());
        } finally {
            gateway.stop();
        }
    }

    @Test
    void securedService_withValidToken_reachesTheBackend() throws Exception {
        java.util.List<String> reachedBackend = new java.util.ArrayList<>();
        Map<String, GrpcClient> clients = new ConcurrentHashMap<>();
        clients.put("/greeter", securedClient(reachedBackend));

        io.surisoft.capi.utils.HttpUtils httpUtils = mock(io.surisoft.capi.utils.HttpUtils.class);
        when(httpUtils.processAuthorizationAccessToken(any(io.undertow.server.HttpServerExchange.class))).thenReturn("tok");
        when(httpUtils.isAuthorized("tok", "grpc-group")).thenReturn(true);

        GrpcGateway gateway = new GrpcGateway(TEST_PORT + 22, clients, null);
        gateway.setHttpUtils(httpUtils);
        gateway.runProxy();
        try {
            callGrpc(TEST_PORT + 22, "Bearer tok");
            assertEquals(1, reachedBackend.size(), "an authorized call must be proxied");
        } finally {
            gateway.stop();
        }
    }

    @Test
    void securedService_withNoValidatorConfigured_isRefusedNotPassedThrough() throws Exception {
        // A gateway that cannot check a credential must not behave as though it were valid.
        java.util.List<String> reachedBackend = new java.util.ArrayList<>();
        Map<String, GrpcClient> clients = new ConcurrentHashMap<>();
        clients.put("/greeter", securedClient(reachedBackend));

        GrpcGateway gateway = new GrpcGateway(TEST_PORT + 23, clients, null);   // no setHttpUtils
        gateway.runProxy();
        try {
            HttpResponse<String> response = callGrpc(TEST_PORT + 23, "Bearer tok");
            assertEquals("16", response.headers().firstValue("grpc-status").orElse(null));
            assertTrue(reachedBackend.isEmpty());
        } finally {
            gateway.stop();
        }
    }

    @Test
    void unsecuredService_isUnaffected() throws Exception {
        java.util.List<String> reachedBackend = new java.util.ArrayList<>();
        GrpcClient open = securedClient(reachedBackend);
        open.setRequiresSubscription(false);
        Map<String, GrpcClient> clients = new ConcurrentHashMap<>();
        clients.put("/greeter", open);

        GrpcGateway gateway = new GrpcGateway(TEST_PORT + 24, clients, null);
        gateway.runProxy();
        try {
            callGrpc(TEST_PORT + 24, null);
            assertEquals(1, reachedBackend.size(), "an open service must still route without a token");
        } finally {
            gateway.stop();
        }
    }

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
