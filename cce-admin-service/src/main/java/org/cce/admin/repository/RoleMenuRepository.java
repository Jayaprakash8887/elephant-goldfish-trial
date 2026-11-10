package org.cce.admin.repository;

import java.util.List;
import java.util.UUID;
import org.cce.admin.entity.RoleMenuEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RoleMenuRepository extends JpaRepository<RoleMenuEntity, UUID> {

    List<RoleMenuEntity> findByRoleId(UUID roleId);

    @Query("select rm.roleId from RoleMenuEntity rm where rm.menuId = :menuId")
    List<UUID> findRoleIdsByMenuId(UUID menuId);

    /** Used by the replace in 5.11, so it flushes before and clears after. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RoleMenuEntity rm where rm.roleId = :roleId")
    int deleteByRoleId(UUID roleId);

    @Modifying
    @Query("delete from RoleMenuEntity rm where rm.menuId = :menuId")
    int deleteByMenuId(UUID menuId);
}
