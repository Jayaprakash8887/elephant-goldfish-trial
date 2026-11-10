package org.cce.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.cce.admin.client.SyncResult;
import org.cce.admin.entity.UserEntity;
import org.cce.admin.entity.UserStatus;
import org.cce.admin.repository.UserRepository;
import org.cce.admin.support.DatabaseTestBase;
import org.cce.admin.support.TestJwtFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The JSON shapes of section 4.4 for the role and menu endpoints, through MockMvc (B-03, B-04). */
class RoleAndMenuContractWebTest extends DatabaseTestBase {

    private static final String ROLES = "/admin/v1/roles";
    private static final String MENUS = "/admin/v1/menus";
    private static final String COMPLIANCE = "10000000-0000-4000-8000-000000000003";
    private static final String PROTOCOL_DETAIL = "10000000-0000-4000-8000-000000000011";
    /** An ISO-8601 UTC instant with 0 to 6 fractional digits (section 4.1). */
    private static final String INSTANT = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,6})?Z$";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private String token;

    @BeforeEach
    void admin() {
        UserEntity admin = new UserEntity();
        admin.setKeycloakId(UUID.randomUUID());
        admin.setUsername("contract-admin");
        admin.setEmail("contract-admin@cce.local");
        admin.setFirstName("Contract");
        admin.setLastName("Admin");
        admin.setStatus(UserStatus.ACTIVE);
        token = TestJwtFactory.token().subject(userRepository.save(admin).getKeycloakId()).roles("SUPER_ADMIN").bearer();
    }

    // ------------------------------------------------------------------------------------ roles

    @Test
    void createReturns201WithLocationAndTheRoleShape() throws Exception {
        when(gatewaySyncClient.sync()).thenReturn(new SyncResult(true, 200, null));

        ResultActions result = call(post(ROLES), "{\"name\":\" CONTRACT_ROLE \",\"displayName\":\"Contract\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("CONTRACT_ROLE"))
                .andExpect(jsonPath("$.data.isSystem").value(false))
                .andExpect(jsonPath("$.data.isAssignable").value(true))
                .andExpect(jsonPath("$.data.syncStatus").value("SYNCED"))
                .andExpect(jsonPath("$.data.lastSyncAt").value(matchesPattern(INSTANT)))
                .andExpect(jsonPath("$.data.createdAt").value(matchesPattern(INSTANT)))
                .andExpect(jsonPath("$.data.updatedAt").value(matchesPattern(INSTANT)))
                .andExpect(jsonPath("$.data.userCount").value(0))
                .andExpect(jsonPath("$.warnings").doesNotExist())
                .andExpect(jsonPath("$.pagination").doesNotExist());
        JsonNode data = body(result).path("data");
        assertThat(data.has("description")).isTrue();
        assertThat(data.path("description").isNull()).isTrue();
        assertThat(data.has("system")).isFalse();
        assertThat(data.has("assignable")).isFalse();
        assertThat(data.size()).isEqualTo(11);
        result.andExpect(header().string(HttpHeaders.LOCATION, "/admin/v1/roles/" + data.path("id").asText()));
    }

    @Test
    void aFailedFirstSyncGivesAWarningWithOnlyCodeAndMessage() throws Exception {
        when(gatewaySyncClient.sync()).thenReturn(new SyncResult(false, 503, "HTTP 503"));

        JsonNode warning = body(call(post(ROLES), "{\"name\":\"UNSYNCED_ROLE\",\"displayName\":\"Unsynced\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.syncStatus").value("FAILED")))
                .path("warnings").get(0);
        assertThat(warning.path("code").asText()).isEqualTo("ROLE_SYNC_FAILED");
        assertThat(warning.path("message").asText()).isNotBlank();
        assertThat(warning.size()).isEqualTo(2);
    }

    @Test
    void createValidatesTheBody() throws Exception {
        call(post(ROLES), "{\"name\":\"lower_case\",\"displayName\":\"x\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("name"));
        call(post(ROLES), "{\"name\":\"OK_NAME\",\"displayName\":\"   \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("displayName"));
        call(post(ROLES), "{\"name\":\"SUPER_ADMIN\",\"displayName\":\"x\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_ROLE_NAME"));
    }

    @Test
    void listIsPaginated() throws Exception {
        call(get(ROLES + "?pageSize=1&page=2"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("SUPER_ADMIN"))
                .andExpect(jsonPath("$.pagination.page").value(2))
                .andExpect(jsonPath("$.pagination.pageSize").value(1))
                .andExpect(jsonPath("$.pagination.totalItems").value(2))
                .andExpect(jsonPath("$.pagination.totalPages").value(2));
        call(get(ROLES + "?page=9"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.pagination.totalItems").value(2));
        call(get(ROLES + "?pageSize=101"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("pageSize"));
        call(get(ROLES + "?page=x"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("page"));
    }

    @Test
    void putRefusesTheNameAsAnUnknownProperty() throws Exception {
        String id = createRole("RENAMED_ROLE");
        call(put(ROLES + "/" + id), "{\"displayName\":\"x\",\"description\":null,\"name\":\"OTHER\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("name"));
        call(put(ROLES + "/20000000-0000-4000-8000-000000000001"), "{\"displayName\":\"x\",\"description\":null}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SYSTEM_ROLE_IMMUTABLE"));
        call(get(ROLES + "/not-a-uuid"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("id"));
        call(get(ROLES + "/" + UUID.randomUUID()), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ROLE_NOT_FOUND"));
    }

    @Test
    void menusReturnTheDetailWarningWithBothReferenceFields() throws Exception {
        String id = createRole("WARNED_ROLE");

        JsonNode response = body(call(put(ROLES + "/" + id + "/menus"),
                "{\"menuIds\":[\"" + PROTOCOL_DETAIL + "\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roleId").value(id))
                .andExpect(jsonPath("$.data.menuIds[0]").value(PROTOCOL_DETAIL)));
        JsonNode warning = response.path("warnings").get(0);
        assertThat(warning.path("code").asText()).isEqualTo("DETAIL_WITHOUT_PARENT");
        assertThat(warning.path("menuId").asText()).isEqualTo(PROTOCOL_DETAIL);
        assertThat(warning.path("parentMenuId").asText()).isEqualTo(COMPLIANCE);
        assertThat(warning.size()).isEqualTo(4);

        call(get(ROLES + "/" + id + "/menus"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.menuIds[0]").value(PROTOCOL_DETAIL))
                .andExpect(jsonPath("$.warnings").doesNotExist());
        call(put(ROLES + "/" + id + "/menus"), "{\"menuIds\":[\"" + COMPLIANCE + "\",\"" + COMPLIANCE + "\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("menuIds[1]"));
    }

    @Test
    void permissionsCarryTheImplicitFlagAndIntegerIds() throws Exception {
        String id = createRole("FLAGGED_ROLE");
        when(gatewaySyncClient.sync()).thenReturn(new SyncResult(true, 200, null));

        call(put(ROLES + "/" + id + "/permissions"),
                "{\"permissions\":[{\"httpMethod\":\"get\",\"uriPattern\":\"/admin/v1/users\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roleId").value(id))
                .andExpect(jsonPath("$.data.syncStatus").value("SYNCED"))
                .andExpect(jsonPath("$.data.permissions.length()").value(3))
                .andExpect(jsonPath("$.data.permissions[0].implicit").value(true))
                .andExpect(jsonPath("$.data.permissions[1].implicit").value(true))
                .andExpect(jsonPath("$.data.permissions[2].implicit").value(false))
                .andExpect(jsonPath("$.data.permissions[2].httpMethod").value("GET"))
                .andExpect(jsonPath("$.data.permissions[2].description").isEmpty());
        JsonNode row = body(call(get(ROLES + "/" + id + "/permissions"), null)).path("data").path("permissions").get(2);
        assertThat(row.path("id").isIntegralNumber()).isTrue();
        assertThat(row.has("description")).isTrue();
        assertThat(row.has("resourceCategory")).isTrue();

        call(put(ROLES + "/" + id + "/permissions"),
                "{\"permissions\":[{\"httpMethod\":\"GET\",\"uriPattern\":\"/a\"},{\"httpMethod\":\"GET\",\"uriPattern\":\"/a\"}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("permissions[1].uriPattern"));
        call(put(ROLES + "/" + id + "/permissions"), "{\"permissions\":[{\"uriPattern\":\"/a\"}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("permissions[0].httpMethod"));
    }

    @Test
    void syncReturnsTheResultAndA502CarriesTheFullPartialState() throws Exception {
        String id = createRole("SYNCED_ROLE");
        when(gatewaySyncClient.sync()).thenReturn(new SyncResult(true, 200, null));
        call(post(ROLES + "/" + id + "/sync"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roleId").value(id))
                .andExpect(jsonPath("$.data.syncStatus").value("SYNCED"))
                .andExpect(jsonPath("$.data.lastSyncAt").value(matchesPattern(INSTANT)));

        when(gatewaySyncClient.sync()).thenReturn(new SyncResult(false, 503, "HTTP 503"));
        JsonNode state = body(call(post(ROLES + "/" + id + "/sync"), null)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("KEYCLOAK_SYNC_FAILED")))
                .path("error").path("partialState");
        assertThat(state.path("operation").asText()).isEqualTo("ROLE_SYNC");
        assertThat(state.path("entityId").asText()).isEqualTo(id);
        assertThat(state.path("failedStep").asText()).isEqualTo("GATEWAY_SYNC");
        assertThat(state.path("completedSteps")).isEmpty();
        assertThat(state.has("keycloakUserId")).isTrue();
        assertThat(state.path("keycloakUserId").isNull()).isTrue();
        assertThat(state.path("auditLogId").isTextual()).isTrue();
    }

    @Test
    void deleteOfARoleIs204WithNoBody() throws Exception {
        String id = createRole("DELETED_ROLE");
        call(delete(ROLES + "/" + id), null)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    // ------------------------------------------------------------------------------------ menus

    @Test
    void theMenuTreeWritesEveryField() throws Exception {
        JsonNode tree = body(call(get(MENUS), null).andExpect(status().isOk())).path("data");
        assertThat(tree).hasSize(10);
        JsonNode compliance = tree.get(2);
        assertThat(compliance.path("key").asText()).isEqualTo("compliance");
        assertThat(compliance.path("isActive").asBoolean()).isTrue();
        assertThat(compliance.has("parentId")).isTrue();
        assertThat(compliance.path("parentId").isNull()).isTrue();
        assertThat(compliance.has("active")).isFalse();
        JsonNode child = compliance.path("children").get(0);
        assertThat(child.path("id").asText()).isEqualTo(PROTOCOL_DETAIL);
        assertThat(child.path("type").asText()).isEqualTo("DETAIL");
        assertThat(child.has("icon")).isTrue();
        assertThat(child.path("icon").isNull()).isTrue();
        assertThat(child.path("children").isArray()).isTrue();
        assertThat(child.path("children")).isEmpty();
        assertThat(child.size()).isEqualTo(10);
    }

    @Test
    void createMenuReturns201WithLocationAndDefaultsToActive() throws Exception {
        JsonNode data = body(call(post(MENUS),
                "{\"key\":\"reports\",\"label\":\"Reports\",\"path\":\"/reports\",\"type\":\"NAV\",\"sortOrder\":110}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.isActive").value(true))
                .andExpect(jsonPath("$.data.children.length()").value(0)))
                .path("data");
        String id = data.path("id").asText();
        assertThat(data.has("icon")).isTrue();

        call(put(MENUS + "/" + id),
                "{\"label\":\"Reports\",\"path\":\"/reports\",\"icon\":null,\"parentId\":null,\"sortOrder\":110,"
                        + "\"isActive\":true,\"key\":\"renamed\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("key"));
        call(put(MENUS + "/" + id),
                "{\"label\":\"Reports\",\"path\":\"/reports\",\"sortOrder\":110,\"isActive\":true,\"type\":\"DETAIL\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("type"));
        call(put(MENUS + "/" + id),
                "{\"label\":\"Reports\",\"path\":\"/reports\",\"sortOrder\":110,\"isActive\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isActive").value(false));
        call(delete(MENUS + "/" + id), null)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    void createMenuValidatesTheBody() throws Exception {
        call(post(MENUS), "{\"key\":\"Bad Key\",\"label\":\"x\",\"path\":\"/x\",\"type\":\"NAV\",\"sortOrder\":1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("key"));
        call(post(MENUS), "{\"key\":\"ok\",\"label\":\"x\",\"path\":\"/x\",\"icon\":\"chart\",\"type\":\"NAV\",\"sortOrder\":1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("icon"));
        call(post(MENUS), "{\"key\":\"ok\",\"label\":\"x\",\"path\":\"x\",\"type\":\"NAV\",\"sortOrder\":10001}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.length()").value(2));
        call(post(MENUS), "{\"key\":\"ok\",\"label\":\"x\",\"path\":\"/x\",\"type\":\"ROUTE\",\"sortOrder\":1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("type"));
        call(post(MENUS), "{\"key\":\"ok\",\"label\":\"x\",\"path\":\"/x\",\"type\":\"DETAIL\",\"sortOrder\":1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("parentId"));
        call(post(MENUS), "{\"key\":\"dashboard\",\"label\":\"x\",\"path\":\"/x\",\"type\":\"NAV\",\"sortOrder\":1}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_MENU_KEY"));
    }

    @Test
    void menuErrorsUseTheirCodes() throws Exception {
        call(put(MENUS + "/" + UUID.randomUUID()), "{\"label\":\"x\",\"path\":\"/x\",\"sortOrder\":1,\"isActive\":true}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("MENU_NOT_FOUND"));
        call(delete(MENUS + "/" + COMPLIANCE), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MENU_HAS_CHILDREN"));
    }

    // -------------------------------------------------------------------------------- helpers

    private String createRole(String name) throws Exception {
        when(gatewaySyncClient.sync()).thenReturn(new SyncResult(true, 200, null));
        return body(call(post(ROLES), "{\"name\":\"" + name + "\",\"displayName\":\"" + name + "\"}")
                .andExpect(status().isCreated())).path("data").path("id").asText();
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String json) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, token);
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
