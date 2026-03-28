package io.surisoft.capi.exception;

import io.surisoft.capi.schema.CapiRestError;
import io.surisoft.capi.utils.HttpUtils;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;
import io.undertow.util.HttpString;

public class HttpErrorHandler {

    public static final AttachmentKey<String> ROUTE_ID_KEY = AttachmentKey.create(String.class);
    public static final AttachmentKey<String> REQUEST_URI_KEY = AttachmentKey.create(String.class);
    public static final AttachmentKey<String> TRACE_ID_KEY = AttachmentKey.create(String.class);

    private final HttpUtils httpUtils;

    public HttpErrorHandler(HttpUtils httpUtils) {
        this.httpUtils = httpUtils;
    }

    public void sendError(HttpServerExchange exchange, int statusCode, String message) {
        sendError(exchange, statusCode, message, null);
    }

    public void sendError(HttpServerExchange exchange, int statusCode, String message, String contextPath) {
        CapiRestError error = new CapiRestError();
        error.setErrorCode(statusCode);
        error.setErrorMessage(message);

        // Use original URI from attachment if available (proxy may have changed requestURI)
        String originalUri = exchange.getAttachment(REQUEST_URI_KEY);
        error.setHttpUri(originalUri != null ? originalUri : exchange.getRequestPath());

        // Use trace ID from attachment if available
        String traceId = exchange.getAttachment(TRACE_ID_KEY);
        if (traceId != null) {
            error.setTraceID(traceId);
        }

        // Use route ID from attachment if available
        String routeId = exchange.getAttachment(ROUTE_ID_KEY);
        if (routeId != null) {
            error.setRouteID(routeId);
        } else if (httpUtils != null) {
            String restClientId = extractServiceId(error.getHttpUri(), contextPath);
            if (restClientId != null) {
                String method = exchange.getRequestMethod().toString().toLowerCase();
                error.setRouteID(httpUtils.contextToRole(restClientId) + ":" + method);
            }
        }

        exchange.setStatusCode(statusCode);
        exchange.getResponseHeaders().put(new HttpString("Content-Type"), "application/json");
        try {
            String body = httpUtils != null
                    ? httpUtils.proxyErrorMapper(error)
                    : "{\"errorCode\":" + statusCode + ",\"errorMessage\":\"" + message + "\"}";
            exchange.getResponseSender().send(body);
        } catch (Exception e) {
            exchange.getResponseSender().send("{\"errorCode\":" + statusCode + "}");
        }
    }

    /**
     * Extract service ID from request path.
     * Path format: /api/serviceName/group/... → /serviceName/group
     */
    private String extractServiceId(String requestPath, String contextPath) {
        // Strip the context path prefix (e.g., "/api")
        String pathWithoutContext = requestPath;
        if (contextPath != null && requestPath.startsWith(contextPath)) {
            pathWithoutContext = requestPath.substring(contextPath.length());
        }

        String[] parts = pathWithoutContext.split("/");
        // Need at least /serviceName/group
        if (parts.length < 3) {
            return null;
        }
        return "/" + parts[1] + "/" + parts[2];
    }
}
