package io.surisoft.capi.schema;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.undertow.server.HttpHandler;

import java.util.Set;

/**
 * A discovered WebDAV service. Mirrors {@link GrpcClient}: identity, the backend mappings, and the
 * proxy handler that fronts them.
 *
 * <p>{@code webdavHost} carries the hostname that selects this service in host-routing mode. When it
 * is null the service is reachable by path only.
 */
public class WebDavClient {

    private String serviceId;
    private String path;
    private Set<Mapping> mappingList;
    @JsonIgnore
    private HttpHandler httpHandler;
    private boolean requiresSubscription;
    private String subscriptionRole;
    private String rootContext;
    private String webdavHost;
    private boolean readOnly;
    private String opaRego;

    public String getServiceId() {
        return serviceId;
    }
    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }

    public String getPath() {
        return path;
    }
    public void setPath(String path) {
        this.path = path;
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

    public String getRootContext() {
        return rootContext;
    }
    public void setRootContext(String rootContext) {
        this.rootContext = rootContext;
    }

    /** Hostname selecting this service in host-routing mode; null means path routing only. */
    public String getWebdavHost() {
        return webdavHost;
    }
    public void setWebdavHost(String webdavHost) {
        this.webdavHost = webdavHost;
    }

    public boolean isReadOnly() {
        return readOnly;
    }
    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public String getOpaRego() {
        return opaRego;
    }
    public void setOpaRego(String opaRego) {
        this.opaRego = opaRego;
    }
}
