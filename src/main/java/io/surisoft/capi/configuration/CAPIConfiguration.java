package io.surisoft.capi.configuration;

import java.util.List;

public class CAPIConfiguration {

    private String version;
    private String instanceName;
    private boolean strictToInstanceName;
    private String runningMode;
    private TrustStore trustStore;
    private int consulCatalogDiscoverInterval;
    private List<HostConfig> consulHosts;
    private Oauth2 oauth2;
    private Traces traces;
    private int adminPort;
    private boolean corsEnabled;
    private List<String> allowedHeaders;
    private Ssl ssl;
    private Rest rest;
    private Websocket websocket;
    private String publicEndpoint;
    private String reverseProxyHost;
    private ConsulStore consulStore;
    private Opa opa;
    private LoggingTraces loggingTraces;
    private AccessLogs accessLogs;
    private Throttle throttle;
    private Mcp mcp;
    private Grpc grpc;
    private ApiKeyStore apiKeyStore;

    public String getVersion() {
        return version;
    }
    public void setVersion(String version) {
        this.version = version;
    }

    public String getInstanceName() {
        return instanceName;
    }
    public void setInstanceName(String instanceName) {
        this.instanceName = instanceName;
    }

    public boolean isStrictToInstanceName() {
        return strictToInstanceName;
    }
    public void setStrictToInstanceName(boolean strictToInstanceName) {
        this.strictToInstanceName = strictToInstanceName;
    }

    public String getRunningMode() {
        return runningMode;
    }
    public void setRunningMode(String runningMode) {
        this.runningMode = runningMode;
    }

    public int getConsulCatalogDiscoverInterval() {
        return consulCatalogDiscoverInterval;
    }
    public void setConsulCatalogDiscoverInterval(int consulCatalogDiscoverInterval) {
        this.consulCatalogDiscoverInterval = consulCatalogDiscoverInterval;
    }

    public String getReverseProxyHost() {
        return reverseProxyHost;
    }
    public void setReverseProxyHost(String reverseProxyHost) {
        this.reverseProxyHost = reverseProxyHost;
    }


    public static class Traces {
        private boolean enabled;
        private String serviceName;
        private String endpoint;
        private String extraMetadataPrefix;

