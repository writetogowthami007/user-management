package com.example.usermanagementservice.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FilterGuardrailTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    // Adversarial (negative/invalid configuration)
    @Test
    void givenNonPositiveRateLimitConfig_whenConstructingFilter_thenThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                new RateLimitingFilter(meterRegistry, objectMapper, 0, 10, 1, 5000, false, 100));
        assertThrows(IllegalArgumentException.class, () ->
                new RateLimitingFilter(meterRegistry, objectMapper, 10, -1, 1, 5000, false, 100));
        assertThrows(IllegalArgumentException.class, () ->
                new RateLimitingFilter(meterRegistry, objectMapper, 10, 10, 0, 5000, false, 100));
        assertThrows(IllegalArgumentException.class, () ->
                new RateLimitingFilter(meterRegistry, objectMapper, 10, 10, 1, 5000, false, -1));
        assertThrows(IllegalArgumentException.class, () ->
                new RateLimitingFilter(meterRegistry, objectMapper, 10, 10, 1, 0, false, 100));
    }

    // Adversarial (negative/invalid configuration)
    @Test
    void givenNonPositiveRequestSizeLimit_whenConstructingFilter_thenThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> new RequestSizeLimitFilter(objectMapper, 0));
        assertThrows(IllegalArgumentException.class, () -> new RequestSizeLimitFilter(objectMapper, -1));
    }

    // Complexity sanity check (overflow safety)
    @Test
    void givenVeryLargeRefillWindow_whenFilteringRequest_thenNoOverflowAndRequestPasses()
            throws ServletException, IOException {
        RateLimitingFilter filter = new RateLimitingFilter(
                meterRegistry,
                objectMapper,
                100,
                100,
                Long.MAX_VALUE,
                5000,
                false,
                10000
        );

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/users/check");
        request.setRemoteAddr("127.0.0.1");
        request.setContentType("application/json");
        request.setContent("{\"email\":\"x@example.com\",\"password\":\"password123\"}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
    }
}
