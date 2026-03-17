package io.surisoft.capi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.surisoft.capi.configuration.CapiSslContextHolder;
import io.surisoft.capi.exception.HttpErrorHandler;
import io.surisoft.capi.schema.*;
import io.surisoft.capi.utils.*;
import jakarta.annotation.Nullable;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.apache.hc.core5.ssl.TrustStrategy;
import org.cache2k.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

public class ConsulStore {

    private static final Logger log = LoggerFactory.getLogger(ConsulStore.class);
    private final Cache<String, ConsulKeyStoreEntry> consulTrustStoreCache;
    private final RouteUtils routeUtils;
    private final String consulKvHost;
    private final String consulKvToken;
    private final String capiTrustStorePassword;
    private final CapiSslContextHolder capiSslContextHolder;
    @Nullable
    private WebsocketUtils websocketUtils;
    @Nullable
    private GrpcUtils grpcUtils;
    @Nullable
    private HttpUtils httpUtils;
    @Nullable
    private Cache<String, Service> serviceCache;
    @Nullable
    private Map<String, RestClient> restClientMap;
    @Nullable
    private Map<String, WebsocketClient> websocketClientMap;
    @Nullable
    private Map<String, GrpcClient> grpcClientMap;
    private int globalResponseTimeout = 120000;
    private volatile HttpClient httpClient;

    private ObjectMapper objectMapper = new ObjectMapper();

    public ConsulStore(Cache<String, ConsulKeyStoreEntry> consulTrustStoreCache,
                       RouteUtils routeUtils,
                       String consulKvHost,
                       String consulKvToken,
                       String capiTrustStorePassword,
                       CapiSslContextHolder capiSslContextHolder,
                       HttpClient httpClient
    ) {
        this.consulTrustStoreCache = consulTrustStoreCache;
        this.routeUtils = routeUtils;
        this.consulKvHost = consulKvHost;
        this.consulKvToken = consulKvToken;
        this.capiTrustStorePassword = capiTrustStorePassword;
        this.capiSslContextHolder = capiSslContextHolder;
        this.httpClient = httpClient;
    }

    public void setWebsocketUtils(WebsocketUtils websocketUtils) {
        this.websocketUtils = websocketUtils;
    }

    public void setGrpcUtils(GrpcUtils grpcUtils) {
        this.grpcUtils = grpcUtils;
    }

    public void setHttpUtils(HttpUtils httpUtils) {
        this.httpUtils = httpUtils;
    }

    public void setServiceCache(Cache<String, Service> serviceCache) {
        this.serviceCache = serviceCache;
    }

    public void setRestClientMap(Map<String, RestClient> restClientMap) {
        this.restClientMap = restClientMap;
    }

    public void setWebsocketClientMap(Map<String, WebsocketClient> websocketClientMap) {
        this.websocketClientMap = websocketClientMap;
    }

    public void setGrpcClientMap(Map<String, GrpcClient> grpcClientMap) {
        this.grpcClientMap = grpcClientMap;
    }

    public void setGlobalResponseTimeout(int globalResponseTimeout) {
        this.globalResponseTimeout = globalResponseTimeout;
    }

    public void process() {
        log.debug("Looking for key values...");
        syncTrustStore();
    }

