package com.example.usermanagementservice.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.function.Function;

@Service
public class JwtService {
    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.jwt.expiration-minutes:60}")
    private long expirationMinutes;
    private SecretKey signingKey;

    @PostConstruct
    void validateConfiguration() {
        byte[] keyBytes;
        try {
            keyBytes = Decoders.BASE64.decode(jwtSecret);
        } catch (Exception ex) {
            throw new IllegalStateException("JWT secret must be valid base64", ex);
        }
        if (keyBytes.length < 32) {
            throw new IllegalStateException("JWT secret must decode to at least 32 bytes");
        }
        if (expirationMinutes <= 0) {
            throw new IllegalStateException("JWT expiration minutes must be > 0");
        }
        signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        String username = extractUsername(token);
        return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
    }

    public boolean isTokenValidForSubject(String token, String subject) {
        String username = extractUsername(token);
        return username.equals(subject) && !isTokenExpired(token);
    }

    public String generateToken(String username) {
        Instant now = Instant.now();
        long expirationSeconds;
        try {
            expirationSeconds = Math.multiplyExact(expirationMinutes, 60L);
        } catch (ArithmeticException ex) {
            throw new IllegalStateException("JWT expiration minutes is too large", ex);
        }
        Instant expiry = now.plusSeconds(expirationSeconds);
        return Jwts.builder()
                .subject(username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(getSigningKey())
                .compact();
    }

    private boolean isTokenExpired(String token) {
        return extractClaim(token, Claims::getExpiration).before(new Date());
    }

    private <T> T extractClaim(String token, Function<Claims, T> claimResolver) {
        final Claims claims = extractAllClaims(token);
        return claimResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey getSigningKey() {
        if (signingKey == null) {
            throw new IllegalStateException("JWT signing key is not initialized");
        }
        return signingKey;
    }
}
