package io.surisoft.capi.configuration;

import io.surisoft.capi.undertow.CAPILoadBalancerProxyClient;
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
    private List<String> allowedOrigins;
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
    private Observability observability = new Observability();
    private Admin admin = new Admin();
    private MatchOpenApiSpec matchOpenApiSpec = new MatchOpenApiSpec();

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
        /** Deployment environment (dev/acc/prod). Emitted as the deployment.environment resource attribute. */
        private String appEnvironment;

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
        public String getAppEnvironment() {
            return appEnvironment;
        }
        public void setAppEnvironment(String appEnvironment) {
            this.appEnvironment = appEnvironment;
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
        /**
         * Refuse a request whose path contains a {@code .} or {@code ..} segment with 400.
         *
         * <p>Off by default. While off the guard only counts what it sees
         * ({@code capi_dot_segment_requests_total{action="observed"}}), because refusing a request an
         * existing deployment currently forwards is a behaviour change. Watch the counter on real
         * traffic, then turn it on.
         */
        private boolean rejectDotSegments = false;
        private int port;
        private String listeningAddress;
        private String contextPath;
        private int ioThreads = Math.max(2, Runtime.getRuntime().availableProcessors());
        private int connectionRequestTimeout;
        private int requestTimeout;
        private int responseTimeout;
        private int proxyPoolSize = 200;
        private int proxyMaxPoolSize = 500;
        /** How long a backend connection may sit unused in the pool before it is closed. Must stay
         *  below the shortest idle timeout on the path (firewall/NAT/LB), otherwise a silently
         *  dropped flow can be leased out again and the request stalls until responseTimeout. */
        private int connectionIdleTimeout = 30000;
        /** Bound on acquiring a backend connection (TCP connect + TLS handshake, or waiting for a
         *  free pooled connection). An unreachable host never fails on its own — its SYN is simply
         *  dropped — so without this a request stalls until responseTimeout and returns 504 instead
         *  of failing over to a healthy instance. 0 disables the watchdog. */
        private int connectTimeout = CAPILoadBalancerProxyClient.PoolSettings.DEFAULT_CONNECT_TIMEOUT_MS;

        public int getConnectTimeout() {
            return connectTimeout;
        }
        public void setConnectTimeout(int connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public boolean isEnabled() {
            return enabled;
        }
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isRejectDotSegments() {
            return rejectDotSegments;
        }

        public void setRejectDotSegments(boolean rejectDotSegments) {
            this.rejectDotSegments = rejectDotSegments;
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
        public int getConnectionIdleTimeout() {
            return connectionIdleTimeout;
        }
        public void setConnectionIdleTimeout(int connectionIdleTimeout) {
            this.connectionIdleTimeout = connectionIdleTimeout;
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
        private String wasmBundleUrl;
        private String wasmBundleToken;
        private int wasmBundlePollIntervalSeconds = 60;
        private int wasmPoolSize = 10;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getWasmBundleUrl() { return wasmBundleUrl; }
        public void setWasmBundleUrl(String wasmBundleUrl) { this.wasmBundleUrl = wasmBundleUrl; }
        public String getWasmBundleToken() { return wasmBundleToken; }
        public void setWasmBundleToken(String wasmBundleToken) { this.wasmBundleToken = wasmBundleToken; }
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

    /**
     * Browser origins that may receive CORS headers. Empty or absent denies every origin; a single
     * {@code "*"} entry sends the literal wildcard without credentials. See {@link io.surisoft.capi.utils.CorsPolicy}.
     */
    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
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

    public Observability getObservability() {
        return observability;
    }
    public void setObservability(Observability observability) {
        this.observability = observability;
    }

    public MatchOpenApiSpec getMatchOpenApiSpec() {
        return matchOpenApiSpec;
    }

    public void setMatchOpenApiSpec(MatchOpenApiSpec matchOpenApiSpec) {
        this.matchOpenApiSpec = matchOpenApiSpec;
    }

    public Admin getAdmin() {
        return admin;
    }
    public void setAdmin(Admin admin) {
        this.admin = admin;
    }

    /**
     * Authorization on the admin listener ({@code adminPort}).
     *
     * <p>When on, every admin endpoint except the health probe requires a bearer token whose
     * subscription claim contains {@code group}; the token is validated by the same OAuth2 key set
     * the data plane uses, so {@code oauth2.enabled} must be true for the check to ever pass.
     *
     * <p>{@code protected} is deliberately a nullable {@link Boolean} so that "absent from the
     * config" is distinguishable from "explicitly false". An absent value keeps the pre-2.22
     * behaviour — unprotected, with a warning — because failing startup on every config that never
     * opted in would crash-loop existing deployments on upgrade. An explicit {@code true} with no
     * {@code group} is contradictory and does fail startup.
     *
     * <p>Either way the trust-store mutation endpoints stay closed on an unprotected listener (see
     * {@code AdminGateway#requireProtectedListener}), so the fleet-wide MITM path is shut
     * regardless of what this says. What an unprotected listener still exposes is read access.
     */
    public static class Admin {
        private Boolean protectedEndpoint;
        private String group;

        // Named getProtected/setProtected so the YAML key is "protected" — the field cannot carry
        // that name because it is a Java keyword. Must be get* rather than is*: JavaBeans
        // introspection, which SnakeYAML relies on, only accepts is* for the primitive boolean.
        public Boolean getProtected() {
            return protectedEndpoint;
        }
        public void setProtected(Boolean protectedEndpoint) {
            this.protectedEndpoint = protectedEndpoint;
        }

        /** True only when the operator explicitly asked for protection. */
        public boolean isProtectedEnabled() {
            return Boolean.TRUE.equals(protectedEndpoint);
        }

        /** True when the config says nothing at all about admin protection. */
        public boolean isUnset() {
            return protectedEndpoint == null;
        }

        public String getGroup() {
            return group;
        }
        public void setGroup(String group) {
            this.group = group;
        }
    }

    /**
     * Governance check binding a fetched OpenAPI spec to the service that declared it: the spec's
     * {@code info.title} must equal the Consul service name, and {@code info.version} must be set.
     *
     * <p>Off by default. The verdict is computed on every cycle regardless, and published at
     * {@code /info/spec-compliance}, so an estate can be measured before the switch is flipped —
     * turning it on blind would strand every service whose spec predates the convention. Nothing is
     * logged while it is off, so enabling the report cannot make an existing deployment noisier.
     *
     * <p>{@code exempt} names services that stay routable even while enforcing. It is operator
     * config on purpose: an exemption a service owner could grant themselves is not governance, and
     * without one the last unowned service keeps the switch off for everyone else.
     */
    public static class MatchOpenApiSpec {
        private boolean enabled = false;
        private List<String> exempt;

        public boolean isEnabled() {
            return enabled;
        }
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /** Consul service names (not name:group) exempt from enforcement. */
        public List<String> getExempt() {
            return exempt;
        }
        public void setExempt(List<String> exempt) {
            this.exempt = exempt;
        }
    }

    /** Top-level observability config — currently just JVM-level (JFR). */
    public static class Observability {
        private Jvm jvm = new Jvm();

        public Jvm getJvm() { return jvm; }
        public void setJvm(Jvm jvm) { this.jvm = jvm; }

        /** In-memory JFR aggregators exposed via /info/jvm/*. Opt-in. */
        public static class Jvm {
            private boolean enabled = false;
            private int sampleIntervalMs = 20;
            private int retentionSamples = 15_000;

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }

            public int getSampleIntervalMs() { return sampleIntervalMs; }
            public void setSampleIntervalMs(int sampleIntervalMs) { this.sampleIntervalMs = sampleIntervalMs; }

            public int getRetentionSamples() { return retentionSamples; }
            public void setRetentionSamples(int retentionSamples) { this.retentionSamples = retentionSamples; }
        }
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
        /**
         * Issuer URLs advertised as {@code authorization_servers} in the RFC 9728 protected-resource
         * metadata served on the MCP listener. Optional: when unset CAPI derives them from
         * {@code oauth2.keys} by trimming the usual JWKS suffixes, which covers the common
         * OIDC layouts but not every provider. Set explicitly if the derivation is wrong.
         */
        private java.util.List<String> authorizationServers;
        private Observability observability = new Observability();

        public java.util.List<String> getAuthorizationServers() {
            return authorizationServers;
        }

        public void setAuthorizationServers(java.util.List<String> authorizationServers) {
            this.authorizationServers = authorizationServers;
        }


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
        public Observability getObservability() {
            return observability;
        }
        public void setObservability(Observability observability) {
            this.observability = observability;
        }

        public Signing getSigning() {
            return signing;
        }
        public void setSigning(Signing signing) {
            this.signing = signing;
        }

        private Signing signing = new Signing();

        public static class Signing {
            /** off | warn | enforce */
            private String mode = "off";

            public String getMode() {
                return mode;
            }
            public void setMode(String mode) {
                this.mode = mode;
            }
        }

        public static class Observability {
            private GenAi genAi = new GenAi();

            public GenAi getGenAi() {
                return genAi;
            }
            public void setGenAi(GenAi genAi) {
                this.genAi = genAi;
            }

            public static class GenAi {
                private boolean enabled = false;

                public boolean isEnabled() {
                    return enabled;
                }
                public void setEnabled(boolean enabled) {
                    this.enabled = enabled;
                }
            }
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
