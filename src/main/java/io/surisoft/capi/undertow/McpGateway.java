package io.surisoft.capi.undertow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.exception.AuthorizationException;
import io.surisoft.capi.schema.*;
import io.surisoft.capi.service.McpBackendLoadBalancer;
import io.surisoft.capi.service.McpServerClient;
import io.surisoft.capi.service.McpSessionStore;
import io.surisoft.capi.service.McpToolRegistry;
import io.surisoft.capi.service.OpaService;
import io.surisoft.capi.service.OpenApiCallBuilder;
import io.surisoft.capi.tracer.McpTracer;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.HttpUtils;
import io.undertow.Undertow;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.PathHandler;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.StatusCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

public class McpGateway implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpGateway.class);
    private static final HttpString MCP_SESSION_ID_HEADER = new HttpString(Constants.MCP_SESSION_HEADER);
    private static final String APPLICATION_JSON = "application/json";
    private static final String TEXT_EVENT_STREAM = "text/event-stream";
    private static final String ACCEPT_HEADER = "Accept";

    private final int port;
    private final SSLContext sslContext;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final McpToolRegistry toolRegistry;
    private final HttpUtils httpUtils;
    private final OpaService opaService;
    private final HttpClient httpClient;
    private final McpSessionStore sessionStore;
    private final CAPIConfiguration configuration;
    private final McpBackendLoadBalancer loadBalancer;
    private final McpServerClient mcpServerClient;
    private final OpenApiCallBuilder openApiCallBuilder = new OpenApiCallBuilder();
    private McpTracer mcpTracer;
    private Undertow server;

    public McpGateway(int port,
                      SSLContext sslContext,
                      McpToolRegistry toolRegistry,
                      HttpUtils httpUtils,
                      OpaService opaService,
                      HttpClient httpClient,
                      McpSessionStore sessionStore,
                      CAPIConfiguration configuration,
                      McpBackendLoadBalancer loadBalancer) {
        this(port, sslContext, toolRegistry, httpUtils, opaService, httpClient, sessionStore, configuration, loadBalancer, null);
    }

    public McpGateway(int port,
                      SSLContext sslContext,
                      McpToolRegistry toolRegistry,
                      HttpUtils httpUtils,
                      OpaService opaService,
                      HttpClient httpClient,
                      McpSessionStore sessionStore,
                      CAPIConfiguration configuration,
                      McpBackendLoadBalancer loadBalancer,
                      McpServerClient mcpServerClient) {
        this.port = port;
        this.sslContext = sslContext;
        this.toolRegistry = toolRegistry;
        this.httpUtils = httpUtils;
        this.opaService = opaService;
        this.httpClient = httpClient;
        this.sessionStore = sessionStore;
        this.configuration = configuration;
        this.loadBalancer = loadBalancer;
        this.mcpServerClient = mcpServerClient;
    }

    public void setMcpTracer(McpTracer mcpTracer) {
        this.mcpTracer = mcpTracer;
    }

    public void start() {
        PathHandler pathHandler = new PathHandler()
                .addExactPath("/mcp", this::handleMcp)
                .addExactPath("/mcp/health", this::handleHealth);

        Undertow.Builder builder = Undertow.builder();
        if (sslContext != null) {
            builder.addHttpsListener(port, "0.0.0.0", sslContext);
        } else {
            builder.addHttpListener(port, "0.0.0.0");
        }
        server = builder.setHandler(pathHandler).build();
        server.start();
        log.info("MCP Gateway started on port {}", port);
    }

    public void stop() {
        if (server != null) {
            log.info("Stopping MCP Gateway on port {}", port);
            server.stop();
        }
    }

    @Override
    public void close() {
        stop();
    }

    private void handleHealth(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, APPLICATION_JSON);
        exchange.setStatusCode(StatusCodes.OK);
        exchange.getResponseSender().send("{\"status\":\"UP\"}");
    }

    private void handleMcp(HttpServerExchange exchange) {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this::handleMcp);
            return;
        }
        exchange.startBlocking();

        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, APPLICATION_JSON);

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod().toString())) {
            exchange.setStatusCode(StatusCodes.METHOD_NOT_ALLOWED);
            exchange.getResponseSender().send("{\"error\":\"Method not allowed\"}");
            return;
        }

        try {
            String body = readBody(exchange);
            JsonRpcRequest request;
            try {
                request = objectMapper.readValue(body, JsonRpcRequest.class);
            } catch (JsonProcessingException e) {
                sendJsonRpc(exchange, StatusCodes.OK,
                        JsonRpcResponse.error(null, Constants.JSONRPC_PARSE_ERROR, "Parse error"));
                return;
            }

            if (!"2.0".equals(request.getJsonrpc())) {
                sendJsonRpc(exchange, StatusCodes.OK,
                        JsonRpcResponse.error(request.getId(), Constants.JSONRPC_INVALID_REQUEST, "Invalid JSON-RPC version"));
                return;
            }

            String method = request.getMethod();
            if (method == null) {
                sendJsonRpc(exchange, StatusCodes.OK,
                        JsonRpcResponse.error(request.getId(), Constants.JSONRPC_INVALID_REQUEST, "Missing method"));
                return;
            }

            switch (method) {
                case "initialize":
                    handleInitialize(exchange, request);
                    break;
                case "tools/list":
                    handleToolsList(exchange, request);
                    break;
                case "tools/call":
                    handleToolsCall(exchange, request);
                    break;
                case "ping":
                    handlePing(exchange, request);
                    break;
                default:
                    sendJsonRpc(exchange, StatusCodes.OK,
                            JsonRpcResponse.error(request.getId(), Constants.JSONRPC_METHOD_NOT_FOUND, "Method not found: " + method));
            }
        } catch (Exception e) {
            log.error("Error handling MCP request", e);
            sendJsonRpc(exchange, StatusCodes.INTERNAL_SERVER_ERROR,
                    JsonRpcResponse.error(null, Constants.JSONRPC_INTERNAL_ERROR, "Internal error"));
        }
    }

    private void handleInitialize(HttpServerExchange exchange, JsonRpcRequest request) {
        Span span = (mcpTracer != null)
                ? mcpTracer.startServerSpan(exchange, Constants.GEN_AI_OPERATION_INITIALIZE)
                : null;
        Scope scope = (span != null) ? span.makeCurrent() : null;
        try {
            if (mcpTracer != null) mcpTracer.setToolCallId(span, request.getId());

            String accessToken = null;
            if (configuration.getOauth2() != null && configuration.getOauth2().isEnabled()) {
                try {
                    accessToken = httpUtils.processAuthorizationAccessToken(exchange);
                } catch (AuthorizationException e) {
                    if (mcpTracer != null) {
                        mcpTracer.setOutcome(span, Constants.CAPI_OUTCOME_UNAUTHORIZED);
                        mcpTracer.setHttpStatus(span, StatusCodes.UNAUTHORIZED);
                    }
                    exchange.setStatusCode(StatusCodes.UNAUTHORIZED);
                    sendJsonRpc(exchange, StatusCodes.UNAUTHORIZED,
                            JsonRpcResponse.error(request.getId(), -32000, "Invalid authorization"));
                    return;
                }
                if (accessToken == null) {
                    if (mcpTracer != null) {
                        mcpTracer.setOutcome(span, Constants.CAPI_OUTCOME_UNAUTHORIZED);
                        mcpTracer.setHttpStatus(span, StatusCodes.UNAUTHORIZED);
                    }
                    sendJsonRpc(exchange, StatusCodes.UNAUTHORIZED,
                            JsonRpcResponse.error(request.getId(), -32000, "Authorization required"));
                    return;
                }
            }

            String sessionId = UUID.randomUUID().toString();
            String clientIdentity = accessToken != null ? accessToken.substring(0, Math.min(accessToken.length(), 16)) + "..." : "anonymous";
            long ttl = configuration.getMcp().getSessionTtl();

            McpSession session = new McpSession(sessionId, clientIdentity, ttl);
            sessionStore.put(sessionId, session);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("protocolVersion", Constants.MCP_PROTOCOL_VERSION);
            Map<String, Object> capabilities = new LinkedHashMap<>();
            capabilities.put("tools", Map.of("listChanged", false));
            result.put("capabilities", capabilities);
            result.put("serverInfo", Map.of("name", Constants.MCP_SERVER_NAME, "version", configuration.getVersion()));

            exchange.getResponseHeaders().put(MCP_SESSION_ID_HEADER, sessionId);

            if (mcpTracer != null) {
                mcpTracer.setSession(span, sessionId, Constants.MCP_PROTOCOL_VERSION);
                mcpTracer.setOutcome(span, Constants.CAPI_OUTCOME_SUCCESS);
                mcpTracer.setHttpStatus(span, StatusCodes.OK);
            }
            sendJsonRpc(exchange, StatusCodes.OK, JsonRpcResponse.success(request.getId(), result));
        } catch (RuntimeException t) {
            if (mcpTracer != null) {
                mcpTracer.recordError(span, t);
                mcpTracer.setOutcome(span, Constants.CAPI_OUTCOME_ERROR);
            }
            throw t;
        } finally {
            if (scope != null) scope.close();
            if (span != null) span.end();
        }
    }

    private void handleToolsList(HttpServerExchange exchange, JsonRpcRequest request) {
        Span span = (mcpTracer != null)
                ? mcpTracer.startServerSpan(exchange, Constants.GEN_AI_OPERATION_LIST_TOOLS)
                : null;
        Scope scope = (span != null) ? span.makeCurrent() : null;
        try {
            if (mcpTracer != null) mcpTracer.setToolCallId(span, request.getId());

            McpSession session = validateSession(exchange, request);
            if (session == null) {
                if (mcpTracer != null) {
                    mcpTracer.setOutcome(span, Constants.CAPI_OUTCOME_INVALID_REQUEST);
                    mcpTracer.setHttpStatus(span, StatusCodes.OK);
                }
                return;
            }

            List<McpTool> tools = toolRegistry.getAllTools();
            List<Map<String, Object>> toolList = new ArrayList<>();
            for (McpTool tool : tools) {
                Map<String, Object> toolMap = new LinkedHashMap<>();
                toolMap.put("name", tool.getName());
                toolMap.put("description", tool.getDescription());
                try {
                    toolMap.put("inputSchema", objectMapper.readValue(tool.getInputSchema(), Object.class));
                } catch (JsonProcessingException e) {
                    toolMap.put("inputSchema", Map.of("type", "object"));
                }
                toolList.add(toolMap);
            }

            if (span != null) span.setAttribute("mcp.tools.count", toolList.size());
            if (mcpTracer != null) {
                mcpTracer.setOutcome(span, Constants.CAPI_OUTCOME_SUCCESS);
                mcpTracer.setHttpStatus(span, StatusCodes.OK);
            }
            sendJsonRpc(exchange, StatusCodes.OK,
                    JsonRpcResponse.success(request.getId(), Map.of("tools", toolList)));
        } catch (RuntimeException t) {
            if (mcpTracer != null) {
                mcpTracer.recordError(span, t);
                mcpTracer.setOutcome(span, Constants.CAPI_OUTCOME_ERROR);
            }
            throw t;
        } finally {
            if (scope != null) scope.close();
            if (span != null) span.end();
        }
    }

    @SuppressWarnings("unchecked")
    private void handleToolsCall(HttpServerExchange exchange, JsonRpcRequest request) {
        Span span = (mcpTracer != null)
                ? mcpTracer.startServerSpan(exchange, Constants.GEN_AI_OPERATION_EXECUTE_TOOL)
                : null;
        Scope scope = (span != null) ? span.makeCurrent() : null;
        try {
            if (mcpTracer != null) mcpTracer.setToolCallId(span, request.getId());

            McpSession session = validateSession(exchange, request);
            if (session == null) {
                if (mcpTracer != null) mcpTracer.setOutcome(span, Constants.CAPI_OUTCOME_INVALID_REQUEST);
                return;
            }

            Map<String, Object> params = extractToolCallParams(request);
            if (params == null) {
                if (mcpTracer != null) mcpTracer.setOutcome(span, Constants.CAPI_OUTCOME_INVALID_REQUEST);
                sendJsonRpc(exchange, StatusCodes.OK,
                        JsonRpcResponse.error(request.getId(), Constants.JSONRPC_INVALID_PARAMS, "Invalid or missing params"));
                return;
            }

            String toolName = (String) params.get("name");
            if (toolName == null) {
                if (mcpTracer != null) mcpTracer.setOutcome(span, Constants.CAPI_OUTCOME_INVALID_REQUEST);
                sendJsonRpc(exchange, StatusCodes.OK,
                        JsonRpcResponse.error(request.getId(), Constants.JSONRPC_INVALID_PARAMS, "Missing tool name"));
                return;
            }

            if (mcpTracer != null) mcpTracer.setToolName(span, toolName);
            if (span != null) span.updateName(Constants.GEN_AI_OPERATION_EXECUTE_TOOL + " " + toolName);

            McpToolRegistry.McpToolResolution resolution = toolRegistry.resolveToolByName(toolName);
            if (resolution == null) {
                if (mcpTracer != null) mcpTracer.setOutcome(span, Constants.CAPI_OUTCOME_TOOL_NOT_FOUND);
                sendJsonRpc(exchange, StatusCodes.OK,
                        JsonRpcResponse.error(request.getId(), Constants.JSONRPC_INVALID_PARAMS, "Tool not found: " + toolName));
                return;
            }

            McpTool tool = resolution.getTool();
            Service service = resolution.getService();
            if (mcpTracer != null) mcpTracer.setToolType(span, tool.isMcpServer());

            if (!isOpaAllowed(exchange, service)) {
                if (mcpTracer != null) mcpTracer.setOutcome(span, Constants.CAPI_OUTCOME_POLICY_DENIED);
                sendJsonRpc(exchange, StatusCodes.OK,
                        JsonRpcResponse.error(request.getId(), -32000, "Access denied by policy"));
                return;
            }

            List<String> backendUrls = loadBalancer.getOrderedBackendUrls(service);
            if (backendUrls.isEmpty()) {
                if (mcpTracer != null) mcpTracer.setOutcome(span, Constants.CAPI_OUTCOME_NO_BACKEND);
                sendJsonRpc(exchange, StatusCodes.OK,
                        JsonRpcResponse.error(request.getId(), Constants.JSONRPC_INTERNAL_ERROR, "No backend available for tool: " + toolName));
                return;
            }

            Object arguments = params.get("arguments");
            if (tool.isMcpServer() && mcpServerClient != null) {
                handleMcpServerToolCall(exchange, request, tool, service, arguments, span);
            } else if (tool.isOpenApiPromoted()) {
                handleOpenApiToolCall(exchange, request, backendUrls, tool, arguments, span);
            } else if (isStreamingRequest(tool, exchange)) {
                handleStreamingToolCall(exchange, request, backendUrls.get(0), tool, arguments, span);
            } else {
                handleSyncToolCallWithFailover(exchange, request, backendUrls, tool, arguments, span);
            }
        } catch (RuntimeException t) {
            if (mcpTracer != null) {
                mcpTracer.recordError(span, t);
                mcpTracer.setOutcome(span, Constants.CAPI_OUTCOME_ERROR);
            }
            throw t;
        } finally {
            if (scope != null) scope.close();
            if (span != null) span.end();
        }
    }

    private void handleMcpServerToolCall(HttpServerExchange exchange, JsonRpcRequest request,
                                          McpTool tool, Service service, Object arguments, Span parentSpan) {
        int timeout = tool.getTimeout() > 0 ? tool.getTimeout() : configuration.getMcp().getToolCallTimeout();
        try {
            Object result = mcpServerClient.forwardToolCall(service, tool.getName(), arguments, timeout);
            if (mcpTracer != null) mcpTracer.setOutcome(parentSpan, Constants.CAPI_OUTCOME_SUCCESS);
            sendJsonRpc(exchange, StatusCodes.OK, JsonRpcResponse.success(request.getId(), result));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (mcpTracer != null) {
                mcpTracer.recordError(parentSpan, e);
                mcpTracer.setOutcome(parentSpan, Constants.CAPI_OUTCOME_INTERRUPTED);
            }
            sendJsonRpc(exchange, StatusCodes.OK,
                    JsonRpcResponse.error(request.getId(), Constants.JSONRPC_INTERNAL_ERROR, "MCP Server tool call interrupted"));
        } catch (Exception e) {
            log.error("MCP Server tool call failed for {}: {}", tool.getName(), e.getMessage());
            if (mcpTracer != null) {
                mcpTracer.recordError(parentSpan, e);
                mcpTracer.setOutcome(parentSpan, Constants.CAPI_OUTCOME_BACKEND_FAILED);
            }
            sendJsonRpc(exchange, StatusCodes.OK,
                    JsonRpcResponse.error(request.getId(), Constants.JSONRPC_INTERNAL_ERROR,
                            "MCP Server tool call failed"));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractToolCallParams(JsonRpcRequest request) {
        try {
            Map<String, Object> params = (Map<String, Object>) request.getParams();
            return params != null ? params : null;
        } catch (ClassCastException e) {
            return null;
        }
    }

    private boolean isOpaAllowed(HttpServerExchange exchange, Service service) {
        if (opaService == null || service.getServiceMeta().getOpaRego() == null) {
            return true;
        }
        String accessToken = null;
        try {
            accessToken = httpUtils.processAuthorizationAccessToken(exchange);
        } catch (AuthorizationException e) {
            // no token available
        }
        if (accessToken == null) {
            return true;
        }
        OpaResult opaResult = opaService.callOpa(service.getServiceMeta().getOpaRego(), accessToken, true);
        return opaResult != null && opaResult.isAllowed();
    }

    private boolean isStreamingRequest(McpTool tool, HttpServerExchange exchange) {
        return tool.isStreaming()
                && exchange.getRequestHeaders().contains(ACCEPT_HEADER)
                && exchange.getRequestHeaders().get(ACCEPT_HEADER).contains(TEXT_EVENT_STREAM);
    }

    private void handleSyncToolCallWithFailover(HttpServerExchange exchange, JsonRpcRequest request,
                                                List<String> backendUrls, McpTool tool, Object arguments, Span parentSpan) {
        int timeout = tool.getTimeout() > 0 ? tool.getTimeout() : configuration.getMcp().getToolCallTimeout();
        String requestBody;
        try {
            requestBody = arguments != null ? objectMapper.writeValueAsString(arguments) : "{}";
        } catch (JsonProcessingException e) {
            if (mcpTracer != null) {
                mcpTracer.recordError(parentSpan, e);
                mcpTracer.setOutcome(parentSpan, Constants.CAPI_OUTCOME_ERROR);
            }
            sendJsonRpc(exchange, StatusCodes.OK,
                    JsonRpcResponse.error(request.getId(), Constants.JSONRPC_INTERNAL_ERROR, "Failed to serialize arguments"));
            return;
        }

        Exception lastException = null;
        int attempt = 0;
        for (String backendUrl : backendUrls) {
            attempt++;
            Span attemptSpan = (mcpTracer != null)
                    ? mcpTracer.startUpstreamSpan(parentSpan, backendUrl, tool.getName(), attempt)
                    : null;
            Scope attemptScope = (attemptSpan != null) ? attemptSpan.makeCurrent() : null;
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(new URI(backendUrl))
                        .header("Content-Type", APPLICATION_JSON)
                        .timeout(Duration.ofMillis(timeout))
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody));
                if (mcpTracer != null) mcpTracer.injectContext(attemptSpan, builder);
                HttpRequest backendRequest = builder.build();

                HttpResponse<String> backendResponse = httpClient.send(backendRequest, HttpResponse.BodyHandlers.ofString());
                loadBalancer.reportSuccess(backendUrl);

                if (mcpTracer != null) mcpTracer.setHttpStatus(attemptSpan, backendResponse.statusCode());

                if (backendResponse.statusCode() >= 200 && backendResponse.statusCode() < 300) {
                    Map<String, Object> content = Map.of("content",
                            List.of(Map.of("type", "text", "text", backendResponse.body())));
                    if (mcpTracer != null) mcpTracer.setOutcome(parentSpan, Constants.CAPI_OUTCOME_SUCCESS);
                    sendJsonRpc(exchange, StatusCodes.OK, JsonRpcResponse.success(request.getId(), content));
                } else {
                    if (mcpTracer != null) mcpTracer.setOutcome(parentSpan, Constants.CAPI_OUTCOME_BACKEND_FAILED);
                    sendJsonRpc(exchange, StatusCodes.OK,
                            JsonRpcResponse.error(request.getId(), Constants.JSONRPC_INTERNAL_ERROR,
                                    "Backend returned status " + backendResponse.statusCode(),
                                    Map.of("status", backendResponse.statusCode(), "body", backendResponse.body())));
                }
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (mcpTracer != null) {
                    mcpTracer.recordError(attemptSpan, e);
                    mcpTracer.setOutcome(parentSpan, Constants.CAPI_OUTCOME_INTERRUPTED);
                }
                sendJsonRpc(exchange, StatusCodes.OK,
                        JsonRpcResponse.error(request.getId(), Constants.JSONRPC_INTERNAL_ERROR, "Tool call interrupted"));
                return;
            } catch (java.io.IOException e) {
                log.warn("Backend {} failed for tool {}: {}", backendUrl, tool.getName(), e.getMessage());
                loadBalancer.reportFailure(backendUrl);
                if (mcpTracer != null) mcpTracer.recordError(attemptSpan, e);
                lastException = e;
            } catch (Exception e) {
                log.error("Unexpected error calling backend {} for tool {}", backendUrl, tool.getName(), e);
                loadBalancer.reportFailure(backendUrl);
                if (mcpTracer != null) mcpTracer.recordError(attemptSpan, e);
                lastException = e;
            } finally {
                if (attemptScope != null) attemptScope.close();
                if (attemptSpan != null) attemptSpan.end();
            }
        }

        log.error("All backends failed for tool {}", tool.getName(), lastException);
        if (mcpTracer != null) mcpTracer.setOutcome(parentSpan, Constants.CAPI_OUTCOME_BACKEND_FAILED);
        sendJsonRpc(exchange, StatusCodes.OK,
                JsonRpcResponse.error(request.getId(), Constants.JSONRPC_INTERNAL_ERROR,
                        "All backends failed for tool: " + tool.getName()));
    }

    @SuppressWarnings("unchecked")
    private void handleOpenApiToolCall(HttpServerExchange exchange, JsonRpcRequest request,
                                       List<String> backendUrls, McpTool tool, Object arguments, Span parentSpan) {
        int timeout = tool.getTimeout() > 0 ? tool.getTimeout() : configuration.getMcp().getToolCallTimeout();

        Map<String, Object> argMap = arguments instanceof Map ? new LinkedHashMap<>((Map<String, Object>) arguments) : new LinkedHashMap<>();
        String forwardedAuth = exchange.getRequestHeaders().contains(Headers.AUTHORIZATION)
                ? exchange.getRequestHeaders().get(Headers.AUTHORIZATION).getFirst()
                : null;

        Exception lastException = null;
        int attempt = 0;
        for (String backendUrl : backendUrls) {
            attempt++;
            Span attemptSpan = (mcpTracer != null)
                    ? mcpTracer.startUpstreamSpan(parentSpan, backendUrl, tool.getName(), attempt)
                    : null;
            Scope attemptScope = (attemptSpan != null) ? attemptSpan.makeCurrent() : null;
            try {
                OpenApiCallBuilder.Built built = openApiCallBuilder.build(
                        backendUrl, tool.getHttpMethod(), tool.getHttpPathTemplate(), argMap);

                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(built.getUri())
                        .timeout(Duration.ofMillis(timeout))
                        .header("Accept", APPLICATION_JSON);

                if (built.hasBody()) {
                    builder.header("Content-Type", APPLICATION_JSON);
                    builder.method(built.getMethod(), HttpRequest.BodyPublishers.ofString(built.getBody()));
                } else {
                    builder.method(built.getMethod(), HttpRequest.BodyPublishers.noBody());
                }
                if (forwardedAuth != null) {
                    builder.header("Authorization", forwardedAuth);
                }
                if (mcpTracer != null) mcpTracer.injectContext(attemptSpan, builder);

                HttpResponse<String> backendResponse = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                loadBalancer.reportSuccess(backendUrl);
                if (mcpTracer != null) mcpTracer.setHttpStatus(attemptSpan, backendResponse.statusCode());

                if (backendResponse.statusCode() >= 200 && backendResponse.statusCode() < 300) {
                    Map<String, Object> content = Map.of("content",
                            List.of(Map.of("type", "text", "text", backendResponse.body())));
                    if (mcpTracer != null) mcpTracer.setOutcome(parentSpan, Constants.CAPI_OUTCOME_SUCCESS);
                    sendJsonRpc(exchange, StatusCodes.OK, JsonRpcResponse.success(request.getId(), content));
                } else {
                    if (mcpTracer != null) mcpTracer.setOutcome(parentSpan, Constants.CAPI_OUTCOME_BACKEND_FAILED);
                    sendJsonRpc(exchange, StatusCodes.OK,
                            JsonRpcResponse.error(request.getId(), Constants.JSONRPC_INTERNAL_ERROR,
                                    "Backend returned status " + backendResponse.statusCode(),
                                    Map.of("status", backendResponse.statusCode(), "body", backendResponse.body())));
                }
                return;
            } catch (OpenApiCallBuilder.OpenApiCallException e) {
                // Bad caller input — fail fast, no failover.
                if (mcpTracer != null) {
                    mcpTracer.recordError(attemptSpan, e);
                    mcpTracer.setOutcome(parentSpan, Constants.CAPI_OUTCOME_INVALID_REQUEST);
                }
                sendJsonRpc(exchange, StatusCodes.OK,
                        JsonRpcResponse.error(request.getId(), Constants.JSONRPC_INVALID_PARAMS, e.getMessage()));
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (mcpTracer != null) {
                    mcpTracer.recordError(attemptSpan, e);
                    mcpTracer.setOutcome(parentSpan, Constants.CAPI_OUTCOME_INTERRUPTED);
                }
                sendJsonRpc(exchange, StatusCodes.OK,
                        JsonRpcResponse.error(request.getId(), Constants.JSONRPC_INTERNAL_ERROR, "Tool call interrupted"));
                return;
            } catch (java.io.IOException e) {
                log.warn("Backend {} failed for promoted tool {}: {}", backendUrl, tool.getName(), e.getMessage());
                loadBalancer.reportFailure(backendUrl);
                if (mcpTracer != null) mcpTracer.recordError(attemptSpan, e);
                lastException = e;
            } catch (Exception e) {
                log.error("Unexpected error calling backend {} for promoted tool {}", backendUrl, tool.getName(), e);
                loadBalancer.reportFailure(backendUrl);
                if (mcpTracer != null) mcpTracer.recordError(attemptSpan, e);
                lastException = e;
            } finally {
                if (attemptScope != null) attemptScope.close();
                if (attemptSpan != null) attemptSpan.end();
            }
        }

        log.error("All backends failed for promoted tool {}", tool.getName(), lastException);
        if (mcpTracer != null) mcpTracer.setOutcome(parentSpan, Constants.CAPI_OUTCOME_BACKEND_FAILED);
        sendJsonRpc(exchange, StatusCodes.OK,
                JsonRpcResponse.error(request.getId(), Constants.JSONRPC_INTERNAL_ERROR,
                        "All backends failed for tool: " + tool.getName()));
    }

    private void handleStreamingToolCall(HttpServerExchange exchange, JsonRpcRequest request,
                                         String backendUrl, McpTool tool, Object arguments, Span parentSpan) {
        int timeout = tool.getTimeout() > 0 ? tool.getTimeout() : configuration.getMcp().getToolCallTimeout();
        Span attemptSpan = (mcpTracer != null)
                ? mcpTracer.startUpstreamSpan(parentSpan, backendUrl, tool.getName(), 1)
                : null;
        Scope attemptScope = (attemptSpan != null) ? attemptSpan.makeCurrent() : null;
        try {
            String requestBody = arguments != null ? objectMapper.writeValueAsString(arguments) : "{}";
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(new URI(backendUrl))
                    .header("Content-Type", APPLICATION_JSON)
                    .header(ACCEPT_HEADER, TEXT_EVENT_STREAM)
                    .timeout(Duration.ofMillis(timeout))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody));
            if (mcpTracer != null) mcpTracer.injectContext(attemptSpan, builder);
            HttpRequest backendRequest = builder.build();

            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, TEXT_EVENT_STREAM);
            exchange.getResponseHeaders().put(new HttpString("Cache-Control"), "no-cache");

            HttpResponse<java.util.stream.Stream<String>> backendResponse =
                    httpClient.send(backendRequest, HttpResponse.BodyHandlers.ofLines());

            if (mcpTracer != null) mcpTracer.setHttpStatus(attemptSpan, backendResponse.statusCode());

            try (java.util.stream.Stream<String> body = backendResponse.body()) {
                body.forEach(line ->
                    exchange.getResponseSender().send("data: " + line + "\n\n")
                );
            }
            exchange.endExchange();
            loadBalancer.reportSuccess(backendUrl);
            if (mcpTracer != null) mcpTracer.setOutcome(parentSpan, Constants.CAPI_OUTCOME_SUCCESS);

        } catch (java.net.http.HttpTimeoutException e) {
            loadBalancer.reportFailure(backendUrl);
            if (mcpTracer != null) {
                mcpTracer.recordError(attemptSpan, e);
                mcpTracer.setOutcome(parentSpan, Constants.CAPI_OUTCOME_TIMEOUT);
            }
            sendJsonRpc(exchange, StatusCodes.OK,
                    JsonRpcResponse.error(request.getId(), Constants.JSONRPC_INTERNAL_ERROR, "Streaming tool call timed out"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (mcpTracer != null) {
                mcpTracer.recordError(attemptSpan, e);
                mcpTracer.setOutcome(parentSpan, Constants.CAPI_OUTCOME_INTERRUPTED);
            }
            sendJsonRpc(exchange, StatusCodes.OK,
                    JsonRpcResponse.error(request.getId(), Constants.JSONRPC_INTERNAL_ERROR, "Streaming tool call interrupted"));
        } catch (Exception e) {
            loadBalancer.reportFailure(backendUrl);
            log.error("Error streaming from backend for tool {}", tool.getName(), e);
            if (mcpTracer != null) {
                mcpTracer.recordError(attemptSpan, e);
                mcpTracer.setOutcome(parentSpan, Constants.CAPI_OUTCOME_BACKEND_FAILED);
            }
            sendJsonRpc(exchange, StatusCodes.OK,
                    JsonRpcResponse.error(request.getId(), Constants.JSONRPC_INTERNAL_ERROR, "Backend streaming failed"));
        } finally {
            if (attemptScope != null) attemptScope.close();
            if (attemptSpan != null) attemptSpan.end();
        }
    }

    private void handlePing(HttpServerExchange exchange, JsonRpcRequest request) {
        sendJsonRpc(exchange, StatusCodes.OK,
                JsonRpcResponse.success(request.getId(), Map.of()));
    }

    private McpSession validateSession(HttpServerExchange exchange, JsonRpcRequest request) {
        String sessionId = null;
        if (exchange.getRequestHeaders().contains(Constants.MCP_SESSION_HEADER)) {
            sessionId = exchange.getRequestHeaders().get(Constants.MCP_SESSION_HEADER).getFirst();
        }
        if (sessionId == null || sessionId.isEmpty()) {
            sendJsonRpc(exchange, StatusCodes.OK,
                    JsonRpcResponse.error(request.getId(), Constants.JSONRPC_INVALID_REQUEST, "Missing " + Constants.MCP_SESSION_HEADER + " header"));
            return null;
        }

        McpSession session = sessionStore.get(sessionId);
        if (session == null || session.isExpired()) {
            if (session != null) {
                sessionStore.remove(sessionId);
            }
            sendJsonRpc(exchange, StatusCodes.OK,
                    JsonRpcResponse.error(request.getId(), Constants.JSONRPC_INVALID_REQUEST, "Session expired or not found"));
            return null;
        }

        session.touch();
        sessionStore.put(sessionId, session);
        return session;
    }

    private String readBody(HttpServerExchange exchange) throws java.io.IOException {
        InputStream is = exchange.getInputStream();
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }

    private void sendJsonRpc(HttpServerExchange exchange, int statusCode, JsonRpcResponse response) {
        try {
            exchange.setStatusCode(statusCode);
            exchange.getResponseSender().send(objectMapper.writeValueAsString(response));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public McpToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    public McpSessionStore getSessionStore() {
        return sessionStore;
    }
}
