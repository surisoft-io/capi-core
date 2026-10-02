package io.surisoft.capi.undertow;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.exception.AuthorizationException;
import io.surisoft.capi.schema.OpaResult;
import io.surisoft.capi.schema.WebDavClient;
import io.surisoft.capi.service.OpaWasmService;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.HttpUtils;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import jakarta.annotation.Nullable;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * WebDAV (RFC 4918) listener. Deliberately shares nothing with {@link RestGateway} beyond the proxy
 * plumbing: WebDAV needs {@code OPTIONS} forwarded rather than answered, arbitrary method tokens
 * rather than the CRUD set, and a {@code 401} challenge rather than CAPI's usual {@code 403}.
 *
 * <p>See WEBDAV-DESIGN.md. Phase 1 covers routing, the authorization gate and verb passthrough. The
 * path-mode response rewriting (207 {@code href} bodies) and {@code Destination} rewriting are phase
 * 3; until then a path-routed service needs its backend configured with its public base URI, which
 * most WebDAV servers support (sabre/dav {@code setBaseUri()}, Apache {@code mod_dav} behind an
 * {@code Alias}).
 */
public class WebDavGateway {

    private static final Logger log = LoggerFactory.getLogger(WebDavGateway.class);

    /** Verbs that modify the collection. Refused outright on a {@code webdav-read-only} service. */
    private static final Set<String> MUTATING_METHODS = Set.of(
            "PUT", "DELETE", "POST", "MKCOL", "COPY", "MOVE", "LOCK", "UNLOCK", "PROPPATCH", "PATCH");

    private static final HttpString DAV_DEPTH = new HttpString(Constants.WEBDAV_HEADER_DEPTH);

    private final int port;
    private final Map<String, WebDavClient> webDavClients;
    private final SSLContext sslContext;
    private final CAPIConfiguration.WebDav config;
    private HttpUtils httpUtils;
    private OpaWasmService opaWasmService;
    private MeterRegistry meterRegistry;
    private Undertow server;

    public WebDavGateway(int port,
                         Map<String, WebDavClient> webDavClients,
                         @Nullable SSLContext sslContext,
                         CAPIConfiguration.WebDav config) {
        this.port = port;
        this.webDavClients = webDavClients;
        this.sslContext = sslContext;
        this.config = config;
    }

    public void setHttpUtils(HttpUtils httpUtils) {
        this.httpUtils = httpUtils;
    }

    public void setOpaWasmService(@Nullable OpaWasmService opaWasmService) {
        this.opaWasmService = opaWasmService;
    }