        public boolean isEnabled() {
            return enabled;
        }
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        public String getEndpoint() {
            return endpoint;
        }
        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }
        public String getExtraMetadataPrefix() {
            return extraMetadataPrefix;
        }
        public void setExtraMetadataPrefix(String extraMetadataPrefix) {
            this.extraMetadataPrefix = extraMetadataPrefix;
        }
        public String getServiceName() {
            return serviceName;
        }
        public void setServiceName(String serviceName) {
            this.serviceName = serviceName;
        }
    }

    public static class Oauth2 {
        private boolean enabled;
        private String cookieName;
        private List<String> keys;

        public boolean isEnabled() {
            return enabled;
        }
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        public String getCookieName() {
            return cookieName;
        }
        public void setCookieName(String cookieName) {
            this.cookieName = cookieName;
        }

        public List<String> getKeys() {
            return keys;
        }
        public void setKeys(List<String> keys) {
            this.keys = keys;
        }
    }

    public static class TrustStore {
        private boolean enabled;
        private String path;
        private String encoded;
        private String password;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getEncoded() {
            return encoded;
        }

        public void setEncoded(String encoded) {
            this.encoded = encoded;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    public static class HostConfig {
        private String endpoint;
        private String token;

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }
    }

    public static class Ssl {
        private boolean enabled;
        private String keyStoreType;
        private String path;
        private String password;

        public boolean isEnabled() {
            return enabled;
        }
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        public String getKeyStoreType() {
            return keyStoreType;
        }
        public void setKeyStoreType(String keyStoreType) {
            this.keyStoreType = keyStoreType;
        }
        public String getPath() {
            return path;
        }
        public void setPath(String path) {
            this.path = path;
        }
        public String getPassword() {
            return password;
        }
        public void setPassword(String password) {
            this.password = password;
        }
    }

    public static class Rest {
        private boolean enabled;
        private int port;
        private String listeningAddress;
        private String contextPath;
        private int ioThreads = Math.max(2, Runtime.getRuntime().availableProcessors());
        private int connectionRequestTimeout;
        private int requestTimeout;
        private int responseTimeout;
        private int proxyPoolSize = 200;
        private int proxyMaxPoolSize = 500;

        public boolean isEnabled() {
            return enabled;
        }
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        public int getPort() {
            return port;
        }
        public void setPort(int port) {
            this.port = port;
        }
        public String getListeningAddress() {
            return listeningAddress;
        }
        public void setListeningAddress(String listeningAddress) {
            this.listeningAddress = listeningAddress;
        }
        public String getContextPath() {
            return contextPath;
        }
        public void setContextPath(String contextPath) {
            this.contextPath = contextPath;
        }

        public int getConnectionRequestTimeout() {
            return connectionRequestTimeout;
        }
        public void setConnectionRequestTimeout(int connectionRequestTimeout) {
            this.connectionRequestTimeout = connectionRequestTimeout;
        }
        public int getRequestTimeout() {
            return requestTimeout;
        }
        public void setRequestTimeout(int requestTimeout) {
            this.requestTimeout = requestTimeout;
        }
        public int getResponseTimeout() {
            return responseTimeout;
        }
        public void setResponseTimeout(int responseTimeout) {
            this.responseTimeout = responseTimeout;
        }
        public int getIoThreads() {
            return ioThreads;
        }
        public void setIoThreads(int ioThreads) {
            this.ioThreads = ioThreads;
        }
        public int getProxyPoolSize() {
            return proxyPoolSize;
        }
        public void setProxyPoolSize(int proxyPoolSize) {
            this.proxyPoolSize = proxyPoolSize;
        }
        public int getProxyMaxPoolSize() {
            return proxyMaxPoolSize;
        }
        public void setProxyMaxPoolSize(int proxyMaxPoolSize) {
            this.proxyMaxPoolSize = proxyMaxPoolSize;
        }

    }

    public static class Websocket {
        private boolean enabled;
        private int port;
        private String listeningAddress;
        private String contextPath;
        private int ioThreads = Math.max(2, Runtime.getRuntime().availableProcessors() * 2);
        private int responseTimeout = 180000;

        public boolean isEnabled() {
            return enabled;
        }
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        public int getPort() {
            return port;
        }
        public void setPort(int port) {
            this.port = port;
        }
        public String getListeningAddress() {
            return listeningAddress;
        }
        public void setListeningAddress(String listeningAddress) {
            this.listeningAddress = listeningAddress;
        }
        public String getContextPath() {
            return contextPath;
        }
        public void setContextPath(String contextPath) {
            this.contextPath = contextPath;
        }
        public int getIoThreads() {
            return ioThreads;
        }
        public void setIoThreads(int ioThreads) {
            this.ioThreads = ioThreads;
        }
        public int getResponseTimeout() {
            return responseTimeout;
        }
        public void setResponseTimeout(int responseTimeout) {
            this.responseTimeout = responseTimeout;
        }
    }

    public static class ConsulStore {
        private boolean enabled;
        private String endpoint;
        private String token;

        public boolean isEnabled() {
            return enabled;
        }
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        public String getEndpoint() {
            return endpoint;
        }
        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }
        public String getToken() {
            return token;
        }
        public void setToken(String token) {
            this.token = token;
        }
    }

    public static class Opa {
        private boolean enabled;
        private String endpoint;
        private boolean wasmEnabled;
        private String wasmBundleUrl;
        private int wasmBundlePollIntervalSeconds = 60;
        private int wasmPoolSize = 10;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public boolean isWasmEnabled() { return wasmEnabled; }
        public void setWasmEnabled(boolean wasmEnabled) { this.wasmEnabled = wasmEnabled; }
        public String getWasmBundleUrl() { return wasmBundleUrl; }
        public void setWasmBundleUrl(String wasmBundleUrl) { this.wasmBundleUrl = wasmBundleUrl; }
        public int getWasmBundlePollIntervalSeconds() { return wasmBundlePollIntervalSeconds; }
        public void setWasmBundlePollIntervalSeconds(int v) { this.wasmBundlePollIntervalSeconds = v; }
        public int getWasmPoolSize() { return wasmPoolSize; }
        public void setWasmPoolSize(int wasmPoolSize) { this.wasmPoolSize = wasmPoolSize; }
    }

    public static class LoggingTraces {
        private boolean enabled;
        private String tenant;
        private String appName;
        private String appEnvironment;
        private String destination;
        private String filePath;

        public boolean isEnabled() {
            return enabled;
        }
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        public String getTenant() {
            return tenant;
        }
        public void setTenant(String tenant) {
            this.tenant = tenant;
        }
        public String getAppName() {
            return appName;
        }
        public void setAppName(String appName) {
            this.appName = appName;
        }
        public String getAppEnvironment() {
            return appEnvironment;
        }
        public void setAppEnvironment(String appEnvironment) {
            this.appEnvironment = appEnvironment;
        }
        public String getDestination() {
            return destination;
        }
        public void setDestination(String destination) {
            this.destination = destination;
        }
        public String getFilePath() {
            return filePath;
        }
        public void setFilePath(String filePath) {
            this.filePath = filePath;
        }
    }

    public static class AccessLogs {
        private boolean enabled;
        private String tenant;
        private String service;
        private String destination;
        private String filePath;

        public boolean isEnabled() {
            return enabled;
        }
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        public String getTenant() {
            return tenant;
        }
        public void setTenant(String tenant) {
            this.tenant = tenant;
        }
        public String getService() {
            return service;
        }
        public void setService(String service) {
            this.service = service;
        }
        public String getDestination() {
            return destination;
        }
        public void setDestination(String destination) {
            this.destination = destination;
        }
        public String getFilePath() {
            return filePath;
        }
        public void setFilePath(String filePath) {
            this.filePath = filePath;
        }
    }



    public TrustStore getTrustStore() {
        return trustStore;
    }

    public void setTrustStore(TrustStore trustStore) {
        this.trustStore = trustStore;
    }

    public List<HostConfig> getConsulHosts() {
        return consulHosts;
    }

    public void setConsulHosts(List<HostConfig> consulHosts) {
        this.consulHosts = consulHosts;
    }

    public Oauth2 getOauth2() {
        return oauth2;
    }
    public void setOauth2(Oauth2 oauth2) {
        this.oauth2 = oauth2;
    }
    public Traces getTraces() {
        return traces;
    }
    public void setTraces(Traces traces) {
        this.traces = traces;
    }

    public int getAdminPort() {
        return adminPort;
    }
    public void setAdminPort(int adminPort) {
        this.adminPort = adminPort;
    }

    public boolean isCorsEnabled() {
        return corsEnabled;
    }
    public void setCorsEnabled(boolean corsEnabled) {
        this.corsEnabled = corsEnabled;
    }

    public List<String> getAllowedHeaders() {
        return allowedHeaders;
    }
    public void setAllowedHeaders(List<String> allowedHeaders) {
        this.allowedHeaders = allowedHeaders;
    }


    public Ssl getSsl() {
        return ssl;
    }

    public void setSsl(Ssl ssl) {
        this.ssl = ssl;
    }

    public Rest getRest() {
        return rest;
    }
    public void setRest(Rest rest) {
        this.rest = rest;
    }

    public Websocket getWebsocket() {
        return websocket;
    }
    public void setWebsocket(Websocket websocket) {
        this.websocket = websocket;
    }
    public String getPublicEndpoint() {
        return publicEndpoint;
    }
    public void setPublicEndpoint(String publicEndpoint) {
        this.publicEndpoint = publicEndpoint;
    }

    public ConsulStore getConsulStore() {
        return consulStore;
    }
    public void setConsulStore(ConsulStore consulKVStore) {
        this.consulStore = consulKVStore;
    }

    public Opa getOpa() {
        return opa;
    }
    public void setOpa(Opa opa) {
        this.opa = opa;
    }
    public LoggingTraces getLoggingTraces() {
        return loggingTraces;
    }
    public void setLoggingTraces(LoggingTraces loggingTraces) {
        this.loggingTraces = loggingTraces;
    }
    public AccessLogs getAccessLogs() {
        return accessLogs;
    }
    public void setAccessLogs(AccessLogs accessLogs) {
        this.accessLogs = accessLogs;
    }

    public Throttle getThrottle() {
        return throttle;
    }
    public void setThrottle(Throttle throttle) {
        this.throttle = throttle;
    }

    public Mcp getMcp() {
        return mcp;
    }
    public void setMcp(Mcp mcp) {
        this.mcp = mcp;
    }

    public Grpc getGrpc() {
        return grpc;
    }
    public void setGrpc(Grpc grpc) {
        this.grpc = grpc;
    }

    public ApiKeyStore getApiKeyStore() {
        return apiKeyStore;
    }
    public void setApiKeyStore(ApiKeyStore apiKeyStore) {
        this.apiKeyStore = apiKeyStore;
    }

    public static class Grpc {
        private boolean enabled;
        private int port = 8384;

        public boolean isEnabled() {
            return enabled;
        }
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        public int getPort() {
            return port;
        }
        public void setPort(int port) {
            this.port = port;
        }
    }

    public static class Mcp {
        private boolean enabled;
        private int port = 8383;
        private long sessionTtl = 1800000;
        private int toolCallTimeout = 30000;
        private long circuitBreakerCooldownMs = 30000;
        private int mcpServerDiscoveryTimeoutMs = 10000;

        public boolean isEnabled() {
            return enabled;
        }
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        public int getPort() {
            return port;
        }
        public void setPort(int port) {
            this.port = port;
        }
        public long getSessionTtl() {
            return sessionTtl;
        }
        public void setSessionTtl(long sessionTtl) {
            this.sessionTtl = sessionTtl;
        }
        public int getToolCallTimeout() {
            return toolCallTimeout;
        }
        public void setToolCallTimeout(int toolCallTimeout) {
            this.toolCallTimeout = toolCallTimeout;
        }
        public long getCircuitBreakerCooldownMs() {
            return circuitBreakerCooldownMs;
        }
        public void setCircuitBreakerCooldownMs(long circuitBreakerCooldownMs) {
            this.circuitBreakerCooldownMs = circuitBreakerCooldownMs;
        }
        public int getMcpServerDiscoveryTimeoutMs() {
            return mcpServerDiscoveryTimeoutMs;
        }
        public void setMcpServerDiscoveryTimeoutMs(int mcpServerDiscoveryTimeoutMs) {
            this.mcpServerDiscoveryTimeoutMs = mcpServerDiscoveryTimeoutMs;
        }
    }

    public static class ApiKeyStore {
        private boolean enabled;

        public boolean isEnabled() {
            return enabled;
        }
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Throttle {
        private boolean enabled;
        private String kubernetesNamespace;
        private String kubernetesServiceName;

        public boolean isEnabled() {
            return enabled;
        }
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        public String getKubernetesNamespace() {
            return kubernetesNamespace;
        }
        public void setKubernetesNamespace(String kubernetesNamespace) {
            this.kubernetesNamespace = kubernetesNamespace;
        }
        public String getKubernetesServiceName() {
            return kubernetesServiceName;
        }
        public void setKubernetesServiceName(String kubernetesServiceName) {
            this.kubernetesServiceName = kubernetesServiceName;
        }
    }
}
