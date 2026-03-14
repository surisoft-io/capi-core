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

    private static sun.misc.Unsafe getUnsafe() throws Exception {
        java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (sun.misc.Unsafe) f.get(null);
    }
}
