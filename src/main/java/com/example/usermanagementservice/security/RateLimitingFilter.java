package com.example.usermanagementservice.security;

import com.example.usermanagementservice.exception.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimitingFilter extends OncePerRequestFilter {
    private static final long MILLIS_PER_MINUTE = 60_000L;
    private static final long STALE_WINDOW_MULTIPLIER = 2L;

    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();
    private final long capacity;
    private final long refillTokens;
    private final long windowMinutes;
    private final long evictionCheckIntervalMs;
    private final boolean trustForwardedFor;
    private final int maxClients;
    private final Counter rateLimitExceededCounter;
    private final ObjectMapper objectMapper;
    private volatile long lastEvictionCheckMs;

    public RateLimitingFilter(
            MeterRegistry meterRegistry,
            ObjectMapper objectMapper,
            @Value("${app.rate-limit.capacity:100}") long capacity,
            @Value("${app.rate-limit.refill-tokens:100}") long refillTokens,
            @Value("${app.rate-limit.refill-minutes:1}") long refillMinutes,
            @Value("${app.rate-limit.eviction-check-interval-ms:5000}") long evictionCheckIntervalMs,
            @Value("${app.rate-limit.trust-forwarded-for:false}") boolean trustForwardedFor,
            @Value("${app.rate-limit.max-clients:10000}") int maxClients
    ) {
        this.capacity = requirePositive(capacity, "app.rate-limit.capacity");
        this.refillTokens = requirePositive(refillTokens, "app.rate-limit.refill-tokens");
        this.windowMinutes = requirePositive(refillMinutes, "app.rate-limit.refill-minutes");
        this.evictionCheckIntervalMs = requirePositive(
                evictionCheckIntervalMs,
                "app.rate-limit.eviction-check-interval-ms"
        );
        this.trustForwardedFor = trustForwardedFor;
        this.maxClients = requirePositive(maxClients, "app.rate-limit.max-clients");
        this.objectMapper = objectMapper;
        this.rateLimitExceededCounter = Counter.builder("app.security.rate_limit.exceeded")
                .description("Number of requests rejected by rate limiter")
                .register(meterRegistry);
        Gauge.builder("app.security.rate_limit.active_clients", counters, Map::size)
                .description("Number of active clients tracked by in-memory rate limiter")
                .register(meterRegistry);
        this.lastEvictionCheckMs = System.currentTimeMillis();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        evictStaleClientsIfNeeded();
        String clientKey = resolveClientKey(request);
        WindowCounter counter = counters.computeIfAbsent(clientKey, unusedClientKey -> new WindowCounter());

        if (counter.tryConsume(capacity, refillTokens, windowMinutes)) {
            filterChain.doFilter(request, response);
            return;
        }

        rateLimitExceededCounter.increment();
        writeError(response, 429, "rate limit exceeded");
    }

    private String resolveClientKey(HttpServletRequest request) {
        String remoteAddress = normalizeClientAddress(request.getRemoteAddr());
        if (!trustForwardedFor) {
            return remoteAddress;
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        String forwardedAddress = extractFirstForwardedAddress(forwardedFor);
        if (forwardedAddress != null) {
            return forwardedAddress;
        }
        return remoteAddress;
    }

    private void evictStaleClientsIfNeeded() {
        if (counters.size() <= maxClients) {
            return;
        }
        long currentTimeMillis = System.currentTimeMillis();
        if (currentTimeMillis - lastEvictionCheckMs < evictionCheckIntervalMs) {
            return;
        }
        lastEvictionCheckMs = currentTimeMillis;
        long staleThresholdMillis = safeMultiplyOrMax(
                safeMultiplyOrMax(windowMinutes, MILLIS_PER_MINUTE),
                STALE_WINDOW_MULTIPLIER
        );
        counters.entrySet().removeIf(entry ->
                currentTimeMillis - entry.getValue().getLastSeenMs() > staleThresholdMillis);
    }

    private String normalizeClientAddress(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isBlank()) {
            return "unknown";
        }
        return remoteAddress;
    }

    private String extractFirstForwardedAddress(String forwardedForHeader) {
        if (forwardedForHeader == null || forwardedForHeader.isBlank()) {
            return null;
        }
        int firstCommaIndex = forwardedForHeader.indexOf(',');
        String firstHopAddress = firstCommaIndex >= 0
                ? forwardedForHeader.substring(0, firstCommaIndex)
                : forwardedForHeader;
        String normalizedAddress = firstHopAddress.trim();
        return normalizedAddress.isEmpty() ? null : normalizedAddress;
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        ApiErrorResponse body = new ApiErrorResponse(message, status, Instant.now());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private static class WindowCounter {
        private double tokens = 0;
        private long lastRefillMs = System.currentTimeMillis();
        private volatile long lastSeenMs = System.currentTimeMillis();

        synchronized boolean tryConsume(long capacity, long refillTokens, long refillMinutes) {
            long currentTimeMillis = System.currentTimeMillis();
            lastSeenMs = currentTimeMillis;

            if (tokens == 0) {
                tokens = capacity;
            }

            long refillWindowMillis = safeMultiplyOrMax(refillMinutes, MILLIS_PER_MINUTE);
            refillWindowMillis = Math.max(refillWindowMillis, 1);
            double refillRatePerMillisecond = refillTokens / (double) refillWindowMillis;
            long elapsedMillis = Math.max(currentTimeMillis - lastRefillMs, 0);
            tokens = Math.min(capacity, tokens + (elapsedMillis * refillRatePerMillisecond));
            lastRefillMs = currentTimeMillis;

            if (tokens < 1.0d) {
                return false;
            }
            tokens -= 1.0d;
            return true;
        }

        long getLastSeenMs() {
            return lastSeenMs;
        }
    }

    private long requirePositive(long value, String propertyName) {
        if (value <= 0) {
            throw new IllegalArgumentException(propertyName + " must be > 0");
        }
        return value;
    }

    private int requirePositive(int value, String propertyName) {
        if (value <= 0) {
            throw new IllegalArgumentException(propertyName + " must be > 0");
        }
        return value;
    }

    private static long safeMultiplyOrMax(long left, long right) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException ex) {
            return Long.MAX_VALUE;
        }
    }
}
