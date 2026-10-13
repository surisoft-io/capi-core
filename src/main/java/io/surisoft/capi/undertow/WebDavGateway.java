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
import io.undertow.util.Methods;
import io.undertow.util.StatusCodes;
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
    /** Carries a token resolved from the session path prefix to the authorization gate. */
    private static final io.undertow.util.AttachmentKey<String> PATH_TOKEN =
            io.undertow.util.AttachmentKey.create(String.class);

    private final int port;
    private final Map<String, WebDavClient> webDavClients;
    private final SSLContext sslContext;
    private final CAPIConfiguration.WebDav config;
    private HttpUtils httpUtils;
    private OpaWasmService opaWasmService;
    private MeterRegistry meterRegistry;
    private io.surisoft.capi.service.WebDavTicketStore ticketStore;
    private io.surisoft.capi.service.WebDavSessionStore sessionStore;
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

    public void setTicketStore(@Nullable io.surisoft.capi.service.WebDavTicketStore ticketStore) {
        this.ticketStore = ticketStore;
    }

    public void setSessionStore(@Nullable io.surisoft.capi.service.WebDavSessionStore sessionStore) {
        this.sessionStore = sessionStore;
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

        if (ticketStore != null
                && config.getTicket() != null
                && config.getTicket().isEnabled()
                && config.getTicket().getPath().equals(requestPath)) {
            issueTicket(exchange);
            return;
        }

        // A session id in the path, stripped before anything else looks at the path. Unlike a query
        // string this survives the client deriving a parent-collection URL by truncating the filename,
        // which is the only reason the first request authenticated and nothing after it did.
        requestPath = consumeSessionPath(exchange, requestPath);

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

        // A token in the query string must not reach the backend: it would land in the backend's
        // access log, and for WebDAV the backend is frequently a plain file server with no notion
        // that the parameter is a credential. RestGateway does the same.
        if (httpUtils != null) {
            httpUtils.stripQueryParameterToken(exchange);
        }

        if (!hostMode) {
            stripServicePrefix(exchange, client);
        }

        client.getHttpHandler().handleRequest(exchange);
    }

    /**
     * Resolves and removes a leading {@code /<prefix>/<session-id>} from the request path.
     *
     * <p>The backend must never see it, so the exchange's path, URI and relative path are all rewritten
     * before routing. An unknown or expired id is left in place rather than stripped, so it falls
     * through to normal routing and fails as an ordinary unauthenticated request.
     *
     * @return the path the rest of the handler should use
     */
    private String consumeSessionPath(HttpServerExchange exchange, String requestPath) {
        String prefix = config.getSessionPathPrefix();
        if (sessionStore == null || prefix == null || prefix.isBlank() || !requestPath.startsWith(prefix)) {
            return requestPath;
        }
        int idEnd = requestPath.indexOf('/', prefix.length());
        String id = idEnd < 0 ? requestPath.substring(prefix.length()) : requestPath.substring(prefix.length(), idEnd);
        String token = sessionStore.resolve(id);
        if (token == null) {
            log.debug("WebDAV session id in path is unknown or expired");
            return requestPath;
        }
        String remainder = (idEnd < 0 || idEnd + 1 > requestPath.length()) ? "/" : requestPath.substring(idEnd);
        if (remainder.isEmpty()) {
            remainder = "/";
        }
        exchange.putAttachment(PATH_TOKEN, token);
        exchange.setRequestPath(remainder);
        exchange.setRequestURI(remainder);
        exchange.setRelativePath(remainder);
        return remainder;
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
    /**
     * Mints a ticket for the caller's own bearer token.
     *
     * <p>Authenticated like any other request: the caller proves who it is with a normal
     * {@code Authorization} header, and the ticket it gets back stands in for exactly that token. It
     * grants nothing extra — the token is still run through the subscription and policy checks when
     * the ticket is redeemed.
     */
    private void issueTicket(HttpServerExchange exchange) {
        // POST only. Minting is a state-changing operation, and on GET it could be triggered by
        // anything that merely follows a link, and cached by an intermediary that ignores no-store.
        if (!Methods.POST.equals(exchange.getRequestMethod())) {
            exchange.setStatusCode(StatusCodes.METHOD_NOT_ALLOWED);
            exchange.getResponseHeaders().put(Headers.ALLOW, "POST");
            exchange.endExchange();
            return;
        }
        if (httpUtils == null) {
            exchange.setStatusCode(Constants.FORBIDDEN_CODE);
            exchange.endExchange();
            return;
        }
        String accessToken;
        try {
            accessToken = httpUtils.processAuthorizationAccessToken(exchange);
        } catch (AuthorizationException e) {
            accessToken = null;
        }
        if (accessToken == null || accessToken.isBlank()) {
            exchange.setStatusCode(Constants.UNAUTHORIZED_CODE);
            exchange.getResponseHeaders().put(Headers.WWW_AUTHENTICATE, "Bearer");
            exchange.endExchange();
            return;
        }

        // Validate before minting. Without this the endpoint hands back a perfectly good-looking
        // ticket for a dead token, and the failure surfaces much later at the authorization gate as
        // an opaque 403 on a document request — a long way from the cause. A credential was presented
        // and rejected, so this is 403 rather than 401.
        try {
            httpUtils.authorizeRequest(accessToken);
        } catch (AuthorizationException e) {
            log.debug("WebDAV ticket refused: the presented token is not valid ({})", e.getMessage());
            exchange.setStatusCode(Constants.FORBIDDEN_CODE);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            exchange.getResponseHeaders().put(Headers.CACHE_CONTROL, "no-store");
            exchange.getResponseSender().send(
                    "{\"error\":\"invalid_token\",\"message\":\"The access token is expired or not valid.\"}");
            return;
        }
        String ticket = ticketStore.issue(accessToken);
        if (ticket == null) {
            exchange.setStatusCode(503);
            exchange.endExchange();
            return;
        }

        // Also a reusable session, because the single-use ticket only ever reaches the first request:
        // a WebDAV client derives a parent-collection URL by truncating the filename and drops the
        // query string with it. Put the session id in the PATH and it survives that truncation.
        String session = sessionStore == null ? null : sessionStore.create(accessToken);
        String sessionPath = (session == null || config.getSessionPathPrefix() == null
                || config.getSessionPathPrefix().isBlank())
                ? null
                : config.getSessionPathPrefix() + session;

        StringBuilder json = new StringBuilder("{\"ticket\":\"").append(ticket)
                .append("\",\"expiresIn\":").append(config.getTicket().getTtlSeconds());
        if (session != null) {
            json.append(",\"session\":\"").append(session)
                .append("\",\"sessionPath\":\"").append(sessionPath)
                .append("\",\"sessionExpiresIn\":").append(config.getSessionTtlSeconds());
        }
        json.append('}');

        exchange.setStatusCode(HttpServletResponse.SC_OK);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.getResponseHeaders().put(Headers.CACHE_CONTROL, "no-store");
        exchange.getResponseSender().send(json.toString());
    }

    /**
     * A ticket in the query string, redeemed into the access token it stands for.
     *
     * <p>Redeeming consumes it, so the copy left behind in the client's recent-documents list cannot
     * be replayed.
     */

    private @Nullable String redeemTicket(HttpServerExchange exchange) {
        if (ticketStore == null || config.getTicket() == null || !config.getTicket().isEnabled()) {
            return null;
        }
        java.util.Deque<String> values = exchange.getQueryParameters().get("ticket");
        if (values == null || values.isEmpty()) {
            return null;
        }
        return ticketStore.redeem(values.getFirst());
    }

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
            challenge(exchange, client, "Authorization not available");
            return false;
        }

        // Three credential sources, most specific first.
        //
        // 1. a session id in the path, already resolved and stripped before routing. This is the only
        //    one that survives the client deriving a URL by truncating the filename.
        String accessToken = exchange.getAttachment(PATH_TOKEN);

        if (accessToken == null) {
            // 2. a single-use ticket in the query string. Reaches only the request whose URL the
            //    caller authored, so it cannot carry the session on its own.
            accessToken = redeemTicket(exchange);
        }

        if (accessToken == null) {
            // 3. an ordinary Authorization header, query token or oauth2 cookie.
            try {
                accessToken = httpUtils.processAuthorizationAccessToken(exchange);
            } catch (AuthorizationException e) {
                challenge(exchange, client, "Invalid authorization");
                return false;
            }
        }
        if (accessToken == null) {
            challenge(exchange, client, "Authorization required");
            return false;
        }

        if (client.requiresSubscription()) {
            if (client.getSubscriptionRole() == null || client.getSubscriptionRole().isBlank()) {
                // Distinct from "the token lacks the group": the service is secured but declares no
                // group to check against, so every request is refused however good the token is.
                log.warn("WebDAV service {} is secured but declares no subscriptionGroup — every "
                       + "request will be refused. Set subscriptionGroup in its Consul metadata.",
                        client.getServiceId());
                deny(exchange, "no-subscription-group-configured");
                return false;
            }
            if (!httpUtils.isAuthorized(accessToken, client.getSubscriptionRole())) {
                log.debug("WebDAV call to {} denied: token not in subscription group {}",
                        client.getServiceId(), client.getSubscriptionRole());
                deny(exchange, "token-not-in-subscription-group:" + client.getSubscriptionRole());
                return false;
            }
        }

        if (policyGoverned && !policyAllows(client, accessToken)) {
            deny(exchange, "policy-denied:" + client.getOpaRego());
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

    /**
     * 401 with a plain {@code Bearer} challenge.
     *
     * <p>Deliberately minimal. Richer challenges were tried against a real Office client and all made
     * things worse: {@code Bearer realm=…, authorization_uri=…} is the shape of Microsoft's Azure AD
     * challenge, so Office signed in to Microsoft and then probed the origin instead of the requested
     * path; {@code X-MS-CookieUri} was ignored outright. The client is told it needs a credential and
     * nothing more — a {@code 401} rather than {@code 403} because only the former is recoverable.
     */
    private void challenge(HttpServerExchange exchange, @Nullable WebDavClient client, String message) {
        exchange.setStatusCode(Constants.UNAUTHORIZED_CODE);
        exchange.getResponseHeaders().put(Headers.WWW_AUTHENTICATE, "Bearer");
        log.debug("WebDAV request unauthenticated: {}", message);
        exchange.endExchange();
    }


    /**
     * 403 — the caller authenticated but is not permitted.
     *
     * <p>The reason is logged rather than returned: a bare 403 cannot distinguish "the service
     * declares no subscriptionGroup" from "the token lacks it" from "a policy refused", and those
     * have completely different fixes. It stays out of the response so the gateway does not tell a
     * caller why it failed.
     */
    private void deny(HttpServerExchange exchange, String reason) {
        log.debug("WebDAV request denied: {}", reason);
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
