package io.surisoft.capi.schema;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.models.OpenAPI;
import io.undertow.server.HttpHandler;

import java.util.Set;

public class RestClient {

    private String serviceId;
    private Set<Mapping> mappingList;
    @JsonIgnore
    private HttpHandler httpHandler;
    private String rootContext;

    // Security
    private boolean secured;
    private String subscriptionGroup;
    private String opaRego;
    private boolean apiKeyEnabled;

    // Throttle
    private boolean throttle;
    private boolean throttleGlobal;
    private long throttleTotalCalls;
    private long throttleDuration;

    // Failover
    private boolean failOverEnabled;
    private boolean roundRobinEnabled;

    // Trace
    private boolean b3TraceId;

    // Group
    private boolean keepGroup;

    // OpenAPI
    @JsonIgnore
    private OpenAPI openAPI;

    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }

    public Set<Mapping> getMappingList() {
        return mappingList;
    }

    public void setMappingList(Set<Mapping> mappingList) {
        this.mappingList = mappingList;
    }

    public HttpHandler getHttpHandler() {
        return httpHandler;
    }

    public void setHttpHandler(HttpHandler httpHandler) {
        this.httpHandler = httpHandler;
    }

    public String getRootContext() {
        return rootContext;
    }

    public void setRootContext(String rootContext) {
        this.rootContext = rootContext;
    }

    public boolean isSecured() {
        return secured;
    }

    public void setSecured(boolean secured) {
        this.secured = secured;
    }

    public String getSubscriptionGroup() {
        return subscriptionGroup;
    }

    public void setSubscriptionGroup(String subscriptionGroup) {
        this.subscriptionGroup = subscriptionGroup;
    }

    public String getOpaRego() {
        return opaRego;
    }

    public void setOpaRego(String opaRego) {
        this.opaRego = opaRego;
    }

    public boolean isApiKeyEnabled() {
        return apiKeyEnabled;
    }

    public void setApiKeyEnabled(boolean apiKeyEnabled) {
        this.apiKeyEnabled = apiKeyEnabled;
    }

    public boolean isThrottle() {
        return throttle;
    }

    public void setThrottle(boolean throttle) {
        this.throttle = throttle;
    }

    public boolean isThrottleGlobal() {
        return throttleGlobal;
    }

    public void setThrottleGlobal(boolean throttleGlobal) {
        this.throttleGlobal = throttleGlobal;
    }

    public long getThrottleTotalCalls() {
        return throttleTotalCalls;
    }

    public void setThrottleTotalCalls(long throttleTotalCalls) {
        this.throttleTotalCalls = throttleTotalCalls;
    }

    public long getThrottleDuration() {
        return throttleDuration;
    }

    public void setThrottleDuration(long throttleDuration) {
        this.throttleDuration = throttleDuration;
    }

    public boolean isFailOverEnabled() {
        return failOverEnabled;
    }

    public void setFailOverEnabled(boolean failOverEnabled) {
        this.failOverEnabled = failOverEnabled;
    }

    public boolean isRoundRobinEnabled() {
        return roundRobinEnabled;
    }

    public void setRoundRobinEnabled(boolean roundRobinEnabled) {
        this.roundRobinEnabled = roundRobinEnabled;
    }

    public boolean isB3TraceId() {
        return b3TraceId;
    }

    public void setB3TraceId(boolean b3TraceId) {
        this.b3TraceId = b3TraceId;
    }

    public boolean isKeepGroup() {
        return keepGroup;
    }

    public void setKeepGroup(boolean keepGroup) {
        this.keepGroup = keepGroup;
    }

    public OpenAPI getOpenAPI() {
        return openAPI;
    }

    public void setOpenAPI(OpenAPI openAPI) {
        this.openAPI = openAPI;
    }
}