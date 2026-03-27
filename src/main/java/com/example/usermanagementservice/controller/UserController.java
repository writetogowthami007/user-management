package com.example.usermanagementservice.controller;

import com.example.usermanagementservice.dto.user.CheckUserRequest;
import com.example.usermanagementservice.dto.user.CreateUserRequest;
import com.example.usermanagementservice.dto.user.MessageResponse;
import com.example.usermanagementservice.dto.user.UserResponse;
import com.example.usermanagementservice.dto.user.ValidationResponse;
import com.example.usermanagementservice.exception.ApiErrorResponse;
import com.example.usermanagementservice.mapper.UserMapper;
import com.example.usermanagementservice.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Locale;

@RestController
@RequestMapping("/api/users")
@Validated
public class UserController {

    private final UserService service;
    private final UserMapper userMapper;

    public UserController(UserService service, UserMapper userMapper) {
        this.service = service;
        this.userMapper = userMapper;
    }

    @PostMapping
    public ResponseEntity<MessageResponse> createUser(@RequestBody @Valid CreateUserRequest request) {
        service.saveUser(userMapper.toEntity(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(new MessageResponse("added"));
    }

    @PostMapping("/check")
    public ValidationResponse checkUser(@RequestBody @Valid CheckUserRequest request) {
        return new ValidationResponse(service.isUserValid(request.email(), request.password()) ? "valid" : "invalid");
    }

    @GetMapping("/{email}")
    public ResponseEntity<?> getUser(@PathVariable @Email String email, Authentication authentication) {
        String normalizedRequestedEmail = normalizeEmail(email);
        String normalizedAuthenticatedEmail = normalizeEmail(authentication.getName());

        if (!normalizedRequestedEmail.equals(normalizedAuthenticatedEmail)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ApiErrorResponse("forbidden", HttpStatus.FORBIDDEN.value(), Instant.now()));
        }

        return service.findByEmail(email)
                .<ResponseEntity<?>>map(user -> ResponseEntity.ok(userMapper.toResponse(user)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ApiErrorResponse("user not found", HttpStatus.NOT_FOUND.value(), Instant.now())));
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
