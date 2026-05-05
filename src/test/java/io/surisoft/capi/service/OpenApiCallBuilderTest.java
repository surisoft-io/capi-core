package io.surisoft.capi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiCallBuilderTest {

    private final OpenApiCallBuilder builder = new OpenApiCallBuilder();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void getWithPathParam() throws Exception {
        OpenApiCallBuilder.Built built = builder.build(
                "http://backend:8080", "GET", "/orders/{orderId}",
                Map.of("orderId", "abc-123"));

        assertEquals("http://backend:8080/orders/abc-123", built.getUri().toString());
        assertEquals("GET", built.getMethod());
        assertNull(built.getBody());
    }

    @Test
    void getWithPathAndQuery() throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("orderId", "42");
        args.put("verbose", "true");
        args.put("limit", 10);

        OpenApiCallBuilder.Built built = builder.build(
                "http://backend:8080/api", "GET", "/orders/{orderId}", args);

        String url = built.getUri().toString();
        assertTrue(url.startsWith("http://backend:8080/api/orders/42?"), "url=" + url);
        assertTrue(url.contains("verbose=true"), "url=" + url);
        assertTrue(url.contains("limit=10"), "url=" + url);
        assertNull(built.getBody());
    }

    @Test
    void postWithExplicitBody() throws Exception {
        Map<String, Object> body = Map.of("product", "laptop", "quantity", 3);
        OpenApiCallBuilder.Built built = builder.build(
                "http://backend:8080", "POST", "/orders",
                Map.of("body", body));

        assertEquals("http://backend:8080/orders", built.getUri().toString());
        assertEquals("POST", built.getMethod());
        JsonNode parsed = objectMapper.readTree(built.getBody());
        assertEquals("laptop", parsed.get("product").asText());
        assertEquals(3, parsed.get("quantity").asInt());
    }

    @Test
    void postWithBodyAndPathParam() throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("orderId", "42");
        args.put("body", Map.of("status", "shipped"));

        OpenApiCallBuilder.Built built = builder.build(
                "http://backend:8080", "PUT", "/orders/{orderId}", args);

        assertEquals("http://backend:8080/orders/42", built.getUri().toString());
        assertEquals("PUT", built.getMethod());
        JsonNode parsed = objectMapper.readTree(built.getBody());
        assertEquals("shipped", parsed.get("status").asText());
    }

    @Test
    void postWithoutExplicitBody_residualArgsBecomeBody() throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("name", "Lisbon");
        args.put("days", 3);

        OpenApiCallBuilder.Built built = builder.build(
                "http://backend:8080", "POST", "/forecast", args);

        assertEquals("http://backend:8080/forecast", built.getUri().toString());
        JsonNode parsed = objectMapper.readTree(built.getBody());
        assertEquals("Lisbon", parsed.get("name").asText());
        assertEquals(3, parsed.get("days").asInt());
    }

    @Test
    void getWithBodyArg_throws() {
        // GET doesn't allow body
        OpenApiCallBuilder.OpenApiCallException ex = assertThrows(OpenApiCallBuilder.OpenApiCallException.class, () ->
                builder.build("http://backend:8080", "GET", "/x", Map.of("body", Map.of("a", 1))));
        assertTrue(ex.getMessage().contains("does not allow a body"));
    }

    @Test
    void missingPathParam_throws() {
        OpenApiCallBuilder.OpenApiCallException ex = assertThrows(OpenApiCallBuilder.OpenApiCallException.class, () ->
                builder.build("http://backend:8080", "GET", "/orders/{orderId}", Map.of()));
        assertTrue(ex.getMessage().contains("Missing required path parameter"));
        assertTrue(ex.getMessage().contains("orderId"));
    }

    @Test
    void valuesArePercentEncoded() throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("id", "a b/c");
        args.put("q", "name=value&other");

        OpenApiCallBuilder.Built built = builder.build(
                "http://backend:8080", "GET", "/items/{id}", args);

        // path param uses %20 (not '+') for spaces
        assertTrue(built.getUri().toString().contains("/items/a%20b%2Fc"), "got: " + built.getUri());
        // query stays form-encoded ('+' for spaces is fine)
        assertTrue(built.getUri().getRawQuery().contains("q=name%3Dvalue%26other"));
    }

    @Test
    void nullArguments_isOk() throws Exception {
        OpenApiCallBuilder.Built built = builder.build(
                "http://backend:8080", "GET", "/health", null);
        assertEquals("http://backend:8080/health", built.getUri().toString());
        assertFalse(built.hasBody());
    }

    @Test
    void emptyPathTemplate_appendsNothing() throws Exception {
        OpenApiCallBuilder.Built built = builder.build(
                "http://backend:8080/api", "GET", "", null);
        assertEquals("http://backend:8080/api", built.getUri().toString());
    }

    @Test
    void slashJoining_handlesBothSidesProperly() throws Exception {
        // base ends with /, path starts with /
        OpenApiCallBuilder.Built b1 = builder.build("http://x/", "GET", "/p", null);
        assertEquals("http://x/p", b1.getUri().toString());
        // base ends without /, path without /
        OpenApiCallBuilder.Built b2 = builder.build("http://x", "GET", "p", null);
        assertEquals("http://x/p", b2.getUri().toString());
    }

    @Test
    void deleteAllowsBody() throws Exception {
        OpenApiCallBuilder.Built built = builder.build(
                "http://backend:8080", "DELETE", "/items",
                Map.of("body", Map.of("ids", List.of(1, 2, 3))));
        assertEquals("DELETE", built.getMethod());
        JsonNode parsed = objectMapper.readTree(built.getBody());
        assertEquals(3, parsed.get("ids").size());
    }
}