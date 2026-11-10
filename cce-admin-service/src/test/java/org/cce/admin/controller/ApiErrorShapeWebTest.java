package org.cce.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.cce.admin.controller.dto.CommonDtos.ApiResponse;
import org.cce.admin.controller.dto.CommonDtos.FieldErrorDto;
import org.cce.admin.controller.dto.CommonDtos.Pagination;
import org.cce.admin.controller.dto.CommonDtos.Warning;
import org.cce.admin.entity.UserEntity;
import org.cce.admin.entity.UserStatus;
import org.cce.admin.repository.UserRepository;
import org.cce.admin.service.exception.ApiException;
import org.cce.admin.service.exception.ErrorCode;
import org.cce.admin.service.exception.KeycloakSyncFailedException;
import org.cce.admin.service.exception.PartialState;
import org.cce.admin.support.DatabaseTestBase;
import org.cce.admin.support.TestJwtFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The envelope and null-field rules of section 4.1 and the error mapping of section 4.2 (B-03).
 *
 * <p>DESIGN-GAP: Phase 3 asks this test to cover an unknown property, malformed JSON, an unsupported
 * content type and a bad UUID, but Phase 3 builds no endpoint that takes a body or a path id. The
 * test therefore imports a test-only controller, {@link ShapeController}, under {@code /admin/v1/test-shapes}.
 * It exists only in this test class's context.
 */
@Import(ApiErrorShapeWebTest.ShapeController.class)
class ApiErrorShapeWebTest extends DatabaseTestBase {

    private static final String BASE = "/admin/v1/test-shapes";

    @RestController
    @RequestMapping(BASE)
    static class ShapeController {

        record Item(@NotBlank String value) {
        }

        record EchoRequest(@NotBlank @Size(max = 5) String name, List<@Valid Item> items) {
        }

        record EchoResponse(String name, Integer count, String nothing) {
        }

        @PostMapping("/echo")
        ApiResponse<EchoResponse> echo(@Valid @RequestBody EchoRequest request) {
            return ApiResponse.of(new EchoResponse(request.name(),
                    request.items() == null ? null : request.items().size(), null));
        }

        @GetMapping("/by-id/{id}")
        ApiResponse<UUID> byId(@PathVariable UUID id) {
            return ApiResponse.of(id);
        }

        @GetMapping("/page")
        ApiResponse<List<String>> page(@Valid PageParams pageParams) {
            return ApiResponse.of(List.of(), pageParams.toPagination(0));
        }

        @GetMapping("/warnings")
        ApiResponse<String> warnings() {
            return ApiResponse.withWarnings("saved", List.of(
                    Warning.detailWithoutParent("Granted without its parent.",
                            UUID.fromString("10000000-0000-4000-8000-000000000011"),
                            UUID.fromString("10000000-0000-4000-8000-000000000003")),
                    Warning.of("ACTION_EMAIL_NOT_SENT", "The email could not be sent.")));
        }

        @GetMapping("/no-warnings")
        ApiResponse<String> noWarnings() {
            return ApiResponse.withWarnings("saved", List.of());
        }

        @GetMapping("/partial")
        ApiResponse<String> partial() {
            throw new KeycloakSyncFailedException("The gateway sync failed.", new PartialState("ROLE_SYNC",
                    UUID.fromString("20000000-0000-4000-8000-000000000001"), "GATEWAY_SYNC", List.of(), null,
                    UUID.fromString("30000000-0000-4000-8000-000000000001")));
        }

