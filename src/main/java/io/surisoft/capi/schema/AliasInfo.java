package io.surisoft.capi.schema;

//import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Date;

//@Schema(hidden = true)
public class AliasInfo {

    private String alias;
    private String issuerDN;
    private String subjectDN;
    private String additionalInfo;
    private String serviceId;
    private Date notBefore;
    private Date notAfter;
    /** When the alias was added to the trust store (KeyStore creation date); null when the store type does not keep it. */
    private Date addedAt;
    /** DNS subjectAlternativeNames of the certificate. */
    private java.util.List<String> dnsNames;

    public Date getAddedAt() {
        return addedAt;
    }

    public void setAddedAt(Date addedAt) {
        this.addedAt = addedAt;
    }

    public java.util.List<String> getDnsNames() {
        return dnsNames;
    }

    public void setDnsNames(java.util.List<String> dnsNames) {
        this.dnsNames = dnsNames;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public String getIssuerDN() {
        return issuerDN;
    }

    public void setIssuerDN(String issuerDN) {
        this.issuerDN = issuerDN;
    }

    public String getSubjectDN() {
        return subjectDN;
    }

    public void setSubjectDN(String subjectDN) {
        this.subjectDN = subjectDN;
    }

    public String getAdditionalInfo() {
        return additionalInfo;
    }

    public void setAdditionalInfo(String additionalInfo) {
        this.additionalInfo = additionalInfo;
    }

    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }

    public Date getNotBefore() {
        return notBefore;
    }

    public void setNotBefore(Date notBefore) {
        this.notBefore = notBefore;
    }

    public Date getNotAfter() {
        return notAfter;
    }

    public void setNotAfter(Date notAfter) {
        this.notAfter = notAfter;
    }
}