package io.surisoft.capi.metrics;

import io.surisoft.capi.schema.AliasInfo;
import io.surisoft.capi.service.CapiTrustManager;
import io.surisoft.capi.utils.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
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
                aliasInfo.setDnsNames(dnsNames(certificate));
                aliasInfo.setAddedAt(addedAt(keystore, alias, capiTrustManager.getLoadedAt()));
                aliasList.add(aliasInfo);
            }
        } catch(Exception e) {
            log.error(e.getMessage(), e);
            return null;
        }
        return aliasList;
    }

    /**
     * When the alias entered the store — not the certificate's issue date — or null when unknown. A
     * JKS file keeps it; a PKCS12 file does not and Java answers with the load time, which would make
     * every alias look freshly added. Verified with real files: only a date before the load is real.
     */
    static Date addedAt(KeyStore keystore, String alias, Date loadedAt) {
        try {
            Date created = keystore.getCreationDate(alias);
            return created != null && loadedAt != null && created.before(loadedAt) ? created : null;
        } catch (Exception e) {
            return null;
        }
    }

    static List<String> dnsNames(X509Certificate certificate) {
        List<String> names = new ArrayList<>();
        try {
            java.util.Collection<List<?>> sans = certificate.getSubjectAlternativeNames();
            if (sans != null) {
                for (List<?> san : sans) {
                    if (san.size() >= 2 && Integer.valueOf(2).equals(san.get(0)) && san.get(1) instanceof String dns) {
                        names.add(dns);
                    }
                }
            }
        } catch (java.security.cert.CertificateParsingException e) {
            // Unparseable SANs: report none.
        }
        return names;
    }

    /**
     * The certificates whose {@code notAfter} is before {@code now}: candidates for removal, since an
     * expired certificate can no longer validate a backend. Null when the trust store cannot be read,
     * as with {@link #getTruststore()}.
     */
    public List<AliasInfo> getExpired(Date now) {
        return filterByExpiry(now, true);
    }

    /**
     * The complement of {@link #getExpired(Date)}: certificates not yet past {@code notAfter}. A
     * certificate whose {@code notBefore} is still in the future is included, as it has not expired.
     */
    public List<AliasInfo> getNotExpired(Date now) {
        return filterByExpiry(now, false);
    }

    private List<AliasInfo> filterByExpiry(Date now, boolean expired) {
        List<AliasInfo> aliasList = getTruststore();
        if(aliasList == null) {
            return null;
        }
        return aliasList.stream()
                .filter(aliasInfo -> aliasInfo.getNotAfter() != null && aliasInfo.getNotAfter().before(now) == expired)
                .toList();
    }
}
