package io.surisoft.capi.processor;

import com.hazelcast.map.IMap;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ThrottleServiceObject;
import io.surisoft.capi.utils.HttpUtils;
import org.cache2k.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ThrottleProcessor {

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

    public boolean canContinue(Service service, String consumerKey, boolean consumerThrottle, long totalCallsAllowed, long expirationDuration) {
        String cacheKey = consumerThrottle ? service.getId() + ":" + consumerKey : service.getId();

        if(!consumerThrottle) {
            totalCallsAllowed = service.getServiceMeta().getThrottleTotalCalls();
            expirationDuration = service.getServiceMeta().getThrottleDuration();
        }

        return throttleServiceObjectCache.executeOnKey(cacheKey, new ThrottleEntryProcessor(totalCallsAllowed, expirationDuration, consumerThrottle ? consumerKey : null));
    }
}
