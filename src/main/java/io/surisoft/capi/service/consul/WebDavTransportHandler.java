package io.surisoft.capi.service.consul;

import io.surisoft.capi.schema.InvalidService;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.State;
import io.surisoft.capi.schema.WebDavClient;
import io.surisoft.capi.undertow.CAPILoadBalancerProxyClient;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.WebDavUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discovery for {@code type = webdav}. Mirrors {@link GrpcTransportHandler}, with one addition: it
 * refuses to publish a multi-instance service that has not asserted shared state.
 *
 * <p>Why that guard exists. CAPI round-robins across a service's instances and cannot replicate
 * files, so if the instances do not share storage a {@code PUT} to one is simply absent from the
 * next, and a {@code LOCK} taken on one is unknown to the others. Load balancing is wrong for WebDAV
 * regardless of locking — instances with independent storage are not one service, they are several.
 * The nastier case is shared storage with unshared locks ({@code mod_dav}'s {@code DAVLockDB} and
 * sabre/dav's file lock backend are both local by default): reads and writes appear to work while
 * locking silently fails, which is exactly the shape that corrupts files in Explorer.
 *
 * <p>So the owner must assert {@code webdav-shared-state: true}, covering storage <em>and</em> the
 * lock database. Absent it, a multi-instance service is not published and is reported at
 * {@code /info/invalid-services} instead of silently corrupting data.
 */
public class WebDavTransportHandler implements TransportHandler {

    private static final Logger log = LoggerFactory.getLogger(WebDavTransportHandler.class);

    /** Grace basis when the service declares no responseTimeout. */
    private static final long DEFAULT_MAX_REQUEST_TIME = 3_600_000L;

    private final boolean enabled;
    private final Map<String, WebDavClient> webDavClientMap;
    private final WebDavUtils webDavUtils;
    private final Map<String, InvalidService> invalidServiceMap;

    /**
     * Services refused for the shared-state reason, retained across cycles.
     *
     * <p>{@code invalidServiceMap} is cleared at the start of every reconcile cycle while
     * {@code onAppear} only fires on a delta, so a refusal recorded once would vanish on the next
     * cycle and never be re-reported. Re-asserting from this set in {@link #afterCycle()} — which
     * runs after the clear — keeps the report stable.
     */
    private final Map<String, InvalidService> refusedByContext = new ConcurrentHashMap<>();

    public WebDavTransportHandler(boolean enabled,
                                  Map<String, WebDavClient> webDavClientMap,
                                  WebDavUtils webDavUtils,
                                  Map<String, InvalidService> invalidServiceMap) {
        this.enabled = enabled;
        this.webDavClientMap = webDavClientMap;
        this.webDavUtils = webDavUtils;
        this.invalidServiceMap = invalidServiceMap;
    }

    @Override
    public boolean supports(Service service) {
        if (!enabled || webDavClientMap == null || webDavUtils == null) {
            return false;
        }
        return Constants.WEBDAV_TYPE.equalsIgnoreCase(service.getServiceMeta().getType());
    }

    @Override
    public void onAppear(Service service) {
        if (!isPublished(service)) {
            return;
        }
        register(service);
    }

    @Override
    public void onChange(Service oldSvc, Service newSvc) {
        WebDavClient removed = webDavClientMap.remove(oldSvc.getContext());
        drainOldClient(removed, oldSvc);
        clearRefusal(oldSvc.getContext());
        if (isPublished(newSvc)) {
            register(newSvc);
        }
    }

    @Override
    public void onDisappear(Service service) {
        WebDavClient removed = webDavClientMap.remove(service.getContext());
        clearRefusal(service.getContext());
        if (removed != null) {
            drainOldClient(removed, service);
            log.info("WebDAV client removed: {}", service.getContext());
        }
    }

    @Override
    public void afterCycle() {
        // invalidServiceMap was cleared at the start of this cycle; re-assert our refusals so the
        // report at /info/invalid-services stays accurate for as long as the cause persists.
        refusedByContext.values().forEach(i -> invalidServiceMap.put(i.serviceId(), i));
    }

    private void register(Service service) {
        String violation = sharedStateViolation(service);
        if (violation != null) {
            InvalidService invalid = new InvalidService(
                    service.getId(),
                    service.getServiceMeta() != null ? service.getServiceMeta().getGroup() : null,
                    null,
                    InvalidService.Reason.WEBDAV_UNSHARED_MULTI_INSTANCE,
                    violation,
                    Instant.now());
            // Keyed by context so onChange/onDisappear can clear it exactly, and reported under the
            // service id because that is what /info/invalid-services keys on.
            refusedByContext.put(service.getContext(), invalid);
            invalidServiceMap.put(service.getId(), invalid);
            log.warn("WebDAV service {} NOT published: {}", service.getContext(), violation);
            return;
        }
        WebDavClient client = webDavUtils.createWebDavClient(service);
        if (client != null) {
            webDavClientMap.put(client.getServiceId(), client);
            log.info("WebDAV client registered: {} ({})", client.getServiceId(),
                    client.getWebdavHost() != null ? "host " + client.getWebdavHost() : "path routed");
        }
    }

    /**
     * @return null when the service may be published, otherwise the reason it may not
     */
    static String sharedStateViolation(Service service) {
        int instances = service.getMappingList() == null ? 0 : service.getMappingList().size();
        if (instances <= 1) {
            return null;
        }
        if (service.getServiceMeta() != null && service.getServiceMeta().isWebdavSharedState()) {
            return null;
        }
        return instances + " instances registered without webdav-shared-state: true. CAPI balances "
             + "across instances and cannot replicate files, so unshared storage loses writes and "
             + "unshared locks corrupt them. Set webdav-shared-state: true once every instance shares "
             + "storage AND the lock database, or register the instances as separate services.";
    }

    private void clearRefusal(String context) {
        InvalidService gone = refusedByContext.remove(context);
        if (gone != null) {
            invalidServiceMap.remove(gone.serviceId());
        }
    }

    /** Reclaim the orphaned client's backend sockets so they don't leak file descriptors. */
    private void drainOldClient(WebDavClient removed, Service svc) {
        if (removed == null) {
            return;
        }
        long maxRequestTime = (svc.getServiceMeta() != null && svc.getServiceMeta().getResponseTimeout() > 0)
                ? svc.getServiceMeta().getResponseTimeout()
                : DEFAULT_MAX_REQUEST_TIME;
        CAPILoadBalancerProxyClient.drainHandler(removed.getHttpHandler(), maxRequestTime);
    }

    private boolean isPublished(Service service) {
        return service.getServiceMeta().getState() == null
                || service.getServiceMeta().getState().equals(State.PUBLISHED);
    }
}
