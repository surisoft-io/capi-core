package io.surisoft.capi.service.consul;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.processor.ServiceCapiInstanceMapper;
import io.surisoft.capi.schema.ConsulObject;
import io.surisoft.capi.schema.Mapping;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceCapiInstances;
import io.surisoft.capi.schema.ServiceMeta;
import io.surisoft.capi.schema.State;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.ServiceUtils;
import org.cache2k.Cache;
import org.cache2k.CacheEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class ConsulCatalogService {

    private static final Logger log = LoggerFactory.getLogger(ConsulCatalogService.class);

    private static final String GET_ALL_SERVICES = "/v1/catalog/services";
    private static final String GET_SERVICE_BY_NAME = "/v1/catalog/service/";
    private static final Duration PER_REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration PER_HOST_TIMEOUT = Duration.ofSeconds(30);

    // §7.3 startup readiness latch — one-way. Set once on first clean cycle, never reset.
    private static volatile boolean connectedToConsul = false;

    public static boolean isConnectedToConsul() {
        return connectedToConsul;
    }

    private final List<CAPIConfiguration.HostConfig> consulHosts;
    private final Cache<String, Service> serviceCache;
    private final ServiceUtils serviceUtils;
    private final List<TransportHandler> transportHandlers;
    private final String capiInstanceName;
    private final boolean strictToInstanceName;
    private final String serviceMetaExtrasPrefix; // nullable

    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile HttpClient httpClient;

    public ConsulCatalogService(List<CAPIConfiguration.HostConfig> consulHosts,
                                Cache<String, Service> serviceCache,
                                ServiceUtils serviceUtils,
                                List<TransportHandler> transportHandlers,
                                HttpClient httpClient,
                                String capiInstanceName,
                                boolean strictToInstanceName,
                                String serviceMetaExtrasPrefix) {
        this.consulHosts = consulHosts;
        this.serviceCache = serviceCache;
        this.serviceUtils = serviceUtils;
        this.transportHandlers = transportHandlers;
        this.httpClient = httpClient;
        this.capiInstanceName = capiInstanceName;
        this.strictToInstanceName = strictToInstanceName;
        this.serviceMetaExtrasPrefix = serviceMetaExtrasPrefix;
    }

    public void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public void runCycle() {
        long start = System.currentTimeMillis();
        CycleResult result = fetchAndUnion();
        Map<String, Service> incoming = buildServices(result.reconciledObjects());
        prefetchOpenApiSpecs(incoming);
        ServiceDelta delta = reconcile(incoming, result);
        apply(delta);
        maybeFlipReadinessLatch(result);
        emitSummary(result, delta, System.currentTimeMillis() - start);
    }

    // -------------------------------------------------------------------------
    // Fetch + union (§7.8 host independence: parallel, bounded, non-blocking)
    // -------------------------------------------------------------------------

    private CycleResult fetchAndUnion() {
        List<CompletableFuture<HostResult>> hostFutures = consulHosts.stream()
                .map(this::fetchHost)
                .toList();

        List<HostResult> results = new ArrayList<>(hostFutures.size());
        for (CompletableFuture<HostResult> future : hostFutures) {
            try {
                results.add(future.get(PER_HOST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            } catch (Exception e) {
                // Per-host failure is isolated. We synthesize a failed result so other hosts still count.
                log.warn("Consul host timed out or failed before returning: {}", e.getMessage());
                results.add(HostResult.failed(null));
            }
        }
        return union(results);
    }

    private CompletableFuture<HostResult> fetchHost(CAPIConfiguration.HostConfig host) {
        return CompletableFuture.supplyAsync(() -> fetchHostSync(host));
    }

    private HostResult fetchHostSync(CAPIConfiguration.HostConfig host) {
        Set<String> catalogNames;
        try {
            HttpResponse<String> catalogResponse = sendWithGoawayRetry(buildCatalogRequest(host))
                    .get(PER_REQUEST_TIMEOUT.toMillis() * 2, TimeUnit.MILLISECONDS);
            @SuppressWarnings("unchecked")
            Map<String, Object> responseObject = objectMapper.readValue(catalogResponse.body(), Map.class);
            responseObject.remove("consul");
            catalogNames = new HashSet<>(responseObject.keySet());
        } catch (Exception e) {
            log.error("Consul catalog fetch failed for host {}: {}", host.getEndpoint(), e.getMessage());
            return HostResult.failed(host);
        }

        Map<String, CompletableFuture<HttpResponse<String>>> futures = new LinkedHashMap<>();
        for (String name : catalogNames) {
            futures.put(name, sendWithGoawayRetry(buildPerServiceRequest(host, name)));
        }

        Map<String, List<ConsulObject>> perService = new HashMap<>();
        Set<String> emptyFromConsul = new HashSet<>();
        int failed = 0;

        for (Map.Entry<String, CompletableFuture<HttpResponse<String>>> e : futures.entrySet()) {
            String name = e.getKey();
            try {
                HttpResponse<String> response = e.getValue().get(PER_REQUEST_TIMEOUT.toMillis() * 2, TimeUnit.MILLISECONDS);
                PerServiceOutcome outcome = processServiceByName(name, response.body());
                switch (outcome) {
                    case PerServiceOutcome.Kept k -> perService.put(name, k.entries());
                    case PerServiceOutcome.EmptyFromConsul ignored -> emptyFromConsul.add(name);
                    case PerServiceOutcome.FilteredEmpty ignored -> { /* authoritative: not ours */ }
                    case PerServiceOutcome.Failed f -> {
                        failed++;
                        logPerServiceFailure(name, f.cause());
                    }
                }
            } catch (Exception ex) {
                failed++;
                logPerServiceFailure(name, ex);
            }
        }

        return new HostResult(host, catalogNames, perService, emptyFromConsul, failed, false);
    }

    private void logPerServiceFailure(String serviceName, Throwable cause) {
        Throwable root = unwrap(cause);
        String msg = root.getMessage();
        if (msg != null && msg.contains("GOAWAY")) {
            // §7.5 — GOAWAY is ALB connection rotation, not an error.
            log.debug("Dropping GOAWAY'd Consul lookup for {}", serviceName);
        } else {
            log.error("Failed to fetch service {}: {}", serviceName, msg);
        }
    }

    private CycleResult union(List<HostResult> hostResults) {
        Set<String> catalogUnion = new HashSet<>();
        Map<String, List<ConsulObject>> mergedPerService = new HashMap<>();
        Set<String> emptyUnion = new HashSet<>();
        int totalFailed = 0;
        int hostsFailed = 0;

        for (HostResult h : hostResults) {
            if (h.catalogFetchFailed()) {
                hostsFailed++;
                continue;
            }
            catalogUnion.addAll(h.catalogNames());
            emptyUnion.addAll(h.emptyFromConsul());
            totalFailed += h.failedServiceLookups();
            for (Map.Entry<String, List<ConsulObject>> e : h.perServiceObjects().entrySet()) {
                mergedPerService.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(e.getValue());
            }
        }
        return new CycleResult(catalogUnion, mergedPerService, emptyUnion, totalFailed, hostsFailed, hostResults.size());
    }

    // -------------------------------------------------------------------------
    // Per-service parse + filter (instance ownership, state, type default)
    // -------------------------------------------------------------------------

    private sealed interface PerServiceOutcome {
        record Kept(List<ConsulObject> entries) implements PerServiceOutcome {}
        record EmptyFromConsul() implements PerServiceOutcome {}
        record FilteredEmpty() implements PerServiceOutcome {}
        record Failed(Throwable cause) implements PerServiceOutcome {}
    }

    private PerServiceOutcome processServiceByName(String serviceName, String body) {
        List<ConsulObject> parsed;
        try {
            parsed = objectMapper.readValue(body, new TypeReference<List<ConsulObject>>() {});
        } catch (Exception e) {
            return new PerServiceOutcome.Failed(e);
        }
        if (parsed.isEmpty()) {
            return new PerServiceOutcome.EmptyFromConsul();
        }

        List<ConsulObject> kept = new ArrayList<>();
        for (ConsulObject o : parsed) {
            if (!isForThisInstance(o)) continue;
            if (!isPublishable(o)) continue;
            kept.add(o);
        }
        return kept.isEmpty()
                ? new PerServiceOutcome.FilteredEmpty()
                : new PerServiceOutcome.Kept(kept);
    }

    private boolean isForThisInstance(ConsulObject o) {
        boolean hasMultiForThis = serviceUtils.getServiceCapiInstance(o, capiInstanceName) != null;
        boolean hasSingleForThis = o.getServiceMeta() != null
                && o.getServiceMeta().getCapiNamespace() != null
                && o.getServiceMeta().getCapiNamespace().equals(capiInstanceName);
        if (hasMultiForThis || hasSingleForThis) return true;
        boolean declaredForOthers = serviceUtils.isTheServiceRegisteredForOtherInstances(o, capiInstanceName)
                || (o.getServiceMeta() != null && o.getServiceMeta().getCapiNamespace() != null);
        if (declaredForOthers) return false;
        return !strictToInstanceName;
    }

    private boolean isPublishable(ConsulObject o) {
        if (o.getServiceMeta() == null || o.getServiceMeta().getState() == null) return true;
        return o.getServiceMeta().getState().equals(State.PUBLISHED);
    }

    // -------------------------------------------------------------------------
    // Build Service objects (per Q6: ID always "<name>:<group>")
    // -------------------------------------------------------------------------

    private Map<String, Service> buildServices(Map<String, List<ConsulObject>> perService) {
        Map<String, Service> result = new HashMap<>();
        for (Map.Entry<String, List<ConsulObject>> entry : perService.entrySet()) {
            String serviceName = entry.getKey();
            Map<String, Set<Mapping>> byGroup = groupByGroup(entry.getValue());
            for (Map.Entry<String, Set<Mapping>> g : byGroup.entrySet()) {
                Service svc = createServiceObject(serviceName, g.getKey(), g.getValue(), entry.getValue());
                result.put(svc.getId(), svc);
            }
        }
        return result;
    }

    private Map<String, Set<Mapping>> groupByGroup(List<ConsulObject> objects) {
        Map<String, Set<Mapping>> result = new HashMap<>();
        for (ConsulObject o : objects) {
            String group = getGroup(o);
            if (group == null) {
                log.trace("Service {} has no meta.group; skipping", o.getServiceName());
                continue;
            }
            result.computeIfAbsent(group, k -> new HashSet<>()).add(serviceUtils.consulObjectToMapping(o));
        }
        return result;
    }

    private String getGroup(ConsulObject o) {
        return (o.getServiceMeta() != null) ? o.getServiceMeta().getGroup() : null;
    }

    private Service createServiceObject(String serviceName, String group, Set<Mapping> mappings, List<ConsulObject> objects) {
        Service svc = new Service();
        ServiceMeta serviceMeta = findServiceMetaForGroup(group, objects);

        if (serviceMetaExtrasPrefix != null && serviceMeta != null) {
            serviceMeta.getUnknownProperties().forEach((k, v) -> {
                if (k.startsWith(serviceMetaExtrasPrefix)) {
                    serviceMeta.addExtraServiceMeta(k.replace(serviceMetaExtrasPrefix, ""), v);
                }
            });
        }

        if (serviceMeta != null) {
            Map<String, String> multiInstances = new HashMap<>();
            serviceMeta.getUnknownProperties().forEach((k, v) -> {
                if (k.startsWith(ServiceCapiInstanceMapper.SERVICE_CAPI_INSTANCE_PREFIX)) {
                    multiInstances.put(k, v);
                }
            });
            if (!multiInstances.isEmpty()) {
                svc.setServiceCapiInstances(new ServiceCapiInstanceMapper().convert(multiInstances));
            }
        }

        // Q6: ID is always "<name>:<group>" (stable, does not flip with routeGroupFirst)
        svc.setId(serviceName + ":" + group);
        svc.setName(serviceName);
        svc.setRegisteredBy(getClass().getName());
        svc.setMappingList(mappings);
        svc.setServiceMeta(serviceMeta);
        svc.setRoundRobinEnabled(mappings.size() != 1);
        svc.setFailOverEnabled(mappings.size() != 1);

        serviceUtils.validateServiceType(svc);
        applyPerInstanceOverrides(svc);
        setContext(svc);

        return svc;
    }

    private ServiceMeta findServiceMetaForGroup(String group, List<ConsulObject> objects) {
        for (ConsulObject o : objects) {
            if (Objects.equals(getGroup(o), group)) return o.getServiceMeta();
        }
        return null;
    }

    private void applyPerInstanceOverrides(Service svc) {
        if (svc.getServiceCapiInstances() == null || svc.getServiceCapiInstances().getInstances() == null) return;
        ServiceCapiInstances.Instance thisInstance = svc.getServiceCapiInstances().getInstances().get(capiInstanceName);
        if (thisInstance == null) return;

        if (!thisInstance.isAssumeParentSecured()) {
            svc.getServiceMeta().setSecured(thisInstance.isSecured());
        }
        if (!thisInstance.isAssumeParentRouteGroupFirst()) {
            svc.getServiceMeta().setRouteGroupFirst(thisInstance.isRouteGroupFirst());
        }
        if (thisInstance.getOpenApi() != null) {
            svc.getServiceMeta().setOpenApiEndpoint(thisInstance.getOpenApi());
        } else if (thisInstance.isIgnoreOpenApi()) {
            svc.setOpenAPI(null);
        }
        if (thisInstance.getScheme() != null && !thisInstance.getScheme().isEmpty()) {
            svc.getServiceMeta().setScheme(thisInstance.getScheme());
        }
    }

    private void setContext(Service svc) {
        String group = svc.getServiceMeta().getGroup();
        if (svc.getServiceMeta().isRouteGroupFirst()) {
            svc.setContext("/" + group + "/" + svc.getName());
        } else {
            svc.setContext("/" + svc.getName() + "/" + group);
        }
    }

    // -------------------------------------------------------------------------
    // OpenAPI pre-fetch (parallel)
    // -------------------------------------------------------------------------

    private void prefetchOpenApiSpecs(Map<String, Service> incoming) {
        Map<Service, CompletableFuture<HttpResponse<String>>> futures = new LinkedHashMap<>();
        for (Service svc : incoming.values()) {
            if (serviceUtils.needsOpenApiFetch(svc)) {
                try {
                    futures.put(svc, httpClient.sendAsync(serviceUtils.buildOpenApiRequest(svc), BodyHandlers.ofString()));
                } catch (Exception e) {
                    log.warn("Failed to build OpenAPI request for {}: {}", svc.getId(), e.getMessage());
                }
            }
        }
        List<String> drop = new ArrayList<>();
        for (Map.Entry<Service, CompletableFuture<HttpResponse<String>>> e : futures.entrySet()) {
            try {
                HttpResponse<String> response = e.getValue().get(PER_REQUEST_TIMEOUT.toMillis() * 2, TimeUnit.MILLISECONDS);
                if (!serviceUtils.processOpenApiSpec(e.getKey(), response)) {
                    drop.add(e.getKey().getId());
                }
            } catch (Exception ex) {
                log.warn("Failed to fetch OpenAPI spec for {}: {}", e.getKey().getId(), ex.getMessage());
                drop.add(e.getKey().getId());
            }
        }
        // Services whose OpenAPI fetch failed / produced invalid spec: do not register this cycle.
        drop.forEach(incoming::remove);
    }

    // -------------------------------------------------------------------------
    // Reconcile (§7.1 + §7.2 guards)
    // -------------------------------------------------------------------------

    private ServiceDelta reconcile(Map<String, Service> incoming, CycleResult result) {
        List<Service> added = new ArrayList<>();
        List<ServiceDelta.ChangedPair> changed = new ArrayList<>();
        for (Service in : incoming.values()) {
            Service cached = serviceCache.peek(in.getId());
            if (cached == null) {
                added.add(in);
            } else if (serviceUtils.didServiceChange(cached, in)) {
                changed.add(new ServiceDelta.ChangedPair(cached, in));
            }
        }

        if (!result.clean()) {
            // §7.1: partial view of Consul → skip removal phase entirely to preserve live routes.
            log.warn("Consul discovery cycle had {} failed lookup(s) across {} host(s); skipping route cleanup",
                    result.totalFailedLookups() + result.hostsFailed(), result.hostsTotal());
            return new ServiceDelta(added, changed, List.of());
        }

        List<Service> gone = new ArrayList<>();
        for (CacheEntry<String, Service> e : serviceCache.entries()) {
            if (incoming.containsKey(e.getKey())) continue;
            Service cached = serviceCache.get(e.getKey());
            if (cached == null) continue;
            if (result.emptyFromConsul().contains(cached.getName())) {
                // §7.2: Consul returned empty per-service response for this name; preserve.
                log.debug("Preserving {} — Consul returned empty for name {}", e.getKey(), cached.getName());
                continue;
            }
            gone.add(cached);
        }
        return new ServiceDelta(added, changed, gone);
    }

    // -------------------------------------------------------------------------
    // Apply (single-writer: scheduler thread only)
    // -------------------------------------------------------------------------

    private void apply(ServiceDelta delta) {
        for (Service s : delta.added()) {
            serviceCache.put(s.getId(), s);
            for (TransportHandler h : transportHandlers) {
                if (h.supports(s)) h.onAppear(s);
            }
        }
        for (ServiceDelta.ChangedPair p : delta.changed()) {
            serviceCache.put(p.newSvc().getId(), p.newSvc());
            for (TransportHandler h : transportHandlers) {
                if (h.supports(p.newSvc())) h.onChange(p.oldSvc(), p.newSvc());
            }
        }
        for (Service s : delta.gone()) {
            for (TransportHandler h : transportHandlers) {
                if (h.supports(s)) h.onDisappear(s);
            }
            serviceCache.remove(s.getId());
        }
    }

    // -------------------------------------------------------------------------
    // Readiness latch + summary
    // -------------------------------------------------------------------------

    private void maybeFlipReadinessLatch(CycleResult result) {
        if (!connectedToConsul && result.clean()) {
            connectedToConsul = true;
            log.info("Consul readiness latch flipped true — node is ready to serve");
        }
    }

    private void emitSummary(CycleResult result, ServiceDelta delta, long durationMs) {
        log.info("consul-cycle hosts={} hostsFailed={} failedLookups={} added={} changed={} gone={} cleanupSkipped={} durationMs={}",
                result.hostsTotal(),
                result.hostsFailed(),
                result.totalFailedLookups(),
                delta.added().size(),
                delta.changed().size(),
                delta.gone().size(),
                !result.clean(),
                durationMs);
    }

    // -------------------------------------------------------------------------
    // HTTP helpers
    // -------------------------------------------------------------------------

    private HttpRequest buildCatalogRequest(CAPIConfiguration.HostConfig host) {
        URI uri = URI.create(host.getEndpoint() + GET_ALL_SERVICES);
        validatePath(uri);
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(uri).timeout(PER_REQUEST_TIMEOUT);
        addAuth(b, host);
        return b.build();
    }

    private HttpRequest buildPerServiceRequest(CAPIConfiguration.HostConfig host, String serviceName) {
        URI uri = URI.create(host.getEndpoint() + GET_SERVICE_BY_NAME + serviceName);
        validatePath(uri);
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(uri).timeout(PER_REQUEST_TIMEOUT);
        addAuth(b, host);
        return b.build();
    }

    private void validatePath(URI uri) {
        if (uri.getPath() != null && uri.getPath().contains("..")) {
            throw new IllegalArgumentException("Path traversal detected in URI path: " + uri.getPath());
        }
    }

    private void addAuth(HttpRequest.Builder builder, CAPIConfiguration.HostConfig host) {
        if (host.getToken() != null && !host.getToken().isEmpty()) {
            builder.header(Constants.AUTHORIZATION_HEADER,
                    Constants.BEARER + host.getToken().replaceAll("(\r\n|\n)", ""));
        }
    }

    private CompletableFuture<HttpResponse<String>> sendWithGoawayRetry(HttpRequest req) {
        return httpClient.sendAsync(req, BodyHandlers.ofString())
                .exceptionallyCompose(ex -> {
                    String msg = unwrap(ex).getMessage();
                    if (msg != null && msg.contains("GOAWAY")) {
                        log.debug("Retrying after GOAWAY for {}", req.uri());
                        return httpClient.sendAsync(req, BodyHandlers.ofString());
                    }
                    return CompletableFuture.failedFuture(ex);
                });
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur;
    }
}