        @GetMapping("/not-synced")
        ApiResponse<String> notSynced() {
            throw new ApiException(ErrorCode.ROLE_NOT_SYNCED, "A role has no realm role yet.",
                    List.of(new FieldErrorDto("roleIds[1]", "Not synced.")));
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private ObjectMapper objectMapper;

    private String bearer;
    private UserEntity user;

    @BeforeEach
    void signIn() {
        String name = "shape-" + UUID.randomUUID().toString().substring(0, 8);
        UserEntity entity = new UserEntity();
        entity.setKeycloakId(UUID.randomUUID());
        entity.setUsername(name);
        entity.setEmail(name + "@cce.local");
        entity.setFirstName("Shape");
        entity.setLastName("Test");
        entity.setStatus(UserStatus.ACTIVE);
        user = users.save(entity);
        bearer = TestJwtFactory.token().subject(user.getKeycloakId()).roles("SUPER_ADMIN").bearer();
    }

    // --------------------------------------------------------------------------- success envelope

    @Test
    void successEnvelopeWritesNullFieldsAndLeavesOutTheOptionalBlocks() throws Exception {
        JsonNode body = json(perform(get("/admin/v1/me")).andExpect(status().isOk()));
        assertThat(fieldNames(body)).containsExactly("data");
        JsonNode data = body.get("data");
        assertThat(fieldNames(data)).containsExactly("id", "username", "roles", "facilityScope");
        assertThat(data.get("facilityScope").isNull()).isTrue();
    }

    @Test
    void nullFieldsInsideDataAreWritten() throws Exception {
        JsonNode body = json(perform(postJson(BASE + "/echo", "{\"name\":\"  ab \"}")).andExpect(status().isOk()));
        assertThat(fieldNames(body)).containsExactly("data");
        assertThat(body.at("/data/name").asText()).isEqualTo("ab");
        assertThat(body.get("data").has("count")).isTrue();
        assertThat(body.at("/data/count").isNull()).isTrue();
        assertThat(body.at("/data/nothing").isNull()).isTrue();
    }

    @Test
    void warningsCarryExactlyTheFieldsOfTheirCode() throws Exception {
        JsonNode body = json(perform(get(BASE + "/warnings")).andExpect(status().isOk()));
        assertThat(fieldNames(body)).containsExactly("data", "warnings");
        JsonNode detail = body.at("/warnings/0");
        assertThat(fieldNames(detail)).containsExactly("code", "message", "menuId", "parentMenuId");
        assertThat(detail.get("code").asText()).isEqualTo("DETAIL_WITHOUT_PARENT");
        JsonNode email = body.at("/warnings/1");
        assertThat(fieldNames(email)).containsExactly("code", "message");
    }

    @Test
    void anEmptyWarningListIsLeftOut() throws Exception {
        JsonNode body = json(perform(get(BASE + "/no-warnings")).andExpect(status().isOk()));
        assertThat(fieldNames(body)).containsExactly("data");
    }

    @Test
    void paginationBlockAndPageParamDefaults() throws Exception {
        perform(get(BASE + "/page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.pagination.page").value(1))
                .andExpect(jsonPath("$.pagination.pageSize").value(20))
                .andExpect(jsonPath("$.pagination.totalItems").value(0))
                .andExpect(jsonPath("$.pagination.totalPages").value(0));
        perform(get(BASE + "/page?page=3&pageSize=100&unknown=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.page").value(3))
                .andExpect(jsonPath("$.pagination.pageSize").value(100));
        assertThat(new PageParams(1, 20).toPagination(41)).isEqualTo(new Pagination(1, 20, 41, 3));
    }

    @Test
    void pageParamsOutOfBoundsOrNotIntegersAre400() throws Exception {
        expectValidation(perform(get(BASE + "/page?page=0")), "page");
        expectValidation(perform(get(BASE + "/page?pageSize=0")), "pageSize");
        expectValidation(perform(get(BASE + "/page?pageSize=101")), "pageSize");
        expectValidation(perform(get(BASE + "/page?page=abc")), "page");
        expectValidation(perform(get(BASE + "/page?pageSize=1.5")), "pageSize");
    }

    // ------------------------------------------------------------------------------- 400 errors

    @Test
    void unknownPropertyIs400() throws Exception {
        expectValidation(perform(postJson(BASE + "/echo", "{\"name\":\"ab\",\"extra\":1}")), "extra");
    }

    @Test
    void unknownNestedPropertyIs400WithItsPath() throws Exception {
        expectValidation(perform(postJson(BASE + "/echo",
                "{\"name\":\"ab\",\"items\":[{\"value\":\"a\"},{\"value\":\"b\",\"bogus\":true}]}")), "items[1].bogus");
    }

    @Test
    void malformedJsonIs400OnBody() throws Exception {
        expectValidation(perform(postJson(BASE + "/echo", "{\"name\":")), "body");
    }

    @Test
    void missingBodyIs400OnBody() throws Exception {
        expectValidation(perform(post(BASE + "/echo").contentType(MediaType.APPLICATION_JSON)), "body");
    }

    @Test
    void unsupportedContentTypeIs400() throws Exception {
        expectValidation(perform(post(BASE + "/echo").contentType(MediaType.TEXT_PLAIN).content("name=ab")), "body");
    }

    @Test
    void beanValidationIs400WithPropertyPaths() throws Exception {
        expectValidation(perform(postJson(BASE + "/echo", "{\"name\":\"toolong\"}")), "name");
        expectValidation(perform(postJson(BASE + "/echo", "{\"name\":\"ab\",\"items\":[{\"value\":\"a\"},{\"value\":\"  \"}]}")),
                "items[1].value");
    }

    @Test
    void aStringThatIsEmptyAfterTrimmingIsNull() throws Exception {
        expectValidation(perform(postJson(BASE + "/echo", "{\"name\":\"   \"}")), "name");
    }

    @Test
    void aBadUuidInThePathIs400OnTheParameter() throws Exception {
        expectValidation(perform(get(BASE + "/by-id/not-a-uuid")), "id");
        UUID id = UUID.randomUUID();
        perform(get(BASE + "/by-id/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.data").value(id.toString()));
    }

    // --------------------------------------------------------------------------- 404, 405, others

    @Test
    void anUnknownPathIs404NotFound() throws Exception {
        expectError(perform(get("/admin/v1/no-such-endpoint")), 404, "NOT_FOUND");
    }

    @Test
    void anUnsupportedMethodIs405() throws Exception {
        expectError(perform(delete("/admin/v1/me")), 405, "METHOD_NOT_ALLOWED");
    }

    @Test
    void apiExceptionCarriesItsDetails() throws Exception {
        JsonNode body = json(expectError(perform(get(BASE + "/not-synced")), 409, "ROLE_NOT_SYNCED"));
        assertThat(body.at("/error/details/0/field").asText()).isEqualTo("roleIds[1]");
        assertThat(body.get("error").has("partialState")).isFalse();
    }

    @Test
    void keycloakSyncFailedCarriesTheFullPartialState() throws Exception {
        JsonNode body = json(expectError(perform(get(BASE + "/partial")), 502, "KEYCLOAK_SYNC_FAILED"));
        JsonNode error = body.get("error");
        assertThat(fieldNames(error)).containsExactly("code", "message", "partialState");
        JsonNode state = error.get("partialState");
        assertThat(fieldNames(state)).containsExactly(
                "operation", "entityId", "failedStep", "completedSteps", "keycloakUserId", "auditLogId");
        assertThat(state.get("operation").asText()).isEqualTo("ROLE_SYNC");
        assertThat(state.get("completedSteps").isArray()).isTrue();
        assertThat(state.get("completedSteps").size()).isZero();
        assertThat(state.get("keycloakUserId").isNull()).isTrue();
        assertThat(state.get("auditLogId").asText()).isEqualTo("30000000-0000-4000-8000-000000000001");
    }

    // -------------------------------------------------------------------------------- helpers

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private static MockHttpServletRequestBuilder postJson(String path, String json) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private void expectValidation(ResultActions result, String field) throws Exception {
        JsonNode body = json(expectError(result, 400, "VALIDATION_ERROR"));
        JsonNode details = body.at("/error/details");
        assertThat(details.isArray()).as("details of %s", body).isTrue();
        assertThat(details.findValuesAsText("field")).as("details of %s", body).contains(field);
        assertThat(body.get("error").has("partialState")).isFalse();
    }

    private static ResultActions expectError(ResultActions result, int status, String code) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.error.message").isString());
    }

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
