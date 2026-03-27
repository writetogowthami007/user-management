package com.example.usermanagementservice.config;

import com.example.usermanagementservice.repository.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.List;
import java.util.Locale;

@Configuration
public class UserDetailsConfig {
    private final UserRepository repository;

    public UserDetailsConfig(UserRepository repository) {
        this.repository = repository;
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return username -> repository.findByEmail(username.trim().toLowerCase(Locale.ROOT))
                .map(user -> buildUserDetails(user.getEmail(), user.getPassword()))
                .orElseThrow(() -> new UsernameNotFoundException("user not found"));
    }

    private UserDetails buildUserDetails(String email, String password) {
        return new org.springframework.security.core.userdetails.User(
                email,
                password,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
    }
}
