package org.cce.admin.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.cce.admin.audit.AuditAction;
import org.cce.admin.audit.AuditEntry;
import org.cce.admin.audit.AuditWriter;
import org.cce.admin.client.GatewaySyncClient;
import org.cce.admin.client.SyncResult;
import org.cce.admin.entity.AuditEntityType;
import org.cce.admin.entity.AuditOutcome;
import org.cce.admin.entity.SyncStatus;
import org.cce.admin.repository.RoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The gateway sync procedure of 5.10, used by 5.6, 5.7 and 5.8. Each attempt is a state change of
 * its own (section 8, rule 2): one {@code ROLE_SYNC_REQUESTED} row in its own transaction, and
 * {@code sync_status} and {@code last_sync_at} set on the role that asked, and only on it (AD-06).
 */
@Service
public class GatewaySyncService {

    private static final Logger log = LoggerFactory.getLogger(GatewaySyncService.class);

    private final GatewaySyncClient gatewaySyncClient;
    private final RoleRepository roleRepository;
    private final AuditWriter auditWriter;
    private final TransactionTemplate newTransaction;

    public GatewaySyncService(GatewaySyncClient gatewaySyncClient, RoleRepository roleRepository,
            AuditWriter auditWriter, PlatformTransactionManager transactionManager) {
        this.gatewaySyncClient = gatewaySyncClient;
        this.roleRepository = roleRepository;
        this.auditWriter = auditWriter;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** The after JSON of {@code ROLE_SYNC_REQUESTED}: {@code { outcome, httpStatus, errorSummary }}. */
    public record SyncAudit(AuditOutcome outcome, Integer httpStatus, String errorSummary) {
    }

    /**
     * The result of one attempt. {@code auditLogId} is null when the {@code ROLE_SYNC_REQUESTED} row
     * could not be written (section 8, rule 5).
     */
    public record Outcome(
            UUID roleId,
            boolean success,
            SyncStatus syncStatus,
            Instant lastSyncAt,
            Integer httpStatus,
            String errorSummary,
            UUID auditLogId) {
    }

    /** Runs 5.10 for the role that asked. Never throws. */
    public Outcome sync(UUID roleId) {
        // Steps 1 and 2: the client gets the service token and calls the gateway. It never throws.
        SyncResult result = gatewaySyncClient.sync();
        SyncStatus status = result.success() ? SyncStatus.SYNCED : SyncStatus.FAILED;
        AuditOutcome outcome = result.success() ? AuditOutcome.SUCCESS : AuditOutcome.FAILED;
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        SyncAudit after = new SyncAudit(outcome, result.httpStatus(), result.errorSummary());

        // Step 3: one new transaction, never by saving the entity, so updated_at is unchanged.
        UUID auditLogId = null;
        try {
            auditLogId = newTransaction.execute(tx -> {
                roleRepository.updateSyncResult(roleId, status, now);
                return auditWriter.record(new AuditEntry(AuditAction.ROLE_SYNC_REQUESTED, AuditEntityType.ROLE,
                        roleId, outcome, null, after, null));
            });
        } catch (RuntimeException e) {
            log.error("Could not record the gateway sync result for role {} (outcome {})", roleId, outcome, e);
        }
        if (!result.success()) {
            log.warn("Gateway sync failed for role {}: {}", roleId, result.errorSummary());
        }
        return new Outcome(roleId, result.success(), status, now, result.httpStatus(), result.errorSummary(),
                auditLogId);
    }
}
