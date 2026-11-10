package org.cce.admin.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.cce.admin.entity.MenuEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** {@code findAll(Sort)} is inherited, and is called with {@code Sort.by("sortOrder", "label")}. */
public interface MenuRepository extends JpaRepository<MenuEntity, UUID> {

    boolean existsByKey(String key);

    boolean existsByParentId(UUID parentId);

    @Query("select distinct m from MenuEntity m, RoleMenuEntity rm, RoleEntity r"
            + " where rm.menuId = m.id and rm.roleId = r.id and r.name in :roleNames and m.active = true")
    List<MenuEntity> findActiveGrantedToRoleNames(Collection<String> roleNames);
}
