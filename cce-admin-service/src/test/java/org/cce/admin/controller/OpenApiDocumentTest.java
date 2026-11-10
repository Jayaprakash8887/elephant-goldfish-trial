package org.cce.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.cce.admin.support.DatabaseTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;

/** Every endpoint in {@code contract/endpoints.txt} appears in {@code /admin/v1/api-docs} (B-06). */
class OpenApiDocumentTest extends DatabaseTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void everyContractEndpointIsInTheOpenApiDocument() throws Exception {
        String body = mockMvc.perform(get("/admin/v1/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode paths = objectMapper.readTree(body).path("paths");

        List<String> endpoints = endpoints();
        assertThat(endpoints).isNotEmpty();
        for (String endpoint : endpoints) {
            String[] parts = endpoint.split(" ", 2);
            String method = parts[0].toLowerCase();
            String path = parts[1];
            assertThat(paths.path(path).has(method))
                    .as("OpenAPI document lists %s", endpoint)
                    .isTrue();
        }
    }

    @Test
    void theDocumentDeclaresTheBearerScheme() throws Exception {
        String body = mockMvc.perform(get("/admin/v1/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode scheme = objectMapper.readTree(body).path("components").path("securitySchemes").path("bearerAuth");
        assertThat(scheme.path("type").asText()).isEqualTo("http");
        assertThat(scheme.path("scheme").asText()).isEqualTo("bearer");
    }

    private static List<String> endpoints() throws IOException {
        String text = new ClassPathResource("contract/endpoints.txt").getContentAsString(StandardCharsets.UTF_8);
        return text.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
    }
}
