package org.cce.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.UUID;
import org.cce.admin.client.KeycloakCallFailedException;
import org.cce.admin.controller.dto.UserDtos.ChangeStatusRequest;
import org.cce.admin.controller.dto.UserDtos.User;
import org.cce.admin.entity.AuditEntityType;
import org.cce.admin.entity.AuditLogEntity;
import org.cce.admin.entity.AuditOutcome;
import org.cce.admin.entity.RoleEntity;
import org.cce.admin.entity.UserEntity;
import org.cce.admin.entity.UserRoleEntity;
import org.cce.admin.entity.UserStatus;
import org.cce.admin.repository.AuditLogRepository;
import org.cce.admin.repository.RoleRepository;
import org.cce.admin.repository.UserRepository;
import org.cce.admin.repository.UserRoleRepository;
import org.cce.admin.service.exception.ApiException;
import org.cce.admin.service.exception.ErrorCode;
import org.cce.admin.service.exception.KeycloakSyncFailedException;
import org.cce.admin.support.DatabaseTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Flow 5.3 (PATCH /users/:id/status, operation USER_STATUS_CHANGE), with the audit rows of section 8. */
class UserStatusFlowTest extends DatabaseTestBase {

    private static final UUID SUPER_ADMIN_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");

    @Autowired
    private UserService userService;

    @MockitoSpyBean
    private UserRepository users;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private AuditLogRepository auditLog;

