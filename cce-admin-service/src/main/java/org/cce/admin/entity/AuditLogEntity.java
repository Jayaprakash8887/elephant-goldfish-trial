package org.cce.admin.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The append-only {@code audit_log} table (sections 6 and 8). {@code @Immutable} keeps Hibernate from
 * ever issuing an {@code UPDATE}: its dirty check compares the JSON columns with a re-read copy, in
 * which a {@code long} written as a {@code LongNode} comes back as an {@code IntNode}, so without it
 * a row holding a long (such as {@code Role.userCount}) would be "updated" at commit and the
 * append-only trigger would fail the transaction.
 */
@Entity
@Immutable
@Table(name = "audit_log")
public class AuditLogEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "actor_keycloak_id", length = 64)
    private String actorKeycloakId;

    @Column(name = "actor_username", nullable = false, length = 100)
    private String actorUsername;

    @Column(name = "action", nullable = false, length = 50)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 20)
    private AuditEntityType entityType;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 10)
    private AuditOutcome outcome;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_state")
    private JsonNode beforeState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_state")
    private JsonNode afterState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "failure")
    private JsonNode failure;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public void setActorUserId(UUID actorUserId) {
        this.actorUserId = actorUserId;
    }

    public String getActorKeycloakId() {
        return actorKeycloakId;
    }

    public void setActorKeycloakId(String actorKeycloakId) {
        this.actorKeycloakId = actorKeycloakId;
    }

    public String getActorUsername() {
        return actorUsername;
    }

    public void setActorUsername(String actorUsername) {
        this.actorUsername = actorUsername;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public AuditEntityType getEntityType() {
        return entityType;
    }

    public void setEntityType(AuditEntityType entityType) {
        this.entityType = entityType;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public void setEntityId(UUID entityId) {
        this.entityId = entityId;
    }

    public AuditOutcome getOutcome() {
        return outcome;
    }

    public void setOutcome(AuditOutcome outcome) {
        this.outcome = outcome;
    }

    public JsonNode getBeforeState() {
        return beforeState;
    }

    public void setBeforeState(JsonNode beforeState) {
        this.beforeState = beforeState;
    }

    public JsonNode getAfterState() {
        return afterState;
    }

    public void setAfterState(JsonNode afterState) {
        this.afterState = afterState;
    }

    public JsonNode getFailure() {
        return failure;
    }

    public void setFailure(JsonNode failure) {
        this.failure = failure;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }
}
