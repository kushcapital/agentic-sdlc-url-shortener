package dev.rajeev.shortener.domain;

import java.time.Instant;

public record LinkResponse(String code, String shortUrl, String targetUrl, Instant createdAt, boolean custom) {

    public static LinkResponse from(Link link, String baseUrl) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return new LinkResponse(link.code(), base + "/" + link.code(), link.targetUrl(), link.createdAt(), link.custom());
    }
}
