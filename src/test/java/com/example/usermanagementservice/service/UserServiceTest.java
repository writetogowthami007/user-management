package com.example.usermanagementservice.service;

import com.example.usermanagementservice.exception.DuplicateEmailException;
import com.example.usermanagementservice.model.User;
import com.example.usermanagementservice.repository.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {
    @Mock
    private UserRepository repository;
    @Mock
    private PasswordEncoder passwordEncoder;

    private UserService userService;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        userService = new UserService(repository, passwordEncoder, meterRegistry);
    }

    // Happy path
    @Test
    void saveUserHashesPasswordAndNormalizesEmail() {
        User user = new User();
        user.setFirstName("John");
        user.setLastName("Doe");
        user.setEmail(" John@Example.com ");
        user.setPassword("password123");

        when(repository.existsByEmail("john@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hashed-password");
        when(repository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User saved = userService.saveUser(user);

        assertEquals("john@example.com", saved.getEmail());
        assertEquals("hashed-password", saved.getPassword());
    }

    // Adversarial (duplicates)
    @Test
    void saveUserThrowsForDuplicateEmail() {
        User user = new User();
        user.setEmail("john@example.com");
        user.setPassword("password123");

        when(repository.existsByEmail("john@example.com")).thenReturn(true);

        assertThrows(DuplicateEmailException.class, () -> userService.saveUser(user));
    }

    // Happy path
    @Test
    void isUserValidReturnsTrueForMatchingPassword() {
        User existing = new User();
        existing.setEmail("john@example.com");
        existing.setPassword("hashed");

        when(repository.findByEmail("john@example.com")).thenReturn(Optional.of(existing));
        when(passwordEncoder.matches("password123", "hashed")).thenReturn(true);

        assertTrue(userService.isUserValid("John@Example.com", "password123"));
    }

    // Degenerate
    @Test
    void isUserValidReturnsFalseWhenUserNotFound() {
        when(repository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        assertFalse(userService.isUserValid("missing@example.com", "password123"));
    }

    // Adversarial
    @Test
    void isUserValidReturnsFalseWhenPasswordDoesNotMatch() {
        User existing = new User();
        existing.setEmail("john@example.com");
        existing.setPassword("hashed");

        when(repository.findByEmail("john@example.com")).thenReturn(Optional.of(existing));
        when(passwordEncoder.matches("wrong-password", "hashed")).thenReturn(false);

        assertFalse(userService.isUserValid("John@Example.com", "wrong-password"));
    }

    // Degenerate
    @Test
    void saveUserWithoutPasswordDoesNotCallEncoder() {
        User user = new User();
        user.setFirstName("No");
        user.setLastName("Password");
        user.setEmail("nopassword@example.com");
        user.setPassword(null);

        when(repository.existsByEmail("nopassword@example.com")).thenReturn(false);
        when(repository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User saved = userService.saveUser(user);

        assertEquals("nopassword@example.com", saved.getEmail());
        assertEquals(null, saved.getPassword());
        verify(passwordEncoder, never()).encode(any());
    }

    // Adversarial (race-safe duplicate handling)
    @Test
    void saveUserTranslatesDataIntegrityViolationToDuplicateEmailException() {
        User user = new User();
        user.setFirstName("Jane");
        user.setLastName("Dup");
        user.setEmail("jane.dup@example.com");
        user.setPassword("password123");

        when(repository.existsByEmail("jane.dup@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hashed-password");
        when(repository.save(any(User.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThrows(DuplicateEmailException.class, () -> userService.saveUser(user));
    }

    // Happy path
    @Test
    void findByEmailNormalizesInputBeforeRepositoryLookup() {
        User existing = new User();
        existing.setEmail("john@example.com");

        when(repository.findByEmail("john@example.com")).thenReturn(Optional.of(existing));

        Optional<User> result = userService.findByEmail("  John@Example.com  ");

        assertTrue(result.isPresent());
        assertEquals("john@example.com", result.get().getEmail());
        verify(repository).findByEmail("john@example.com");
    }

    // Degenerate / Adversarial
    @Test
    void nullEmailThrowsIllegalArgumentExceptionAcrossPublicMethods() {
        User user = new User();
        user.setFirstName("Null");
        user.setLastName("Email");
        user.setEmail(null);
        user.setPassword("password123");

        assertThrows(IllegalArgumentException.class, () -> userService.saveUser(user));
        assertThrows(IllegalArgumentException.class, () -> userService.findByEmail(null));
        assertThrows(IllegalArgumentException.class, () -> userService.isUserValid(null, "password123"));
    }

    // Degenerate / Adversarial
    @Test
    void blankEmailThrowsIllegalArgumentExceptionAcrossPublicMethods() {
        User user = new User();
        user.setFirstName("Blank");
        user.setLastName("Email");
        user.setEmail("   ");
        user.setPassword("password123");

        assertThrows(IllegalArgumentException.class, () -> userService.saveUser(user));
        assertThrows(IllegalArgumentException.class, () -> userService.findByEmail("   "));
        assertThrows(IllegalArgumentException.class, () -> userService.isUserValid("   ", "password123"));
    }

    // Degenerate
    @Test
    void isUserValidWithNullPasswordReturnsFalseWithoutRepositoryLookup() {
        assertFalse(userService.isUserValid("john@example.com", null));
        verifyNoInteractions(repository);
    }
}
