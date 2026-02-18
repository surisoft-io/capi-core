package io.surisoft.capi.builder;

import io.surisoft.capi.schema.CapiRestError;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.HttpUtils;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.ExchangeBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ErrorRouteTest {

    @Mock
    private HttpUtils httpUtils;

    private CamelContext camelContext;

    @BeforeEach
    void setUp() {
        camelContext = new DefaultCamelContext();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (camelContext != null) {
            camelContext.stop();
        }
    }

    @Test
    void constructionDoesNotThrow() {
        ErrorRoute errorRoute = new ErrorRoute(httpUtils);
        assertNotNull(errorRoute);
    }

    @Test
    void configureDoesNotThrow() {
        ErrorRoute errorRoute = new ErrorRoute(httpUtils);
        assertDoesNotThrow(() -> camelContext.addRoutes(errorRoute));
    }

    @Test
    void configure_registersErrorRoute() throws Exception {
        ErrorRoute errorRoute = new ErrorRoute(httpUtils);
        camelContext.addRoutes(errorRoute);
        camelContext.start();

        // Verify the route with id "error-route" was registered
        assertNotNull(camelContext.getRoute("error-route"));
    }

    @Test
    void process_withReasonHeaders_setsCustomErrorCodeAndMessage() throws Exception {
        String expectedJson = "{\"errorMessage\":\"Service Unavailable\",\"errorCode\":503}";
        when(httpUtils.proxyErrorMapper(any(CapiRestError.class))).thenReturn(expectedJson);

        ErrorRoute errorRoute = new ErrorRoute(httpUtils);
        camelContext.addRoutes(errorRoute);
        camelContext.start();

        ProducerTemplate template = camelContext.createProducerTemplate();
        Exchange exchange = ExchangeBuilder.anExchange(camelContext)
                .withHeader(Constants.REASON_MESSAGE_HEADER, "Service Unavailable")
                .withHeader(Constants.REASON_CODE_HEADER, 503)
                .build();

        Exchange result = template.send(Constants.CAPI_ERROR_ROUTE, exchange);

        assertEquals(expectedJson, result.getIn().getBody(String.class));
        assertEquals(503, result.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE));
        assertEquals("application/json", result.getIn().getHeader("Content-Type"));
        // Reason headers should be removed
        assertNull(result.getIn().getHeader(Constants.REASON_MESSAGE_HEADER));
        assertNull(result.getIn().getHeader(Constants.REASON_CODE_HEADER));
        verify(httpUtils).proxyErrorMapper(any(CapiRestError.class));

        template.stop();
    }

    @Test
    void process_withoutReasonHeaders_setsDefault400Error() throws Exception {
        String expectedJson = "{\"errorMessage\":\"Unknown error\",\"errorCode\":400}";
        when(httpUtils.proxyErrorMapper(any(CapiRestError.class))).thenReturn(expectedJson);

        ErrorRoute errorRoute = new ErrorRoute(httpUtils);
        camelContext.addRoutes(errorRoute);
        camelContext.start();

        ProducerTemplate template = camelContext.createProducerTemplate();
        Exchange exchange = ExchangeBuilder.anExchange(camelContext).build();

        Exchange result = template.send(Constants.CAPI_ERROR_ROUTE, exchange);

        assertEquals(expectedJson, result.getIn().getBody(String.class));
        assertEquals(400, result.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE));
        verify(httpUtils).proxyErrorMapper(any(CapiRestError.class));

        template.stop();
    }

    @Test
    void process_withUriInErrorHeader_setsHttpUriOnError() throws Exception {
        String expectedJson = "{\"httpUri\":\"http://failing-service/api\",\"errorCode\":400}";
        when(httpUtils.proxyErrorMapper(any(CapiRestError.class))).thenReturn(expectedJson);

        ErrorRoute errorRoute = new ErrorRoute(httpUtils);
        camelContext.addRoutes(errorRoute);
        camelContext.start();

        ProducerTemplate template = camelContext.createProducerTemplate();
        Exchange exchange = ExchangeBuilder.anExchange(camelContext)
                .withHeader(Constants.CAPI_URI_IN_ERROR, "http://failing-service/api")
                .build();

        Exchange result = template.send(Constants.CAPI_ERROR_ROUTE, exchange);

        assertEquals(expectedJson, result.getIn().getBody(String.class));
        // Default 400 because no reason headers
        assertEquals(400, result.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE));
        verify(httpUtils).proxyErrorMapper(any(CapiRestError.class));

        template.stop();
    }

    @Test
    void process_withRouteIdHeader_setsRouteIdOnError() throws Exception {
        String expectedJson = "{\"routeID\":\"my-route-123\",\"errorCode\":400}";
        when(httpUtils.proxyErrorMapper(any(CapiRestError.class))).thenReturn(expectedJson);

        ErrorRoute errorRoute = new ErrorRoute(httpUtils);
        camelContext.addRoutes(errorRoute);
        camelContext.start();

        ProducerTemplate template = camelContext.createProducerTemplate();
        Exchange exchange = ExchangeBuilder.anExchange(camelContext)
                .withHeader(Constants.ROUTE_ID_HEADER, "my-route-123")
                .build();

        Exchange result = template.send(Constants.CAPI_ERROR_ROUTE, exchange);

        assertEquals(expectedJson, result.getIn().getBody(String.class));
        assertEquals(400, result.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE));
        verify(httpUtils).proxyErrorMapper(any(CapiRestError.class));

        template.stop();
    }

    @Test
    void process_withTraceIdProperty_setsTraceIdOnError() throws Exception {
        String expectedJson = "{\"traceID\":\"span-abc-123\",\"errorCode\":400}";
        when(httpUtils.proxyErrorMapper(any(CapiRestError.class))).thenReturn(expectedJson);

        ErrorRoute errorRoute = new ErrorRoute(httpUtils);
        camelContext.addRoutes(errorRoute);
        camelContext.start();

        ProducerTemplate template = camelContext.createProducerTemplate();
        Exchange exchange = ExchangeBuilder.anExchange(camelContext)
                .withProperty(Constants.CAPI_EXCHANGE_INTERNAL_TRACE_ID, "span-abc-123")
                .build();

        Exchange result = template.send(Constants.CAPI_ERROR_ROUTE, exchange);

        assertEquals(expectedJson, result.getIn().getBody(String.class));
        assertEquals(400, result.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE));
        verify(httpUtils).proxyErrorMapper(any(CapiRestError.class));

        template.stop();
    }

    @Test
    void process_withAllHeadersAndProperties_setsAllFieldsOnError() throws Exception {
        String expectedJson = "{\"routeID\":\"route-1\",\"httpUri\":\"http://svc/api\",\"traceID\":\"trace-xyz\",\"errorMessage\":\"Not Found\",\"errorCode\":404}";
        when(httpUtils.proxyErrorMapper(any(CapiRestError.class))).thenReturn(expectedJson);

        ErrorRoute errorRoute = new ErrorRoute(httpUtils);
        camelContext.addRoutes(errorRoute);
        camelContext.start();

        ProducerTemplate template = camelContext.createProducerTemplate();
        Exchange exchange = ExchangeBuilder.anExchange(camelContext)
                .withHeader(Constants.CAPI_URI_IN_ERROR, "http://svc/api")
                .withHeader(Constants.ROUTE_ID_HEADER, "route-1")
                .withHeader(Constants.REASON_MESSAGE_HEADER, "Not Found")
                .withHeader(Constants.REASON_CODE_HEADER, 404)
                .withProperty(Constants.CAPI_EXCHANGE_INTERNAL_TRACE_ID, "trace-xyz")
                .build();

        Exchange result = template.send(Constants.CAPI_ERROR_ROUTE, exchange);

        assertEquals(expectedJson, result.getIn().getBody(String.class));
        assertEquals(404, result.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE));
        assertEquals("application/json", result.getIn().getHeader("Content-Type"));
        // Reason headers should be removed after processing
        assertNull(result.getIn().getHeader(Constants.REASON_MESSAGE_HEADER));
        assertNull(result.getIn().getHeader(Constants.REASON_CODE_HEADER));
        verify(httpUtils).proxyErrorMapper(any(CapiRestError.class));

        template.stop();
    }

    @Test
    void process_withNoOptionalHeadersOrProperties_setsOnlyDefaults() throws Exception {
        String expectedJson = "{\"errorMessage\":\"Unknown error\",\"errorCode\":400}";
        when(httpUtils.proxyErrorMapper(any(CapiRestError.class))).thenReturn(expectedJson);

        ErrorRoute errorRoute = new ErrorRoute(httpUtils);
        camelContext.addRoutes(errorRoute);
        camelContext.start();

        ProducerTemplate template = camelContext.createProducerTemplate();
        Exchange exchange = ExchangeBuilder.anExchange(camelContext).build();

        Exchange result = template.send(Constants.CAPI_ERROR_ROUTE, exchange);

        assertEquals(expectedJson, result.getIn().getBody(String.class));
        assertEquals(400, result.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE));
        assertEquals("application/json", result.getIn().getHeader("Content-Type"));
        verify(httpUtils).proxyErrorMapper(any(CapiRestError.class));

        template.stop();
    }

    @Test
    void process_withOnlyReasonMessage_noReasonCode_setsDefault400() throws Exception {
        // Only REASON_MESSAGE_HEADER is set, REASON_CODE_HEADER is null
        // The condition requires both to be non-null, so it should fall into the else branch
        String expectedJson = "{\"errorMessage\":\"Unknown error\",\"errorCode\":400}";
        when(httpUtils.proxyErrorMapper(any(CapiRestError.class))).thenReturn(expectedJson);

        ErrorRoute errorRoute = new ErrorRoute(httpUtils);
        camelContext.addRoutes(errorRoute);
        camelContext.start();

        ProducerTemplate template = camelContext.createProducerTemplate();
        Exchange exchange = ExchangeBuilder.anExchange(camelContext)
                .withHeader(Constants.REASON_MESSAGE_HEADER, "Partial Error")
                .build();

        Exchange result = template.send(Constants.CAPI_ERROR_ROUTE, exchange);

        assertEquals(expectedJson, result.getIn().getBody(String.class));
        assertEquals(400, result.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE));
        verify(httpUtils).proxyErrorMapper(any(CapiRestError.class));

        template.stop();
    }

    @Test
    void process_withOnlyReasonCode_noReasonMessage_setsDefault400() throws Exception {
        // Only REASON_CODE_HEADER is set, REASON_MESSAGE_HEADER is null
        // The condition requires both to be non-null, so it should fall into the else branch
        String expectedJson = "{\"errorMessage\":\"Unknown error\",\"errorCode\":400}";
        when(httpUtils.proxyErrorMapper(any(CapiRestError.class))).thenReturn(expectedJson);

        ErrorRoute errorRoute = new ErrorRoute(httpUtils);
        camelContext.addRoutes(errorRoute);
        camelContext.start();

        ProducerTemplate template = camelContext.createProducerTemplate();
        Exchange exchange = ExchangeBuilder.anExchange(camelContext)
                .withHeader(Constants.REASON_CODE_HEADER, 500)
                .build();

        Exchange result = template.send(Constants.CAPI_ERROR_ROUTE, exchange);

        assertEquals(expectedJson, result.getIn().getBody(String.class));
        assertEquals(400, result.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE));
        verify(httpUtils).proxyErrorMapper(any(CapiRestError.class));

        template.stop();
    }
}
