package io.surisoft.capi.builder;

import io.surisoft.capi.schema.Service;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.RouteUtils;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.camel.Exchange;
import org.apache.camel.Route;
import org.apache.camel.builder.RouteBuilder;
import org.cache2k.Cache;

import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class PrimaryRoute extends RouteBuilder {

    private final RouteUtils routeUtils;
    private final int capiRestPort;
    private final String capiRestListeningAddress;
    private final String capiRestPath;
    private final boolean sslEnabled;
    private final boolean gatewayCorsManagementEnabled;
    private final Map<String, String> managedHeaders;
    private final Cache<String, Service> serviceCache;

    public PrimaryRoute(RouteUtils routeUtils, int capiRestPort, String capiRestListeningAddress, String capiRestPath, boolean sslEnabled, boolean gatewayCorsManagementEnabled, Map<String, String> managedHeaders, Cache<String, Service> serviceCache) {
        this.routeUtils = routeUtils;
        this.capiRestPort = capiRestPort;
        this.capiRestListeningAddress = capiRestListeningAddress;
        this.capiRestPath = capiRestPath;
        this.sslEnabled = sslEnabled;
        this.gatewayCorsManagementEnabled = gatewayCorsManagementEnabled;
        this.managedHeaders = managedHeaders;
        this.serviceCache = serviceCache;
    }

    @Override
    public void configure() throws Exception {
        String scheme = sslEnabled ? "https" : "http";
        from("undertow:" + scheme + "://" + capiRestListeningAddress + ":" + capiRestPort + capiRestPath + "?matchOnUriPrefix=true&optionsEnabled=true&httpMethodRestrict=GET,POST,PUT,DELETE,OPTIONS,PATCH")
                .choice()
                .when(header(Exchange.HTTP_METHOD).isEqualTo("OPTIONS"))
                .process(exchange -> {
                    processControlledHeaders(exchange);
                    exchange.getIn().setHeader("Access-Control-Max-Age", Constants.ACCESS_CONTROL_MAX_AGE_VALUE);
                    exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, HttpServletResponse.SC_ACCEPTED);
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
                        exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, 404);
                        exchange.getIn().setBody("{\"error\":\"Route not found\"}");
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
                        exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, 404);
                        exchange.getIn().setBody("{\"error\":\"Route not found\"}");
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
        try {
            new URL(origin).toURI();
            return true;
        } catch (MalformedURLException | URISyntaxException e) {
            return false;
        }
    }
}
