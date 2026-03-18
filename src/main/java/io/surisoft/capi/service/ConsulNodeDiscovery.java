package io.surisoft.capi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.configuration.CapiSslContextHolder;
import io.surisoft.capi.processor.ServiceCapiInstanceMapper;
import io.surisoft.capi.processor.ThrottleProcessor;
import io.surisoft.capi.schema.*;
import io.surisoft.capi.utils.*;
import io.surisoft.capi.schema.GrpcClient;
import jakarta.annotation.Nullable;
import org.cache2k.Cache;
import org.cache2k.CacheEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class ConsulNodeDiscovery {

    private static final String GET_ALL_SERVICES = "/v1/catalog/services";
    private static final String GET_SERVICE_BY_NAME = "/v1/catalog/service/";

    private static volatile boolean connectedToConsul = false;
    private static final Logger log = LoggerFactory.getLogger(ConsulNodeDiscovery.class);
    private HttpClient httpClient;
    private CapiSslContextHolder capiSslContextHolder;
    private List<CAPIConfiguration.HostConfig> consulHosts;
    private String capiInstanceName;
    private boolean strictToInstanceName;
    private final ServiceUtils serviceUtils;
    private final Cache<String, Service> serviceCache;
    private final RouteUtils routeUtils;
    private String serviceMetaExtrasPrefix;
    private HttpUtils httpUtils;
    private OpaService opaService;
    private String capiRunningMode;

    private WebsocketUtils websocketUtils;
    private GrpcUtils grpcUtils;

    private Map<String, WebsocketClient> websocketClientMap;
    private Map<String, GrpcClient> grpcClientMap;
    private Map<String, RestClient> restClientMap;

    private ThrottleProcessor throttleProcessor;

    private String reverseProxyHost;
    private String capiContext;
    private int globalResponseTimeout = 120000;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ConsulNodeDiscovery(@Nullable CapiSslContextHolder capiSslContextHolder,
                               List<CAPIConfiguration.HostConfig> consulHosts,
                               ServiceUtils serviceUtils, Cache<String, Service> serviceCache,
                               RouteUtils routeUtils, WebsocketUtils websocketUtils, HttpClient httpClient) {
        this.capiSslContextHolder = capiSslContextHolder;
        this.consulHosts = consulHosts;
        this.serviceUtils = serviceUtils;
        this.serviceCache = serviceCache;
        this.routeUtils = routeUtils;
        this.websocketUtils = websocketUtils;
        this.httpClient = httpClient;
    }

    public void processInfo() {
        try {
            Map<String, List<ConsulObject>> serviceListObjects = getAllServices();
            lookForRemovedServices(serviceListObjects);
            processServices(serviceListObjects);
        } catch (InterruptedException e) {
            log.error(ErrorMessage.ERROR_CONNECTING_TO_CONSUL);
            //throw new RuntimeException(e);
        } catch (IOException e) {
            log.error(ErrorMessage.ERROR_CONNECTING_TO_CONSUL);
            //throw new RuntimeException(e);
        }
    }

    private Map<String, List<ConsulObject>> getAllServices() throws InterruptedException, IOException {
        Map<String, List<ConsulObject>> serviceListObjects = new HashMap<>();
        HttpResponse<String> response;

        for(CAPIConfiguration.HostConfig consulHost : consulHosts) {
            log.trace("Querying Consul {} for new services", consulHost);
            response = httpClient.send(buildServicesHttpRequest(consulHost), HttpResponse.BodyHandlers.ofString());
            @SuppressWarnings("unchecked")
            Map<String, Object> responseObject = objectMapper.readValue(response.body(), Map.class);
            responseObject.remove("consul");
            Set<String> services = responseObject.keySet();

            // Fire all per-service lookups in parallel
            Map<String, CompletableFuture<HttpResponse<String>>> futures = new LinkedHashMap<>();
            for(String serviceName : services) {
                futures.put(serviceName, httpClient.sendAsync(
                    buildServiceNameHttpRequest(consulHost, serviceName),
                    HttpResponse.BodyHandlers.ofString()
                ));
            }

            // Collect results
            for(Map.Entry<String, CompletableFuture<HttpResponse<String>>> entry : futures.entrySet()) {
                String serviceName = entry.getKey();
                try {
                    HttpResponse<String> serviceResponse = entry.getValue().join();
                    List<ConsulObject> consulInstanceObjectList = processServiceByNameResponse(serviceName, serviceResponse);
                    if(consulInstanceObjectList != null) {
                        if(serviceListObjects.containsKey(serviceName)) {
                            serviceListObjects.get(serviceName).addAll(consulInstanceObjectList);
                        } else {
                            serviceListObjects.put(serviceName, consulInstanceObjectList);
                        }
                    }
                } catch(CompletionException e) {
                    log.error("Failed to fetch service {}: {}", serviceName, e.getMessage());
                }
            }
        }
        return serviceListObjects;
    }

    private void lookForRemovedServices(Map<String, List<ConsulObject>> serviceListObjects) {
        Map<String, ConsulObject> servicesOnConsul = new HashMap<>();
        for(Map.Entry<String, List<ConsulObject>> entry : serviceListObjects.entrySet()) {
            List<ConsulObject> serviceList = entry.getValue();
            for(ConsulObject consulObject : serviceList) {
                String serviceId = null;

                Map<String, String> multipleCapiInstances = new HashMap<>();
                consulObject.getServiceMeta().getUnknownProperties().forEach((unknownKey, unknownValue) -> {
                    if(unknownKey.startsWith(ServiceCapiInstanceMapper.SERVICE_CAPI_INSTANCE_PREFIX)) {
                        multipleCapiInstances.put(unknownKey, unknownValue);
                    }
                });

                ServiceCapiInstances.Instance thisInstance = serviceUtils.getServiceCapiInstance(consulObject, capiInstanceName);

                if(thisInstance != null) {
                    if(thisInstance.isRouteGroupFirst()) {
                        serviceId = consulObject.getServiceMeta().getGroup() + ":" + consulObject.getServiceName();
                    } else {
                        serviceId = consulObject.getServiceName() + ":" + consulObject.getServiceMeta().getGroup();
                    }
                } else {
                    if(consulObject.getServiceMeta().isRouteGroupFirst()) {
                        serviceId = consulObject.getServiceMeta().getGroup() + ":" + consulObject.getServiceName();
                    } else {
                        serviceId = consulObject.getServiceName() + ":" + consulObject.getServiceMeta().getGroup();
                    }
                }
                servicesOnConsul.put(serviceId, consulObject);
            }
        }
        try {
            for(CacheEntry<String, Service> stringServiceCacheEntry : serviceCache.entries()) {
                if(!servicesOnConsul.containsKey(stringServiceCacheEntry.getKey())) {
                    log.debug("This service is not on Consul: {}, and will be removed.", stringServiceCacheEntry.getKey());
                    serviceUtils.removeUnusedService(Objects.requireNonNull(serviceCache.get(stringServiceCacheEntry.getKey())));
                    serviceCache.remove(stringServiceCacheEntry.getKey());
                }
            }
        } catch(Exception e) {
            log.error(e.getMessage());
        }
    }

    private HttpRequest buildServicesHttpRequest(CAPIConfiguration.HostConfig hostConfig) {
        HttpRequest.Builder builder = HttpRequest.newBuilder();
        URI uri = URI.create(hostConfig.getEndpoint() + GET_ALL_SERVICES);
        if (uri.getPath() != null && uri.getPath().contains("..")) {
            throw new IllegalArgumentException("Path traversal detected in URI path: " + uri.getPath());
        }
        if(hostConfig.getToken() != null && !hostConfig.getToken().isEmpty()) {
            builder.header(Constants.AUTHORIZATION_HEADER, Constants.BEARER + hostConfig.getToken().replaceAll("(\r\n|\n)", ""));
        }
        return builder
                .uri(uri)
                .timeout(Duration.ofSeconds(10))
                .build();
    }

    private List<ConsulObject> processServiceByNameResponse(String serviceName, HttpResponse<String> response) {
        log.trace("Processing service name: {}", serviceName);
        List<ConsulObject> servicesToDeploy = new ArrayList<>();
        try {
            TypeReference<List<ConsulObject>> typeRef = new TypeReference<>() {};
            List<ConsulObject> temporaryList = objectMapper.readValue(response.body(), typeRef);
            temporaryList.forEach(o -> {
                //CAPI Supports Services to declare availability to multiple capi instances
                boolean hasMultiForThis = serviceUtils.getServiceCapiInstance(o, capiInstanceName) != null;
                boolean hasSingleForThis = o.getServiceMeta() != null
                        && o.getServiceMeta().getCapiNamespace() != null
                        && o.getServiceMeta().getCapiNamespace().equals(capiInstanceName);

                if(hasMultiForThis || hasSingleForThis) {
                    // Service is declared for this instance (multi or single)
                    servicesToDeploy.add(o);
                } else if(serviceUtils.isTheServiceRegisteredForOtherInstances(o, capiInstanceName)
                        || (o.getServiceMeta() != null && o.getServiceMeta().getCapiNamespace() != null)) {
                    // Service is declared for other instances only
                    log.trace("Service is declared for other instances, not for this one");
                } else if(!strictToInstanceName) {
                    // No instance declared at all, non-strict mode accepts it
                    servicesToDeploy.add(o);
                } else {
                    // No instance declared, strict mode rejects it
                    log.trace("Strict mode: service has no instance declaration, will not deploy");
                }
            });
            return servicesToDeploy;
        } catch (IOException e) {
            log.error("Error processing service {}: {}", serviceName, e.getMessage());
        }
        return null;
    }

    private void processServices(Map<String, List<ConsulObject>> serviceListObjects) {
        // Phase 1: Identify services that need route creation and pre-fetch OpenAPI specs in parallel
        List<Service> servicesToDeploy = new ArrayList<>();
        serviceListObjects.forEach((serviceName, objectList) -> {
            log.trace("Processing service name: {}", serviceName);
            Map<String, Set<Mapping>> servicesStructure = groupByServiceId(objectList);
            for (var entry : servicesStructure.entrySet()) {
                Service incomingService = createServiceObject(serviceName, entry.getKey(), entry.getValue(), objectList);
                Service existingService = serviceCache.peek(incomingService.getId());
                if(existingService == null) {
                    servicesToDeploy.add(incomingService);
                } else {
                    if(serviceUtils.updateExistingService(existingService, incomingService, serviceCache)) {
                        boolean shouldCreate = true;
                        if(incomingService.getServiceCapiInstances() != null) {
                            if(!incomingService.getServiceCapiInstances().getInstances().containsKey(capiInstanceName)) {
                                shouldCreate = false;
                            }
                        }
                        if(shouldCreate) {
                            servicesToDeploy.add(incomingService);
                        }
                    }
                }
            }
        });

        // Phase 2: Fire all OpenAPI fetches in parallel
        Map<Service, CompletableFuture<HttpResponse<String>>> openApiFutures = new LinkedHashMap<>();
        for(Service service : servicesToDeploy) {
            if(serviceUtils.needsOpenApiFetch(service)) {
                try {
                    openApiFutures.put(service, httpClient.sendAsync(
                        serviceUtils.buildOpenApiRequest(service),
                        HttpResponse.BodyHandlers.ofString()
                    ));
                } catch(Exception e) {
                    log.warn("Failed to build OpenAPI request for service {}: {}", service.getId(), e.getMessage());
                }
            }
        }

        // Phase 3: Process results and create routes
        for(Service service : servicesToDeploy) {
            CompletableFuture<HttpResponse<String>> future = openApiFutures.get(service);
            if(future != null) {
                try {
                    HttpResponse<String> response = future.join();
                    if(serviceUtils.processOpenApiSpec(service, response)) {
                        createRoute(service);
                    }
                } catch(CompletionException e) {
                    log.warn("Failed to fetch OpenAPI spec for service {}: {}", service.getId(), e.getMessage());
                }
            } else {
                createRoute(service);
            }
        }

        connectedToConsul = true;
    }

    private Service createServiceObject(String serviceName, String key, Set<Mapping> mappingList, List<ConsulObject> consulResponse) {
        Service incomingService = new Service();
        ServiceMeta serviceMeta = getServiceMeta(key, consulResponse);

        if(serviceMetaExtrasPrefix != null) {
            serviceMeta.getUnknownProperties().forEach((unknownKey, unknownValue) -> {
                if(unknownKey.startsWith(serviceMetaExtrasPrefix)) {
                    serviceMeta.addExtraServiceMeta(unknownKey.replace(serviceMetaExtrasPrefix, ""), unknownValue);
                }
            });
        }

        Map<String, String> multipleCapiInstances = new HashMap<>();
        serviceMeta.getUnknownProperties().forEach((unknownKey, unknownValue) -> {
            if(unknownKey.startsWith(ServiceCapiInstanceMapper.SERVICE_CAPI_INSTANCE_PREFIX)) {
                multipleCapiInstances.put(unknownKey, unknownValue);
            }
        });

        if(!multipleCapiInstances.isEmpty()) {
            incomingService.setServiceCapiInstances(new ServiceCapiInstanceMapper().convert(multipleCapiInstances));
        }

        if(serviceMeta.isRouteGroupFirst()) {
            incomingService.setId(key + ":" + serviceName);
            incomingService.setContext("/" + key + "/" + serviceName);
        } else {
            incomingService.setId(serviceName + ":" + key);
            incomingService.setContext("/" + serviceName + "/" + key);
        }

        incomingService.setName(serviceName);
        incomingService.setRegisteredBy(getClass().getName());

        incomingService.setMappingList(mappingList);
        incomingService.setServiceMeta(getServiceMeta(key, consulResponse));
        incomingService.setRoundRobinEnabled(incomingService.getMappingList().size() != 1);
        incomingService.setFailOverEnabled(incomingService.getMappingList().size() != 1);

        serviceUtils.validateServiceType(incomingService);

        updateServiceWithSpecificInstance(incomingService);

        return incomingService;
    }

    private Map<String, Set<Mapping>> groupByServiceId(List<ConsulObject> consulService) {
        Map<String, Set<Mapping>> groupedService = new HashMap<>();
        Set<String> serviceIdList = new HashSet<>();
        for(ConsulObject serviceIdEntry : consulService) {
            String serviceNodeGroup = getServiceNodeGroup(serviceIdEntry);
            if(serviceNodeGroup != null) {
                serviceIdList.add(serviceNodeGroup);
            } else {
                log.trace("Meta data {} group not present, service will not be deployed", serviceIdEntry.getServiceName());
            }
        }
        for (String id : serviceIdList) {
            Set<Mapping> mappingList = new HashSet<>();
            for (ConsulObject serviceIdToProcess : consulService) {
                String serviceNodeGroup = getServiceNodeGroup(serviceIdToProcess);
                if (id.equals(serviceNodeGroup)) {
                    Mapping entryMapping = serviceUtils.consulObjectToMapping(serviceIdToProcess);
                    mappingList.add(entryMapping);
                }
            }
            groupedService.put(id, mappingList);
        }
        return groupedService;
    }

    private String getServiceNodeGroup(ConsulObject consulObject) {
        if(consulObject.getServiceMeta() != null && consulObject.getServiceMeta().getGroup() != null) {
            return consulObject.getServiceMeta().getGroup();
        }
        return null;
    }

    private HttpRequest buildServiceNameHttpRequest(CAPIConfiguration.HostConfig hostConfig, String serviceName) {
        HttpRequest.Builder builder = HttpRequest.newBuilder();
        URI uri = URI.create(hostConfig.getEndpoint() + GET_SERVICE_BY_NAME + serviceName);
        if (uri.getPath() != null && uri.getPath().contains("..")) {
            throw new IllegalArgumentException("Path traversal detected in URI path: " + uri.getPath());
        }
        if(hostConfig.getToken() != null && !hostConfig.getToken().isEmpty()) {
            builder.header(Constants.AUTHORIZATION_HEADER, Constants.BEARER + hostConfig.getToken().replaceAll("(\r\n|\n)", ""));
        }
        return builder
                .uri(uri)
                .timeout(Duration.ofSeconds(10))
                .build();
    }

    private void updateServiceWithSpecificInstance(Service incomingService) {
        if(incomingService.getServiceCapiInstances() != null && incomingService.getServiceCapiInstances().getInstances() != null) {
            ServiceCapiInstances.Instance thisInstance = incomingService.getServiceCapiInstances().getInstances().get(capiInstanceName);
            if(thisInstance != null) {
                if(!thisInstance.isAssumeParentSecured()) {
                    incomingService.getServiceMeta().setSecured(thisInstance.isSecured());
                }

                if(!thisInstance.isAssumeParentRouteGroupFirst()) {
                    incomingService.getServiceMeta().setRouteGroupFirst(thisInstance.isRouteGroupFirst());
                }

                if(thisInstance.isRouteGroupFirst()) {
                    incomingService.setId(incomingService.getServiceMeta().getGroup() + ":" + incomingService.getName());
                    incomingService.setContext("/" + incomingService.getServiceMeta().getGroup() + "/" + incomingService.getName());
                } else {
                    incomingService.setId(incomingService.getName() + ":" + incomingService.getServiceMeta().getGroup());
                    incomingService.setContext("/" + incomingService.getName() + "/" + incomingService.getServiceMeta().getGroup());
                }
                if(thisInstance.getOpenApi() != null) {
                    incomingService.getServiceMeta().setOpenApiEndpoint(thisInstance.getOpenApi());
                } else if(thisInstance.isIgnoreOpenApi()) {
                    incomingService.setOpenAPI(null);
                }
                if(thisInstance.getScheme() != null && !thisInstance.getScheme().isEmpty()) {
                    incomingService.getServiceMeta().setScheme(thisInstance.getScheme());
                }
            }
        }
    }

    public ServiceMeta getServiceMeta(String key, List<ConsulObject> consulObject) {
        for(ConsulObject entry : consulObject) {
            if(Objects.equals(getServiceNodeGroup(entry), key)) {
                return entry.getServiceMeta();
            }
        }
        return null;
    }

    public void setServiceMetaExtrasPrefix(String serviceMetaExtrasPrefix) {
        this.serviceMetaExtrasPrefix = serviceMetaExtrasPrefix;
    }

    private void createRoute(Service incomingService) {
        if(incomingService.getServiceMeta().getState() == null || incomingService.getServiceMeta().getState().equals(State.PUBLISHED)) {
            serviceCache.put(incomingService.getId(), incomingService);
            if((incomingService.getServiceMeta().getType().equalsIgnoreCase(Constants.WEBSOCKET_TYPE) ||
                    incomingService.getServiceMeta().getType().equalsIgnoreCase(Constants.SSE_TYPE)) &&
                    (capiRunningMode.equalsIgnoreCase(Constants.WEBSOCKET_TYPE) || capiRunningMode.equalsIgnoreCase(Constants.SSE_TYPE) || capiRunningMode.equalsIgnoreCase(Constants.FULL_TYPE)) && websocketUtils != null) {
                WebsocketClient websocketClient = websocketUtils.createWebsocketClient(incomingService);
                if(websocketClient != null && websocketClientMap != null) {
                    websocketClientMap.put(websocketClient.getServiceId(), websocketClient);
                }
            } else if(incomingService.getServiceMeta().getType().equalsIgnoreCase(Constants.GRPC_TYPE) && grpcUtils != null) {
                GrpcClient grpcClient = grpcUtils.createGrpcClient(incomingService);
                if(grpcClient != null && grpcClientMap != null) {
                    grpcClientMap.put(grpcClient.getServiceId(), grpcClient);
                }
            } else if(capiRunningMode.equalsIgnoreCase(Constants.FULL_TYPE) && (incomingService.getServiceMeta().getType() == null || incomingService.getServiceMeta().getType().equals("rest"))) {
                if(restClientMap != null && websocketUtils != null) {
                    String clientId = incomingService.getContext();
                    if(!restClientMap.containsKey(clientId)) {
                        RestClient restClient = new RestClient();
                        restClient.setServiceId(clientId);
                        restClient.setMappingList(incomingService.getMappingList());
                        String rootContext = incomingService.getMappingList().stream().toList().get(0).getRootContext();
                        if(rootContext != null && !rootContext.isEmpty() && !rootContext.equals("/") && !rootContext.equals("*")) {
                            restClient.setRootContext(rootContext);
                        }
                        restClient.setSecured(incomingService.getServiceMeta().isSecured());
                        restClient.setSubscriptionGroup(incomingService.getServiceMeta().getSubscriptionGroup());
                        restClient.setOpaRego(incomingService.getServiceMeta().getOpaRego());
                        restClient.setApiKeyEnabled(incomingService.getServiceMeta().isApiKeyEnabled());
                        restClient.setThrottle(incomingService.getServiceMeta().isThrottle());
                        restClient.setThrottleGlobal(incomingService.getServiceMeta().isThrottleGlobal());
                        restClient.setThrottleTotalCalls(incomingService.getServiceMeta().getThrottleTotalCalls());
                        restClient.setThrottleDuration(incomingService.getServiceMeta().getThrottleDuration());
                        restClient.setFailOverEnabled(incomingService.isFailOverEnabled());
                        restClient.setRoundRobinEnabled(incomingService.isRoundRobinEnabled());
                        restClient.setB3TraceId(incomingService.getServiceMeta().isB3TraceId());
                        restClient.setKeepGroup(incomingService.getServiceMeta().isKeepGroup());
                        if (incomingService.getOpenAPI() != null) {
                            restClient.setOpenAPI(incomingService.getOpenAPI());
                        }
                        WebsocketClient tempClient = new WebsocketClient();
                        tempClient.setMappingList(incomingService.getMappingList());
                        restClient.setHttpHandler(websocketUtils.createClientHttpHandler(tempClient, incomingService, new io.surisoft.capi.exception.HttpErrorHandler(httpUtils), globalResponseTimeout));
                        restClientMap.put(clientId, restClient);
                        log.trace("REST client registered: {} with {} backend(s)", clientId, incomingService.getMappingList().size());
                    }
                }
            }
        }
    }

    public void setOpaService(OpaService opaService) {
        this.opaService = opaService;
    }

    public void setHttpUtils(HttpUtils httpUtils) {
        this.httpUtils = httpUtils;
    }

    public void setCapiRunningMode(String capiRunningMode) {
        this.capiRunningMode = capiRunningMode;
    }

    public void setWebsocketClientMap(Map<String, WebsocketClient> websocketClientMap) {
        this.websocketClientMap = websocketClientMap;
    }

    public static boolean isConnectedToConsul() {
        return connectedToConsul;
    }

    public void setThrottleProcessor(ThrottleProcessor throttleProcessor) {
        this.throttleProcessor = throttleProcessor;
    }

    public void setReverseProxyHost(String reverseProxyHost) {
        this.reverseProxyHost = reverseProxyHost;
    }

    public void setCapiContext(String capiContext) {
        this.capiContext = capiContext;
    }

    public void setGlobalResponseTimeout(int globalResponseTimeout) {
        this.globalResponseTimeout = globalResponseTimeout;
    }

    public void setStrictToInstanceName(boolean strictToInstanceName) {
        this.strictToInstanceName = strictToInstanceName;
    }

    public void setCapiInstanceNamespace(String capiInstanceName) {
        this.capiInstanceName = capiInstanceName;
    }

    public void setGrpcUtils(GrpcUtils grpcUtils) {
        this.grpcUtils = grpcUtils;
    }

    public void setGrpcClientMap(Map<String, GrpcClient> grpcClientMap) {
        this.grpcClientMap = grpcClientMap;
    }

    public void setRestClientMap(Map<String, RestClient> restClientMap) {
        this.restClientMap = restClientMap;
    }
}