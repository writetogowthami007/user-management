package com.example.usermanagementservice.controller;

import com.example.usermanagementservice.security.JwtService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final Counter loginSuccessCounter;
    private final Counter loginFailureCounter;

    public AuthController(AuthenticationManager authenticationManager, JwtService jwtService, MeterRegistry meterRegistry) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.loginSuccessCounter = Counter.builder("app.auth.login.attempts")
                .description("Total login attempts partitioned by outcome")
                .tag("outcome", "success")
                .register(meterRegistry);
        this.loginFailureCounter = Counter.builder("app.auth.login.attempts")
                .description("Total login attempts partitioned by outcome")
                .tag("outcome", "failure")
                .register(meterRegistry);
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody @Valid LoginRequest request) {
        String normalizedEmail = request.email().trim().toLowerCase(Locale.ROOT);
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(normalizedEmail, request.password())
            );
            String token = jwtService.generateToken(normalizedEmail);
            loginSuccessCounter.increment();
            return ResponseEntity.ok(new LoginResponse(token, "Bearer"));
        } catch (BadCredentialsException ex) {
            loginFailureCounter.increment();
            throw ex;
        }
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password
    ) {
    }

    public record LoginResponse(String accessToken, String tokenType) {
    }
}
