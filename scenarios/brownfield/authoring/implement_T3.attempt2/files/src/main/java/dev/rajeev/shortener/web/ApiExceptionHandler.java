package dev.rajeev.shortener.web;

import dev.rajeev.shortener.domain.DomainException;
import dev.rajeev.shortener.domain.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * One place where domain outcomes become HTTP statuses. {@link #statusFor} is an exhaustive switch
 * over {@link ErrorCode}: adding a code without deciding its status does not compile.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    static HttpStatus statusFor(ErrorCode code) {
        return switch (code) {
            case VALIDATION, INVALID_URL -> HttpStatus.BAD_REQUEST;
            case URL_NOT_ALLOWED, ALIAS_RESERVED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case ALIAS_TAKEN -> HttpStatus.CONFLICT;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case GONE, EXPIRED -> HttpStatus.GONE;
            case CODE_EXHAUSTED -> HttpStatus.SERVICE_UNAVAILABLE;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
        };
    }

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ApiError> domain(DomainException e, HttpServletRequest req) {
        return ResponseEntity.status(statusFor(e.code())).body(new ApiError(e.code().name(), e.getMessage(), RequestIdFilter.current(req)));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiError> validation(Exception e, HttpServletRequest req) {
        String message = e instanceof MethodArgumentNotValidException m && m.getBindingResult().getFieldError() != null
                ? m.getBindingResult().getFieldError().getField() + ": " + m.getBindingResult().getFieldError().getDefaultMessage()
                : "request body is malformed";
        return ResponseEntity.badRequest().body(new ApiError(ErrorCode.VALIDATION.name(), message, RequestIdFilter.current(req)));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> notFound(NoResourceFoundException e, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ErrorCode.NOT_FOUND.name(), "no such resource", RequestIdFilter.current(req)));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException e, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(new ApiError("METHOD_NOT_ALLOWED", e.getMessage(), RequestIdFilter.current(req)));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unhandled(Exception e, HttpServletRequest req) {
        log.error("unhandled error [requestId={}]", RequestIdFilter.current(req), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError("INTERNAL", "internal error", RequestIdFilter.current(req)));
    }
}
