package dev.rajeev.shortener.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Create request. Bean Validation annotations document the contract and fail fast at the edge;
 * {@link LinkService} re-validates explicitly so the rules hold for every caller (not only HTTP).
 */
public record CreateLinkRequest(
        @NotBlank @Size(max = 8192) String url,
        @Pattern(regexp = LinkRules.CUSTOM_ALIAS_REGEX, message = "alias must be 4-32 URL-safe characters") String customAlias,
        @Size(max = 128) String ownerId,
        /** Optional hard expiry (ISO-8601). Must be in the future at creation; enforced in LinkService. */
        Instant expiresAt) {}
