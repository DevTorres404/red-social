package com.redsocial.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Uniform error response shape for all API errors.
 * Serialized as JSON by Quarkus ExceptionMapper.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        int status,
        String error,
        String message,
        String path,
        Instant timestamp
) {
    public static ApiError of(int status, String error, String message, String path) {
        return new ApiError(status, error, message, path, Instant.now());
    }
}
