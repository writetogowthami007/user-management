package com.example.usermanagementservice.controller;

import com.example.usermanagementservice.support.PostgresContainerTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("container")
@TestPropertySource(properties = {
        "app.rate-limit.capacity=1000",
        "app.rate-limit.refill-tokens=1000",
        "app.rate-limit.refill-minutes=1",
        "app.request.max-body-bytes=2048"
})
class UserControllerIntegrationTest extends PostgresContainerTestBase {
    /** Fixed test signing key (same as application-test default); not for runtime use. */
    private static final String TEST_JWT_SECRET_B64 = "c3VwZXItc2VjdXJlLWRldl9qd3Rfc2VjcmV0X2tleV8zMl9ieXRlcw==";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    // Happy path
    @Test
    void givenValidUser_whenCreateCheckLoginAndGetProfile_thenFlowSucceeds() throws Exception {
        String createPayload = """
                {
                  "firstName": "John",
                  "lastName": "Doe",
                  "email": "john@example.com",
                  "password": "password123"
                }
                """;

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("added"));

        mockMvc.perform(post("/api/users/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "john@example.com",
                                  "password": "password123"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("valid"));

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "john@example.com",
                                  "password": "password123"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn();

        JsonNode loginBody = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String token = loginBody.get("accessToken").asText();

        mockMvc.perform(get("/api/users/john@example.com")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("john@example.com"));
    }

