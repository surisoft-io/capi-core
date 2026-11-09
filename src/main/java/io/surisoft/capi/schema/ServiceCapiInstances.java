package io.surisoft.capi.schema;

import java.util.HashMap;
import java.util.Map;

public class ServiceCapiInstances {

    private Map<String, Instance> instances = new HashMap<>();

    public static class Instance {
        private boolean secured;
        private String openApi;
        private boolean routeGroupFirst;
        private String scheme;
        private boolean ignoreOpenApi;
        private String ingress;
        private String opaRego;
        private boolean opaRegoOverridden;

        private boolean assumeParentSecured = true;
        private boolean assumeParentRouteGroupFirst = true;

        public boolean isSecured() {
            return secured;
        }

        public void setSecured(boolean secured) {
            this.secured = secured;
        }

        public String getOpenApi() {
            return openApi;
        }

        public void setOpenApi(String openApi) {
            this.openApi = openApi;
        }

        public boolean isRouteGroupFirst() {
            return routeGroupFirst;
        }

        public void setRouteGroupFirst(boolean routeGroupFirst) {
            this.routeGroupFirst = routeGroupFirst;
        }

        public String getScheme() {
            return scheme;
        }

        public void setScheme(String scheme) {
            this.scheme = scheme;
        }

        public boolean isIgnoreOpenApi() {
            return ignoreOpenApi;
        }

        public void setIgnoreOpenApi(boolean ignoreOpenApi) {
            this.ignoreOpenApi = ignoreOpenApi;
        }

        public String getIngress() {
            return ingress;
        }

        public void setIngress(String ingress) {
            this.ingress = ingress;
        }

        /** This instance's policy; {@code null} means no policy. Only meaningful when {@link #isOpaRegoOverridden()}. */
        public String getOpaRego() {
            return opaRego;
        }

        /**
         * A blank value is stored as {@code null}: an explicit "no policy on this instance", so the
         * service-level {@code opa-rego} is removed here rather than half-applied.
         */
        public void setOpaRego(String opaRego) {
            this.opaRego = (opaRego == null || opaRego.isBlank()) ? null : opaRego.trim();
            this.opaRegoOverridden = true;
        }

        /** Whether {@code capi-instance-<name>-opa-rego} was declared; otherwise the service-level value applies. */
        public boolean isOpaRegoOverridden() {
            return opaRegoOverridden;
        }

        public boolean isAssumeParentSecured() {
            return assumeParentSecured;
        }

        public void setAssumeParentSecured(boolean assumeParentSecured) {
            this.assumeParentSecured = assumeParentSecured;
        }

        public boolean isAssumeParentRouteGroupFirst() {
            return assumeParentRouteGroupFirst;
        }

        public void setAssumeParentRouteGroupFirst(boolean assumeParentRouteGroupFirst) {
            this.assumeParentRouteGroupFirst = assumeParentRouteGroupFirst;
        }


    }

    public Map<String, Instance> getInstances() {
        return instances;
    }

    public void setSections(Map<String, Instance> instances) {
        this.instances = instances;
    }
}
