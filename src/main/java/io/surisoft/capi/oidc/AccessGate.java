package io.surisoft.capi.oidc;

import com.nimbusds.jwt.JWTClaimsSet;
import io.surisoft.capi.exception.AuthorizationException;
import io.surisoft.capi.schema.OpaResult;
import io.surisoft.capi.service.OpaWasmService;
import io.surisoft.capi.utils.HttpUtils;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import jakarta.annotation.Nullable;

import java.util.List;

/**
 * The one authorization gate for the REST, WebSocket and gRPC listeners (WebDAV has its own).
 *
 * <p>Two questions, kept apart so each transport asks them the same way:
 *
 * <ol>
 *   <li>{@link #requiresAuthorization} — does this call need authorizing at all?
 *     <ul>
 *       <li>{@code secured: true}: every call. The OpenAPI spec's security requirements are ignored.</li>
 *       <li>{@code secured: false} with an OpenAPI spec: only operations that declare a security
 *           requirement, their own or the spec's global one. {@code security: []}, or a requirement
 *           list containing an empty object, means anonymous.</li>
 *       <li>{@code secured: false} without a spec but with {@code opa-rego}: every call. The owner
 *           declared a policy; dropping it because {@code secured} was left false would turn a
 *           misconfiguration into an open service.</li>
 *       <li>Otherwise: anonymous.</li>
 *     </ul></li>
 *   <li>{@link #authorize} — is this caller allowed? A verified token (signature and expiry), and
 *       then <em>every</em> rule the service declares: the {@code subscription-group} (the token's
 *       {@code subscriptions} claim) and the {@code opa-rego} policy. A service declaring neither
 *       is refused: there is nothing to authorize against.</li>
 * </ol>
 *
 * <p>An OPA policy that cannot be evaluated never falls through to a weaker check: OPA disabled or
 * a policy missing from the bundle is a refusal, a bundle not loaded yet is
 * {@link Outcome#POLICY_UNAVAILABLE} (transient — the transports answer 503 / UNAVAILABLE).
 */
public final class AccessGate {

    public enum Outcome {
        ALLOWED,
        /** No token, or a token that does not verify. */
        UNAUTHENTICATED,
        /** A verified token that the service's rules refuse, or a service with no rules. */
        FORBIDDEN,
        /** The OPA engine has not loaded a bundle yet. Transient. */
        POLICY_UNAVAILABLE
    }

    /** The verdict, why, and — when a token was verified — its claims. */
    public record Decision(Outcome outcome, String message, @Nullable JWTClaimsSet claims) {
        public boolean allowed() {
            return outcome == Outcome.ALLOWED;
        }
    }

    static final String NO_RULE = "No authorization rule declared";
    static final String NO_TOKEN = "No authorization provided";
    static final String INVALID_TOKEN = "Invalid authentication";
    static final String NOT_SUBSCRIBED = "Not subscribed";
    static final String OPA_DISABLED = "Policy engine not enabled";
    static final String OPA_NOT_READY = "Policy engine not ready";
    static final String OPA_UNKNOWN = "Unknown policy: ";
    static final String OPA_DENIED = "Access denied by policy";

    private final HttpUtils httpUtils;
    private final OpaWasmService opaWasmService;

    public AccessGate(@Nullable HttpUtils httpUtils, @Nullable OpaWasmService opaWasmService) {
        this.httpUtils = httpUtils;
        this.opaWasmService = opaWasmService;
    }

    /**
     * Whether a call needs authorizing.
     *
     * @param openApiDeclared the service declares an OpenAPI spec ({@code open-api} meta)
     * @param operationSecured for a declared spec, whether the matched operation requires security
     *                         (see {@link #operationRequiresSecurity}); ignored otherwise
     */
    public static boolean requiresAuthorization(boolean secured, boolean openApiDeclared,
                                                boolean operationSecured, @Nullable String opaRego) {
        if (secured) {
            return true;
        }
        if (openApiDeclared) {
            return operationSecured;
        }
        return opaRego != null;
    }

    /**
     * Whether an OpenAPI operation requires security: its own {@code security}, or when it declares
     * none, the spec's global one. An empty list, or a list containing an empty requirement object
     * (OpenAPI's "authentication optional"), means anonymous.
     */
    public static boolean operationRequiresSecurity(@Nullable OpenAPI spec, Operation operation) {
        List<SecurityRequirement> requirements = operation.getSecurity();
        if (requirements == null && spec != null) {
            requirements = spec.getSecurity();
        }
        if (requirements == null || requirements.isEmpty()) {
            return false;
        }
        for (SecurityRequirement requirement : requirements) {
            if (requirement == null || requirement.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Authorize a call that {@link #requiresAuthorization requires it}.
     *
     * @param serviceId the canonical service id, handed to OPA as input
     */
    public Decision authorize(String serviceId, @Nullable String subscriptionGroup, @Nullable String opaRego,
                              @Nullable String accessToken) {
        if (isBlank(subscriptionGroup) && isBlank(opaRego)) {
            return new Decision(Outcome.FORBIDDEN, NO_RULE, null);
        }
        if (isBlank(accessToken)) {
            return new Decision(Outcome.UNAUTHENTICATED, NO_TOKEN, null);
        }
        JWTClaimsSet claims = verify(accessToken);
        if (claims == null) {
            return new Decision(Outcome.UNAUTHENTICATED, INVALID_TOKEN, null);
        }
        if (!isBlank(subscriptionGroup) && !httpUtils.isInSubscriptionGroup(claims, subscriptionGroup)) {
            return new Decision(Outcome.FORBIDDEN, NOT_SUBSCRIBED, claims);
        }
        if (!isBlank(opaRego)) {
            if (opaWasmService == null) {
                return new Decision(Outcome.FORBIDDEN, OPA_DISABLED, claims);
            }
            if (!opaWasmService.isReady()) {
                return new Decision(Outcome.POLICY_UNAVAILABLE, OPA_NOT_READY, claims);
            }
            if (!opaWasmService.hasPolicy(opaRego)) {
                return new Decision(Outcome.FORBIDDEN, OPA_UNKNOWN + opaRego, claims);
            }
            OpaResult result = opaWasmService.evaluate(serviceId, opaRego, accessToken, true);
            if (result == null || !result.isAllowed()) {
                return new Decision(Outcome.FORBIDDEN, OPA_DENIED, claims);
            }
        }
        return new Decision(Outcome.ALLOWED, null, claims);
    }

    /** Signature, expiry and the other checks of the configured JWT processors; null when none passes or none is configured. */
    private JWTClaimsSet verify(String accessToken) {
        if (httpUtils == null) {
            return null;
        }
        try {
            return httpUtils.authorizeRequest(accessToken);
        } catch (AuthorizationException e) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
