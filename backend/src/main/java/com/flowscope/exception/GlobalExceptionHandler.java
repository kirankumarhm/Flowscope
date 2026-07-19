package com.flowscope.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.flowscope.dto.ErrorResponse;

/**
 * Maps analysis exceptions to HTTP status codes with a JSON {@code {"error": ...}}
 * body. Every response — success or error — carries {@code application/json}
 * (Requirement 6.6).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiExceptions.MissingPathException.class)
    public ResponseEntity<ErrorResponse> missingPath(ApiExceptions.MissingPathException e) {
        return json(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(ApiExceptions.InvalidPathException.class)
    public ResponseEntity<ErrorResponse> invalidPath(ApiExceptions.InvalidPathException e) {
        return json(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(ApiExceptions.AnalysisTimeoutException.class)
    public ResponseEntity<ErrorResponse> timeout(ApiExceptions.AnalysisTimeoutException e) {
        return json(HttpStatus.GATEWAY_TIMEOUT, e.getMessage());
    }

    @ExceptionHandler(ApiExceptions.NotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(ApiExceptions.NotFoundException e) {
        return json(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ApiExceptions.MemoryExceededException.class)
    public ResponseEntity<ErrorResponse> memory(ApiExceptions.MemoryExceededException e) {
        return json(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> internal(Exception e) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return json(HttpStatus.INTERNAL_SERVER_ERROR, "analysis failed: " + message);
    }

    private ResponseEntity<ErrorResponse> json(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(message));
    }
}
