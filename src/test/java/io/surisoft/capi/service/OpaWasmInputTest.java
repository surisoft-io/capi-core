package io.surisoft.capi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The shape of the OPA input (CAPI-05): documented fields present, request fields not forgeable. */
class OpaWasmInputTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OpaWasmService service = new OpaWasmService("http://unused/bundle.tar.gz", 1, HttpClient.newHttpClient());

    private static String token(String claimsJson) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return b64.encodeToString("{\"alg\":\"RS256\"}".getBytes()) + "." + b64.encodeToString(claimsJson.getBytes()) + ".sig";
    }

    private Map<String, Object> input(String token, Map<String, Object> request) throws Exception {
        return mapper.readValue(service.buildInput("orders:v1", token, true, request), new TypeReference<>() {});
    }

    @Test
    void documentedShape_methodPathAndTokenArePresent() throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("method", "POST");
        request.put("path", "/api/orders/v1/orders/123");

        Map<String, Object> input = input(token("{\"sub\":\"u1\",\"azp\":\"client-a\",\"realm_access\":{\"roles\":[\"admin\"]}}"), request);

        assertEquals("POST", input.get("method"));
        assertEquals("/api/orders/v1/orders/123", input.get("path"));
        assertEquals("orders:v1", input.get("service"));
        assertEquals("client-a", ((Map<?, ?>) input.get("token")).get("azp"), "input.token.<claim>, as documented");
    }

    @Test
    void existingPolicies_keepReadingClaimsAtTheTopLevel() throws Exception {
        // Policies in use read input.azp; the documented input.token must not break them.
        Map<String, Object> input = input(token("{\"azp\":\"client-a\"}"), null);
        assertEquals("client-a", input.get("azp"));
    }

    @Test
    void aClaim_cannotImpersonateTheRequest() throws Exception {
        Map<String, Object> request = Map.of("method", "GET", "path", "/api/orders/v1/orders");
        Map<String, Object> input = input(token("{\"method\":\"DELETE\",\"path\":\"/admin\",\"service\":\"other:v1\"}"), request);

        assertEquals("GET", input.get("method"));
        assertEquals("/api/orders/v1/orders", input.get("path"));
        assertEquals("orders:v1", input.get("service"));
    }

    @Test
    void withoutRequestContext_noMethodOrPathIsInvented() throws Exception {
        Map<String, Object> input = input(token("{\"sub\":\"u1\"}"), null);
        assertFalse(input.containsKey("method"));
        assertFalse(input.containsKey("path"));
    }
}
