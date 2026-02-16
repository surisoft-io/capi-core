package io.surisoft.capi.metrics;

import io.surisoft.capi.schema.AliasInfo;
import io.surisoft.capi.service.CapiTrustManager;
import io.surisoft.capi.utils.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

public class Truststore {
    private static final Logger log = LoggerFactory.getLogger(Truststore.class);
    private final boolean capiTrustStoreEnabled;
    private final CapiTrustManager capiTrustManager;

    public Truststore(boolean capiTrustStoreEnabled, CapiTrustManager capiTrustManager) {
        this.capiTrustStoreEnabled = capiTrustStoreEnabled;
        this.capiTrustManager = capiTrustManager;
    }

    public List<AliasInfo> getTruststore() {
        List<AliasInfo> aliasList = new ArrayList<>();
        if(!capiTrustStoreEnabled) {
            AliasInfo aliasInfo = new AliasInfo();
            aliasInfo.setAdditionalInfo(Constants.NO_CUSTOM_TRUST_STORE_PROVIDED);
            aliasList.add(aliasInfo);
            return aliasList;
        }

        try {
            KeyStore keystore = capiTrustManager.getKeyStore();
            Enumeration<String> aliases = keystore.aliases();
            while(aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                AliasInfo aliasInfo = new AliasInfo();
                aliasInfo.setAlias(alias);
                X509Certificate certificate = (X509Certificate) keystore.getCertificate(alias);
                aliasInfo.setIssuerDN(certificate.getIssuerX500Principal().getName());
                aliasInfo.setSubjectDN(certificate.getSubjectX500Principal().getName());
                aliasInfo.setNotBefore(certificate.getNotBefore());
                aliasInfo.setNotAfter(certificate.getNotAfter());
                aliasList.add(aliasInfo);
            }
        } catch(Exception e) {
            log.error(e.getMessage(), e);
            return null;
        }
        return aliasList;
    }
}