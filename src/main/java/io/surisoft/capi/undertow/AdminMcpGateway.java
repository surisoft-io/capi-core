package io.surisoft.capi.undertow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.exception.AuthorizationException;
import io.surisoft.capi.schema.JsonRpcRequest;
import io.surisoft.capi.schema.JsonRpcResponse;
import io.surisoft.capi.service.admin.AdminMcpTools;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.HttpUtils;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.PathHandler;
import io.undertow.util.Headers;
import io.undertow.util.SameThreadExecutor;
import io.undertow.util.StatusCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nimbusds.jwt.JWTParser;

import javax.net.ssl.SSLContext;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Read-only MCP server over CAPI's own admin API, on its own listener ({@code capi.admin.mcp.port}).
 * See ADMIN-MCP-DESIGN.md.
 *
 * <p>Stateless on every revision it serves: each request is authenticated with the admin check
 * ({@code admin.group}), {@code initialize} is answered without minting a session, and nothing
 * outlives the request.
 *
 * <p><b>Non-blocking.</b> The body is read with the async receiver, the tool's fan-out runs on
 * {@code HttpClient.sendAsync}, and the response is written back on the exchange's I/O thread. No
 * {@code startBlocking()}, no stream I/O, nothing that parks an I/O thread.
 */
public class AdminMcpGateway implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AdminMcpGateway.class);
    private static final String APPLICATION_JSON = "application/json";
    static final String MCP_PATH = "/mcp";
    /** JSON-RPC calls are tiny; the body is buffered before parsing, so keep the ceiling low. */
    static final long MAX_REQUEST_SIZE = 64 * 1024L;
    static final int JSONRPC_SERVER_BUSY = -32000;

    private final int port;
    private final SSLContext sslContext;
    private final AdminMcpTools tools;
    private final HttpUtils httpUtils;
    private final String allowedGroup;
    private final CAPIConfiguration configuration;
    private final Semaphore callPermits;
    /** Hard ceiling for one tools/call, so an exchange is always completed even if a peer black-holes. */
    private final long callDeadlineMs;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** The one issuer allowed to authorize agents, normalised; null = any trusted issuer. */
    private final String authorizationServer;
    private Undertow server;

    public AdminMcpGateway(int port, SSLContext sslContext, AdminMcpTools tools, HttpUtils httpUtils,
                           String allowedGroup, CAPIConfiguration configuration) {
        if (httpUtils == null || allowedGroup == null || allowedGroup.isBlank()) {
            throw new IllegalStateException("Admin MCP requires admin authentication (admin.protected: true with admin.group)");
        }
        this.port = port;
        this.sslContext = sslContext;
        this.tools = tools;
        this.httpUtils = httpUtils;
        this.allowedGroup = allowedGroup;
        this.configuration = configuration;
        CAPIConfiguration.AdminMcp cfg = configuration.getAdmin().getMcp();
        this.callPermits = new Semaphore(Math.max(1, cfg.getMaxConcurrentCalls()));
        // Discovery and fan-out each get nodeTimeoutMs; allow both plus slack.
        this.callDeadlineMs = cfg.getNodeTimeoutMs() * 2 + 2000;
        this.authorizationServer = cfg.getAuthorizationServer() == null || cfg.getAuthorizationServer().isBlank()
                ? null : stripTrailingSlash(cfg.getAuthorizationServer());
        if (this.authorizationServer == null) {
            log.warn("admin.mcp.authorizationServer is not set — the admin MCP accepts a token from ANY issuer in "
                   + "oauth2.keys that carries group {}. Set it to the one issuer allowed to authorize agents.", allowedGroup);
        }
    }

    public void start() {
        PathHandler paths = new PathHandler()
                .addExactPath(MCP_PATH, this::handleMcp)
                .addExactPath(Constants.MCP_PROTECTED_RESOURCE_PATH, this::handleProtectedResourceMetadata);
        Undertow.Builder builder = Undertow.builder()
                .setServerOption(UndertowOptions.MAX_ENTITY_SIZE, MAX_REQUEST_SIZE);
        if (sslContext != null) {
            builder.addHttpsListener(port, "0.0.0.0", sslContext);
        } else {
            builder.addHttpListener(port, "0.0.0.0");
        }
        server = builder.setHandler(paths).build();
        server.start();
        log.info("Admin MCP started on port {} (group: {})", port, allowedGroup);
    }

    public void stop() {
        if (server != null) {
            log.info("Stopping Admin MCP on port {}", port);
            server.stop();
        }
    }

    @Override
    public void close() {
        stop();
    }

    // ------------------------------------------------------------------ request entry (I/O thread)

    void handleMcp(HttpServerExchange exchange) {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod().toString())) {
            exchange.getResponseHeaders().put(Headers.ALLOW, "POST");
            send(exchange, StatusCodes.METHOD_NOT_ALLOWED,
                    JsonRpcResponse.error(null, Constants.JSONRPC_INVALID_REQUEST, "Method not allowed"));
            return;
        }
        // Authenticate before reading the body: an unauthenticated caller costs a header check.
        String accessToken;
        try {
            accessToken = httpUtils.processAuthorizationAccessToken(exchange);
        } catch (AuthorizationException e) {
            accessToken = null;
        }
        if (accessToken == null) {
            exchange.getResponseHeaders().put(Headers.WWW_AUTHENTICATE,
                    "Bearer resource_metadata=\"" + externalBaseUrl(exchange) + Constants.MCP_PROTECTED_RESOURCE_PATH + "\"");
            send(exchange, StatusCodes.UNAUTHORIZED, JsonRpcResponse.error(null, -32000, "Authorization required"));
            return;
        }
        if (!httpUtils.isAuthorized(accessToken, allowedGroup)) {
            send(exchange, StatusCodes.FORBIDDEN, JsonRpcResponse.error(null, -32000, "Not authorized"));
            return;
        }
        if (authorizationServer != null && !issuedByAuthorizationServer(accessToken)) {
            // Valid, in the group, but from another issuer CAPI trusts. Not an agent credential.
            send(exchange, StatusCodes.FORBIDDEN, JsonRpcResponse.error(null, -32000,
                    "Token not issued by the admin MCP authorization server"));
            return;
        }
        // Forwarded verbatim to peers, which re-check it against their own admin group.
        String authorization = Constants.BEARER + accessToken;
        exchange.getRequestReceiver().receiveFullString(
                (ex, body) -> handleBody(ex, body, authorization),
                (ex, e) -> send(ex, StatusCodes.BAD_REQUEST,
                        JsonRpcResponse.error(null, Constants.JSONRPC_PARSE_ERROR, "Could not read request body")));
    }

    void handleBody(HttpServerExchange exchange, String body, String authorization) {
        JsonRpcRequest request;
        try {
            request = objectMapper.readValue(body, JsonRpcRequest.class);
        } catch (JsonProcessingException e) {
            send(exchange, StatusCodes.OK, JsonRpcResponse.error(null, Constants.JSONRPC_PARSE_ERROR, "Parse error"));
            return;
        }
        if (!"2.0".equals(request.getJsonrpc()) || request.getMethod() == null) {
            send(exchange, StatusCodes.OK,
                    JsonRpcResponse.error(request.getId(), Constants.JSONRPC_INVALID_REQUEST, "Invalid JSON-RPC request"));
            return;
        }
        // A notification (no id) gets no JSON-RPC response; streamable HTTP answers it with 202.
        if (request.getId() == null) {
            exchange.setStatusCode(StatusCodes.ACCEPTED);
            exchange.endExchange();
            return;
        }
        String version = declaredVersion(exchange, request);
        if (version != null && !Constants.CAPI_ADMIN_MCP_PROTOCOL_VERSIONS.contains(version)
                && !"initialize".equals(request.getMethod())) {
            send(exchange, StatusCodes.OK, JsonRpcResponse.error(request.getId(),
                    Constants.JSONRPC_UNSUPPORTED_PROTOCOL_VERSION, "Unsupported protocol version: " + version,
                    Map.of("supported", Constants.CAPI_ADMIN_MCP_PROTOCOL_VERSIONS)));
            return;
        }
        switch (request.getMethod()) {
            case "server/discover" -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("protocolVersions", Constants.CAPI_ADMIN_MCP_PROTOCOL_VERSIONS);
                result.put("capabilities", capabilities());
                result.put("serverInfo", serverInfo());
                send(exchange, StatusCodes.OK, JsonRpcResponse.success(request.getId(), decorate(result, version, false)));
            }
            case "initialize" -> handleInitialize(exchange, request);
            case "ping" -> send(exchange, StatusCodes.OK,
                    JsonRpcResponse.success(request.getId(), decorate(Map.of(), version, false)));
            case "tools/list" -> send(exchange, StatusCodes.OK, JsonRpcResponse.success(request.getId(),
                    decorate(Map.of("tools", tools.definitions()), version, true)));
            case "tools/call" -> handleToolsCall(exchange, request, authorization, version);
            default -> send(exchange, StatusCodes.OK, JsonRpcResponse.error(request.getId(),
                    Constants.JSONRPC_METHOD_NOT_FOUND, "Method not found: " + request.getMethod()));
        }
    }

    /**
     * Answers the handshake so older clients connect, but mints no session — streamable HTTP makes
     * the session id optional for the server. Picks the client's revision when served, else the
     * newest handshake revision.
     */
    private void handleInitialize(HttpServerExchange exchange, JsonRpcRequest request) {
        String requested = null;
        if (request.getParams() instanceof Map<?, ?> params && params.get("protocolVersion") instanceof String v) {
            requested = v;
        }
        // Never answer initialize with 2026-07-28: that revision has no handshake, so a client that
        // sent initialize cannot speak it. An unknown request gets the newest handshake revision.
        String negotiated = requested != null && Constants.CAPI_ADMIN_MCP_HANDSHAKE_VERSIONS.contains(requested)
                ? requested
                : Constants.CAPI_ADMIN_MCP_HANDSHAKE_VERSIONS.getFirst();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", negotiated);
        result.put("capabilities", capabilities());
        result.put("serverInfo", serverInfo());
        result.put("instructions", "Read-only view of this CAPI cluster's admin API. Every answer is per node and "
                + "lists nodes that did not answer. Start with " + AdminMcpTools.LIST_NODES + ".");
        send(exchange, StatusCodes.OK, JsonRpcResponse.success(request.getId(), result));
    }

    private void handleToolsCall(HttpServerExchange exchange, JsonRpcRequest request, String authorization, String version) {
        String name = null;
        Map<String, Object> arguments = Map.of();
        if (request.getParams() instanceof Map<?, ?> params) {
            if (params.get("name") instanceof String n) name = n;
            if (params.get("arguments") instanceof Map<?, ?> a) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) a;
                arguments = typed;
            }
        }
        if (!callPermits.tryAcquire()) {
            // Refuse rather than queue: an agent in a loop must not pile work onto every node.
            send(exchange, StatusCodes.OK, JsonRpcResponse.error(request.getId(), JSONRPC_SERVER_BUSY,
                    "Admin MCP is busy on this node; retry shortly"));
            return;
        }
        CompletableFuture<Map<String, Object>> call;
        try {
            call = tools.call(name, arguments, authorization)
                    .orTimeout(callDeadlineMs, TimeUnit.MILLISECONDS);
        } catch (AdminMcpTools.InvalidToolCall e) {
            callPermits.release();
            send(exchange, StatusCodes.OK, JsonRpcResponse.error(request.getId(), Constants.JSONRPC_INVALID_PARAMS, e.getMessage()));
            return;
        } catch (RuntimeException e) {
            callPermits.release();
            log.error("Admin MCP tool {} failed to start", name, e);
            send(exchange, StatusCodes.OK, JsonRpcResponse.error(request.getId(), Constants.JSONRPC_INTERNAL_ERROR, "Internal error"));
            return;
        }
        String toolName = name;
        // Keep the exchange open past this handler; complete it on its own I/O thread.
        exchange.dispatch(SameThreadExecutor.INSTANCE, () -> call.whenComplete((result, failure) -> {
            callPermits.release();
            exchange.getIoThread().execute(() -> {
                if (failure == null) {
                    send(exchange, StatusCodes.OK, JsonRpcResponse.success(request.getId(),
                            decorate(toolResult(result, false), version, false)));
                } else {
                    Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
                    String message = cause instanceof TimeoutException
                            ? "Timed out after " + callDeadlineMs + " ms waiting for the cluster"
                            : "Tool failed: " + cause.getClass().getSimpleName();
                    log.warn("Admin MCP tool {} failed: {}", toolName, cause.toString());
                    send(exchange, StatusCodes.OK, JsonRpcResponse.success(request.getId(),
                            decorate(toolResult(Map.of("error", message), true), version, false)));
                }
            });
        }));
    }

    /** Tool output as both structured content and the same JSON as text, for clients that read only text. */
    private Map<String, Object> toolResult(Map<String, Object> structured, boolean isError) {
        String text;
        try {
            text = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(structured);
        } catch (JsonProcessingException e) {
            text = String.valueOf(structured);
        }
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", text));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("structuredContent", structured);
        result.put("isError", isError);
        return result;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Fields every {@code 2026-07-28} result must carry ({@code resultType}, {@code _meta.serverInfo}),
     * plus cache hints on list results — the same shape {@code McpGateway.decorateResult} produces.
     * Without them Claude Code rejects the {@code server/discover} probe and falls back to the
     * handshake. A no-op for every other revision, so handshake clients see plain results.
     */
    Object decorate(Map<String, Object> result, String version, boolean cacheable) {
        if (!Constants.MCP_PROTOCOL_VERSION_CURRENT.equals(version)) {
            return result;
        }
        Map<String, Object> decorated = new LinkedHashMap<>(result);
        if (cacheable) {
            // private: a listing is per caller, so no shared intermediary may cache it.
            decorated.put(Constants.MCP_CACHE_TTL_MS, 60_000L);
            decorated.put(Constants.MCP_CACHE_SCOPE, Constants.MCP_CACHE_SCOPE_PRIVATE);
        }
        decorated.put(Constants.MCP_RESULT_TYPE, Constants.MCP_RESULT_TYPE_COMPLETE);
        decorated.put("_meta", Map.of(Constants.MCP_META_KEY_SERVER_INFO, serverInfo()));
        return decorated;
    }

    private static String declaredVersion(HttpServerExchange exchange, JsonRpcRequest request) {
        if (request.getMeta() != null
                && request.getMeta().get(Constants.MCP_META_KEY_PROTOCOL_VERSION) instanceof String v && !v.isBlank()) {
            return v.trim();
        }
        String header = exchange.getRequestHeaders().getFirst(Constants.MCP_PROTOCOL_VERSION_HEADER);
        return header != null && !header.isBlank() ? header.trim() : null;
    }

    private static Map<String, Object> capabilities() {
        return Map.of("tools", Map.of("listChanged", false));
    }

    private Map<String, Object> serverInfo() {
        return Map.of("name", Constants.CAPI_ADMIN_MCP_SERVER_NAME,
                "version", configuration.getVersion() != null ? configuration.getVersion() : "unknown");
    }

    /** RFC 9728 metadata, unauthenticated, so an interactive client can find the authorization server. */
    void handleProtectedResourceMetadata(HttpServerExchange exchange) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("resource", externalBaseUrl(exchange) + MCP_PATH);
        doc.put("authorization_servers", authorizationServers());
        doc.put("bearer_methods_supported", List.of("header"));
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, APPLICATION_JSON);
        try {
            exchange.getResponseSender().send(objectMapper.writeValueAsString(doc));
        } catch (JsonProcessingException e) {
            exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
            exchange.getResponseSender().send("{}");
        }
    }

    /**
     * Whether {@code iss} equals {@code admin.mcp.authorizationServer} (trailing slash ignored). Only
     * called after {@code isAuthorized} has verified the signature, so reading the claims without
     * re-verifying is safe.
     */
    boolean issuedByAuthorizationServer(String accessToken) {
        try {
            String issuer = JWTParser.parse(accessToken).getJWTClaimsSet().getIssuer();
            return issuer != null && stripTrailingSlash(issuer).equals(authorizationServer);
        } catch (ParseException e) {
            return false;
        }
    }

    private static String stripTrailingSlash(String url) {
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private List<String> authorizationServers() {
        if (authorizationServer != null) {
            return List.of(authorizationServer);
        }
        CAPIConfiguration.Mcp mcp = configuration.getMcp();
        if (mcp != null && mcp.getAuthorizationServers() != null && !mcp.getAuthorizationServers().isEmpty()) {
            return mcp.getAuthorizationServers();
        }
        List<String> issuers = new ArrayList<>();
        if (configuration.getOauth2() != null && configuration.getOauth2().getKeys() != null) {
            for (String jwks : configuration.getOauth2().getKeys()) {
                String issuer = McpGateway.deriveIssuer(jwks);
                if (issuer != null && !issuers.contains(issuer)) issuers.add(issuer);
            }
        }
        return issuers;
    }

    private String externalBaseUrl(HttpServerExchange exchange) {
        String scheme = sslContext != null ? "https" : "http";
        String host = exchange.getRequestHeaders().getFirst(Headers.HOST);
        return scheme + "://" + (host != null ? host : exchange.getHostAndPort());
    }

    private void send(HttpServerExchange exchange, int status, JsonRpcResponse response) {
        String body;
        try {
            body = objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            status = StatusCodes.INTERNAL_SERVER_ERROR;
            body = "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Internal error\"},\"id\":null}";
        }
        exchange.setStatusCode(status);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, APPLICATION_JSON);
        exchange.getResponseSender().send(body);
    }
}