    public void setMeterRegistry(@Nullable MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void runProxy() {
        Undertow.Builder builder = Undertow.builder();
        if (sslContext != null) {
            builder.addHttpsListener(port, Constants.UNDERTOW_LISTENING_ADDRESS, sslContext);
        } else {
            builder.addHttpListener(port, Constants.UNDERTOW_LISTENING_ADDRESS);
        }
        // No ENABLE_HTTP2: WebDAV clients are overwhelmingly HTTP/1.1 and nothing in the protocol
        // benefits from h2.
        //
        // Entity size is pinned, not inherited: Undertow's own default became 2 MiB in 2.3.21, which
        // would reject essentially every real WebDAV upload. A large PUT is the normal case here.
        builder.setServerOption(UndertowOptions.MAX_ENTITY_SIZE, config.getMaxRequestSize());
        builder.setHandler(this::handle);
        server = builder.build();
        server.start();
        log.info("WebDAV Gateway started on port {} (routing: {}, maxRequestSize={})", port, config.getRouting(),
                config.getMaxRequestSize() < 0 ? "unlimited" : config.getMaxRequestSize());
    }

    private void handle(HttpServerExchange exchange) throws Exception {
        String requestPath = exchange.getRequestPath();
        String method = exchange.getRequestMethod().toString().toUpperCase(Locale.ROOT);

        if (Constants.CAPI_HEALTH_PATH.equals(requestPath)) {
            exchange.setStatusCode(HttpServletResponse.SC_OK);
            exchange.endExchange();
            return;
        }

        // Host first, then path — a service that opted into a hostname is never also path-reachable.
        WebDavClient client = resolveByHost(exchange);
        boolean hostMode = client != null;
        if (client == null && config.isPathRoutingEnabled()) {
            client = resolveByPath(requestPath);
        }

        // OPTIONS * is a server-wide capability query. It is routable in host mode; in path mode the
        // path is literally "*" and maps to no service, so answer it here — with Allow, and
        // deliberately WITHOUT a DAV header, because CAPI is not itself a WebDAV server and must not
        // assert compliance classes it has not verified.
        if (client == null && "OPTIONS".equals(method) && "*".equals(requestPath)) {
            exchange.setStatusCode(HttpServletResponse.SC_OK);
            exchange.getResponseHeaders().put(Headers.ALLOW,
                    "OPTIONS, GET, HEAD, PUT, DELETE, POST, PROPFIND, PROPPATCH, MKCOL, COPY, MOVE, LOCK, UNLOCK");
            exchange.endExchange();
            return;
        }

        if (client == null) {
            log.debug("No WebDAV service for host={} path={}", hostHeader(exchange), requestPath);
            exchange.setStatusCode(Constants.NOT_FOUND_CODE);
            exchange.endExchange();
            return;
        }

        // The request path and the Destination header are both attacker-influenced filenames, so the
        // dot-segment guard matters more here than on REST. Reuses RestGateway's segment-wise check
        // (a pure static in this package) rather than a second copy that could drift from it.
        if (RestGateway.containsDotSegment(requestPath)) {
            log.debug("WebDAV request rejected, dot segment in path: {}", requestPath);
            exchange.setStatusCode(Constants.BAD_REQUEST_CODE);
            exchange.endExchange();
            return;
        }

        if (client.isReadOnly() && MUTATING_METHODS.contains(method)) {
            log.debug("WebDAV {} refused on read-only service {}", method, client.getServiceId());
            exchange.setStatusCode(Constants.FORBIDDEN_CODE);
            exchange.endExchange();
            return;
        }

        if (!depthAllowed(exchange, client)) {
            exchange.setStatusCode(Constants.FORBIDDEN_CODE);
            exchange.endExchange();
            return;
        }

        if (!authorize(exchange, client)) {
            return; // authorize() has written the status and ended the exchange
        }

        if (!hostMode) {
            stripServicePrefix(exchange, client);
        }

        client.getHttpHandler().handleRequest(exchange);
    }

    // ---- routing ----

    private @Nullable WebDavClient resolveByHost(HttpServerExchange exchange) {
        if (!config.isHostRoutingEnabled()) {
            return null;
        }
        String host = hostHeader(exchange);
        if (host == null) {
            return null;
        }
        for (WebDavClient candidate : webDavClients.values()) {
            if (candidate.getWebdavHost() != null && candidate.getWebdavHost().equals(host)) {
                return candidate;
            }
        }
        return null;
    }

    /** Host header, lowercased and with any port removed. IPv6 literals keep their brackets. */
    private static @Nullable String hostHeader(HttpServerExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst(Headers.HOST);
        if (host == null || host.isBlank()) {
            return null;
        }
        host = host.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            return close < 0 ? host : host.substring(0, close + 1);
        }
        int colon = host.indexOf(':');
        return colon < 0 ? host : host.substring(0, colon);
    }

    /** {@code /{service}/{group}/…} → the client registered under {@code /service/group}. */
    private @Nullable WebDavClient resolveByPath(String requestPath) {
        String[] parts = requestPath.split("/");
        if (parts.length < 3) {
            return null;
        }
        return webDavClients.get("/" + parts[1] + "/" + parts[2]);
    }

    /**
     * Rewrites the exchange into backend path space by dropping {@code /{service}/{group}}, the same
     * mechanism {@link RestGateway} uses before dispatching to the proxy.
     */
    private void stripServicePrefix(HttpServerExchange exchange, WebDavClient client) {
        String requestPath = exchange.getRequestPath();
        String prefix = client.getServiceId();
        String remainder = requestPath.length() > prefix.length() && requestPath.startsWith(prefix)
                ? requestPath.substring(prefix.length())
                : "/";
        if (remainder.isEmpty()) {
            remainder = "/";
        }
        String forwardPath = client.getRootContext() != null
                ? client.getRootContext() + (remainder.equals("/") ? "" : remainder)
                : remainder;
        if (forwardPath.isEmpty()) {
            forwardPath = "/";
        }
        exchange.setRequestURI(forwardPath);
        exchange.setRelativePath(forwardPath);
    }

    // ---- Depth bound ----

