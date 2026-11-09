package io.surisoft.capi.undertow;

import com.nimbusds.jose.jwk.RSAKey;
import io.surisoft.capi.schema.ApiKeyEntry;
import io.surisoft.capi.schema.ApiKeyStoreEntry;
import io.surisoft.capi.schema.OpaResult;
import io.surisoft.capi.schema.RestClient;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.service.OpaWasmService;
import io.surisoft.capi.service.consul.ConsulCatalogService;
import io.surisoft.capi.utils.HttpUtils;
import io.surisoft.capi.utils.TestJwts;
import io.surisoft.capi.utils.WebsocketUtils;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The REST authorization table, end to end through a real listener with real signed tokens:
 *
 * <pre>
 * secured | spec          | opa-rego | result
 * true    | any           | any      | every call: verified token + every declared rule (group, rego)
 * false   | none          | none     | anonymous
 * false   | none          | yes      | every call, as secured (misconfiguration stays closed)
 * false   | declared, not loaded     | 503
 * false   | yes           | any      | only operations the spec protects; the rest anonymous
 * </pre>
 *
 * A protected call on a service declaring neither {@code subscription-group} nor {@code opa-rego} is 403.
 */
class RestGatewayAccessTest {

    private static final String POLICY = "capi/orders";
    private static final String GROUP = "premium";

