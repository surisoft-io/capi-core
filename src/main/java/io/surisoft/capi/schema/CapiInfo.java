package io.surisoft.capi.schema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Date;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CapiInfo {
    private String javaVersion;
    private String capiVersion;
    private Date startTimestamp;
    private Integer totalRoutes;
    private String uptime;
    private String capiNameSpace;
    private boolean oauth2Enabled;
    private String oauth2Endpoint;
    private String oauth2CookieName;
    private boolean opaEnabled;
    private String opaWasmBundleUrl;
    private boolean consulEnabled;
    private List<String> consulHosts;
    private int consulTimerInterval;
    private String routesContextPath;
    private String metricsContextPath;
    private boolean tracesEnabled;
    private String tracesEndpoint;
    private boolean throttleEnabled;
    private boolean trustStoreEnabled;
    private boolean sslEnabled;
    private boolean websocketEnabled;
    private int websocketPort;
    private boolean mcpEnabled;
    private int mcpPort;
    private boolean grpcEnabled;
    private int grpcPort;
    private int restPort;
    private int adminPort;
    private boolean apiKeyStoreEnabled;
    private String reverseProxyHost;
    private boolean corsEnabled;

    public String getCapiVersion() {
        return capiVersion;
    }

    public void setCapiVersion(String capiVersion) {
        this.capiVersion = capiVersion;
    }

    public Date getStartTimestamp() {
        return startTimestamp;
    }

    public void setStartTimestamp(Date startTimestamp) {
        this.startTimestamp = startTimestamp;
    }

    public Integer getTotalRoutes() {
        return totalRoutes;
    }

    public void setTotalRoutes(Integer totalRoutes) {
        this.totalRoutes = totalRoutes;
    }

    public String getUptime() {
        return uptime;
    }

    public void setUptime(String uptime) {
        this.uptime = uptime;
    }

    public String getCapiNameSpace() {
        return capiNameSpace;
    }

    public void setCapiNameSpace(String capiNameSpace) {
        this.capiNameSpace = capiNameSpace;
    }

    public boolean isOauth2Enabled() {
        return oauth2Enabled;
    }

    public void setOauth2Enabled(boolean oauth2Enabled) {
        this.oauth2Enabled = oauth2Enabled;
    }

    public String getOauth2Endpoint() {
        return oauth2Endpoint;
    }

    public void setOauth2Endpoint(String oauth2Endpoint) {
        this.oauth2Endpoint = oauth2Endpoint;
    }

    public boolean isOpaEnabled() {
        return opaEnabled;
    }

    public void setOpaEnabled(boolean opaEnabled) {
        this.opaEnabled = opaEnabled;
    }

    public String getOpaWasmBundleUrl() {
        return opaWasmBundleUrl;
    }

    public void setOpaWasmBundleUrl(String opaWasmBundleUrl) {
        this.opaWasmBundleUrl = opaWasmBundleUrl;
    }

    public boolean isConsulEnabled() {
        return consulEnabled;
    }

    public void setConsulEnabled(boolean consulEnabled) {
        this.consulEnabled = consulEnabled;
    }

    public List<String> getConsulHosts() {
        return consulHosts;
    }

    public void setConsulHosts(List<String> consulHosts) {
        this.consulHosts = consulHosts;
    }

    public int getConsulTimerInterval() {
        return consulTimerInterval;
    }

    public void setConsulTimerInterval(int consulTimerInterval) {
        this.consulTimerInterval = consulTimerInterval;
    }

    public String getRoutesContextPath() {
        return routesContextPath;
    }

    public void setRoutesContextPath(String routesContextPath) {
        this.routesContextPath = routesContextPath;
    }

    public String getMetricsContextPath() {
        return metricsContextPath;
    }

    public void setMetricsContextPath(String metricsContextPath) {
        this.metricsContextPath = metricsContextPath;
    }

    public boolean isTracesEnabled() {
        return tracesEnabled;
    }

    public void setTracesEnabled(boolean tracesEnabled) {
        this.tracesEnabled = tracesEnabled;
    }

    public String getTracesEndpoint() {
        return tracesEndpoint;
    }

    public void setTracesEndpoint(String tracesEndpoint) {
        this.tracesEndpoint = tracesEndpoint;
    }

    public String getJavaVersion() {
        return javaVersion;
    }

    public void setJavaVersion(String javaVersion) {
        this.javaVersion = javaVersion;
    }

    public String getOauth2CookieName() {
        return oauth2CookieName;
    }

    public void setOauth2CookieName(String oauth2CookieName) {
        this.oauth2CookieName = oauth2CookieName;
    }

    public boolean isThrottleEnabled() { return throttleEnabled; }
    public void setThrottleEnabled(boolean throttleEnabled) { this.throttleEnabled = throttleEnabled; }

    public boolean isTrustStoreEnabled() { return trustStoreEnabled; }
    public void setTrustStoreEnabled(boolean trustStoreEnabled) { this.trustStoreEnabled = trustStoreEnabled; }

    public boolean isSslEnabled() { return sslEnabled; }
    public void setSslEnabled(boolean sslEnabled) { this.sslEnabled = sslEnabled; }

    public boolean isWebsocketEnabled() { return websocketEnabled; }
    public void setWebsocketEnabled(boolean websocketEnabled) { this.websocketEnabled = websocketEnabled; }

    public int getWebsocketPort() { return websocketPort; }
    public void setWebsocketPort(int websocketPort) { this.websocketPort = websocketPort; }

    public boolean isMcpEnabled() { return mcpEnabled; }
    public void setMcpEnabled(boolean mcpEnabled) { this.mcpEnabled = mcpEnabled; }

    public int getMcpPort() { return mcpPort; }
    public void setMcpPort(int mcpPort) { this.mcpPort = mcpPort; }

    public boolean isGrpcEnabled() { return grpcEnabled; }
    public void setGrpcEnabled(boolean grpcEnabled) { this.grpcEnabled = grpcEnabled; }

    public int getGrpcPort() { return grpcPort; }
    public void setGrpcPort(int grpcPort) { this.grpcPort = grpcPort; }

    public int getRestPort() { return restPort; }
    public void setRestPort(int restPort) { this.restPort = restPort; }

    public int getAdminPort() { return adminPort; }
    public void setAdminPort(int adminPort) { this.adminPort = adminPort; }

    public boolean isApiKeyStoreEnabled() { return apiKeyStoreEnabled; }
    public void setApiKeyStoreEnabled(boolean apiKeyStoreEnabled) { this.apiKeyStoreEnabled = apiKeyStoreEnabled; }

    public String getReverseProxyHost() { return reverseProxyHost; }
    public void setReverseProxyHost(String reverseProxyHost) { this.reverseProxyHost = reverseProxyHost; }

    public boolean isCorsEnabled() { return corsEnabled; }
    public void setCorsEnabled(boolean corsEnabled) { this.corsEnabled = corsEnabled; }
}