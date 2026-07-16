package io.surisoft.capi.service.consul;

import io.surisoft.capi.exception.HttpErrorHandler;
import io.surisoft.capi.schema.RestClient;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.State;
import io.surisoft.capi.schema.WebsocketClient;
import io.surisoft.capi.service.OpaWasmService;
import io.surisoft.capi.service.RestClientSnapshot;
import io.surisoft.capi.undertow.CAPILoadBalancerProxyClient;
import io.surisoft.capi.utils.HttpUtils;
import io.surisoft.capi.utils.WebsocketUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class RestTransportHandler implements TransportHandler {

    private static final Logger log = LoggerFactory.getLogger(RestTransportHandler.class);

    private final boolean enabled;
    private final Map<String, RestClient> restClientMap;
    private final WebsocketUtils websocketUtils;
    private final HttpUtils httpUtils;
    private final OpaWasmService opaWasmService;
    private final int globalResponseTimeout;
    private final RestClientSnapshot restClientSnapshot;

    public RestTransportHandler(boolean enabled,
                                Map<String, RestClient> restClientMap,
                                WebsocketUtils websocketUtils,
                                HttpUtils httpUtils,
                                OpaWasmService opaWasmService,
                                int globalResponseTimeout,
                                RestClientSnapshot restClientSnapshot) {
        this.enabled = enabled;
        this.restClientMap = restClientMap;
        this.websocketUtils = websocketUtils;
        this.httpUtils = httpUtils;
        this.opaWasmService = opaWasmService;
        this.globalResponseTimeout = globalResponseTimeout;
        this.restClientSnapshot = restClientSnapshot;
    }

    @Override
    public boolean supports(Service service) {
        if (!enabled || restClientMap == null || websocketUtils == null) {
            return false;
        }
        String type = service.getServiceMeta().getType();
        return type == null || "rest".equalsIgnoreCase(type);
    }

    @Override
    public void onAppear(Service service) {
        if (!isPublished(service)) {
            return;
        }
        String clientId = service.getContext();
        if (restClientMap.containsKey(clientId)) {
            return;
        }
        restClientMap.put(clientId, buildRestClient(service));
        log.info("REST client registered: {} with {} backend(s)", clientId, service.getMappingList().size());
    }

    @Override
    public void onChange(Service oldSvc, Service newSvc) {
        RestClient removed = restClientMap.remove(oldSvc.getContext());
        drainOldClient(removed, oldSvc);
        log.info("REST client removed for update: {}", oldSvc.getContext());
        if (isPublished(newSvc)) {
            restClientMap.put(newSvc.getContext(), buildRestClient(newSvc));
            log.info("REST client re-registered: {} with {} backend(s)", newSvc.getContext(), newSvc.getMappingList().size());
        }
    }

    @Override
    public void onDisappear(Service service) {
        RestClient removed = restClientMap.remove(service.getContext());
        if (removed != null) {
            drainOldClient(removed, service);
            log.info("REST client removed: {}", service.getContext());
        }
    }

    /** Reclaim the orphaned client's backend keep-alive sockets so they don't leak file descriptors
     *  (Undertow never TTL-evicts a dereferenced pool). Deferred past the route's maxRequestTime so
     *  in-flight requests on the old client finish untouched. */
    private void drainOldClient(RestClient removed, Service svc) {
        if (removed == null) {
            return;
        }
        long maxRequestTime = (svc.getServiceMeta() != null && svc.getServiceMeta().getResponseTimeout() > 0)
                ? svc.getServiceMeta().getResponseTimeout()
                : globalResponseTimeout;
        CAPILoadBalancerProxyClient.drainHandler(removed.getHttpHandler(), maxRequestTime);
    }

    @Override
    public void afterCycle() {
        if (restClientSnapshot != null) {
            restClientSnapshot.publish(restClientMap);
        }
    }

    private RestClient buildRestClient(Service service) {
        RestClient restClient = new RestClient();
        restClient.setServiceId(service.getContext());
        restClient.setCanonicalServiceId(service.getId());
        restClient.setMappingList(service.getMappingList());

        String rootContext = service.getMappingList().stream().toList().getFirst().getRootContext();
        if (rootContext != null && !rootContext.isEmpty() && !rootContext.equals("/") && !rootContext.equals("*")) {
            restClient.setRootContext(rootContext);
        }

        restClient.setSecured(service.getServiceMeta().isSecured());
        restClient.setSubscriptionGroup(service.getServiceMeta().getSubscriptionGroup());
        restClient.setOpaRego(service.getServiceMeta().getOpaRego());
        if (service.getServiceMeta().getOpaRego() != null && opaWasmService != null) {
            opaWasmService.registerPolicy(service.getServiceMeta().getOpaRego());
        }
        restClient.setApiKeyEnabled(service.getServiceMeta().isApiKeyEnabled());
        restClient.setThrottle(service.getServiceMeta().isThrottle());
        restClient.setThrottleGlobal(service.getServiceMeta().isThrottleGlobal());
        restClient.setThrottleTotalCalls(service.getServiceMeta().getThrottleTotalCalls());
        restClient.setThrottleDuration(service.getServiceMeta().getThrottleDuration());
        restClient.setFailOverEnabled(service.isFailOverEnabled());
        restClient.setRoundRobinEnabled(service.isRoundRobinEnabled());
        restClient.setB3TraceId(service.getServiceMeta().isB3TraceId());
        restClient.setKeepGroup(service.getServiceMeta().isKeepGroup());
        if (service.getOpenAPI() != null) {
            restClient.setOpenAPI(service.getOpenAPI());
        }

        WebsocketClient tempClient = new WebsocketClient();
        tempClient.setMappingList(service.getMappingList());
        restClient.setHttpHandler(websocketUtils.createClientHttpHandler(
                tempClient, service, new HttpErrorHandler(httpUtils), globalResponseTimeout));

        return restClient;
    }

    private boolean isPublished(Service service) {
        return service.getServiceMeta().getState() == null
                || service.getServiceMeta().getState().equals(State.PUBLISHED);
    }
}