package dev.rajeev.shortener.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rajeev.shortener.config.ShortenerProperties;
import dev.rajeev.shortener.domain.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Fixed-window rate limit per client IP on the API surface only ({@code /api/**}). Redirects are the
 * product and are never rate-limited here. In-process by design for a single node; a multi-instance
 * deployment swaps this for Bucket4j + Redis or an edge limiter (see docs).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RateLimitFilter extends OncePerRequestFilter {

    private record Window(long startMs, int count) {}

    private final int max;
    private final long windowMs;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(ShortenerProperties props, Clock clock, ObjectMapper mapper) {
        this.max = props.rateLimit().max();
        this.windowMs = props.rateLimit().windowMs();
        this.clock = clock;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String key = clientIp(request);
        long now = clock.millis();
        Window w = windows.compute(key, (k, cur) -> cur == null || now - cur.startMs() >= windowMs ? new Window(now, 1) : new Window(cur.startMs(), cur.count() + 1));
        if (w.count() > max) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Retry-After", String.valueOf(Math.max(1, (w.startMs() + windowMs - now) / 1000)));
            mapper.writeValue(response.getOutputStream(), new ApiError(ErrorCode.RATE_LIMITED.name(), "rate limit exceeded", RequestIdFilter.current(request)));
            return;
        }
        chain.doFilter(request, response);
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
