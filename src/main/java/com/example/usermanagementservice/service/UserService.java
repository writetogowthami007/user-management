package com.example.usermanagementservice.service;

import com.example.usermanagementservice.exception.DuplicateEmailException;
import com.example.usermanagementservice.model.User;
import com.example.usermanagementservice.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

@Service
public class UserService {
    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final MeterRegistry meterRegistry;

    public UserService(UserRepository repository, PasswordEncoder passwordEncoder, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.meterRegistry = meterRegistry;
    }

    @Transactional(readOnly = true)
    public boolean isUserValid(String email, String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            return false;
        }
        Optional<User> existingUser = recordDbLatency("find_by_email_for_auth",
                () -> repository.findByEmail(normalizeEmail(email)));
        return existingUser
                .map(user -> passwordEncoder.matches(rawPassword, user.getPassword()))
                .orElse(false);
    }

    @Transactional
    public User saveUser(User user) {
        if (user == null) {
            throw new IllegalArgumentException("user must not be null");
        }
        String normalizedEmail = normalizeEmail(user.getEmail());
        user.setEmail(normalizedEmail);

        boolean alreadyExists = recordDbLatency("exists_by_email",
                () -> repository.existsByEmail(normalizedEmail));
        if (alreadyExists) {
            throw new DuplicateEmailException("user already exists");
        }

        if (user.getPassword() != null) {
            user.setPassword(passwordEncoder.encode(user.getPassword()));
        }

        try {
            return recordDbLatency("save_user", () -> repository.save(user));
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateEmailException("user already exists");
        }
    }

    @Transactional(readOnly = true)
    public Optional<User> findByEmail(String email) {
        return recordDbLatency("find_by_email", () -> repository.findByEmail(normalizeEmail(email)));
    }

    private String normalizeEmail(String email) {
        if (email == null) {
            throw new IllegalArgumentException("email must not be null");
        }
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (normalizedEmail.isEmpty()) {
            throw new IllegalArgumentException("email must not be blank");
        }
        return normalizedEmail;
    }

    private <T> T recordDbLatency(String operation, DbOperation<T> operationCall) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return operationCall.execute();
        } finally {
            sample.stop(Timer.builder("app.db.operation.latency")
                    .description("Database operation latency")
                    .tag("operation", operation)
                    .register(meterRegistry));
        }
    }

    @FunctionalInterface
    private interface DbOperation<T> {
        T execute();
    }
}
