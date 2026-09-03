package dev.rajeev.shortener.web;

import dev.rajeev.shortener.config.ShortenerProperties;
import dev.rajeev.shortener.domain.CreateLinkRequest;
import dev.rajeev.shortener.domain.LinkResponse;
import dev.rajeev.shortener.domain.LinkService;
import dev.rajeev.shortener.repository.LinkStats;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Link lifecycle and analytics. Contract: src/main/resources/static/openapi.yaml. */
@RestController
@RequestMapping("/api/links")
public class LinkController {

    private final LinkService service;
    private final String baseUrl;

    public LinkController(LinkService service, ShortenerProperties props) {
        this.service = service;
        this.baseUrl = props.baseUrl();
    }

    /**
     * Create a short link. Send an {@code Idempotency-Key} header to make retries safe: the same key
     * returns the original link with 200 instead of creating a duplicate.
     */
    @PostMapping
    public ResponseEntity<LinkResponse> create(@Valid @RequestBody CreateLinkRequest body,
                                               @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        LinkService.CreateResult result = service.create(body, idempotencyKey);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(LinkResponse.from(result.link(), baseUrl));
    }

    @GetMapping("/{code:[A-Za-z0-9_-]{4,32}}")
    public LinkResponse get(@PathVariable("code") String code) {
        return LinkResponse.from(service.get(code), baseUrl);
    }

    /** Soft delete: the code is never reissued; stats remain readable. */
    @DeleteMapping("/{code:[A-Za-z0-9_-]{4,32}}")
    public ResponseEntity<Void> delete(@PathVariable("code") String code) {
        service.remove(code);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{code:[A-Za-z0-9_-]{4,32}}/stats")
    public LinkStats stats(@PathVariable("code") String code,
                           @RequestParam(name = "days", defaultValue = "30") int days,
                           @RequestParam(name = "topN", defaultValue = "5") int topN) {
        return service.stats(code, Math.max(1, Math.min(days, 365)), Math.max(1, Math.min(topN, 50)));
    }
}
