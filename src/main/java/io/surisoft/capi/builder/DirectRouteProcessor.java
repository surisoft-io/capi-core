package io.surisoft.capi.builder;

import io.surisoft.capi.exception.AuthorizationException;
import io.surisoft.capi.processor.*;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.service.OpaService;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.RouteUtils;
import jakarta.activation.DataHandler;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.RouteDefinition;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.NoHttpResponseException;
import org.cache2k.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLHandshakeException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.SocketException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class DirectRouteProcessor extends RouteBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(DirectRouteProcessor.class);
    private final RouteUtils routeUtils;
    private final Service service;
    private final String routeId;
    private final String capiContext;
    private final MetricsProcessor metricsProcessor;
    private final String reverseProxyHost;
    private OpaService opaService;
    private Cache<String, Service> serviceCache;
    private final ContentTypeValidator contentTypeValidator;
    private final ThrottleProcessor throttleProcessor;

    public DirectRouteProcessor(CamelContext camelContext,
                                Service service,
                                RouteUtils routeUtils,
                                MetricsProcessor metricsProcessor,
                                String routeId,
                                String capiContext,
                                String reverseProxyHost,
                                ContentTypeValidator contentTypeValidator,
                                ThrottleProcessor throttleProcessor) {
        super(camelContext);
        this.service = service;
        this.routeUtils = routeUtils;
        this.routeId = routeId;
        this.capiContext = capiContext;
        this.metricsProcessor = metricsProcessor;
        this.reverseProxyHost = reverseProxyHost;
        this.contentTypeValidator = contentTypeValidator;
        this.throttleProcessor = throttleProcessor;
    }

    @Override
    public void configure() {
        log.trace("Trying to build and deploy route {}", routeId);

        RouteDefinition routeDefinition = from(Constants.CAMEL_DIRECT + routeId);
        if(reverseProxyHost != null) {
            routeDefinition
                    .setHeader(Constants.X_FORWARDED_HOST, constant(reverseProxyHost));
            routeDefinition
                    .setHeader(Constants.X_FORWARDED_PREFIX, constant(capiContext + service.getContext()));
        }

        if(service.getServiceMeta().isKeepGroup()) {
            routeDefinition.setHeader(Constants.CAPI_GROUP_HEADER, constant(service.getContext()));
        }

        //For failing over enabled routes we want to build the route with try catch
        if(service.isFailOverEnabled()) {
            log.debug("Fail over enabled for route {}", routeId);

            routeDefinition
                .process(DirectRouteProcessor::preserveRequestBody)
                .doTry()
                    .setHeader("CapiServicePath", simple(service.getContext()))
                    .process(metricsProcessor)
                    .process(contentTypeValidator)
                    .process(exchange -> {
                        AuthorizationProcessor authorizationProcessor = routeUtils.authorizationProcessor(service.getId(), routeDefinition, service.getServiceMeta().isSecured());
                        if (authorizationProcessor != null) {
                            authorizationProcessor.process(exchange);
                        }
                    })
                    .process(exchange -> {
                        OpenApiProcessor openApiProcessor = routeUtils.openApiProcessor(service, opaService, serviceCache);
                        if (openApiProcessor != null) {
                            openApiProcessor.process(exchange);
                        }
                    })
                    .process(exchange -> {
                        if(service.getServiceMeta().isThrottle() && throttleProcessor != null) {
                            throttleProcessor.process(exchange);
                        }
                    })
                    .process(DirectRouteProcessor::restoreRequestBody)
                    .loadBalance()
                    .failover(1, false, service.isRoundRobinEnabled(), false)
                    .to(routeUtils.buildEndpoints(service))
                .endDoTry()
                .doCatch(SSLHandshakeException.class, SocketException.class, UnknownHostException.class, AuthorizationException.class, NoHttpResponseException.class, ConnectTimeoutException.class)
                    .setHeader(Constants.ERROR_API_SHOW_TRACE_ID, constant(service.getServiceMeta().isB3TraceId()))
                    .process(routeUtils.getHttpErrorProcessor())
                    .setHeader(Constants.ROUTE_ID_HEADER, constant(routeId))
                    .to(Constants.CAPI_ERROR_ROUTE)
                    .removeHeader(Constants.ERROR_API_SHOW_TRACE_ID)
                    .removeHeader(Constants.ERROR_API_SHOW_INTERNAL_ERROR_MESSAGE)
                    .removeHeader(Constants.ERROR_API_SHOW_INTERNAL_ERROR_CLASS)
                    .removeHeader(Constants.CAPI_URL_IN_ERROR)
                    .removeHeader(Constants.CAPI_URI_IN_ERROR)
                    .removeHeader(Constants.ROUTE_ID_HEADER)
                .end()
                    .removeHeader(Constants.X_FORWARDED_HOST)
                    .removeHeader(Constants.X_FORWARDED_PREFIX)
                    .removeHeader(Constants.AUTHORIZATION_HEADER)
                    .removeHeader(Constants.CAPI_GROUP_HEADER)
                    .removeHeader(Constants.CAPI_SHOULD_THROTTLE)
                    .removeHeader(Constants.CAPI_THROTTLE_DURATION_MILLI)
                    .removeHeader(Constants.CAPI_META_THROTTLE_DURATION)
                    .removeHeader(Constants.CAPI_META_THROTTLE_TOTAL_CALLS_ALLOWED)
                    .removeHeader(Constants.CAPI_META_THROTTLE_CONSUMER_KEY)
                    .removeHeader("CapiServicePath")
                    .routeId(routeId);
        } else {
            routeDefinition
                    .process(DirectRouteProcessor::preserveRequestBody)
                    .doTry()
                    .setHeader("CapiServicePath", simple(service.getContext()))
                    .process(metricsProcessor)
                    .process(contentTypeValidator)
                    .process(exchange -> {
                        AuthorizationProcessor authorizationProcessor = routeUtils.authorizationProcessor(service.getId(), routeDefinition, service.getServiceMeta().isSecured());
                        if (authorizationProcessor != null) {
                            authorizationProcessor.process(exchange);
                        }
                    })
                    .process(exchange -> {
                        OpenApiProcessor openApiProcessor = routeUtils.openApiProcessor(service, opaService, serviceCache);
                        if (openApiProcessor != null) {
                            openApiProcessor.process(exchange);
                        }
                    })
                    .removeHeader("X-BlueCoat-Via")
                    .process(exchange -> {
                        if(service.getServiceMeta().isThrottle() && throttleProcessor != null) {
                            throttleProcessor.process(exchange);
                        }
                    })
                    .process(DirectRouteProcessor::restoreRequestBody)
                .to(routeUtils.buildEndpoints(service))
                    .endDoTry()
                    .doCatch(SSLHandshakeException.class, SocketException.class, UnknownHostException.class, AuthorizationException.class, NoHttpResponseException.class, ConnectTimeoutException.class)
                    .setHeader(Constants.ERROR_API_SHOW_TRACE_ID, constant(service.getServiceMeta().isB3TraceId()))
                    .process(routeUtils.getHttpErrorProcessor())
                    .setHeader(Constants.ROUTE_ID_HEADER, constant(routeId))
                    .to(Constants.CAPI_ERROR_ROUTE)
                    .removeHeader(Constants.ERROR_API_SHOW_TRACE_ID)
                    .removeHeader(Constants.ERROR_API_SHOW_INTERNAL_ERROR_MESSAGE)
                    .removeHeader(Constants.ERROR_API_SHOW_INTERNAL_ERROR_CLASS)
                    .removeHeader(Constants.CAPI_URL_IN_ERROR)
                    .removeHeader(Constants.CAPI_URI_IN_ERROR)
                    .removeHeader(Constants.ROUTE_ID_HEADER)
                    .end()
                    .removeHeader(Constants.X_FORWARDED_HOST)
                    .removeHeader(Constants.X_FORWARDED_PREFIX)
                    .removeHeader(Constants.AUTHORIZATION_HEADER)
                    .removeHeader(Constants.CAPI_GROUP_HEADER)
                    .removeHeader(Constants.CAPI_SHOULD_THROTTLE)
                    .removeHeader(Constants.CAPI_THROTTLE_DURATION_MILLI)
                    .removeHeader(Constants.CAPI_META_THROTTLE_DURATION)
                    .removeHeader(Constants.CAPI_META_THROTTLE_TOTAL_CALLS_ALLOWED)
                    .removeHeader(Constants.CAPI_META_THROTTLE_CONSUMER_KEY)
                    .removeHeader("CapiServicePath")
                    .routeId(routeId);

        }

        routeUtils.registerMetric(routeId);
        routeUtils.registerTracer(service);
    }

    public void setOpaService(OpaService opaService) {
        this.opaService = opaService;
    }

    public void setServiceCache(Cache<String, Service> serviceCache) {
        this.serviceCache = serviceCache;
    }

    @SuppressWarnings("unchecked")
    static void preserveRequestBody(Exchange exchange) {
        Object body = exchange.getIn().getBody();
        String contentType = exchange.getIn().getHeader(Exchange.CONTENT_TYPE, String.class);
        String ct = contentType != null ? contentType.toLowerCase() : "";

        if (body instanceof Map && ct.contains("application/x-www-form-urlencoded")) {
            // Undertow's EagerFormParsingHandler converted form-urlencoded body to HashMap.
            // Re-encode back to URL-encoded bytes for the backend.
            Map<String, Object> formData = (Map<String, Object>) body;
            StringBuilder sb = new StringBuilder();
            formData.forEach((key, value) -> {
                if (!sb.isEmpty()) sb.append("&");
                sb.append(URLEncoder.encode(key, StandardCharsets.UTF_8));
                sb.append("=");
                sb.append(URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8));
            });
            byte[] encoded = sb.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getIn().setBody(encoded);
            exchange.setProperty("CAPIOriginalRequestBody", encoded);
        } else if (body instanceof Map && ct.contains("multipart/form-data")) {
            // Undertow's EagerFormParsingHandler converted multipart body to HashMap.
            // Reconstruct a proper multipart body using MultipartEntityBuilder.
            // File parts are stored as DataHandler objects, text fields as Strings.
            //
            // IMPORTANT: The body MUST be set as an HttpEntity (not bytes or InputStream).
            // Camel's HttpProducer handles HttpEntity directly (bypassing ContentType.parse
            // which strips the multipart boundary parameter, breaking the request).
            Map<String, Object> formData = (Map<String, Object>) body;
            try {
                MultipartEntityBuilder builder = MultipartEntityBuilder.create();
                formData.forEach((key, value) -> {
                    if (value instanceof DataHandler dh) {
                        try {
                            ContentType partContentType = dh.getContentType() != null
                                    ? ContentType.parse(dh.getContentType())
                                    : ContentType.APPLICATION_OCTET_STREAM;
                            builder.addBinaryBody(key, dh.getInputStream(), partContentType, dh.getName());
                        } catch (IOException e) {
                            LOG.error("Failed to read multipart file part: {}", key, e);
                        }
                    } else {
                        builder.addTextBody(key, String.valueOf(value));
                    }
                });
                HttpEntity entity = builder.build();
                // Serialize to bytes for restore (needed for failover retries)
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                entity.writeTo(baos);
                byte[] encoded = baos.toByteArray();
                String multipartContentType = entity.getContentType();
                exchange.setProperty("CAPIOriginalRequestBody", encoded);
                exchange.setProperty("CAPIMultipartContentType", multipartContentType);
                // Set body as HttpEntity so HttpProducer uses it directly
                exchange.getIn().setBody(
                        new org.apache.hc.core5.http.io.entity.ByteArrayEntity(encoded,
                                ContentType.parse(multipartContentType)));
                exchange.getIn().setHeader(Exchange.CONTENT_TYPE, multipartContentType);
            } catch (IOException e) {
                LOG.error("Failed to reconstruct multipart body", e);
            }
        } else {
            byte[] bytes = exchange.getIn().getBody(byte[].class);
            exchange.setProperty("CAPIOriginalRequestBody", bytes);
            if (bytes != null) {
                exchange.getIn().setBody(bytes);
            }
        }
    }

    static void restoreRequestBody(Exchange exchange) {
        byte[] saved = exchange.getProperty("CAPIOriginalRequestBody", byte[].class);
        if (saved != null) {
            String multipartContentType = exchange.getProperty("CAPIMultipartContentType", String.class);
            if (multipartContentType != null) {
                // Restore as HttpEntity to preserve multipart boundary in Content-Type
                exchange.getIn().setBody(
                        new org.apache.hc.core5.http.io.entity.ByteArrayEntity(saved,
                                ContentType.parse(multipartContentType)));
                exchange.getIn().setHeader(Exchange.CONTENT_TYPE, multipartContentType);
            } else {
                exchange.getIn().setBody(new ByteArrayInputStream(saved));
            }
        }
    }
}