package dev.rajeev.shortener.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.rajeev.shortener.domain.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ApiExceptionHandlerTest {

    @Test
    void everyErrorCodeHasAStatus() {
        for (ErrorCode code : ErrorCode.values()) {
            HttpStatus status = ApiExceptionHandler.statusFor(code);
            assertEquals(true, status.is4xxClientError() || status.is5xxServerError(), code.name());
        }
        assertEquals(HttpStatus.GONE, ApiExceptionHandler.statusFor(ErrorCode.GONE));
        assertEquals(HttpStatus.CONFLICT, ApiExceptionHandler.statusFor(ErrorCode.ALIAS_TAKEN));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ApiExceptionHandler.statusFor(ErrorCode.URL_NOT_ALLOWED));
    }
}
