package org.cce.admin.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Reads the caller's role names from the claim at {@code JWT_ROLES_CLAIM_PATH} (section 3, step 3,
 * B-11). The path is split on {@code .} and each part is looked up in the nested JSON objects of the
 * token. A missing claim, or one that is not a list of strings, means no roles; it never fails.
 */
public class RolesClaimReader {

    private final List<String> pathParts;

    public RolesClaimReader(String claimPath) {
        this.pathParts = List.of(claimPath.split("\\."));
    }

    public List<String> read(Jwt jwt) {
        return read(jwt.getClaims());
    }

    public List<String> read(Map<String, Object> claims) {
        Object current = claims;
        for (String part : pathParts) {
            if (!(current instanceof Map<?, ?> map)) {
                return List.of();
            }
            current = map.get(part);
        }
        if (!(current instanceof Collection<?> values)) {
            return List.of();
        }
        List<String> roles = new ArrayList<>(values.size());
        for (Object value : values) {
            if (!(value instanceof String role)) {
                return List.of();
            }
            roles.add(role);
        }
        return List.copyOf(roles);
    }
}
