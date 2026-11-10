package org.cce.gatewaystub;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pure logic of the sync (section 9.1, GW-1 and GW-4 assumptions): create a tagged realm role for
 * every {@code permission_name} that has none, and delete the tagged realm roles that no longer have
 * rows. Keycloak's built-in roles ({@code default-roles-<realm>}, {@code offline_access},
 * {@code uma_authorization}) are never created, changed or deleted, untagged roles are never deleted,
 * and {@code SUPER_ADMIN} is never deleted.
 */
public final class RealmRoleSyncPlanner {

    static final String SUPER_ADMIN = "SUPER_ADMIN";

    /** The roles to create and to delete, each in name order. */
    public record Plan(List<String> toCreate, List<String> toDelete) {
    }

    private RealmRoleSyncPlanner() {
    }

    public static boolean isBuiltIn(String name, String realm) {
        return name.equals("default-roles-" + realm) || name.equals("offline_access")
                || name.equals("uma_authorization");
    }

    public static Plan plan(Set<String> permissionNames, List<RealmRoles.RealmRole> existing, String realm) {
        Map<String, RealmRoles.RealmRole> byName = existing.stream()
                .collect(Collectors.toMap(RealmRoles.RealmRole::name, Function.identity(), (a, b) -> a));
        List<String> toCreate = permissionNames.stream()
                .filter(name -> !byName.containsKey(name))
                .filter(name -> !isBuiltIn(name, realm))
                .sorted()
                .toList();
        List<String> toDelete = existing.stream()
                .filter(RealmRoles.RealmRole::managed)
                .map(RealmRoles.RealmRole::name)
                .filter(name -> !permissionNames.contains(name))
                .filter(name -> !isBuiltIn(name, realm))
                .filter(name -> !SUPER_ADMIN.equals(name))
                .sorted()
                .toList();
        return new Plan(toCreate, toDelete);
    }
}
