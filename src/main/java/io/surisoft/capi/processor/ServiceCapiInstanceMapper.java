package io.surisoft.capi.processor;

import io.surisoft.capi.schema.ServiceCapiInstances;

import java.util.Map;

public class ServiceCapiInstanceMapper {

    public static final String SERVICE_CAPI_INSTANCE_PREFIX = "capi-instance-";

    /**
     * Splits {@code capi-instance-<instance>-<property>} into instance and property, or returns
     * null when the key does not have that shape: no hyphen after the instance name
     * ({@code capi-instance-default}), or an empty instance or property. The split is at the first
     * hyphen, so an instance name cannot contain one.
     */
    public static String[] split(String key) {
        if (key == null || !key.startsWith(SERVICE_CAPI_INSTANCE_PREFIX)) {
            return null;
        }
        String remainder = key.substring(SERVICE_CAPI_INSTANCE_PREFIX.length());
        int idx = remainder.indexOf('-');
        if (idx <= 0 || idx == remainder.length() - 1) {
            return null;
        }
        return new String[]{remainder.substring(0, idx), remainder.substring(idx + 1)};
    }

    /** A {@code capi-instance-} key that {@link #split} cannot read. */
    public static boolean isMalformed(String key) {
        return key != null && key.startsWith(SERVICE_CAPI_INSTANCE_PREFIX) && split(key) == null;
    }

    /**
     * Reads the well-formed {@code capi-instance-<instance>-<property>} keys. A malformed key is
     * skipped here, never thrown on: one bad key used to throw out of discovery and fail the whole
     * service lookup on every node. It still counts for the instance allowlist
     * ({@code ServiceUtils.isTheServiceRegisteredForOtherInstances}), so a key that was meant to
     * restrict the service does not silently open it to every instance — and discovery reports it
     * under /info/invalid-services.
     */
    public ServiceCapiInstances convert(Map<String, String> flat) {
        ServiceCapiInstances result = new ServiceCapiInstances();

        flat.forEach((key, value) -> {
            String[] parts = split(key);
            if (parts == null) return;

            ServiceCapiInstances.Instance instance =
                    result.getInstances().computeIfAbsent(parts[0], s -> new ServiceCapiInstances.Instance());

            applyProperty(instance, parts[1], value);
        });

        return result;
    }

    private void applyProperty(ServiceCapiInstances.Instance instance, String prop, String value) {
        switch (prop) {
            case "secured":
                instance.setSecured(Boolean.parseBoolean(value));
                instance.setAssumeParentSecured(false);
                break;
            case "open-api":
                instance.setOpenApi(value);
                break;
            case "route-group-first":
                instance.setRouteGroupFirst(Boolean.parseBoolean(value));
                instance.setAssumeParentRouteGroupFirst(false);
                break;
            case "scheme":
                instance.setScheme(value);
                break;
            case "ignore-open-api":
                instance.setIgnoreOpenApi(Boolean.parseBoolean(value));
                break;
            case "ingress":
                instance.setIngress(value);
                break;
            case "opa-rego":
                instance.setOpaRego(value);
                break;
        }
    }
}
