package org.cce.admin.repository;

import java.util.Collection;
import java.util.List;
import org.cce.admin.entity.ApiPermissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ApiPermissionRepository extends JpaRepository<ApiPermissionEntity, Long> {

    /** Section 3, step 5. Uses the index {@code idx_permission_name}. */
    List<ApiPermissionEntity> findByPermissionNameInAndHttpMethod(Collection<String> names, String method);

    /** A role's rows. */
    List<ApiPermissionEntity> findByPermissionNameOrderByUriPatternAscHttpMethodAsc(String name);

    boolean existsByPermissionName(String name);

    /**
     * Deletes a role's explicit rows and keeps its implicit {@code GET} rows (5.7). Called with the
     * two implicit URIs. Used by a replace, so it flushes before and clears after.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ApiPermissionEntity p where p.permissionName = :name"
            + " and not (p.httpMethod = 'GET' and p.uriPattern in :implicitUris)")
    int deleteExplicitRows(String name, Collection<String> implicitUris);

    @Modifying
    @Query("delete from ApiPermissionEntity p where p.permissionName = :name")
    int deleteByPermissionName(String name);
}
