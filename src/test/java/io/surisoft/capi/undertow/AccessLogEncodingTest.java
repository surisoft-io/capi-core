package io.surisoft.capi.undertow;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static net.logstash.logback.argument.StructuredArguments.v;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the access-log wire format: the JSON that LogstashEncoder actually ships to
 * Logstash/Data Prepper/OpenSearch.
 *
 * <p>{@code RestGatewayTest} asserts the fields are attached to the logging event; this asserts
 * they survive serialisation, which is what dashboards query. Without it, a change to the encoder
 * config (for example dropping {@code includeStructuredArguments}) would leave every unit test
 * green while silently reverting the fields to an unparsed {@code message} string.
 */
class AccessLogEncodingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Logger accessLogger;
    private ListAppender<ILoggingEvent> captured;

    @BeforeEach
    void setUp() {
        accessLogger = (Logger) LoggerFactory.getLogger("capi.access.encodingtest");
        captured = new ListAppender<>();
        captured.start();
        accessLogger.addAppender(captured);
    }

    @AfterEach
    void tearDown() {
        accessLogger.detachAppender(captured);
    }

    private JsonNode encodeAccessLine() throws Exception {
        accessLogger.info("{} {} {} {}ms {}",
                v("http_method", "GET"),
                v("http_path", "/capi/cc-prod/eac-pmm-ors-data-backend/organisations/list"),
                v("http_status", 200),
                v("duration_ms", 29L),
                v("client_ip", "79.191.139.68"));

        LogstashEncoder encoder = new LogstashEncoder();
        encoder.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        // Mirrors the customFields on the ACCESS-LOGSTASH appender in logback.xml.
        encoder.setCustomFields("{\"log_type\":\"access\",\"service\":\"capi-instance-external\"}");
        encoder.start();
        return MAPPER.readTree(encoder.encode(captured.list.get(0)));
    }

    @Test
    void eachAccessLogPartIsItsOwnJsonField() throws Exception {
        JsonNode json = encodeAccessLine();

        assertEquals("GET", json.path("http_method").asText());
        assertEquals("/capi/cc-prod/eac-pmm-ors-data-backend/organisations/list",
                json.path("http_path").asText());
        assertEquals(200, json.path("http_status").asInt());
        assertEquals(29, json.path("duration_ms").asLong());
        assertEquals("79.191.139.68", json.path("client_ip").asText());
    }

    @Test
    void statusAndDurationAreNumericSoDashboardsCanAggregate() throws Exception {
        JsonNode json = encodeAccessLine();

        assertTrue(json.path("http_status").isNumber(), "http_status must not be a string");
        assertTrue(json.path("duration_ms").isNumber(), "duration_ms must not be a string");
    }

    @Test
    void messageIsRetainedUnchangedForExistingConsumers() throws Exception {
        JsonNode json = encodeAccessLine();

        assertEquals("GET /capi/cc-prod/eac-pmm-ors-data-backend/organisations/list 200 29ms 79.191.139.68",
                json.path("message").asText());
    }

    @Test
    void customFieldsStillPresentAlongsideTheNewOnes() throws Exception {
        JsonNode json = encodeAccessLine();

        assertEquals("access", json.path("log_type").asText());
        assertEquals("capi-instance-external", json.path("service").asText());
    }
}
