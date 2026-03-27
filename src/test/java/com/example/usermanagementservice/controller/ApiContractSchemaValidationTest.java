package com.example.usermanagementservice.controller;

import com.example.usermanagementservice.support.PostgresContainerTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("container")
@TestPropertySource(properties = {
        "app.rate-limit.capacity=1000",
        "app.rate-limit.refill-tokens=1000",
        "app.rate-limit.refill-minutes=1"
})
class ApiContractSchemaValidationTest extends PostgresContainerTestBase {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void apiResponsesMatchDocumentedSchemaContracts() throws Exception {
        String email = "contract.schema@example.com";

        MvcResult createResult = mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Contract",
                                  "lastName": "Schema",
                                  "email": "contract.schema@example.com",
                                  "password": "password123"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "contract.schema@example.com",
                                  "password": "password123"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode loginBody = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String token = loginBody.get("accessToken").asText();

        MvcResult getResult = mockMvc.perform(get("/api/users/" + email)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode createSchema = readSchema("contracts/create-user-response.schema.json");
        JsonNode loginSchema = readSchema("contracts/login-response.schema.json");
        JsonNode userSchema = readSchema("contracts/user-response.schema.json");

        assertTrue(validateSchema(createSchema, objectMapper.readTree(createResult.getResponse().getContentAsString())));
        assertTrue(validateSchema(loginSchema, loginBody));
        assertTrue(validateSchema(userSchema, objectMapper.readTree(getResult.getResponse().getContentAsString())));
    }

    private JsonNode readSchema(String classpathLocation) throws IOException {
        return objectMapper.readTree(getClass().getClassLoader().getResourceAsStream(classpathLocation));
    }

    private boolean validateSchema(JsonNode schema, JsonNode payload) {
        if (!"object".equals(schema.path("type").asText())) {
            return false;
        }

        for (JsonNode requiredField : schema.path("required")) {
            if (!payload.has(requiredField.asText())) {
                return false;
            }
        }

        JsonNode properties = schema.path("properties");
        Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String fieldName = entry.getKey();
            JsonNode fieldSchema = entry.getValue();
            JsonNode value = payload.get(fieldName);

            if (value == null) {
                continue;
            }

            String expectedType = fieldSchema.path("type").asText();
            if ("string".equals(expectedType) && !value.isTextual()) {
                return false;
            }

            if (fieldSchema.has("minLength") && value.asText().length() < fieldSchema.get("minLength").asInt()) {
                return false;
            }

            if (fieldSchema.has("enum")) {
                boolean matchesEnum = false;
                for (JsonNode enumValue : fieldSchema.get("enum")) {
                    if (enumValue.asText().equals(value.asText())) {
                        matchesEnum = true;
                        break;
                    }
                }
                if (!matchesEnum) {
                    return false;
                }
            }

            if (fieldSchema.has("pattern")) {
                Pattern pattern = Pattern.compile(fieldSchema.get("pattern").asText());
                if (!pattern.matcher(value.asText()).matches()) {
                    return false;
                }
            }
        }

        if (schema.path("additionalProperties").isBoolean() && !schema.path("additionalProperties").asBoolean()) {
            Iterator<String> payloadFields = payload.fieldNames();
            while (payloadFields.hasNext()) {
                String payloadField = payloadFields.next();
                if (!properties.has(payloadField)) {
                    return false;
                }
            }
        }

        return true;
    }
}
