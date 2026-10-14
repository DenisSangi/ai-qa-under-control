package com.arcade.player.web;

import com.arcade.player.dto.ApiError;
import com.arcade.player.exception.BusinessException;
import com.arcade.player.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single place that shapes all error responses — a stable error contract is
 * what QA relies on for negative testing.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError notFound(NotFoundException e) {
        return error("NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> business(BusinessException e) {
        return ResponseEntity.status(e.getStatus()).body(error(e.getCode(), e.getMessage(), e.getDetails()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError validation(MethodArgumentNotValidException e) {
        Map<String, String> details = new LinkedHashMap<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            details.put(fe.getField(), fe.getDefaultMessage());
        }
        return error("VALIDATION_ERROR", "request validation failed", details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError malformed(HttpMessageNotReadableException e) {
        return error("MALFORMED_JSON", "request body is not readable");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError conflict(DataIntegrityViolationException e) {
        return error("DATA_CONFLICT", "request violates a data constraint");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiError internal(Exception e) {
        log.error("unhandled exception", e);
        return error("INTERNAL_ERROR", "unexpected server error");
    }

    private ApiError error(String code, String message) {
        return error(code, message, null);
    }

    private ApiError error(String code, String message, Map<String, String> details) {
        return new ApiError(code, message, clock.instant(), details);
    }
}
