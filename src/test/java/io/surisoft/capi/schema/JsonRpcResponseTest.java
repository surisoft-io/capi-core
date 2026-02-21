package io.surisoft.capi.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonRpcResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void success_factory() {
        JsonRpcResponse response = JsonRpcResponse.success(1, Map.of("key", "value"));

        assertEquals("2.0", response.getJsonrpc());
        assertEquals(1, response.getId());
        assertNotNull(response.getResult());
        assertNull(response.getError());
    }

    @Test
    void error_factory() {
        JsonRpcResponse response = JsonRpcResponse.error(2, -32600, "Invalid request");

        assertEquals("2.0", response.getJsonrpc());
        assertEquals(2, response.getId());
        assertNull(response.getResult());
        assertNotNull(response.getError());
        assertEquals(-32600, response.getError().getCode());
        assertEquals("Invalid request", response.getError().getMessage());
        assertNull(response.getError().getData());
    }

    @Test
    void error_factoryWithData() {
        JsonRpcResponse response = JsonRpcResponse.error(3, -32603, "Internal error", "extra info");

        assertEquals("2.0", response.getJsonrpc());
        assertEquals(3, response.getId());
        assertNotNull(response.getError());
        assertEquals(-32603, response.getError().getCode());
        assertEquals("Internal error", response.getError().getMessage());
        assertEquals("extra info", response.getError().getData());
    }

    @Test
    void serialize_successResponse() throws Exception {
        JsonRpcResponse response = JsonRpcResponse.success(1, Map.of("tools", "list"));
        String json = objectMapper.writeValueAsString(response);

        assertTrue(json.contains("\"jsonrpc\":\"2.0\""));
        assertTrue(json.contains("\"id\":1"));
        assertTrue(json.contains("\"result\""));
        assertFalse(json.contains("\"error\"") && !json.contains("\"error\":null"));
    }

    @Test
    void serialize_errorResponse() throws Exception {
        JsonRpcResponse response = JsonRpcResponse.error(null, -32700, "Parse error");
        String json = objectMapper.writeValueAsString(response);

        assertTrue(json.contains("\"jsonrpc\":\"2.0\""));
        assertTrue(json.contains("-32700"));
        assertTrue(json.contains("Parse error"));
    }

    @Test
    void jsonRpcError_getterSetters() {
        JsonRpcResponse.JsonRpcError error = new JsonRpcResponse.JsonRpcError();
        error.setCode(-32601);
        error.setMessage("Method not found");
        error.setData("details");

        assertEquals(-32601, error.getCode());
        assertEquals("Method not found", error.getMessage());
        assertEquals("details", error.getData());
    }

    @Test
    void getterSetters() {
        JsonRpcResponse response = new JsonRpcResponse();
        response.setJsonrpc("2.0");
        response.setResult("ok");
        response.setId(99);

        assertEquals("2.0", response.getJsonrpc());
        assertEquals("ok", response.getResult());
        assertEquals(99, response.getId());
    }
}
