package io.surisoft.capi.service;

import io.surisoft.capi.schema.McpTool;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import io.surisoft.capi.utils.Constants;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McpToolRegistryTest {

    private Cache<String, Service> serviceCache;
    private McpToolRegistry registry;

    @BeforeEach
    void setUp() {
        serviceCache = new Cache2kBuilder<String, Service>() {}
                .name("testMcpRegistry-" + System.currentTimeMillis())
                .eternal(true)
                .entryCapacity(100)
                .storeByReference(true)
                .build();
        registry = new McpToolRegistry(serviceCache);
    }

    @AfterEach
    void tearDown() {
        serviceCache.close();
    }

    @Test
    void getAllTools_emptyCache_returnsEmptyList() {
        List<McpTool> tools = registry.getAllTools();
        assertTrue(tools.isEmpty());
    }

    @Test
    void getAllTools_serviceWithoutMcpEnabled_returnsEmptyList() {
        Service service = createService("svc1");
        serviceCache.put("svc1", service);

        List<McpTool> tools = registry.getAllTools();
        assertTrue(tools.isEmpty());
    }

    @Test
    void getAllTools_serviceWithMcpEnabled_returnsTools() {
        Service service = createMcpService("svc1", "hello,goodbye");
        serviceCache.put("svc1", service);

        List<McpTool> tools = registry.getAllTools();
        assertEquals(2, tools.size());
        assertEquals("hello", tools.get(0).getName());
        assertEquals("goodbye", tools.get(1).getName());
    }

    @Test
    void getAllTools_withPrefix_prefixesToolNames() {
        Service service = createMcpService("svc1", "get,list");
        service.getServiceMeta().handleUnknown("mcp-toolPrefix", "orders");
        serviceCache.put("svc1", service);

        List<McpTool> tools = registry.getAllTools();
        assertEquals(2, tools.size());
        assertEquals("orders_get", tools.get(0).getName());
        assertEquals("orders_list", tools.get(1).getName());
    }

    @Test
    void getAllTools_withDescription_setsDescription() {
        Service service = createMcpService("svc1", "hello");
        service.getServiceMeta().handleUnknown("mcp-tools-hello-description", "Says hello");
        serviceCache.put("svc1", service);

        List<McpTool> tools = registry.getAllTools();
        assertEquals(1, tools.size());
        assertEquals("Says hello", tools.get(0).getDescription());
    }

    @Test
    void getAllTools_withInputSchema_setsSchema() {
        Service service = createMcpService("svc1", "hello");
        service.getServiceMeta().handleUnknown("mcp-tools-hello-inputSchema", "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}");
        serviceCache.put("svc1", service);

        List<McpTool> tools = registry.getAllTools();
        assertEquals(1, tools.size());
        assertTrue(tools.get(0).getInputSchema().contains("name"));
    }

    @Test
    void getAllTools_withStreaming_setsStreamingFlag() {
        Service service = createMcpService("svc1", "stream,normal");
        service.getServiceMeta().handleUnknown("mcp-streaming", "stream");
        serviceCache.put("svc1", service);

        List<McpTool> tools = registry.getAllTools();
        assertEquals(2, tools.size());
        McpTool streamTool = tools.stream().filter(t -> t.getName().equals("stream")).findFirst().orElse(null);
        McpTool normalTool = tools.stream().filter(t -> t.getName().equals("normal")).findFirst().orElse(null);
        assertNotNull(streamTool);
        assertNotNull(normalTool);
        assertTrue(streamTool.isStreaming());
        assertFalse(normalTool.isStreaming());
    }

    @Test
    void getAllTools_withCategoryAndTimeout_setsBoth() {
        Service service = createMcpService("svc1", "hello");
        service.getServiceMeta().handleUnknown("mcp-category", "utils");
        service.getServiceMeta().handleUnknown("mcp-timeout", "5000");
        serviceCache.put("svc1", service);

        List<McpTool> tools = registry.getAllTools();
        assertEquals(1, tools.size());
        assertEquals("utils", tools.get(0).getCategory());
        assertEquals(5000, tools.get(0).getTimeout());
    }

    @Test
    void getAllTools_withPerToolTimeout_overridesGlobal() {
        Service service = createMcpService("svc1", "hello");
        service.getServiceMeta().handleUnknown("mcp-timeout", "5000");
        service.getServiceMeta().handleUnknown("mcp-tools-hello-timeout", "10000");
        serviceCache.put("svc1", service);

        List<McpTool> tools = registry.getAllTools();
        assertEquals(1, tools.size());
        assertEquals(10000, tools.get(0).getTimeout());
    }

    @Test
    void resolveToolByName_found() {
        Service service = createMcpService("svc1", "hello");
        serviceCache.put("svc1", service);

        McpToolRegistry.McpToolResolution resolution = registry.resolveToolByName("hello");
        assertNotNull(resolution);
        assertEquals("hello", resolution.getTool().getName());
        assertEquals("svc1", resolution.getService().getId());
    }

    @Test
    void resolveToolByName_withPrefix() {
        Service service = createMcpService("svc1", "get");
        service.getServiceMeta().handleUnknown("mcp-toolPrefix", "orders");
        serviceCache.put("svc1", service);

        McpToolRegistry.McpToolResolution resolution = registry.resolveToolByName("orders_get");
        assertNotNull(resolution);
        assertEquals("orders_get", resolution.getTool().getName());
    }

    @Test
    void resolveToolByName_notFound() {
        Service service = createMcpService("svc1", "hello");
        serviceCache.put("svc1", service);

        McpToolRegistry.McpToolResolution resolution = registry.resolveToolByName("nonexistent");
        assertNull(resolution);
    }

    @Test
    void getAllTools_serviceWithNullServiceMeta_skipped() {
        Service service = new Service();
        service.setId("svc1");
        serviceCache.put("svc1", service);

        List<McpTool> tools = registry.getAllTools();
        assertTrue(tools.isEmpty());
    }

    @Test
    void getAllTools_emptyToolNames_returnsEmpty() {
        Service service = createService("svc1");
        service.getServiceMeta().handleUnknown("mcp-enabled", "true");
        service.getServiceMeta().handleUnknown("mcp-tools", "");
        serviceCache.put("svc1", service);

        List<McpTool> tools = registry.getAllTools();
        assertTrue(tools.isEmpty());
    }

    @Test
    void getAllTools_setsServiceId() {
        Service service = createMcpService("my-service-123", "hello");
        serviceCache.put("my-service-123", service);

        List<McpTool> tools = registry.getAllTools();
        assertEquals(1, tools.size());
        assertEquals("my-service-123", tools.get(0).getServiceId());
    }

    @Test
    void getAllTools_mcpServerService_setsMcpServerFlag() {
        Service service = createMcpService("svc1", "hello");
        service.getServiceMeta().handleUnknown("mcp-type", "server");
        serviceCache.put("svc1", service);

        List<McpTool> tools = registry.getAllTools();
        assertEquals(1, tools.size());
        assertTrue(tools.get(0).isMcpServer());
    }

    @Test
    void getAllTools_restService_mcpServerFlagIsFalse() {
        Service service = createMcpService("svc1", "hello");
        serviceCache.put("svc1", service);

        List<McpTool> tools = registry.getAllTools();
        assertEquals(1, tools.size());
        assertFalse(tools.get(0).isMcpServer());
    }

    @Test
    void getAllTools_explicitRestType_mcpServerFlagIsFalse() {
        Service service = createMcpService("svc1", "hello");
        service.getServiceMeta().handleUnknown("mcp-type", "rest");
        serviceCache.put("svc1", service);

        List<McpTool> tools = registry.getAllTools();
        assertEquals(1, tools.size());
        assertFalse(tools.get(0).isMcpServer());
    }

    @Test
    void getAllTools_openApiOnlyService_surfacesPromotedTools() {
        Service service = createOpenApiService("orders");
        serviceCache.put("orders", service);

        List<McpTool> tools = registry.getAllTools();
        assertEquals(1, tools.size());
        assertEquals("getThing", tools.get(0).getName());
        assertTrue(tools.get(0).isOpenApiPromoted());
        assertEquals("GET", tools.get(0).getHttpMethod());
        assertEquals("/things/{id}", tools.get(0).getHttpPathTemplate());
    }

    @Test
    void resolveToolByName_findsPromotedTool() {
        Service service = createOpenApiService("orders");
        serviceCache.put("orders", service);

        McpToolRegistry.McpToolResolution resolution = registry.resolveToolByName("getThing");
        assertNotNull(resolution);
        assertEquals("orders", resolution.getService().getId());
        assertTrue(resolution.getTool().isOpenApiPromoted());
    }

    @Test
    void hybridService_tagDefinedToolWinsOnNameCollision() {
        Service service = createOpenApiService("orders");
        // Also enable tag-defined tools, with a tool whose name collides with the OpenAPI one
        service.getServiceMeta().handleUnknown(Constants.MCP_META_ENABLED, "true");
        service.getServiceMeta().handleUnknown(Constants.MCP_META_TOOLS, "getThing");
        service.getServiceMeta().handleUnknown(Constants.MCP_META_PREFIX + "tools-getThing-description", "Tag-defined override");
        serviceCache.put("orders", service);

        List<McpTool> tools = registry.getAllTools();
        assertEquals(1, tools.size());
        // Tag-defined wins: not OpenAPI-promoted, has tag-defined description
        assertFalse(tools.get(0).isOpenApiPromoted());
        assertEquals("Tag-defined override", tools.get(0).getDescription());
    }

    @Test
    void hybridService_disjointToolsAreMerged() {
        Service service = createOpenApiService("orders");
        // Tag-defined tool with a different name than the promoted one
        service.getServiceMeta().handleUnknown(Constants.MCP_META_ENABLED, "true");
        service.getServiceMeta().handleUnknown(Constants.MCP_META_TOOLS, "manual");
        serviceCache.put("orders", service);

        List<McpTool> tools = registry.getAllTools();
        assertEquals(2, tools.size());
        assertTrue(tools.stream().anyMatch(t -> "getThing".equals(t.getName()) && t.isOpenApiPromoted()));
        assertTrue(tools.stream().anyMatch(t -> "manual".equals(t.getName()) && !t.isOpenApiPromoted()));
    }

    @Test
    void getAllTools_openApiOptedInButNoSpec_returnsEmpty() {
        Service service = createService("orders");
        service.getServiceMeta().handleUnknown(Constants.MCP_META_FROM_OPENAPI, "true");
        // No openAPI set
        serviceCache.put("orders", service);

        List<McpTool> tools = registry.getAllTools();
        assertTrue(tools.isEmpty());
    }

    @Test
    void getAllTools_neitherFlag_returnsEmpty() {
        // Service with an OpenAPI spec but neither mcp-enabled nor mcp-from-openapi
        Service service = createService("orders");
        service.setOpenAPI(buildSimpleOpenApi());
        serviceCache.put("orders", service);

        List<McpTool> tools = registry.getAllTools();
        assertTrue(tools.isEmpty());
    }

    private Service createService(String id) {
        Service service = new Service();
        service.setId(id);
        service.setName(id);
        ServiceMeta meta = new ServiceMeta();
        service.setServiceMeta(meta);
        return service;
    }

    private Service createMcpService(String id, String tools) {
        Service service = createService(id);
        service.getServiceMeta().handleUnknown("mcp-enabled", "true");
        service.getServiceMeta().handleUnknown("mcp-tools", tools);
        return service;
    }

    private Service createOpenApiService(String id) {
        Service service = createService(id);
        service.getServiceMeta().handleUnknown(Constants.MCP_META_FROM_OPENAPI, "true");
        service.setOpenAPI(buildSimpleOpenApi());
        return service;
    }

    private OpenAPI buildSimpleOpenApi() {
        OpenAPI api = new OpenAPI();
        Paths paths = new Paths();
        Operation op = new Operation();
        op.setOperationId("getThing");
        op.setSummary("Get a thing");
        Parameter idParam = new Parameter();
        idParam.setName("id");
        idParam.setIn("path");
        idParam.setRequired(true);
        idParam.setSchema(new StringSchema());
        op.addParametersItem(idParam);
        PathItem item = new PathItem();
        item.setGet(op);
        paths.addPathItem("/things/{id}", item);
        api.setPaths(paths);
        return api;
    }

    // ==========================================================================
    // Signed-manifest verification tests
    // ==========================================================================

    @org.junit.jupiter.api.Nested
    class SigningTests {

        private java.security.KeyPair kp;
        private McpTrustStore trustStore;
        private McpToolRegistry signingRegistry;

        @org.junit.jupiter.api.BeforeEach
        void setUpSigning() throws Exception {
            kp = java.security.KeyPairGenerator.getInstance("RSA").generateKeyPair();
            trustStore = org.mockito.Mockito.mock(McpTrustStore.class);
            org.mockito.Mockito.when(trustStore.get("ops-2026")).thenReturn(kp.getPublic());
            McpManifestVerifier verifier = new McpManifestVerifier(trustStore);
            signingRegistry = new McpToolRegistry(serviceCache);
            signingRegistry.setManifestVerifier(verifier);
        }

        @org.junit.jupiter.api.Test
        void offMode_skipsVerificationEntirelyEvenWithBadSig() {
            signingRegistry.setSigningMode(McpToolRegistry.SIGNING_OFF);
            Service svc = signedService("svc-a", "doIt", "Does it", "{\"type\":\"object\"}", "1",
                    "AAAA-bogus", "ops-2026", false);
            serviceCache.put("svc-a", svc);
            assertEquals(1, signingRegistry.getAllTools().size());
        }

        @org.junit.jupiter.api.Test
        void warnMode_validSig_admits() throws Exception {
            signingRegistry.setSigningMode(McpToolRegistry.SIGNING_WARN);
            String desc = "Does it";
            String schema = "{\"type\":\"object\"}";
            String sig = sign("svc-a", "doIt", desc, schema, "1");
            Service svc = signedService("svc-a", "doIt", desc, schema, "1", sig, "ops-2026", false);
            serviceCache.put("svc-a", svc);
            assertEquals(1, signingRegistry.getAllTools().size());
        }

        @org.junit.jupiter.api.Test
        void warnMode_tamperedDescription_stillAdmits() throws Exception {
            signingRegistry.setSigningMode(McpToolRegistry.SIGNING_WARN);
            String origDesc = "Does it";
            String schema = "{\"type\":\"object\"}";
            String sig = sign("svc-a", "doIt", origDesc, schema, "1");
            // Operator signed origDesc; the live tag carries a tampered one.
            Service svc = signedService("svc-a", "doIt", "TAMPERED", schema, "1", sig, "ops-2026", false);
            serviceCache.put("svc-a", svc);
            assertEquals(1, signingRegistry.getAllTools().size(),
                    "warn mode should log but still admit tampered tools");
        }

        @org.junit.jupiter.api.Test
        void enforceMode_tamperedDescription_drops() throws Exception {
            signingRegistry.setSigningMode(McpToolRegistry.SIGNING_ENFORCE);
            String origDesc = "Does it";
            String schema = "{\"type\":\"object\"}";
            String sig = sign("svc-a", "doIt", origDesc, schema, "1");
            Service svc = signedService("svc-a", "doIt", "TAMPERED", schema, "1", sig, "ops-2026", false);
            serviceCache.put("svc-a", svc);
            assertEquals(0, signingRegistry.getAllTools().size(),
                    "enforce mode should drop tampered tools");
        }

        @org.junit.jupiter.api.Test
        void enforceMode_validSig_admits() throws Exception {
            signingRegistry.setSigningMode(McpToolRegistry.SIGNING_ENFORCE);
            String desc = "Does it";
            String schema = "{\"type\":\"object\"}";
            String sig = sign("svc-a", "doIt", desc, schema, "1");
            Service svc = signedService("svc-a", "doIt", desc, schema, "1", sig, "ops-2026", false);
            serviceCache.put("svc-a", svc);
            assertEquals(1, signingRegistry.getAllTools().size());
        }

        @org.junit.jupiter.api.Test
        void warnMode_unsignedTool_admits() {
            signingRegistry.setSigningMode(McpToolRegistry.SIGNING_WARN);
            // No signature/keyid tags; mcp-required-signed=false
            Service svc = signedService("svc-a", "doIt", "Hello", "{\"type\":\"object\"}", "1",
                    null, null, false);
            serviceCache.put("svc-a", svc);
            assertEquals(1, signingRegistry.getAllTools().size());
        }

        @org.junit.jupiter.api.Test
        void enforceMode_unsignedTool_requiredSigned_drops() {
            signingRegistry.setSigningMode(McpToolRegistry.SIGNING_ENFORCE);
            Service svc = signedService("svc-a", "doIt", "Hello", "{\"type\":\"object\"}", "1",
                    null, null, true);
            serviceCache.put("svc-a", svc);
            assertEquals(0, signingRegistry.getAllTools().size());
        }

        @org.junit.jupiter.api.Test
        void enforceMode_unsignedTool_notRequired_admits() {
            // mcp-required-signed=false → unsigned tools pass through even in enforce.
            // This lets operators roll out signing incrementally.
            signingRegistry.setSigningMode(McpToolRegistry.SIGNING_ENFORCE);
            Service svc = signedService("svc-a", "doIt", "Hello", "{\"type\":\"object\"}", "1",
                    null, null, false);
            serviceCache.put("svc-a", svc);
            assertEquals(1, signingRegistry.getAllTools().size());
        }

        @org.junit.jupiter.api.Test
        void enforceMode_versionDrift_drops() throws Exception {
            // Operator signed version "1"; ServiceMeta.version is now "2" in Consul.
            signingRegistry.setSigningMode(McpToolRegistry.SIGNING_ENFORCE);
            String desc = "Does it";
            String schema = "{\"type\":\"object\"}";
            String sigForV1 = sign("svc-a", "doIt", desc, schema, "1");
            Service svc = signedService("svc-a", "doIt", desc, schema, "2", sigForV1, "ops-2026", false);
            serviceCache.put("svc-a", svc);
            assertEquals(0, signingRegistry.getAllTools().size());
        }

        @org.junit.jupiter.api.Test
        void enforceMode_unknownKeyId_drops() throws Exception {
            signingRegistry.setSigningMode(McpToolRegistry.SIGNING_ENFORCE);
            String desc = "Does it";
            String schema = "{\"type\":\"object\"}";
            String sig = sign("svc-a", "doIt", desc, schema, "1");
            Service svc = signedService("svc-a", "doIt", desc, schema, "1", sig, "no-such-key", false);
            serviceCache.put("svc-a", svc);
            assertEquals(0, signingRegistry.getAllTools().size());
        }

        // --- helpers ---

        private Service signedService(String id, String toolName, String description,
                                      String schema, String version,
                                      String signature, String keyId, boolean requireSigned) {
            Service service = createService(id);
            ServiceMeta meta = service.getServiceMeta();
            meta.handleUnknown(Constants.MCP_META_ENABLED, "true");
            meta.handleUnknown(Constants.MCP_META_TOOLS, toolName);
            meta.handleUnknown(Constants.MCP_META_PREFIX + "tools-" + toolName + "-description", description);
            meta.handleUnknown(Constants.MCP_META_PREFIX + "tools-" + toolName + "-inputSchema", schema);
            if (signature != null) {
                meta.handleUnknown(Constants.MCP_META_PREFIX + "tools-" + toolName
                        + Constants.MCP_META_TOOLS_SIGNATURE_SUFFIX, signature);
            }
            if (keyId != null) {
                meta.handleUnknown(Constants.MCP_META_PREFIX + "tools-" + toolName
                        + Constants.MCP_META_TOOLS_KEYID_SUFFIX, keyId);
            }
            if (requireSigned) {
                meta.handleUnknown(Constants.MCP_META_REQUIRED_SIGNED, "true");
            }
            meta.setVersion(version);
            return service;
        }

        private String sign(String svc, String name, String desc, String schema, String version) throws Exception {
            byte[] manifest = McpManifest.canonicalize(svc, name, desc, schema, version);
            java.security.Signature s = java.security.Signature.getInstance("SHA256withRSA");
            s.initSign(kp.getPrivate());
            s.update(manifest);
            return java.util.Base64.getEncoder().encodeToString(s.sign());
        }
    }
}
