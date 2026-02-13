package io.surisoft.capi.configuration;

import java.util.List;

public class CAPIConfiguration {

    private String version;
    private String instanceName;
    private String runningMode;
    private TrustStore trustStore;
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

    public String getRunningMode() {
        return runningMode;
    }
    public void setRunningMode(String runningMode) {
        this.runningMode = runningMode;
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
        private int connectionRequestTimeout;
        private int requestTimeout;
        private int responseTimeout;

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

    }

    public static class Websocket {
        private boolean enabled;
        private int port;
        private String listeningAddress;
        private String contextPath;

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


}
