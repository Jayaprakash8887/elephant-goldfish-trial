package org.cce.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RolesClaimReaderTest {

    @Test
    void readsTheDefaultPath() {
        RolesClaimReader reader = new RolesClaimReader("realm_access.roles");
        Map<String, Object> claims = Map.of("realm_access", Map.of("roles", List.of("SUPER_ADMIN", "offline_access")));
        assertThat(reader.read(claims)).containsExactly("SUPER_ADMIN", "offline_access");
    }

    @Test
    void readsACustomPath() {
        RolesClaimReader reader = new RolesClaimReader("resource_access.cce.roles");
        Map<String, Object> claims = Map.of(
                "realm_access", Map.of("roles", List.of("IGNORED")),
                "resource_access", Map.of("cce", Map.of("roles", List.of("ROLE_A", "ROLE_B"))));
        assertThat(reader.read(claims)).containsExactly("ROLE_A", "ROLE_B");

        RolesClaimReader topLevel = new RolesClaimReader("groups");
        assertThat(topLevel.read(Map.of("groups", List.of("G1")))).containsExactly("G1");
    }

    @Test
    void aMissingClaimMeansNoRoles() {
        RolesClaimReader reader = new RolesClaimReader("realm_access.roles");
        assertThat(reader.read(Map.of())).isEmpty();
        assertThat(reader.read(Map.of("realm_access", Map.of()))).isEmpty();
        assertThat(reader.read(Map.of("sub", "x"))).isEmpty();
    }

    @Test
    void aClaimThatIsNotAListOfStringsMeansNoRoles() {
        RolesClaimReader reader = new RolesClaimReader("realm_access.roles");
        assertThat(reader.read(Map.of("realm_access", Map.of("roles", "SUPER_ADMIN")))).isEmpty();
        assertThat(reader.read(Map.of("realm_access", Map.of("roles", List.of("SUPER_ADMIN", 42))))).isEmpty();
        assertThat(reader.read(Map.of("realm_access", "not-an-object"))).isEmpty();
        assertThat(reader.read(Map.of("realm_access", Map.of("roles", Map.of("a", "b"))))).isEmpty();
    }
}
