package io.surisoft.capi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.surisoft.capi.configuration.CapiSslContextHolder;
import io.surisoft.capi.schema.ConsulKeyStoreEntry;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.RouteUtils;
import org.apache.camel.CamelContext;
import org.apache.camel.component.http.HttpComponent;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.apache.hc.core5.ssl.TrustStrategy;
import org.cache2k.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;

public class ConsulStore {

    private static final Logger log = LoggerFactory.getLogger(ConsulStore.class);
    private final Cache<String, ConsulKeyStoreEntry> consulTrustStoreCache;
    private final RouteUtils routeUtils;
    private final String consulKvHost;
    private final String consulKvToken;
    private final String capiTrustStorePassword;
    private final CapiSslContextHolder capiSslContextHolder;
    private final CamelContext camelContext;
    private HttpClient httpClient;

    private ObjectMapper objectMapper = new ObjectMapper();

    public ConsulStore(Cache<String, ConsulKeyStoreEntry> consulTrustStoreCache,
                       RouteUtils routeUtils,
                       String consulKvHost,
                       String consulKvToken,
                       String capiTrustStorePassword,
                       CapiSslContextHolder capiSslContextHolder,
                       CamelContext camelContext,
                       HttpClient httpClient
    ) {
        this.consulTrustStoreCache = consulTrustStoreCache;
        this.routeUtils = routeUtils;
        this.consulKvHost = consulKvHost;
        this.consulKvToken = consulKvToken;
        this.capiTrustStorePassword = capiTrustStorePassword;
        this.capiSslContextHolder = capiSslContextHolder;
        this.camelContext = camelContext;
        this.httpClient = httpClient;
    }

    public void process() {
        log.debug("Looking for key values...");
        syncTrustStore();
    }

    private void syncTrustStore() {
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

    private void processTrustStore(ConsulKeyStoreEntry trustStoreConsulKeyStoreEntry) throws IOException {
        try (InputStream trustStoreInputStream = consulKeyValueToInputStream(trustStoreConsulKeyStoreEntry.getValue())) {
            routeUtils.reloadTrustStoreManager(trustStoreInputStream, capiTrustStorePassword);
        }

        try {
            //Reload SSL Context Used By HTTP Client
            HttpComponent httpComponent = (HttpComponent) camelContext.getComponent("https");
            CapiTrustManager capiTrustManager = (CapiTrustManager) httpComponent.getSslContextParameters().getTrustManagers().getTrustManager();
            TrustStrategy trustStrategy = (X509Certificate[] chain, String authType) -> false;
            SSLContext sslContext = SSLContextBuilder
                        .create()
                        .loadTrustMaterial(capiTrustManager.getKeyStore(), trustStrategy)
                        .build();
            capiSslContextHolder.setSslContext(sslContext);

            //Reload HTTP Client Used By both Consul Node discovery and this Consul Store
            HttpClient.Builder httpClientBuilder = HttpClient.newBuilder();
            httpClientBuilder.sslContext(capiSslContextHolder.getSslContext());

            httpClientBuilder.connectTimeout(Duration.ofSeconds(10));
            httpClient = httpClientBuilder.build();

        } catch (NoSuchAlgorithmException | KeyManagementException | KeyStoreException e) {
            throw new RuntimeException(e);
        }
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