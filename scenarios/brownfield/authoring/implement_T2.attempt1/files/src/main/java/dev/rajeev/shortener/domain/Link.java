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
        /** Optional hard expiry. After this instant the link answers 410 EXPIRED; stats stay readable. */
        Instant expiresAt,
        boolean custom,
        String ownerId) {

    public boolean deleted() {
        return deletedAt != null;
    }

    public boolean expiredAt(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public Link withDeletedAt(Instant at) {
        return new Link(code, targetUrl, createdAt, at, expiresAt, custom, ownerId);
    }
}
