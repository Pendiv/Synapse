package dev.div.synapse.http;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Set;

/**
 * Pure request-guard predicates with no Minecraft/Forge dependencies, so the
 * security-critical parsing can be unit-tested in isolation. {@link SynapseHttpServer}
 * applies them; the rationale (anti DNS-rebinding / browser-CSRF, constant-time auth)
 * lives there.
 */
public final class RequestGuards {

    /** Loopback host names accepted in the Host/Origin guard (anti DNS-rebinding). */
    private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "localhost", "::1", "[::1]");

    private RequestGuards() {
    }

    /** True if {@code host} (a Host/authority value) names a loopback host on the expected port. */
    public static boolean hostAllowed(String host, int port) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        String h = host.trim();
        String name;
        String portStr;
        if (h.startsWith("[")) { // IPv6 literal, e.g. [::1]:25599
            int close = h.indexOf(']');
            if (close < 0) {
                return false;
            }
            name = h.substring(0, close + 1);
            String rest = h.substring(close + 1);
            portStr = rest.startsWith(":") ? rest.substring(1) : "";
        } else {
            int colon = h.lastIndexOf(':');
            if (colon >= 0) {
                name = h.substring(0, colon);
                portStr = h.substring(colon + 1);
            } else {
                name = h;
                portStr = "";
            }
        }
        if (portStr.isEmpty()) {
            return false; // our port is non-default, so a valid Host always carries it
        }
        try {
            if (Integer.parseInt(portStr) != port) {
                return false;
            }
        } catch (NumberFormatException e) {
            return false;
        }
        return LOOPBACK_HOSTS.contains(name.toLowerCase(Locale.ROOT));
    }

    /** True if {@code origin} is an http(s) localhost origin on the expected port. */
    public static boolean originAllowed(String origin, int port) {
        if (origin == null) {
            return false;
        }
        int sep = origin.indexOf("://");
        if (sep < 0) {
            return false;
        }
        String scheme = origin.substring(0, sep).toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return false;
        }
        return hostAllowed(origin.substring(sep + 3), port);
    }

    /** True if a {@code Sec-Fetch-Site} value marks a browser cross/same-site request to block. */
    public static boolean isBlockedSecFetchSite(String secFetchSite) {
        if (secFetchSite == null) {
            return false;
        }
        String s = secFetchSite.trim().toLowerCase(Locale.ROOT);
        return s.equals("cross-site") || s.equals("same-site");
    }

    /**
     * Constant-time, length-independent comparison: both inputs are SHA-256'd first so the
     * compare runs over equal-length digests and cannot leak the secret's length via timing.
     */
    public static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(sha256(a), sha256(b));
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // guaranteed present on every JVM
        }
    }
}
