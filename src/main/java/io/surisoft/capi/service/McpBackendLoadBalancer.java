package io.surisoft.capi.service;

import io.surisoft.capi.schema.Mapping;
import io.surisoft.capi.schema.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class McpBackendLoadBalancer {

    private static final Logger log = LoggerFactory.getLogger(McpBackendLoadBalancer.class);

    private final long circuitBreakerCooldownMs;
    private final ConcurrentHashMap<String, AtomicInteger> roundRobinCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> circuitBreakerState = new ConcurrentHashMap<>();

    public McpBackendLoadBalancer(long circuitBreakerCooldownMs) {
        this.circuitBreakerCooldownMs = circuitBreakerCooldownMs;
    }

    public List<String> getOrderedBackendUrls(Service service) {
        if (service.getMappingList() == null || service.getMappingList().isEmpty()) {
            return Collections.emptyList();
        }

        List<Mapping> mappings = new ArrayList<>(service.getMappingList());
        List<String> urls = new ArrayList<>(mappings.size());
        for (Mapping mapping : mappings) {
            urls.add(buildBackendUrl(service, mapping));
        }

        if (urls.size() <= 1) {
            return urls;
        }

        String serviceKey = service.getName() != null ? service.getName() : service.getId();
        AtomicInteger counter = roundRobinCounters.computeIfAbsent(serviceKey, k -> new AtomicInteger(0));
        int index = Math.abs(counter.getAndIncrement() % urls.size());

        // Rotate the list so the round-robin pick is first
        List<String> rotated = new ArrayList<>(urls.size());
        for (int i = 0; i < urls.size(); i++) {
            rotated.add(urls.get((index + i) % urls.size()));
        }

        // Partition into healthy and circuit-broken
        long now = System.currentTimeMillis();
        List<String> healthy = new ArrayList<>();
        List<String> circuitBroken = new ArrayList<>();
        for (String url : rotated) {
            Long failedAt = circuitBreakerState.get(url);
            if (failedAt != null && (now - failedAt) < circuitBreakerCooldownMs) {
                circuitBroken.add(url);
            } else {
                healthy.add(url);
            }
        }

        // Healthy first, circuit-broken appended (deprioritized, not skipped)
        List<String> result = new ArrayList<>(healthy.size() + circuitBroken.size());
        result.addAll(healthy);
        result.addAll(circuitBroken);
        return result;
    }

    public void reportFailure(String backendUrl) {
        log.debug("Circuit breaker: marking backend as failed: {}", backendUrl);
        circuitBreakerState.put(backendUrl, System.currentTimeMillis());
    }

    public void reportSuccess(String backendUrl) {
        if (circuitBreakerState.remove(backendUrl) != null) {
            log.debug("Circuit breaker: cleared failure state for backend: {}", backendUrl);
        }
    }

    String buildBackendUrl(Service service, Mapping mapping) {
        if (mapping.isIngress()) {
            String scheme = mapping.getPort() == 443 ? "https" : "http";
            String rootContext = normalizeRootContext(mapping.getRootContext());
            boolean standardPort = (mapping.getPort() == 443 && "https".equals(scheme))
                    || (mapping.getPort() == 80 && "http".equals(scheme));
            if (standardPort) {
                return scheme + "://" + mapping.getHostname() + rootContext;
            } else {
                return scheme + "://" + mapping.getHostname() + ":" + mapping.getPort() + rootContext;
            }
        }

        String scheme = service.getServiceMeta().getScheme();
        if (scheme == null) {
            scheme = "http";
        }
        String rootContext = normalizeRootContext(mapping.getRootContext());
        return scheme + "://" + mapping.getHostname() + ":" + mapping.getPort() + rootContext;
    }

    private String normalizeRootContext(String rootContext) {
        if (rootContext == null) {
            return "";
        }
        if (!rootContext.startsWith("/") && !rootContext.isEmpty()) {
            return "/" + rootContext;
        }
        return rootContext;
    }
}
