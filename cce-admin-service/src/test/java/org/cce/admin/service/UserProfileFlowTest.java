package org.cce.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.cce.admin.client.KeycloakCallFailedException;
import org.cce.admin.client.KeycloakConflictException;
import org.cce.admin.client.KeycloakUserInfo;
import org.cce.admin.controller.dto.UserDtos.UpdateUserRequest;
import org.cce.admin.controller.dto.UserDtos.User;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Flow 5.2 (PUT /users/:id, operation USER_PROFILE_UPDATE), with the audit rows of section 8. */
class UserProfileFlowTest extends DatabaseTestBase {

    @Autowired
    private UserService userService;

    @MockitoSpyBean
    private UserRepository users;

    @Autowired
    private AuditLogRepository auditLog;

    private UserEntity user;

    @BeforeEach
    void setUp() {
        user = localUser("profile-user", "profile-user@cce.local", "FAC-1");
        when(keycloakGateway.getUser(user.getKeycloakId())).thenReturn(Optional.of(new KeycloakUserInfo(
                user.getKeycloakId(), "profile-user", "profile-user@cce.local", "Old", "Name", true, true, List.of())));
    }

    private UserEntity localUser(String username, String email, String facilityScope) {
        UserEntity entity = new UserEntity();
        entity.setKeycloakId(UUID.randomUUID());
        entity.setUsername(username);
        entity.setEmail(email);
        entity.setFirstName("Old");
        entity.setLastName("Name");
        entity.setStatus(UserStatus.ACTIVE);
        entity.setFacilityScope(facilityScope);
        return users.save(entity);
    }

    private List<AuditLogEntity> rows() {
        return auditLog.findByEntityTypeAndEntityIdOrderByOccurredAtAsc(AuditEntityType.USER, user.getId());
    }

    private static ApiException expectCode(Runnable call, ErrorCode code) {
        ApiException e = catchThrowableOfType(ApiException.class, call::run);
        assertThat(e).as("expected " + code).isNotNull();
        assertThat(e.code()).isEqualTo(code);
        return e;
    }

