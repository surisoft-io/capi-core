package io.surisoft.capi.undertow;

import io.surisoft.capi.configuration.CAPIConfiguration;
import io.undertow.UndertowOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the 2.23 upload regression.
 *
 * <p>Undertow changed {@code DEFAULT_MAX_ENTITY_SIZE} from {@code -1} (unlimited) to 2 MiB in
 * 2.3.21. CAPI set the option only on the MCP listener, so the 2.23 dependency bump silently began
 * rejecting every request body over 2 MiB with {@code 400 Bad Request} on REST, gRPC and Admin —
 * including proxied, streamed bodies, which is the path essentially all CAPI traffic takes.
 *
 * <p>These tests fail if anyone reintroduces the inherited default, and will also fail loudly if a
 * future Undertow moves the default again.
 */
class MaxEntitySizeRegressionTest {

    /** What the running Undertow would impose if CAPI did not pin the option. */
    @Test
    void undertowDefaultIsNoLongerUnlimited_whichIsWhyWePinIt() {
        assertEquals(2_097_152L, UndertowOptions.DEFAULT_MAX_ENTITY_SIZE,
                "If this changed, re-read the pinning in RestGateway/GrpcGateway/AdminGateway/"
              + "WebDavGateway — the whole reason they set MAX_ENTITY_SIZE explicitly is that this "
              + "default is not stable across Undertow releases.");
    }

    @Test
    void restDefaultsToUnlimited() {
        assertEquals(-1L, new CAPIConfiguration.Rest().getMaxRequestSize(),
                "REST must default to unlimited: uploads worked before 2.23 and must keep working");
    }

    @Test
    void grpcDefaultsToUnlimited() {
        assertEquals(-1L, new CAPIConfiguration.Grpc().getMaxRequestSize());
    }

    @Test
    void webdavDefaultsToUnlimited() {
        // A large PUT is the normal case for WebDAV; a 2 MiB cap would reject almost every real file.
        assertEquals(-1L, new CAPIConfiguration.WebDav().getMaxRequestSize());
    }

    /** MCP is the one listener that caps on purpose: it buffers the body before authenticating. */
    @Test
    void mcpStillCapsDeliberately() {
        assertEquals(1_048_576L, new CAPIConfiguration.Mcp().getMaxRequestSize(),
                "MCP's cap is intentional (F4) and must not be relaxed by this fix");
    }

    @Test
    void anOperatorCanStillChooseToCap() {
        CAPIConfiguration.Rest rest = new CAPIConfiguration.Rest();
        rest.setMaxRequestSize(5_242_880L);
        assertEquals(5_242_880L, rest.getMaxRequestSize());
    }
}
