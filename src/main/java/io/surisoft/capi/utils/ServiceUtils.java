package io.surisoft.capi.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.surisoft.capi.processor.ServiceCapiInstanceMapper;
import io.surisoft.capi.schema.*;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.cache2k.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public class ServiceUtils {

    private static final Logger log = LoggerFactory.getLogger(ServiceUtils.class);
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private final HttpUtils httpUtils;
    private final Optional<Map<String, WebsocketClient>> websocketClientMap;
    private final RouteUtils routeUtils;
    private Map<String, RestClient> restClientMap;
    private final Optional<WebsocketUtils> websocketUtils;
    private final String capiRunningMode;

    public ServiceUtils(HttpUtils httpUtils,
                        Optional<Map<String, WebsocketClient>> websocketClientMap,
                        RouteUtils routeUtils,
                        Optional<WebsocketUtils> websocketUtils,
                        String capiRunningMode) {
        this.httpUtils = httpUtils;
        this.websocketClientMap = websocketClientMap;
        this.routeUtils = routeUtils;
        this.websocketUtils = websocketUtils;
        this.capiRunningMode = capiRunningMode;
    }

    public void setRestClientMap(Map<String, RestClient> restClientMap) {
        this.restClientMap = restClientMap;
    }

    public String getServiceId(Service service) {
        return service.getName() + ":" + service.getServiceMeta().getGroup();
    }

    public String getServiceIdFromPath(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }

        int start = path.startsWith("/") ? 1 : 0;

        int firstSlash = path.indexOf('/', start);
        if (firstSlash == -1) {
            return null;
        }

        int secondSlash = path.indexOf('/', firstSlash + 1);

        String first = path.substring(start, firstSlash);
        String second = (secondSlash == -1)
                ? path.substring(firstSlash + 1)
                : path.substring(firstSlash + 1, secondSlash);

        if (first.isEmpty() || second.isEmpty()) {
            return null;
        }
        return first + ":" + second;
    }

    public Mapping consulObjectToMapping(ConsulObject consulObject) {
        String host = consulObject.getServiceAddress();
        int port = consulObject.getServicePort();
        Mapping mapping = new Mapping();

        if(consulObject.getServiceMeta() != null && consulObject.getServiceMeta().getIngress() != null) {
            mapping.setHostname(httpUtils.normalizeHttpEndpoint(consulObject.getServiceMeta().getIngress()));
            mapping.setIngress(true);
            if(httpUtils.isEndpointSecure(consulObject.getServiceMeta().getIngress())) {
                mapping.setPort(Constants.HTTPS_PORT);
            } else {
                mapping.setPort(Constants.HTTP_PORT);
            }
        } else {
            mapping.setHostname(normalizeConsulAddress(host, consulObject.getServiceName()));
            mapping.setPort(port);
        }

        if(consulObject.getServiceMeta().getRootContext() != null && !consulObject.getServiceMeta().getRootContext().isEmpty()) {
            if(consulObject.getServiceMeta().getRootContext().startsWith("/")) {
                mapping.setRootContext(consulObject.getServiceMeta().getRootContext());
            } else {
                mapping.setRootContext("/" + consulObject.getServiceMeta().getRootContext());
            }
        } else {
            mapping.setRootContext("/");
        }
        return mapping;
    }

    /**
     * Reduces a Consul {@code ServiceAddress} to the bare host CAPI can put in a backend URI.
     *
     * <p>The backend URI is built as {@code scheme://hostname:port}, so a scheme-prefixed,
     * path-carrying or port-carrying address produces a URI that {@link URI#create} accepts but
     * that points somewhere else entirely — {@code http://http://host:1818} parses to
     * {@code host=http}, which then fails DNS on every request and surfaces only as a 503
     * "No server available at the moment". Consul accepts such an address happily, so normalize
     * it here and tell the operator what was registered.
     *
     * <p>The scheme is <em>not</em> inferred from the address: the transport scheme comes from the
     * {@code scheme} meta, and quietly flipping a route to TLS as a side effect of parsing an
     * address would be worse than the warning.
     */
    String normalizeConsulAddress(String address, String serviceName) {
        if (address == null || address.isBlank()) {
            return address;
        }

        String normalized = address.strip();
        String scheme = null;
        if (normalized.regionMatches(true, 0, "http://", 0, 7)) {
            scheme = "http";
            normalized = normalized.substring(7);
        } else if (normalized.regionMatches(true, 0, "https://", 0, 8)) {
            scheme = "https";
            normalized = normalized.substring(8);
        }

        int slash = normalized.indexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(0, slash);
        }

        int colon = normalized.lastIndexOf(':');
        if (colon > 0 && normalized.indexOf(':') == colon) {
            normalized = normalized.substring(0, colon);
        }

        if (normalized.equals(address)) {
            return address;
        }

        if (scheme != null) {
            log.warn("Service {} registered ServiceAddress '{}' with a scheme; using host '{}'. " +
                     "Register the bare host in Consul and set the 'scheme' meta to '{}' instead.",
                     serviceName, address, normalized, scheme);
        } else {
            log.warn("Service {} registered ServiceAddress '{}'; using host '{}'. " +
                     "Register the bare host in Consul (port comes from ServicePort).",
                     serviceName, address, normalized);
        }
        return normalized;
    }

    /**
     * Points a mapping at the given ingress endpoint (host derived from the URL,
     * port from the scheme: 80 for http, 443 for https). Mirrors the ingress
     * branch of {@link #consulObjectToMapping} and is used by the per-instance
     * ingress override so a single Consul registration can target a different
     * backend per CAPI instance.
     */
    public void applyIngressToMapping(Mapping mapping, String ingress) {
        mapping.setHostname(httpUtils.normalizeHttpEndpoint(ingress));
        mapping.setIngress(true);
        if(httpUtils.isEndpointSecure(ingress)) {
            mapping.setPort(Constants.HTTPS_PORT);
        } else {
            mapping.setPort(Constants.HTTP_PORT);
        }
    }

    public void validateServiceType(Service service) {
        if(service.getServiceMeta().getType() == null) {
            service.getServiceMeta().setType("rest");
        }
    }

    public boolean isMappingChanged(List<Mapping> existingMappingList, List<Mapping> incomingMappingList) {
        if(existingMappingList.size() != incomingMappingList.size()) {
            return true;
        }
        for(Mapping incomingMapping : incomingMappingList) {
            if(!existingMappingList.contains(incomingMapping)) {
                return true;
            }
        }
        return false;
    }

    public boolean didServiceChange(Service existingService, Service incomingService) {
        if(existingService.getMappingList().size() != incomingService.getMappingList().size()) {
            return true;
        }
        for(Mapping incomingMapping : incomingService.getMappingList()) {
            if(!existingService.getMappingList().contains(incomingMapping)) {
                return true;
            }
        }

        if(didOpenApiEndpointChange(existingService.getServiceMeta().getOpenApiEndpoint(), incomingService.getServiceMeta().getOpenApiEndpoint())) {
            return true;
        }

        if(existingService.getServiceMeta().isSecured() != incomingService.getServiceMeta().isSecured()) {
            return true;
        }

        if(existingService.getServiceMeta().isRouteGroupFirst() != incomingService.getServiceMeta().isRouteGroupFirst()) {
            return true;
        }

        if(didVersionChange(existingService.getServiceMeta().getVersion(), incomingService.getServiceMeta().getVersion())) {
            return true;
        }

        return didSubscriptionGroupChange(existingService.getServiceMeta().getSubscriptionGroup(), incomingService.getServiceMeta().getSubscriptionGroup());
    }

    private boolean didOpenApiEndpointChange(String existingEndpoint, String incomingEndpoint) {
        if(existingEndpoint == null && incomingEndpoint != null) {
            return true;
        }
        if(existingEndpoint != null && incomingEndpoint == null) {
            return true;
        }
        return existingEndpoint != null && !existingEndpoint.equals(incomingEndpoint);
    }

    public boolean didVersionChange(String existingVersion, String incomingVersion) {
        if(existingVersion == null && incomingVersion != null) {
            return true;
        }
        if(existingVersion != null && incomingVersion == null) {
            return true;
        }
        return existingVersion != null && !existingVersion.equals(incomingVersion);
    }

    private boolean didSubscriptionGroupChange(String existingSubscriptionGroup, String incomingSubscriptionGroup) {
        if(existingSubscriptionGroup == null && incomingSubscriptionGroup != null) {
            return true;
        }
        if(existingSubscriptionGroup != null && incomingSubscriptionGroup == null) {
            return true;
        }
        return existingSubscriptionGroup != null && !existingSubscriptionGroup.equals(incomingSubscriptionGroup);
    }


    public boolean checkIfOpenApiIsEnabled(Service service, HttpClient httpClient) {
        if (!capiRunningMode.equalsIgnoreCase(Constants.FULL_TYPE) || !serviceHasOpenApiEndpoint(service)) {
            return true;
        }

        String openApiEndpoint = service.getServiceMeta().getOpenApiEndpoint();

        try {
            URI uri = URI.create(openApiEndpoint);
            if (uri.getPath() != null && uri.getPath().contains("..")) {
                throw new IllegalArgumentException("Path traversal detected in URI path: " + uri.getPath());
            }

            HttpRequest request =  HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(10))
                    .build();

            log.trace("Calling Remote Open API Spec: {}", openApiEndpoint);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Open API specification is invalid for service {}, response code: {}", service.getId(), response.statusCode());
                return false;
            }

            assert response.body() != null;
            SwaggerParseResult swaggerParseResult = new OpenAPIV3Parser().readContents(stripJsonNulls(response.body()));
            if (swaggerParseResult.getMessages() != null) {
                swaggerParseResult.getMessages().forEach(log::trace);
            }

            OpenAPI openAPI = swaggerParseResult.getOpenAPI();

            if(openAPI == null) {
                log.warn("Open API specification is null for service {}", service.getId());
                return false;
            }

            service.setOpenAPI(openAPI);
            return true;
        } catch(Exception e) {
            log.trace(e.getMessage(), e);
            log.trace("Open API specification is invalid for service {}", service.getId());
            return false;
        }
    }

    public boolean needsOpenApiFetch(Service service) {
        return capiRunningMode.equalsIgnoreCase(Constants.FULL_TYPE) && serviceHasOpenApiEndpoint(service);
    }

    public HttpRequest buildOpenApiRequest(Service service) {
        String openApiEndpoint = service.getServiceMeta().getOpenApiEndpoint();
        URI uri = URI.create(openApiEndpoint);
        if (uri.getPath() != null && uri.getPath().contains("..")) {
            throw new IllegalArgumentException("Path traversal detected in URI path: " + uri.getPath());
        }
        return HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(10))
                .build();
    }

    public boolean processOpenApiSpec(Service service, HttpResponse<String> response) {
        try {
            if (response.statusCode() != 200) {
                log.warn("Open API specification is invalid for service {}, response code: {}", service.getId(), response.statusCode());
                return false;
            }

            assert response.body() != null;
            SwaggerParseResult swaggerParseResult = new OpenAPIV3Parser().readContents(stripJsonNulls(response.body()));

            OpenAPI openAPI = swaggerParseResult.getOpenAPI();
            if (openAPI == null) {
                log.warn("Open API specification is null for service {} (body length={})", service.getId(), response.body().length());
                if (swaggerParseResult.getMessages() != null && !swaggerParseResult.getMessages().isEmpty()) {
                    swaggerParseResult.getMessages().forEach(m -> log.warn("OpenAPI parse message for {}: {}", service.getId(), m));
                } else {
                    String body = response.body();
                    String snippet = body.length() > 200 ? body.substring(0, 200) + "..." : body;
                    log.warn("OpenAPI parser returned no messages for {}; body snippet: {}", service.getId(), snippet);
                }
                return false;
            }
            if (swaggerParseResult.getMessages() != null) {
                swaggerParseResult.getMessages().forEach(log::trace);
            }

            service.setOpenAPI(openAPI);
            return true;
        } catch (Exception e) {
            log.trace(e.getMessage(), e);
            log.warn("Open API specification is invalid for service {}", service.getId());
            return false;
        }
    }

    /**
     * Strip all JSON null values from the body before handing it to OpenAPIV3Parser.
     * Some backends (notably springdoc emitting OpenAPI 3.1 without NON_NULL serialization)
     * produce specs where optional fields are serialized as explicit nulls. The parser
     * chokes on these with `NullNode cannot be cast to ObjectNode` when it encounters a
     * null where it expects an object. Nulls are valid JSON but semantically equivalent
     * to the field being absent, so removing them is safe and restores parseability.
     * Falls back to the original body on error — downstream parse will produce the
     * familiar warning and we stay fail-closed.
     */
    String stripJsonNulls(String body) {
        try {
            JsonNode root = JSON_MAPPER.readTree(body);
            removeNulls(root);
            return JSON_MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            log.debug("Null-stripping failed, falling back to original body: {}", e.getMessage());
            return body;
        }
    }

    private static void removeNulls(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            List<String> toRemove = new java.util.ArrayList<>();
            obj.fields().forEachRemaining(e -> {
                if (e.getValue() == null || e.getValue().isNull()) {
                    toRemove.add(e.getKey());
                } else {
                    removeNulls(e.getValue());
                }
            });
            toRemove.forEach(obj::remove);
        } else if (node instanceof ArrayNode arr) {
            for (JsonNode child : arr) {
                removeNulls(child);
            }
        }
    }

    private boolean serviceHasOpenApiEndpoint(Service service) {
        return service.getServiceMeta() != null &&
                service.getServiceMeta().getOpenApiEndpoint() != null &&
                !service.getServiceMeta().getOpenApiEndpoint().isEmpty();
    }

    public ServiceCapiInstances.Instance getServiceCapiInstance(ConsulObject consulObject, String capiInstanceName) {
        Map<String, String> multipleCapiInstances = new HashMap<>();
        ServiceCapiInstances serviceCapiInstances = null;
        consulObject.getServiceMeta().getUnknownProperties().forEach((unknownKey, unknownValue) -> {
            if(unknownKey.startsWith(ServiceCapiInstanceMapper.SERVICE_CAPI_INSTANCE_PREFIX)) {
                multipleCapiInstances.put(unknownKey, unknownValue);
            }
        });
        if(!multipleCapiInstances.isEmpty()) {
            serviceCapiInstances = new ServiceCapiInstanceMapper().convert(multipleCapiInstances);
        }
        if(serviceCapiInstances != null && serviceCapiInstances.getInstances().containsKey(capiInstanceName)) {
            return serviceCapiInstances.getInstances().get(capiInstanceName);
        }
        return null;
    }

    public boolean isTheServiceRegisteredForOtherInstances(ConsulObject consulObject, String capiInstanceName) {
        AtomicBoolean multipleCapiInstances = new AtomicBoolean(false);
        consulObject.getServiceMeta().getUnknownProperties().forEach((unknownKey, unknownValue) -> {
            if(unknownKey.startsWith(ServiceCapiInstanceMapper.SERVICE_CAPI_INSTANCE_PREFIX)) {
                multipleCapiInstances.set(true);
            }
        });
        return multipleCapiInstances.get();
    }
}