    private synchronized void syncTrustStore() {
        ConsulKeyStoreEntry cachedTrustStore = consulTrustStoreCache.get(Constants.CONSUL_CAPI_TRUST_STORE_GROUP_KEY);
        ConsulKeyStoreEntry remoteTrustStore = getRemoteTrustStore();
        try {
            //Found remote
            if(remoteTrustStore != null ) {
                if(cachedTrustStore != null) {
                    if(remoteTrustStore.getModifyIndex() != cachedTrustStore.getModifyIndex()) {
                        log.debug("The remote object is different from the local, lets update the local");
                        processTrustStore(remoteTrustStore);
                        consulTrustStoreCache.put(Constants.CONSUL_CAPI_TRUST_STORE_GROUP_KEY, remoteTrustStore);
                    } else {
                        log.debug("The remote object is equal to the local, nothing to do for now.");
                        consulTrustStoreCache.put(Constants.CONSUL_CAPI_TRUST_STORE_GROUP_KEY, remoteTrustStore);
                    }
                } else {
                    log.debug("Found remote trust store but not local, CAPI will cache the remote for the first time.");
                    processTrustStore(remoteTrustStore);
                    consulTrustStoreCache.put(Constants.CONSUL_CAPI_TRUST_STORE_GROUP_KEY, remoteTrustStore);
                }
            } else {
                log.debug("No remote keystore found, so nothing to do for now.");
            }
        } catch(Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    private ConsulKeyStoreEntry getRemoteTrustStore() {
        if(consulKvHost != null) {
            try {
                HttpResponse<String> response = httpClient.send(buildServicesHttpRequest(), HttpResponse.BodyHandlers.ofString());
                if(response.statusCode() != 200) {
                    log.error("Error getting remote trust store from Consul Store, status code: {}", response.statusCode());
                    return null;
                }
                ConsulKeyStoreEntry[] responseObject = objectMapper.readValue(response.body(), ConsulKeyStoreEntry[].class);
                return responseObject[0];
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }
        return null;
    }

    public InputStream consulKeyValueToInputStream(String encodedValue) throws JsonProcessingException {
        String decodedValue = new String(Base64.getDecoder().decode(encodedValue));
        return new ByteArrayInputStream(Base64.getDecoder().decode(decodedValue.getBytes()));
    }

    private void processTrustStore(ConsulKeyStoreEntry trustStoreConsulKeyStoreEntry) {
        try {
            log.info("Processing trust store update from Consul KV (modifyIndex={})", trustStoreConsulKeyStoreEntry.getModifyIndex());
            InputStream trustStoreInputStream = consulKeyValueToInputStream(trustStoreConsulKeyStoreEntry.getValue());

            KeyStore keyStore = KeyStore.getInstance("JKS");
            keyStore.load(trustStoreInputStream, capiTrustStorePassword.toCharArray());

            TrustStrategy trustStrategy = (X509Certificate[] chain, String authType) -> false;
            SSLContext sslContext = SSLContextBuilder
                    .create()
                    .loadTrustMaterial(keyStore, trustStrategy)
                    .build();

            capiSslContextHolder.setSslContext(sslContext);
            log.info("SSLContext updated in CapiSslContextHolder");

            if (websocketUtils != null) {
                websocketUtils.refreshXnioSsl();
                log.info("XnioSsl refreshed in WebsocketUtils");
                rebuildRestClientHandlers();
                rebuildWebsocketClientHandlers();
            }
            if (grpcUtils != null) {
                grpcUtils.refreshXnioSsl();
                log.info("XnioSsl refreshed in GrpcUtils");
                rebuildGrpcClientHandlers();
            }
        } catch (Exception e) {
            log.error("Failed to process trust store update: {}", e.getMessage(), e);
        }
    }

    private void rebuildRestClientHandlers() {
        if (restClientMap == null || serviceCache == null || websocketUtils == null) return;
        int count = 0;
        for (Map.Entry<String, RestClient> entry : restClientMap.entrySet()) {
            RestClient restClient = entry.getValue();
            String serviceKey = httpUtils != null ? httpUtils.contextToRole(restClient.getServiceId()) : restClient.getServiceId();
            Service service = serviceCache.get(serviceKey);
            if (service == null) continue;
            WebsocketClient tempClient = new WebsocketClient();
            tempClient.setMappingList(service.getMappingList());
            restClient.setHttpHandler(websocketUtils.createClientHttpHandler(tempClient, service, new HttpErrorHandler(httpUtils), globalResponseTimeout));
            count++;
        }
        log.info("Rebuilt {} REST client handler(s) with new XnioSsl", count);
    }

    private void rebuildWebsocketClientHandlers() {
        if (websocketClientMap == null || serviceCache == null || websocketUtils == null) return;
        int count = 0;
        for (Map.Entry<String, WebsocketClient> entry : websocketClientMap.entrySet()) {
            WebsocketClient wsClient = entry.getValue();
            String serviceKey = wsClient.getServiceId();
            Service service = serviceCache.get(serviceKey);
            if (service == null) continue;
            wsClient.setHttpHandler(websocketUtils.createClientHttpHandler(wsClient, service, null));
            count++;
        }
        log.info("Rebuilt {} WebSocket client handler(s) with new XnioSsl", count);
    }

    private void rebuildGrpcClientHandlers() {
        if (grpcClientMap == null || serviceCache == null || grpcUtils == null) return;
        int count = 0;
        for (Map.Entry<String, GrpcClient> entry : grpcClientMap.entrySet()) {
            GrpcClient grpcClient = entry.getValue();
            String serviceKey = grpcClient.getServiceId();
            Service service = serviceCache.get(serviceKey);
            if (service == null) continue;
            grpcClient.setHttpHandler(grpcUtils.createClientHttpHandler(grpcClient, service));
            count++;
        }
        log.info("Rebuilt {} gRPC client handler(s) with new XnioSsl", count);
    }

    private HttpRequest buildServicesHttpRequest() {
        HttpRequest.Builder builder = HttpRequest.newBuilder();
        URI uri = URI.create(consulKvHost + Constants.CONSUL_KV_STORE_API + Constants.CONSUL_CAPI_TRUST_STORE_GROUP_KEY);
        if (uri.getPath() != null && uri.getPath().contains("..")) {
            throw new IllegalArgumentException("Path traversal detected in URI path: " + uri.getPath());
        }
        if(consulKvToken != null && !consulKvToken.isEmpty()) {
            builder.header(Constants.AUTHORIZATION_HEADER, Constants.BEARER + consulKvToken.replaceAll("(\r\n|\n)", ""));
        }
        return builder
                .uri(uri)
                .timeout(Duration.ofMinutes(2))
                .build();
    }
}