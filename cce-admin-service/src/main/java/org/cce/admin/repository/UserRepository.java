package org.cce.admin.repository;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.cce.admin.entity.UserEntity;
import org.cce.admin.entity.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface UserRepository extends JpaRepository<UserEntity, UUID>, JpaSpecificationExecutor<UserEntity> {

    Optional<UserEntity> findByKeycloakId(UUID keycloakId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserEntity u where u.id = :id")
    Optional<UserEntity> lockById(UUID id);

    boolean existsByUsername(String username);

    /** Call with {@code email.toLowerCase(Locale.ROOT)}: emails are stored in lower case. */
    boolean existsByEmail(String email);

    /** Call with {@code email.toLowerCase(Locale.ROOT)}: emails are stored in lower case. */
    boolean existsByEmailAndIdNot(String email, UUID id);

    @Query("select count(u) from UserEntity u, UserRoleEntity ur, RoleEntity r"
            + " where ur.userId = u.id and ur.roleId = r.id and r.name = :roleName and u.status = :status")
    long countUsersWithRole(String roleName, UserStatus status);

    @Query("select count(u) from UserEntity u, UserRoleEntity ur, RoleEntity r"
            + " where ur.userId = u.id and ur.roleId = r.id and r.name = :roleName and u.status = :status"
            + " and u.id <> :excludedUserId")
    long countUsersWithRoleExcluding(String roleName, UserStatus status, UUID excludedUserId);

    /** Section 3, step 4 (AD-05): moves {@code last_login_at} forward only, and leaves {@code updated_at} alone. */
    @Modifying
    @Transactional
    @Query(nativeQuery = true, value = "UPDATE users SET last_login_at = :t"
            + " WHERE id = :id AND (last_login_at IS NULL OR last_login_at < :t)")
    int updateLastLoginIfLater(UUID id, Instant t);

    @Modifying
    @Query("update UserEntity u set u.updatedAt = :at where u.id = :id")
    int touchUpdatedAt(UUID id, Instant at);
}
