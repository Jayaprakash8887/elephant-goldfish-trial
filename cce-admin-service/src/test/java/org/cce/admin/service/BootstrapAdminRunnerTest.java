package org.cce.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.cce.admin.client.KeycloakCallFailedException;
import org.cce.admin.client.KeycloakUserInfo;
import org.cce.admin.config.AdminProperties;
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
import org.cce.admin.support.DatabaseTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.representations.idm.RoleRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Flow 5.13 (AD-15). The runner is constructed directly, with the base class's mocked
 * {@code KeycloakGateway} and the context's real {@link AccountLinker}.
 */
@ExtendWith(OutputCaptureExtension.class)
class BootstrapAdminRunnerTest extends DatabaseTestBase {

    private static final String ADMIN = "boot-admin";

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

    private BootstrapAdminRunner runner(String adminUsername) {
        AdminProperties properties = new AdminProperties(null, null, null, null,
                new AdminProperties.Bootstrap(adminUsername));
        return new BootstrapAdminRunner(userRepository, roleRepository, keycloakGateway, accountLinker, properties);
    }

    private static KeycloakUserInfo account(UUID id, boolean enabled, boolean emailVerified, List<String> roles) {
        return new KeycloakUserInfo(id, ADMIN, "boot-admin@cce.local", "Boot", "Admin", enabled, emailVerified, roles);
    }

    private UserEntity localUser(String username, UUID keycloakId, UserStatus status) {
        UserEntity user = new UserEntity();
        user.setKeycloakId(keycloakId);
        user.setUsername(username);
        user.setEmail(username + "@cce.local");
        user.setFirstName("Local");
        user.setLastName("User");
        user.setStatus(status);
        return userRepository.save(user);
    }

    private void grantSuperAdmin(UserEntity user) {
        UserRoleEntity link = new UserRoleEntity();
        link.setUserId(user.getId());
        link.setRoleId(roleRepository.findByName("SUPER_ADMIN").orElseThrow().getId());
        userRoleRepository.save(link);
    }

    private void superAdminRealmRoleExists() {
        RoleRepresentation role = new RoleRepresentation();
        role.setName("SUPER_ADMIN");
        when(keycloakGateway.findRealmRole("SUPER_ADMIN")).thenReturn(Optional.of(role));
    }

    @Test
    void anActiveAdministratorWithTheVariableSetLogsTheWarningAndDoesNothing(CapturedOutput output) {
        grantSuperAdmin(localUser("present-admin", UUID.randomUUID(), UserStatus.ACTIVE));

        runner(ADMIN).run(null);

        assertThat(output).contains("BOOTSTRAP_ADMIN_USERNAME is no longer needed and should be removed");
        verifyNoInteractions(keycloakGateway);
        assertThat(auditLog.count()).isZero();
    }

    @Test
    void anActiveAdministratorWithoutTheVariableDoesNothingQuietly(CapturedOutput output) {
        grantSuperAdmin(localUser("present-admin", UUID.randomUUID(), UserStatus.ACTIVE));

        runner("").run(null);

        assertThat(output).doesNotContain("no longer needed").doesNotContain("Bootstrap refused");
        verifyNoInteractions(keycloakGateway);
        assertThat(auditLog.count()).isZero();
    }

    @Test
    void noAdministratorAndNoVariableLogsAnError(CapturedOutput output) {
        // An INACTIVE holder does not count as an administrator.
        grantSuperAdmin(localUser("inactive-admin", UUID.randomUUID(), UserStatus.INACTIVE));

        runner(null).run(null);

        assertThat(output).contains("ERROR").contains("BOOTSTRAP_ADMIN_USERNAME is not set");
        verifyNoInteractions(keycloakGateway);
        assertThat(auditLog.count()).isZero();
    }

    @Test
    void aMissingKeycloakUserIsRefused(CapturedOutput output) {
        when(keycloakGateway.findUserByUsername(ADMIN)).thenReturn(Optional.empty());

        runner(ADMIN).run(null);

        assertThat(output).contains("ERROR").contains("Bootstrap refused").contains("does not exist");
        verify(keycloakGateway, never()).addRealmRoles(any(), anyCollection());
        assertThat(userRepository.count()).isZero();
        assertThat(auditLog.count()).isZero();
    }

    @Test
    void aDisabledKeycloakUserIsRefused(CapturedOutput output) {
        when(keycloakGateway.findUserByUsername(ADMIN))
                .thenReturn(Optional.of(account(UUID.randomUUID(), false, true, List.of())));

        runner(ADMIN).run(null);

        assertThat(output).contains("ERROR").contains("is disabled");
        verify(keycloakGateway, never()).addRealmRoles(any(), anyCollection());
        assertThat(userRepository.count()).isZero();
        assertThat(auditLog.count()).isZero();
    }

