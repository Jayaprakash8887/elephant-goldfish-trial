package org.cce.gatewaystub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Every rule of section 9.1's sync, as pure logic. */
class RealmRoleSyncPlannerTest {

    private static final String REALM = "cce";

    private static RealmRoles.RealmRole tagged(String name) {
        return new RealmRoles.RealmRole(name, Map.of(RealmRoles.MANAGED_ATTRIBUTE, List.of("true")));
    }

    private static RealmRoles.RealmRole untagged(String name) {
        return new RealmRoles.RealmRole(name, Map.of());
    }

    @Test
    void createsARoleForEveryPermissionNameThatHasNone() {
        RealmRoleSyncPlanner.Plan plan = RealmRoleSyncPlanner.plan(Set.of("ROLE_B", "ROLE_A", "EXISTING"),
                List.of(untagged("EXISTING")), REALM);

        assertEquals(List.of("ROLE_A", "ROLE_B"), plan.toCreate());
        assertEquals(List.of(), plan.toDelete());
    }

    @Test
    void anExistingUntaggedRoleWithRowsIsLeftAlone() {
        RealmRoleSyncPlanner.Plan plan = RealmRoleSyncPlanner.plan(Set.of("SUPER_ADMIN", "CCE_ADMIN_SERVICE"),
                List.of(untagged("SUPER_ADMIN"), untagged("CCE_ADMIN_SERVICE")), REALM);

        assertEquals(List.of(), plan.toCreate());
        assertEquals(List.of(), plan.toDelete());
    }

    @Test
    void deletesTaggedRolesThatNoLongerHaveRows() {
        RealmRoleSyncPlanner.Plan plan = RealmRoleSyncPlanner.plan(Set.of("KEPT"),
                List.of(tagged("KEPT"), tagged("GONE_B"), tagged("GONE_A")), REALM);

        assertEquals(List.of(), plan.toCreate());
        assertEquals(List.of("GONE_A", "GONE_B"), plan.toDelete());
    }

    @Test
    void neverDeletesUntaggedRoles() {
        RealmRoleSyncPlanner.Plan plan = RealmRoleSyncPlanner.plan(Set.of(),
                List.of(untagged("HAND_MADE"), new RealmRoles.RealmRole("OTHER_TAG",
                        Map.of(RealmRoles.MANAGED_ATTRIBUTE, List.of("false")))), REALM);

        assertEquals(List.of(), plan.toDelete());
    }

    @Test
    void neverDeletesSuperAdminEvenWhenTaggedAndWithoutRows() {
        RealmRoleSyncPlanner.Plan plan = RealmRoleSyncPlanner.plan(Set.of(), List.of(tagged("SUPER_ADMIN")), REALM);

        assertEquals(List.of(), plan.toDelete());
    }

    @Test
    void neverCreatesOrDeletesBuiltInRoles() {
        List<RealmRoles.RealmRole> builtIns = List.of(tagged("default-roles-cce"), tagged("offline_access"),
                tagged("uma_authorization"));

        RealmRoleSyncPlanner.Plan deleting = RealmRoleSyncPlanner.plan(Set.of(), builtIns, REALM);
        assertEquals(List.of(), deleting.toDelete());

        RealmRoleSyncPlanner.Plan creating = RealmRoleSyncPlanner.plan(
                Set.of("default-roles-cce", "offline_access", "uma_authorization"), List.of(), REALM);
        assertEquals(List.of(), creating.toCreate());
    }

    @Test
    void theDefaultRolesNameFollowsTheRealm() {
        assertTrue(RealmRoleSyncPlanner.isBuiltIn("default-roles-other", "other"));
        RealmRoleSyncPlanner.Plan plan = RealmRoleSyncPlanner.plan(Set.of(), List.of(tagged("default-roles-cce")),
                "other");
        assertEquals(List.of("default-roles-cce"), plan.toDelete());
    }
}
