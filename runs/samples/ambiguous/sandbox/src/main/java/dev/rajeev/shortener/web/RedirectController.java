package dev.rajeev.shortener.web;

import dev.rajeev.shortener.domain.Link;
import dev.rajeev.shortener.domain.LinkRules;
import dev.rajeev.shortener.domain.LinkService;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /{code} -> 302 to the target.
 *
 * 302 (not 301) is deliberate: a 301 is cached aggressively by browsers, which means we would stop
 * seeing clicks after the first visit and a deleted link would keep redirecting from cache.
 * {@code Cache-Control: no-store} reinforces the same intent for intermediaries.
 */
@RestController
public class RedirectController {

    private final LinkService service;

    public RedirectController(LinkService service) {
        this.service = service;
    }

    @GetMapping("/{code:" + LinkRules.CODE_PATH + "}")
    public ResponseEntity<Void> redirect(@PathVariable("code") String code,
                                         @RequestHeader(name = "Referer", required = false) String referer,
                                         @RequestHeader(name = "User-Agent", required = false) String userAgent) {
        Link link = service.resolveAndTrack(code, referer, userAgent);
        return ResponseEntity.status(HttpStatus.FOUND)
                .cacheControl(CacheControl.noStore())
                .location(URI.create(link.targetUrl()))
                .build();
    }
}
