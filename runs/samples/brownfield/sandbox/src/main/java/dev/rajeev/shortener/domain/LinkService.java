package dev.rajeev.shortener.domain;

import dev.rajeev.shortener.analytics.AnalyticsQueue;
import dev.rajeev.shortener.analytics.ClickEvent;
import dev.rajeev.shortener.analytics.ClickNormalizer;
import dev.rajeev.shortener.repository.AliasTakenException;
import dev.rajeev.shortener.repository.LinkRepository;
import dev.rajeev.shortener.repository.LinkStats;
import java.time.Clock;
import java.util.Locale;

/**
 * Application service: owns the use cases, knows nothing about HTTP. Routes are a thin adapter
 * over this class, which is what makes behaviour testable without a server and what lets the
 * orchestrator's impact analysis point at one module when a rule changes.
 */
public class LinkService {

    public record CreateResult(Link link, boolean created) {}

    private final LinkRepository repo;
    private final CodeGenerator codegen;
    private final AnalyticsQueue analytics;
    private final UrlPolicy urlPolicy;
    private final Clock clock;

    public LinkService(LinkRepository repo, CodeGenerator codegen, AnalyticsQueue analytics, UrlPolicy urlPolicy, Clock clock) {
        this.repo = repo;
        this.codegen = codegen;
        this.analytics = analytics;
        this.urlPolicy = urlPolicy;
        this.clock = clock;
    }

    public CreateResult create(CreateLinkRequest req, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = repo.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) return new CreateResult(existing.get(), false);
        }
        validate(req);
        validateExpiry(req);

        String normalized = switch (urlPolicy.evaluate(req.url())) {
            case UrlPolicy.Ok ok -> ok.normalized();
            case UrlPolicy.Rejected rejected -> throw new DomainException(rejected.code(), rejected.message());
        };

        String code;
        if (req.customAlias() != null) {
            if (LinkRules.RESERVED_CODES.contains(req.customAlias().toLowerCase(Locale.ROOT))) {
                throw new DomainException(ErrorCode.ALIAS_RESERVED, "'" + req.customAlias() + "' is reserved");
            }
            code = req.customAlias();
        } else {
            try {
                code = codegen.generate(repo::exists);
            } catch (CodeExhaustedException e) {
                throw new DomainException(ErrorCode.CODE_EXHAUSTED, e.getMessage());
            }
        }

        Link link = new Link(code, normalized, clock.instant(), null, req.expiresAt(), req.customAlias() != null, req.ownerId());
        try {
            repo.insert(link);
        } catch (AliasTakenException e) {
            throw new DomainException(ErrorCode.ALIAS_TAKEN, e.getMessage());
        }
        if (idempotencyKey != null && !idempotencyKey.isBlank()) repo.rememberIdempotencyKey(idempotencyKey, code, clock.instant());
        return new CreateResult(link, true);
    }

    /** Explicit validation so the rules hold for every caller, not only for @Valid at the HTTP edge. */
    private static void validate(CreateLinkRequest req) {
        if (req == null || req.url() == null || req.url().isBlank()) throw new DomainException(ErrorCode.VALIDATION, "url is required");
        if (req.url().length() > 8192) throw new DomainException(ErrorCode.VALIDATION, "url is too long");
        if (req.customAlias() != null && !LinkRules.CUSTOM_ALIAS_PATTERN.matcher(req.customAlias()).matches()) {
            throw new DomainException(ErrorCode.VALIDATION, "alias must be 4-32 URL-safe characters");
        }
        if (req.ownerId() != null && req.ownerId().length() > 128) throw new DomainException(ErrorCode.VALIDATION, "ownerId is too long");
    }

    private void validateExpiry(CreateLinkRequest req) {
        if (req.expiresAt() != null && !req.expiresAt().isAfter(clock.instant())) {
            throw new DomainException(ErrorCode.VALIDATION, "expiresAt must be in the future");
        }
    }

    /** Returns the active link or throws NOT_FOUND / GONE. */
    public Link resolve(String code) {
        Link link = repo.findByCode(code).orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "short link not found"));
        if (link.deleted()) throw new DomainException(ErrorCode.GONE, "short link has been deleted");
        // Expiry is evaluated on read against the injected clock: no background sweeper, no drift between workers.
        if (link.expiredAt(clock.instant())) throw new DomainException(ErrorCode.EXPIRED, "short link has expired");
        return link;
    }

    /** Resolve + fire-and-forget analytics. Redirect latency never waits on the store. */
    public Link resolveAndTrack(String code, String referrer, String userAgent) {
        Link link = resolve(code);
        analytics.enqueue(new ClickEvent(link.code(), clock.instant(), ClickNormalizer.referrer(referrer), ClickNormalizer.userAgent(userAgent)));
        return link;
    }

    public Link get(String code) {
        return resolve(code);
    }

    public void remove(String code) {
        Link link = repo.findByCode(code).orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "short link not found"));
        if (link.deleted()) throw new DomainException(ErrorCode.GONE, "short link has already been deleted");
        repo.softDelete(code, clock.instant());
    }

    public LinkStats stats(String code, int days, int topN) {
        repo.findByCode(code).orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "short link not found"));
        // Stats stay readable after deletion: owners often want the final numbers.
        analytics.flush();
        return repo.stats(code, days, topN);
    }
}
