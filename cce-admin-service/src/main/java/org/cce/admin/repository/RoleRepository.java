package org.cce.admin.repository;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.cce.admin.entity.RoleEntity;
import org.cce.admin.entity.SyncStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** {@code findAll(Pageable)} is inherited, and is called with {@code Sort.by("name", "id")}. */
public interface RoleRepository extends JpaRepository<RoleEntity, UUID> {

    Optional<RoleEntity> findByName(String name);

    boolean existsByName(String name);

    List<RoleEntity> findByNameIn(Collection<String> names);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RoleEntity r where r.name = :name")
    Optional<RoleEntity> lockByName(String name);

    /** A bulk update, so {@code @PreUpdate} does not run and {@code updated_at} is unchanged (5.10). */
    @Modifying
    @Query("update RoleEntity r set r.syncStatus = :status, r.lastSyncAt = :at where r.id = :id")
    int updateSyncResult(UUID id, SyncStatus status, Instant at);
}
