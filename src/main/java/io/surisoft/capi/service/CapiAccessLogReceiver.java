package io.surisoft.capi.service;

import io.undertow.server.handlers.accesslog.AccessLogReceiver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CapiAccessLogReceiver implements AccessLogReceiver {

    private static final Logger ACCESS_LOG = LoggerFactory.getLogger("capi.access");

    @Override
    public void logMessage(String message) {
        ACCESS_LOG.info(message);
    }
}
