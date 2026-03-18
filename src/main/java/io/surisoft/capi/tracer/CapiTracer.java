package io.surisoft.capi.tracer;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.surisoft.capi.schema.RestClient;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.undertow.CAPILoadBalancerProxyClient;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.HttpUtils;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderValues;
import org.cache2k.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Calendar;
import java.util.Date;
import java.util.Map;

/**
 * OpenTelemetry tracing for RestGateway, WebsocketGateway, McpGateway, GrpcGateway.
 */
public class CapiTracer {

    private static final Logger log = LoggerFactory.getLogger(CapiTracer.class);

    private final Tracer tracer;
    private final HttpUtils httpUtils;
    private final Cache<String, Service> serviceCache;
    private final String capiInstanceName;

    public CapiTracer(Tracer tracer, HttpUtils httpUtils, Cache<String, Service> serviceCache, String capiInstanceName) {
        this.tracer = tracer;
        this.httpUtils = httpUtils;
        this.serviceCache = serviceCache;
        this.capiInstanceName = capiInstanceName;
    }

    /**
     * Creates a span, decorates it, and registers a completion listener to finish it.
     * Call this right before proxying to the backend.
     */
    public void traceRequest(HttpServerExchange exchange, RestClient restClient) {
        String method = exchange.getRequestMethod().toString().toLowerCase();
        String serviceId = httpUtils.contextToRole(restClient.getServiceId());
        String spanName = serviceId + ":" + method;

        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.SERVER)
                .setAttribute("http.method", exchange.getRequestMethod().toString())
                .setAttribute("http.url", exchange.getRequestPath())
                .setAttribute("http.target", serviceId)
                .setAttribute("component", "capi-rest-gateway")
                .setAttribute(Constants.CAMEL_SERVER_ENDPOINT_URL, exchange.getRequestPath())
                .setAttribute("capi-instance", capiInstanceName)
                .startSpan();

        // Store trace ID as exchange attachment for error responses
        exchange.putAttachment(io.surisoft.capi.exception.HttpErrorHandler.TRACE_ID_KEY,
                span.getSpanContext().getTraceId());

        decorateWithCapi(exchange, span, restClient);

