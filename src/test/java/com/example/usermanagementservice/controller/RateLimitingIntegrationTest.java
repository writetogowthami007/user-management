package com.example.usermanagementservice.controller;

import com.example.usermanagementservice.support.PostgresContainerTestBase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("container")
@TestPropertySource(properties = {
        "app.rate-limit.capacity=3",
        "app.rate-limit.refill-tokens=3",
        "app.rate-limit.refill-minutes=5"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RateLimitingIntegrationTest extends PostgresContainerTestBase {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void rateLimitExceededReturns429() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Rate",
                                  "lastName": "Limit",
                                  "email": "rate.limit@example.com",
                                  "password": "password123"
                                }
                                """))
                .andExpect(status().isCreated());

        String checkPayload = """
                {
                  "email": "rate.limit@example.com",
                  "password": "password123"
                }
                """;

        mockMvc.perform(post("/api/users/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkPayload))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/users/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkPayload))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/users/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkPayload))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("rate limit exceeded"));
    }
}
