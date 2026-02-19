package io.surisoft.capi.builder;

import io.surisoft.capi.exception.AuthorizationException;
import io.surisoft.capi.processor.*;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.service.OpaService;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.RouteUtils;
import org.apache.camel.CamelContext;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.RouteDefinition;
import org.apache.hc.core5.http.NoHttpResponseException;
import org.cache2k.Cache;

import javax.net.ssl.SSLHandshakeException;
import java.net.SocketException;
import java.net.UnknownHostException;

public class DirectRouteProcessor extends RouteBuilder {

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
                    .loadBalance()
                    .failover(1, false, service.isRoundRobinEnabled(), false)
                    .to(routeUtils.buildEndpoints(service))
                .endDoTry()
                .doCatch(SSLHandshakeException.class, SocketException.class, UnknownHostException.class, AuthorizationException.class)
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
                .to(routeUtils.buildEndpoints(service))
                    .endDoTry()
                    .doCatch(SSLHandshakeException.class, SocketException.class, UnknownHostException.class, AuthorizationException.class, NoHttpResponseException.class)
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
}