    /**
     * {@code Depth: infinity} is an amplification lever: one small request, unbounded backend work
     * and an unbounded response. Some sync clients use it legitimately and this listener is internal
     * only, so it ships observing rather than enforcing — watch
     * {@code capi_webdav_depth_total{action="observed"}} on real traffic before setting
     * {@code enforceMaxDepth: true}.
     */
    private boolean depthAllowed(HttpServerExchange exchange, WebDavClient client) {
        String depth = exchange.getRequestHeaders().getFirst(DAV_DEPTH);
        if (depth == null) {
            return true;
        }
        depth = depth.trim();
        int value;
        if ("infinity".equalsIgnoreCase(depth)) {
            value = Integer.MAX_VALUE;
        } else {
            try {
                value = Integer.parseInt(depth);
            } catch (NumberFormatException e) {
                return true; // let the backend reject a malformed Depth per RFC 4918
            }
        }
        if (config.getMaxDepth() <= 0 || value <= config.getMaxDepth()) {
            return true;
        }
        count("capi_webdav_depth_total", client, config.isEnforceMaxDepth() ? "rejected" : "observed");
        if (!config.isEnforceMaxDepth()) {
            return true;
        }
        log.debug("WebDAV Depth {} exceeds maxDepth {} on {}", depth, config.getMaxDepth(), client.getServiceId());
        return false;
    }

    // ---- authorization ----

    /**
     * The subscription and policy gate. Two deliberate divergences from the rest of CAPI:
     *
     * <ul>
     *   <li>a missing or invalid credential answers {@code 401} with {@code WWW-Authenticate}, not
     *       {@code 403}. A {@code 403} does not make Explorer or Finder prompt for credentials, so
     *       the mount simply fails with no way for the user to authenticate;</li>
     *   <li>an authenticated caller who is not permitted still gets {@code 403}, matching the house
     *       convention.</li>
     * </ul>
     *
     * @return true when the request may proceed; otherwise the response has already been written
     */
    private boolean authorize(HttpServerExchange exchange, WebDavClient client) {
        boolean policyGoverned = opaWasmService != null
                && client.getOpaRego() != null
                && !client.getOpaRego().isBlank();
        if (!client.requiresSubscription() && !policyGoverned) {
            return true;
        }

        if (httpUtils == null) {
            log.error("WebDAV service {} is secured but no token validator is configured; refusing",
                    client.getServiceId());
            challenge(exchange, "Authorization not available");
            return false;
        }

        String accessToken;
        try {
            accessToken = httpUtils.processAuthorizationAccessToken(exchange);
        } catch (AuthorizationException e) {
            challenge(exchange, "Invalid authorization");
            return false;
        }
        if (accessToken == null) {
            challenge(exchange, "Authorization required");
            return false;
        }

        if (client.requiresSubscription()
                && !httpUtils.isAuthorized(accessToken, client.getSubscriptionRole())) {
            log.debug("WebDAV call to {} denied: token not in subscription group", client.getServiceId());
            deny(exchange);
            return false;
        }

        if (policyGoverned && !policyAllows(client, accessToken)) {
            deny(exchange);
            return false;
        }
        return true;
    }

    /**
     * A missing policy decision is a refusal, never a pass. Mirrors the MCP gateway after SEC-01:
     * no token means no claims, and no claims means no decision.
     */
    private boolean policyAllows(WebDavClient client, String accessToken) {
        try {
            OpaResult result = opaWasmService.evaluate(
                    client.getServiceId(), client.getOpaRego(), accessToken, true);
            if (result == null || !result.isAllowed()) {
                log.debug("WebDAV call to {} denied by policy {}", client.getServiceId(), client.getOpaRego());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("WebDAV policy evaluation failed for {} ({}): refusing", client.getServiceId(), e.getMessage());
            return false;
        }
    }

    /** 401 + WWW-Authenticate, so a GUI client prompts for a credential instead of just failing. */
    private void challenge(HttpServerExchange exchange, String message) {
        exchange.setStatusCode(Constants.UNAUTHORIZED_CODE);
        if (config.getBasicAuth() != null && config.getBasicAuth().isEnabled()) {
            exchange.getResponseHeaders().put(Headers.WWW_AUTHENTICATE,
                    "Basic realm=\"" + config.getBasicAuth().getRealm() + "\", charset=\"UTF-8\"");
        } else {
            exchange.getResponseHeaders().put(Headers.WWW_AUTHENTICATE, "Bearer");
        }
        log.debug("WebDAV request unauthenticated: {}", message);
        exchange.endExchange();
    }

    private void deny(HttpServerExchange exchange) {
        exchange.setStatusCode(Constants.FORBIDDEN_CODE);
        exchange.endExchange();
    }

    private void count(String name, WebDavClient client, String action) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder(name)
                .tag("service", client.getServiceId() != null ? client.getServiceId() : "unknown")
                .tag("action", action)
                .register(meterRegistry)
                .increment();
    }

    public void stop() {
        if (server != null) {
            log.info("Stopping WebDAV Gateway on port {}", port);
            server.stop();
        }
    }
}
