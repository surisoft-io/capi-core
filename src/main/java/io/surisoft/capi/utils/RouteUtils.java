package io.surisoft.capi.utils;

import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.opentelemetry.api.trace.SpanBuilder;
import io.surisoft.capi.configuration.CapiSslContextHolder;
import io.surisoft.capi.processor.*;
import io.surisoft.capi.schema.HttpMethod;
import io.surisoft.capi.schema.HttpProtocol;
import io.surisoft.capi.schema.Mapping;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.service.CapiTrustManager;
import io.surisoft.capi.service.OpaService;
import io.surisoft.capi.tracer.CapiTracer;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Route;
import org.apache.camel.component.http.HttpComponent;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.opentelemetry.SpanCustomizer;
import org.cache2k.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class RouteUtils {
    private static final Logger log = LoggerFactory.getLogger(RouteUtils.class);
    private final HttpErrorProcessor httpErrorProcessor;
    private final HttpUtils httpUtils;
    private final CompositeMeterRegistry meterRegistry;
    private final CapiTracer capiTracer;
    private final CamelContext camelContext;
    private final AuthorizationProcessor authorizationProcessor;
    private final boolean gatewayCorsManagementEnabled;
    private final CapiSslContextHolder capiSslContextHolder;
    private final int responseTimeout;
    private final int connectionRequestTimeout;
    private final int requestTimeout;

    public RouteUtils(HttpErrorProcessor httpErrorProcessor,
                      HttpUtils httpUtils,
                      CompositeMeterRegistry meterRegistry,
                      CapiTracer capiTracer,
                      CamelContext camelContext,
                      AuthorizationProcessor authorizationProcessor,
                      boolean gatewayCorsManagementEnabled,
                      CapiSslContextHolder capiSslContextHolder,
                      int responseTimeout,
                      int connectionRequestTimeout,
                      int requestTimeout
    ) {
        this.httpErrorProcessor = httpErrorProcessor;
        this.httpUtils = httpUtils;
        this.meterRegistry = meterRegistry;
        this.capiTracer = capiTracer;
        this.camelContext = camelContext;
        this.authorizationProcessor = authorizationProcessor;
        this.gatewayCorsManagementEnabled = gatewayCorsManagementEnabled;
        this.capiSslContextHolder = capiSslContextHolder;
        this.responseTimeout = responseTimeout;
        this.connectionRequestTimeout = connectionRequestTimeout;
        this.requestTimeout = requestTimeout;
    }

    public void registerMetric(String routeId) {
        meterRegistry.counter(routeId);
    }

    public void registerTracer(Service service) {
        if (capiTracer != null) {
            log.debug("Adding API to tracer as {}", service.getId());
            SpanCustomizer spanCustomizer = new SpanCustomizer() {
                @Override
                public void customize(SpanBuilder spanBuilder, String operationName, Exchange exchange) {
                    log.info("Setting custom tags for span: {}", operationName);
                }
            };
        }
    }

    private String buildTimeouts() {
        return "&" +
                "responseTimeout=" + responseTimeout + "&" +
                "connectionRequestTimeout=" +  connectionRequestTimeout + "&" +
                "connectTimeout=" + requestTimeout;
    }

    public String[] buildEndpoints(Service service) {
        List<String> transformedEndpointList = new ArrayList<>();
        for(Mapping mapping : service.getMappingList()) {
            HttpProtocol httpProtocol = null;
            if(service.getServiceMeta().getScheme() == null || service.getServiceMeta().getScheme().equals("http")) {
                httpProtocol = HttpProtocol.HTTP;
            } else {
                httpProtocol = HttpProtocol.HTTPS;
            }

            String endpoint;
            if(mapping.getPort() > -1) {
                endpoint = httpProtocol.getProtocol() + "://" + mapping.getHostname() + ":" + mapping.getPort() + mapping.getRootContext() + "?bridgeEndpoint=true&throwExceptionOnFailure=false" + buildTimeouts();
            } else {
                endpoint = httpProtocol.getProtocol() + "://" + mapping.getHostname() + mapping.getRootContext() + "?bridgeEndpoint=true&throwExceptionOnFailure=false" + buildTimeouts();
            }
            if(mapping.isIngress()) {
                endpoint = httpUtils.setIngressEndpoint(endpoint, mapping.getHostname());
            }

            if(gatewayCorsManagementEnabled) {
                endpoint = endpoint + "&headerFilterStrategy=#capiCorsFilterStrategy";
            }

            transformedEndpointList.add(endpoint);
        }
        return transformedEndpointList.toArray(String[]::new);
    }

    public String buildFrom(Service service) {
        if(!service.getContext().startsWith("/")) {
            return "/" + service.getContext();
        }
        return service.getContext();
    }

    public List<String> getAllRouteIdForAGivenService(Service service) {
        List<String> routeIdList = new ArrayList<>();
        routeIdList.add(service.getId() + ":" + HttpMethod.DELETE.getMethod());
        routeIdList.add(service.getId() + ":" + HttpMethod.PUT.getMethod());
        routeIdList.add(service.getId() + ":" + HttpMethod.POST.getMethod());
        routeIdList.add(service.getId() + ":" + HttpMethod.GET.getMethod());
        routeIdList.add(service.getId() + ":" + HttpMethod.PATCH.getMethod());
        return routeIdList;
    }

    public String getMethodFromRouteId(String routeId) {
        return routeId.split(":")[2];
    }

    public List<String> getAllActiveRoutes(CamelContext camelContext) {
        List<String> routeIdList = new ArrayList<>();
        List<Route> routeList = camelContext.getRoutes();
        for(Route route : routeList) {
            routeIdList.add(route.getRouteId());
        }
        return routeIdList;
    }

    public void reloadTrustStoreManager(InputStream inputStream, String capiTrustStorePassword) {
        try {
            log.trace("Reloading Trust Store Manager after changes detected");
            HttpComponent httpComponent = (HttpComponent) camelContext.getComponent("https");
            CapiTrustManager capiTrustManager = (CapiTrustManager) httpComponent.getSslContextParameters().getTrustManagers().getTrustManager();
            capiTrustManager.reloadTrustManager(inputStream, capiTrustStorePassword);
            httpComponent.getSslContextParameters().getTrustManagers().setTrustManager(capiTrustManager);
        } catch(Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    public void enableAuthorization(String apiId, RouteDefinition routeDefinition) {
        if(authorizationProcessor != null) {
            routeDefinition.process(this.authorizationProcessor);
        } else {
            log.warn("The api with id {} is marked to protect but there is no OIDC provider enabled.", apiId);
        }
    }

    public AuthorizationProcessor authorizationProcessor(String apiId, RouteDefinition routeDefinition, boolean isSecured) {
        if(authorizationProcessor != null && isSecured) {
            return this.authorizationProcessor;
        }
        return null;
    }

    public OpenApiProcessor openApiProcessor(Service service, OpaService opaService, Cache<String, Service> serviceCache) {
        if(service.getServiceMeta().getOpenApiEndpoint() != null && service.getOpenAPI() != null) {
            return new OpenApiProcessor(service.getOpenAPI(), httpUtils, serviceCache, opaService);
        }
        return null;
    }

    public HttpErrorProcessor getHttpErrorProcessor() {
        return httpErrorProcessor;
    }
}