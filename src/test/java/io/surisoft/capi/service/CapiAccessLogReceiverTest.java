package io.surisoft.capi.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class CapiAccessLogReceiverTest {

    @Test
    void logMessage_doesNotThrow() {
        CapiAccessLogReceiver receiver = new CapiAccessLogReceiver();
        assertDoesNotThrow(() -> receiver.logMessage("GET /api/test 200 15ms"));
    }

    @Test
    void logMessage_withNullMessage_doesNotThrow() {
        CapiAccessLogReceiver receiver = new CapiAccessLogReceiver();
        assertDoesNotThrow(() -> receiver.logMessage(null));
    }

    @Test
    void logMessage_withEmptyMessage_doesNotThrow() {
        CapiAccessLogReceiver receiver = new CapiAccessLogReceiver();
        assertDoesNotThrow(() -> receiver.logMessage(""));
    }
}
