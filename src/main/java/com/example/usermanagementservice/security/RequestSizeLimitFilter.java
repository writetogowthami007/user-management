package com.example.usermanagementservice.security;

import com.example.usermanagementservice.exception.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

@Component
public class RequestSizeLimitFilter extends OncePerRequestFilter {
    private final long maxBodyBytes;
    private final ObjectMapper objectMapper;

    public RequestSizeLimitFilter(
            ObjectMapper objectMapper,
            @Value("${app.request.max-body-bytes:1048576}") long maxBodyBytes
    ) {
        this.objectMapper = objectMapper;
        if (maxBodyBytes <= 0) {
            throw new IllegalArgumentException("app.request.max-body-bytes must be > 0");
        }
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength > 0 && contentLength > maxBodyBytes) {
            writeError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "payload too large");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        ApiErrorResponse body = new ApiErrorResponse(message, status, Instant.now());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
