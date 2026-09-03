package dev.rajeev.shortener.domain;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Target-URL policy. A shortener is an open redirector by design, so this is where abuse is contained:
 * <ol>
 *   <li>Scheme allowlist: only http/https. javascript:/data: would turn the redirect into an XSS vector.</li>
 *   <li>No credentials in the URL.</li>
 *   <li>Private-network guard: loopback, link-local, RFC1918, CGNAT, metadata hosts. Closes the SSRF
 *       door before any server-side component ever follows a link.</li>
 *   <li>Self-reference guard: links may not point back at this service (redirect loops).</li>
 *   <li>Length cap: bounds storage and log size.</li>
 * </ol>
 * The result is a sealed type rather than an exception so callers map to HTTP without string matching.
 */
public class UrlPolicy {

    public sealed interface Result permits Ok, Rejected {}

    public record Ok(String normalized) implements Result {}

    public record Rejected(ErrorCode code, String message) implements Result {}

    private static final Set<String> BLOCKED_HOSTNAMES = Set.of("localhost", "localhost.localdomain", "metadata.google.internal");

    private final int maxLength;
    private final List<String> selfHosts;
    private final boolean allowPrivateNetworks;

    public UrlPolicy(int maxLength, List<String> selfHosts, boolean allowPrivateNetworks) {
        this.maxLength = maxLength;
        this.selfHosts = selfHosts.stream().map(h -> h.toLowerCase(Locale.ROOT)).toList();
        this.allowPrivateNetworks = allowPrivateNetworks;
    }

    public Result evaluate(String raw) {
        if (raw == null || raw.isBlank()) return new Rejected(ErrorCode.INVALID_URL, "url is required");
        if (raw.length() > maxLength) return new Rejected(ErrorCode.INVALID_URL, "url exceeds " + maxLength + " characters");
        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException e) {
            return new Rejected(ErrorCode.INVALID_URL, "url is not a valid absolute URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.isEmpty()) {
            return new Rejected(ErrorCode.INVALID_URL, "url is not a valid absolute URL");
        }
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return new Rejected(ErrorCode.URL_NOT_ALLOWED, "only http and https URLs are allowed");
        }
        if (uri.getRawAuthority() == null) {
            return new Rejected(ErrorCode.INVALID_URL, "url is not a valid absolute URL");
        }
        if (uri.getRawUserInfo() != null) {
            return new Rejected(ErrorCode.URL_NOT_ALLOWED, "credentials in URLs are not allowed");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) return new Rejected(ErrorCode.INVALID_URL, "url has no host");
        if (!allowPrivateNetworks) {
            if (BLOCKED_HOSTNAMES.contains(host) || host.endsWith(".localhost") || host.endsWith(".internal")) {
                return new Rejected(ErrorCode.URL_NOT_ALLOWED, "private or internal hosts are not allowed");
            }
            String bare = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
            if (isIpLiteral(bare) && isPrivateAddress(bare)) {
                return new Rejected(ErrorCode.URL_NOT_ALLOWED, "private network addresses are not allowed");
            }
        }
        String authority = uri.getAuthority().toLowerCase(Locale.ROOT);
        if (selfHosts.contains(authority)) {
            return new Rejected(ErrorCode.URL_NOT_ALLOWED, "links may not point back at this service");
        }
        String normalized = uri.normalize().toString();
        if (uri.getRawPath() == null || uri.getRawPath().isEmpty()) {
            // "https://example.com" -> "https://example.com/" so equal targets compare equal.
            normalized = scheme + "://" + authority + "/" + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "") + (uri.getRawFragment() != null ? "#" + uri.getRawFragment() : "");
        }
        return new Ok(normalized);
    }

    static boolean isIpLiteral(String host) {
        return host.chars().allMatch(c -> Character.isDigit(c) || c == '.') || host.contains(":");
    }

    /** No DNS is ever performed: only literal addresses are classified. */
    static boolean isPrivateAddress(String literal) {
        try {
            InetAddress addr = InetAddress.getByName(literal); // literal only: never resolves a name (guarded by isIpLiteral)
            if (addr.isLoopbackAddress() || addr.isAnyLocalAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()) return true;
            byte[] b = addr.getAddress();
            if (b.length == 4) {
                int a0 = b[0] & 0xFF, a1 = b[1] & 0xFF;
                return a0 == 100 && a1 >= 64 && a1 <= 127; // CGNAT 100.64.0.0/10
            }
            int a0 = b[0] & 0xFF;
            return a0 == 0xFC || a0 == 0xFD; // IPv6 unique local fc00::/7
        } catch (Exception e) {
            return false;
        }
    }
}
