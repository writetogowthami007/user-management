package com.example.usermanagementservice.dto.user;

public record UserResponse(
        String firstName,
        String lastName,
        String email
) {
}
