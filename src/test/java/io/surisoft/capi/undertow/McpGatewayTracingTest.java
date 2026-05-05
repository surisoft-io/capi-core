package io.surisoft.capi.undertow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.schema.McpSession;
import io.surisoft.capi.schema.Mapping;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import io.surisoft.capi.service.LocalMcpSessionStore;
import io.surisoft.capi.service.McpBackendLoadBalancer;
import io.surisoft.capi.service.McpSessionStore;
import io.surisoft.capi.service.McpToolRegistry;
import io.surisoft.capi.tracer.McpTracer;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.HttpUtils;
import io.undertow.Undertow;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpGatewayTracingTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private McpGateway mcpGateway;
    private Cache<String, Service> serviceCache;
    private Cache<String, McpSession> sessionCache;
    private CapturingSpanProcessor processor;
    private SdkTracerProvider tracerProvider;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        port = findFreePort();
        serviceCache = new Cache2kBuilder<String, Service>() {}
                .name("traceTest-svc-" + System.nanoTime())
                .eternal(true).entryCapacity(100).storeByReference(true).build();
        sessionCache = new Cache2kBuilder<String, McpSession>() {}
                .name("traceTest-sess-" + System.nanoTime())
                .expireAfterWrite(1800000, TimeUnit.MILLISECONDS)
                .entryCapacity(100).storeByReference(true).build();

        processor = new CapturingSpanProcessor();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(processor)
                .build();
        Tracer tracer = tracerProvider.get("capi-mcp-test");

        McpToolRegistry registry = new McpToolRegistry(serviceCache);
        McpSessionStore sessionStore = new LocalMcpSessionStore(sessionCache);

        CAPIConfiguration config = new CAPIConfiguration();
        CAPIConfiguration.Mcp mcp = new CAPIConfiguration.Mcp();
        mcp.setEnabled(true);
        mcp.setPort(port);
        mcp.setSessionTtl(1800000);
        mcp.setToolCallTimeout(30000);
        config.setMcp(mcp);
        config.setVersion("1.0.0-test");

        CAPIConfiguration.Oauth2 oauth2 = new CAPIConfiguration.Oauth2();
        oauth2.setEnabled(false);
        config.setOauth2(oauth2);

        HttpUtils httpUtils = new HttpUtils(null, null);
        McpBackendLoadBalancer lb = new McpBackendLoadBalancer(30000);

        mcpGateway = new McpGateway(port, null, registry, httpUtils, null,
                HttpClient.newHttpClient(), sessionStore, config, lb);
        mcpGateway.setMcpTracer(new McpTracer(tracer, "capi-test-instance", true));
        mcpGateway.start();
    }

    @AfterEach
    void tearDown() {
        if (mcpGateway != null) mcpGateway.stop();
        if (tracerProvider != null) tracerProvider.close();
        serviceCache.close();
        sessionCache.close();
    }

    @Test
    void initialize_emitsSpanWithGenAiAttributes() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "initialize", "id", 1));
        HttpResponse<String> resp = sendPost("/mcp", body);
        assertEquals(200, resp.statusCode());

        SpanData span = waitForSpan("initialize");
        assertEquals(Constants.GEN_AI_SYSTEM_MCP, span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(Constants.GEN_AI_SYSTEM)));
        assertEquals(Constants.GEN_AI_OPERATION_INITIALIZE, span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(Constants.GEN_AI_OPERATION_NAME)));
        assertEquals(Constants.CAPI_OUTCOME_SUCCESS, span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(Constants.CAPI_OUTCOME_ATTR)));
        assertNotNull(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(Constants.MCP_SESSION_ID_ATTR)));
        assertEquals(Constants.MCP_PROTOCOL_VERSION, span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(Constants.MCP_PROTOCOL_VERSION_ATTR)));
    }

    @Test
    void toolsList_emitsSpanWithCount() throws Exception {
        String sessionId = initializeSession();

        Service svc = new Service();
        svc.setId("svc-a"); svc.setName("svc-a");
        ServiceMeta meta = new ServiceMeta();
        meta.handleUnknown(Constants.MCP_META_ENABLED, "true");
        meta.handleUnknown(Constants.MCP_META_TOOLS, "tool-a");
        meta.handleUnknown(Constants.MCP_META_PREFIX + "tools-tool-a-description", "A tool");
        svc.setServiceMeta(meta);
        serviceCache.put("svc-a", svc);

        String body = objectMapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "tools/list", "id", 2));
        HttpResponse<String> resp = sendPostWithSession("/mcp", body, sessionId);
        assertEquals(200, resp.statusCode());

        SpanData span = waitForSpan("list_tools");
        assertEquals(Constants.GEN_AI_OPERATION_LIST_TOOLS, span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(Constants.GEN_AI_OPERATION_NAME)));
        assertEquals(Constants.CAPI_OUTCOME_SUCCESS, span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(Constants.CAPI_OUTCOME_ATTR)));
        assertEquals(1L, span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.longKey("mcp.tools.count")));
    }

    @Test
    void toolsCall_unknownTool_emitsSpanWithToolNotFoundOutcome() throws Exception {
        String sessionId = initializeSession();
        String body = objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "method", "tools/call", "id", 3,
                "params", Map.of("name", "ghost", "arguments", Map.of())
        ));
        HttpResponse<String> resp = sendPostWithSession("/mcp", body, sessionId);
        assertEquals(200, resp.statusCode());

        SpanData span = waitForSpan("execute_tool ghost");
        assertEquals(Constants.GEN_AI_OPERATION_EXECUTE_TOOL, span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(Constants.GEN_AI_OPERATION_NAME)));
        assertEquals("ghost", span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(Constants.GEN_AI_TOOL_NAME)));
        assertEquals(Constants.CAPI_OUTCOME_TOOL_NOT_FOUND, span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(Constants.CAPI_OUTCOME_ATTR)));
    }

    @Test
    void toolsCall_success_emitsParentAndUpstreamSpans() throws Exception {
        int backendPort = findFreePort();
        Undertow backend = Undertow.builder()
                .addHttpListener(backendPort, "0.0.0.0")
                .setHandler(ex -> {
                    ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    ex.setStatusCode(StatusCodes.OK);
                    ex.getResponseSender().send("{\"ok\":true}");
                }).build();
        backend.start();
        try {
            String sessionId = initializeSession();
            Service svc = mcpServiceWithBackend("be-svc", "do-thing", "localhost", backendPort);
            serviceCache.put("be-svc", svc);

            String body = objectMapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0", "method", "tools/call", "id", 7,
                    "params", Map.of("name", "do-thing", "arguments", Map.of())
            ));
            HttpResponse<String> resp = sendPostWithSession("/mcp", body, sessionId);
            assertEquals(200, resp.statusCode());

            SpanData parent = waitForSpan("execute_tool do-thing");
            assertEquals(Constants.CAPI_OUTCOME_SUCCESS, parent.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(Constants.CAPI_OUTCOME_ATTR)));
            assertEquals(Constants.GEN_AI_TOOL_TYPE_FUNCTION, parent.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(Constants.GEN_AI_TOOL_TYPE)));

            SpanData upstream = waitForSpan("mcp.upstream do-thing");
            assertEquals("CLIENT", upstream.getKind().name());
            assertEquals(parent.getSpanId(), upstream.getParentSpanId());
            assertEquals(1L, upstream.getAttributes().get(io.opentelemetry.api.common.AttributeKey.longKey(Constants.MCP_ATTEMPT_ATTR)));
            assertEquals(200L, upstream.getAttributes().get(io.opentelemetry.api.common.AttributeKey.longKey("http.response.status_code")));
        } finally {
            backend.stop();
        }
    }

    @Test
    void disabledTracer_emitsNoSpans() throws Exception {
        // Re-create gateway without tracer
        mcpGateway.stop();
        McpToolRegistry registry = new McpToolRegistry(serviceCache);
        McpSessionStore sessionStore = new LocalMcpSessionStore(sessionCache);
        CAPIConfiguration config = new CAPIConfiguration();
        CAPIConfiguration.Mcp mcp = new CAPIConfiguration.Mcp();
        mcp.setEnabled(true); mcp.setPort(port); mcp.setSessionTtl(1800000); mcp.setToolCallTimeout(30000);
        config.setMcp(mcp); config.setVersion("1.0.0-test");
        CAPIConfiguration.Oauth2 oauth2 = new CAPIConfiguration.Oauth2();
        oauth2.setEnabled(false); config.setOauth2(oauth2);
        mcpGateway = new McpGateway(port, null, registry, new HttpUtils(null, null),
                null, HttpClient.newHttpClient(), sessionStore, config,
                new McpBackendLoadBalancer(30000));
        mcpGateway.start();

        processor.spans.clear();

        String body = objectMapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "initialize", "id", 1));
        sendPost("/mcp", body);

        // Brief wait — no spans should appear
        Thread.sleep(50);
        assertTrue(processor.spans.isEmpty(), "Expected no spans when tracer is unset, got: " + processor.spans);
    }

    private String initializeSession() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "initialize", "id", 1));
        HttpResponse<String> resp = sendPost("/mcp", body);
        return resp.headers().firstValue("Mcp-Session-Id").orElseThrow();
    }

    private SpanData waitForSpan(String name) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            for (SpanData s : processor.spans) {
                if (name.equals(s.getName())) return s;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Span '" + name + "' not emitted. Got: "
                + processor.spans.stream().map(SpanData::getName).toList());
    }

    private Service mcpServiceWithBackend(String id, String tool, String host, int backendPort) {
        Service svc = new Service();
        svc.setId(id); svc.setName(id);
        ServiceMeta meta = new ServiceMeta();
        meta.handleUnknown(Constants.MCP_META_ENABLED, "true");
        meta.handleUnknown(Constants.MCP_META_TOOLS, tool);
        meta.handleUnknown(Constants.MCP_META_PREFIX + "tools-" + tool + "-description", "desc");
        svc.setServiceMeta(meta);
        Mapping m = new Mapping();
        m.setHostname(host); m.setPort(backendPort); m.setRootContext("/");
        svc.setMappingList(Set.of(m));
        return svc;
    }

    private HttpResponse<String> sendPost(String path, String body) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendPostWithSession(String path, String body, String sessionId) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Mcp-Session-Id", sessionId)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static class CapturingSpanProcessor implements SpanProcessor {
        final List<SpanData> spans = new ArrayList<>();

        @Override
        public void onStart(Context parentContext, ReadWriteSpan span) { /* no-op */ }

        @Override
        public boolean isStartRequired() { return false; }

        @Override
        public synchronized void onEnd(ReadableSpan span) {
            spans.add(span.toSpanData());
        }

        @Override
        public boolean isEndRequired() { return true; }

        @Override
        public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }

        @Override
        public CompletableResultCode forceFlush() { return CompletableResultCode.ofSuccess(); }
    }
}