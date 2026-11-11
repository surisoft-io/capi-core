package io.surisoft.capi.undertow;

import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.exception.HttpErrorHandler;
import io.surisoft.capi.schema.Mapping;
import io.surisoft.capi.schema.Service;
import io.surisoft.capi.schema.ServiceMeta;
import io.surisoft.capi.schema.WebsocketClient;
import io.surisoft.capi.utils.HttpUtils;
import io.surisoft.capi.utils.WebsocketUtils;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.util.HeaderMap;
import io.undertow.util.HeaderValues;
import io.undertow.util.Headers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a backend receives in X-Forwarded-For. The chain the request arrived with must reach the
 * backend with CAPI's direct peer appended — not just the peer, which is the load balancer in front
 * of CAPI and loses the client. Built through the production proxy factory, as in
 * {@link ProxyStaleConnectionReuseTest}, so the real header filtering is what runs.
 */
class ProxyForwardedForTest {

    /** The test client connects from loopback, so this is CAPI's direct peer. */
    private static final String PEER = "127.0.0.1";

    private Undertow backend;
    private Undertow gateway;
    private HttpClient client;
    private String gatewayUrl;
    /** Every X-Forwarded-For line the backend received on the last request. */
    private final AtomicReference<List<String>> received = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        backend = Undertow.builder()
                .addHttpListener(0, "127.0.0.1")
                .setHandler(exchange -> {
                    HeaderValues values = exchange.getRequestHeaders().get(Headers.X_FORWARDED_FOR);
                    received.set(values == null ? List.of() : new ArrayList<>(values));
                    exchange.getResponseSender().send("ok");
                })
                .build();
        backend.start();

        gateway = Undertow.builder()
                .addHttpListener(0, "127.0.0.1")
                .setHandler(proxyTo(portOf(backend)))
                .build();
        gateway.start();
        gatewayUrl = "http://127.0.0.1:" + portOf(gateway) + "/";
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    @AfterEach
    void tearDown() {
        if (gateway != null) gateway.stop();
        if (backend != null) backend.stop();
    }

    @Test
    void noInboundHeader_backendGetsThePeer() throws Exception {
        send();
        assertEquals(List.of(PEER), received.get());
    }

    @Test
    void clientBehindOneProxy_backendGetsTheClientAndThePeer() throws Exception {
        send("203.0.113.50");
        assertEquals(List.of("203.0.113.50, " + PEER), received.get());
    }

    @Test
    void longerChain_isKeptInOrderAndThePeerAppended() throws Exception {
        send("203.0.113.50, 70.41.3.18,150.172.238.178");
        assertEquals(List.of("203.0.113.50, 70.41.3.18, 150.172.238.178, " + PEER), received.get());
    }

    @Test
    void severalHeaderLines_areOneChain_sentAsOneLine() throws Exception {
        send("203.0.113.50", "70.41.3.18");
        assertEquals(List.of("203.0.113.50, 70.41.3.18, " + PEER), received.get());
    }

    @Test
    void inboundForwardedFor_dropsBlankEntries() {
        HeaderMap headers = new HeaderMap();
        headers.add(Headers.X_FORWARDED_FOR, " 203.0.113.50 ,, ");
        headers.add(Headers.X_FORWARDED_FOR, "");
        headers.add(Headers.X_FORWARDED_FOR, "70.41.3.18");
        assertEquals("203.0.113.50, 70.41.3.18", CAPIProxyHandler.inboundForwardedFor(headers));
        assertEquals("", CAPIProxyHandler.inboundForwardedFor(new HeaderMap()));
    }

    private void send(String... forwardedFor) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(gatewayUrl)).GET();
        for (String line : forwardedFor) {
            request.header("X-Forwarded-For", line);
        }
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
    }

    private static HttpHandler proxyTo(int backendPort) {
        Mapping mapping = new Mapping();
        mapping.setHostname("127.0.0.1");
        mapping.setPort(backendPort);
        mapping.setRootContext("/");

        WebsocketClient websocketClient = new WebsocketClient();
        websocketClient.setServiceId("/xff/dev");
        websocketClient.setRootContext("/");
        websocketClient.setMappingList(Set.of(mapping));

        ServiceMeta serviceMeta = new ServiceMeta();
        serviceMeta.setScheme("http");
        Service service = new Service();
        service.setServiceMeta(serviceMeta);

        WebsocketUtils websocketUtils = new WebsocketUtils(new CAPIConfiguration.Websocket(), List.of(), null,
                CAPILoadBalancerProxyClient.PoolSettings.DEFAULTS);
        HttpErrorHandler errorHandler = new HttpErrorHandler(new HttpUtils(null, null));
        return websocketUtils.createClientHttpHandler(websocketClient, service, errorHandler, 5000);
    }

    private static int portOf(Undertow server) {
        return ((InetSocketAddress) server.getListenerInfo().getFirst().getAddress()).getPort();
    }
}
