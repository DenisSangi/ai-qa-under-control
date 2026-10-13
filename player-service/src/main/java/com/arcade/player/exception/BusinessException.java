package com.arcade.player.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class BusinessException extends RuntimeException {

    private final String code;
    private final HttpStatus status;
    private final Map<String, String> details;

    public BusinessException(String code, HttpStatus status, String message) {
        this(code, status, message, null);
    }

    public BusinessException(String code, HttpStatus status, String message, Map<String, String> details) {
        super(message);
        this.code = code;
        this.status = status;
        this.details = details;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public Map<String, String> getDetails() {
        return details;
    }
}
