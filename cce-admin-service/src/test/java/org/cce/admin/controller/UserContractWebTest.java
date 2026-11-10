package org.cce.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.cce.admin.client.KeycloakCallFailedException;
import org.cce.admin.controller.dto.UserDtos.User;
import org.cce.admin.entity.RoleEntity;
import org.cce.admin.entity.UserEntity;
import org.cce.admin.entity.UserRoleEntity;
import org.cce.admin.entity.UserStatus;
import org.cce.admin.repository.RoleRepository;
import org.cce.admin.repository.UserRepository;
import org.cce.admin.repository.UserRoleRepository;
import org.cce.admin.service.UserViews;
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

/** The {@code /users} endpoints through MockMvc: shapes, pagination, filters, search (B-03, B-04, B-30). */
class UserContractWebTest extends DatabaseTestBase {

    private static final String USERS = "/admin/v1/users";
    /** An ISO-8601 UTC instant with 0 to 6 fractional digits (section 4.1). */
    private static final String INSTANT = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,6})?Z$";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private String token;
    private UserEntity admin;
    private RoleEntity viewer;

    @BeforeEach
    void setUp() {
        admin = user("aa-admin", "aa-admin@cce.local", "Contract", "Admin", UserStatus.ACTIVE, null);
        token = TestJwtFactory.token().subject(admin.getKeycloakId()).roles("SUPER_ADMIN").bearer();
        viewer = new RoleEntity();
        viewer.setName("CONTRACT_VIEWER");
        viewer.setDisplayName("Contract viewer");
        viewer.setSystem(false);
        viewer.setAssignable(true);
        viewer = roleRepository.save(viewer);
        when(keycloakGateway.listRealmRoleNames()).thenReturn(Set.of("CONTRACT_VIEWER", "SUPER_ADMIN"));
        when(keycloakGateway.createUser(anyString(), anyString(), anyString(), anyString(), anyList()))
                .thenAnswer(invocation -> UUID.randomUUID());
    }

    private UserEntity user(String username, String email, String firstName, String lastName, UserStatus status,
            String facilityScope) {
        UserEntity user = new UserEntity();
        user.setKeycloakId(UUID.randomUUID());
        user.setUsername(username);
        user.setEmail(email);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setStatus(status);
        user.setFacilityScope(facilityScope);
        return userRepository.save(user);
    }

    private void hold(UserEntity user, RoleEntity role) {
        UserRoleEntity userRole = new UserRoleEntity();
        userRole.setUserId(user.getId());
        userRole.setRoleId(role.getId());
        userRoleRepository.save(userRole);
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String createBody(String username) {
        return "{\"username\":\"" + username + "\",\"email\":\"" + username + "@CCE.local\",\"firstName\":\" New \","
                + "\"lastName\":\"Person\",\"roleIds\":[\"" + viewer.getId() + "\"]}";
    }

    private ResultActions search(String q) throws Exception {
        return call(get(USERS).param("q", q), null);
    }

    private List<String> usernames(ResultActions result) throws Exception {
        return body(result).path("data").findValuesAsText("username");
    }

    // ------------------------------------------------------------------------------- create

    @Test
    void createReturns201WithLocationAndTheUserShape() throws Exception {
        ResultActions result = call(post(USERS), createBody("contract-new"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.username").value("contract-new"))
                .andExpect(jsonPath("$.data.email").value("contract-new@cce.local"))
                .andExpect(jsonPath("$.data.firstName").value("New"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.roles[0].name").value("CONTRACT_VIEWER"))
                .andExpect(jsonPath("$.data.roles[0].displayName").value("Contract viewer"))
                .andExpect(jsonPath("$.data.createdAt").value(matchesPattern(INSTANT)))
                .andExpect(jsonPath("$.data.updatedAt").value(matchesPattern(INSTANT)))
                .andExpect(jsonPath("$.warnings").doesNotExist())
                .andExpect(jsonPath("$.pagination").doesNotExist());
        JsonNode data = body(result).path("data");
        assertThat(data.size()).isEqualTo(12);
        assertThat(data.has("facilityScope") && data.path("facilityScope").isNull()).isTrue();
        assertThat(data.has("lastLoginAt") && data.path("lastLoginAt").isNull()).isTrue();
        assertThat(data.path("roles").get(0).size()).isEqualTo(3);
        result.andExpect(header().string(HttpHeaders.LOCATION, "/admin/v1/users/" + data.path("id").asText()));
    }

    @Test
    void aFailedEmailGivesA201WithAWarningOfOnlyCodeAndMessage() throws Exception {
        doThrow(new KeycloakCallFailedException("SMTP down", 500, null))
                .when(keycloakGateway).sendExecuteActionsEmail(any(), anyList());

        JsonNode warnings = body(call(post(USERS), createBody("no-email"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.username").value("no-email")))
                .path("warnings");
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).path("code").asText()).isEqualTo("ACTION_EMAIL_NOT_SENT");
        assertThat(warnings.get(0).path("message").asText()).isNotBlank();
        assertThat(warnings.get(0).size()).isEqualTo(2);
    }

    @Test
    void createValidatesTheBody() throws Exception {
        call(post(USERS), createBody("Upper-Case"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("username"));
        call(post(USERS), "{\"username\":\"ok-user\",\"email\":\"not-an-address\",\"firstName\":\"A\","
                + "\"lastName\":\"B\",\"roleIds\":[\"" + viewer.getId() + "\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("email"));
        call(post(USERS), "{\"username\":\"ok-user\",\"email\":\"a@cce.local\",\"firstName\":\"A\","
                + "\"lastName\":\"B\",\"roleIds\":[]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("roleIds"));
        call(post(USERS), "{\"username\":\"ok-user\",\"email\":\"a@cce.local\",\"firstName\":\"A\","
                + "\"lastName\":\"B\",\"roleIds\":[\"" + viewer.getId() + "\"],\"status\":\"ACTIVE\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("status"));
        call(post(USERS), "{\"username\":\"ok-user\",\"email\":\"a@cce.local\",\"firstName\":\"A\","
                + "\"lastName\":\"B\",\"roleIds\":[\"" + UUID.randomUUID() + "\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("roleIds[0]"));
    }

    @Test
    void roleNotAssignableAndRoleNotSyncedCarryDetailsPerRole() throws Exception {
        call(post(USERS), "{\"username\":\"ok-user\",\"email\":\"a@cce.local\",\"firstName\":\"A\",\"lastName\":\"B\","
                + "\"roleIds\":[\"" + viewer.getId() + "\",\"20000000-0000-4000-8000-000000000002\"]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ROLE_NOT_ASSIGNABLE"))
                .andExpect(jsonPath("$.error.details.length()").value(1))
                .andExpect(jsonPath("$.error.details[0].field").value("roleIds[1]"));
        when(keycloakGateway.listRealmRoleNames()).thenReturn(Set.of());
        call(post(USERS), createBody("unsynced"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ROLE_NOT_SYNCED"))
                .andExpect(jsonPath("$.error.details[0].field").value("roleIds[0]"));
    }

    // ------------------------------------------------------------------- get, update, status, roles

    @Test
    void getUpdateStatusAndRolesReturnTheUserAndTheirErrors() throws Exception {
        UserEntity target = user("target", "target@cce.local", "Tar", "Get", UserStatus.ACTIVE, null);
        String path = USERS + "/" + target.getId();

        call(get(path), null).andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value("target"));
        call(get(USERS + "/" + UUID.randomUUID()), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
        call(get(USERS + "/not-a-uuid"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("id"));

        call(put(path), "{\"email\":\"target@cce.local\",\"firstName\":\"Tar\",\"lastName\":\"Get\","
                + "\"facilityScope\":\"FAC-9\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.facilityScope").value("FAC-9"));
        call(put(path), "{\"email\":\"target@cce.local\",\"firstName\":\"Tar\",\"lastName\":\"Get\",\"username\":\"x\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("username"));

        call(patch(path + "/status"), "{\"status\":\"LOCKED\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("LOCKED"));
        call(patch(path + "/status"), "{\"status\":\"DELETED\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("status"));

        call(put(path + "/roles"), "{\"roleIds\":[\"" + viewer.getId() + "\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles[0].name").value("CONTRACT_VIEWER"));

        call(post(path + "/reset-password"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(target.getId().toString()))
                .andExpect(jsonPath("$.data.email").value("target@cce.local"))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void theLastAdministratorIs409() throws Exception {
        RoleEntity superAdmin = roleRepository.findByName("SUPER_ADMIN").orElseThrow();
        hold(admin, superAdmin);

        call(patch(USERS + "/" + admin.getId() + "/status"), "{\"status\":\"INACTIVE\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LAST_SYSTEM_ADMIN"));
        call(put(USERS + "/" + admin.getId() + "/roles"), "{\"roleIds\":[]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LAST_SYSTEM_ADMIN"));
    }

    // ------------------------------------------------------------- 4.1: at most 6 fractional digits

    @Test
    void everyUserResponseHasAtMostSixFractionalDigits() throws Exception {
        String id = body(call(post(USERS), createBody("precise-user")).andExpect(status().isCreated()))
                .path("data").path("id").asText();
        userRepository.updateLastLoginIfLater(UUID.fromString(id), Instant.parse("2026-11-10T09:15:00.123456789Z"));
        String path = USERS + "/" + id;

        List<ResultActions> responses = List.of(
                call(get(path), null),
                call(put(path), "{\"email\":\"precise@cce.local\",\"firstName\":\"P\",\"lastName\":\"U\"}"),
                call(patch(path + "/status"), "{\"status\":\"INACTIVE\"}"),
                call(put(path + "/roles"), "{\"roleIds\":[]}"),
                call(get(USERS + "?q=precise"), null));
        for (ResultActions response : responses) {
            response.andExpect(status().isOk());
            JsonNode data = body(response).path("data");
            JsonNode user = data.isArray() ? data.get(0) : data;
            assertThat(user.path("createdAt").asText()).matches(INSTANT);
            assertThat(user.path("updatedAt").asText()).matches(INSTANT);
            assertThat(user.path("lastLoginAt").asText()).matches(INSTANT);
        }

        // A User built from an entity that was never read back holds Instant.now()'s nanoseconds.
        UserEntity unsaved = new UserEntity();
        unsaved.setCreatedAt(Instant.parse("2026-11-10T09:15:00.123456789Z"));
        unsaved.setUpdatedAt(Instant.parse("2026-11-10T09:15:00.987654321Z"));
        User view = UserViews.toUser(unsaved, List.of());
        assertThat(view.createdAt()).isEqualTo(Instant.parse("2026-11-10T09:15:00.123456Z"));
        assertThat(objectMapper.writeValueAsString(view)).contains("\"updatedAt\":\"2026-11-10T09:15:00.987654Z\"");
    }

    // ---------------------------------------------------------------- list, pagination, filters

    @Test
    void listIsPaginatedAndOrderedByUsername() throws Exception {
        user("cc-user", "cc@cce.local", "C", "C", UserStatus.ACTIVE, null);
        user("bb-user", "bb@cce.local", "B", "B", UserStatus.ACTIVE, null);

        ResultActions page = call(get(USERS + "?pageSize=2&page=1"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.page").value(1))
                .andExpect(jsonPath("$.pagination.pageSize").value(2))
                .andExpect(jsonPath("$.pagination.totalItems").value(3))
                .andExpect(jsonPath("$.pagination.totalPages").value(2))
                .andExpect(jsonPath("$.warnings").doesNotExist());
        assertThat(usernames(page)).containsExactly("aa-admin", "bb-user");
        assertThat(usernames(call(get(USERS + "?pageSize=2&page=2"), null))).containsExactly("cc-user");
        call(get(USERS + "?page=5"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.pagination.totalItems").value(3))
                .andExpect(jsonPath("$.pagination.totalPages").value(1));
        call(get(USERS + "?pageSize=0"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("pageSize"));
        call(get(USERS + "?page=x"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("page"));
    }

    @Test
    void filtersMustAllMatch() throws Exception {
        UserEntity one = user("filter-one", "one@cce.local", "One", "Person", UserStatus.ACTIVE, "FAC-1");
        UserEntity two = user("filter-two", "two@cce.local", "Two", "Person", UserStatus.INACTIVE, "FAC-1");
        user("filter-three", "three@cce.local", "Three", "Person", UserStatus.INACTIVE, "FAC-10");
        hold(one, viewer);
        hold(two, viewer);

        assertThat(usernames(call(get(USERS + "?status=INACTIVE"), null)))
                .containsExactly("filter-three", "filter-two");
        assertThat(usernames(call(get(USERS + "?roleId=" + viewer.getId()), null)))
                .containsExactly("filter-one", "filter-two");
        assertThat(usernames(call(get(USERS + "?facilityScope=FAC-1"), null)))
                .containsExactly("filter-one", "filter-two");
        assertThat(usernames(call(get(USERS + "?status=INACTIVE&roleId=" + viewer.getId() + "&facilityScope=FAC-1"),
                null))).containsExactly("filter-two");
        call(get(USERS + "?roleId=" + UUID.randomUUID()), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.pagination.totalItems").value(0))
                .andExpect(jsonPath("$.pagination.totalPages").value(0));
        call(get(USERS + "?status=GONE"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("status"));
        call(get(USERS + "?roleId=nope"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("roleId"));
        assertThat(usernames(call(get(USERS + "?status=ACTIVE&unknown=1"), null)))
                .containsExactly("aa-admin", "filter-one");
    }

    @Test
    void searchMatchesSubstringsIgnoringCase() throws Exception {
        user("jdoe", "jane.doe@example.org", "Jane", "Doe", UserStatus.ACTIVE, null);
        user("other_1", "x%y@cce.local", "Mary", "Major", UserStatus.ACTIVE, null);

        assertThat(usernames(search("JDO"))).containsExactly("jdoe");
        assertThat(usernames(search("EXAMPLE.org"))).containsExactly("jdoe");
        assertThat(usernames(search("jan"))).containsExactly("jdoe");
        assertThat(usernames(search("doe"))).containsExactly("jdoe");
        assertThat(usernames(search("e d"))).containsExactly("jdoe");
        assertThat(usernames(search("%"))).containsExactly("other_1");
        assertThat(usernames(search("r_"))).containsExactly("other_1");
        assertThat(usernames(search(""))).hasSize(3);
        assertThat(usernames(search("   "))).hasSize(3);
        assertThat(usernames(search(" mary major "))).containsExactly("other_1");
        search("a".repeat(101))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("q"));
    }
}
