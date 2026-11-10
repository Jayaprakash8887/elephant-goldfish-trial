package org.cce.admin.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.cce.admin.entity.UserEntity;
import org.cce.admin.entity.UserStatus;
import org.cce.admin.repository.UserRepository;
import org.cce.admin.support.DatabaseTestBase;
import org.cce.admin.support.TestJwtFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Section 3, step 5 turned off by {@code PERMISSION_CHECK_ENABLED=false} (AD-17). */
@TestPropertySource(properties = "admin.permission.check-enabled=false")
class PermissionCheckOffWebTest extends DatabaseTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Test
    void aCallerWithNoMatchingRowIsAllowed() throws Exception {
        UserEntity user = users.save(activeUser());
        mockMvc.perform(get("/admin/v1/me").header(HttpHeaders.AUTHORIZATION,
                        TestJwtFactory.token().subject(user.getKeycloakId()).roles("ROLE_WITHOUT_ROWS").bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(user.getId().toString()));
    }

    @Test
    void aCallerWithNoRolesIsAllowed() throws Exception {
        UserEntity user = users.save(activeUser());
        mockMvc.perform(get("/admin/v1/me").header(HttpHeaders.AUTHORIZATION,
                        TestJwtFactory.token().subject(user.getKeycloakId()).bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles.length()").value(0));
    }

    @Test
    void theLocalUserCheckStillApplies() throws Exception {
        mockMvc.perform(get("/admin/v1/me").header(HttpHeaders.AUTHORIZATION,
                        TestJwtFactory.token().subject(UUID.randomUUID()).roles("SUPER_ADMIN").bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    private static UserEntity activeUser() {
        String name = "check-off-" + UUID.randomUUID().toString().substring(0, 8);
        UserEntity user = new UserEntity();
        user.setKeycloakId(UUID.randomUUID());
        user.setUsername(name);
        user.setEmail(name + "@cce.local");
        user.setFirstName("Check");
        user.setLastName("Off");
        user.setStatus(UserStatus.ACTIVE);
        return user;
    }
}
