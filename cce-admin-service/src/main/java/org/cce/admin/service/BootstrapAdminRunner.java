package org.cce.admin.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.cce.admin.client.KeycloakGateway;
import org.cce.admin.client.KeycloakUserInfo;
import org.cce.admin.config.AdminProperties;
import org.cce.admin.entity.RoleEntity;
import org.cce.admin.entity.UserEntity;
import org.cce.admin.entity.UserStatus;
import org.cce.admin.repository.RoleRepository;
import org.cce.admin.repository.UserRepository;
import org.cce.admin.service.exception.KeycloakSyncFailedException;
import org.cce.admin.service.exception.PartialState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Bootstrap of the first {@code SUPER_ADMIN} (AD-15, flow 5.13). Runs once at each startup, after
 * Flyway, and never throws: every failure is logged at {@code ERROR} and the service still starts.
 * It never runs a gateway sync. Its audit rows carry the actor {@code system:bootstrap}.
 */
@Component
@Profile("!test")
public class BootstrapAdminRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final KeycloakGateway keycloakGateway;
    private final AccountLinker accountLinker;
    private final String adminUsername;

    public BootstrapAdminRunner(UserRepository userRepository, RoleRepository roleRepository,
            KeycloakGateway keycloakGateway, AccountLinker accountLinker, AdminProperties properties) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.keycloakGateway = keycloakGateway;
        this.accountLinker = accountLinker;
        String configured = properties.bootstrap() == null ? null : properties.bootstrap().adminUsername();
        this.adminUsername = configured == null || configured.isBlank() ? null : configured.trim();
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            bootstrap();
        } catch (KeycloakSyncFailedException e) {
            PartialState state = e.partialState();
            log.error("Bootstrap failed: {} event=keycloak_sync_failed operation={} entityId={} failedStep={}"
                            + " completedSteps={} keycloakUserId={} auditLogId={}",
                    e.getMessage(), state.operation(), state.entityId(), state.failedStep(),
                    String.join(",", state.completedSteps()), orNone(state.keycloakUserId()),
                    orNone(state.auditLogId()));
        } catch (RuntimeException e) {
            log.error("Bootstrap failed: {}", e.toString(), e);
        }
    }

    void bootstrap() {
        // Step 1.
        if (userRepository.countUsersWithRole(AccountLinker.SUPER_ADMIN, UserStatus.ACTIVE) > 0) {
            if (adminUsername != null) {
                log.warn("Bootstrap: an ACTIVE SUPER_ADMIN already exists, so BOOTSTRAP_ADMIN_USERNAME is no longer"
                        + " needed and should be removed.");
            }
            return;
        }
        // Step 2.
        if (adminUsername == null) {
            log.error("Bootstrap: there is no ACTIVE SUPER_ADMIN and BOOTSTRAP_ADMIN_USERNAME is not set,"
                    + " so the service has no administrator.");
            return;
        }
        // Step 3.
        Optional<KeycloakUserInfo> found = keycloakGateway.findUserByUsername(adminUsername);
        if (found.isEmpty()) {
            log.error("Bootstrap refused: the Keycloak user '{}' (BOOTSTRAP_ADMIN_USERNAME) does not exist.",
                    adminUsername);
            return;
        }
        KeycloakUserInfo account = found.get();
        if (!account.enabled()) {
            log.error("Bootstrap refused: the Keycloak user '{}' is disabled.", adminUsername);
            return;
        }
        if (!account.emailVerified() && keycloakGateway.isEmailVerificationRequired()) {
            log.error("Bootstrap refused: the Keycloak user '{}' has an unverified email and the realm requires"
                    + " email verification.", adminUsername);
            return;
        }
        // Step 4. Creating the realm role is a bootstrap step (section 9.5); this never runs a sync.
        if (keycloakGateway.findRealmRole(AccountLinker.SUPER_ADMIN).isEmpty()) {
            log.error("Bootstrap refused: the realm role SUPER_ADMIN does not exist in Keycloak."
                    + " Create it by hand (section 9.5, step 4).");
            return;
        }
        // Step 5.
        Optional<UserEntity> existing = userRepository.findByKeycloakId(account.id());
        if (existing.isEmpty()) {
            RoleEntity superAdmin = roleRepository.findByName(AccountLinker.SUPER_ADMIN)
                    .orElseThrow(() -> new IllegalStateException("The seed role SUPER_ADMIN is missing"));
            var user = accountLinker.link(account, UUID.randomUUID(), List.of(superAdmin), null, true);
            log.info("Bootstrap: linked the Keycloak user '{}' ({}) as SUPER_ADMIN, local user {}.",
                    account.username(), account.id(), user.id());
        } else {
            var user = accountLinker.reactivateAsAdmin(existing.get(), account);
            log.info("Bootstrap: re-activated the local user {} ('{}') as SUPER_ADMIN.", user.id(), user.username());
        }
    }

    private static String orNone(UUID id) {
        return id == null ? "none" : id.toString();
    }
}
