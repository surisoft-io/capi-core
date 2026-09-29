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

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
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
    /** {@code capi.openApi.allowLocalSpecEndpoints} — see {@link #assertSpecEndpointAllowed}. */
    private boolean allowLocalSpecEndpoints;

    public void setAllowLocalSpecEndpoints(boolean allowLocalSpecEndpoints) {
        this.allowLocalSpecEndpoints = allowLocalSpecEndpoints;
    }

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
            assertSpecEndpointAllowed(uri);

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

    /**
     * Verifies that the spec CAPI just fetched is the one the service's {@code version} meta
     * announces — opt in via {@code match-openapi-version}.
     *
     * <p>Guards the rolling-deploy race: the first pod of a new release bumps {@code version},
     * CAPI re-fetches, but the load-balanced {@code open-api} endpoint answers from a pod still
     * running the old release. CAPI would then cache the previous spec against the new version
     * and never try again, because the version it recorded already matches.
     *
     * <p>Returns a human-readable description of the mismatch, or {@code null} when the spec is
     * accepted (including when the check is off or cannot be evaluated).
     *
     * @see ServiceMeta#isMatchOpenApiVersion()
     */
    public String openApiVersionMismatch(Service service) {
        ServiceMeta meta = service.getServiceMeta();
        if (meta == null || !meta.isMatchOpenApiVersion()) {
            return null;
        }
        String declaredVersion = meta.getVersion();
        OpenAPI openAPI = service.getOpenAPI();
        String specVersion = (openAPI != null && openAPI.getInfo() != null)
                ? openAPI.getInfo().getVersion()
                : null;

        // Fail open when there is nothing to compare. A service that opted in but never set a
        // version meta — or whose spec declares no info.version — is misconfigured, not stale;
        // freezing it on its current spec would be worse than the race this guards against.
        if (isBlank(declaredVersion) || isBlank(specVersion)) {
            log.warn("Service {} sets match-openapi-version but {}; skipping the version check",
                    service.getId(),
                    isBlank(declaredVersion) ? "has no version meta" : "its spec declares no info.version");
            return null;
        }
        if (declaredVersion.trim().equals(specVersion.trim())) {
            return null;
        }
        return "meta version " + declaredVersion.trim() + ", spec info.version " + specVersion.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Refuses a spec endpoint that points somewhere CAPI has no business fetching from.
     *
     * <p>The {@code open-api} endpoint is the one URL a service owner supplies that CAPI then
     * requests itself, with the Consul HTTP client — which carries CAPI's trust store. Without a
     * check, a registration can aim it at the cloud metadata service or at Consul's own KV API on
     * loopback, and whatever parses as OpenAPI is republished on
     * {@code /definitions/openapi/<that service>}.
     *
     * <p>Deliberately **not** a private-range block. CAPI's legitimate backends are on RFC 1918
     * addresses — that is where a spec normally lives — so blanket-blocking them would refuse nearly
     * every real endpoint. This blocks only the two ranges that are never a legitimate spec host:
     *
     * <ul>
     *   <li><b>link-local</b> (169.254.0.0/16, fe80::/10) — the cloud metadata endpoints;</li>
     *   <li><b>loopback</b> (127.0.0.0/8, ::1) — CAPI's own admin port and a co-located Consul agent,
     *       reachable from the gateway but never from the service it claims to describe.</li>
     * </ul>
     *
     * <p>The host is resolved before comparison, so {@code 0177.0.0.1} and {@code 2130706433} — both
     * accepted by {@code URI} and by the JDK HTTP client — are caught along with the dotted form.
     * A name that resolves to a blocked address is caught too.
     *
     * <p>Set {@code capi.openApi.allowLocalSpecEndpoints: true} for a dev stack that genuinely serves
     * specs from loopback. Link-local stays blocked either way.
     *
     * @throws IllegalArgumentException when the endpoint must not be fetched
     */
    void assertSpecEndpointAllowed(URI uri) {
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("OpenAPI endpoint has no host: " + uri);
        }
        InetAddress address;
        try {
            address = InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            // Unresolvable is the fetch's problem, not this check's — let it fail on connect with a
            // message that says so.
            return;
        }
        if (address.isLinkLocalAddress() || address.isAnyLocalAddress()) {
            throw new IllegalArgumentException(
                    "OpenAPI endpoint resolves to a link-local address (" + address.getHostAddress()
                            + "), which is never a valid spec host: " + uri);
        }
        if (address.isLoopbackAddress() && !allowLocalSpecEndpoints) {
            throw new IllegalArgumentException(
                    "OpenAPI endpoint resolves to loopback (" + address.getHostAddress()
                            + "); set capi.openApi.allowLocalSpecEndpoints to allow it: " + uri);
        }
    }

    public HttpRequest buildOpenApiRequest(Service service) {
        String openApiEndpoint = service.getServiceMeta().getOpenApiEndpoint();
        URI uri = URI.create(openApiEndpoint);
        if (uri.getPath() != null && uri.getPath().contains("..")) {
            throw new IllegalArgumentException("Path traversal detected in URI path: " + uri.getPath());
        }
        assertSpecEndpointAllowed(uri);
        return HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Checks that a fetched spec identifies the service that declared it: {@code info.title} must
     * equal the Consul service name exactly, and {@code info.version} must be present.
     *
     * <p>Two problems at once. The routine one is misregistration — an owner copy-pastes a
     * registration and leaves someone else's {@code open-api} URL in it, so CAPI silently caches
     * another API's spec and then drives its operation-security gate from it. The other is that the
     * spec endpoint is the one URL a service owner can point anywhere: without this, whatever
     * answers gets parsed and republished on {@code /definitions/openapi/<their service>}.
     *
     * <p>Matching is exact after trimming: no case folding, no separator folding. The owner controls
     * both sides, and a governance rule people have to guess at is not one.
     *
     * <p>Matches the Consul service <em>name</em>, not {@code name:group} — one spec legitimately
     * describes an API registered into several groups.
     *
     * @return a human-readable description of the mismatch, or {@code null} when the spec identifies
     *         the service (including when there is no spec to check)
     */
    public String openApiIdentityMismatch(Service service) {
        OpenAPI openAPI = service.getOpenAPI();
        if (openAPI == null) {
            return null;
        }
        if (openAPI.getInfo() == null) {
            return "spec declares no info block";
        }
        String title = openAPI.getInfo().getTitle();
        String version = openAPI.getInfo().getVersion();
        if (isBlank(title)) {
            return "spec declares no info.title";
        }
        if (isBlank(version)) {
            return "spec declares no info.version";
        }
        if (!title.trim().equals(service.getName())) {
            return "spec info.title '" + title.trim() + "' does not identify service '" + service.getName() + "'";
        }
        return null;
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

    public boolean serviceHasOpenApiEndpoint(Service service) {
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