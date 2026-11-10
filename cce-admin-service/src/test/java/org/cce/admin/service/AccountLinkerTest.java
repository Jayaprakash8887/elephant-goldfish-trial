package org.cce.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;
import org.cce.admin.client.KeycloakCallFailedException;
import org.cce.admin.client.KeycloakUserInfo;
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
import org.cce.admin.service.exception.KeycloakSyncFailedException;
import org.cce.admin.service.exception.PartialState;
import org.cce.admin.support.DatabaseTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The linking method of AD-18 (5.1 steps 5 and 6) and {@code reactivateAsAdmin} (5.13), with section 8's rows. */
class AccountLinkerTest extends DatabaseTestBase {

    @Autowired
    private AccountLinker accountLinker;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private AuditLogRepository auditLog;

    private RoleEntity role(String name) {
        RoleEntity role = new RoleEntity();
        role.setName(name);
        role.setDisplayName("Display " + name);
        role.setSystem(false);
        role.setAssignable(true);
        return roleRepository.save(role);
    }

    private static KeycloakUserInfo account(String username, List<String> realmRoles) {
        return new KeycloakUserInfo(UUID.randomUUID(), username, username + "@CCE.local", "First", "Last", true, true,
                realmRoles);
    }

    private List<UUID> roleIdsOf(UUID userId) {
        return userRoleRepository.findByUserId(userId).stream().map(UserRoleEntity::getRoleId).sorted().toList();
    }

    @Test
    void linkAssignsTheMappingsSavesTheRowsAndWritesUserCreated() {
        RoleEntity alpha = role("LINK_ALPHA");
        RoleEntity beta = role("LINK_BETA");
        KeycloakUserInfo account = account("new-user", List.of());
        UUID userId = UUID.randomUUID();

        User user = accountLinker.link(account, userId, List.of(beta, alpha), "FAC-1", false);

        verify(keycloakGateway).addRealmRoles(account.id(), List.of("LINK_BETA", "LINK_ALPHA"));
        assertThat(user.id()).isEqualTo(userId);
        assertThat(user.keycloakId()).isEqualTo(account.id());
        assertThat(user.email()).isEqualTo("new-user@cce.local");
        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.facilityScope()).isEqualTo("FAC-1");
        assertThat(user.roles()).extracting(r -> r.name()).containsExactly("LINK_ALPHA", "LINK_BETA");

