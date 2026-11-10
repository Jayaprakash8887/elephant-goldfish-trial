package org.cce.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.cce.admin.entity.ApiPermissionEntity;
import org.cce.admin.entity.RoleEntity;
import org.cce.admin.entity.UserEntity;
import org.cce.admin.entity.UserStatus;
import org.cce.admin.repository.ApiPermissionRepository;
import org.cce.admin.repository.RoleRepository;
import org.cce.admin.repository.UserRepository;
import org.cce.admin.support.DatabaseTestBase;
import org.cce.admin.support.TestJwtFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Section 3, steps 1 to 5, through MockMvc with {@link TestJwtFactory} tokens (B-10 to B-12). */
class SecurityPipelineWebTest extends DatabaseTestBase {

    private static final String ME = "/admin/v1/me";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private RoleRepository roles;

    @Autowired
    private ApiPermissionRepository permissions;

    // ------------------------------------------------------------------------------ 401 (step 2)

    @Test
    void missingTokenIs401() throws Exception {
        expectUnauthenticated(mockMvc.perform(get(ME)));
    }

    @Test
    void malformedTokenIs401() throws Exception {
        expectUnauthenticated(mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer x")));
    }

    @Test
    void wronglySignedTokenIs401() throws Exception {
        UserEntity user = activeUser();
        expectUnauthenticated(call(TestJwtFactory.token().subject(user.getKeycloakId()).roles("SUPER_ADMIN")
                .signedWithOtherKey()));
    }

    @Test
    void expiredTokenIs401() throws Exception {
        UserEntity user = activeUser();
        Instant now = Instant.now();
        expectUnauthenticated(call(TestJwtFactory.token().subject(user.getKeycloakId()).roles("SUPER_ADMIN")
                .issuedAt(now.minusSeconds(600)).expiresAt(now.minusSeconds(120))));
    }

    @Test
    void wrongIssuerIs401() throws Exception {
        UserEntity user = activeUser();
        expectUnauthenticated(call(TestJwtFactory.token().subject(user.getKeycloakId()).roles("SUPER_ADMIN")
                .issuer("https://other-issuer.local/realms/cce")));
    }

    @Test
    void wrongAudienceIs401() throws Exception {
        UserEntity user = activeUser();
        expectUnauthenticated(call(TestJwtFactory.token().subject(user.getKeycloakId()).roles("SUPER_ADMIN")
                .audience("account")));
    }

    @Test
    void expiryWithinTheAllowedSkewIsAccepted() throws Exception {
        UserEntity user = activeUser();
        Instant now = Instant.now();
        call(TestJwtFactory.token().subject(user.getKeycloakId()).roles("SUPER_ADMIN")
                .issuedAt(now.minusSeconds(300)).expiresAt(now.minusSeconds(10)))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------- 403, local user (step 4)

    @Test
    void noLocalUserIs403() throws Exception {
        expectForbidden(call(TestJwtFactory.token().subject(UUID.randomUUID()).roles("SUPER_ADMIN")));
    }

    @Test
    void inactiveUserIs403() throws Exception {
        UserEntity user = users.save(user(UserStatus.INACTIVE));
        expectForbidden(call(TestJwtFactory.token().subject(user.getKeycloakId()).roles("SUPER_ADMIN")));
    }

    @Test
    void lockedUserIs403() throws Exception {
        UserEntity user = users.save(user(UserStatus.LOCKED));
        expectForbidden(call(TestJwtFactory.token().subject(user.getKeycloakId()).roles("SUPER_ADMIN")));
    }

    @Test
    void subjectThatIsNotAUuidIs403() throws Exception {
        expectForbidden(call(TestJwtFactory.token().subject("not-a-uuid").roles("SUPER_ADMIN")));
    }

    // ------------------------------------------------------------------- 403, permission (step 5)

    @Test
    void tokenWithNoRolesIs403() throws Exception {
        UserEntity user = activeUser();
        expectForbidden(call(TestJwtFactory.token().subject(user.getKeycloakId())))
                .andExpect(jsonPath("$.error.message").value("Your roles do not allow GET /admin/v1/me."));
    }

    @Test
    void noMatchingPermissionRowIs403() throws Exception {
        UserEntity user = activeUser();
        permissions.save(permission("PIPELINE_ROLE", "GET", "/admin/v1/users/**"));
        permissions.save(permission("PIPELINE_ROLE", "POST", "/admin/v1/**"));
        expectForbidden(call(TestJwtFactory.token().subject(user.getKeycloakId()).roles("PIPELINE_ROLE")))
                .andExpect(jsonPath("$.error.message").value("Your roles do not allow GET /admin/v1/me."));
    }

    @Test
    void aMatchingRowAllowsTheCall() throws Exception {
        UserEntity user = activeUser();
        roles.save(role("PIPELINE_ROLE"));
        permissions.save(permission("PIPELINE_ROLE", "GET", "/admin/v1/**"));
        call(TestJwtFactory.token().subject(user.getKeycloakId()).roles("offline_access", "PIPELINE_ROLE", "NO_SUCH_ROLE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.data.username").value(user.getUsername()))
                .andExpect(jsonPath("$.data.roles.length()").value(1))
                .andExpect(jsonPath("$.data.roles[0]").value("PIPELINE_ROLE"))
                .andExpect(jsonPath("$.data.facilityScope").value("FAC-1"));
    }

    @Test
    void meListsTokenRolesThatExistOrderedByName() throws Exception {
        UserEntity user = activeUser();
        roles.save(role("ZETA_ROLE"));
        call(TestJwtFactory.token().subject(user.getKeycloakId())
                .roles("ZETA_ROLE", "default-roles-cce", "SUPER_ADMIN", "offline_access"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles.length()").value(2))
                .andExpect(jsonPath("$.data.roles[0]").value("SUPER_ADMIN"))
                .andExpect(jsonPath("$.data.roles[1]").value("ZETA_ROLE"));
    }

    // ---------------------------------------------------------------------- public paths (step 1)

    @Test
    void healthNeedsNoToken() throws Exception {
        mockMvc.perform(get("/admin/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("UP"));
    }

    @Test
    void healthIgnoresABadToken() throws Exception {
        mockMvc.perform(get("/admin/v1/health").header(HttpHeaders.AUTHORIZATION, "Bearer x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    // -------------------------------------------------------------------- last_login_at (AD-05)

    @Test
    void lastLoginAtIsSetFromAuthTime() throws Exception {
        UserEntity user = activeUser();
        Instant updatedBefore = column("updated_at", user.getId());
        Instant authTime = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.SECONDS);
        call(TestJwtFactory.token().subject(user.getKeycloakId()).roles("SUPER_ADMIN").authTime(authTime))
                .andExpect(status().isOk());
        assertThat(column("last_login_at", user.getId())).isEqualTo(authTime);
        assertThat(column("updated_at", user.getId())).isEqualTo(updatedBefore);
    }

    @Test
    void aTokenWithoutAuthTimeLeavesLastLoginAtAlone() throws Exception {
        UserEntity user = activeUser();
        call(TestJwtFactory.token().subject(user.getKeycloakId()).roles("SUPER_ADMIN"))
                .andExpect(status().isOk());
        assertThat(column("last_login_at", user.getId())).isNull();
    }

    // -------------------------------------------------------------------------------- helpers

    private ResultActions call(TestJwtFactory.Builder token) throws Exception {
        return mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, token.bearer()));
    }

    private static void expectUnauthenticated(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.error.message").isString())
                .andExpect(jsonPath("$.error.details").doesNotExist())
                .andExpect(jsonPath("$.error.partialState").doesNotExist());
    }

    private static ResultActions expectForbidden(ResultActions result) throws Exception {
        return result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.error.message").isString());
    }

    private UserEntity activeUser() {
        return users.save(user(UserStatus.ACTIVE));
    }

    private static UserEntity user(UserStatus status) {
        String name = "pipeline-" + UUID.randomUUID().toString().substring(0, 8);
        UserEntity user = new UserEntity();
        user.setKeycloakId(UUID.randomUUID());
        user.setUsername(name);
        user.setEmail(name + "@cce.local");
        user.setFirstName("Pipe");
        user.setLastName("Line");
        user.setStatus(status);
        user.setFacilityScope("FAC-1");
        return user;
    }

    private static RoleEntity role(String name) {
        RoleEntity role = new RoleEntity();
        role.setName(name);
        role.setDisplayName(name);
        role.setAssignable(true);
        return role;
    }

    private static ApiPermissionEntity permission(String name, String method, String pattern) {
        ApiPermissionEntity row = new ApiPermissionEntity();
        row.setPermissionName(name);
        row.setHttpMethod(method);
        row.setUriPattern(pattern);
        return row;
    }

    private Instant column(String column, UUID userId) {
        Timestamp value = jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM users WHERE id = ?", Timestamp.class, userId);
        return value == null ? null : value.toInstant();
    }
}
