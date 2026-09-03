package dev.rajeev.shortener.web;

/** Uniform error body: {@code {error, message, requestId}}. */
public record ApiError(String error, String message, String requestId) {}
