package io.surisoft.capi.configuration;

import javax.net.ssl.SSLContext;

public class CapiSslContextHolder {
    private volatile SSLContext sslContext;
    public CapiSslContextHolder(SSLContext sslContext) {
        this.sslContext = sslContext;
    }
    public SSLContext getSslContext() {
        return sslContext;
    }
    public void setSslContext(SSLContext sslContext) {
        this.sslContext = sslContext;
    }
}
