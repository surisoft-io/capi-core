package io.surisoft.capi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the concrete HTTP request for an OpenAPI-promoted MCP tool call.
 *
 * Conventions (kept simple; cover the common case for LLM-driven tool use):
 *  - Path placeholders (`{name}`) are substituted from {@code arguments};
 *    a missing required path arg fails the build.
 *  - {@code arguments.body}, when present, is the JSON request body.
 *  - Any remaining top-level argument is sent as a query parameter for
 *    GET/HEAD/DELETE, or merged into the body for POST/PUT/PATCH if no
 *    explicit {@code body} was given.
 *  - All values are percent-encoded.
 */
public class OpenApiCallBuilder {

    private static final String BODY_KEY = "body";

    private final ObjectMapper objectMapper;

    public OpenApiCallBuilder() {
        this(new ObjectMapper());
    }

    public OpenApiCallBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Built build(String backendBase, String httpMethod, String pathTemplate, Map<String, Object> arguments)
            throws OpenApiCallException {
        if (backendBase == null) throw new OpenApiCallException("backendBase is required");
        if (httpMethod == null || httpMethod.isBlank()) throw new OpenApiCallException("httpMethod is required");
        if (pathTemplate == null) pathTemplate = "";

        Map<String, Object> remaining = new LinkedHashMap<>();
        if (arguments != null) remaining.putAll(arguments);

        Set<String> pathParamNames = extractPlaceholders(pathTemplate);
        String resolvedPath = pathTemplate;
        for (String name : pathParamNames) {
            Object value = remaining.remove(name);
            if (value == null) {
                throw new OpenApiCallException("Missing required path parameter: " + name);
            }
            String encoded = URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8)
                    // path segments shouldn't encode '+' as space, prefer %20
                    .replace("+", "%20");
            resolvedPath = resolvedPath.replace("{" + name + "}", encoded);
        }

        boolean methodAllowsBody = methodAllowsBody(httpMethod);
        Object body = remaining.remove(BODY_KEY);
        String bodyJson = null;
        if (body != null) {
            if (!methodAllowsBody) {
                throw new OpenApiCallException("HTTP method " + httpMethod.toUpperCase() + " does not allow a body");
            }
            try {
                bodyJson = objectMapper.writeValueAsString(body);
            } catch (JsonProcessingException e) {
                throw new OpenApiCallException("Failed to serialize body: " + e.getMessage(), e);
            }
        }

        // Remaining args: query string for GET/HEAD/DELETE; merged body for POST/PUT/PATCH if no body was provided.
        String queryString = "";
        if (!remaining.isEmpty()) {
            if (methodAllowsBody && bodyJson == null) {
                try {
                    bodyJson = objectMapper.writeValueAsString(remaining);
                } catch (JsonProcessingException e) {
                    throw new OpenApiCallException("Failed to serialize residual args as body: " + e.getMessage(), e);
                }
            } else {
                queryString = buildQueryString(remaining);
            }
        }

        String fullUrl = joinPath(backendBase, resolvedPath);
        if (!queryString.isEmpty()) {
            fullUrl = fullUrl + (fullUrl.contains("?") ? "&" : "?") + queryString;
        }

        URI uri;
        try {
            uri = URI.create(fullUrl);
        } catch (IllegalArgumentException e) {
            throw new OpenApiCallException("Invalid backend URL: " + fullUrl, e);
        }
        return new Built(uri, httpMethod.toUpperCase(), bodyJson);
    }

    static Set<String> extractPlaceholders(String template) {
        Set<String> names = new HashSet<>();
        int len = template.length();
        int i = 0;
        while (i < len) {
            int open = template.indexOf('{', i);
            if (open < 0) break;
            int close = template.indexOf('}', open + 1);
            if (close < 0) break;
            String name = template.substring(open + 1, close);
            if (!name.isEmpty()) names.add(name);
            i = close + 1;
        }
        return names;
    }

    static boolean methodAllowsBody(String method) {
        String m = method.toUpperCase();
        return m.equals("POST") || m.equals("PUT") || m.equals("PATCH") || m.equals("DELETE");
    }

    private static String buildQueryString(Map<String, Object> args) {
        List<String> parts = new ArrayList<>(args.size());
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            String k = URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8);
            String v = URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8);
            parts.add(k + "=" + v);
        }
        return String.join("&", parts);
    }

    private static String joinPath(String base, String path) {
        if (path == null || path.isEmpty()) return base;
        if (base.endsWith("/") && path.startsWith("/")) return base + path.substring(1);
        if (!base.endsWith("/") && !path.startsWith("/")) return base + "/" + path;
        return base + path;
    }

    public static class Built {
        private final URI uri;
        private final String method;
        private final String body;

        public Built(URI uri, String method, String body) {
            this.uri = uri;
            this.method = method;
            this.body = body;
        }

        public URI getUri() { return uri; }
        public String getMethod() { return method; }
        public String getBody() { return body; }
        public boolean hasBody() { return body != null; }
    }

    public static class OpenApiCallException extends Exception {
        public OpenApiCallException(String message) { super(message); }
        public OpenApiCallException(String message, Throwable cause) { super(message, cause); }
    }
}