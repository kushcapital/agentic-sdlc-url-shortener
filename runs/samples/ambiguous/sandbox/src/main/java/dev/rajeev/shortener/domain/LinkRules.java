package dev.rajeev.shortener.domain;

import java.util.Set;
import java.util.regex.Pattern;

/** Shared validation constants for codes and aliases. */
public final class LinkRules {

    /**
     * Custom aliases at creation time: 6-32 URL-safe characters. The minimum was raised from 4 because
     * short aliases ("sale1") are trivially guessable.
     */
    public static final String CUSTOM_ALIAS_REGEX = "^[A-Za-z0-9_-]{6,32}$";
    public static final Pattern CUSTOM_ALIAS_PATTERN = Pattern.compile(CUSTOM_ALIAS_REGEX);

    /**
     * What a code may look like in a URL path (used by the controllers' path templates). Deliberately
     * wider than the creation rule (4-32) so every code ever issued — legacy 4/5-char aliases, 7-char
     * generated codes — keeps resolving. Tightening this would be a breaking change.
     */
    public static final String CODE_PATH = "[A-Za-z0-9_-]{4,32}";
    public static final Pattern CODE_PATH_PATTERN = Pattern.compile("^" + CODE_PATH + "$");

    /** First path segments a custom alias must never shadow. */
    public static final Set<String> RESERVED_CODES = Set.of("api", "actuator", "health", "ready", "metrics", "docs", "openapi", "error");

    private LinkRules() {}
}