    private RSAKey trusted;
    private OpaWasmService opa;
    private Cache<String, Service> serviceCache;
    private Map<String, RestClient> restClientMap;
    private RestGateway gateway;
    private int port;
    private final List<String> seen = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        trusted = TestJwts.newKey();
        opa = mock(OpaWasmService.class);
        when(opa.isReady()).thenReturn(true);
        when(opa.hasPolicy(POLICY)).thenReturn(true);
        policyAllows(true);
        serviceCache = Cache2kBuilder.of(String.class, Service.class)
                .name("restGwAccess-" + System.nanoTime()).eternal(true).entryCapacity(10).build();
        restClientMap = new HashMap<>();
        setConnectedToConsul(true);
        port = io.surisoft.capi.TestPorts.next();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (gateway != null) {
            gateway.stop();
        }
        serviceCache.close();
        setConnectedToConsul(false);
    }

    private static void setConnectedToConsul(boolean value) throws Exception {
        Field field = ConsulCatalogService.class.getDeclaredField("connectedToConsul");
        field.setAccessible(true);
        field.set(null, value);
    }

    private void policyAllows(boolean allowed) {
        OpaResult result = new OpaResult();
        result.setResult(allowed);
        when(opa.evaluate(anyString(), eq(POLICY), anyString(), anyBoolean())).thenReturn(result);
    }

    private RestClient service(boolean secured, String group, String rego) {
        RestClient rc = new RestClient();
        rc.setServiceId("/orders/v1");
        rc.setCanonicalServiceId("orders:v1");
        rc.setSecured(secured);
        rc.setSubscriptionGroup(group);
        rc.setOpaRego(rego);
        rc.setHttpHandler(exchange -> {
            seen.add(exchange.getRequestURI());
            exchange.setStatusCode(200);
            exchange.getResponseSender().send("ok");
        });
        restClientMap.put("/orders/v1", rc);
        return rc;
    }

    /** {@code GET /public} anonymous, {@code GET /private} protected by its own requirement. */
    private static OpenAPI spec() {
        Paths paths = new Paths();
        paths.addPathItem("/public", new PathItem().get(new Operation()));
        paths.addPathItem("/private", new PathItem().get(new Operation()
                .security(List.of(new SecurityRequirement().addList("bearer")))));
        return new OpenAPI().paths(paths);
    }

    private void start() {
        start(opa);
    }

    private void start(OpaWasmService engine) {
        gateway = new RestGateway(port, 2, "/api", restClientMap,
                new HttpUtils(null, List.of(TestJwts.processorFor(trusted))), serviceCache, null,
                new ArrayList<>(List.of("Authorization")), null);
        gateway.setWebsocketUtils(mock(WebsocketUtils.class));
        if (engine != null) {
            gateway.setOpaWasmService(engine);
        }
        gateway.runProxy();
    }

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/orders/v1" + path)).GET();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int status(String path, String authorization) throws Exception {
        return get(path, authorization).statusCode();
    }

    private String bearer(String... groups) {
        return "Bearer " + TestJwts.tokenWithSubscriptions(trusted, "user-1", 300, groups);
    }

    // ------------------------------------------------------------ secured: true

    @Test
    void secured_everyCallNeedsAVerifiedToken_andTheGroup() throws Exception {
        service(true, GROUP, null);
        start();
        assertEquals(401, status("/x", null));
        assertEquals(401, status("/x", "Bearer garbage"));
        assertEquals(403, status("/x", bearer("basic")));
        assertEquals(200, status("/x", bearer(GROUP)));
        assertEquals(1, seen.size());
    }

    @Test
    void secured_groupAndPolicy_bothMustPass() throws Exception {
        service(true, GROUP, POLICY);
        start();
        assertEquals(403, status("/x", bearer("basic")), "policy would allow, group refuses");
        assertEquals(200, status("/x", bearer(GROUP)));
        policyAllows(false);
        assertEquals(403, status("/x", bearer(GROUP)), "group matches, policy refuses");
        assertEquals(1, seen.size());
    }

    @Test
    void secured_ignoresAnOperationTheSpecLeavesAnonymous() throws Exception {
        service(true, GROUP, null).setOpenAPI(spec());
        start();
        assertEquals(401, status("/public", null));
        assertEquals(200, status("/public", bearer(GROUP)));
    }

    @Test
    void secured_withNoRule_isRefused_evenWithAValidToken() throws Exception {
        service(true, null, null);
        start();
        assertEquals(403, status("/x", bearer(GROUP)));
        assertTrue(seen.isEmpty());
    }

    @Test
    void policy_withOpaDisabled_isRefused() throws Exception {
        service(true, GROUP, POLICY);
        start(null);
        assertEquals(403, status("/x", bearer(GROUP)), "a group match must not stand in for an unevaluated policy");
        assertTrue(seen.isEmpty());
    }

    @Test
    void policy_unknownToTheBundle_isRefused() throws Exception {
        when(opa.hasPolicy(POLICY)).thenReturn(false);
        service(true, null, POLICY);
        start();
        HttpResponse<String> response = get("/x", bearer());
        assertEquals(403, response.statusCode());
        assertTrue(response.body().contains(POLICY), response.body());
    }

    @Test
    void policy_engineNotReady_is503WithRetryAfter() throws Exception {
        when(opa.isReady()).thenReturn(false);
        service(true, null, POLICY);
        start();
        HttpResponse<String> response = get("/x", bearer());
        assertEquals(503, response.statusCode());
        assertEquals("1", response.headers().firstValue("Retry-After").orElse(null));
    }

    // ------------------------------------------------------------ secured: false, no spec

    @Test
    void notSecured_noSpec_noPolicy_isAnonymous() throws Exception {
        service(false, GROUP, null);
        start();
        assertEquals(200, status("/x", null));
    }

    @Test
    void notSecured_noSpec_withPolicy_staysProtected() throws Exception {
        service(false, null, POLICY);
        start();
        assertEquals(401, status("/x", null));
        assertEquals(200, status("/x", bearer()));
        verify(opa).evaluate(eq("orders:v1"), eq(POLICY), anyString(), eq(true));
    }

    // ------------------------------------------------------------ secured: false, with spec

    @Test
    void notSecured_spec_noPolicy_onlyProtectedOperationsNeedTheGroup() throws Exception {
        service(false, GROUP, null).setOpenAPI(spec());
        start();
        assertEquals(200, status("/public", null));
        assertEquals(401, status("/private", null));
        assertEquals(403, status("/private", bearer("basic")));
        assertEquals(200, status("/private", bearer(GROUP)));
    }

    /** The case that motivated the gate: before it, OPA refused every anonymous operation. */
    @Test
    void notSecured_spec_withPolicy_anonymousOperationsStayAnonymous() throws Exception {
        service(false, GROUP, POLICY).setOpenAPI(spec());
        start();
        assertEquals(200, status("/public", null));
        verify(opa, never()).evaluate(anyString(), anyString(), anyString(), anyBoolean());

        assertEquals(401, status("/private", null));
        assertEquals(403, status("/private", bearer("basic")));
        policyAllows(false);
        assertEquals(403, status("/private", bearer(GROUP)));
        policyAllows(true);
        assertEquals(200, status("/private", bearer(GROUP)));
    }

    @Test
    void protectedOperation_evaluatesThePolicyOnce() throws Exception {
        service(false, null, POLICY).setOpenAPI(spec());
        start();
        assertEquals(200, status("/private", bearer()));
        verify(opa, times(1)).evaluate(anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void protectedOperation_withNoRule_isRefused() throws Exception {
        service(false, null, null).setOpenAPI(spec());
        start();
        assertEquals(403, status("/private", bearer(GROUP)));
        assertEquals(200, status("/public", null));
    }

    @Test
    void operationWithoutOwnSecurity_inheritsTheGlobalRequirement() throws Exception {
        OpenAPI spec = spec().security(List.of(new SecurityRequirement().addList("bearer")));
        spec.getPaths().addPathItem("/opted-out", new PathItem().get(new Operation().security(List.of())));
        service(false, GROUP, null).setOpenAPI(spec);
        start();
        assertEquals(401, status("/public", null), "no own security: the global requirement applies");
        assertEquals(200, status("/public", bearer(GROUP)));
        assertEquals(200, status("/opted-out", null), "security: [] opts out of the global requirement");
    }

    @Test
    void declaredSpecNotLoaded_is503_neverAnonymous() throws Exception {
        service(false, null, null).setOpenApiDeclared(true);
        start();
        HttpResponse<String> response = get("/public", null);
        assertEquals(503, response.statusCode());
        assertTrue(seen.isEmpty());
    }

    /**
     * A templated path declared before a literal one must not capture it: {@code /items/{id}} is
     * anonymous, {@code /items/search} is protected, and the request for the literal path has to be
     * checked against the literal operation, whatever the declaration order.
     */
    @Test
    void literalPath_winsOverATemplateDeclaredBeforeIt() throws Exception {
        Paths paths = new Paths();
        paths.addPathItem("/items/{id}", new PathItem().get(new Operation().security(List.of())));
        paths.addPathItem("/items/search", new PathItem().get(new Operation()
                .security(List.of(new SecurityRequirement().addList("bearer")))));
        service(false, null, POLICY).setOpenAPI(new OpenAPI().paths(paths));
        start();

        assertEquals(401, status("/items/search", null), "the literal, protected operation applies");
        policyAllows(false);
        assertEquals(403, status("/items/search", bearer()), "and its policy is evaluated");
        assertEquals(200, status("/items/42", null), "the templated operation stays anonymous");
        assertFalse(seen.stream().anyMatch(u -> u.endsWith("/items/search")), "nothing reached the backend for search");
    }

    /** More literal segments win, compared left to right: {@code /a/{x}/c} beats {@code /a/{x}/{y}}. */
    @Test
    void mostSpecificTemplate_wins() throws Exception {
        Paths paths = new Paths();
        paths.addPathItem("/a/{x}/{y}", new PathItem().get(new Operation().security(List.of())));
        paths.addPathItem("/a/{x}/c", new PathItem().get(new Operation()
                .security(List.of(new SecurityRequirement().addList("bearer")))));
        service(false, null, POLICY).setOpenAPI(new OpenAPI().paths(paths));
        start();

        assertEquals(401, status("/a/1/c", null));
        assertEquals(200, status("/a/1/d", null));
    }

    /**
     * The spec as CAPI really gets it: JSON text through the same parser as discovery, not an object built in code. A protected operation must reach the policy.
     */
    @Test
    void parsedSpec_protectedOperation_reachesThePolicy() throws Exception {
        String json = """
                {"openapi":"3.0.1","info":{"title":"orders","version":"1"},
                 "components":{"securitySchemes":{"bearerAuth":{"type":"http","scheme":"bearer","bearerFormat":"JWT"}}},
                 "paths":{
                   "/orders/{id}":{"get":{"responses":{"200":{"description":"ok"}}}},
                   "/orders/mine":{"get":{"security":[{"bearerAuth":[]}],"responses":{"200":{"description":"ok"}}}},
                   "/orders":{"post":{"security":[{"bearerAuth":[]}],"responses":{"200":{"description":"ok"}}},
                              "get":{"responses":{"200":{"description":"ok"}}}}
                 }}
                """;
        OpenAPI spec = new io.swagger.v3.parser.OpenAPIV3Parser().readContents(json).getOpenAPI();
        assertNotNull(spec);
        service(false, null, POLICY).setOpenAPI(spec);
        start();

        assertEquals(401, status("/orders/mine", null), "protected, declared after a template");
        policyAllows(false);
        assertEquals(403, status("/orders/mine", bearer()), "policy evaluated and refuses");
        policyAllows(true);
        assertEquals(200, status("/orders/mine", bearer()), "policy evaluated and allows");
        assertEquals(200, status("/orders/7", null), "no security declared: anonymous");
        assertEquals(200, status("/orders", null), "GET /orders declares none: anonymous");
        HttpRequest post = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/orders/v1/orders"))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        assertEquals(401, HttpClient.newHttpClient().send(post, HttpResponse.BodyHandlers.ofString()).statusCode(),
                "POST /orders is protected");
    }

    @Test
    void operationNotInTheSpec_is400() throws Exception {
        service(false, GROUP, null).setOpenAPI(spec());
        start();
        assertEquals(400, status("/elsewhere", bearer(GROUP)));
    }

    // ------------------------------------------------------------ API keys

    private void withApiKey(RestClient rc, String rawKey) {
        rc.setApiKeyEnabled(true);
        String hash = HttpUtils.hashApiKey(rawKey);
        ApiKeyEntry entry = new ApiKeyEntry();
        entry.setKeyHash(hash);
        entry.setEnabled(true);
        ApiKeyStoreEntry store = new ApiKeyStoreEntry();
        store.getKeysByHash().put(hash, entry);
        Cache<String, ApiKeyStoreEntry> cache = Cache2kBuilder.of(String.class, ApiKeyStoreEntry.class)
                .name("restGwAccessKeys-" + System.nanoTime()).eternal(true).entryCapacity(10).build();
        cache.put("orders:v1", store);
        gateway.setApiKeyCache(cache);
    }

    @Test
    void apiKey_standsInForTheToken_onASecuredServiceWithoutPolicy() throws Exception {
        RestClient rc = service(true, GROUP, null);
        start();
        withApiKey(rc, "k-1");
        assertEquals(200, status("/x", "ApiKey k-1"));
        assertEquals(403, status("/x", "ApiKey wrong"));
    }

    @Test
    void apiKey_cannotSatisfyAPolicy() throws Exception {
        RestClient rc = service(true, GROUP, POLICY);
        start();
        withApiKey(rc, "k-1");
        assertEquals(403, status("/x", "ApiKey k-1"));
        assertTrue(seen.isEmpty());
    }
}
