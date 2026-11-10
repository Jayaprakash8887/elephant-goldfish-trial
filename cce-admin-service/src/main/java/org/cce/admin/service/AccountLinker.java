package org.cce.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.cce.admin.audit.AuditAction;
import org.cce.admin.audit.AuditEntry;
import org.cce.admin.audit.AuditWriter;
import org.cce.admin.client.KeycloakGateway;
import org.cce.admin.client.KeycloakUserInfo;
import org.cce.admin.controller.dto.UserDtos.User;
import org.cce.admin.entity.AuditEntityType;
import org.cce.admin.entity.RoleEntity;
import org.cce.admin.entity.UserEntity;
import org.cce.admin.entity.UserRoleEntity;
import org.cce.admin.entity.UserStatus;
import org.cce.admin.repository.RoleRepository;
import org.cce.admin.repository.UserRepository;
import org.cce.admin.repository.UserRoleRepository;
import org.cce.admin.service.exception.KeycloakSyncFailedException;
import org.cce.admin.service.exception.PartialState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The linking method of AD-18: steps 5 ({@code KEYCLOAK_ROLES_ASSIGNED}) and 6
 * ({@code LOCAL_USER_SAVED}) of flow 5.1, used by {@code UserService} and by
 * {@link BootstrapAdminRunner} (5.13). Also {@link #reactivateAsAdmin} for the bootstrapper. No
 * Keycloak step is ever undone (B-32). Audit rows follow section 8.
 */
@Service
public class AccountLinker {

    private static final Logger log = LoggerFactory.getLogger(AccountLinker.class);

    public static final String SUPER_ADMIN = "SUPER_ADMIN";

    public static final String OPERATION_USER_CREATE = "USER_CREATE";
    public static final String STEP_KEYCLOAK_USER_CREATED = "KEYCLOAK_USER_CREATED";
    public static final String STEP_KEYCLOAK_ROLES_ASSIGNED = "KEYCLOAK_ROLES_ASSIGNED";
    public static final String STEP_LOCAL_USER_SAVED = "LOCAL_USER_SAVED";

    // The operation and step names of flow 5.4, reused by reactivateAsAdmin (see its DESIGN-GAP).
    static final String OPERATION_USER_ROLES_UPDATE = "USER_ROLES_UPDATE";
    static final String STEP_KEYCLOAK_ROLES_ADDED = "KEYCLOAK_ROLES_ADDED";
    static final String STEP_LOCAL_ROLES_SAVED = "LOCAL_ROLES_SAVED";

    /** The {@code failure} JSON of a failure row (section 6, {@code audit_log.failure}). */
    public record Failure(
            String operation,
            String failedStep,
            List<String> completedSteps,
            UUID keycloakUserId,
            String error) {
    }

    private final KeycloakGateway keycloakGateway;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final AuditWriter auditWriter;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;

    public AccountLinker(KeycloakGateway keycloakGateway, UserRepository userRepository,
            UserRoleRepository userRoleRepository, RoleRepository roleRepository, AuditWriter auditWriter,
            ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.keycloakGateway = keycloakGateway;
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
        this.auditWriter = auditWriter;
        this.objectMapper = objectMapper;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * 5.1 steps 5 and 6. {@code linkedExisting} is true when the account existed before this call
     * (step 4 was skipped), and false when step 4 ({@code KEYCLOAK_USER_CREATED}) just created it.
     * A failure in either step gives a {@link KeycloakSyncFailedException} whose
     * {@code keycloakUserId} is the account (B-32).
     */
    public User link(KeycloakUserInfo account, UUID userId, List<RoleEntity> roles, String facilityScope,
            boolean linkedExisting) {
        List<String> completed = new ArrayList<>();
        if (!linkedExisting) {
            completed.add(STEP_KEYCLOAK_USER_CREATED);
        }

        // Step 5: KEYCLOAK_ROLES_ASSIGNED, all the realm role mappings in one call.
        try {
            keycloakGateway.addRealmRoles(account.id(), roles.stream().map(RoleEntity::getName).toList());
        } catch (RuntimeException e) {
            throw failed(account, userId, roles, facilityScope, linkedExisting, STEP_KEYCLOAK_ROLES_ASSIGNED,
                    completed, e);
        }
        completed.add(STEP_KEYCLOAK_ROLES_ASSIGNED);

        // Step 6: LOCAL_USER_SAVED, the users and user_roles rows and USER_CREATED, in one transaction.
        try {
            return transaction.execute(status -> {
                UserEntity saved = userRepository.save(newUser(account, userId, facilityScope));
                for (RoleEntity role : roles) {
                    UserRoleEntity userRole = new UserRoleEntity();
                    userRole.setUserId(saved.getId());
                    userRole.setRoleId(role.getId());
                    userRoleRepository.save(userRole);
                }
                User user = UserViews.toUser(saved, roles);
                auditWriter.record(AuditEntry.success(AuditAction.USER_CREATED, AuditEntityType.USER, saved.getId(),
                        null, withLinked(user, linkedExisting)));
                return user;
            });
        } catch (RuntimeException e) {
            throw failed(account, userId, roles, facilityScope, linkedExisting, STEP_LOCAL_USER_SAVED, completed, e);
        }
    }

    /**
     * For the bootstrapper (5.13) when the administrator's local row exists: adds the
     * {@code SUPER_ADMIN} realm role mapping when it is missing, sets the row {@code ACTIVE}, adds
     * the {@code SUPER_ADMIN} {@code user_roles} row, and writes {@code USER_ROLES_UPDATED}.
     */
    public User reactivateAsAdmin(UserEntity existing, KeycloakUserInfo account) {
        RoleEntity superAdmin = roleRepository.findByName(SUPER_ADMIN)
                .orElseThrow(() -> new IllegalStateException("The seed role SUPER_ADMIN is missing"));
        boolean mappingAdded = false;
        if (!account.realmRoles().contains(SUPER_ADMIN)) {
            keycloakGateway.addRealmRoles(account.id(), List.of(SUPER_ADMIN));
            mappingAdded = true;
        }
        try {
            return transaction.execute(status -> {
                UserEntity user = userRepository.findById(existing.getId())
                        .orElseThrow(() -> new IllegalStateException("User " + existing.getId() + " no longer exists"));
                List<UUID> roleIds = userRoleRepository.findByUserId(user.getId()).stream()
                        .map(UserRoleEntity::getRoleId)
                        .toList();
                List<RoleEntity> before = roleIds.isEmpty() ? List.of() : roleRepository.findAllById(roleIds);
                List<RoleEntity> after = new ArrayList<>(before);
                user.setStatus(UserStatus.ACTIVE);
                UserEntity saved = userRepository.save(user);
                if (!roleIds.contains(superAdmin.getId())) {
                    UserRoleEntity userRole = new UserRoleEntity();
                    userRole.setUserId(saved.getId());
                    userRole.setRoleId(superAdmin.getId());
                    userRoleRepository.save(userRole);
                    after.add(superAdmin);
                }
                auditWriter.record(AuditEntry.success(AuditAction.USER_ROLES_UPDATED, AuditEntityType.USER,
                        saved.getId(), Map.of("roles", UserViews.toRoles(before)),
                        Map.of("roles", UserViews.toRoles(after))));
                return UserViews.toUser(saved, after);
            });
        } catch (RuntimeException e) {
            if (!mappingAdded) {
                // Nothing changed before the failure: no row (section 8, rule 4).
                throw e;
            }
            // DESIGN-GAP: 5.13 names no operation or step names for reactivateAsAdmin. A database
            // failure after the mapping was added is recorded under rule 3 with the operation and
            // step names of flow 5.4, which makes the same kind of change.
            List<String> completed = List.of(STEP_KEYCLOAK_ROLES_ADDED);
            UUID auditLogId = recordFailure(AuditAction.USER_ROLES_UPDATED, existing.getId(), null,
                    new Failure(OPERATION_USER_ROLES_UPDATE, STEP_LOCAL_ROLES_SAVED, completed, account.id(),
                            describe(e)));
            throw new KeycloakSyncFailedException(
                    "The SUPER_ADMIN realm role was assigned in Keycloak, but saving the local user failed.",
                    new PartialState(OPERATION_USER_ROLES_UPDATE, existing.getId(), STEP_LOCAL_ROLES_SAVED,
                            completed, account.id(), auditLogId));
        }
    }

    private KeycloakSyncFailedException failed(KeycloakUserInfo account, UUID userId, List<RoleEntity> roles,
            String facilityScope, boolean linkedExisting, String failedStep, List<String> completed,
            RuntimeException cause) {
        log.warn("{} failed at step {} for Keycloak user {}", OPERATION_USER_CREATE, failedStep, account.id(), cause);
        List<String> completedSteps = List.copyOf(completed);
        UUID auditLogId = null;
        if (!completedSteps.isEmpty()) {
            // Rule 3: an earlier step of this call changed something.
            // DESIGN-GAP: section 8 gives no after JSON for a USER_CREATE failure row. The row
            // records the user that was to be created (the User shape plus linked), because the
            // local row does not exist and a resubmission assigns a new id.
            UserEntity intended = newUser(account, userId, facilityScope);
            auditLogId = recordFailure(AuditAction.USER_CREATED, userId,
                    withLinked(UserViews.toUser(intended, roles), linkedExisting),
                    new Failure(OPERATION_USER_CREATE, failedStep, completedSteps, account.id(), describe(cause)));
        }
        // Rule 4 otherwise: a linked account whose role assignment failed changed nothing, so no row.
        String message = STEP_LOCAL_USER_SAVED.equals(failedStep)
                ? "The Keycloak account and its realm roles exist, but saving the local user failed."
                        + " Submit the same request again to link the account."
                : "Assigning the realm roles in Keycloak failed. Submit the same request again.";
        return new KeycloakSyncFailedException(message,
                new PartialState(OPERATION_USER_CREATE, userId, failedStep, completedSteps, account.id(), auditLogId));
    }

    /** Rules 3 and 5: the failure row in its own transaction, or null when it cannot be written. */
    private UUID recordFailure(AuditAction action, UUID entityId, Object after, Failure failure) {
        try {
            return auditWriter.recordInNewTransaction(
                    AuditEntry.failed(action, AuditEntityType.USER, entityId, null, after, failure));
        } catch (RuntimeException e) {
            log.error("Could not write the failure row for {} {}", failure.operation(), entityId, e);
            return null;
        }
    }

    private static UserEntity newUser(KeycloakUserInfo account, UUID userId, String facilityScope) {
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setKeycloakId(account.id());
        user.setUsername(account.username());
        user.setEmail(account.email() == null ? null : account.email().toLowerCase(Locale.ROOT));
        user.setFirstName(account.firstName());
        user.setLastName(account.lastName());
        user.setStatus(UserStatus.ACTIVE);
        user.setFacilityScope(facilityScope);
        return user;
    }

    /** {@code USER_CREATED}'s after JSON: the User, plus {@code linked} (section 8). */
    private ObjectNode withLinked(User user, boolean linked) {
        ObjectNode node = objectMapper.valueToTree(user);
        node.put("linked", linked);
        return node;
    }

    private static String describe(Throwable e) {
        return e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }
}
