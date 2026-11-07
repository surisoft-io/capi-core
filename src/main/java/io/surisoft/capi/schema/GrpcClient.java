package io.surisoft.capi.schema;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.undertow.server.HttpHandler;

import java.util.Set;

public class GrpcClient {

    private String serviceId;
    private String path;
    private Set<Mapping> mappingList;
    @JsonIgnore
    private HttpHandler httpHandler;
    private boolean requiresSubscription;
    private String subscriptionRole;
    private String opaRego;
    private String canonicalServiceId;
    private String rootContext;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public HttpHandler getHttpHandler() {
        return httpHandler;
    }

    public void setHttpHandler(HttpHandler httpHandler) {
        this.httpHandler = httpHandler;
    }

    public boolean requiresSubscription() {
        return requiresSubscription;
    }

    public boolean isRequiresSubscription() {
        return requiresSubscription;
    }

    public void setRequiresSubscription(boolean requiresSubscription) {
        this.requiresSubscription = requiresSubscription;
    }

    public String getSubscriptionRole() {
        return subscriptionRole;
    }

    public void setSubscriptionRole(String subscriptionRole) {
        this.subscriptionRole = subscriptionRole;
    }

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

    public String getRootContext() {
        return rootContext;
    }
    public void setRootContext(String rootContext) {
        this.rootContext = rootContext;
    }

    /** The service's {@code opa-rego} policy, evaluated by the access gate. */
    public String getOpaRego() {
        return opaRego;
    }

    public void setOpaRego(String opaRego) {
        this.opaRego = opaRego;
    }

    /** {@code name:group} — handed to OPA as the service id, as on the REST listener. */
    public String getCanonicalServiceId() {
        return canonicalServiceId;
    }

    public void setCanonicalServiceId(String canonicalServiceId) {
        this.canonicalServiceId = canonicalServiceId;
    }
}
