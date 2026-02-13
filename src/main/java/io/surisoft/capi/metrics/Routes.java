package io.surisoft.capi.metrics;

import io.surisoft.capi.schema.RouteDetailsEndpointInfo;
import io.surisoft.capi.schema.RouteEndpointInfo;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.utils.Constants;
import org.apache.camel.CamelContext;
import org.cache2k.Cache;

import java.util.ArrayList;
import java.util.List;

public class Routes {

    private final CamelContext camelContext;
    private final Cache<String, Service> serviceCache;

    public Routes(CamelContext camelContext, Cache<String, Service> serviceCache) {
        this.camelContext = camelContext;
        this.serviceCache = serviceCache;
    }

    public List<RouteDetailsEndpointInfo> getAllRoutesInfo() {
        List<RouteDetailsEndpointInfo> detailInfoList = new ArrayList<>();
        List<RouteEndpointInfo> routeEndpointInfoList = camelContext.getRoutes().stream()
                .map(RouteEndpointInfo::new)
                .toList();
        for(RouteEndpointInfo routeEndpointInfo : routeEndpointInfoList) {
            if(!Constants.CAPI_INTERNAL_ROUTES_PREFIX.contains(routeEndpointInfo.getId()))  {
                detailInfoList.add(new RouteDetailsEndpointInfo(camelContext, camelContext.getRoute(routeEndpointInfo.getId())));
            }
        }
        return detailInfoList;
    }

    public Service getCachedService(String serviceName) {
        if(serviceCache.containsKey(serviceName)) {
            return serviceCache.get(serviceName);
        }
        return null;
    }
}