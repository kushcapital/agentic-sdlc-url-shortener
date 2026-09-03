package dev.rajeev.shortener.domain;

import java.time.Instant;

/**
 * A short link. {@code code} is the public identifier: either generated (base62, see
 * {@link RandomCodeGenerator}) or a user-supplied custom alias. Soft-deleted links keep their
 * row so a code is never reissued and analytics remain readable.
 */
public record Link(
        String code,
        String targetUrl,
        Instant createdAt,
        Instant deletedAt,
        boolean custom,
        String ownerId) {

    public boolean deleted() {
        return deletedAt != null;
    }

    public Link withDeletedAt(Instant at) {
        return new Link(code, targetUrl, createdAt, at, custom, ownerId);
    }
}
