package io.surisoft.capi.undertow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.metrics.Info;
import io.surisoft.capi.metrics.Routes;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.utils.Startup;
import io.undertow.Undertow;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.PathHandler;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import org.apache.camel.CamelContext;
import org.cache2k.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;


public class AdminGateway {

    private static final Logger log = LoggerFactory.getLogger(AdminGateway.class);
    private final int port;
    private final PrometheusMeterRegistry prometheusRegistry;
    private final CAPIConfiguration capiConfiguration;
    private final CamelContext camelContext;
    private Undertow server;
    private final Cache<String, Service> serviceCache;
    private final SSLContext sslContext;
    ObjectMapper objectMapper = new ObjectMapper();

    public AdminGateway(int port, PrometheusMeterRegistry prometheusRegistry, CAPIConfiguration capiConfiguration, CamelContext camelContext, Cache<String, Service> serviceCache, SSLContext sslContext) {
        this.port = port;
        this.prometheusRegistry = prometheusRegistry;
        this.capiConfiguration = capiConfiguration;
        this.camelContext = camelContext;
        this.serviceCache = serviceCache;
        this.sslContext = sslContext;
    }

    public void start() {
        PathHandler pathHandler = new PathHandler()
                .addExactPath("/info/metrics", this::handleMetrics)
                .addExactPath("/info/health", this::handleHealth)
                .addExactPath("/info/capi", this::handleCapiInfo)
                .addExactPath("/info/routes", this::handleRoutesInfo)
                .addPrefixPath("/info/routes/", this::handleRouteById);


        Undertow.Builder builder = Undertow.builder();
        if(sslContext != null) {
            builder.addHttpsListener(port, "0.0.0.0", sslContext);
        } else {
            builder.addHttpListener(port, "0.0.0.0");
        }
        server = builder.setHandler(pathHandler).build();
        server.start();
        log.info("Admin Gateway started on port {}", port);
    }

    public void stop() {
        if(server != null) {
            log.info("Stopping Admin Gateway on port {}", port);
            server.stop();
        }
    }

    private void handleMetrics(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain; version=0.0.4; charset=utf-8");
        exchange.setStatusCode(StatusCodes.OK);
        exchange.getResponseSender().send(prometheusRegistry.scrape());
    }

    private void handleHealth(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.setStatusCode(StatusCodes.OK);
        exchange.getResponseSender().send("{\"status\":\"UP\"}");
    }

    private void handleCapiInfo(HttpServerExchange exchange) {
        try {
            Info info = new Info(capiConfiguration, camelContext);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            exchange.setStatusCode(StatusCodes.OK);
            exchange.getResponseSender().send(objectMapper.writeValueAsString(info.getInfo()));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private void handleRouteById(HttpServerExchange exchange) {
        String serviceId = exchange.getRelativePath().substring(1);
        if(serviceId.isEmpty()) {
            handleRoutesInfo(exchange);
            return;
        }
        try {
            Routes routes = new Routes(camelContext, serviceCache);
            Service service = routes.getCachedService(serviceId);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            if(service == null) {
                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                exchange.getResponseSender().send("{\"error\":\"Service not found\"}");
            } else {
                exchange.setStatusCode(StatusCodes.OK);
                exchange.getResponseSender().send(objectMapper.writeValueAsString(service));
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private void handleRoutesInfo(HttpServerExchange exchange) {
        try {
            Routes routes = new Routes(camelContext, serviceCache);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            exchange.setStatusCode(StatusCodes.OK);
            exchange.getResponseSender().send(objectMapper.writeValueAsString(routes.getAllRoutesInfo()));
        }catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}