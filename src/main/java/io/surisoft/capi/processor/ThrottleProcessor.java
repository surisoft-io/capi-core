package io.surisoft.capi.processor;

import com.hazelcast.map.IMap;
import io.surisoft.capi.exception.AuthorizationException;
import io.surisoft.capi.oidc.Oauth2Constants;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ThrottleServiceObject;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.HttpUtils;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.cache2k.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ThrottleProcessor implements Processor {

    private static final Logger log = LoggerFactory.getLogger(ThrottleProcessor.class);
    private final Cache<String, Service> serviceCache;
    private final HttpUtils httpUtils;
    private final IMap<String, ThrottleServiceObject> throttleServiceObjectCache;

    public ThrottleProcessor(Cache<String, Service> serviceCache,
                             HttpUtils httpUtils,
                             IMap<String, ThrottleServiceObject> throttleServiceObjectCache) {
        this.serviceCache = serviceCache;
        this.httpUtils = httpUtils;
        this.throttleServiceObjectCache = throttleServiceObjectCache;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        try {
            String contextPath = (String) exchange.getIn().getHeader(Oauth2Constants.CAMEL_SERVLET_CONTEXT_PATH);
            Service service = serviceCache.get(httpUtils.contextToRole(contextPath));
            if(service != null) {
                if(service.getServiceMeta().isThrottleGlobal() &&
                        service.getServiceMeta().getThrottleDuration() > -1 &&
                        service.getServiceMeta().getThrottleTotalCalls() > -1) {
                    if(!canContinue(service, null, false, -1, -1)) {
                        exchange.setProperty(Constants.REASON_MESSAGE_HEADER, "Too Many requests");

                        exchange.getIn().setHeader(Constants.REASON_MESSAGE_HEADER, "Too Many requests");
                        exchange.getIn().setHeader(Constants.REASON_CODE_HEADER, 429);
                        exchange.setException(new AuthorizationException("Too Many requests"));
                    }
                } else if(!service.getServiceMeta().isThrottleGlobal()) {
                    if(exchange.getIn().getHeader(Constants.CAPI_META_THROTTLE_CONSUMER_KEY) != null &&
                            exchange.getIn().getHeader(Constants.CAPI_META_THROTTLE_DURATION) != null &&
                            exchange.getIn().getHeader(Constants.CAPI_META_THROTTLE_TOTAL_CALLS_ALLOWED) != null) {
                        if(!canContinue(service, (String) exchange.getIn().getHeader(Constants.CAPI_META_THROTTLE_CONSUMER_KEY), true, (Long) exchange.getIn().getHeader(Constants.CAPI_META_THROTTLE_TOTAL_CALLS_ALLOWED), (Long) exchange.getIn().getHeader(Constants.CAPI_META_THROTTLE_DURATION))) {
                            exchange.setProperty(Constants.REASON_MESSAGE_HEADER, "Too Many requests");

                            exchange.getIn().setHeader(Constants.REASON_MESSAGE_HEADER, "Too Many requests");
                            exchange.getIn().setHeader(Constants.REASON_CODE_HEADER, 429);
                            exchange.setException(new AuthorizationException("Too Many requests"));
                        }
                    }
                }
            }
        } catch(Exception e) {
            log.warn(e.getMessage(), e);
        }
    }

    public boolean canContinue(Service service, String consumerKey, boolean consumerThrottle, long totalCallsAllowed, long expirationDuration) {
        String cacheKey = consumerThrottle ? service.getId() + ":" + consumerKey : service.getId();

        if(!consumerThrottle) {
            totalCallsAllowed = service.getServiceMeta().getThrottleTotalCalls();
            expirationDuration = service.getServiceMeta().getThrottleDuration();
        }

        return throttleServiceObjectCache.executeOnKey(cacheKey, new ThrottleEntryProcessor(totalCallsAllowed, expirationDuration, consumerThrottle ? consumerKey : null));
    }
}
