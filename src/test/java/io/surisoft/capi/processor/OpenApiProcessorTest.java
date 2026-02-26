package io.surisoft.capi.processor;

import io.surisoft.capi.exception.AuthorizationException;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import io.surisoft.capi.service.OpaService;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.HttpUtils;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpenApiProcessorTest {

    @Mock
    private Exchange exchange;
    @Mock
    private Message message;

    private OpenAPI openAPI;

    @BeforeEach
    void setUp() {
        when(exchange.getIn()).thenReturn(message);
        openAPI = new OpenAPI();
    }

    @Test
    void validateRequest_matchingGetPath_returnsTrue() {
        Paths paths = new Paths();
        PathItem pathItem = new PathItem();
        pathItem.setGet(new Operation());
        paths.addPathItem("/users", pathItem);
        openAPI.setPaths(paths);

        when(message.getHeader("CamelHttpPath")).thenReturn("/users");
        when(message.getHeader("CamelHttpMethod")).thenReturn("GET");

        OpenApiProcessor processor = new OpenApiProcessor(openAPI, null, null, null);
        assertTrue(processor.validateRequest(exchange));
    }

    @Test
    void validateRequest_matchingPostPath_returnsTrue() {
        Paths paths = new Paths();
        PathItem pathItem = new PathItem();
        pathItem.setPost(new Operation());
        paths.addPathItem("/users", pathItem);
        openAPI.setPaths(paths);

        when(message.getHeader("CamelHttpPath")).thenReturn("/users");
        when(message.getHeader("CamelHttpMethod")).thenReturn("POST");

        OpenApiProcessor processor = new OpenApiProcessor(openAPI, null, null, null);
        assertTrue(processor.validateRequest(exchange));
    }

    @Test
    void validateRequest_noMatchingPath_returnsFalse() {
        Paths paths = new Paths();
        PathItem pathItem = new PathItem();
        pathItem.setGet(new Operation());
        paths.addPathItem("/users", pathItem);
        openAPI.setPaths(paths);

        when(message.getHeader("CamelHttpPath")).thenReturn("/orders");
        when(message.getHeader("CamelHttpMethod")).thenReturn("GET");

        OpenApiProcessor processor = new OpenApiProcessor(openAPI, null, null, null);
        assertFalse(processor.validateRequest(exchange));
    }

    @Test
    void validateRequest_methodNotDefined_returnsFalse() {
        Paths paths = new Paths();
        PathItem pathItem = new PathItem();
        pathItem.setGet(new Operation());
        paths.addPathItem("/users", pathItem);
        openAPI.setPaths(paths);

        when(message.getHeader("CamelHttpPath")).thenReturn("/users");
        when(message.getHeader("CamelHttpMethod")).thenReturn("DELETE");

        OpenApiProcessor processor = new OpenApiProcessor(openAPI, null, null, null);
        assertFalse(processor.validateRequest(exchange));
    }

    @Test
    void validateRequest_pathParameter_matches() {
        Paths paths = new Paths();
        PathItem pathItem = new PathItem();
        pathItem.setGet(new Operation());
        paths.addPathItem("/users/{userId}", pathItem);
        openAPI.setPaths(paths);

        when(message.getHeader("CamelHttpPath")).thenReturn("/users/123");
        when(message.getHeader("CamelHttpMethod")).thenReturn("GET");

        OpenApiProcessor processor = new OpenApiProcessor(openAPI, null, null, null);
        assertTrue(processor.validateRequest(exchange));
    }

    @Test
    void validateRequest_pathParameter_wrongSegmentCount_returnsFalse() {
        Paths paths = new Paths();
        PathItem pathItem = new PathItem();
        pathItem.setGet(new Operation());
        paths.addPathItem("/users/{userId}", pathItem);
        openAPI.setPaths(paths);

        when(message.getHeader("CamelHttpPath")).thenReturn("/users/123/orders");
        when(message.getHeader("CamelHttpMethod")).thenReturn("GET");

        OpenApiProcessor processor = new OpenApiProcessor(openAPI, null, null, null);
        assertFalse(processor.validateRequest(exchange));
    }

    @Test
    void validateRequest_nestedPathParameters() {
        Paths paths = new Paths();
        PathItem pathItem = new PathItem();
        pathItem.setGet(new Operation());
        paths.addPathItem("/users/{userId}/orders/{orderId}", pathItem);
        openAPI.setPaths(paths);

        when(message.getHeader("CamelHttpPath")).thenReturn("/users/42/orders/99");
        when(message.getHeader("CamelHttpMethod")).thenReturn("GET");

        OpenApiProcessor processor = new OpenApiProcessor(openAPI, null, null, null);
        assertTrue(processor.validateRequest(exchange));
    }

    @Test
    void validateRequest_putAndPatch() {
        Paths paths = new Paths();
        PathItem pathItem = new PathItem();
        pathItem.setPut(new Operation());
        pathItem.setPatch(new Operation());
        paths.addPathItem("/users/{id}", pathItem);
        openAPI.setPaths(paths);

        when(message.getHeader("CamelHttpPath")).thenReturn("/users/1");

        OpenApiProcessor processor = new OpenApiProcessor(openAPI, null, null, null);

        when(message.getHeader("CamelHttpMethod")).thenReturn("PUT");
        assertTrue(processor.validateRequest(exchange));

        when(message.getHeader("CamelHttpMethod")).thenReturn("PATCH");
        assertTrue(processor.validateRequest(exchange));
    }

    // ---------------------------------------------------------------
    // Additional test for delete operation
    // ---------------------------------------------------------------

    @Test
    void validateRequest_matchingDeletePath_returnsTrue() {
        Paths paths = new Paths();
        PathItem pathItem = new PathItem();
        pathItem.setDelete(new Operation());
        paths.addPathItem("/users/{id}", pathItem);
        openAPI.setPaths(paths);

        when(message.getHeader("CamelHttpPath")).thenReturn("/users/42");
        when(message.getHeader("CamelHttpMethod")).thenReturn("DELETE");

        OpenApiProcessor processor = new OpenApiProcessor(openAPI, null, null, null);
        assertTrue(processor.validateRequest(exchange));
    }

    // ---------------------------------------------------------------
    // Unknown method
    // ---------------------------------------------------------------

    @Test
    void validateRequest_unknownMethod_returnsFalse() {
        Paths paths = new Paths();
        PathItem pathItem = new PathItem();
        pathItem.setGet(new Operation());
        paths.addPathItem("/users", pathItem);
        openAPI.setPaths(paths);

        when(message.getHeader("CamelHttpPath")).thenReturn("/users");
        when(message.getHeader("CamelHttpMethod")).thenReturn("HEAD");

        OpenApiProcessor processor = new OpenApiProcessor(openAPI, null, null, null);
        // HEAD is not in the switch, so operation will be null, but path matches -> the switch returns null -> false
        assertFalse(processor.validateRequest(exchange));
    }

    // ---------------------------------------------------------------
    // isPathMatch with trailing slashes
    // ---------------------------------------------------------------

    @Test
    void validateRequest_pathWithTrailingSlash_matches() {
        Paths paths = new Paths();
        PathItem pathItem = new PathItem();
        pathItem.setGet(new Operation());
        paths.addPathItem("/users/", pathItem);
        openAPI.setPaths(paths);

        when(message.getHeader("CamelHttpPath")).thenReturn("/users/");
        when(message.getHeader("CamelHttpMethod")).thenReturn("GET");

        OpenApiProcessor processor = new OpenApiProcessor(openAPI, null, null, null);
        assertTrue(processor.validateRequest(exchange));
    }

    @Test
    void validateRequest_pathWithMultipleLeadingSlashes() {
        Paths paths = new Paths();
        PathItem pathItem = new PathItem();
        pathItem.setGet(new Operation());
        paths.addPathItem("//users", pathItem);
        openAPI.setPaths(paths);

        when(message.getHeader("CamelHttpPath")).thenReturn("//users");
        when(message.getHeader("CamelHttpMethod")).thenReturn("GET");

        OpenApiProcessor processor = new OpenApiProcessor(openAPI, null, null, null);
        assertTrue(processor.validateRequest(exchange));
    }

    // ---------------------------------------------------------------
    // process method - calls sendException on invalid request
    // ---------------------------------------------------------------

    @Test
    void process_invalidRequest_setsExceptionOnExchange() throws Exception {
        Paths paths = new Paths();
        PathItem pathItem = new PathItem();
        pathItem.setGet(new Operation());
        paths.addPathItem("/users", pathItem);
        openAPI.setPaths(paths);

        when(message.getHeader("CamelHttpPath")).thenReturn("/nonexistent");
        when(message.getHeader("CamelHttpMethod")).thenReturn("GET");

        OpenApiProcessor processor = new OpenApiProcessor(openAPI, null, null, null);
        processor.process(exchange);

        verify(message).setHeader(Constants.REASON_CODE_HEADER, Constants.BAD_REQUEST_CODE);
        verify(message).setHeader(eq(Constants.REASON_MESSAGE_HEADER), eq("Call not allowed"));
        verify(exchange).setException(any(AuthorizationException.class));
    }

    @Test
    void process_validRequest_doesNotSetException() throws Exception {
        Paths paths = new Paths();
        PathItem pathItem = new PathItem();
        pathItem.setGet(new Operation());
        paths.addPathItem("/users", pathItem);
        openAPI.setPaths(paths);

        when(message.getHeader("CamelHttpPath")).thenReturn("/users");
        when(message.getHeader("CamelHttpMethod")).thenReturn("GET");

        OpenApiProcessor processor = new OpenApiProcessor(openAPI, null, null, null);
        processor.process(exchange);

        verify(exchange, never()).setException(any());
    }

    // ---------------------------------------------------------------
    // Security with null access token
    // ---------------------------------------------------------------

    @Test
    void validateRequest_withSecurity_noAccessToken_setsExceptionHeaders() throws Exception {
        Paths paths = new Paths();
        PathItem pathItem = new PathItem();
        Operation op = new Operation();
        SecurityRequirement secReq = new SecurityRequirement();
        secReq.addList("oauth2", "read");
        op.setSecurity(List.of(secReq));
        pathItem.setGet(op);
        paths.addPathItem("/secure/resource", pathItem);
        openAPI.setPaths(paths);

        HttpUtils httpUtilsMock = mock(HttpUtils.class);
        when(httpUtilsMock.processAuthorizationAccessToken(exchange)).thenReturn(null);

        when(message.getHeader("CamelHttpPath")).thenReturn("/secure/resource");
        when(message.getHeader("CamelHttpMethod")).thenReturn("GET");

        OpenApiProcessor processor = new OpenApiProcessor(openAPI, httpUtilsMock, null, null);
        boolean result = processor.validateRequest(exchange);
        assertTrue(result); // path matches so returns true

        // Should have set "No authorization provided" exception
        verify(message).setHeader(eq(Constants.REASON_MESSAGE_HEADER), eq("No authorization provided"));
        verify(message).setHeader(eq(Constants.REASON_CODE_HEADER), eq(Constants.UNAUTHORIZED_CODE));
    }

    // ---------------------------------------------------------------
    // Security with access token but null service
    // ---------------------------------------------------------------

    @Test
    void validateRequest_withSecurity_accessTokenPresent_nullService() throws Exception {
        Paths paths = new Paths();
        PathItem pathItem = new PathItem();
        Operation op = new Operation();
        SecurityRequirement secReq = new SecurityRequirement();
        secReq.addList("oauth2", "read");
        op.setSecurity(List.of(secReq));
        pathItem.setPost(op);
        paths.addPathItem("/secure/data", pathItem);
        openAPI.setPaths(paths);

        HttpUtils httpUtilsMock = mock(HttpUtils.class);
        when(httpUtilsMock.processAuthorizationAccessToken(exchange)).thenReturn("some-token");
        when(httpUtilsMock.contextToRole(any())).thenReturn("secure:data");
        when(message.getHeader("CamelHttpPath")).thenReturn("/secure/data");
        when(message.getHeader("CamelHttpMethod")).thenReturn("POST");
        when(message.getHeader("CamelServletContextPath")).thenReturn("/secure/data");

        Cache<String, Service> cache = Cache2kBuilder.of(String.class, Service.class)
                .name("openApiTestCache-" + System.nanoTime())
                .eternal(true)
                .build();

        try {
            OpenApiProcessor processor = new OpenApiProcessor(openAPI, httpUtilsMock, cache, null);
            boolean result = processor.validateRequest(exchange);
            assertTrue(result);

            // service is null so should send "Call not allowed"
            verify(message).setHeader(eq(Constants.REASON_MESSAGE_HEADER), eq("Call not allowed"));
        } finally {
            cache.close();
        }
    }

    // ---------------------------------------------------------------
    // Security with AuthorizationException
    // ---------------------------------------------------------------

    @Test
    void validateRequest_withSecurity_authorizationException() throws Exception {
        Paths paths = new Paths();
        PathItem pathItem = new PathItem();
        Operation op = new Operation();
        SecurityRequirement secReq = new SecurityRequirement();
        secReq.addList("oauth2", "read");
        op.setSecurity(List.of(secReq));
        pathItem.setGet(op);
        paths.addPathItem("/secure/resource", pathItem);
        openAPI.setPaths(paths);

        HttpUtils httpUtilsMock = mock(HttpUtils.class);
        when(httpUtilsMock.processAuthorizationAccessToken(exchange))
                .thenThrow(new AuthorizationException("Invalid token"));

        when(message.getHeader("CamelHttpPath")).thenReturn("/secure/resource");
        when(message.getHeader("CamelHttpMethod")).thenReturn("GET");

        OpenApiProcessor processor = new OpenApiProcessor(openAPI, httpUtilsMock, null, null);
        boolean result = processor.validateRequest(exchange);
        assertTrue(result); // path matches, returns true even on error

        verify(message).setHeader(eq(Constants.REASON_MESSAGE_HEADER), eq("Invalid authorization provided"));
        verify(message).setHeader(eq(Constants.REASON_CODE_HEADER), eq(Constants.BAD_REQUEST_CODE));
    }

    // ---------------------------------------------------------------
    // Operation without security
    // ---------------------------------------------------------------

    @Test
    void validateRequest_operationWithoutSecurity_doesNotCheckAuth() {
        Paths paths = new Paths();
        PathItem pathItem = new PathItem();
        Operation op = new Operation();
        // No security set
        pathItem.setGet(op);
        paths.addPathItem("/public/resource", pathItem);
        openAPI.setPaths(paths);

        when(message.getHeader("CamelHttpPath")).thenReturn("/public/resource");
        when(message.getHeader("CamelHttpMethod")).thenReturn("GET");

        OpenApiProcessor processor = new OpenApiProcessor(openAPI, null, null, null);
        assertTrue(processor.validateRequest(exchange));

        // Should not set any exception
        verify(exchange, never()).setException(any());
    }

    @Test
    void validateRequest_operationWithEmptySecurity_doesNotCheckAuth() {
        Paths paths = new Paths();
        PathItem pathItem = new PathItem();
        Operation op = new Operation();
        op.setSecurity(List.of()); // empty security
        pathItem.setGet(op);
        paths.addPathItem("/public/resource", pathItem);
        openAPI.setPaths(paths);

        when(message.getHeader("CamelHttpPath")).thenReturn("/public/resource");
        when(message.getHeader("CamelHttpMethod")).thenReturn("GET");

        OpenApiProcessor processor = new OpenApiProcessor(openAPI, null, null, null);
        assertTrue(processor.validateRequest(exchange));
    }

    // ---------------------------------------------------------------
    // Path segment mismatch (not a path parameter)
    // ---------------------------------------------------------------

    @Test
    void validateRequest_segmentMismatch_nonParameterized_returnsFalse() {
        Paths paths = new Paths();
        PathItem pathItem = new PathItem();
        pathItem.setGet(new Operation());
        paths.addPathItem("/users/active", pathItem);
        openAPI.setPaths(paths);

        when(message.getHeader("CamelHttpPath")).thenReturn("/users/inactive");
        when(message.getHeader("CamelHttpMethod")).thenReturn("GET");

        OpenApiProcessor processor = new OpenApiProcessor(openAPI, null, null, null);
        assertFalse(processor.validateRequest(exchange));
    }

    // ---------------------------------------------------------------
    // Multiple paths - second one matches
    // ---------------------------------------------------------------

    @Test
    void validateRequest_multiplePaths_secondMatches() {
        Paths paths = new Paths();

        PathItem pathItem1 = new PathItem();
        pathItem1.setGet(new Operation());
        paths.addPathItem("/orders", pathItem1);

        PathItem pathItem2 = new PathItem();
        pathItem2.setGet(new Operation());
        paths.addPathItem("/users", pathItem2);

        openAPI.setPaths(paths);

        when(message.getHeader("CamelHttpPath")).thenReturn("/users");
        when(message.getHeader("CamelHttpMethod")).thenReturn("GET");

        OpenApiProcessor processor = new OpenApiProcessor(openAPI, null, null, null);
        assertTrue(processor.validateRequest(exchange));
    }
}