    // Adversarial
    @Test
    void givenDuplicateEmail_whenCreateUser_thenReturnsConflict() throws Exception {
        String payload = """
                {
                  "firstName": "Dup",
                  "lastName": "User",
                  "email": "duplicate@example.com",
                  "password": "password123"
                }
                """;

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("user already exists"));
    }

    // Adversarial
    @Test
    void givenInvalidCredentials_whenLogin_thenReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Login",
                                  "lastName": "User",
                                  "email": "bad.login@example.com",
                                  "password": "password123"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "bad.login@example.com",
                                  "password": "wrong-password"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("invalid credentials"));
    }

    // Adversarial
    @Test
    void givenInvalidCredentials_whenCheckUser_thenReturnsInvalidStatus() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Check",
                                  "lastName": "User",
                                  "email": "check.invalid@example.com",
                                  "password": "password123"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/users/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "check.invalid@example.com",
                                  "password": "wrong-password"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("invalid"));
    }

    // Adversarial
    @Test
    void givenMissingToken_whenGetUser_thenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/users/john@example.com"))
                .andExpect(status().isUnauthorized());
    }

    // Adversarial
    @Test
    void givenAuthenticatedUserForAnotherProfile_whenGetUser_thenReturnsForbidden() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Alice",
                                  "lastName": "One",
                                  "email": "alice@example.com",
                                  "password": "password123"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Bob",
                                  "lastName": "Two",
                                  "email": "bob@example.com",
                                  "password": "password123"
                                }
                                """))
                .andExpect(status().isCreated());

        MvcResult loginAlice = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "alice@example.com",
                                  "password": "password123"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode loginBody = objectMapper.readTree(loginAlice.getResponse().getContentAsString());
        String aliceToken = loginBody.get("accessToken").asText();

        mockMvc.perform(get("/api/users/bob@example.com")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("forbidden"));
    }

    // Degenerate
    @Test
    void givenMissingUser_whenGetUserWithMatchingToken_thenReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/users/missing@example.com")
                        .header("Authorization", "Bearer " + buildValidToken("missing@example.com")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("user not found"));
    }

    // Adversarial
    @Test
    void givenValidToken_whenDeleteUser_thenReturnsForbidden() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Delete",
                                  "lastName": "Test",
                                  "email": "delete.test@example.com",
                                  "password": "password123"
                                }
                                """))
                .andExpect(status().isCreated());

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "delete.test@example.com",
                                  "password": "password123"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode loginBody = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String token = loginBody.get("accessToken").asText();

        mockMvc.perform(delete("/api/users/delete.test@example.com")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("forbidden"));
    }

    // Degenerate
    @Test
    void givenMalformedJson_whenCreateUser_thenReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"A\","))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("malformed request payload"));
    }

    // Degenerate
    @Test
    void givenValidationErrors_whenCreateUser_thenReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "",
                                  "lastName": "Doe",
                                  "email": "not-an-email",
                                  "password": "123"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    // Degenerate
    @Test
    void givenEmptyJson_whenCreateUser_thenReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    // Degenerate
    @Test
    void givenAllSameNameValues_whenCreateUser_thenReturnsCreated() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Same",
                                  "lastName": "Same",
                                  "email": "same.values@example.com",
                                  "password": "password123"
                                }
                                """))
                .andExpect(status().isCreated());
    }

    // Degenerate
    @Test
    void givenNullOrMissingRequiredFields_whenCreateUser_thenReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": null,
                                  "lastName": "Doe",
                                  "email": "null.first@example.com",
                                  "password": "password123"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Missing",
                                  "email": "missing.last@example.com",
                                  "password": "password123"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    // Boundary (min)
    @Test
    void givenMinimumAllowedLengths_whenCreateUser_thenReturnsCreated() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Al",
                                  "lastName": "Li",
                                  "email": "min.lengths@example.com",
                                  "password": "123456"
                                }
                                """))
                .andExpect(status().isCreated());
    }

    // Boundary (min)
    @Test
    void givenTooShortNameOrPassword_whenCreateUser_thenReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "A",
                                  "lastName": "Doe",
                                  "email": "short.first@example.com",
                                  "password": "password123"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Amy",
                                  "lastName": "D",
                                  "email": "short.last@example.com",
                                  "password": "password123"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Amy",
                                  "lastName": "Doe",
                                  "email": "short.password@example.com",
                                  "password": "12345"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    // Boundary (max)
    @Test
    void givenEmailAboveMaxLength_whenCreateUser_thenReturnsBadRequest() throws Exception {
        String overlongLocalPart = "a".repeat(250);
        String email = overlongLocalPart + "@example.com";

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Long",
                                  "lastName": "Email",
                                  "email": "%s",
                                  "password": "password123"
                                }
                                """.formatted(email)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    // Boundary (max)
    @Test
    void givenMaxAllowedEmailAndPasswordLengths_whenCreateUser_thenReturnsCreated() throws Exception {
        String emailAtMaxLength = buildEmailWithLength254();
        String passwordAtMaxLength = "p".repeat(72);

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Max",
                                  "lastName": "Boundary",
                                  "email": "%s",
                                  "password": "%s"
                                }
                                """.formatted(emailAtMaxLength, passwordAtMaxLength)))
                .andExpect(status().isCreated());
    }

    // Boundary (max)
    @Test
    void givenEmailLength255_whenCreateUser_thenReturnsBadRequest() throws Exception {
        String emailTooLong = buildEmailWithLength255();

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Too",
                                  "lastName": "Long",
                                  "email": "%s",
                                  "password": "password123"
                                }
                                """.formatted(emailTooLong)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    // Boundary (max)
    @Test
    void givenPasswordAboveMaxLength_whenCreateUser_thenReturnsBadRequest() throws Exception {
        String longPassword = "p".repeat(73);
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Long",
                                  "lastName": "Password",
                                  "email": "long.password@example.com",
                                  "password": "%s"
                                }
                                """.formatted(longPassword)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    // Boundary (max)
    @Test
    void givenPasswordAboveMaxLength_whenCheckUser_thenReturnsBadRequest() throws Exception {
        String longPassword = "p".repeat(73);
        mockMvc.perform(post("/api/users/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "check.long.password@example.com",
                                  "password": "%s"
                                }
                                """.formatted(longPassword)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    // Boundary (max)
    @Test
    void givenEmailLength255_whenCheckUser_thenReturnsBadRequest() throws Exception {
        String emailTooLong = buildEmailWithLength255();
        mockMvc.perform(post("/api/users/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "password123"
                                }
                                """.formatted(emailTooLong)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    // Boundary (request size)
    @Test
    void givenPayloadAboveConfiguredLimit_whenCreateUser_thenReturnsPayloadTooLarge() throws Exception {
        String oversizedBody = "{\"payload\":\"" + "a".repeat(2_100) + "\"}";
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversizedBody))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message").value("payload too large"));
    }

    // Boundary (request size)
    @Test
    void givenPayloadAtConfiguredLimit_whenCheckUser_thenPassesSizeFilter() throws Exception {
        String bodyAtLimit = "{\"payload\":\"" + "a".repeat(2_034) + "\"}";
        mockMvc.perform(post("/api/users/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyAtLimit))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    // Complexity sanity check
    @Test
    void givenHighRequestVolumeWithinRateCapacity_whenCheckUser_thenResponsesRemainStable() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Load",
                                  "lastName": "Check",
                                  "email": "load.check@example.com",
                                  "password": "password123"
                                }
                                """))
                .andExpect(status().isCreated());

        String checkPayload = """
                {
                  "email": "load.check@example.com",
                  "password": "password123"
                }
                """;

        for (int requestIndex = 0; requestIndex < 200; requestIndex++) {
            mockMvc.perform(post("/api/users/check")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(checkPayload))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("valid"));
        }
    }

    // Happy path (security behavior)
    @Test
    void givenSecureHealthRequest_whenInvoked_thenSecurityHeadersArePresent() throws Exception {
        mockMvc.perform(get("/actuator/health")
                        .secure(true))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().exists("Strict-Transport-Security"));
    }

    // Adversarial
    @Test
    void givenInvalidJwt_whenGetUser_thenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/users/john@example.com")
                        .header("Authorization", "Bearer invalid.jwt.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("unauthorized"));
    }

    // Adversarial
    @Test
    void givenExpiredJwt_whenGetUser_thenReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Expired",
                                  "lastName": "Token",
                                  "email": "expired.token@example.com",
                                  "password": "password123"
                                }
                                """))
                .andExpect(status().isCreated());

        String expiredToken = buildExpiredToken("expired.token@example.com");

        mockMvc.perform(get("/api/users/expired.token@example.com")
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("unauthorized"));
    }

    private String buildExpiredToken(String subject) {
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(TEST_JWT_SECRET_B64));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(subject)
                .issuedAt(Date.from(now.minusSeconds(3600)))
                .expiration(Date.from(now.minusSeconds(60)))
                .signWith(key)
                .compact();
    }

    private String buildEmailWithLength254() {
        String local = "l".repeat(64);
        String domain = "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(61);
        return local + "@" + domain;
    }

    private String buildEmailWithLength255() {
        return "x" + buildEmailWithLength254();
    }

    private String buildValidToken(String subject) {
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(TEST_JWT_SECRET_B64));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(subject)
                .issuedAt(Date.from(now.minusSeconds(60)))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(key)
                .compact();
    }
}
