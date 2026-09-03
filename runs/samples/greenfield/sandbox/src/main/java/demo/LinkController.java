package demo;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** HTTP adapter: create, redirect (302 + no-store so every click is counted), stats, health. */
@RestController
public class LinkController {

    private final LinkStore store = new LinkStore.InMemory();
    private final String baseUrl;

    public LinkController(@Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** Only absolute http(s) URLs may be shortened: anything else turns the redirect into an attack vector. */
    static String validateUrl(Object raw) {
        if (!(raw instanceof String s) || s.isBlank()) return null;
        try {
            URI u = new URI(s.trim());
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && u.getHost() != null ? u.toString() : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @PostMapping("/api/links")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String target = validateUrl(body.get("url"));
        if (target == null) return ResponseEntity.badRequest().body(Map.of("error", "INVALID_URL", "message", "only absolute http(s) URLs are accepted"));
        String code = Codes.unique(store::exists, 7, 5);
        LinkStore.Link link = new LinkStore.Link(code, target, Instant.now());
        store.insert(link);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("code", code, "targetUrl", target, "createdAt", link.createdAt().toString(), "shortUrl", baseUrl + "/" + code));
    }

    @GetMapping("/api/links/{code:[0-9A-Za-z]{7}}/stats")
    public ResponseEntity<Map<String, Object>> stats(@PathVariable("code") String code) {
        return store.stats(code)
                .<ResponseEntity<Map<String, Object>>>map(s -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("code", s.code());
                    m.put("clicks", s.clicks());
                    m.put("lastClickAt", s.lastClickAt() == null ? null : s.lastClickAt().toString());
                    return ResponseEntity.ok(m);
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "NOT_FOUND")));
    }

    @GetMapping("/{code:[0-9A-Za-z]{7}}")
    public ResponseEntity<Void> redirect(@PathVariable("code") String code) {
        return store.find(code)
                .map(link -> {
                    store.recordClick(code, Instant.now());
                    return ResponseEntity.status(HttpStatus.FOUND).cacheControl(CacheControl.noStore()).location(URI.create(link.targetUrl())).<Void>build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
