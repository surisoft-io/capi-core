package io.surisoft.capi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.surisoft.capi.schema.McpTool;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import io.surisoft.capi.utils.Constants;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiToMcpPromoterTest {

    private final OpenApiToMcpPromoter promoter = new OpenApiToMcpPromoter();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void notOptedIn_returnsEmpty() {
        Service svc = service("svc", buildSimpleApi(), null);
        assertTrue(promoter.promote(svc).isEmpty());
    }

    @Test
    void optedIn_butNullSpec_returnsEmpty() {
        Service svc = service("svc", null, "true");
        assertTrue(promoter.promote(svc).isEmpty());
    }

    @Test
    void promotesGetWithPathParam() throws Exception {
        Service svc = service("orders", buildSimpleApi(), "true");
        List<McpTool> tools = promoter.promote(svc);

        assertEquals(2, tools.size());

        McpTool get = tools.stream().filter(t -> "getOrder".equals(t.getName())).findFirst().orElseThrow();
        assertEquals("orders", get.getServiceId());
        assertEquals("GET", get.getHttpMethod());
        assertEquals("/orders/{orderId}", get.getHttpPathTemplate());
        assertTrue(get.isOpenApiPromoted());
        assertEquals("Fetch a single order", get.getDescription());

        JsonNode schema = objectMapper.readTree(get.getInputSchema());
        assertEquals("object", schema.get("type").asText());
        assertNotNull(schema.get("properties").get("orderId"));
        assertEquals("string", schema.get("properties").get("orderId").get("type").asText());
        assertEquals("orderId", schema.get("required").get(0).asText());
    }

    @Test
    void promotesPostWithJsonBody() throws Exception {
        Service svc = service("orders", buildSimpleApi(), "true");
        List<McpTool> tools = promoter.promote(svc);

        McpTool post = tools.stream().filter(t -> "createOrder".equals(t.getName())).findFirst().orElseThrow();
        assertEquals("POST", post.getHttpMethod());
        assertEquals("/orders", post.getHttpPathTemplate());

        JsonNode schema = objectMapper.readTree(post.getInputSchema());
        JsonNode body = schema.get("properties").get("body");
        assertNotNull(body, "body property should be present");
        assertEquals("object", body.get("type").asText());
        assertEquals("integer", body.get("properties").get("quantity").get("type").asText());
        // body was marked required
        assertTrue(toList(schema.get("required")).contains("body"));
    }

    @Test
    void includeFilter_keepsOnlyMatching() {
        Service svc = service("orders", buildSimpleApi(), "true");
        svc.getServiceMeta().handleUnknown(Constants.MCP_META_PROMOTE_INCLUDE, "getOrder");
        List<McpTool> tools = promoter.promote(svc);
        assertEquals(1, tools.size());
        assertEquals("getOrder", tools.get(0).getName());
    }

    @Test
    void excludeFilter_removesMatching() {
        Service svc = service("orders", buildSimpleApi(), "true");
        svc.getServiceMeta().handleUnknown(Constants.MCP_META_PROMOTE_EXCLUDE, "createOrder");
        List<McpTool> tools = promoter.promote(svc);
        assertEquals(1, tools.size());
        assertEquals("getOrder", tools.get(0).getName());
    }

    @Test
    void prefixApplied() {
        Service svc = service("orders", buildSimpleApi(), "true");
        svc.getServiceMeta().handleUnknown(Constants.MCP_META_TOOL_PREFIX, "ord");
        List<McpTool> tools = promoter.promote(svc);
        assertTrue(tools.stream().anyMatch(t -> "ord_getOrder".equals(t.getName())));
        assertTrue(tools.stream().anyMatch(t -> "ord_createOrder".equals(t.getName())));
    }

    @Test
    void operationWithoutOperationId_synthesisesNameFromMethodAndPath() {
        OpenAPI api = new OpenAPI();
        Paths paths = new Paths();
        Operation op = new Operation();
        op.setSummary("List things");
        PathItem item = new PathItem();
        item.setGet(op);
        paths.addPathItem("/things/active", item);
        api.setPaths(paths);

        Service svc = service("things", api, "true");
        List<McpTool> tools = promoter.promote(svc);
        assertEquals(1, tools.size());
        // sanitize replaces non-alnum with _ and collapses
        assertEquals("get_things_active", tools.get(0).getName());
    }

    @Test
    void schemaSerialisationFailure_fallsBackToObject() throws Exception {
        // Operation with null schema in body content -> falls back to object
        OpenAPI api = new OpenAPI();
        Paths paths = new Paths();
        Operation op = new Operation();
        op.setOperationId("doIt");
        RequestBody body = new RequestBody();
        Content content = new Content();
        // No media type entry -> body schema null -> body property dropped
        body.setContent(content);
        op.setRequestBody(body);
        PathItem item = new PathItem();
        item.setPost(op);
        paths.addPathItem("/x", item);
        api.setPaths(paths);

        Service svc = service("x", api, "true");
        List<McpTool> tools = promoter.promote(svc);
        assertEquals(1, tools.size());

        JsonNode schema = objectMapper.readTree(tools.get(0).getInputSchema());
        assertEquals("object", schema.get("type").asText());
        // No body property, no required
        assertNull(schema.get("properties").get("body"));
    }

    @Test
    void cookieParameters_areSkipped() throws Exception {
        OpenAPI api = new OpenAPI();
        Paths paths = new Paths();
        Operation op = new Operation();
        op.setOperationId("login");
        Parameter cookie = new Parameter();
        cookie.setName("session");
        cookie.setIn("cookie");
        cookie.setSchema(new StringSchema());
        Parameter query = new Parameter();
        query.setName("redirect");
        query.setIn("query");
        query.setSchema(new StringSchema());
        op.addParametersItem(cookie);
        op.addParametersItem(query);
        PathItem item = new PathItem();
        item.setGet(op);
        paths.addPathItem("/login", item);
        api.setPaths(paths);

        Service svc = service("auth", api, "true");
        McpTool tool = promoter.promote(svc).get(0);
        JsonNode schema = objectMapper.readTree(tool.getInputSchema());
        assertNotNull(schema.get("properties").get("redirect"));
        assertNull(schema.get("properties").get("session"));
    }

    private OpenAPI buildSimpleApi() {
        OpenAPI api = new OpenAPI();
        Paths paths = new Paths();

        // GET /orders/{orderId}
        Operation getOp = new Operation();
        getOp.setOperationId("getOrder");
        getOp.setSummary("Fetch a single order");
        Parameter orderIdParam = new Parameter();
        orderIdParam.setName("orderId");
        orderIdParam.setIn("path");
        orderIdParam.setRequired(true);
        orderIdParam.setSchema(new StringSchema());
        getOp.addParametersItem(orderIdParam);

        PathItem byId = new PathItem();
        byId.setGet(getOp);
        paths.addPathItem("/orders/{orderId}", byId);

        // POST /orders   body: {quantity:int}
        Operation postOp = new Operation();
        postOp.setOperationId("createOrder");
        postOp.setSummary("Create an order");
        ObjectSchema bodySchema = new ObjectSchema();
        bodySchema.addProperty("quantity", new IntegerSchema());
        Content content = new Content();
        MediaType json = new MediaType();
        json.setSchema(bodySchema);
        content.addMediaType("application/json", json);
        RequestBody rb = new RequestBody();
        rb.setRequired(true);
        rb.setContent(content);
        postOp.setRequestBody(rb);

        PathItem coll = new PathItem();
        coll.setPost(postOp);
        paths.addPathItem("/orders", coll);

        api.setPaths(paths);
        return api;
    }

    private Service service(String id, OpenAPI api, String fromOpenApi) {
        Service svc = new Service();
        svc.setId(id);
        svc.setName(id);
        svc.setOpenAPI(api);
        ServiceMeta meta = new ServiceMeta();
        if (fromOpenApi != null) {
            meta.handleUnknown(Constants.MCP_META_FROM_OPENAPI, fromOpenApi);
        }
        svc.setServiceMeta(meta);
        return svc;
    }

    private List<String> toList(JsonNode arr) {
        if (arr == null) return List.of();
        List<String> out = new java.util.ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }

    @Test
    void openApiPromoted_flagIsFalseForNonPromotedTool() {
        McpTool plain = new McpTool();
        plain.setName("manual");
        assertFalse(plain.isOpenApiPromoted());
    }
}