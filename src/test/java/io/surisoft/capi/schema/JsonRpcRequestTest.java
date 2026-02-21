package io.surisoft.capi.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonRpcRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deserialize_validRequest() throws Exception {
        String json = "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":1}";
        JsonRpcRequest request = objectMapper.readValue(json, JsonRpcRequest.class);

        assertEquals("2.0", request.getJsonrpc());
        assertEquals("tools/list", request.getMethod());
        assertEquals(1, request.getId());
        assertNull(request.getParams());
    }

    @Test
    void deserialize_withParams() throws Exception {
        String json = "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"id\":\"abc\",\"params\":{\"name\":\"test\"}}";
        JsonRpcRequest request = objectMapper.readValue(json, JsonRpcRequest.class);

        assertEquals("2.0", request.getJsonrpc());
        assertEquals("tools/call", request.getMethod());
        assertEquals("abc", request.getId());
        assertNotNull(request.getParams());
    }

    @Test
    void deserialize_ignoresUnknownProperties() throws Exception {
        String json = "{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":1,\"extra\":\"ignored\"}";
        JsonRpcRequest request = objectMapper.readValue(json, JsonRpcRequest.class);

        assertEquals("ping", request.getMethod());
    }

    @Test
    void serialize_roundTrip() throws Exception {
        JsonRpcRequest request = new JsonRpcRequest();
        request.setJsonrpc("2.0");
        request.setMethod("initialize");
        request.setId(42);
        request.setParams(Map.of("key", "value"));

        String json = objectMapper.writeValueAsString(request);
        JsonRpcRequest deserialized = objectMapper.readValue(json, JsonRpcRequest.class);

        assertEquals("2.0", deserialized.getJsonrpc());
        assertEquals("initialize", deserialized.getMethod());
        assertEquals(42, deserialized.getId());
        assertNotNull(deserialized.getParams());
    }

    @Test
    void getterSetters() {
        JsonRpcRequest request = new JsonRpcRequest();
        request.setJsonrpc("2.0");
        request.setMethod("test");
        request.setId("id-1");
        request.setParams("params");

        assertEquals("2.0", request.getJsonrpc());
        assertEquals("test", request.getMethod());
        assertEquals("id-1", request.getId());
        assertEquals("params", request.getParams());
    }
}
