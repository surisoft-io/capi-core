package io.surisoft.capi;

import java.lang.reflect.Constructor;

/**
 * Entry point for CAPI Gateway.
 * Sets system properties before any Camel class is loaded.
 * Camel's ThreadType reads "camel.threads.virtual.enabled" in its static initializer,
 * so the property must be set before CAPIMain (which imports Camel classes) is resolved.
 * Uses reflection to avoid the JVM resolving CAPIMain's imports at Bootstrap load time.
 */
public class Bootstrap {
    public static void main(String[] args) throws Exception {
        System.setProperty("camel.threads.virtual.enabled", "true");
        Constructor<?> constructor = Class.forName("io.surisoft.capi.CAPIMain").getDeclaredConstructor();
        constructor.newInstance();
    }
}