package org.cce.admin.repository;

import java.util.List;
import java.util.UUID;
import org.cce.admin.entity.AuditEntityType;
import org.cce.admin.entity.AuditLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, UUID> {

    List<AuditLogEntity> findByEntityTypeAndEntityIdOrderByOccurredAtAsc(AuditEntityType type, UUID id);
}