    @Test
    void aChangedEmailUpdatesKeycloakWithEmailVerifiedFalseThenTheRow() {
        User updated = userService.update(user.getId(),
                new UpdateUserRequest("New.Address@CCE.local", "New", "Person", "FAC-2"));

        verify(keycloakGateway).updateProfile(user.getKeycloakId(), "new.address@cce.local", "New", "Person", false);
        verify(keycloakGateway, never()).getUser(any());
        assertThat(updated.email()).isEqualTo("new.address@cce.local");
        assertThat(updated.firstName()).isEqualTo("New");
        assertThat(updated.facilityScope()).isEqualTo("FAC-2");
        UserEntity row = users.findById(user.getId()).orElseThrow();
        assertThat(row.getEmail()).isEqualTo("new.address@cce.local");
        assertThat(row.getUpdatedAt()).isAfter(row.getCreatedAt());
        assertThat(updated.updatedAt()).isEqualTo(row.getUpdatedAt());

        assertThat(rows()).hasSize(1);
        AuditLogEntity audit = rows().get(0);
        assertThat(audit.getAction()).isEqualTo("USER_UPDATED");
        assertThat(audit.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(audit.getBeforeState().path("email").asText()).isEqualTo("profile-user@cce.local");
        assertThat(audit.getBeforeState().path("facilityScope").asText()).isEqualTo("FAC-1");
        assertThat(audit.getAfterState().path("email").asText()).isEqualTo("new.address@cce.local");
        assertThat(audit.getAfterState().path("facilityScope").asText()).isEqualTo("FAC-2");
    }

    @Test
    void aChangedNameKeepsTheAccountsEmailVerifiedFlag() {
        userService.update(user.getId(), new UpdateUserRequest("profile-user@cce.local", "Renamed", "Name", "FAC-1"));

        verify(keycloakGateway).updateProfile(user.getKeycloakId(), "profile-user@cce.local", "Renamed", "Name", true);
        assertThat(rows()).extracting(AuditLogEntity::getAction).containsExactly("USER_UPDATED");
    }

    @Test
    void aFacilityScopeOnlyChangeSkipsKeycloakAndANullScopeClearsIt() {
        User updated = userService.update(user.getId(),
                new UpdateUserRequest("profile-user@cce.local", "Old", "Name", null));

        verifyNoInteractions(keycloakGateway);
        assertThat(updated.facilityScope()).isNull();
        assertThat(users.findById(user.getId()).orElseThrow().getFacilityScope()).isNull();
        assertThat(rows()).extracting(AuditLogEntity::getAction).containsExactly("USER_UPDATED");
    }

    @Test
    void anUnchangedProfileIsANoOp() {
        User same = userService.update(user.getId(),
                new UpdateUserRequest("Profile-User@cce.local", "Old", "Name", "FAC-1"));

        verifyNoInteractions(keycloakGateway);
        assertThat(same.updatedAt()).isEqualTo(users.findById(user.getId()).orElseThrow().getUpdatedAt());
        assertThat(auditLog.count()).isZero();
    }

    @Test
    void aMissingUserIs404() {
        expectCode(() -> userService.update(UUID.randomUUID(), new UpdateUserRequest("a@cce.local", "A", "B", null)),
                ErrorCode.USER_NOT_FOUND);
        assertThat(auditLog.count()).isZero();
    }

    @Test
    void anEmailOfAnotherUserIs409IgnoringCase() {
        localUser("other-user", "taken@cce.local", null);

        expectCode(() -> userService.update(user.getId(), new UpdateUserRequest("TAKEN@cce.local", "Old", "Name", "FAC-1")),
                ErrorCode.EMAIL_IN_USE);

        verifyNoInteractions(keycloakGateway);
        assertThat(auditLog.count()).isZero();
    }

    @Test
    void aKeycloak409IsEmailInUseAndChangesNothing() {
        doThrow(new KeycloakConflictException("409", null)).when(keycloakGateway)
                .updateProfile(any(), anyString(), anyString(), anyString(), anyBoolean());

        expectCode(() -> userService.update(user.getId(), new UpdateUserRequest("kc-taken@cce.local", "Old", "Name", "FAC-1")),
                ErrorCode.EMAIL_IN_USE);

        assertThat(users.findById(user.getId()).orElseThrow().getEmail()).isEqualTo("profile-user@cce.local");
        assertThat(auditLog.count()).isZero();
    }

    @Test
    void aKeycloakFailureGives502WithNothingCompletedAndNoRow() {
        doThrow(new KeycloakCallFailedException("HTTP 500", 500, null)).when(keycloakGateway)
                .updateProfile(any(), anyString(), anyString(), anyString(), anyBoolean());

        KeycloakSyncFailedException e = catchThrowableOfType(KeycloakSyncFailedException.class,
                () -> userService.update(user.getId(), new UpdateUserRequest("n@cce.local", "Old", "Name", "FAC-1")));

        assertThat(e.partialState().operation()).isEqualTo("USER_PROFILE_UPDATE");
        assertThat(e.partialState().entityId()).isEqualTo(user.getId());
        assertThat(e.partialState().failedStep()).isEqualTo("KEYCLOAK_PROFILE_UPDATED");
        assertThat(e.partialState().completedSteps()).isEmpty();
        assertThat(e.partialState().keycloakUserId()).isEqualTo(user.getKeycloakId());
        assertThat(e.partialState().auditLogId()).isNull();
        assertThat(auditLog.count()).isZero();
    }

    @Test
    void aLocalSaveFailureAfterKeycloakGives502AndAFailedRowWithBothStatesThenResubmittingCompletes() {
        UpdateUserRequest request = new UpdateUserRequest("moved@cce.local", "New", "Person", "FAC-1");
        doThrow(new DataAccessResourceFailureException("database down")).when(users).saveAndFlush(any());

        KeycloakSyncFailedException e = catchThrowableOfType(KeycloakSyncFailedException.class,
                () -> userService.update(user.getId(), request));

        assertThat(e.partialState().failedStep()).isEqualTo("LOCAL_PROFILE_SAVED");
        assertThat(e.partialState().completedSteps()).containsExactly("KEYCLOAK_PROFILE_UPDATED");
        assertThat(e.partialState().keycloakUserId()).isEqualTo(user.getKeycloakId());
        assertThat(users.findById(user.getId()).orElseThrow().getEmail()).isEqualTo("profile-user@cce.local");
        AuditLogEntity failure = auditLog.findById(e.partialState().auditLogId()).orElseThrow();
        assertThat(failure.getAction()).isEqualTo("USER_UPDATED");
        assertThat(failure.getOutcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(failure.getBeforeState().path("email").asText()).isEqualTo("profile-user@cce.local");
        assertThat(failure.getAfterState().path("email").asText()).isEqualTo("moved@cce.local");
        assertThat(failure.getFailure().path("operation").asText()).isEqualTo("USER_PROFILE_UPDATE");
        assertThat(failure.getFailure().path("keycloakUserId").asText()).isEqualTo(user.getKeycloakId().toString());
        assertThat(auditLog.count()).isEqualTo(1);

        reset(users);
        User updated = userService.update(user.getId(), request);

        verify(keycloakGateway, times(2)).updateProfile(user.getKeycloakId(), "moved@cce.local", "New", "Person", false);
        assertThat(updated.email()).isEqualTo("moved@cce.local");
        assertThat(rows()).extracting(AuditLogEntity::getAction, AuditLogEntity::getOutcome)
                .containsExactly(
                        tuple("USER_UPDATED", AuditOutcome.FAILED),
                        tuple("USER_UPDATED", AuditOutcome.SUCCESS));
    }
}
