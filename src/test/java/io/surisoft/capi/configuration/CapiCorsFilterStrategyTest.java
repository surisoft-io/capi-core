package io.surisoft.capi.configuration;

import org.apache.camel.Exchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class CapiCorsFilterStrategyTest {

    @Mock
    private Exchange exchange;

    private CapiCorsFilterStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new CapiCorsFilterStrategy(List.of("Authorization", "Content-Type"));
    }

    @Test
    void constructor_initializesWithAllowedHeaders() {
        // Should not throw; the strategy object is usable after construction
        assertNotNull(strategy);
    }

    @Test
    void applyFilterToExternalHeaders_returnsTrueForAccessControlAllowOrigin() {
        assertTrue(strategy.applyFilterToExternalHeaders("Access-Control-Allow-Origin", "*", exchange));
    }

    @Test
    void applyFilterToExternalHeaders_returnsTrueForAccessControlAllowOrigin_caseInsensitive() {
        assertTrue(strategy.applyFilterToExternalHeaders("access-control-allow-origin", "*", exchange));
    }

    @Test
    void applyFilterToExternalHeaders_returnsTrueForManagedCorsHeader_allowCredentials() {
        // "Access-Control-Allow-Credentials" is a key in CAPI_CORS_MANAGED_HEADERS
        assertTrue(strategy.applyFilterToExternalHeaders("Access-Control-Allow-Credentials", "true", exchange));
    }

    @Test
    void applyFilterToExternalHeaders_returnsTrueForManagedCorsHeader_maxAge() {
        // "Access-Control-Max-Age" is a key in CAPI_CORS_MANAGED_HEADERS
        assertTrue(strategy.applyFilterToExternalHeaders("Access-Control-Max-Age", "86400", exchange));
    }

    @Test
    void applyFilterToExternalHeaders_returnsTrueForAccessControlAllowMethods() {
        assertTrue(strategy.applyFilterToExternalHeaders("Access-Control-Allow-Methods", "GET, POST", exchange));
    }

    @Test
    void applyFilterToExternalHeaders_returnsTrueForAccessControlAllowMethods_caseInsensitive() {
        assertTrue(strategy.applyFilterToExternalHeaders("access-control-allow-methods", "GET, POST", exchange));
    }

    @Test
    void applyFilterToExternalHeaders_returnsTrueForAccessControlAllowCredentials() {
        assertTrue(strategy.applyFilterToExternalHeaders("Access-Control-Allow-Credentials", "true", exchange));
    }

    @Test
    void applyFilterToExternalHeaders_returnsTrueForAccessControlAllowCredentials_caseInsensitive() {
        assertTrue(strategy.applyFilterToExternalHeaders("access-control-allow-credentials", "true", exchange));
    }

    @Test
    void applyFilterToExternalHeaders_nonCorsHeader_delegatesToSuper() {
        // A regular header that is not CORS-related should delegate to the parent class behavior
        boolean result = strategy.applyFilterToExternalHeaders("X-Custom-Header", "value", exchange);
        // The parent class DefaultHeaderFilterStrategy will return false for unknown external headers by default
        assertFalse(result);
    }

    @Test
    void constructor_withEmptyAllowedHeaders() {
        // Should not throw even with an empty list
        CapiCorsFilterStrategy emptyStrategy = new CapiCorsFilterStrategy(List.of());
        assertNotNull(emptyStrategy);
    }
}
