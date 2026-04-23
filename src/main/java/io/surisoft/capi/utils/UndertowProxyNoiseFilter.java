package io.surisoft.capi.utils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.Marker;

import java.nio.channels.ClosedChannelException;

// Drops Undertow proxy ERRORs caused by ClosedChannelException (stale pool / upstream idle-close); other UT005028 causes stay visible.
public class UndertowProxyNoiseFilter extends TurboFilter {

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level,
                              String format, Object[] params, Throwable t) {
        if (level != Level.ERROR) {
            return FilterReply.NEUTRAL;
        }
        if (logger == null || !logger.getName().startsWith("io.undertow.proxy")) {
            return FilterReply.NEUTRAL;
        }
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof ClosedChannelException) {
                return FilterReply.DENY;
            }
        }
        return FilterReply.NEUTRAL;
    }
}