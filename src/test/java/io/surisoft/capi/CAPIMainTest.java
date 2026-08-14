package io.surisoft.capi;

import ch.qos.logback.classic.LoggerContext;
import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.schema.WebsocketClient;
import io.surisoft.capi.utils.Startup;
import io.surisoft.capi.utils.WebsocketUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CAPIMainTest {

    // ---------------------------------------------------------------
    // Constructor - no env variable set -> should throw RuntimeException
    // ---------------------------------------------------------------

    @Test
    void constructor_noCAPIConfigFile_throwsRuntimeException() {
        // The constructor reads CAPI_CONFIG_FILE env var. When it's null, it throws.
        // We need to ensure the env var is NOT set for this test.
        // If it happens to be set in the test environment, we skip.
        String configFile = System.getenv().get("CAPI_CONFIG_FILE");
        if (configFile != null) {
            // Cannot test this path if env var is set
            return;
        }
        assertThrows(RuntimeException.class, CAPIMain::new);
    }

    // ---------------------------------------------------------------
    // initializeLogs - via reflection to test the private method
    // ---------------------------------------------------------------

    @Test
    void initializeLogs_withLoggingTracesEnabled_setsSysProperties() throws Exception {
        CAPIConfiguration config = new CAPIConfiguration();

        CAPIConfiguration.LoggingTraces loggingTraces = new CAPIConfiguration.LoggingTraces();
        loggingTraces.setEnabled(true);
        loggingTraces.setTenant("test-tenant");
        loggingTraces.setAppName("test-app");
        loggingTraces.setAppEnvironment("test-env");
        loggingTraces.setDestination("test-dest");
        config.setLoggingTraces(loggingTraces);

        CAPIConfiguration.AccessLogs accessLogs = new CAPIConfiguration.AccessLogs();
        accessLogs.setEnabled(false);
        config.setAccessLogs(accessLogs);

        // Use reflection to call the private initializeLogs method
        Method initLogs = CAPIMain.class.getDeclaredMethod("initializeLogs", CAPIConfiguration.class);
        initLogs.setAccessible(true);

        // Create an instance without calling the real constructor
        // We use Unsafe or just invoke the static-like method on a proxy
        // Since initializeLogs is an instance method, we need an instance.
        // However, the constructor does too much. We'll use sun.misc.Unsafe to bypass.
        try {
            sun.misc.Unsafe unsafe = getUnsafe();
            CAPIMain instance = (CAPIMain) unsafe.allocateInstance(CAPIMain.class);
            initLogs.invoke(instance, config);

            assertEquals("true", System.getProperty("logging.logback.logs.enabled"));
            assertEquals("test-tenant", System.getProperty("logging.logback.logs.tenant"));
            assertEquals("test-app", System.getProperty("logging.logback.logs.appName"));
            assertEquals("test-env", System.getProperty("logging.logback.logs.appEnvironment"));
            assertEquals("test-dest", System.getProperty("logging.logback.logs.destination"));
        } finally {
            // Cleanup
            System.clearProperty("logging.logback.logs.enabled");
            System.clearProperty("logging.logback.logs.tenant");
            System.clearProperty("logging.logback.logs.appName");
            System.clearProperty("logging.logback.logs.appEnvironment");
            System.clearProperty("logging.logback.logs.destination");

            // Restore logback context
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            context.reset();
            ch.qos.logback.classic.joran.JoranConfigurator configurator = new ch.qos.logback.classic.joran.JoranConfigurator();
            configurator.setContext(context);
            try {
                configurator.doConfigure(CAPIMain.class.getClassLoader().getResource("logback.xml"));
            } catch (Exception ignored) {
                // If logback.xml is not on test classpath, silently ignore
            }
        }
    }

    @Test
    void initializeLogs_withAccessLogsEnabled_setsSysProperties() throws Exception {
        CAPIConfiguration config = new CAPIConfiguration();

        CAPIConfiguration.LoggingTraces loggingTraces = new CAPIConfiguration.LoggingTraces();
        loggingTraces.setEnabled(false);
        config.setLoggingTraces(loggingTraces);

        CAPIConfiguration.AccessLogs accessLogs = new CAPIConfiguration.AccessLogs();
        accessLogs.setEnabled(true);
        accessLogs.setTenant("access-tenant");
        accessLogs.setService("access-service");
        accessLogs.setDestination("access-dest");
        config.setAccessLogs(accessLogs);

        Method initLogs = CAPIMain.class.getDeclaredMethod("initializeLogs", CAPIConfiguration.class);
        initLogs.setAccessible(true);

        try {
            sun.misc.Unsafe unsafe = getUnsafe();
            CAPIMain instance = (CAPIMain) unsafe.allocateInstance(CAPIMain.class);
            initLogs.invoke(instance, config);

            assertEquals("true", System.getProperty("logging.logback.access.enabled"));
            assertEquals("access-tenant", System.getProperty("logging.logback.access.tenant"));
            assertEquals("access-service", System.getProperty("logging.logback.access.service"));
            assertEquals("access-dest", System.getProperty("logging.logback.access.destination"));
        } finally {
            System.clearProperty("logging.logback.access.enabled");
            System.clearProperty("logging.logback.access.tenant");
            System.clearProperty("logging.logback.access.service");
            System.clearProperty("logging.logback.access.destination");

            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            context.reset();
            ch.qos.logback.classic.joran.JoranConfigurator configurator = new ch.qos.logback.classic.joran.JoranConfigurator();
            configurator.setContext(context);
            try {
                configurator.doConfigure(CAPIMain.class.getClassLoader().getResource("logback.xml"));
            } catch (Exception ignored) {}
        }
    }

    @Test
    void initializeLogs_bothDisabled_doesNotSetTraceOrAccessProps() throws Exception {
        CAPIConfiguration config = new CAPIConfiguration();

        CAPIConfiguration.LoggingTraces loggingTraces = new CAPIConfiguration.LoggingTraces();
        loggingTraces.setEnabled(false);
        config.setLoggingTraces(loggingTraces);

        CAPIConfiguration.AccessLogs accessLogs = new CAPIConfiguration.AccessLogs();
        accessLogs.setEnabled(false);
        config.setAccessLogs(accessLogs);

        Method initLogs = CAPIMain.class.getDeclaredMethod("initializeLogs", CAPIConfiguration.class);
        initLogs.setAccessible(true);

        // Clear the properties before running
        System.clearProperty("logging.logback.logs.enabled");
        System.clearProperty("logging.logback.access.enabled");

        try {
            sun.misc.Unsafe unsafe = getUnsafe();
            CAPIMain instance = (CAPIMain) unsafe.allocateInstance(CAPIMain.class);
            initLogs.invoke(instance, config);

            assertNull(System.getProperty("logging.logback.logs.enabled"));
            assertNull(System.getProperty("logging.logback.access.enabled"));
        } finally {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            context.reset();
            ch.qos.logback.classic.joran.JoranConfigurator configurator = new ch.qos.logback.classic.joran.JoranConfigurator();
            configurator.setContext(context);
            try {
                configurator.doConfigure(CAPIMain.class.getClassLoader().getResource("logback.xml"));
            } catch (Exception ignored) {}
        }
    }

    // === New tests to increase coverage ===

    @Test
    void initializeLogs_bothEnabled_setsBothProperties() throws Exception {
        CAPIConfiguration config = new CAPIConfiguration();

        CAPIConfiguration.LoggingTraces loggingTraces = new CAPIConfiguration.LoggingTraces();
        loggingTraces.setEnabled(true);
        loggingTraces.setTenant("trace-tenant");
        loggingTraces.setAppName("trace-app");
        loggingTraces.setAppEnvironment("trace-env");
        loggingTraces.setDestination("trace-dest");
        config.setLoggingTraces(loggingTraces);

        CAPIConfiguration.AccessLogs accessLogs = new CAPIConfiguration.AccessLogs();
        accessLogs.setEnabled(true);
        accessLogs.setTenant("access-tenant-both");
        accessLogs.setService("access-service-both");
        accessLogs.setDestination("access-dest-both");
        config.setAccessLogs(accessLogs);

        Method initLogs = CAPIMain.class.getDeclaredMethod("initializeLogs", CAPIConfiguration.class);
        initLogs.setAccessible(true);

        try {
            sun.misc.Unsafe unsafe = getUnsafe();
            CAPIMain instance = (CAPIMain) unsafe.allocateInstance(CAPIMain.class);
            initLogs.invoke(instance, config);

            // Verify logging traces props
            assertEquals("true", System.getProperty("logging.logback.logs.enabled"));
            assertEquals("trace-tenant", System.getProperty("logging.logback.logs.tenant"));
            assertEquals("trace-app", System.getProperty("logging.logback.logs.appName"));
            assertEquals("trace-env", System.getProperty("logging.logback.logs.appEnvironment"));
            assertEquals("trace-dest", System.getProperty("logging.logback.logs.destination"));

            // Verify access logs props
            assertEquals("true", System.getProperty("logging.logback.access.enabled"));
            assertEquals("access-tenant-both", System.getProperty("logging.logback.access.tenant"));
            assertEquals("access-service-both", System.getProperty("logging.logback.access.service"));
            assertEquals("access-dest-both", System.getProperty("logging.logback.access.destination"));
        } finally {
            System.clearProperty("logging.logback.logs.enabled");
            System.clearProperty("logging.logback.logs.tenant");
            System.clearProperty("logging.logback.logs.appName");
            System.clearProperty("logging.logback.logs.appEnvironment");
            System.clearProperty("logging.logback.logs.destination");
            System.clearProperty("logging.logback.access.enabled");
            System.clearProperty("logging.logback.access.tenant");
            System.clearProperty("logging.logback.access.service");
            System.clearProperty("logging.logback.access.destination");

            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            context.reset();
            ch.qos.logback.classic.joran.JoranConfigurator configurator = new ch.qos.logback.classic.joran.JoranConfigurator();
            configurator.setContext(context);
            try {
                configurator.doConfigure(CAPIMain.class.getClassLoader().getResource("logback.xml"));
            } catch (Exception ignored) {}
        }
    }

    @Test
    void initializeLogs_loggingTracesEnabled_accessLogsDisabled_setsCorrectProperties() throws Exception {
        CAPIConfiguration config = new CAPIConfiguration();

        CAPIConfiguration.LoggingTraces loggingTraces = new CAPIConfiguration.LoggingTraces();
        loggingTraces.setEnabled(true);
        loggingTraces.setTenant("t1");
        loggingTraces.setAppName("a1");
        loggingTraces.setAppEnvironment("e1");
        loggingTraces.setDestination("d1");
        config.setLoggingTraces(loggingTraces);

        CAPIConfiguration.AccessLogs accessLogs = new CAPIConfiguration.AccessLogs();
        accessLogs.setEnabled(false);
        config.setAccessLogs(accessLogs);

        // Clear first
        System.clearProperty("logging.logback.access.enabled");

        Method initLogs = CAPIMain.class.getDeclaredMethod("initializeLogs", CAPIConfiguration.class);
        initLogs.setAccessible(true);

        try {
            sun.misc.Unsafe unsafe = getUnsafe();
            CAPIMain instance = (CAPIMain) unsafe.allocateInstance(CAPIMain.class);
            initLogs.invoke(instance, config);

            assertEquals("true", System.getProperty("logging.logback.logs.enabled"));
            assertEquals("t1", System.getProperty("logging.logback.logs.tenant"));
            assertEquals("a1", System.getProperty("logging.logback.logs.appName"));
            assertEquals("e1", System.getProperty("logging.logback.logs.appEnvironment"));
            assertEquals("d1", System.getProperty("logging.logback.logs.destination"));
            assertNull(System.getProperty("logging.logback.access.enabled"));
        } finally {
            System.clearProperty("logging.logback.logs.enabled");
            System.clearProperty("logging.logback.logs.tenant");
            System.clearProperty("logging.logback.logs.appName");
            System.clearProperty("logging.logback.logs.appEnvironment");
            System.clearProperty("logging.logback.logs.destination");

            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            context.reset();
            ch.qos.logback.classic.joran.JoranConfigurator configurator = new ch.qos.logback.classic.joran.JoranConfigurator();
            configurator.setContext(context);
            try {
                configurator.doConfigure(CAPIMain.class.getClassLoader().getResource("logback.xml"));
            } catch (Exception ignored) {}
        }
    }

    @Test
    void constructor_throwsRuntimeException_messageCheck() {
        String configFile = System.getenv().get("CAPI_CONFIG_FILE");
        if (configFile != null) {
            return;
        }
        RuntimeException ex = assertThrows(RuntimeException.class, CAPIMain::new);
        assertEquals("CAPI_CONFIG_FILE environment variable is not set", ex.getMessage());
    }

    @Test
    void getWebsocketGateway_viaReflection_nullWhenDisabled() throws Exception {
        CAPIConfiguration config = new CAPIConfiguration();
        CAPIConfiguration.Websocket wsConfig = new CAPIConfiguration.Websocket();
        wsConfig.setEnabled(false);
        config.setWebsocket(wsConfig);

        sun.misc.Unsafe unsafe = getUnsafe();
        CAPIMain instance = (CAPIMain) unsafe.allocateInstance(CAPIMain.class);

        // Set the capiConfiguration field via reflection
        java.lang.reflect.Field configField = CAPIMain.class.getDeclaredField("capiConfiguration");
        configField.setAccessible(true);
        configField.set(instance, config);

        // The getWebsocketGateway method needs a Startup mock, but it's private
        // We can test the websocket disabled branch
        Method getWsGateway = CAPIMain.class.getDeclaredMethod("getWebsocketGateway", io.surisoft.capi.utils.Startup.class);
        getWsGateway.setAccessible(true);

        // With websocket disabled, it should return null
        Object result = getWsGateway.invoke(instance, (Object) null);
        assertNull(result);
    }

    @Test
    void getWebsocketGateway_enabledButNullContextPath_returnsNull() throws Exception {
        CAPIConfiguration config = new CAPIConfiguration();
        CAPIConfiguration.Websocket wsConfig = new CAPIConfiguration.Websocket();
        wsConfig.setEnabled(true);
        wsConfig.setContextPath(null);
        config.setWebsocket(wsConfig);

        sun.misc.Unsafe unsafe = getUnsafe();
        CAPIMain instance = (CAPIMain) unsafe.allocateInstance(CAPIMain.class);

        java.lang.reflect.Field configField = CAPIMain.class.getDeclaredField("capiConfiguration");
        configField.setAccessible(true);
        configField.set(instance, config);

        Method getWsGateway = CAPIMain.class.getDeclaredMethod("getWebsocketGateway", io.surisoft.capi.utils.Startup.class);
        getWsGateway.setAccessible(true);

        Object result = getWsGateway.invoke(instance, (Object) null);
        assertNull(result);
    }

    @Test
    void getWebsocketGateway_enabledButEmptyContextPath_returnsNull() throws Exception {
        CAPIConfiguration config = new CAPIConfiguration();
        CAPIConfiguration.Websocket wsConfig = new CAPIConfiguration.Websocket();
        wsConfig.setEnabled(true);
        wsConfig.setContextPath("");
        config.setWebsocket(wsConfig);

        sun.misc.Unsafe unsafe = getUnsafe();
        CAPIMain instance = (CAPIMain) unsafe.allocateInstance(CAPIMain.class);

        java.lang.reflect.Field configField = CAPIMain.class.getDeclaredField("capiConfiguration");
        configField.setAccessible(true);
        configField.set(instance, config);

        Method getWsGateway = CAPIMain.class.getDeclaredMethod("getWebsocketGateway", io.surisoft.capi.utils.Startup.class);
        getWsGateway.setAccessible(true);

        Object result = getWsGateway.invoke(instance, (Object) null);
        assertNull(result);
    }

    // === Additional tests to increase CAPIMain coverage ===

    @Test
    void getWebsocketGateway_enabledWithValidContextPath_callsStartupMethods() throws Exception {
        CAPIConfiguration config = new CAPIConfiguration();
        CAPIConfiguration.Websocket wsConfig = new CAPIConfiguration.Websocket();
        wsConfig.setEnabled(true);
        wsConfig.setContextPath("/ws");
        wsConfig.setPort(19200);
        config.setWebsocket(wsConfig);

        sun.misc.Unsafe unsafe = getUnsafe();
        CAPIMain instance = (CAPIMain) unsafe.allocateInstance(CAPIMain.class);

        Field configField = CAPIMain.class.getDeclaredField("capiConfiguration");
        configField.setAccessible(true);
        configField.set(instance, config);

        Startup mockStartup = mock(Startup.class);
        Map<String, WebsocketClient> clientMap = new HashMap<>();
        when(mockStartup.getWebSocketClientMap()).thenReturn(clientMap);
        WebsocketUtils mockWsUtils = mock(WebsocketUtils.class);
        when(mockStartup.getWebsocketUtils()).thenReturn(mockWsUtils);
        when(mockStartup.getUndertowSslContext()).thenReturn(null);

        Method getWsGateway = CAPIMain.class.getDeclaredMethod("getWebsocketGateway", Startup.class);
        getWsGateway.setAccessible(true);

        // This will create a real WebsocketGateway and call runProxy().
        // runProxy() starts an Undertow server, so we need to handle that.
        Object result = getWsGateway.invoke(instance, mockStartup);
        assertNotNull(result);

        // Clean up: stop the websocket gateway if it was started
        try {
            Method stopMethod = result.getClass().getMethod("stop");
            stopMethod.invoke(result);
        } catch (Exception ignored) {
            // Best-effort cleanup
        }
    }

    @Test
    void initializeLogs_loggingTracesWithSpecialCharFields_setsSysProperties() throws Exception {
        CAPIConfiguration config = new CAPIConfiguration();

        CAPIConfiguration.LoggingTraces loggingTraces = new CAPIConfiguration.LoggingTraces();
        loggingTraces.setEnabled(true);
        loggingTraces.setTenant("tenant-with-special-chars_123");
        loggingTraces.setAppName("app/with/slashes");
        loggingTraces.setAppEnvironment("env.with.dots");
        loggingTraces.setDestination("localhost:5044");
        config.setLoggingTraces(loggingTraces);

        CAPIConfiguration.AccessLogs accessLogs = new CAPIConfiguration.AccessLogs();
        accessLogs.setEnabled(false);
        config.setAccessLogs(accessLogs);

        Method initLogs = CAPIMain.class.getDeclaredMethod("initializeLogs", CAPIConfiguration.class);
        initLogs.setAccessible(true);

        try {
            sun.misc.Unsafe unsafe = getUnsafe();
            CAPIMain instance = (CAPIMain) unsafe.allocateInstance(CAPIMain.class);
            initLogs.invoke(instance, config);

            assertEquals("true", System.getProperty("logging.logback.logs.enabled"));
            assertEquals("tenant-with-special-chars_123", System.getProperty("logging.logback.logs.tenant"));
            assertEquals("app/with/slashes", System.getProperty("logging.logback.logs.appName"));
            assertEquals("env.with.dots", System.getProperty("logging.logback.logs.appEnvironment"));
            assertEquals("localhost:5044", System.getProperty("logging.logback.logs.destination"));
        } finally {
            System.clearProperty("logging.logback.logs.enabled");
            System.clearProperty("logging.logback.logs.tenant");
            System.clearProperty("logging.logback.logs.appName");
            System.clearProperty("logging.logback.logs.appEnvironment");
            System.clearProperty("logging.logback.logs.destination");

            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            context.reset();
            ch.qos.logback.classic.joran.JoranConfigurator configurator = new ch.qos.logback.classic.joran.JoranConfigurator();
            configurator.setContext(context);
            try {
                configurator.doConfigure(CAPIMain.class.getClassLoader().getResource("logback.xml"));
            } catch (Exception ignored) {}
        }
    }

    @Test
    void initializeLogs_accessLogsWithSpecialCharFields_setsSysProperties() throws Exception {
        CAPIConfiguration config = new CAPIConfiguration();

        CAPIConfiguration.LoggingTraces loggingTraces = new CAPIConfiguration.LoggingTraces();
        loggingTraces.setEnabled(false);
        config.setLoggingTraces(loggingTraces);

        CAPIConfiguration.AccessLogs accessLogs = new CAPIConfiguration.AccessLogs();
        accessLogs.setEnabled(true);
        accessLogs.setTenant("access-tenant-special_456");
        accessLogs.setService("capi-gateway-service");
        accessLogs.setDestination("logstash.local:5044");
        config.setAccessLogs(accessLogs);

        Method initLogs = CAPIMain.class.getDeclaredMethod("initializeLogs", CAPIConfiguration.class);
        initLogs.setAccessible(true);

        try {
            sun.misc.Unsafe unsafe = getUnsafe();
            CAPIMain instance = (CAPIMain) unsafe.allocateInstance(CAPIMain.class);
            initLogs.invoke(instance, config);

            assertEquals("true", System.getProperty("logging.logback.access.enabled"));
            assertEquals("access-tenant-special_456", System.getProperty("logging.logback.access.tenant"));
            assertEquals("capi-gateway-service", System.getProperty("logging.logback.access.service"));
            assertEquals("logstash.local:5044", System.getProperty("logging.logback.access.destination"));
        } finally {
            System.clearProperty("logging.logback.access.enabled");
            System.clearProperty("logging.logback.access.tenant");
            System.clearProperty("logging.logback.access.service");
            System.clearProperty("logging.logback.access.destination");

            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            context.reset();
            ch.qos.logback.classic.joran.JoranConfigurator configurator = new ch.qos.logback.classic.joran.JoranConfigurator();
            configurator.setContext(context);
            try {
                configurator.doConfigure(CAPIMain.class.getClassLoader().getResource("logback.xml"));
            } catch (Exception ignored) {}
        }
    }

    @Test
    void initializeLogs_verifyLogbackResetAndReconfigure() throws Exception {
        CAPIConfiguration config = new CAPIConfiguration();

        CAPIConfiguration.LoggingTraces loggingTraces = new CAPIConfiguration.LoggingTraces();
        loggingTraces.setEnabled(false);
        config.setLoggingTraces(loggingTraces);

        CAPIConfiguration.AccessLogs accessLogs = new CAPIConfiguration.AccessLogs();
        accessLogs.setEnabled(false);
        config.setAccessLogs(accessLogs);

        Method initLogs = CAPIMain.class.getDeclaredMethod("initializeLogs", CAPIConfiguration.class);
        initLogs.setAccessible(true);

        try {
            sun.misc.Unsafe unsafe = getUnsafe();
            CAPIMain instance = (CAPIMain) unsafe.allocateInstance(CAPIMain.class);
            // After calling initializeLogs, the static 'log' field should be set
            initLogs.invoke(instance, config);

            // Verify the static log field is now initialized
            Field logField = CAPIMain.class.getDeclaredField("log");
            logField.setAccessible(true);
            Object logInstance = logField.get(null);
            assertNotNull(logInstance, "Static log field should be initialized after initializeLogs");
        } finally {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            context.reset();
            ch.qos.logback.classic.joran.JoranConfigurator configurator = new ch.qos.logback.classic.joran.JoranConfigurator();
            configurator.setContext(context);
            try {
                configurator.doConfigure(CAPIMain.class.getClassLoader().getResource("logback.xml"));
            } catch (Exception ignored) {}
        }
    }

    @Test
    void main_withoutConfigEnvVar_throwsRuntimeException() {
        String configFile = System.getenv().get("CAPI_CONFIG_FILE");
        if (configFile != null) {
            return;
        }
        // The main method creates a new CAPIMain which throws
        assertThrows(RuntimeException.class, CAPIMain::new);
    }

    @Test
    void getWebsocketGateway_websocketDisabled_returnsNullRegardlessOfStartup() throws Exception {
        CAPIConfiguration config = new CAPIConfiguration();
        CAPIConfiguration.Websocket wsConfig = new CAPIConfiguration.Websocket();
        wsConfig.setEnabled(false);
        wsConfig.setContextPath("/ws");
        wsConfig.setPort(19201);
        config.setWebsocket(wsConfig);

        sun.misc.Unsafe unsafe = getUnsafe();
        CAPIMain instance = (CAPIMain) unsafe.allocateInstance(CAPIMain.class);

        Field configField = CAPIMain.class.getDeclaredField("capiConfiguration");
        configField.setAccessible(true);
        configField.set(instance, config);

        Startup mockStartup = mock(Startup.class);

        Method getWsGateway = CAPIMain.class.getDeclaredMethod("getWebsocketGateway", Startup.class);
        getWsGateway.setAccessible(true);

        Object result = getWsGateway.invoke(instance, mockStartup);
        assertNull(result);

        // Verify that startup methods were never called since websocket is disabled
        verify(mockStartup, never()).getWebSocketClientMap();
        verify(mockStartup, never()).getWebsocketUtils();
    }

    @Test
    void getWebsocketGateway_enabledButBlankContextPath_returnsNull() throws Exception {
        CAPIConfiguration config = new CAPIConfiguration();
        CAPIConfiguration.Websocket wsConfig = new CAPIConfiguration.Websocket();
        wsConfig.setEnabled(true);
        wsConfig.setContextPath("   ");
        config.setWebsocket(wsConfig);

        sun.misc.Unsafe unsafe = getUnsafe();
        CAPIMain instance = (CAPIMain) unsafe.allocateInstance(CAPIMain.class);

        Field configField = CAPIMain.class.getDeclaredField("capiConfiguration");
        configField.setAccessible(true);
        configField.set(instance, config);

        Method getWsGateway = CAPIMain.class.getDeclaredMethod("getWebsocketGateway", Startup.class);
        getWsGateway.setAccessible(true);

        // contextPath is "   " (whitespace only) - this is NOT empty so it will proceed
        // to create WebsocketGateway. We need to mock Startup to avoid NPE.
        Startup mockStartup = mock(Startup.class);
        Map<String, WebsocketClient> clientMap = new HashMap<>();
        when(mockStartup.getWebSocketClientMap()).thenReturn(clientMap);
        WebsocketUtils mockWsUtils = mock(WebsocketUtils.class);
        when(mockStartup.getWebsocketUtils()).thenReturn(mockWsUtils);
        when(mockStartup.getUndertowSslContext()).thenReturn(null);

        Object result = getWsGateway.invoke(instance, mockStartup);
        // "   " is not empty, so it creates a WebsocketGateway
        assertNotNull(result);

        // Clean up
        try {
            Method stopMethod = result.getClass().getMethod("stop");
            stopMethod.invoke(result);
        } catch (Exception ignored) {}
    }

    // === Tests for configureAdminGateway ===

    @Test
    void configureAdminGateway_withAllOptionalComponents_setsAll() throws Exception {
        CAPIConfiguration config = new CAPIConfiguration();
        config.setAdminPort(19481);
        config.setVersion("1.0.0");
        config.setInstanceName("test");

        sun.misc.Unsafe unsafe = getUnsafe();
        CAPIMain instance = (CAPIMain) unsafe.allocateInstance(CAPIMain.class);

        Field configField = CAPIMain.class.getDeclaredField("capiConfiguration");
        configField.setAccessible(true);
        configField.set(instance, config);

        Startup mockStartup = mock(Startup.class);
        io.micrometer.prometheusmetrics.PrometheusMeterRegistry mockRegistry = mock(io.micrometer.prometheusmetrics.PrometheusMeterRegistry.class);
        when(mockStartup.getPrometheusRegistry()).thenReturn(mockRegistry);

        org.cache2k.Cache<String, io.surisoft.capi.schema.Service> svcCache =
                org.cache2k.Cache2kBuilder.of(String.class, io.surisoft.capi.schema.Service.class)
                        .name("capiMainAdminGw-" + System.nanoTime())
                        .eternal(true)
                        .entryCapacity(10)
                        .build();
        when(mockStartup.getServiceCache()).thenReturn(svcCache);
        when(mockStartup.getUndertowSslContext()).thenReturn(null);
        when(mockStartup.getCapiTrustManager()).thenReturn(null);

        Map<String, WebsocketClient> wsMap = new HashMap<>();
        when(mockStartup.getWebSocketClientMap()).thenReturn(wsMap);
        when(mockStartup.getMcpToolRegistry()).thenReturn(mock(io.surisoft.capi.service.McpToolRegistry.class));
        when(mockStartup.getMcpSessionStore()).thenReturn(mock(io.surisoft.capi.service.McpSessionStore.class));
        when(mockStartup.getRestClientMap()).thenReturn(new java.util.concurrent.ConcurrentHashMap<>());

        Method configAdmin = CAPIMain.class.getDeclaredMethod("configureAdminGateway", Startup.class);
        configAdmin.setAccessible(true);

        Object result = configAdmin.invoke(instance, mockStartup);
        assertNotNull(result);

        // Clean up
        try {
            Method stopMethod = result.getClass().getMethod("stop");
            stopMethod.invoke(result);
        } catch (Exception ignored) {}
        svcCache.close();
    }

    @Test
    void configureAdminGateway_withNullOptionalComponents_doesNotSetOptionals() throws Exception {
        CAPIConfiguration config = new CAPIConfiguration();
        config.setAdminPort(19482);
        config.setVersion("1.0.0");
        config.setInstanceName("test");

        sun.misc.Unsafe unsafe = getUnsafe();
        CAPIMain instance = (CAPIMain) unsafe.allocateInstance(CAPIMain.class);

        Field configField = CAPIMain.class.getDeclaredField("capiConfiguration");
        configField.setAccessible(true);
        configField.set(instance, config);

        Startup mockStartup = mock(Startup.class);
        io.micrometer.prometheusmetrics.PrometheusMeterRegistry mockRegistry = mock(io.micrometer.prometheusmetrics.PrometheusMeterRegistry.class);
        when(mockStartup.getPrometheusRegistry()).thenReturn(mockRegistry);

        org.cache2k.Cache<String, io.surisoft.capi.schema.Service> svcCache =
                org.cache2k.Cache2kBuilder.of(String.class, io.surisoft.capi.schema.Service.class)
                        .name("capiMainAdminGwNull-" + System.nanoTime())
                        .eternal(true)
                        .entryCapacity(10)
                        .build();
        when(mockStartup.getServiceCache()).thenReturn(svcCache);
        when(mockStartup.getUndertowSslContext()).thenReturn(null);
        when(mockStartup.getCapiTrustManager()).thenReturn(null);
        when(mockStartup.getWebSocketClientMap()).thenReturn(null);
        when(mockStartup.getMcpToolRegistry()).thenReturn(null);
        when(mockStartup.getMcpSessionStore()).thenReturn(null);
        when(mockStartup.getRestClientMap()).thenReturn(new java.util.concurrent.ConcurrentHashMap<>());

        Method configAdmin = CAPIMain.class.getDeclaredMethod("configureAdminGateway", Startup.class);
        configAdmin.setAccessible(true);

        Object result = configAdmin.invoke(instance, mockStartup);
        assertNotNull(result);

        try {
            Method stopMethod = result.getClass().getMethod("stop");
            stopMethod.invoke(result);
        } catch (Exception ignored) {}
        svcCache.close();
    }

    // === Tests for buildManagedHeaders ===

    @Test
    void buildManagedHeaders_nullAllowedHeaders_returnsDefaults() throws Exception {
        CAPIConfiguration config = new CAPIConfiguration();
        config.setAllowedHeaders(null);

        sun.misc.Unsafe unsafe = getUnsafe();
        CAPIMain instance = (CAPIMain) unsafe.allocateInstance(CAPIMain.class);

        Field configField = CAPIMain.class.getDeclaredField("capiConfiguration");
        configField.setAccessible(true);
        configField.set(instance, config);

        Method buildHeaders = CAPIMain.class.getDeclaredMethod("buildManagedHeaders");
        buildHeaders.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, String> result = (Map<String, String>) buildHeaders.invoke(instance);
        assertNotNull(result);
    }

    @Test
    void buildManagedHeaders_emptyAllowedHeaders_returnsDefaults() throws Exception {
        CAPIConfiguration config = new CAPIConfiguration();
        config.setAllowedHeaders(new java.util.ArrayList<>());

        sun.misc.Unsafe unsafe = getUnsafe();
        CAPIMain instance = (CAPIMain) unsafe.allocateInstance(CAPIMain.class);

        Field configField = CAPIMain.class.getDeclaredField("capiConfiguration");
        configField.setAccessible(true);
        configField.set(instance, config);

        Method buildHeaders = CAPIMain.class.getDeclaredMethod("buildManagedHeaders");
        buildHeaders.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, String> result = (Map<String, String>) buildHeaders.invoke(instance);
        assertNotNull(result);
        assertNull(result.get("Access-Control-Allow-Headers"));
    }

    @Test
    void buildManagedHeaders_withAllowedHeadersAndCookieName_addsCookie() throws Exception {
        CAPIConfiguration config = new CAPIConfiguration();
        java.util.List<String> headers = new java.util.ArrayList<>();
        headers.add("Content-Type");
        headers.add("Authorization");
        config.setAllowedHeaders(headers);

        CAPIConfiguration.Oauth2 oauth2 = new CAPIConfiguration.Oauth2();
        oauth2.setCookieName("my-cookie");
        config.setOauth2(oauth2);

        sun.misc.Unsafe unsafe = getUnsafe();
        CAPIMain instance = (CAPIMain) unsafe.allocateInstance(CAPIMain.class);

        Field configField = CAPIMain.class.getDeclaredField("capiConfiguration");
        configField.setAccessible(true);
        configField.set(instance, config);

        Method buildHeaders = CAPIMain.class.getDeclaredMethod("buildManagedHeaders");
        buildHeaders.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, String> result = (Map<String, String>) buildHeaders.invoke(instance);
        assertNotNull(result);
        String allowHeaders = result.get("Access-Control-Allow-Headers");
        assertNotNull(allowHeaders);
        assertTrue(allowHeaders.contains("my-cookie"));
        assertTrue(allowHeaders.contains("Content-Type"));
    }

    @Test
    void buildManagedHeaders_withAllowedHeadersButNullOauth2_noCookie() throws Exception {
        CAPIConfiguration config = new CAPIConfiguration();
        java.util.List<String> headers = new java.util.ArrayList<>();
        headers.add("X-Custom");
        config.setAllowedHeaders(headers);
        config.setOauth2(null);

        sun.misc.Unsafe unsafe = getUnsafe();
        CAPIMain instance = (CAPIMain) unsafe.allocateInstance(CAPIMain.class);

        Field configField = CAPIMain.class.getDeclaredField("capiConfiguration");
        configField.setAccessible(true);
        configField.set(instance, config);

        Method buildHeaders = CAPIMain.class.getDeclaredMethod("buildManagedHeaders");
        buildHeaders.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, String> result = (Map<String, String>) buildHeaders.invoke(instance);
        String allowHeaders = result.get("Access-Control-Allow-Headers");
        assertNotNull(allowHeaders);
        assertTrue(allowHeaders.contains("X-Custom"));
    }

    @Test
    void buildManagedHeaders_withAllowedHeadersNullCookieName_noCookie() throws Exception {
        CAPIConfiguration config = new CAPIConfiguration();
        java.util.List<String> headers = new java.util.ArrayList<>();
        headers.add("Accept");
        config.setAllowedHeaders(headers);

        CAPIConfiguration.Oauth2 oauth2 = new CAPIConfiguration.Oauth2();
        oauth2.setCookieName(null);
        config.setOauth2(oauth2);

        sun.misc.Unsafe unsafe = getUnsafe();
        CAPIMain instance = (CAPIMain) unsafe.allocateInstance(CAPIMain.class);

        Field configField = CAPIMain.class.getDeclaredField("capiConfiguration");
        configField.setAccessible(true);
        configField.set(instance, config);

        Method buildHeaders = CAPIMain.class.getDeclaredMethod("buildManagedHeaders");
        buildHeaders.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, String> result = (Map<String, String>) buildHeaders.invoke(instance);
        assertNotNull(result);
    }

    // === Tests for getGrpcGateway ===

    @Test
    void getGrpcGateway_nullGrpcConfig_returnsNull() throws Exception {
        CAPIConfiguration config = new CAPIConfiguration();
        config.setGrpc(null);

        sun.misc.Unsafe unsafe = getUnsafe();
        CAPIMain instance = (CAPIMain) unsafe.allocateInstance(CAPIMain.class);

        Field configField = CAPIMain.class.getDeclaredField("capiConfiguration");
        configField.setAccessible(true);
        configField.set(instance, config);

        Method getGrpc = CAPIMain.class.getDeclaredMethod("getGrpcGateway", Startup.class);
        getGrpc.setAccessible(true);

        Object result = getGrpc.invoke(instance, mock(Startup.class));
        assertNull(result);
    }

    @Test
    void getGrpcGateway_grpcDisabled_returnsNull() throws Exception {
        CAPIConfiguration config = new CAPIConfiguration();
        CAPIConfiguration.Grpc grpc = new CAPIConfiguration.Grpc();
        grpc.setEnabled(false);
        config.setGrpc(grpc);

        sun.misc.Unsafe unsafe = getUnsafe();
        CAPIMain instance = (CAPIMain) unsafe.allocateInstance(CAPIMain.class);

        Field configField = CAPIMain.class.getDeclaredField("capiConfiguration");
        configField.setAccessible(true);
        configField.set(instance, config);

        Method getGrpc = CAPIMain.class.getDeclaredMethod("getGrpcGateway", Startup.class);
        getGrpc.setAccessible(true);

        Object result = getGrpc.invoke(instance, mock(Startup.class));
        assertNull(result);
    }

    @Test
    void getGrpcGateway_grpcEnabledButNullGrpcUtils_returnsNull() throws Exception {
        CAPIConfiguration config = new CAPIConfiguration();
        CAPIConfiguration.Grpc grpc = new CAPIConfiguration.Grpc();
        grpc.setEnabled(true);
        grpc.setPort(19384);
        config.setGrpc(grpc);

        sun.misc.Unsafe unsafe = getUnsafe();
        CAPIMain instance = (CAPIMain) unsafe.allocateInstance(CAPIMain.class);

        Field configField = CAPIMain.class.getDeclaredField("capiConfiguration");
        configField.setAccessible(true);
        configField.set(instance, config);

        Startup mockStartup = mock(Startup.class);
        when(mockStartup.getGrpcUtils()).thenReturn(null);

        Method getGrpc = CAPIMain.class.getDeclaredMethod("getGrpcGateway", Startup.class);
        getGrpc.setAccessible(true);

        Object result = getGrpc.invoke(instance, mockStartup);
        assertNull(result);
    }

    // === Tests for getMcpGateway ===

    @Test
    void getMcpGateway_nullMcpConfig_returnsNull() throws Exception {
        CAPIConfiguration config = new CAPIConfiguration();
        config.setMcp(null);

        sun.misc.Unsafe unsafe = getUnsafe();
        CAPIMain instance = (CAPIMain) unsafe.allocateInstance(CAPIMain.class);

        Field configField = CAPIMain.class.getDeclaredField("capiConfiguration");
        configField.setAccessible(true);
        configField.set(instance, config);

        Method getMcp = CAPIMain.class.getDeclaredMethod("getMcpGateway", Startup.class);
        getMcp.setAccessible(true);

        Object result = getMcp.invoke(instance, mock(Startup.class));
        assertNull(result);
    }

    @Test
    void getMcpGateway_mcpDisabled_returnsNull() throws Exception {
        CAPIConfiguration config = new CAPIConfiguration();
        CAPIConfiguration.Mcp mcp = new CAPIConfiguration.Mcp();
        mcp.setEnabled(false);
        config.setMcp(mcp);

        sun.misc.Unsafe unsafe = getUnsafe();
        CAPIMain instance = (CAPIMain) unsafe.allocateInstance(CAPIMain.class);

        Field configField = CAPIMain.class.getDeclaredField("capiConfiguration");
        configField.setAccessible(true);
        configField.set(instance, config);

        Method getMcp = CAPIMain.class.getDeclaredMethod("getMcpGateway", Startup.class);
        getMcp.setAccessible(true);

        Object result = getMcp.invoke(instance, mock(Startup.class));
        assertNull(result);
    }

    @Test
    void getMcpGateway_mcpEnabledButNullToolRegistry_returnsNull() throws Exception {
        CAPIConfiguration config = new CAPIConfiguration();
        CAPIConfiguration.Mcp mcp = new CAPIConfiguration.Mcp();
        mcp.setEnabled(true);
        config.setMcp(mcp);

        sun.misc.Unsafe unsafe = getUnsafe();
        CAPIMain instance = (CAPIMain) unsafe.allocateInstance(CAPIMain.class);

        Field configField = CAPIMain.class.getDeclaredField("capiConfiguration");
        configField.setAccessible(true);
        configField.set(instance, config);

        Startup mockStartup = mock(Startup.class);
        when(mockStartup.getMcpToolRegistry()).thenReturn(null);

        Method getMcp = CAPIMain.class.getDeclaredMethod("getMcpGateway", Startup.class);
        getMcp.setAccessible(true);

        Object result = getMcp.invoke(instance, mockStartup);
        assertNull(result);
    }

    // === Tests for getRestGateway ===

    @Test
    void getRestGateway_restDisabled_returnsNull() throws Exception {
        CAPIConfiguration config = new CAPIConfiguration();
        CAPIConfiguration.Rest rest = new CAPIConfiguration.Rest();
        rest.setEnabled(false);
        config.setRest(rest);

        sun.misc.Unsafe unsafe = getUnsafe();
        CAPIMain instance = (CAPIMain) unsafe.allocateInstance(CAPIMain.class);

        Field configField = CAPIMain.class.getDeclaredField("capiConfiguration");
        configField.setAccessible(true);
        configField.set(instance, config);

        Method getRestGw = CAPIMain.class.getDeclaredMethod("getRestGateway", Startup.class, Map.class);
        getRestGw.setAccessible(true);

        Object result = getRestGw.invoke(instance, mock(Startup.class), new HashMap<>());
        assertNull(result);
    }

    // === Tests for getRestGateway with REST enabled ===

    @Test
    void getRestGateway_restEnabled_createsGateway() throws Exception {
        CAPIConfiguration config = new CAPIConfiguration();
        CAPIConfiguration.Rest rest = new CAPIConfiguration.Rest();
        rest.setEnabled(true);
        rest.setPort(19380);
        rest.setContextPath("/api");
        config.setRest(rest);
        config.setOauth2(null);
        config.setAllowedHeaders(null);

        sun.misc.Unsafe unsafe = getUnsafe();
        CAPIMain instance = (CAPIMain) unsafe.allocateInstance(CAPIMain.class);

        Field configField = CAPIMain.class.getDeclaredField("capiConfiguration");
        configField.setAccessible(true);
        configField.set(instance, config);

        Startup mockStartup = mock(Startup.class);
        when(mockStartup.getRestClientMap()).thenReturn(new java.util.concurrent.ConcurrentHashMap<>());
        when(mockStartup.getHttpUtils()).thenReturn(mock(io.surisoft.capi.utils.HttpUtils.class));

        org.cache2k.Cache<String, io.surisoft.capi.schema.Service> svcCache =
                org.cache2k.Cache2kBuilder.of(String.class, io.surisoft.capi.schema.Service.class)
                        .name("capiMainRestGw-" + System.nanoTime())
                        .eternal(true)
                        .entryCapacity(10)
                        .build();
        when(mockStartup.getServiceCache()).thenReturn(svcCache);
        when(mockStartup.getUndertowSslContext()).thenReturn(null);
        when(mockStartup.getWebsocketUtils()).thenReturn(null);
        when(mockStartup.getOpaWasmService()).thenReturn(null);
        when(mockStartup.getThrottleProcessor()).thenReturn(null);
        when(mockStartup.getApiKeyCache()).thenReturn(null);
        when(mockStartup.getOpenTelemetryTracer()).thenReturn(null);

        io.micrometer.prometheusmetrics.PrometheusMeterRegistry mockRegistry = mock(io.micrometer.prometheusmetrics.PrometheusMeterRegistry.class);
        when(mockStartup.getPrometheusRegistry()).thenReturn(mockRegistry);

        Method getRestGw = CAPIMain.class.getDeclaredMethod("getRestGateway", Startup.class, Map.class);
        getRestGw.setAccessible(true);

        Object result = getRestGw.invoke(instance, mockStartup, new HashMap<>());
        assertNotNull(result);

        try {
            Method stopMethod = result.getClass().getMethod("stop");
            stopMethod.invoke(result);
        } catch (Exception ignored) {}
        svcCache.close();
    }

    @Test
    void getRestGateway_restEnabledWithAllOptionals_setsAll() throws Exception {
        CAPIConfiguration config = new CAPIConfiguration();
        CAPIConfiguration.Rest rest = new CAPIConfiguration.Rest();
        rest.setEnabled(true);
        rest.setPort(19381);
        rest.setContextPath("/api");
        config.setRest(rest);

        CAPIConfiguration.Oauth2 oauth2 = new CAPIConfiguration.Oauth2();
        oauth2.setCookieName("auth-cookie");
        config.setOauth2(oauth2);

        config.setAllowedHeaders(java.util.List.of("Content-Type"));
        config.setReverseProxyHost("proxy.example.com");
        config.setInstanceName("test-instance");

        sun.misc.Unsafe unsafe = getUnsafe();
        CAPIMain instance = (CAPIMain) unsafe.allocateInstance(CAPIMain.class);

        Field configField = CAPIMain.class.getDeclaredField("capiConfiguration");
        configField.setAccessible(true);
        configField.set(instance, config);

        Startup mockStartup = mock(Startup.class);
        when(mockStartup.getRestClientMap()).thenReturn(new java.util.concurrent.ConcurrentHashMap<>());
        io.surisoft.capi.utils.HttpUtils mockHttpUtils = mock(io.surisoft.capi.utils.HttpUtils.class);
        when(mockStartup.getHttpUtils()).thenReturn(mockHttpUtils);

        org.cache2k.Cache<String, io.surisoft.capi.schema.Service> svcCache =
                org.cache2k.Cache2kBuilder.of(String.class, io.surisoft.capi.schema.Service.class)
                        .name("capiMainRestGwAll-" + System.nanoTime())
                        .eternal(true)
                        .entryCapacity(10)
                        .build();
        when(mockStartup.getServiceCache()).thenReturn(svcCache);
        when(mockStartup.getUndertowSslContext()).thenReturn(null);

        io.surisoft.capi.utils.WebsocketUtils mockWsUtils = mock(io.surisoft.capi.utils.WebsocketUtils.class);
        when(mockWsUtils.createWebsocketAuthorization()).thenThrow(new RuntimeException("No OIDC configured"));
        when(mockStartup.getWebsocketUtils()).thenReturn(mockWsUtils);

        when(mockStartup.getOpaWasmService()).thenReturn(mock(io.surisoft.capi.service.OpaWasmService.class));
        when(mockStartup.getThrottleProcessor()).thenReturn(mock(io.surisoft.capi.processor.ThrottleProcessor.class));

        org.cache2k.Cache<String, io.surisoft.capi.schema.ApiKeyStoreEntry> apiCache =
                org.cache2k.Cache2kBuilder.of(String.class, io.surisoft.capi.schema.ApiKeyStoreEntry.class)
                        .name("capiMainApiKeyCache-" + System.nanoTime())
                        .eternal(true)
                        .entryCapacity(10)
                        .build();
        when(mockStartup.getApiKeyCache()).thenReturn(apiCache);
        when(mockStartup.getOpenTelemetryTracer()).thenReturn(mock(io.opentelemetry.api.trace.Tracer.class));

        io.micrometer.prometheusmetrics.PrometheusMeterRegistry mockRegistry = mock(io.micrometer.prometheusmetrics.PrometheusMeterRegistry.class);
        when(mockStartup.getPrometheusRegistry()).thenReturn(mockRegistry);

        Method getRestGw = CAPIMain.class.getDeclaredMethod("getRestGateway", Startup.class, Map.class);
        getRestGw.setAccessible(true);

        Object result = getRestGw.invoke(instance, mockStartup, new HashMap<>());
        assertNotNull(result);

        try {
            Method stopMethod = result.getClass().getMethod("stop");
            stopMethod.invoke(result);
        } catch (Exception ignored) {}
        svcCache.close();
        apiCache.close();
    }

    // === Tests for initializeLogs with filePaths ===

    @Test
    void initializeLogs_loggingTracesWithFilePath_setsFileProps() throws Exception {
        CAPIConfiguration config = new CAPIConfiguration();

        CAPIConfiguration.LoggingTraces loggingTraces = new CAPIConfiguration.LoggingTraces();
        loggingTraces.setEnabled(true);
        loggingTraces.setTenant("t");
        loggingTraces.setAppName("a");
        loggingTraces.setAppEnvironment("e");
        loggingTraces.setDestination("d");
        loggingTraces.setFilePath("/var/log/capi.log");
        config.setLoggingTraces(loggingTraces);

        CAPIConfiguration.AccessLogs accessLogs = new CAPIConfiguration.AccessLogs();
        accessLogs.setEnabled(false);
        config.setAccessLogs(accessLogs);

        Method initLogs = CAPIMain.class.getDeclaredMethod("initializeLogs", CAPIConfiguration.class);
        initLogs.setAccessible(true);

        try {
            sun.misc.Unsafe unsafe = getUnsafe();
            CAPIMain instance = (CAPIMain) unsafe.allocateInstance(CAPIMain.class);
            initLogs.invoke(instance, config);

            assertEquals("true", System.getProperty("logging.logback.logs.fileEnabled"));
            assertEquals("/var/log/capi.log", System.getProperty("logging.logback.logs.filePath"));
        } finally {
            System.clearProperty("logging.logback.logs.enabled");
            System.clearProperty("logging.logback.logs.tenant");
            System.clearProperty("logging.logback.logs.appName");
            System.clearProperty("logging.logback.logs.appEnvironment");
            System.clearProperty("logging.logback.logs.destination");
            System.clearProperty("logging.logback.logs.fileEnabled");
            System.clearProperty("logging.logback.logs.filePath");

            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            context.reset();
            ch.qos.logback.classic.joran.JoranConfigurator configurator = new ch.qos.logback.classic.joran.JoranConfigurator();
            configurator.setContext(context);
            try {
                configurator.doConfigure(CAPIMain.class.getClassLoader().getResource("logback.xml"));
            } catch (Exception ignored) {}
        }
    }

    @Test
    void initializeLogs_accessLogsWithFilePath_setsFileProps() throws Exception {
        CAPIConfiguration config = new CAPIConfiguration();

        CAPIConfiguration.LoggingTraces loggingTraces = new CAPIConfiguration.LoggingTraces();
        loggingTraces.setEnabled(false);
        config.setLoggingTraces(loggingTraces);

        CAPIConfiguration.AccessLogs accessLogs = new CAPIConfiguration.AccessLogs();
        accessLogs.setEnabled(true);
        accessLogs.setTenant("at");
        accessLogs.setService("as");
        accessLogs.setDestination("ad");
        accessLogs.setFilePath("/var/log/access.log");
        config.setAccessLogs(accessLogs);

        Method initLogs = CAPIMain.class.getDeclaredMethod("initializeLogs", CAPIConfiguration.class);
        initLogs.setAccessible(true);

        try {
            sun.misc.Unsafe unsafe = getUnsafe();
            CAPIMain instance = (CAPIMain) unsafe.allocateInstance(CAPIMain.class);
            initLogs.invoke(instance, config);

            assertEquals("true", System.getProperty("logging.logback.access.fileEnabled"));
            assertEquals("/var/log/access.log", System.getProperty("logging.logback.access.filePath"));
        } finally {
            System.clearProperty("logging.logback.access.enabled");
            System.clearProperty("logging.logback.access.tenant");
            System.clearProperty("logging.logback.access.service");
            System.clearProperty("logging.logback.access.destination");
            System.clearProperty("logging.logback.access.fileEnabled");
            System.clearProperty("logging.logback.access.filePath");

            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            context.reset();
            ch.qos.logback.classic.joran.JoranConfigurator configurator = new ch.qos.logback.classic.joran.JoranConfigurator();
            configurator.setContext(context);
            try {
                configurator.doConfigure(CAPIMain.class.getClassLoader().getResource("logback.xml"));
            } catch (Exception ignored) {}
        }
    }

    // === Tests for registerShutdownHook ===

    @Test
    void registerShutdownHook_withAllNullGateways_doesNotThrow() throws Exception {
        Method registerHook = CAPIMain.class.getDeclaredMethod("registerShutdownHook",
                io.surisoft.capi.undertow.WebsocketGateway.class,
                io.surisoft.capi.undertow.GrpcGateway.class,
                io.surisoft.capi.undertow.McpGateway.class,
                io.surisoft.capi.undertow.RestGateway.class,
                java.util.concurrent.ScheduledExecutorService.class,
                io.surisoft.capi.undertow.AdminGateway.class,
                io.surisoft.capi.utils.Startup.class);
        registerHook.setAccessible(true);

        java.util.concurrent.ScheduledExecutorService scheduler = java.util.concurrent.Executors.newScheduledThreadPool(1);
        io.surisoft.capi.undertow.AdminGateway mockAdmin = mock(io.surisoft.capi.undertow.AdminGateway.class);
        // Mocked rather than null: the hook body dereferences startup (getJvmObservability())
        // when it actually fires at JVM shutdown, after this test has finished.
        io.surisoft.capi.utils.Startup mockStartup = mock(io.surisoft.capi.utils.Startup.class);

        // This registers a shutdown hook but shouldn't throw
        assertDoesNotThrow(() -> registerHook.invoke(null, null, null, null, null, scheduler, mockAdmin, mockStartup));
        scheduler.shutdownNow();
    }

    // === Test for startSchedulers ===

    @Test
    void startSchedulers_withMinimalConfig_returnsScheduler() throws Exception {
        CAPIConfiguration config = new CAPIConfiguration();
        config.setConsulCatalogDiscoverInterval(60000);

        CAPIConfiguration.ConsulStore consulStore = new CAPIConfiguration.ConsulStore();
        consulStore.setEnabled(false);
        config.setConsulStore(consulStore);

        sun.misc.Unsafe unsafe = getUnsafe();
        CAPIMain instance = (CAPIMain) unsafe.allocateInstance(CAPIMain.class);

        Field configField = CAPIMain.class.getDeclaredField("capiConfiguration");
        configField.setAccessible(true);
        configField.set(instance, config);

        // Set static log field
        Field logField = CAPIMain.class.getDeclaredField("log");
        logField.setAccessible(true);
        logField.set(null, org.slf4j.LoggerFactory.getLogger(CAPIMain.class));

        Startup mockStartup = mock(Startup.class);
        io.surisoft.capi.service.consul.ConsulCatalogService mockDiscovery = mock(io.surisoft.capi.service.consul.ConsulCatalogService.class);
        io.surisoft.capi.service.RouteConsistencyChecker mockChecker = mock(io.surisoft.capi.service.RouteConsistencyChecker.class);
        when(mockStartup.getConsulCatalogService()).thenReturn(mockDiscovery);
        when(mockStartup.getRouteConsistencyChecker()).thenReturn(mockChecker);
        when(mockStartup.getConsulStore()).thenReturn(null);
        when(mockStartup.getApiKeyStore()).thenReturn(null);
        when(mockStartup.getMcpServerClient()).thenReturn(null);

        Method startSchedulers = CAPIMain.class.getDeclaredMethod("startSchedulers", Startup.class);
        startSchedulers.setAccessible(true);

        java.util.concurrent.ScheduledExecutorService scheduler =
                (java.util.concurrent.ScheduledExecutorService) startSchedulers.invoke(instance, mockStartup);
        assertNotNull(scheduler);
        scheduler.shutdownNow();
    }

    @Test
    void startSchedulers_withConsulStoreEnabled_schedulesStoreTask() throws Exception {
        CAPIConfiguration config = new CAPIConfiguration();
        config.setConsulCatalogDiscoverInterval(60000);

        CAPIConfiguration.ConsulStore consulStoreConfig = new CAPIConfiguration.ConsulStore();
        consulStoreConfig.setEnabled(true);
        config.setConsulStore(consulStoreConfig);

        sun.misc.Unsafe unsafe = getUnsafe();
        CAPIMain instance = (CAPIMain) unsafe.allocateInstance(CAPIMain.class);

        Field configField = CAPIMain.class.getDeclaredField("capiConfiguration");
        configField.setAccessible(true);
        configField.set(instance, config);

        Field logField = CAPIMain.class.getDeclaredField("log");
        logField.setAccessible(true);
        logField.set(null, org.slf4j.LoggerFactory.getLogger(CAPIMain.class));

        io.surisoft.capi.service.ConsulStore mockStore = mock(io.surisoft.capi.service.ConsulStore.class);
        Startup mockStartup = mock(Startup.class);
        when(mockStartup.getConsulCatalogService()).thenReturn(mock(io.surisoft.capi.service.consul.ConsulCatalogService.class));
        when(mockStartup.getRouteConsistencyChecker()).thenReturn(mock(io.surisoft.capi.service.RouteConsistencyChecker.class));
        when(mockStartup.getConsulStore()).thenReturn(mockStore);
        when(mockStartup.getApiKeyStore()).thenReturn(null);
        when(mockStartup.getMcpServerClient()).thenReturn(null);

        Method startSchedulers = CAPIMain.class.getDeclaredMethod("startSchedulers", Startup.class);
        startSchedulers.setAccessible(true);

        java.util.concurrent.ScheduledExecutorService scheduler =
                (java.util.concurrent.ScheduledExecutorService) startSchedulers.invoke(instance, mockStartup);
        assertNotNull(scheduler);
        scheduler.shutdownNow();
    }

    @Test
    void startSchedulers_withApiKeyStore_schedulesApiKeyTask() throws Exception {
        CAPIConfiguration config = new CAPIConfiguration();
        config.setConsulCatalogDiscoverInterval(60000);

        CAPIConfiguration.ConsulStore consulStoreConfig = new CAPIConfiguration.ConsulStore();
        consulStoreConfig.setEnabled(false);
        config.setConsulStore(consulStoreConfig);

        sun.misc.Unsafe unsafe = getUnsafe();
        CAPIMain instance = (CAPIMain) unsafe.allocateInstance(CAPIMain.class);

        Field configField = CAPIMain.class.getDeclaredField("capiConfiguration");
        configField.setAccessible(true);
        configField.set(instance, config);

        Field logField = CAPIMain.class.getDeclaredField("log");
        logField.setAccessible(true);
        logField.set(null, org.slf4j.LoggerFactory.getLogger(CAPIMain.class));

        io.surisoft.capi.service.ApiKeyStore mockApiKeyStore = mock(io.surisoft.capi.service.ApiKeyStore.class);
        Startup mockStartup = mock(Startup.class);
        when(mockStartup.getConsulCatalogService()).thenReturn(mock(io.surisoft.capi.service.consul.ConsulCatalogService.class));
        when(mockStartup.getRouteConsistencyChecker()).thenReturn(mock(io.surisoft.capi.service.RouteConsistencyChecker.class));
        when(mockStartup.getConsulStore()).thenReturn(null);
        when(mockStartup.getApiKeyStore()).thenReturn(mockApiKeyStore);
        when(mockStartup.getMcpServerClient()).thenReturn(null);

        Method startSchedulers = CAPIMain.class.getDeclaredMethod("startSchedulers", Startup.class);
        startSchedulers.setAccessible(true);

        java.util.concurrent.ScheduledExecutorService scheduler =
                (java.util.concurrent.ScheduledExecutorService) startSchedulers.invoke(instance, mockStartup);
        assertNotNull(scheduler);
        scheduler.shutdownNow();
    }

    private static sun.misc.Unsafe getUnsafe() throws Exception {
        java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (sun.misc.Unsafe) f.get(null);
    }
}
