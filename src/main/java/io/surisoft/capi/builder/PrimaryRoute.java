package io.surisoft.capi.builder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.surisoft.capi.service.CapiAccessLogReceiver;
import io.surisoft.capi.service.ConsulNodeDiscovery;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.RouteUtils;
import io.undertow.util.Headers;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.camel.Exchange;
import org.apache.camel.Route;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.util.json.JsonObject;
import org.cache2k.Cache;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class PrimaryRoute extends RouteBuilder {

    private final RouteUtils routeUtils;
    private final int capiRestPort;
    private final String capiRestListeningAddress;
    private final String capiRestPath;
    private final boolean sslEnabled;
    private final boolean gatewayCorsManagementEnabled;
    private final Map<String, String> managedHeaders;
    private final Cache<String, Service> serviceCache;
    private static final Pattern VALID_ORIGIN_PATTERN = Pattern.compile("^https?://[^\\s]+$");
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String primaryEndpoint;

    public PrimaryRoute(RouteUtils routeUtils, int capiRestPort, String capiRestListeningAddress, String capiRestPath, boolean sslEnabled, boolean gatewayCorsManagementEnabled, Map<String, String> managedHeaders, Cache<String, Service> serviceCache, String primaryEndpoint) {
        this.routeUtils = routeUtils;
        this.capiRestPort = capiRestPort;
        this.capiRestListeningAddress = capiRestListeningAddress;
        this.capiRestPath = capiRestPath;
        this.sslEnabled = sslEnabled;
        this.gatewayCorsManagementEnabled = gatewayCorsManagementEnabled;
        this.managedHeaders = managedHeaders;
        this.serviceCache = serviceCache;
        this.primaryEndpoint = primaryEndpoint;
    }

    @Override
    public void configure() throws Exception {
        from(primaryEndpoint)
                .choice()
                .when(header(Exchange.HTTP_METHOD).isEqualTo("OPTIONS"))
                .process(exchange -> {
                    processControlledHeaders(exchange);
                    exchange.getIn().setHeader("Access-Control-Max-Age", Constants.ACCESS_CONTROL_MAX_AGE_VALUE);
                    exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, HttpServletResponse.SC_NO_CONTENT);
                })
                .otherwise()
                .process(exchange -> {
                    processControlledHeaders(exchange);
                    String path = exchange.getIn().getHeader(Exchange.HTTP_PATH, String.class);
                    String method = exchange.getIn().getHeader(Exchange.HTTP_METHOD, String.class).toLowerCase();

                    // Strip leading slash, split into segments
                    String[] segments = path.replaceFirst("^/", "").split("/");

                    // We need at least 2 segments for the service ID (name:group)
                    if(segments.length < 2) {
                        String error = buildError(404, "The requested route was not found, please try again later on.");
                        exchange.getIn().setHeader(String.valueOf(Headers.CONTENT_TYPE), "application/json");
                        exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, 404);
                        exchange.getIn().setBody(error);
                        exchange.setRouteStop(true);
                        return;
                    }

                    // First two segments form the service ID: sample-service:dev
                    String routeId = segments[0] + ":" + segments[1] + ":" + method;

                    // Remaining segments form the downstream path
                    StringBuilder remainingPath = new StringBuilder("/");
                    for(int i = 2; i < segments.length; i++) {
                        remainingPath.append(segments[i]);
                        if(i < segments.length - 1) {
                            remainingPath.append("/");
                        }
                    }

                    // Check the CamelContext for the actual running route
                    Route route = getContext().getRoute(routeId);
                    if(route == null) {
                        String error = buildError(404, "The requested route was not found, please try again later on.");
                        exchange.getIn().setHeader(String.valueOf(Headers.CONTENT_TYPE), "application/json");
                        exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, 404);
                        exchange.getIn().setBody(error);
                        exchange.setRouteStop(true);
                    } else {
                        exchange.getIn().setHeader("CamelHttpPath", remainingPath.toString());
                        exchange.setProperty("CamelRecipientListEndpoint", "direct:" + routeId);
                    }
                })
                .choice()
                .when().simple("${exchangeProperty.CamelRecipientListEndpoint} != null")
                .recipientList(simple("${exchangeProperty.CamelRecipientListEndpoint}"))
                .routeId("primary-route");
        routeUtils.registerMetric("primary-route");

        String healthScheme = sslEnabled ? "https" : "http";
        from("undertow:" + healthScheme + "://" + capiRestListeningAddress + ":" + capiRestPort + "/health")
                .process(exchange -> {
                    exchange.getIn().setHeader(String.valueOf(Headers.CONTENT_TYPE), "application/json");
                    if(ConsulNodeDiscovery.isConnectedToConsul()) {
                        exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
                        exchange.getIn().setBody("{\"status\":\"UP\"}");
                    } else {
                        exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, 503);
                        exchange.getIn().setBody("{\"status\":\"DOWN\"}");
                    }
                })
                .routeId("health-route");
    }

    private void processControlledHeaders(Exchange exchange) {
        if(gatewayCorsManagementEnabled) {
            String path = exchange.getIn().getHeader(Exchange.HTTP_URI, String.class);
            boolean capiConsumer = path.startsWith(capiRestPath);
            String origin = "";
            if(exchange.getIn().getHeader(Constants.ORIGIN_HEADER, String.class) != null) {
                if(!exchange.getIn().getHeader(Constants.ORIGIN_HEADER, String.class).equals("null")) {
                    origin = exchange.getIn().getHeader(Constants.ORIGIN_HEADER, String.class);
                }
            }
            if(origin.isEmpty()) {
                if(exchange.getIn().getHeader("Referer") != null) {
                    origin = exchange.getIn().getHeader("Referer", String.class).replaceAll("/$", "");
                }
            }
            managedHeaders.forEach((k, v) -> {
                exchange.getIn().setHeader(k, v);
            });
            processOrigin(exchange, path, origin, capiConsumer);
        }
    }

    private void processOrigin(Exchange exchange, String path, String origin, boolean capiConsumer) {
        if(isValidOrigin(origin)) {
            if(capiConsumer) {
                if(isOriginAllowed(path, origin)) {
                    exchange.getIn().setHeader(Constants.ACCESS_CONTROL_ALLOW_ORIGIN, origin.replaceAll("(\r\n|\n)", ""));
                }
            } else {
                exchange.getIn().setHeader(Constants.ACCESS_CONTROL_ALLOW_ORIGIN, origin.replaceAll("(\r\n|\n)", ""));
            }
        }
    }

    private boolean isOriginAllowed(String path, String origin) {
        String serviceId = path.trim().split("/")[2] + ":" + path.split("/")[3];
        Service service = serviceCache.peek(serviceId);
        if(service != null && service.getServiceMeta() != null) {
            if(service.getServiceMeta().getAllowedOrigins() != null) {
                List<String> allowedOriginsList = Arrays.asList(service.getServiceMeta().getAllowedOrigins().split(",", -1));
                return allowedOriginsList.contains(origin);
            } else {
                return true;
            }
        }
        return true;
    }

    private boolean isValidOrigin(String origin) {
        return origin != null && !origin.isEmpty() && VALID_ORIGIN_PATTERN.matcher(origin).matches();
    }

    private String buildError(int statusCode, String message) throws JsonProcessingException {
        JsonObject jsonObject = new JsonObject();
        jsonObject.put("error", message);
        jsonObject.put("status", statusCode);
        return objectMapper.writeValueAsString(jsonObject);
    }
}
