package io.surisoft.capi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.surisoft.capi.builder.DirectRouteProcessor;
import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.configuration.CapiSslContextHolder;
import io.surisoft.capi.processor.ContentTypeValidator;
import io.surisoft.capi.processor.MetricsProcessor;
import io.surisoft.capi.processor.ServiceCapiInstanceMapper;
import io.surisoft.capi.processor.ThrottleProcessor;
import io.surisoft.capi.schema.*;
import io.surisoft.capi.utils.*;
import jakarta.annotation.Nullable;
import org.apache.camel.CamelContext;
import org.apache.camel.Route;
import org.apache.camel.util.json.JsonObject;
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

public class ConsulNodeDiscovery {

    private static final String GET_ALL_SERVICES = "/v1/catalog/services";
    private static final String GET_SERVICE_BY_NAME = "/v1/catalog/service/";

    private static boolean connectedToConsul = false;
    private static final Logger log = LoggerFactory.getLogger(ConsulNodeDiscovery.class);
    private final CamelContext camelContext;
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
    private SSEUtils sseUtils;

    private Map<String, WebsocketClient> websocketClientMap;
    private Map<String, SSEClient> sseClientMap;

    private MetricsProcessor metricsProcessor;
    private ThrottleProcessor throttleProcessor;
    private ContentTypeValidator contentTypeValidator;

    private String reverseProxyHost;
    private String capiContext;

    ObjectMapper objectMapper = new ObjectMapper();

