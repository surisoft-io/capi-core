package io.surisoft.capi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.surisoft.capi.schema.OpaResult;
import org.apache.camel.util.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

import static java.time.temporal.ChronoUnit.SECONDS;

public class OpaService {

    private static final Logger log = LoggerFactory.getLogger(OpaService.class);
    private static final Pattern VALID_OPA_REGO = Pattern.compile("^[a-zA-Z0-9_/\\-]+$");
    private final String opaEndpoint;
    private HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpaService(String opaEndpoint, HttpClient httpClient) {
        this.opaEndpoint = opaEndpoint;
        this.httpClient = httpClient;
    }

    public OpaResult callOpa(String opaRego, String value, boolean isAccessToken) {
        HttpResponse<String> httpResponse;
        try {
            httpResponse = httpClient.send(buildHttpRequest(opaRego, value, isAccessToken), HttpResponse.BodyHandlers.ofString());
            if(httpResponse.statusCode() == 200) {
                return objectMapper.readValue(httpResponse.body(), OpaResult.class);
            }
        } catch (InterruptedException | IOException | URISyntaxException e) {
            log.error(e.getMessage(), e);
            return null;
        }
        return null;
    }

    private HttpRequest buildHttpRequest(String opaRego, String value, boolean isAccessToken) throws URISyntaxException {
        if(opaRego == null || !VALID_OPA_REGO.matcher(opaRego).matches()) {
            throw new IllegalArgumentException("Invalid OPA rego path");
        }
        return HttpRequest.newBuilder()
                    .uri(new URI(opaEndpoint + "/v1/data/" + opaRego + "/allow"))
                    .setHeader("Media-Type", "application/json")
                    .timeout(Duration.of(10, SECONDS))
                    .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(value, isAccessToken)))
                    .build();
    }

    private String buildRequestBody(String value, boolean isAccessToken) {
        JsonObject tokenObject = new JsonObject();
        if(isAccessToken) {
            tokenObject.put("token", value);
        } else {
            tokenObject.put("consumerKey", value);
        }
        JsonObject inputObject = new JsonObject();
        inputObject.put("input", tokenObject);
        return inputObject.toJson();
    }
}