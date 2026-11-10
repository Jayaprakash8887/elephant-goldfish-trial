package org.cce.admin.service;

import java.util.List;
import org.cce.admin.entity.ApiPermissionEntity;

/**
 * The two implicit rows every non-system role always holds (section 4.4, AD-16):
 * {@code GET /admin/v1/me} and {@code GET /admin/v1/me/menus}. There is no {@code implicit} column;
 * a row is implicit when its method and pattern equal one of these two.
 */
public final class ImplicitPermissions {

    private ImplicitPermissions() {
    }

    /** One implicit row's definition. */
    public record Definition(String httpMethod, String uriPattern, String description, String resourceCategory) {

        /** A new {@code api_permissions} row for this definition, owned by {@code roleName}. */
        public ApiPermissionEntity toEntity(String roleName) {
            ApiPermissionEntity row = new ApiPermissionEntity();
            row.setPermissionName(roleName);
            row.setHttpMethod(httpMethod);
            row.setUriPattern(uriPattern);
            row.setDescription(description);
            row.setResourceCategory(resourceCategory);
            return row;
        }
    }

    public static final String METHOD = "GET";
    public static final String ME = "/admin/v1/me";
    public static final String ME_MENUS = "/admin/v1/me/menus";

    /** The two rows, in the order {@code GET /roles/:id/permissions} lists them. */
    public static final List<Definition> ROWS = List.of(
            new Definition(METHOD, ME, "Read own profile", "Self"),
            new Definition(METHOD, ME_MENUS, "Read own menu tree", "Self"));

    /** The two implicit URIs, for {@code ApiPermissionRepository.deleteExplicitRows}. */
    public static final List<String> URIS = List.of(ME, ME_MENUS);

    /** Whether a row with this method and pattern is implicit. The method is compared as given. */
    public static boolean isImplicit(String httpMethod, String uriPattern) {
        return METHOD.equals(httpMethod) && URIS.contains(uriPattern);
    }
}
