package dev.rajeev.shortener.analytics;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class ClickNormalizer {

    private record Family(Pattern pattern, String name) {}

    private static final List<Family> FAMILIES = List.of(
            new Family(Pattern.compile("curl/", Pattern.CASE_INSENSITIVE), "curl"),
            new Family(Pattern.compile("wget", Pattern.CASE_INSENSITIVE), "wget"),
            new Family(Pattern.compile("postman", Pattern.CASE_INSENSITIVE), "Postman"),
            new Family(Pattern.compile("java-http-client|java/", Pattern.CASE_INSENSITIVE), "Java"),
            new Family(Pattern.compile("bot|crawler|spider|slurp", Pattern.CASE_INSENSITIVE), "Bot"),
            new Family(Pattern.compile("edg/", Pattern.CASE_INSENSITIVE), "Edge"),
            new Family(Pattern.compile("opr/|opera", Pattern.CASE_INSENSITIVE), "Opera"),
            new Family(Pattern.compile("chrome/", Pattern.CASE_INSENSITIVE), "Chrome"),
            new Family(Pattern.compile("firefox/", Pattern.CASE_INSENSITIVE), "Firefox"),
            new Family(Pattern.compile("safari/", Pattern.CASE_INSENSITIVE), "Safari"));

    private ClickNormalizer() {}

    public static String referrer(String raw) {
        if (raw == null || raw.isBlank()) return "(direct)";
        try {
            String host = URI.create(raw.trim()).getHost();
            return host == null || host.isEmpty() ? "(invalid)" : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "(invalid)";
        }
    }

    public static String userAgent(String raw) {
        if (raw == null || raw.isBlank()) return "(unknown)";
        for (Family f : FAMILIES) {
            if (f.pattern().matcher(raw).find()) return f.name();
        }
        return "Other";
    }
}
