package io.surisoft.capi.processor;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.Operation;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
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
}