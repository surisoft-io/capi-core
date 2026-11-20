package io.surisoft.capi.utils;

import io.undertow.UndertowOptions;
import org.xnio.OptionMap;

/**
 * Backend connections check that the certificate was issued for the host CAPI dialled.
 *
 * <p>Undertow's HTTP and HTTP/2 client providers already do this by default, but they drop it when
 * the JVM runs with {@code -Dio.undertow.client.https.disableEndpointIdentification=true}. CAPI's
 * options are applied after Undertow's defaults, so setting the algorithm here keeps the check on
 * whatever the JVM flags or a future Undertow default say. There is deliberately no switch to turn
 * it off: a backend whose certificate does not name its address must get a certificate that does.
 */
public final class BackendTls {

    private BackendTls() {
    }

    /** {@code base} plus hostname verification. Only HTTPS connections use the option. */
    public static OptionMap withHostnameVerification(OptionMap base) {
        return OptionMap.builder().addAll(base).set(UndertowOptions.ENDPOINT_IDENTIFICATION_ALGORITHM, "HTTPS").getMap();
    }
}
