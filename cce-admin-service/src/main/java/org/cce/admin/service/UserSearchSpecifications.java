package org.cce.admin.service;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Subquery;
import java.util.Locale;
import java.util.UUID;
import org.cce.admin.entity.UserEntity;
import org.cce.admin.entity.UserRoleEntity;
import org.cce.admin.entity.UserStatus;
import org.springframework.data.jpa.domain.Specification;

/**
 * The filters of {@code GET /users} (section 4.5, B-30, AS-REQ-09). Every filter that is given must
 * match. A null value adds no condition.
 */
public final class UserSearchSpecifications {

    private static final char LIKE_ESCAPE = '\\';

    private UserSearchSpecifications() {
    }

    /** All four filters, combined with {@code and}. */
    public static Specification<UserEntity> matching(UserStatus status, UUID roleId, String facilityScope, String q) {
        return Specification.allOf(status(status), roleId(roleId), facilityScope(facilityScope), q(q));
    }

    public static Specification<UserEntity> status(UserStatus status) {
        return (root, query, cb) -> status == null ? null : cb.equal(root.get("status"), status);
    }

    /** Users holding the role. A role id that names no role matches nobody, so the page is empty. */
    public static Specification<UserEntity> roleId(UUID roleId) {
        return (root, query, cb) -> {
            if (roleId == null) {
                return null;
            }
            Subquery<UUID> held = query.subquery(UUID.class);
            var userRole = held.from(UserRoleEntity.class);
            held.select(userRole.get("userId"))
                    .where(cb.equal(userRole.get("userId"), root.get("id")), cb.equal(userRole.get("roleId"), roleId));
            return cb.exists(held);
        };
    }

    /** Exact match (AD-09). */
    public static Specification<UserEntity> facilityScope(String facilityScope) {
        return (root, query, cb) -> facilityScope == null ? null : cb.equal(root.get("facilityScope"), facilityScope);
    }

    /**
     * A case-insensitive substring of {@code username}, {@code email}, {@code firstName},
     * {@code lastName}, or {@code firstName + " " + lastName}. An empty {@code q} is ignored.
     */
    public static Specification<UserEntity> q(String q) {
        return (root, query, cb) -> {
            if (q == null || q.isEmpty()) {
                return null;
            }
            String pattern = "%" + escapeLike(q.toLowerCase(Locale.ROOT)) + "%";
            Expression<String> firstName = root.get("firstName");
            Expression<String> lastName = root.get("lastName");
            Expression<String> fullName = cb.concat(cb.concat(firstName, " "), lastName);
            return cb.or(
                    cb.like(cb.lower(root.get("username")), pattern, LIKE_ESCAPE),
                    cb.like(cb.lower(root.get("email")), pattern, LIKE_ESCAPE),
                    cb.like(cb.lower(firstName), pattern, LIKE_ESCAPE),
                    cb.like(cb.lower(lastName), pattern, LIKE_ESCAPE),
                    cb.like(cb.lower(fullName), pattern, LIKE_ESCAPE));
        };
    }

    /** {@code q} is a plain substring, so the LIKE wildcards in it are escaped. */
    private static String escapeLike(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            if (c == '%' || c == '_' || c == LIKE_ESCAPE) {
                escaped.append(LIKE_ESCAPE);
            }
            escaped.append(c);
        }
        return escaped.toString();
    }
}
