package io.surisoft.capi.service;

import io.surisoft.capi.schema.OpaResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OpaServiceTest {

    private OpaService opaService;

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<String> httpResponse;

    @BeforeEach
    void setUp() {
        opaService = new OpaService("http://localhost:8181", httpClient);
    }

    @Test
    void callOpa_successfulResponse_returnsOpaResult() throws Exception {
        String responseBody = "{\"result\": true}";
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(responseBody);

        OpaResult result = opaService.callOpa("my/policy", "my-token-value", true);

        assertNotNull(result);
        assertTrue(result.isAllowed());
    }

    @Test
    void callOpa_successfulResponse_withAccessToken_sendsCorrectRequest() throws Exception {
        String responseBody = "{\"result\": true}";
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(responseBody);

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);

        opaService.callOpa("my/policy", "my-token-value", true);

        verify(httpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        HttpRequest captured = requestCaptor.getValue();
        assertEquals("http://localhost:8181/v1/data/my/policy/allow", captured.uri().toString());
        assertEquals("POST", captured.method());
    }

    @Test
    void callOpa_successfulResponse_withConsumerKey_sendsCorrectRequest() throws Exception {
        String responseBody = "{\"result\": false}";
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(responseBody);

        OpaResult result = opaService.callOpa("my/policy", "consumer-key-123", false);

        assertNotNull(result);
        assertFalse(result.isAllowed());
    }

    @Test
    void callOpa_non200Response_returnsNull() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);
        when(httpResponse.statusCode()).thenReturn(500);

        OpaResult result = opaService.callOpa("my/policy", "some-value", true);

        assertNull(result);
    }

    @Test
    void callOpa_403Response_returnsNull() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);
        when(httpResponse.statusCode()).thenReturn(403);

        OpaResult result = opaService.callOpa("my/policy", "some-value", false);

        assertNull(result);
    }

    @Test
    void callOpa_ioException_returnsNull() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("Connection refused"));

        OpaResult result = opaService.callOpa("my/policy", "some-value", true);

        assertNull(result);
    }

    @Test
    void callOpa_interruptedException_returnsNull() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new InterruptedException("Interrupted"));

        OpaResult result = opaService.callOpa("my/policy", "some-value", false);

        assertNull(result);
    }

    // === callOpaAsync tests ===

    @SuppressWarnings("unchecked")
    @Test
    void callOpaAsync_successfulResponse_returnsOpaResult() throws Exception {
        String responseBody = "{\"result\": true}";

        HttpResponse<String> asyncResponse = mock(HttpResponse.class);
        when(asyncResponse.statusCode()).thenReturn(200);
        when(asyncResponse.body()).thenReturn(responseBody);

        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(asyncResponse));

        java.util.concurrent.CompletableFuture<OpaResult> future = opaService.callOpaAsync("my/policy", "token-val", true);
        OpaResult result = future.get();

        assertNotNull(result);
        assertTrue(result.isAllowed());
    }

    @SuppressWarnings("unchecked")
    @Test
    void callOpaAsync_non200Response_returnsNull() throws Exception {
        HttpResponse<String> asyncResponse = mock(HttpResponse.class);
        when(asyncResponse.statusCode()).thenReturn(500);

        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(asyncResponse));

        java.util.concurrent.CompletableFuture<OpaResult> future = opaService.callOpaAsync("my/policy", "token-val", true);
        OpaResult result = future.get();

        assertNull(result);
    }

    @SuppressWarnings("unchecked")
    @Test
    void callOpaAsync_withConsumerKey_returnsResult() throws Exception {
        String responseBody = "{\"result\": false}";

        HttpResponse<String> asyncResponse = mock(HttpResponse.class);
        when(asyncResponse.statusCode()).thenReturn(200);
        when(asyncResponse.body()).thenReturn(responseBody);

        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(asyncResponse));

        java.util.concurrent.CompletableFuture<OpaResult> future = opaService.callOpaAsync("my/policy", "consumer-key", false);
        OpaResult result = future.get();

        assertNotNull(result);
        assertFalse(result.isAllowed());
    }

    @SuppressWarnings("unchecked")
    @Test
    void callOpaAsync_exception_returnsNull() throws Exception {
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(java.util.concurrent.CompletableFuture.failedFuture(new java.io.IOException("Connection refused")));

        java.util.concurrent.CompletableFuture<OpaResult> future = opaService.callOpaAsync("my/policy", "token-val", true);
        OpaResult result = future.get();

        assertNull(result);
    }

    @Test
    void callOpa_invalidRego_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                opaService.callOpa("../evil/path", "token", true));
    }
}