    private RoleEntity analyst;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        analyst = new RoleEntity();
        analyst.setName("STATUS_ANALYST");
        analyst.setDisplayName("Analyst");
        analyst.setSystem(false);
        analyst.setAssignable(true);
        analyst = roleRepository.save(analyst);
        user = localUser("status-user", UserStatus.ACTIVE, analyst.getId());
    }

    private UserEntity localUser(String username, UserStatus status, UUID... roleIds) {
        UserEntity entity = new UserEntity();
        entity.setKeycloakId(UUID.randomUUID());
        entity.setUsername(username);
        entity.setEmail(username + "@cce.local");
        entity.setFirstName("Status");
        entity.setLastName("User");
        entity.setStatus(status);
        UserEntity saved = users.save(entity);
        for (UUID roleId : roleIds) {
            UserRoleEntity userRole = new UserRoleEntity();
            userRole.setUserId(saved.getId());
            userRole.setRoleId(roleId);
            userRoleRepository.save(userRole);
        }
        return saved;
    }

    private User change(UUID id, UserStatus status) {
        return userService.changeStatus(id, new ChangeStatusRequest(status));
    }

    private List<AuditLogEntity> rows(UUID userId) {
        return auditLog.findByEntityTypeAndEntityIdOrderByOccurredAtAsc(AuditEntityType.USER, userId);
    }

    private UserStatus storedStatus(UUID id) {
        return users.findById(id).orElseThrow().getStatus();
    }

    private static ApiException expectCode(Runnable call, ErrorCode code) {
        ApiException e = catchThrowableOfType(ApiException.class, call::run);
        assertThat(e).as("expected " + code).isNotNull();
        assertThat(e.code()).isEqualTo(code);
        return e;
    }

    // ---------------------------------------------------------------------------- transitions

    @Test
    void deactivatingDisablesTheAccountEndsSessionsAndKeepsTheRoles() {
        User updated = change(user.getId(), UserStatus.INACTIVE);

        InOrder order = inOrder(keycloakGateway);
        order.verify(keycloakGateway).setEnabled(user.getKeycloakId(), false);
        order.verify(keycloakGateway).logoutAllSessions(user.getKeycloakId());
        assertThat(updated.status()).isEqualTo(UserStatus.INACTIVE);
        assertThat(updated.roles()).extracting(r -> r.name()).containsExactly("STATUS_ANALYST");
        assertThat(userRoleRepository.findByUserId(user.getId())).hasSize(1);
        assertThat(storedStatus(user.getId())).isEqualTo(UserStatus.INACTIVE);

        List<AuditLogEntity> rows = rows(user.getId());
        assertThat(rows).extracting(AuditLogEntity::getAction, AuditLogEntity::getOutcome)
                .containsExactly(tuple("USER_DEACTIVATED", AuditOutcome.SUCCESS));
        assertThat(rows.get(0).getBeforeState().path("status").asText()).isEqualTo("ACTIVE");
        assertThat(rows.get(0).getAfterState().path("status").asText()).isEqualTo("INACTIVE");
    }

    @Test
    void lockingAnInactiveUserOnlyEndsSessions() {
        UserEntity inactive = localUser("inactive-user", UserStatus.INACTIVE);

        change(inactive.getId(), UserStatus.LOCKED);

        verify(keycloakGateway, never()).setEnabled(any(), anyBoolean());
        verify(keycloakGateway).logoutAllSessions(inactive.getKeycloakId());
        assertThat(storedStatus(inactive.getId())).isEqualTo(UserStatus.LOCKED);
        assertThat(rows(inactive.getId())).extracting(AuditLogEntity::getAction).containsExactly("USER_LOCKED");
    }

    @Test
    void activatingALockedUserEnablesTheAccountWithoutEndingSessions() {
        UserEntity locked = localUser("locked-user", UserStatus.LOCKED, analyst.getId());

        User updated = change(locked.getId(), UserStatus.ACTIVE);

        verify(keycloakGateway).setEnabled(locked.getKeycloakId(), true);
        verify(keycloakGateway, never()).logoutAllSessions(any());
        assertThat(updated.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(updated.roles()).hasSize(1);
        assertThat(rows(locked.getId())).extracting(AuditLogEntity::getAction).containsExactly("USER_ACTIVATED");
    }

    @Test
    void theCurrentStatusIsANoOpAndAMissingUserIs404() {
        User same = change(user.getId(), UserStatus.ACTIVE);

        assertThat(same.status()).isEqualTo(UserStatus.ACTIVE);
        verifyNoInteractions(keycloakGateway);
        expectCode(() -> change(UUID.randomUUID(), UserStatus.INACTIVE), ErrorCode.USER_NOT_FOUND);
        assertThat(auditLog.count()).isZero();
    }

    // ------------------------------------------------------------------- last administrator (AD-07)

    @Test
    void theLastActiveAdministratorCannotBeBlocked() {
        UserEntity admin = localUser("only-admin", UserStatus.ACTIVE, SUPER_ADMIN_ID);
        localUser("inactive-admin", UserStatus.INACTIVE, SUPER_ADMIN_ID);

        expectCode(() -> change(admin.getId(), UserStatus.INACTIVE), ErrorCode.LAST_SYSTEM_ADMIN);
        expectCode(() -> change(admin.getId(), UserStatus.LOCKED), ErrorCode.LAST_SYSTEM_ADMIN);

        verifyNoInteractions(keycloakGateway);
        assertThat(storedStatus(admin.getId())).isEqualTo(UserStatus.ACTIVE);
        assertThat(auditLog.count()).isZero();
    }

    @Test
    void anAdministratorCanBeBlockedWhileAnotherIsActive() {
        UserEntity admin = localUser("admin-one", UserStatus.ACTIVE, SUPER_ADMIN_ID);
        localUser("admin-two", UserStatus.ACTIVE, SUPER_ADMIN_ID);

        change(admin.getId(), UserStatus.LOCKED);

        assertThat(storedStatus(admin.getId())).isEqualTo(UserStatus.LOCKED);
        assertThat(rows(admin.getId())).extracting(AuditLogEntity::getAction).containsExactly("USER_LOCKED");
    }

    // ------------------------------------------------------------------------------ failures

    @Test
    void aFailureToDisableGives502WithNothingCompletedAndNoRow() {
        doThrow(new KeycloakCallFailedException("HTTP 500", 500, null))
                .when(keycloakGateway).setEnabled(any(), anyBoolean());

        KeycloakSyncFailedException e = catchThrowableOfType(KeycloakSyncFailedException.class,
                () -> change(user.getId(), UserStatus.INACTIVE));

        assertThat(e.partialState().operation()).isEqualTo("USER_STATUS_CHANGE");
        assertThat(e.partialState().failedStep()).isEqualTo("KEYCLOAK_ENABLED_CHANGED");
        assertThat(e.partialState().completedSteps()).isEmpty();
        assertThat(e.partialState().auditLogId()).isNull();
        verify(keycloakGateway, never()).logoutAllSessions(any());
        assertThat(storedStatus(user.getId())).isEqualTo(UserStatus.ACTIVE);
        assertThat(auditLog.count()).isZero();
    }

    @Test
    void aFailureToEndSessionsAfterDisablingWritesTheFailedRowAndResubmittingCompletes() {
        doThrow(new KeycloakCallFailedException("timeout", null, null))
                .when(keycloakGateway).logoutAllSessions(any());

        KeycloakSyncFailedException e = catchThrowableOfType(KeycloakSyncFailedException.class,
                () -> change(user.getId(), UserStatus.INACTIVE));

        assertThat(e.partialState().failedStep()).isEqualTo("KEYCLOAK_SESSIONS_ENDED");
        assertThat(e.partialState().completedSteps()).containsExactly("KEYCLOAK_ENABLED_CHANGED");
        assertThat(e.partialState().keycloakUserId()).isEqualTo(user.getKeycloakId());
        assertThat(storedStatus(user.getId())).isEqualTo(UserStatus.ACTIVE);
        AuditLogEntity failure = auditLog.findById(e.partialState().auditLogId()).orElseThrow();
        assertThat(failure.getAction()).isEqualTo("USER_DEACTIVATED");
        assertThat(failure.getOutcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(failure.getFailure().path("failedStep").asText()).isEqualTo("KEYCLOAK_SESSIONS_ENDED");
        assertThat(failure.getFailure().path("completedSteps").get(0).asText()).isEqualTo("KEYCLOAK_ENABLED_CHANGED");

        reset(keycloakGateway);
        change(user.getId(), UserStatus.INACTIVE);

        verify(keycloakGateway).setEnabled(user.getKeycloakId(), false);
        verify(keycloakGateway).logoutAllSessions(user.getKeycloakId());
        assertThat(storedStatus(user.getId())).isEqualTo(UserStatus.INACTIVE);
        assertThat(rows(user.getId())).extracting(AuditLogEntity::getAction, AuditLogEntity::getOutcome)
                .containsExactly(tuple("USER_DEACTIVATED", AuditOutcome.FAILED),
                        tuple("USER_DEACTIVATED", AuditOutcome.SUCCESS));
    }

    @Test
    void aLocalSaveFailureAfterKeycloakGives502AndResubmittingCompletes() {
        doThrow(new DataAccessResourceFailureException("database down")).when(users).saveAndFlush(any());

        KeycloakSyncFailedException e = catchThrowableOfType(KeycloakSyncFailedException.class,
                () -> change(user.getId(), UserStatus.LOCKED));

        assertThat(e.partialState().failedStep()).isEqualTo("LOCAL_STATUS_SAVED");
        assertThat(e.partialState().completedSteps())
                .containsExactly("KEYCLOAK_ENABLED_CHANGED", "KEYCLOAK_SESSIONS_ENDED");
        assertThat(storedStatus(user.getId())).isEqualTo(UserStatus.ACTIVE);
        AuditLogEntity failure = auditLog.findById(e.partialState().auditLogId()).orElseThrow();
        assertThat(failure.getAction()).isEqualTo("USER_LOCKED");
        assertThat(failure.getAfterState().path("status").asText()).isEqualTo("LOCKED");

        reset(users);
        change(user.getId(), UserStatus.LOCKED);

        verify(keycloakGateway, times(2)).setEnabled(user.getKeycloakId(), false);
        assertThat(storedStatus(user.getId())).isEqualTo(UserStatus.LOCKED);
        assertThat(auditLog.count()).isEqualTo(2);
    }
}
