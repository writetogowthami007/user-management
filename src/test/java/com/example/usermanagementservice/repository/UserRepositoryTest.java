package com.example.usermanagementservice.repository;

import com.example.usermanagementservice.model.User;
import com.example.usermanagementservice.support.PostgresContainerTestBase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@ActiveProfiles("test")
@Tag("container")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(UserRepositoryTest.JpaAuditingTestConfig.class)
class UserRepositoryTest extends PostgresContainerTestBase {
    @Autowired
    private UserRepository userRepository;

    @Test
    void saveAndFindByEmailWorks() {
        User user = new User();
        user.setFirstName("Repo");
        user.setLastName("Test");
        user.setEmail("repo.test@example.com");
        user.setPassword("hashed");

        userRepository.save(user);

        Optional<User> found = userRepository.findByEmail("repo.test@example.com");
        assertTrue(found.isPresent());
        assertNotNull(found.get().getCreatedAt());
        assertNotNull(found.get().getUpdatedAt());
    }

    @Test
    void softDeleteExcludesUserFromDefaultQueries() {
        User user = new User();
        user.setFirstName("Soft");
        user.setLastName("Delete");
        user.setEmail("soft.delete@example.com");
        user.setPassword("hashed");

        User saved = userRepository.save(user);
        userRepository.deleteById(saved.getId());

        assertFalse(userRepository.findByEmail("soft.delete@example.com").isPresent());
        assertFalse(userRepository.existsByEmail("soft.delete@example.com"));
    }

    @Configuration
    @EnableJpaAuditing
    @AutoConfigurationPackage(basePackageClasses = UserRepository.class)
    @EntityScan(basePackageClasses = User.class)
    static class JpaAuditingTestConfig {
    }
}
