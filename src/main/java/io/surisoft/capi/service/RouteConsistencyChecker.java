package io.surisoft.capi.service;

import io.surisoft.capi.schema.RestClient;
import io.surisoft.capi.schema.Service;
import org.cache2k.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RouteConsistencyChecker {

    private static final Logger log = LoggerFactory.getLogger(RouteConsistencyChecker.class);
    private final Cache<String, Service> serviceCache;
    private Map<String, RestClient> restClientMap;
    private RestClientSnapshot restClientSnapshot;

    public RouteConsistencyChecker(Cache<String, Service> serviceCache) {
        this.serviceCache = serviceCache;
    }

    public void setRestClientMap(Map<String, RestClient> restClientMap) {
        this.restClientMap = restClientMap;
    }

    public void setRestClientSnapshot(RestClientSnapshot restClientSnapshot) {
        this.restClientSnapshot = restClientSnapshot;
    }

    public void process() {
        log.debug("Looking for inconsistent routes...");
        checkForOpenApiInconsistency();
    }

    private void checkForOpenApiInconsistency() {
        List<String> servicesToRemove = new ArrayList<>();
        serviceCache.entries().forEach(service -> {
            String endpoint = service.getValue().getServiceMeta().getOpenApiEndpoint();
            if(endpoint != null && !endpoint.isEmpty() && service.getValue().getOpenAPI() == null) {
                log.warn("Inconsistency detected for service {}. Service will be removed.", service.getKey());
                if(restClientMap != null) {
                    restClientMap.remove(service.getValue().getContext());
                }
                servicesToRemove.add(service.getKey());
            }
        });
        servicesToRemove.forEach(serviceCache::remove);
        // If we removed anything from the live restClientMap, republish so RestGateway
        // stops routing to inconsistent services immediately rather than waiting for
        // the next Consul cycle's snapshot publish.
        if (!servicesToRemove.isEmpty() && restClientSnapshot != null && restClientMap != null) {
            restClientSnapshot.publish(restClientMap);
        }
    }
}