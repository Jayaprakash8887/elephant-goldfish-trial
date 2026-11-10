package org.cce.admin.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.cce.admin.entity.UserRoleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface UserRoleRepository extends JpaRepository<UserRoleEntity, UUID> {

    List<UserRoleEntity> findByUserId(UUID userId);

    boolean existsByRoleId(UUID roleId);

    /** Rows of {@code [roleId, count]}, for {@code Role.userCount}, one query per page. */
    @Query("select ur.roleId, count(ur) from UserRoleEntity ur where ur.roleId in :roleIds group by ur.roleId")
    List<Object[]> countByRoleIds(Collection<UUID> roleIds);

    /** Used by the replace in 5.4, so it flushes before and clears after. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UserRoleEntity ur where ur.userId = :userId and ur.roleId in :roleIds")
    int deleteByUserIdAndRoleIdIn(UUID userId, Collection<UUID> roleIds);
}
