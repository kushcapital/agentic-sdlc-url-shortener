package dev.rajeev.shortener.domain;

/**
 * Domain outcomes that are not success. The HTTP status for each lives in the web layer
 * ({@code ApiExceptionHandler}) as an exhaustive switch, so adding a code here without deciding
 * its status is a compile error, not a 500 in production.
 */
public enum ErrorCode {
    VALIDATION,
    INVALID_URL,
    URL_NOT_ALLOWED,
    ALIAS_TAKEN,
    ALIAS_RESERVED,
    NOT_FOUND,
    GONE,
    EXPIRED,
    CODE_EXHAUSTED,
    RATE_LIMITED
}
