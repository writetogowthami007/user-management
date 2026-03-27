package com.example.usermanagementservice.dto.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CheckUserRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 6, max = 72) String password
) {
}