    @Test
    void anUnverifiedEmailIsRefusedWhileTheRealmRequiresVerification(CapturedOutput output) {
        when(keycloakGateway.findUserByUsername(ADMIN))
                .thenReturn(Optional.of(account(UUID.randomUUID(), true, false, List.of())));
        when(keycloakGateway.isEmailVerificationRequired()).thenReturn(true);

        runner(ADMIN).run(null);

        assertThat(output).contains("ERROR").contains("unverified email");
        verify(keycloakGateway, never()).addRealmRoles(any(), anyCollection());
        assertThat(userRepository.count()).isZero();
        assertThat(auditLog.count()).isZero();
    }

    @Test
    void anUnverifiedEmailIsAcceptedWhenTheRealmDoesNotRequireVerification() {
        UUID keycloakId = UUID.randomUUID();
        when(keycloakGateway.findUserByUsername(ADMIN))
                .thenReturn(Optional.of(account(keycloakId, true, false, List.of())));
        when(keycloakGateway.isEmailVerificationRequired()).thenReturn(false);
        superAdminRealmRoleExists();

        runner(ADMIN).run(null);

        assertThat(userRepository.findByKeycloakId(keycloakId)).isPresent();
    }

    @Test
    void aMissingSuperAdminRealmRoleIsAnErrorWithNoSyncAndNoChange(CapturedOutput output) {
        when(keycloakGateway.findUserByUsername(ADMIN))
                .thenReturn(Optional.of(account(UUID.randomUUID(), true, true, List.of())));
        when(keycloakGateway.findRealmRole("SUPER_ADMIN")).thenReturn(Optional.empty());

        runner(ADMIN).run(null);

        assertThat(output).contains("ERROR").contains("realm role SUPER_ADMIN does not exist");
        verify(keycloakGateway, never()).addRealmRoles(any(), anyCollection());
        verifyNoInteractions(gatewaySyncClient);
        assertThat(userRepository.count()).isZero();
        assertThat(auditLog.count()).isZero();
    }

    @Test
    void theSuccessPathLinksTheAccountWithOneRowBySystemBootstrap(CapturedOutput output) {
        UUID keycloakId = UUID.randomUUID();
        when(keycloakGateway.findUserByUsername(ADMIN))
                .thenReturn(Optional.of(account(keycloakId, true, true, List.of("SUPER_ADMIN", "default-roles-cce"))));
        superAdminRealmRoleExists();

        runner(ADMIN).run(null);

        verify(keycloakGateway).addRealmRoles(keycloakId, List.of("SUPER_ADMIN"));
        verifyNoInteractions(gatewaySyncClient);
        UserEntity user = userRepository.findByKeycloakId(keycloakId).orElseThrow();
        assertThat(user.getUsername()).isEqualTo(ADMIN);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        RoleEntity superAdmin = roleRepository.findByName("SUPER_ADMIN").orElseThrow();
        assertThat(userRoleRepository.findByUserId(user.getId())).extracting(UserRoleEntity::getRoleId)
                .containsExactly(superAdmin.getId());

        List<AuditLogEntity> rows = auditLog.findAll();
        assertThat(rows).hasSize(1);
        AuditLogEntity row = rows.get(0);
        assertThat(row.getAction()).isEqualTo("USER_CREATED");
        assertThat(row.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(row.getEntityId()).isEqualTo(user.getId());
        assertThat(row.getActorUsername()).isEqualTo("system:bootstrap");
        assertThat(row.getActorUserId()).isNull();
        assertThat(row.getAfterState().path("linked").asBoolean()).isTrue();
        assertThat(output).contains("Bootstrap: linked the Keycloak user");

        // A second start finds the administrator and only warns.
        runner(ADMIN).run(null);
        assertThat(output).contains("BOOTSTRAP_ADMIN_USERNAME is no longer needed");
        assertThat(auditLog.count()).isEqualTo(1);
    }

    @Test
    void anExistingLocalRowIsReactivatedAsAdministrator() {
        UUID keycloakId = UUID.randomUUID();
        UserEntity existing = localUser(ADMIN, keycloakId, UserStatus.INACTIVE);
        when(keycloakGateway.findUserByUsername(ADMIN))
                .thenReturn(Optional.of(account(keycloakId, true, true, List.of("default-roles-cce"))));
        superAdminRealmRoleExists();

        runner(ADMIN).run(null);

        verify(keycloakGateway).addRealmRoles(keycloakId, List.of("SUPER_ADMIN"));
        assertThat(userRepository.findById(existing.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(userRepository.countUsersWithRole("SUPER_ADMIN", UserStatus.ACTIVE)).isEqualTo(1);
        List<AuditLogEntity> rows = auditLog.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAction()).isEqualTo("USER_ROLES_UPDATED");
        assertThat(rows.get(0).getActorUsername()).isEqualTo("system:bootstrap");
    }

    @Test
    void aKeycloakFailureIsLoggedAndNeverThrown(CapturedOutput output) {
        when(keycloakGateway.findUserByUsername(anyString()))
                .thenThrow(new KeycloakCallFailedException("Keycloak failed to find user: HTTP 503", 503, null));

        assertThatCode(() -> runner(ADMIN).run(null)).doesNotThrowAnyException();

        assertThat(output).contains("ERROR").contains("Bootstrap failed");
        assertThat(userRepository.count()).isZero();
    }
}
