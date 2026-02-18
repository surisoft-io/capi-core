package io.surisoft.capi.configuration;

import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.surisoft.capi.schema.ConsulKeyStoreEntry;
import io.surisoft.capi.schema.Service;
import org.cache2k.Cache;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import java.security.NoSuchAlgorithmException;

import static org.junit.jupiter.api.Assertions.*;

class ConfigHelpersTest {

    // --- MetricsConfiguration ---

    @Test
    void createMetricsRegistry_returnsNonNullCompositeMeterRegistry() {
        CompositeMeterRegistry registry = MetricsConfiguration.createMetricsRegistry();
        assertNotNull(registry);
    }

    @Test
    void createPrometheusMeterRegistry_extractsPrometheusFromComposite() {
        CompositeMeterRegistry composite = MetricsConfiguration.createMetricsRegistry();
        PrometheusMeterRegistry prometheus = MetricsConfiguration.createPrometheusMeterRegistry(composite);
        assertNotNull(prometheus);
    }

    @Test
    void createPrometheusMeterRegistry_throwsIfNoPrometheusRegistry() {
        CompositeMeterRegistry emptyComposite = new CompositeMeterRegistry();
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> MetricsConfiguration.createPrometheusMeterRegistry(emptyComposite)
        );
        assertEquals("Prometheus registry not found", ex.getMessage());
    }

    // --- LocalCacheConfiguration ---

    @Test
    void serviceCache_returnsNonNullCache() {
        Cache<String, Service> cache = LocalCacheConfiguration.serviceCache();
        assertNotNull(cache);
        cache.close();
    }

    @Test
    void consulStoreCache_returnsNonNullCache() {
        Cache<String, ConsulKeyStoreEntry> cache = LocalCacheConfiguration.consulStoreCache();
        assertNotNull(cache);
        cache.close();
    }

    // --- CapiSslContextHolder ---

    @Test
    void capiSslContextHolder_constructorAndGetter() throws NoSuchAlgorithmException {
        SSLContext sslContext = SSLContext.getDefault();
        CapiSslContextHolder holder = new CapiSslContextHolder(sslContext);
        assertSame(sslContext, holder.getSslContext());
    }

    @Test
    void capiSslContextHolder_setter() throws NoSuchAlgorithmException {
        SSLContext original = SSLContext.getDefault();
        CapiSslContextHolder holder = new CapiSslContextHolder(original);
        assertSame(original, holder.getSslContext());

        SSLContext another = SSLContext.getInstance("TLS");
        holder.setSslContext(another);
        assertSame(another, holder.getSslContext());
    }

    @Test
    void capiSslContextHolder_constructorWithNull() {
        CapiSslContextHolder holder = new CapiSslContextHolder(null);
        assertNull(holder.getSslContext());
    }
}