        UserEntity row = userRepository.findById(userId).orElseThrow();
        assertThat(row.getKeycloakId()).isEqualTo(account.id());
        assertThat(row.getUsername()).isEqualTo("new-user");
        assertThat(row.getEmail()).isEqualTo("new-user@cce.local");
        assertThat(row.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(roleIdsOf(userId)).containsExactlyInAnyOrderElementsOf(List.of(alpha.getId(), beta.getId()));

        assertThat(auditLog.count()).isEqualTo(1);
        AuditLogEntity audit = auditLog.findByEntityTypeAndEntityIdOrderByOccurredAtAsc(AuditEntityType.USER, userId)
                .get(0);
        assertThat(audit.getAction()).isEqualTo("USER_CREATED");
        assertThat(audit.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(audit.getBeforeState()).isNull();
        assertThat(audit.getAfterState().path("linked").asBoolean()).isFalse();
        assertThat(audit.getAfterState().path("username").asText()).isEqualTo("new-user");
        assertThat(audit.getAfterState().path("roles")).hasSize(2);
        assertThat(audit.getFailure()).isNull();
    }

    @Test
    void linkingAnExistingAccountRecordsLinkedTrue() {
        RoleEntity alpha = role("LINK_EXISTING");
        KeycloakUserInfo account = account("existing-user", List.of("offline_access"));
        UUID userId = UUID.randomUUID();

        accountLinker.link(account, userId, List.of(alpha), null, true);

        AuditLogEntity audit = auditLog.findByEntityTypeAndEntityIdOrderByOccurredAtAsc(AuditEntityType.USER, userId)
                .get(0);
        assertThat(audit.getAction()).isEqualTo("USER_CREATED");
        assertThat(audit.getAfterState().path("linked").asBoolean()).isTrue();
        assertThat(audit.getAfterState().path("facilityScope").isNull()).isTrue();
        assertThat(userRepository.findById(userId)).isPresent();
    }

    @Test
    void aRoleAssignmentFailureAfterTheAccountWasCreatedWritesTheFailureRow() {
        RoleEntity alpha = role("LINK_FAIL_ROLES");
        KeycloakUserInfo account = account("roles-fail", List.of());
        UUID userId = UUID.randomUUID();
        doThrow(new KeycloakCallFailedException("Keycloak failed: HTTP 500", 500, null))
                .when(keycloakGateway).addRealmRoles(any(), anyCollection());

        KeycloakSyncFailedException e = catchThrowableOfType(KeycloakSyncFailedException.class,
                () -> accountLinker.link(account, userId, List.of(alpha), null, false));

        PartialState state = e.partialState();
        assertThat(state.operation()).isEqualTo("USER_CREATE");
        assertThat(state.entityId()).isEqualTo(userId);
        assertThat(state.failedStep()).isEqualTo("KEYCLOAK_ROLES_ASSIGNED");
        assertThat(state.completedSteps()).containsExactly("KEYCLOAK_USER_CREATED");
        assertThat(state.keycloakUserId()).isEqualTo(account.id());
        assertThat(state.auditLogId()).isNotNull();
        assertThat(userRepository.findById(userId)).isEmpty();

        assertThat(auditLog.count()).isEqualTo(1);
        AuditLogEntity row = auditLog.findById(state.auditLogId()).orElseThrow();
        assertThat(row.getAction()).isEqualTo("USER_CREATED");
        assertThat(row.getOutcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(row.getEntityId()).isEqualTo(userId);
        assertThat(row.getFailure().path("operation").asText()).isEqualTo("USER_CREATE");
        assertThat(row.getFailure().path("failedStep").asText()).isEqualTo("KEYCLOAK_ROLES_ASSIGNED");
        assertThat(row.getFailure().path("completedSteps").get(0).asText()).isEqualTo("KEYCLOAK_USER_CREATED");
        assertThat(row.getFailure().path("keycloakUserId").asText()).isEqualTo(account.id().toString());
        assertThat(row.getFailure().path("error").asText()).contains("HTTP 500");
    }

    @Test
    void aRoleAssignmentFailureOnALinkedAccountChangedNothingAndWritesNoRow() {
        RoleEntity alpha = role("LINK_FAIL_LINKED");
        KeycloakUserInfo account = account("linked-fail", List.of());
        UUID userId = UUID.randomUUID();
        doThrow(new KeycloakCallFailedException("timeout", null, null))
                .when(keycloakGateway).addRealmRoles(any(), anyCollection());

        KeycloakSyncFailedException e = catchThrowableOfType(KeycloakSyncFailedException.class,
                () -> accountLinker.link(account, userId, List.of(alpha), null, true));

        assertThat(e.partialState().failedStep()).isEqualTo("KEYCLOAK_ROLES_ASSIGNED");
        assertThat(e.partialState().completedSteps()).isEmpty();
        assertThat(e.partialState().keycloakUserId()).isEqualTo(account.id());
        assertThat(e.partialState().auditLogId()).isNull();
        assertThat(auditLog.count()).isZero();
        assertThat(userRepository.findById(userId)).isEmpty();
    }

    @Test
    void aLocalSaveFailureAfterKeycloakSucceededWritesTheFailureRowAndNoUser() {
        RoleEntity alpha = role("LINK_FAIL_LOCAL");
        // The username is taken locally, so the insert of step 6 fails on ux_users_username.
        accountLinker.link(account("taken", List.of()), UUID.randomUUID(), List.of(alpha), null, false);
        long rowsBefore = auditLog.count();
        KeycloakUserInfo account = account("taken", List.of());
        UUID userId = UUID.randomUUID();

        KeycloakSyncFailedException e = catchThrowableOfType(KeycloakSyncFailedException.class,
                () -> accountLinker.link(account, userId, List.of(alpha), null, false));

        PartialState state = e.partialState();
        assertThat(state.failedStep()).isEqualTo("LOCAL_USER_SAVED");
        assertThat(state.completedSteps()).containsExactly("KEYCLOAK_USER_CREATED", "KEYCLOAK_ROLES_ASSIGNED");
        assertThat(state.keycloakUserId()).isEqualTo(account.id());
        assertThat(userRepository.findById(userId)).isEmpty();
        assertThat(userRoleRepository.findByUserId(userId)).isEmpty();

        assertThat(auditLog.count()).isEqualTo(rowsBefore + 1);
        AuditLogEntity row = auditLog.findById(state.auditLogId()).orElseThrow();
        assertThat(row.getAction()).isEqualTo("USER_CREATED");
        assertThat(row.getOutcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(row.getFailure().path("failedStep").asText()).isEqualTo("LOCAL_USER_SAVED");
        assertThat(row.getFailure().path("completedSteps")).hasSize(2);
        assertThat(row.getFailure().path("keycloakUserId").asText()).isEqualTo(account.id().toString());
    }

    @Test
    void aLocalSaveFailureOnALinkedAccountStillWritesTheFailureRow() {
        RoleEntity alpha = role("LINK_FAIL_LOCAL_LINKED");
        accountLinker.link(account("taken-linked", List.of()), UUID.randomUUID(), List.of(alpha), null, true);
        KeycloakUserInfo account = account("taken-linked", List.of());

        KeycloakSyncFailedException e = catchThrowableOfType(KeycloakSyncFailedException.class,
                () -> accountLinker.link(account, UUID.randomUUID(), List.of(alpha), null, true));

        assertThat(e.partialState().failedStep()).isEqualTo("LOCAL_USER_SAVED");
        assertThat(e.partialState().completedSteps()).containsExactly("KEYCLOAK_ROLES_ASSIGNED");
        AuditLogEntity row = auditLog.findById(e.partialState().auditLogId()).orElseThrow();
        assertThat(row.getOutcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(row.getAfterState().path("linked").asBoolean()).isTrue();
    }

    private UserEntity localUser(String username, UserStatus status) {
        UserEntity user = new UserEntity();
        user.setKeycloakId(UUID.randomUUID());
        user.setUsername(username);
        user.setEmail(username + "@cce.local");
        user.setFirstName("Local");
        user.setLastName("User");
        user.setStatus(status);
        return userRepository.save(user);
    }

    @Test
    void reactivateAsAdminAddsAMissingMappingAndWritesUserRolesUpdated() {
        RoleEntity other = role("REACTIVATE_OTHER");
        UserEntity existing = localUser("old-admin", UserStatus.INACTIVE);
        UserRoleEntity link = new UserRoleEntity();
        link.setUserId(existing.getId());
        link.setRoleId(other.getId());
        userRoleRepository.save(link);
        KeycloakUserInfo account = new KeycloakUserInfo(existing.getKeycloakId(), "old-admin", "old-admin@cce.local",
                "Local", "User", true, true, List.of("default-roles-cce"));

        User user = accountLinker.reactivateAsAdmin(existing, account);

        verify(keycloakGateway).addRealmRoles(existing.getKeycloakId(), List.of("SUPER_ADMIN"));
        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.roles()).extracting(r -> r.name()).containsExactly("REACTIVATE_OTHER", "SUPER_ADMIN");
        assertThat(userRepository.findById(existing.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
        RoleEntity superAdmin = roleRepository.findByName("SUPER_ADMIN").orElseThrow();
        assertThat(roleIdsOf(existing.getId())).contains(superAdmin.getId(), other.getId());

        List<AuditLogEntity> rows = auditLog.findByEntityTypeAndEntityIdOrderByOccurredAtAsc(AuditEntityType.USER,
                existing.getId());
        assertThat(rows).hasSize(1);
        AuditLogEntity row = rows.get(0);
        assertThat(row.getAction()).isEqualTo("USER_ROLES_UPDATED");
        assertThat(row.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(row.getBeforeState().path("roles")).hasSize(1);
        assertThat(row.getBeforeState().path("roles").get(0).path("name").asText()).isEqualTo("REACTIVATE_OTHER");
        assertThat(row.getAfterState().path("roles")).hasSize(2);
        assertThat(row.getAfterState().path("roles").get(1).path("name").asText()).isEqualTo("SUPER_ADMIN");
        assertThat(row.getAfterState().path("roles").get(1).path("id").asText()).isEqualTo(superAdmin.getId().toString());
    }

    @Test
    void reactivateAsAdminSkipsAnExistingMappingAndAnExistingUserRolesRow() {
        RoleEntity superAdmin = roleRepository.findByName("SUPER_ADMIN").orElseThrow();
        UserEntity existing = localUser("locked-admin", UserStatus.LOCKED);
        UserRoleEntity link = new UserRoleEntity();
        link.setUserId(existing.getId());
        link.setRoleId(superAdmin.getId());
        userRoleRepository.save(link);
        KeycloakUserInfo account = new KeycloakUserInfo(existing.getKeycloakId(), "locked-admin",
                "locked-admin@cce.local", "Local", "User", true, true, List.of("SUPER_ADMIN"));

        User user = accountLinker.reactivateAsAdmin(existing, account);

        verify(keycloakGateway, never()).addRealmRoles(any(), anyCollection());
        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(roleIdsOf(existing.getId())).containsExactly(superAdmin.getId());
        AuditLogEntity row = auditLog.findByEntityTypeAndEntityIdOrderByOccurredAtAsc(AuditEntityType.USER,
                existing.getId()).get(0);
        assertThat(row.getAction()).isEqualTo("USER_ROLES_UPDATED");
    }
}
