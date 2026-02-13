package io.surisoft.capi.processor;

import io.surisoft.capi.exception.AuthorizationException;
import io.surisoft.capi.oidc.Oauth2Constants;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.service.OpaService;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.HttpUtils;
import jakarta.annotation.Nullable;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.cache2k.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.ParseException;
import java.util.Optional;

//@ConditionalOnProperty(prefix = "capi.oauth2.provider", name = "enabled", havingValue = "true")
public class AuthorizationProcessor implements Processor {
    private static final Logger log = LoggerFactory.getLogger(AuthorizationProcessor.class);
    private final HttpUtils httpUtils;
    private final Cache<String, Service> serviceCache;
    private final OpaService opaService;

    public AuthorizationProcessor(HttpUtils httpUtils, Cache<String, Service> serviceCache, @Nullable OpaService opaService) {
        this.httpUtils = httpUtils;
        this.serviceCache = serviceCache;
        this.opaService = opaService;
    }

    @Override
    public void process(Exchange exchange) {
        String contextPath = (String) exchange.getIn().getHeader(Oauth2Constants.CAMEL_SERVLET_CONTEXT_PATH);
        String accessToken;
        try {
            accessToken = httpUtils.processAuthorizationAccessToken(exchange);
            Service service = serviceCache.get(httpUtils.contextToRole(contextPath));
            assert service != null;

            if(accessToken != null) {
                if(!httpUtils.isAuthorized(accessToken, contextPath, service, opaService)) {
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

    private void sendException(Exchange exchange, String message) {
        exchange.setProperty(Constants.REASON_MESSAGE_HEADER, message);
        exchange.getIn().setHeader(Constants.REASON_MESSAGE_HEADER, message);
        exchange.getIn().setHeader(Constants.REASON_CODE_HEADER, 403);
        exchange.setException(new AuthorizationException(message));
    }
}