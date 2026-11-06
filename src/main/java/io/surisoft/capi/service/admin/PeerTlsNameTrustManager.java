package io.surisoft.capi.service.admin;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Verifies a peer's admin certificate against a fixed name ({@code admin.mcp.peerTlsName}) instead
 * of the address that was dialled.
 *
 * <p>Peers are reached by IP (pod IPs, which change on every restart). Standard HTTPS identity
 * checking matches an IP only against an iPAddress SAN for that exact IP — never a DNS SAN, never the
 * CN — so it can never pass for nodes that share one certificate issued to a DNS name. This trust
 * manager keeps everything else:
 * <ol>
 *   <li>the full chain is validated by the delegate (CAPI's trust store, or the JVM default), and</li>
 *   <li>the leaf must carry a SAN covering {@code peerTlsName} (exact, or a single-label
 *       {@code *.} wildcard).</li>
 * </ol>
 * Both happen during the handshake, so the caller's bearer token is never sent to an endpoint that
 * fails either check.
 *
 * <p>Being an {@link X509ExtendedTrustManager} matters: JSSE delegates identity checking to an
 * extended trust manager instead of wrapping it with its own hostname check, which is what lets the
 * IP check be replaced rather than added to. The delegate is called through its two-argument
 * {@code checkServerTrusted}, which validates the chain without an identity check.
 */
public final class PeerTlsNameTrustManager extends X509ExtendedTrustManager {

    private final X509TrustManager delegate;
    private final String expectedName;

    public PeerTlsNameTrustManager(X509TrustManager delegate, String expectedName) {
        if (expectedName == null || expectedName.isBlank()) {
            throw new IllegalArgumentException("peerTlsName must not be empty");
        }
        this.delegate = delegate;
        this.expectedName = expectedName.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        delegate.checkServerTrusted(chain, authType);
        if (chain == null || chain.length == 0 || !covers(chain[0], expectedName)) {
            throw new CertificateException("Peer admin certificate does not cover admin.mcp.peerTlsName '"
                    + expectedName + "' (its SANs: " + (chain == null || chain.length == 0 ? "none" : sans(chain[0])) + ")");
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        checkServerTrusted(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        checkServerTrusted(chain, authType);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        throw new CertificateException("Client-side trust manager only");
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        checkClientTrusted(chain, authType);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        checkClientTrusted(chain, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return delegate.getAcceptedIssuers();
    }

    /**
     * Whether a DNS SAN covers {@code name}: exact match, or a {@code *.rest} wildcard matching
     * exactly one leftmost label (RFC 6125). The CN is deliberately not consulted.
     */
    static boolean covers(X509Certificate certificate, String name) {
        String wanted = name.toLowerCase(Locale.ROOT);
        for (String san : dnsSans(certificate)) {
            String pattern = san.toLowerCase(Locale.ROOT);
            if (pattern.equals(wanted)) {
                return true;
            }
            if (pattern.startsWith("*.")) {
                String suffix = pattern.substring(1);              // ".example.com"
                int firstDot = wanted.indexOf('.');
                if (firstDot > 0 && wanted.substring(firstDot).equals(suffix)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> dnsSans(X509Certificate certificate) {
        List<String> names = new ArrayList<>();
        try {
            Collection<List<?>> sans = certificate.getSubjectAlternativeNames();
            if (sans != null) {
                for (List<?> san : sans) {
                    if (san.size() >= 2 && Integer.valueOf(2).equals(san.get(0)) && san.get(1) instanceof String dns) {
                        names.add(dns);
                    }
                }
            }
        } catch (CertificateParsingException e) {
            // Unparseable SANs cover nothing.
        }
        return names;
    }

    private static String sans(X509Certificate certificate) {
        List<String> names = dnsSans(certificate);
        return names.isEmpty() ? "no DNS SANs" : String.join(", ", names);
    }
}
