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
import io.undertow.server.ExchangeCompletionListener;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;
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

    /** The REST span of the exchange, so attributes known only after the auth gates can be added. */
    public static final AttachmentKey<Span> SPAN = AttachmentKey.create(Span.class);

    /** Off by default: {@code sub} identifies a person. See {@link #recordVerifiedSubject}. */
    private boolean traceSubject;

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
                .setAttribute(Constants.SERVER_ENDPOINT_URL, exchange.getRequestPath())
                .setAttribute("capi-instance", capiInstanceName)
                .startSpan();

        // Store trace ID as exchange attachment for error responses
        exchange.putAttachment(io.surisoft.capi.exception.HttpErrorHandler.TRACE_ID_KEY,
                span.getSpanContext().getTraceId());

        exchange.putAttachment(SPAN, span);

        decorateWithCapi(exchange, span, restClient);

        final long proxyStartTime = System.currentTimeMillis();
        exchange.addExchangeCompleteListener((ex, nextListener) -> endSpan(ex, span, serviceId, proxyStartTime, nextListener));
    }

    public void setTraceSubject(boolean traceSubject) {
        this.traceSubject = traceSubject;
    }

    /**
     * Adds the token's {@code sub} to the exchange's span — when {@code capi.traces.traceSubject} is on.
     *
     * <p>Call it only after an authorization gate has verified {@code verifiedToken} for this request.
     * The span is created before the gates run, when the token is still just a string the caller
     * sent; recording its subject then would let a forged token put any name in the trace.
     */
    public void recordVerifiedSubject(HttpServerExchange exchange, String verifiedToken) {
        if (!traceSubject || verifiedToken == null) {
            return;
        }
        Span span = exchange.getAttachment(SPAN);
        if (span == null) {
            return;
        }
        try {
            String subject = SignedJWT.parse(verifiedToken).getJWTClaimsSet().getSubject();
            if (subject != null) {
                span.setAttribute(Constants.CAPI_EXCHANGE_REQUESTER_SUBJECT, subject);
            }
        } catch (Exception e) {
            log.trace("Could not read the subject for the span: {}", e.getMessage());
        }
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
                .setAttribute(Constants.SERVER_ENDPOINT_URL, exchange.getRequestPath())
                .setAttribute("capi-instance", capiInstanceName)
                .startSpan();

        decorateWithCapiBasic(exchange, span);

        final long proxyStartTime = System.currentTimeMillis();
        exchange.addExchangeCompleteListener((ex, nextListener) -> endSpan(ex, span, serviceId, proxyStartTime, nextListener));
    }

    /**
     * Decorates and ends the span, then ALWAYS drives the completion chain.
     *
     * proceed() must never be skipped: it drives the completion-listener chain whose terminal
     * listener is Undertow's connection cleanup. If it is skipped the chain stalls, the channel
     * is never closed and the socket FD leaks — the same defect fixed in RestGateway's access-log
     * and metrics listeners. This listener is registered LAST (RestGateway ~:322) and Undertow
     * runs completion listeners LIFO, so it runs FIRST: a throw here would stall the chain before
     * those two hardened listeners ever run, bypassing their protection entirely.
     *
     * Throwable (not Exception) is caught deliberately — swallowing an Error is bad, but leaking
     * a socket FD for the lifetime of the process is worse, and the span/exporter path is the
     * throw-prone part (SimpleSpanProcessor exports inline on span.end()).
     */
    // package-private so CapiTracerTest can drive the failure paths directly
    void endSpan(HttpServerExchange exchange,
                 Span span,
                 String serviceId,
                 long proxyStartTime,
                 ExchangeCompletionListener.NextListener nextListener) {
        try {
            long clientResponseTime = System.currentTimeMillis() - proxyStartTime;
            span.setAttribute("capi.client.response.time", Long.toString(clientResponseTime));
            span.setAttribute("http.status_code", exchange.getStatusCode());
            span.setAttribute("capi.client.response.code", Integer.toString(exchange.getStatusCode()));

            String selectedHost = CAPILoadBalancerProxyClient.getSelectedHost(exchange);
            if (selectedHost != null) {
                span.setAttribute("capi.client.address", selectedHost);
            }

            if (exchange.getStatusCode() >= 400) {
                span.setStatus(StatusCode.ERROR, "HTTP " + exchange.getStatusCode());
            }

            span.updateName(serviceId);
        } catch (Throwable t) {
            log.warn("Failed to decorate trace span for {}", serviceId, t);
        } finally {
            try {
                span.end();
            } catch (Throwable t) {
                log.warn("Failed to end trace span for {}", serviceId, t);
            } finally {
                nextListener.proceed();
            }
        }
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
            String serviceKey = restClient.getCanonicalServiceId();
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