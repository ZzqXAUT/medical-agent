package com.example.mednav.api;

import com.example.mednav.memory.MemoryCacheUnavailableException;
import java.time.Instant;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException exception) {
        return ResponseEntity.status(exception.status())
                .body(new ApiError(exception.code(), exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ApiError> handleValidation(WebExchangeBindException exception) {
        String message = exception.getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("VALIDATION_ERROR", message, Instant.now()));
    }

    @ExceptionHandler(MemoryCacheUnavailableException.class)
    public ResponseEntity<ApiError> handleMemoryCacheUnavailable(
            MemoryCacheUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError(
                        "MEMORY_CACHE_UNAVAILABLE",
                        "Session memory is temporarily unavailable. Please retry without closing the Session.",
                        Instant.now()));
    }
}