        final long proxyStartTime = System.currentTimeMillis();
        exchange.addExchangeCompleteListener((ex, nextListener) -> {
            long clientResponseTime = System.currentTimeMillis() - proxyStartTime;
            span.setAttribute("capi.client.response.time", Long.toString(clientResponseTime));
            span.setAttribute("http.status_code", ex.getStatusCode());
            span.setAttribute("capi.client.response.code", Integer.toString(ex.getStatusCode()));

            String selectedHost = CAPILoadBalancerProxyClient.getSelectedHost(ex);
            if (selectedHost != null) {
                span.setAttribute("capi.client.address", selectedHost);
            }

            if (ex.getStatusCode() >= 400) {
                span.setStatus(StatusCode.ERROR, "HTTP " + ex.getStatusCode());
            }

            // Update span name to serviceId (like Camel's decorateWithCapi)
            span.updateName(serviceId);
            span.end();
            nextListener.proceed();
        });
    }

    /**
     * Trace a request with just a service ID (for WebsocketGateway, McpGateway, GrpcGateway).
     */
    public void traceRequest(HttpServerExchange exchange, String serviceId) {
        String method = exchange.getRequestMethod().toString().toLowerCase();
        String spanName = serviceId + ":" + method;

        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.SERVER)
                .setAttribute("http.method", exchange.getRequestMethod().toString())
                .setAttribute("http.url", exchange.getRequestPath())
                .setAttribute("http.target", serviceId)
                .setAttribute("component", "capi-gateway")
                .setAttribute(Constants.CAMEL_SERVER_ENDPOINT_URL, exchange.getRequestPath())
                .setAttribute("capi-instance", capiInstanceName)
                .startSpan();

        decorateWithCapiBasic(exchange, span);

        final long proxyStartTime = System.currentTimeMillis();
        exchange.addExchangeCompleteListener((ex, nextListener) -> {
            long clientResponseTime = System.currentTimeMillis() - proxyStartTime;
            span.setAttribute("capi.client.response.time", Long.toString(clientResponseTime));
            span.setAttribute("http.status_code", ex.getStatusCode());
            span.setAttribute("capi.client.response.code", Integer.toString(ex.getStatusCode()));

            String selectedHost = CAPILoadBalancerProxyClient.getSelectedHost(ex);
            if (selectedHost != null) {
                span.setAttribute("capi.client.address", selectedHost);
            }

            if (ex.getStatusCode() >= 400) {
                span.setStatus(StatusCode.ERROR, "HTTP " + ex.getStatusCode());
            }

            span.updateName(serviceId);
            span.end();
            nextListener.proceed();
        });
    }

    /**
     * Basic decoration without RestClient (token, IP, content type).
     */
    private void decorateWithCapiBasic(HttpServerExchange exchange, Span span) {
        try {
            String accessToken = httpUtils.processAuthorizationAccessToken(exchange);
            if (accessToken != null) {
                SignedJWT signedJWT = SignedJWT.parse(accessToken);
                JWTClaimsSet claims = signedJWT.getJWTClaimsSet();

                Date expiration = claims.getExpirationTime();
                if (expiration != null) {
                    span.setAttribute(Constants.CAPI_TOKEN_EXPIRED,
                            Boolean.toString(expiration.before(Calendar.getInstance().getTime())));
                }
                String authorizedParty = claims.getStringClaim(Constants.AUTHORIZED_PARTY);
                if (authorizedParty != null) {
                    span.setAttribute(Constants.CAPI_EXCHANGE_REQUESTER_ID, authorizedParty);
                }
                String clientHost = claims.getStringClaim("clientHost");
                if (clientHost != null) {
                    span.setAttribute("capi.requester.host", clientHost);
                }
                String iss = claims.getStringClaim("iss");
                if (iss != null) {
                    span.setAttribute(Constants.CAPI_REQUESTER_TOKEN_ISSUER, iss);
                }
            }

            HeaderValues contentType = exchange.getRequestHeaders().get("Content-Type");
            if (contentType != null && !contentType.isEmpty()) {
                span.setAttribute("capi.incoming.request.content.type", contentType.getFirst());
            }

            if (exchange.getRequestHeaders().contains("X-Forwarded-For")) {
                span.setAttribute("capi.incoming.request.original.ip",
                        exchange.getRequestHeaders().get("X-Forwarded-For").getFirst());
            }
        } catch (Exception e) {
            log.trace("Error decorating span: {}", e.getMessage());
        }
    }

    private void decorateWithCapi(HttpServerExchange exchange, Span span, RestClient restClient) {
        try {
            String accessToken = httpUtils.processAuthorizationAccessToken(exchange);
            if (accessToken != null) {
                SignedJWT signedJWT = SignedJWT.parse(accessToken);
                JWTClaimsSet claims = signedJWT.getJWTClaimsSet();

                Date expiration = claims.getExpirationTime();
                if (expiration != null) {
                    span.setAttribute(Constants.CAPI_TOKEN_EXPIRED,
                            Boolean.toString(expiration.before(Calendar.getInstance().getTime())));
                }

                String authorizedParty = claims.getStringClaim(Constants.AUTHORIZED_PARTY);
                if (authorizedParty != null) {
                    span.setAttribute(Constants.CAPI_EXCHANGE_REQUESTER_ID, authorizedParty);
                }

                String clientHost = claims.getStringClaim("clientHost");
                if (clientHost != null) {
                    span.setAttribute("capi.requester.host", clientHost);
                }

                String iss = claims.getStringClaim("iss");
                if (iss != null) {
                    span.setAttribute(Constants.CAPI_REQUESTER_TOKEN_ISSUER, iss);
                }
            }

            // Content type
            HeaderValues contentType = exchange.getRequestHeaders().get("Content-Type");
            if (contentType != null && !contentType.isEmpty()) {
                span.setAttribute("capi.incoming.request.content.type", contentType.getFirst());
            }

            // Original IP
            if (exchange.getRequestHeaders().contains("X-Forwarded-For")) {
                span.setAttribute("capi.incoming.request.original.ip",
                        exchange.getRequestHeaders().get("X-Forwarded-For").getFirst());
            }

            // Extra service metadata
            String serviceKey = httpUtils.contextToRole(restClient.getServiceId());
            Service service = serviceCache.peek(serviceKey);
            if (service != null && service.getServiceMeta().getExtraServiceMeta() != null) {
                for (Map.Entry<String, String> entry : service.getServiceMeta().getExtraServiceMeta().entrySet()) {
                    span.setAttribute(entry.getKey(), entry.getValue());
                }
            }

        } catch (Exception e) {
            log.trace("Error decorating span: {}", e.getMessage());
        }
    }
}