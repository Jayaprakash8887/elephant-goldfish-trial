package org.cce.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.UUID;
import org.cce.admin.client.KeycloakCallFailedException;
import org.cce.admin.controller.dto.UserDtos.ResetPasswordResult;
import org.cce.admin.entity.AuditEntityType;
import org.cce.admin.entity.AuditLogEntity;
import org.cce.admin.entity.AuditOutcome;
import org.cce.admin.entity.UserEntity;
import org.cce.admin.entity.UserStatus;
import org.cce.admin.repository.AuditLogRepository;
import org.cce.admin.repository.UserRepository;
import org.cce.admin.service.exception.ApiException;
import org.cce.admin.service.exception.ErrorCode;
import org.cce.admin.service.exception.KeycloakSyncFailedException;
import org.cce.admin.support.DatabaseTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Flow 5.5 (POST /users/:id/reset-password, operation USER_PASSWORD_RESET), B-36. */
class UserResetPasswordTest extends DatabaseTestBase {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository users;

    @Autowired
    private AuditLogRepository auditLog;

    private UserEntity localUser(String username, UserStatus status) {
        UserEntity entity = new UserEntity();
        entity.setKeycloakId(UUID.randomUUID());
        entity.setUsername(username);
        entity.setEmail(username + "@cce.local");
        entity.setFirstName("Reset");
        entity.setLastName("User");
        entity.setStatus(status);
        return users.save(entity);
    }

    @Test
    void sendsTheEmailThenWritesTheRow() {
        UserEntity user = localUser("reset-user", UserStatus.ACTIVE);

        ResetPasswordResult result = userService.resetPassword(user.getId());

        verify(keycloakGateway).sendExecuteActionsEmail(user.getKeycloakId(), List.of("UPDATE_PASSWORD"));
        assertThat(result.userId()).isEqualTo(user.getId());
        assertThat(result.email()).isEqualTo("reset-user@cce.local");
        List<AuditLogEntity> rows = auditLog.findByEntityTypeAndEntityIdOrderByOccurredAtAsc(AuditEntityType.USER,
                user.getId());
        assertThat(rows).hasSize(1);
        AuditLogEntity row = rows.get(0);
        assertThat(row.getAction()).isEqualTo("USER_PASSWORD_RESET_REQUESTED");
        assertThat(row.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(row.getBeforeState()).isNull();
        assertThat(row.getAfterState().path("email").asText()).isEqualTo("reset-user@cce.local");
        assertThat(row.getAfterState().path("actions").get(0).asText()).isEqualTo("UPDATE_PASSWORD");
        assertThat(row.getAfterState().size()).isEqualTo(2);
        assertThat(row.getAfterState().toString()).doesNotContainIgnoringCase("password\":");
    }

    @Test
    void isAllowedForABlockedUser() {
        UserEntity locked = localUser("locked-user", UserStatus.LOCKED);

        userService.resetPassword(locked.getId());

        verify(keycloakGateway).sendExecuteActionsEmail(locked.getKeycloakId(), List.of("UPDATE_PASSWORD"));
        assertThat(users.findById(locked.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.LOCKED);
        assertThat(auditLog.count()).isEqualTo(1);
    }

    @Test
    void aMissingUserIs404() {
        ApiException e = catchThrowableOfType(ApiException.class, () -> userService.resetPassword(UUID.randomUUID()));

        assertThat(e.code()).isEqualTo(ErrorCode.USER_NOT_FOUND);
        verifyNoInteractions(keycloakGateway);
        assertThat(auditLog.count()).isZero();
    }

    @Test
    void aFailedEmailGives502WithNothingCompletedAndNoRowAndTryingAgainWorks() {
        UserEntity user = localUser("reset-fail", UserStatus.ACTIVE);
        doThrow(new KeycloakCallFailedException("HTTP 500", 500, null))
                .when(keycloakGateway).sendExecuteActionsEmail(any(), anyList());

        KeycloakSyncFailedException e = catchThrowableOfType(KeycloakSyncFailedException.class,
                () -> userService.resetPassword(user.getId()));

        assertThat(e.code()).isEqualTo(ErrorCode.KEYCLOAK_SYNC_FAILED);
        assertThat(e.partialState().operation()).isEqualTo("USER_PASSWORD_RESET");
        assertThat(e.partialState().entityId()).isEqualTo(user.getId());
        assertThat(e.partialState().failedStep()).isEqualTo("KEYCLOAK_ACTION_EMAIL_SENT");
        assertThat(e.partialState().completedSteps()).isEmpty();
        assertThat(e.partialState().keycloakUserId()).isNull();
        assertThat(e.partialState().auditLogId()).isNull();
        assertThat(auditLog.count()).isZero();

        reset(keycloakGateway);
        userService.resetPassword(user.getId());
        assertThat(auditLog.count()).isEqualTo(1);
    }
}
