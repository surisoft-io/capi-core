package io.surisoft.capi.utils;

import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.surisoft.capi.configuration.CapiSslContextHolder;
import io.surisoft.capi.schema.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for route management.
 * Most Camel-specific methods have been removed — REST proxying is now handled by RestGateway.
 */
public class RouteUtils {
    private static final Logger log = LoggerFactory.getLogger(RouteUtils.class);
    private final CompositeMeterRegistry meterRegistry;

    public RouteUtils(CompositeMeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void registerMetric(String routeId) {
        meterRegistry.counter(routeId);
    }

    public void registerTracer(Service service) {
        // Tracing now handled by CapiTracer
    }

    public List<String> getAllRouteIdForAGivenService(Service service) {
        List<String> routeIdList = new ArrayList<>();
        for (String method : List.of("get", "post", "put", "delete", "patch")) {
            routeIdList.add(service.getId() + ":" + method);
        }
        return routeIdList;
    }
}
