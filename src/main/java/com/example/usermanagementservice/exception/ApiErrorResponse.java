package com.example.usermanagementservice.exception;

import java.time.Instant;

public record ApiErrorResponse(
        String message,
        int status,
        Instant timestamp
) {
}
