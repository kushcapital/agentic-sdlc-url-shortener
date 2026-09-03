package dev.rajeev.shortener.domain;

import java.util.Set;
import java.util.regex.Pattern;

/** Shared validation constants for codes and aliases. */
public final class LinkRules {

    /** Custom aliases: 4-32 URL-safe characters. */
    public static final String CUSTOM_ALIAS_REGEX = "^[A-Za-z0-9_-]{4,32}$";
    public static final Pattern CUSTOM_ALIAS_PATTERN = Pattern.compile(CUSTOM_ALIAS_REGEX);

    /** First path segments a custom alias must never shadow. */
    public static final Set<String> RESERVED_CODES = Set.of("api", "actuator", "health", "ready", "metrics", "docs", "openapi", "error");

    private LinkRules() {}
}
