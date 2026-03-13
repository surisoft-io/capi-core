package io.surisoft.capi.processor;

import io.surisoft.capi.exception.AuthorizationException;
import io.surisoft.capi.oidc.Oauth2Constants;
import io.surisoft.capi.schema.ApiKeyEntry;
import io.surisoft.capi.schema.ApiKeyStoreEntry;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.service.OpaService;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.HttpUtils;
import jakarta.annotation.Nullable;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.cache2k.Cache;

import java.text.ParseException;

public class AuthorizationProcessor implements Processor {

    private final HttpUtils httpUtils;
    private final Cache<String, Service> serviceCache;
    private final OpaService opaService;
    @Nullable
    private final Cache<String, ApiKeyStoreEntry> apiKeyCache;

    public AuthorizationProcessor(HttpUtils httpUtils, Cache<String, Service> serviceCache, @Nullable OpaService opaService, @Nullable Cache<String, ApiKeyStoreEntry> apiKeyCache) {
        this.httpUtils = httpUtils;
        this.serviceCache = serviceCache;
        this.opaService = opaService;
        this.apiKeyCache = apiKeyCache;
    }

    @Override
    public void process(Exchange exchange) {
        String contextPath = (String) exchange.getIn().getHeader(Oauth2Constants.CAMEL_SERVLET_CONTEXT_PATH);
        try {
            Service service = serviceCache.get(httpUtils.contextToRole(contextPath));
            assert service != null;

            // API Key authentication check (before OAuth2)
            if (service.getServiceMeta().isApiKeyEnabled()) {
                String rawApiKey = httpUtils.extractApiKey(exchange);
                if (rawApiKey != null) {
                    if (apiKeyCache == null) {
                        sendException(exchange, "API key store not configured");
                        return;
                    }
                    ApiKeyStoreEntry storeEntry = apiKeyCache.get(service.getId());
                    if (storeEntry == null) {
                        sendException(exchange, "Invalid API key");
                        return;
                    }
                    String keyHash = HttpUtils.hashApiKey(rawApiKey);
                    ApiKeyEntry apiKeyEntry = storeEntry.getKeysByHash().get(keyHash);
                    if (apiKeyEntry == null || !apiKeyEntry.isEnabled()) {
                        sendException(exchange, "Invalid API key");
                        return;
                    }
                    // Valid API key — set throttle if configured, remove auth header, and return (bypass OAuth2)
                    prepareApiKeyThrottle(service, apiKeyEntry, exchange, keyHash);
                    exchange.getIn().removeHeader(Constants.AUTHORIZATION_HEADER);
                    return;
                }
                // No API key header present
                if (!service.getServiceMeta().isSecured()) {
                    // Service only uses API key auth, no OAuth2 fallback
                    sendException(exchange, "API key required");
                    return;
                }
                // Fall through to OAuth2 flow
            }

            // Existing OAuth2 flow
            String accessToken = httpUtils.processAuthorizationAccessToken(exchange);
            if (accessToken != null) {
                if (!httpUtils.isAuthorized(accessToken, contextPath, service, opaService)) {
                    sendException(exchange, "Not subscribed");
                }
                httpUtils.propagateAuthorization(exchange, accessToken);
                httpUtils.prepareForThrottleIfNeeded(service, accessToken, exchange);
            } else {
                sendException(exchange, "No authorization header provided");
            }
        } catch (AuthorizationException | ParseException e) {
            sendException(exchange, e.getMessage());
        }
    }

    private void prepareApiKeyThrottle(Service service, ApiKeyEntry apiKeyEntry, Exchange exchange, String keyHash) {
        if (apiKeyEntry.getThrottleTotalCalls() > 0 && apiKeyEntry.getThrottleDuration() > 0) {
            String consumerKey = "apikey:" + keyHash;
            exchange.setProperty(Constants.CAPI_META_THROTTLE_DURATION, apiKeyEntry.getThrottleDuration());
            exchange.setProperty(Constants.CAPI_META_THROTTLE_TOTAL_CALLS_ALLOWED, apiKeyEntry.getThrottleTotalCalls());
            exchange.getIn().setHeader(Constants.CAPI_META_THROTTLE_CONSUMER_KEY, consumerKey);
            exchange.getIn().setHeader(Constants.CAPI_META_THROTTLE_DURATION, apiKeyEntry.getThrottleDuration());
            exchange.getIn().setHeader(Constants.CAPI_META_THROTTLE_TOTAL_CALLS_ALLOWED, apiKeyEntry.getThrottleTotalCalls());
        }
    }

    private void sendException(Exchange exchange, String message) {
        exchange.setProperty(Constants.REASON_MESSAGE_HEADER, message);
        exchange.getIn().setHeader(Constants.REASON_MESSAGE_HEADER, message);
        exchange.getIn().setHeader(Constants.REASON_CODE_HEADER, 403);
        exchange.setException(new AuthorizationException(message));
    }
}