    public ConsulNodeDiscovery(CamelContext camelContext, @Nullable CapiSslContextHolder capiSslContextHolder,
                               List<CAPIConfiguration.HostConfig> consulHosts,
                               ServiceUtils serviceUtils, Cache<String, Service> serviceCache,
                               RouteUtils routeUtils, WebsocketUtils websocketUtils, HttpClient httpClient) {
        this.camelContext = camelContext;
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
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Map<String, List<ConsulObject>> getAllServices() throws InterruptedException, IOException {
        Map<String, List<ConsulObject>> serviceListObjects = new HashMap<>();
        HttpResponse<String> response;

        for(CAPIConfiguration.HostConfig consulHost : consulHosts) {
            log.trace("Querying Consul {} for new services", consulHost);
            response = httpClient.send(buildServicesHttpRequest(consulHost), HttpResponse.BodyHandlers.ofString());
            JsonObject responseObject = objectMapper.readValue(response.body(), JsonObject.class);
            //We want to ignore the consul array
            responseObject.remove("consul");
            Set<String> services = responseObject.keySet();
            for(String serviceName : services) {
                List<ConsulObject> consulInstanceObjectList = getServiceByName(consulHost, serviceName);
                if(consulInstanceObjectList != null) {
                    if(serviceListObjects.containsKey(serviceName)) {
                        serviceListObjects.get(serviceName).addAll(consulInstanceObjectList);
                    } else {
                        serviceListObjects.put(serviceName, consulInstanceObjectList);
                    }
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
                    serviceUtils.removeUnusedService(camelContext, routeUtils, Objects.requireNonNull(serviceCache.get(stringServiceCacheEntry.getKey())));
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
                .timeout(Duration.ofMinutes(2))
                .build();
    }

    private List<ConsulObject> getServiceByName(CAPIConfiguration.HostConfig consulHost, String serviceName) {
        log.trace("Getting service name: {} at consul host: {}", serviceName, consulHost);
        List<ConsulObject> servicesToDeploy = new ArrayList<>();
        try {
            HttpResponse<String> response = httpClient.send(buildServiceNameHttpRequest(consulHost, serviceName), HttpResponse.BodyHandlers.ofString());
            ObjectMapper objectMapper = new ObjectMapper();
            TypeReference<List<ConsulObject>> typeRef = new TypeReference<>() {};
            List<ConsulObject> temporaryList = objectMapper.readValue(response.body(), typeRef);
            temporaryList.forEach(o -> {
                //CAPI Supports Services to declare availability to multiple capi instances
                ServiceCapiInstances.Instance instanceDeclared = serviceUtils.getServiceCapiInstance(o, capiInstanceName);
                boolean serviceAdded = false;
                if(instanceDeclared != null) {
                    //The service has declared this instance as multi instance support
                    //ex.: For an instance named "default".
                    // Service meta: capi-instance-default-route-group-first
                    servicesToDeploy.add(o);
                    serviceAdded = true;
                } else {
                    //The service has declared another instance as multi instance support
                    //ex.: For an instance named "default".
                    // Service meta: capi-instance-other-route-group-first
                    log.trace("Service is declared as multi instance, but not for this instance");
                }

                if(o.getServiceMeta() != null && o.getServiceMeta().getCapiNamespace() != null && o.getServiceMeta().getCapiNamespace().equals(capiInstanceName)) {
                    if(!serviceAdded) {
                        //The service has declared as single instance do this instance
                        //ex.: For an instance named "default".
                        // Service meta: capi-instance: default
                        servicesToDeploy.add(o);
                    }
                } else if(o.getServiceMeta() != null && o.getServiceMeta().getCapiNamespace() != null && !o.getServiceMeta().getCapiNamespace().equals(capiInstanceName)) {
                    if(!serviceAdded) {
                        //The service has declared as single instance but to a different instance
                        //ex.: For an instance named "default".
                        // Service meta: capi-instance: other
                        log.trace("this service is declared this instance as single, but not to this instance");
                    }
                } else {
                    if(!strictToInstanceName) {
                        if(!serviceAdded) {
                            //We need to check if there are other instances declared in the service
                            if(serviceUtils.isTheServiceRegisteredForOtherInstances(o, capiInstanceName)) {
                                log.trace("this service is declared for other instances, but not to this instance");
                            } else {
                                //This CAPI instance is not stric so it will accept the service
                                servicesToDeploy.add(o);
                            }
                        }
                    } else {
                        if(!serviceAdded) {
                            log.trace("This CAPI is strict so it will not accept the service");
                        }
                    }
                }
            });
            return servicesToDeploy;
        } catch (IOException e) {
            log.error(ErrorMessage.ERROR_CONNECTING_TO_CONSUL);
        } catch (InterruptedException e) {
            log.error(ErrorMessage.ERROR_CONNECTING_TO_CONSUL);
            Thread.currentThread().interrupt();
        }
        return null;
    }

    private void processServices(Map<String, List<ConsulObject>> serviceListObjects) {
        serviceListObjects.forEach((serviceName, objectList) -> {
            log.trace("Processing service name: {}", serviceName);
            Map<String, Set<Mapping>> servicesStructure = groupByServiceId(objectList);
            for (var entry : servicesStructure.entrySet()) {
                Service incomingService = createServiceObject(serviceName, entry.getKey(), entry.getValue(), objectList);
                Service existingService = serviceCache.peek(incomingService.getId());
                if(existingService == null) {
                    boolean createRoute = true;
                    if(incomingService.getServiceCapiInstances() != null) {
                        if(!incomingService.getServiceCapiInstances().getInstances().containsKey(capiInstanceName)) {
                            createRoute = false;
                        }
                    }
                    if(createRoute) {
                        if(serviceUtils.checkIfOpenApiIsEnabled(incomingService, httpClient)) {
                            createRoute(incomingService);
                        }
                    }
                } else {
                    if(serviceUtils.updateExistingService(existingService, incomingService, serviceCache)) {
                        boolean createRoute = true;
                        if(incomingService.getServiceCapiInstances() != null) {
                            if(!incomingService.getServiceCapiInstances().getInstances().containsKey(capiInstanceName)) {
                                createRoute = false;
                            }
                        }
                        if(createRoute) {
                            if(serviceUtils.checkIfOpenApiIsEnabled(incomingService, httpClient)) {
                                createRoute(incomingService);
                            }
                        }
                    }
                }
            }
        });
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

        incomingService.setModifyIndex(getModifyIndex(key, consulResponse));

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
                .timeout(Duration.ofMinutes(2))
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

    public int getModifyIndex(String key, List<ConsulObject> consulObject) {
        for(ConsulObject entry : consulObject) {
            if(Objects.equals(getServiceNodeGroup(entry), key)) {
                return entry.getModifyIndex();
            }
        }
        return -1;
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
            if(incomingService.getServiceMeta().getType().equalsIgnoreCase(Constants.WEBSOCKET_TYPE) &&
                    (capiRunningMode.equalsIgnoreCase(Constants.WEBSOCKET_TYPE) || capiRunningMode.equalsIgnoreCase(Constants.FULL_TYPE)) && websocketUtils != null) {
                WebsocketClient websocketClient = websocketUtils.createWebsocketClient(incomingService);
                if(websocketClient != null && websocketClientMap != null) {
                    websocketClientMap.put(websocketClient.getServiceId(), websocketClient);
                }
            } else if(incomingService.getServiceMeta().getType().equalsIgnoreCase(Constants.SSE_TYPE) &&
                    (capiRunningMode.equalsIgnoreCase(Constants.SSE_TYPE) || capiRunningMode.equalsIgnoreCase(Constants.FULL_TYPE))) {
                log.trace("Creating SSE client for service: {}", incomingService.getId());
                SSEClient sseClient = sseUtils.createSSEClient(incomingService);
                if(sseClient != null && sseClientMap != null) {
                    sseClientMap.put(sseClient.getApiId(), sseClient);
                }

            } else if(capiContext != null && capiRunningMode.equalsIgnoreCase(Constants.FULL_TYPE) && (incomingService.getServiceMeta().getType() == null || incomingService.getServiceMeta().getType().equals("rest"))) {
                List<String> apiRouteIdList = routeUtils.getAllRouteIdForAGivenService(incomingService);
                for(String routeId : apiRouteIdList) {
                    Route existingRoute = camelContext.getRoute(routeId);
                    if(existingRoute == null) {
                        try {
                            DirectRouteProcessor directRouteProcessor = new DirectRouteProcessor(camelContext, incomingService, routeUtils, metricsProcessor, routeId, capiContext, reverseProxyHost, contentTypeValidator, throttleProcessor);
                            directRouteProcessor.setHttpUtils(httpUtils);
                            directRouteProcessor.setOpaService(opaService);
                            directRouteProcessor.setServiceCache(serviceCache);
                            camelContext.addRoutes(directRouteProcessor);
                        } catch (Exception e) {
                            log.error(e.getMessage(), e);
                        }
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

    public void setSseClientMap(Map<String, SSEClient> sseClientMap) {
        this.sseClientMap = sseClientMap;
    }

    public static boolean isConnectedToConsul() {
        return connectedToConsul;
    }

    public void setMetricsProcessor(MetricsProcessor metricsProcessor) {
        this.metricsProcessor = metricsProcessor;
    }

    public void setThrottleProcessor(ThrottleProcessor throttleProcessor) {
        this.throttleProcessor = throttleProcessor;
    }

    public void setContentTypeValidator(ContentTypeValidator contentTypeValidator) {
        this.contentTypeValidator = contentTypeValidator;
    }

    public void setReverseProxyHost(String reverseProxyHost) {
        this.reverseProxyHost = reverseProxyHost;
    }

    public void setCapiContext(String capiContext) {
        this.capiContext = capiContext;
    }

    public void setStrictToInstanceName(boolean strictToInstanceName) {
        this.strictToInstanceName = strictToInstanceName;
    }

    public void setCapiInstanceNamespace(String capiInstanceName) {
        this.capiInstanceName = capiInstanceName;
    }
}