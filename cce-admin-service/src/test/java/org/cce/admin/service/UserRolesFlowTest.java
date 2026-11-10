package org.cce.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.cce.admin.client.KeycloakCallFailedException;
import org.cce.admin.controller.dto.UserDtos.ReplaceUserRolesRequest;
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

/** Flow 5.4 (PUT /users/:id/roles, operation USER_ROLES_UPDATE): only changed realm roles move (B-35). */
class UserRolesFlowTest extends DatabaseTestBase {

    private static final UUID SUPER_ADMIN_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID CCE_ADMIN_SERVICE_ID = UUID.fromString("20000000-0000-4000-8000-000000000002");

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

    private RoleEntity alpha;
    private RoleEntity beta;
    private RoleEntity gamma;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        alpha = role("ROLES_ALPHA");
        beta = role("ROLES_BETA");
        gamma = role("ROLES_GAMMA");
        user = localUser("roles-user", UserStatus.ACTIVE, alpha.getId(), beta.getId());
        when(keycloakGateway.listRealmRoleNames())
                .thenReturn(Set.of("ROLES_ALPHA", "ROLES_BETA", "ROLES_GAMMA", "SUPER_ADMIN", "CCE_ADMIN_SERVICE"));
    }

    private RoleEntity role(String name) {
        RoleEntity role = new RoleEntity();
        role.setName(name);
        role.setDisplayName("Display " + name);
        role.setSystem(false);
        role.setAssignable(true);
        return roleRepository.save(role);
    }

    private UserEntity localUser(String username, UserStatus status, UUID... roleIds) {
        UserEntity entity = new UserEntity();
        entity.setKeycloakId(UUID.randomUUID());
        entity.setUsername(username);
        entity.setEmail(username + "@cce.local");
        entity.setFirstName("Roles");
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

    private User replace(UUID userId, UUID... roleIds) {
        return userService.replaceRoles(userId, new ReplaceUserRolesRequest(List.of(roleIds)));
    }

    private List<UUID> roleIdsOf(UUID userId) {
        return userRoleRepository.findByUserId(userId).stream().map(UserRoleEntity::getRoleId).toList();
    }

    private static ApiException expectCode(Runnable call, ErrorCode code) {
        ApiException e = catchThrowableOfType(ApiException.class, call::run);
        assertThat(e).as("expected " + code).isNotNull();
        assertThat(e.code()).isEqualTo(code);
        return e;
    }

    // ------------------------------------------------------------------------------ happy path

    @Test
    void onlyTheAddedAndRemovedRealmRolesAreTouchedAndUpdatedAtChanges() {
        Instant updatedBefore = users.findById(user.getId()).orElseThrow().getUpdatedAt();

        User updated = replace(user.getId(), gamma.getId(), alpha.getId());

        InOrder order = inOrder(keycloakGateway);
        order.verify(keycloakGateway).listRealmRoleNames();
        order.verify(keycloakGateway).addRealmRoles(user.getKeycloakId(), List.of("ROLES_GAMMA"));
        order.verify(keycloakGateway).removeRealmRoles(user.getKeycloakId(), List.of("ROLES_BETA"));
        assertThat(updated.roles()).extracting(r -> r.name()).containsExactly("ROLES_ALPHA", "ROLES_GAMMA");
        assertThat(roleIdsOf(user.getId())).containsExactlyInAnyOrder(alpha.getId(), gamma.getId());
        Instant updatedAfter = users.findById(user.getId()).orElseThrow().getUpdatedAt();
        assertThat(updatedAfter).isAfter(updatedBefore);
        assertThat(updated.updatedAt()).isEqualTo(updatedAfter);

        List<AuditLogEntity> rows = auditLog.findByEntityTypeAndEntityIdOrderByOccurredAtAsc(AuditEntityType.USER,
                user.getId());
        assertThat(rows).extracting(AuditLogEntity::getAction, AuditLogEntity::getOutcome)
                .containsExactly(tuple("USER_ROLES_UPDATED", AuditOutcome.SUCCESS));
        AuditLogEntity row = rows.get(0);
        assertThat(row.getBeforeState().path("roles")).hasSize(2);
        assertThat(row.getBeforeState().path("roles").get(1).path("name").asText()).isEqualTo("ROLES_BETA");
        assertThat(row.getBeforeState().path("roles").get(1).path("id").asText()).isEqualTo(beta.getId().toString());
        assertThat(row.getAfterState().path("roles").get(1).path("name").asText()).isEqualTo("ROLES_GAMMA");
        assertThat(row.getAfterState().path("roles").get(1).path("id").asText()).isEqualTo(gamma.getId().toString());
    }

    @Test
    void removingOnlySkipsTheRealmRoleLookupAndAnEmptySetIsAllowed() {
        replace(user.getId());

        verify(keycloakGateway, never()).listRealmRoleNames();
        verify(keycloakGateway, never()).addRealmRoles(any(), anyCollection());
        verify(keycloakGateway).removeRealmRoles(user.getKeycloakId(), List.of("ROLES_ALPHA", "ROLES_BETA"));
        assertThat(roleIdsOf(user.getId())).isEmpty();
        assertThat(auditLog.count()).isEqualTo(1);
    }

    @Test
    void theSameSetInAnotherOrderIsANoOp() {
        Instant updatedBefore = users.findById(user.getId()).orElseThrow().getUpdatedAt();

        User same = replace(user.getId(), beta.getId(), alpha.getId());

        verifyNoInteractions(keycloakGateway);
        assertThat(same.roles()).hasSize(2);
        assertThat(users.findById(user.getId()).orElseThrow().getUpdatedAt()).isEqualTo(updatedBefore);
        assertThat(auditLog.count()).isZero();
    }

    // ---------------------------------------------------------------------------- error codes

    @Test
    void aMissingUserIs404AndUnknownOrRepeatedRoleIdsAre400() {
        expectCode(() -> replace(UUID.randomUUID(), alpha.getId()), ErrorCode.USER_NOT_FOUND);

        ApiException e = expectCode(() -> replace(user.getId(), alpha.getId(), UUID.randomUUID(), alpha.getId()),
                ErrorCode.VALIDATION_ERROR);
        assertThat(e.details()).extracting(d -> d.field()).containsExactly("roleIds[1]", "roleIds[2]");
        verifyNoInteractions(keycloakGateway);
        assertThat(auditLog.count()).isZero();
    }

    @Test
    void addingANonAssignableRoleIs409WithDetails() {
        ApiException e = expectCode(() -> replace(user.getId(), alpha.getId(), beta.getId(), CCE_ADMIN_SERVICE_ID),
                ErrorCode.ROLE_NOT_ASSIGNABLE);

        assertThat(e.details()).hasSize(1);
        assertThat(e.details().get(0).field()).isEqualTo("roleIds[2]");
        verifyNoInteractions(keycloakGateway);
        assertThat(auditLog.count()).isZero();
    }

    @Test
    void addingARoleWithoutARealmRoleIs409RoleNotSynced() {
        when(keycloakGateway.listRealmRoleNames()).thenReturn(Set.of("ROLES_ALPHA", "ROLES_BETA"));

        ApiException e = expectCode(() -> replace(user.getId(), alpha.getId(), gamma.getId()),
                ErrorCode.ROLE_NOT_SYNCED);

        assertThat(e.details()).extracting(d -> d.field()).containsExactly("roleIds[1]");
        verify(keycloakGateway, never()).addRealmRoles(any(), anyCollection());
        verify(keycloakGateway, never()).removeRealmRoles(any(), anyCollection());
        assertThat(roleIdsOf(user.getId())).hasSize(2);
        assertThat(auditLog.count()).isZero();
    }

    @Test
    void aFailedRealmRoleLookupGives502WithNothingChanged() {
        when(keycloakGateway.listRealmRoleNames()).thenThrow(new KeycloakCallFailedException("HTTP 503", 503, null));

        KeycloakSyncFailedException e = catchThrowableOfType(KeycloakSyncFailedException.class,
                () -> replace(user.getId(), gamma.getId()));

        assertThat(e.partialState().operation()).isEqualTo("USER_ROLES_UPDATE");
        assertThat(e.partialState().failedStep()).isEqualTo("KEYCLOAK_REALM_ROLES_CHECKED");
        assertThat(e.partialState().completedSteps()).isEmpty();
        assertThat(e.partialState().auditLogId()).isNull();
        assertThat(auditLog.count()).isZero();
    }

    @Test
    void theLastActiveAdministratorKeepsSuperAdmin() {
        UserEntity admin = localUser("only-admin", UserStatus.ACTIVE, SUPER_ADMIN_ID);

        expectCode(() -> replace(admin.getId(), alpha.getId()), ErrorCode.LAST_SYSTEM_ADMIN);

        verify(keycloakGateway, never()).addRealmRoles(any(), anyCollection());
        verify(keycloakGateway, never()).removeRealmRoles(any(), anyCollection());
        assertThat(roleIdsOf(admin.getId())).containsExactly(SUPER_ADMIN_ID);
        assertThat(auditLog.count()).isZero();
    }

    @Test
    void anInactiveUserCanLoseSuperAdmin() {
        localUser("only-admin", UserStatus.ACTIVE, SUPER_ADMIN_ID);
        UserEntity inactive = localUser("inactive-admin", UserStatus.INACTIVE, SUPER_ADMIN_ID);

        replace(inactive.getId());

        verify(keycloakGateway).removeRealmRoles(inactive.getKeycloakId(), List.of("SUPER_ADMIN"));
        assertThat(roleIdsOf(inactive.getId())).isEmpty();
    }

    // ------------------------------------------------------------------------------ failures

    @Test
    void aFailureToAddGives502WithNothingCompleted() {
        doThrow(new KeycloakCallFailedException("HTTP 500", 500, null))
                .when(keycloakGateway).addRealmRoles(any(), anyCollection());

        KeycloakSyncFailedException e = catchThrowableOfType(KeycloakSyncFailedException.class,
                () -> replace(user.getId(), gamma.getId()));

        assertThat(e.partialState().failedStep()).isEqualTo("KEYCLOAK_ROLES_ADDED");
        assertThat(e.partialState().completedSteps()).isEmpty();
        assertThat(e.partialState().auditLogId()).isNull();
        verify(keycloakGateway, never()).removeRealmRoles(any(), anyCollection());
        assertThat(auditLog.count()).isZero();
    }

    @Test
    void aFailureToRemoveAfterAddingWritesTheFailedRowAndResubmittingCompletes() {
        doThrow(new KeycloakCallFailedException("timeout", null, null))
                .when(keycloakGateway).removeRealmRoles(any(), anyCollection());

        KeycloakSyncFailedException e = catchThrowableOfType(KeycloakSyncFailedException.class,
                () -> replace(user.getId(), alpha.getId(), gamma.getId()));

        assertThat(e.partialState().failedStep()).isEqualTo("KEYCLOAK_ROLES_REMOVED");
        assertThat(e.partialState().completedSteps()).containsExactly("KEYCLOAK_ROLES_ADDED");
        assertThat(e.partialState().keycloakUserId()).isEqualTo(user.getKeycloakId());
        assertThat(roleIdsOf(user.getId())).containsExactlyInAnyOrder(alpha.getId(), beta.getId());
        AuditLogEntity failure = auditLog.findById(e.partialState().auditLogId()).orElseThrow();
        assertThat(failure.getAction()).isEqualTo("USER_ROLES_UPDATED");
        assertThat(failure.getOutcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(failure.getAfterState().path("roles").get(1).path("name").asText()).isEqualTo("ROLES_GAMMA");

        reset(keycloakGateway);
        when(keycloakGateway.listRealmRoleNames()).thenReturn(Set.of("ROLES_ALPHA", "ROLES_BETA", "ROLES_GAMMA"));
        replace(user.getId(), alpha.getId(), gamma.getId());

        verify(keycloakGateway).addRealmRoles(user.getKeycloakId(), List.of("ROLES_GAMMA"));
        verify(keycloakGateway).removeRealmRoles(user.getKeycloakId(), List.of("ROLES_BETA"));
        assertThat(roleIdsOf(user.getId())).containsExactlyInAnyOrder(alpha.getId(), gamma.getId());
        assertThat(auditLog.count()).isEqualTo(2);
    }

    @Test
    void aLocalSaveFailureAfterKeycloakGives502AndKeepsTheOldRows() {
        doThrow(new DataAccessResourceFailureException("database down")).when(users).touchUpdatedAt(any(), any());

        KeycloakSyncFailedException e = catchThrowableOfType(KeycloakSyncFailedException.class,
                () -> replace(user.getId(), gamma.getId()));

        assertThat(e.partialState().failedStep()).isEqualTo("LOCAL_ROLES_SAVED");
        assertThat(e.partialState().completedSteps()).containsExactly("KEYCLOAK_ROLES_ADDED", "KEYCLOAK_ROLES_REMOVED");
        assertThat(roleIdsOf(user.getId())).containsExactlyInAnyOrder(alpha.getId(), beta.getId());
        assertThat(auditLog.findById(e.partialState().auditLogId()).orElseThrow().getOutcome())
                .isEqualTo(AuditOutcome.FAILED);

        reset(users);
        replace(user.getId(), gamma.getId());

        verify(keycloakGateway, times(2)).addRealmRoles(user.getKeycloakId(), List.of("ROLES_GAMMA"));
        assertThat(roleIdsOf(user.getId())).containsExactly(gamma.getId());
        assertThat(auditLog.count()).isEqualTo(2);
    }
}
