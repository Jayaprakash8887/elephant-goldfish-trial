package org.cce.admin.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.cce.admin.audit.AuditAction;
import org.cce.admin.audit.AuditEntry;
import org.cce.admin.audit.AuditWriter;
import org.cce.admin.client.KeycloakConflictException;
import org.cce.admin.client.KeycloakGateway;
import org.cce.admin.client.KeycloakUserInfo;
import org.cce.admin.controller.dto.CommonDtos.FieldErrorDto;
import org.cce.admin.controller.dto.CommonDtos.Warning;
import org.cce.admin.controller.dto.UserDtos.ChangeStatusRequest;
import org.cce.admin.controller.dto.UserDtos.CreateUserRequest;
import org.cce.admin.controller.dto.UserDtos.ReplaceUserRolesRequest;
import org.cce.admin.controller.dto.UserDtos.ResetPasswordResult;
import org.cce.admin.controller.dto.UserDtos.UpdateUserRequest;
import org.cce.admin.controller.dto.UserDtos.User;
import org.cce.admin.entity.AuditEntityType;
import org.cce.admin.entity.RoleEntity;
import org.cce.admin.entity.UserEntity;
import org.cce.admin.entity.UserRoleEntity;
import org.cce.admin.entity.UserStatus;
import org.cce.admin.repository.RoleRepository;
import org.cce.admin.repository.UserRepository;
import org.cce.admin.repository.UserRoleRepository;
import org.cce.admin.service.exception.ApiException;
import org.cce.admin.service.exception.ErrorCode;
import org.cce.admin.service.exception.KeycloakSyncFailedException;
import org.cce.admin.service.exception.PartialState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Users: list and get, and flows 5.1 to 5.5 with their step names. No class- or method-level
 * {@code @Transactional}. There is no compensation: no Keycloak step is ever undone (B-32).
 *
 * <p>DESIGN-GAP: "How to use this plan" says each database step commits before the next Keycloak
 * call, while section 5 ("Locks") says flows 5.2 to 5.4 run inside one transaction that starts with
 * the {@code FOR UPDATE} lock and keeps it open across the Keycloak calls, so that two requests for
 * the same user run one after the other. Section 5's specific rule is followed for 5.2 to 5.4 (and
 * {@code LastSystemAdminConcurrencyTest} depends on it); 5.1 and 5.5 commit each database step on
 * its own.
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    /** The sort of {@code GET /users}: {@code username}, then {@code id} (section 4.3). */
    public static final Sort SORT = Sort.by("username", "id");

    public static final List<String> UPDATE_PASSWORD = List.of("UPDATE_PASSWORD");

    public static final String OPERATION_USER_PROFILE_UPDATE = "USER_PROFILE_UPDATE";
    public static final String OPERATION_USER_STATUS_CHANGE = "USER_STATUS_CHANGE";
    public static final String OPERATION_USER_ROLES_UPDATE = "USER_ROLES_UPDATE";
    public static final String OPERATION_USER_PASSWORD_RESET = "USER_PASSWORD_RESET";

    public static final String STEP_KEYCLOAK_PROFILE_UPDATED = "KEYCLOAK_PROFILE_UPDATED";
    public static final String STEP_LOCAL_PROFILE_SAVED = "LOCAL_PROFILE_SAVED";
    public static final String STEP_KEYCLOAK_ENABLED_CHANGED = "KEYCLOAK_ENABLED_CHANGED";
    public static final String STEP_KEYCLOAK_SESSIONS_ENDED = "KEYCLOAK_SESSIONS_ENDED";
    public static final String STEP_LOCAL_STATUS_SAVED = "LOCAL_STATUS_SAVED";
    public static final String STEP_KEYCLOAK_ROLES_ADDED = "KEYCLOAK_ROLES_ADDED";
    public static final String STEP_KEYCLOAK_ROLES_REMOVED = "KEYCLOAK_ROLES_REMOVED";
    public static final String STEP_LOCAL_ROLES_SAVED = "LOCAL_ROLES_SAVED";
    public static final String STEP_KEYCLOAK_ACTION_EMAIL_SENT = "KEYCLOAK_ACTION_EMAIL_SENT";

    // DESIGN-GAP: 5.1 step 3 and 5.4 step 3 make Keycloak lookups that have no step name, and a
    // failure there comes before anything changed (DEV-8: 502 with completedSteps []). As in
    // Phase 5's role flows, these names are used as failedStep for that case.
    public static final String STEP_KEYCLOAK_ACCOUNTS_CHECKED = "KEYCLOAK_ACCOUNTS_CHECKED";
    public static final String STEP_KEYCLOAK_REALM_ROLES_CHECKED = "KEYCLOAK_REALM_ROLES_CHECKED";

    /** One page of users, with the true total (section 4.3). */
    public record UserPage(List<User> items, long totalItems) {
    }

    /** The created or linked user, and the warning {@code ACTION_EMAIL_NOT_SENT} when the email failed. */
    public record UserCreated(User user, List<Warning> warnings) {
    }

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final KeycloakGateway keycloakGateway;
    private final AccountLinker accountLinker;
    private final UserChangeLocks locks;
    private final UserViews userViews;
    private final AuditWriter auditWriter;
    private final TransactionTemplate transaction;

    public UserService(UserRepository userRepository, UserRoleRepository userRoleRepository,
            RoleRepository roleRepository, KeycloakGateway keycloakGateway, AccountLinker accountLinker,
            UserChangeLocks locks, UserViews userViews, AuditWriter auditWriter,
            PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
        this.keycloakGateway = keycloakGateway;
        this.accountLinker = accountLinker;
        this.locks = locks;
        this.userViews = userViews;
        this.auditWriter = auditWriter;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    // ---------------------------------------------------------------------------- list and get

    /** {@code GET /users}: the filters of section 4.5, ordered by username, then id. */
    public UserPage list(UserStatus status, UUID roleId, String facilityScope, String q, Pageable pageable) {
        Page<UserEntity> page = userRepository.findAll(
                UserSearchSpecifications.matching(status, roleId, facilityScope, q), pageable);
        Map<UUID, RoleEntity> roles = page.isEmpty() ? Map.of()
                : roleRepository.findAll().stream().collect(Collectors.toMap(RoleEntity::getId, Function.identity()));
        List<User> items = page.getContent().stream()
                .map(user -> UserViews.toUser(user, userRoleRepository.findByUserId(user.getId()).stream()
                        .map(userRole -> roles.get(userRole.getRoleId()))
                        .filter(Objects::nonNull)
                        .toList()))
                .toList();
        return new UserPage(items, page.getTotalElements());
    }

    /** {@code GET /users/:id}. */
    public User get(UUID id) {
        return userViews.view(load(id));
    }

    // ------------------------------------------------------------------------- 5.1 create user

    /** {@code POST /users} (5.1, operation {@code USER_CREATE}). */
    public UserCreated create(CreateUserRequest request) {
        // Step 1: the roles, which must exist and be assignable, and the new user's id.
        List<RoleEntity> roles = loadRoles(request.roleIds());
        requireAssignable(request.roleIds(), roles);
        UUID userId = UUID.randomUUID();
        String username = request.username();
        String email = request.email().toLowerCase(Locale.ROOT);

        // Step 2: username and email (ignoring case) free in users.
        if (userRepository.existsByUsername(username)) {
            throw new ApiException(ErrorCode.USERNAME_IN_USE, "Another user has this username.");
        }
        if (userRepository.existsByEmail(email)) {
            throw new ApiException(ErrorCode.EMAIL_IN_USE, "Another user has this email address.");
        }

        // Step 3: an existing Keycloak account is linked, or refused; then the realm roles.
        List<KeycloakUserInfo> accounts;
        try {
            accounts = keycloakGateway.findUsersByUsernameOrEmail(username, email);
        } catch (RuntimeException e) {
            throw nothingChanged(AccountLinker.OPERATION_USER_CREATE, userId, STEP_KEYCLOAK_ACCOUNTS_CHECKED,
                    "Looking up the Keycloak accounts failed. Nothing was changed.", e);
        }
        KeycloakUserInfo linkable = accounts.stream()
                .filter(account -> account.enabled()
                        && username.equalsIgnoreCase(account.username())
                        && email.equalsIgnoreCase(account.email())
                        && userRepository.findByKeycloakId(account.id()).isEmpty())
                .findFirst()
                .orElse(null);
        if (linkable == null && !accounts.isEmpty()) {
            throw unlinkable(accounts, username, email);
        }
        requireSynced(request.roleIds(), roles, AccountLinker.OPERATION_USER_CREATE, userId);

        KeycloakUserInfo account;
        if (linkable != null) {
            // DESIGN-GAP: 5.1 does not say whether a linked account's local row takes its names from
            // the request or from Keycloak. Username and email match by definition; the names come
            // from the request, which is what the administrator entered and what the 201 returns.
            // Keycloak's own names are left as they are, because 5.1 has no profile step.
            account = new KeycloakUserInfo(linkable.id(), linkable.username(), email, request.firstName(),
                    request.lastName(), linkable.enabled(), linkable.emailVerified(), linkable.realmRoles());
        } else {
            // Step 4: KEYCLOAK_USER_CREATED.
            UUID keycloakId;
            try {
                keycloakId = keycloakGateway.createUser(username, email, request.firstName(), request.lastName(),
                        UPDATE_PASSWORD);
            } catch (KeycloakConflictException e) {
                throw raceConflict(username, userId);
            } catch (RuntimeException e) {
                throw nothingChanged(AccountLinker.OPERATION_USER_CREATE, userId,
                        AccountLinker.STEP_KEYCLOAK_USER_CREATED, "Creating the Keycloak account failed.", e);
            }
            account = new KeycloakUserInfo(keycloakId, username, email, request.firstName(), request.lastName(),
                    true, false, List.of());
        }

        // Steps 5 and 6: KEYCLOAK_ROLES_ASSIGNED and LOCAL_USER_SAVED, the linking method.
        accountLinker.link(account, userId, roles, request.facilityScope(), linkable != null);

        // Step 7: the set-password email. A failure is only a warning (AD-08).
        List<Warning> warnings = new ArrayList<>();
        try {
            keycloakGateway.sendExecuteActionsEmail(account.id(), UPDATE_PASSWORD);
        } catch (RuntimeException e) {
            log.warn("The set-password email for user {} (Keycloak user {}) could not be sent", userId,
                    account.id(), e);
            warnings.add(Warning.of("ACTION_EMAIL_NOT_SENT", "The user was created, but the set-password email"
                    + " could not be sent. Use reset-password to send it again."));
        }

        // Step 8: 201, read back after the commit.
        return new UserCreated(get(userId), warnings);
    }

    /** 5.1 step 3: why the account with this username or email cannot be linked. */
    private ApiException unlinkable(List<KeycloakUserInfo> accounts, String username, String email) {
        for (KeycloakUserInfo account : accounts) {
            if (username.equalsIgnoreCase(account.username())) {
                return new ApiException(ErrorCode.USERNAME_IN_USE,
                        "The username belongs to " + whyNotLinkable(account, email.equalsIgnoreCase(account.email()),
                                "its email address differs"));
            }
        }
        for (KeycloakUserInfo account : accounts) {
            if (email.equalsIgnoreCase(account.email())) {
                return new ApiException(ErrorCode.EMAIL_IN_USE,
                        "The address belongs to " + whyNotLinkable(account,
                                username.equalsIgnoreCase(account.username()), "its username differs"));
            }
        }
        // findUsersByUsernameOrEmail matches exactly, so every account matches one of the two fields.
        throw new IllegalStateException("A Keycloak account matched neither the username nor the email");
    }

    private String whyNotLinkable(KeycloakUserInfo account, boolean otherFieldMatches, String otherFieldDiffers) {
        if (userRepository.findByKeycloakId(account.id()).isPresent()) {
            return "a Keycloak account that is already linked to another local user.";
        }
        List<String> reasons = new ArrayList<>();
        if (!otherFieldMatches) {
            reasons.add(otherFieldDiffers);
        }
        if (!account.enabled()) {
            reasons.add("it is disabled");
        }
        String reason = String.join(" and ", reasons);
        return "a Keycloak account with no local user that cannot be linked, because " + reason + ".";
    }

    /** 5.1 step 4: a Keycloak 409 (a race) is resolved by looking up the username. Nothing exists. */
    private ApiException raceConflict(String username, UUID userId) {
        boolean usernameTaken;
        try {
            usernameTaken = keycloakGateway.findUserByUsername(username).isPresent();
        } catch (RuntimeException e) {
            return nothingChanged(AccountLinker.OPERATION_USER_CREATE, userId, AccountLinker.STEP_KEYCLOAK_USER_CREATED,
                    "Creating the Keycloak account failed.", e);
        }
        return usernameTaken
                ? new ApiException(ErrorCode.USERNAME_IN_USE, "A Keycloak account with this username was just created.")
                : new ApiException(ErrorCode.EMAIL_IN_USE, "A Keycloak account with this address was just created.");
    }

    // ----------------------------------------------------------------------- 5.2 update profile

    /** {@code PUT /users/:id} (5.2, {@code USER_PROFILE_UPDATE}). */
    public User update(UUID id, UpdateUserRequest request) {
        String email = request.email().toLowerCase(Locale.ROOT);
        Flow flow = new Flow(OPERATION_USER_PROFILE_UPDATE, id, AuditAction.USER_UPDATED);
        run(flow, () -> {
            // Step 1: lock and load; a changed email must be free in users.
            UserEntity user = locks.lockUser(id);
            boolean emailChanged = !email.equals(user.getEmail());
            if (emailChanged && userRepository.existsByEmailAndIdNot(email, id)) {
                throw new ApiException(ErrorCode.EMAIL_IN_USE, "Another user has this email address.");
            }
            boolean keycloakChanged = emailChanged
                    || !request.firstName().equals(user.getFirstName())
                    || !request.lastName().equals(user.getLastName());

            // Step 2: no-op.
            if (!keycloakChanged && Objects.equals(request.facilityScope(), user.getFacilityScope())) {
                return;
            }
            List<RoleEntity> roles = rolesOf(id);
            User before = UserViews.toUser(user, roles);
            flow.prepare(user.getKeycloakId(), before, new User(before.id(), before.keycloakId(), before.username(),
                    email, request.firstName(), request.lastName(), before.status(), request.facilityScope(),
                    before.roles(), before.lastLoginAt(), before.createdAt(), before.updatedAt()));

            // Step 3: KEYCLOAK_PROFILE_UPDATED, only when the email or a name changed.
            if (keycloakChanged) {
                flow.keycloak(STEP_KEYCLOAK_PROFILE_UPDATED, () -> {
                    try {
                        keycloakGateway.updateProfile(user.getKeycloakId(), email, request.firstName(),
                                request.lastName(), !emailChanged && currentEmailVerified(user.getKeycloakId()));
                    } catch (KeycloakConflictException e) {
                        throw new ApiException(ErrorCode.EMAIL_IN_USE,
                                "The address belongs to another Keycloak account.");
                    }
                });
            }

            // Step 4: LOCAL_PROFILE_SAVED.
            flow.local(STEP_LOCAL_PROFILE_SAVED);
            user.setEmail(email);
            user.setFirstName(request.firstName());
            user.setLastName(request.lastName());
            user.setFacilityScope(request.facilityScope());
            UserEntity saved = userRepository.saveAndFlush(user);
            auditWriter.record(AuditEntry.success(AuditAction.USER_UPDATED, AuditEntityType.USER, id, before,
                    UserViews.toUser(saved, roles)));
            userRepository.flush();
        });
        return get(id);
    }

    /**
     * DESIGN-GAP: {@code KeycloakGateway.updateProfile} always writes {@code emailVerified}, and 5.2
     * says only a changed email sets it to false. When the email is unchanged, the account's current
     * flag is read first, as part of {@code KEYCLOAK_PROFILE_UPDATED}, so it is kept.
     */
    private boolean currentEmailVerified(UUID keycloakId) {
        return keycloakGateway.getUser(keycloakId)
                .orElseThrow(() -> new IllegalStateException("Keycloak user " + keycloakId + " does not exist"))
                .emailVerified();
    }

    // ------------------------------------------------------------------------ 5.3 change status

    /** {@code PATCH /users/:id/status} (5.3, {@code USER_STATUS_CHANGE}). */
    public User changeStatus(UUID id, ChangeStatusRequest request) {
        UserStatus target = request.status();
        AuditAction action = switch (target) {
            case ACTIVE -> AuditAction.USER_ACTIVATED;
            case INACTIVE -> AuditAction.USER_DEACTIVATED;
            case LOCKED -> AuditAction.USER_LOCKED;
        };
        Flow flow = new Flow(OPERATION_USER_STATUS_CHANGE, id, action);
        run(flow, () -> {
            // Step 1: lock and load; no-op when unchanged.
            UserEntity user = locks.lockUser(id);
            UserStatus current = user.getStatus();
            if (current == target) {
                return;
            }
            List<RoleEntity> roles = rolesOf(id);

            // Step 2: the last administrator.
            if (current == UserStatus.ACTIVE && target != UserStatus.ACTIVE && holdsSuperAdmin(roles)) {
                locks.requireAnotherActiveAdmin(id);
            }
            User before = UserViews.toUser(user, roles);
            flow.prepare(user.getKeycloakId(), before, new User(before.id(), before.keycloakId(), before.username(),
                    before.email(), before.firstName(), before.lastName(), target, before.facilityScope(),
                    before.roles(), before.lastLoginAt(), before.createdAt(), before.updatedAt()));

            // Step 3: KEYCLOAK_ENABLED_CHANGED, only when Keycloak's enabled flag changes.
            boolean enable = target == UserStatus.ACTIVE;
            if ((current == UserStatus.ACTIVE) != enable) {
                flow.keycloak(STEP_KEYCLOAK_ENABLED_CHANGED,
                        () -> keycloakGateway.setEnabled(user.getKeycloakId(), enable));
            }

            // Step 4: KEYCLOAK_SESSIONS_ENDED, only when the new status is not ACTIVE.
            if (!enable) {
                flow.keycloak(STEP_KEYCLOAK_SESSIONS_ENDED,
                        () -> keycloakGateway.logoutAllSessions(user.getKeycloakId()));
            }

            // Step 5: LOCAL_STATUS_SAVED. user_roles is never touched (B-34).
            flow.local(STEP_LOCAL_STATUS_SAVED);
            user.setStatus(target);
            UserEntity saved = userRepository.saveAndFlush(user);
            auditWriter.record(AuditEntry.success(action, AuditEntityType.USER, id, before,
                    UserViews.toUser(saved, roles)));
            userRepository.flush();
        });
        return get(id);
    }

    // ------------------------------------------------------------------------- 5.4 replace roles

    /** {@code PUT /users/:id/roles} (5.4, {@code USER_ROLES_UPDATE}). Only changed realm roles move (B-35). */
    public User replaceRoles(UUID id, ReplaceUserRolesRequest request) {
        // Step 1: the roles (unknown ids give 400), then lock and load the user.
        List<RoleEntity> requested = loadRoles(request.roleIds());
        Flow flow = new Flow(OPERATION_USER_ROLES_UPDATE, id, AuditAction.USER_ROLES_UPDATED);
        run(flow, () -> {
            UserEntity user = locks.lockUser(id);

            // Step 2: added and removed; no-op when both are empty.
            List<RoleEntity> current = rolesOf(id);
            Set<UUID> currentIds = current.stream().map(RoleEntity::getId).collect(Collectors.toSet());
            Set<UUID> requestedIds = requested.stream().map(RoleEntity::getId).collect(Collectors.toSet());
            List<RoleEntity> added = requested.stream().filter(r -> !currentIds.contains(r.getId())).toList();
            List<RoleEntity> removed = current.stream().filter(r -> !requestedIds.contains(r.getId())).toList();
            if (added.isEmpty() && removed.isEmpty()) {
                return;
            }

            // Step 3: the added roles must be assignable and have a realm role.
            if (!added.isEmpty()) {
                requireAssignable(request.roleIds(), added);
                requireSynced(request.roleIds(), added, OPERATION_USER_ROLES_UPDATE, id);
            }

            // Step 4: the last administrator.
            if (user.getStatus() == UserStatus.ACTIVE && holdsSuperAdmin(removed)) {
                locks.requireAnotherActiveAdmin(id);
            }
            Map<String, Object> before = Map.of("roles", UserViews.toRoles(current));
            Map<String, Object> after = Map.of("roles", UserViews.toRoles(requested));
            flow.prepare(user.getKeycloakId(), before, after);
            UUID keycloakId = user.getKeycloakId();

            // Step 5: KEYCLOAK_ROLES_ADDED.
            if (!added.isEmpty()) {
                flow.keycloak(STEP_KEYCLOAK_ROLES_ADDED, () -> keycloakGateway.addRealmRoles(keycloakId, names(added)));
            }

            // Step 6: KEYCLOAK_ROLES_REMOVED. The client skips a role whose realm role is gone (AD-10).
            if (!removed.isEmpty()) {
                flow.keycloak(STEP_KEYCLOAK_ROLES_REMOVED,
                        () -> keycloakGateway.removeRealmRoles(keycloakId, names(removed)));
            }

            // Step 7: LOCAL_ROLES_SAVED. The bulk delete clears the persistence context, so
            // updated_at is set with touchUpdatedAt, not through the now detached entity.
            flow.local(STEP_LOCAL_ROLES_SAVED);
            if (!removed.isEmpty()) {
                userRoleRepository.deleteByUserIdAndRoleIdIn(id, removed.stream().map(RoleEntity::getId).toList());
            }
            for (RoleEntity role : added) {
                UserRoleEntity userRole = new UserRoleEntity();
                userRole.setUserId(id);
                userRole.setRoleId(role.getId());
                userRoleRepository.save(userRole);
            }
            userRoleRepository.flush();
            userRepository.touchUpdatedAt(id, Instant.now().truncatedTo(ChronoUnit.MICROS));
            auditWriter.record(AuditEntry.success(AuditAction.USER_ROLES_UPDATED, AuditEntityType.USER, id, before,
                    after));
            userRepository.flush();
        });
        return get(id);
    }

    // ------------------------------------------------------------------------ 5.5 reset password

    /** {@code POST /users/:id/reset-password} (5.5, {@code USER_PASSWORD_RESET}). Allowed for any status. */
    public ResetPasswordResult resetPassword(UUID id) {
        // Step 1.
        UserEntity user = load(id);

        // Step 2: KEYCLOAK_ACTION_EMAIL_SENT. A failure changed nothing: 502, no audit row.
        try {
            keycloakGateway.sendExecuteActionsEmail(user.getKeycloakId(), UPDATE_PASSWORD);
        } catch (RuntimeException e) {
            throw nothingChanged(OPERATION_USER_PASSWORD_RESET, id, STEP_KEYCLOAK_ACTION_EMAIL_SENT,
                    "Sending the set-password email failed. Nothing changed. Try again.", e);
        }

        // Step 3: USER_PASSWORD_RESET_REQUESTED. No password passes through the service (B-36).
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("email", user.getEmail());
        after.put("actions", UPDATE_PASSWORD);
        transaction.executeWithoutResult(status -> auditWriter.record(AuditEntry.success(
                AuditAction.USER_PASSWORD_RESET_REQUESTED, AuditEntityType.USER, id, null, after)));
        return new ResetPasswordResult(id, user.getEmail());
    }

    // ---------------------------------------------------------------------------------- helpers

    private UserEntity load(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND, "No user has this id."));
    }

    /** The user's roles, from its {@code user_roles} rows, ordered by name. */
    private List<RoleEntity> rolesOf(UUID userId) {
        List<UUID> roleIds = userRoleRepository.findByUserId(userId).stream().map(UserRoleEntity::getRoleId).toList();
        return roleIds.isEmpty() ? List.of()
                : roleRepository.findAllById(roleIds).stream().sorted(Comparator.comparing(RoleEntity::getName)).toList();
    }

    private static boolean holdsSuperAdmin(Collection<RoleEntity> roles) {
        return roles.stream().anyMatch(role -> AccountLinker.SUPER_ADMIN.equals(role.getName()));
    }

    private static List<String> names(Collection<RoleEntity> roles) {
        return roles.stream().map(RoleEntity::getName).toList();
    }

    /** Distinct ids of existing roles, in request order. An unknown or repeated id gives 400 on {@code roleIds[i]}. */
    private List<RoleEntity> loadRoles(List<UUID> roleIds) {
        Map<UUID, RoleEntity> found = roleRepository.findAllById(new HashSet<>(roleIds)).stream()
                .collect(Collectors.toMap(RoleEntity::getId, Function.identity()));
        List<FieldErrorDto> details = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        List<RoleEntity> roles = new ArrayList<>();
        for (int i = 0; i < roleIds.size(); i++) {
            UUID roleId = roleIds.get(i);
            if (!seen.add(roleId)) {
                details.add(new FieldErrorDto("roleIds[" + i + "]", "The same role id is listed twice."));
            } else if (!found.containsKey(roleId)) {
                details.add(new FieldErrorDto("roleIds[" + i + "]", "No role has this id."));
            } else {
                roles.add(found.get(roleId));
            }
        }
        if (!details.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "The request is not valid.", details);
        }
        return roles;
    }

    /** 409 {@code ROLE_NOT_ASSIGNABLE}, with one detail per role concerned (AD-14). */
    private static void requireAssignable(List<UUID> requestIds, Collection<RoleEntity> roles) {
        List<RoleEntity> refused = roles.stream().filter(role -> !Boolean.TRUE.equals(role.getAssignable())).toList();
        if (!refused.isEmpty()) {
            throw new ApiException(ErrorCode.ROLE_NOT_ASSIGNABLE, "A role in the request cannot be assigned to users.",
                    details(requestIds, refused, role -> "Role " + role.getName() + " cannot be assigned to users."));
        }
    }

    /**
     * 409 {@code ROLE_NOT_SYNCED} when a role has no realm role in Keycloak yet (AD-10). The realm
     * role names are listed once. A failure of that lookup changed nothing (DEV-8).
     */
    private void requireSynced(List<UUID> requestIds, Collection<RoleEntity> roles, String operation, UUID entityId) {
        Set<String> realmRoles;
        try {
            realmRoles = keycloakGateway.listRealmRoleNames();
        } catch (RuntimeException e) {
            throw nothingChanged(operation, entityId, STEP_KEYCLOAK_REALM_ROLES_CHECKED,
                    "Listing the Keycloak realm roles failed. Nothing was changed.", e);
        }
        List<RoleEntity> missing = roles.stream().filter(role -> !realmRoles.contains(role.getName())).toList();
        if (!missing.isEmpty()) {
            throw new ApiException(ErrorCode.ROLE_NOT_SYNCED,
                    "A role in the request has no Keycloak realm role yet. Run a sync of the role first.",
                    details(requestIds, missing, role -> "Role " + role.getName() + " has no Keycloak realm role yet."));
        }
    }

    private static List<FieldErrorDto> details(List<UUID> requestIds, List<RoleEntity> roles,
            Function<RoleEntity, String> message) {
        Map<UUID, RoleEntity> byId = roles.stream().collect(Collectors.toMap(RoleEntity::getId, Function.identity()));
        List<FieldErrorDto> details = new ArrayList<>();
        for (int i = 0; i < requestIds.size(); i++) {
            RoleEntity role = byId.get(requestIds.get(i));
            if (role != null) {
                details.add(new FieldErrorDto("roleIds[" + i + "]", message.apply(role)));
            }
        }
        return details;
    }

    /** A 502 for a Keycloak failure before anything changed: {@code completedSteps []}, no row (rule 4). */
    private static KeycloakSyncFailedException nothingChanged(String operation, UUID entityId, String failedStep,
            String message, RuntimeException cause) {
        log.warn("{} failed at step {} for {}: nothing changed", operation, failedStep, entityId, cause);
        return new KeycloakSyncFailedException(message,
                new PartialState(operation, entityId, failedStep, List.of(), null, null));
    }

    /** Runs flows 5.2 to 5.4 in one transaction that holds the user's lock (section 5, "Locks"). */
    private void run(Flow flow, Runnable body) {
        try {
            transaction.executeWithoutResult(status -> body.run());
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            // The local step (or the commit that ends it) failed. After a Keycloak step this is a
            // partial state (rule 3); before any, nothing changed and it is an ordinary failure.
            if (flow.localStep == null || flow.completed.isEmpty()) {
                throw e;
            }
            throw flow.failed(flow.localStep, e,
                    "Keycloak was changed, but saving the local user failed. Submit the same request again.");
        }
    }

    /**
     * The progress of one of flows 5.2 to 5.4, for the 502 and its failure row (section 8, rule 3).
     *
     * <p>DESIGN-GAP: section 4.2 sets {@code keycloakUserId} "when a Keycloak user exists that the
     * local database may not reflect". After a failed Keycloak write in 5.2 to 5.4, Keycloak may hold
     * the new state (for example after a timeout), so it is the user's Keycloak id on every such
     * 502. It is null for a lookup that failed before any write.
     */
    private final class Flow {

        private final String operation;
        private final UUID userId;
        private final AuditAction action;
        private final List<String> completed = new ArrayList<>();
        private UUID keycloakId;
        private Object before;
        private Object after;
        private String localStep;

        Flow(String operation, UUID userId, AuditAction action) {
            this.operation = operation;
            this.userId = userId;
            this.action = action;
        }

        /** The Keycloak id, and the before and intended after JSON of a failure row. */
        void prepare(UUID keycloakId, Object before, Object after) {
            this.keycloakId = keycloakId;
            this.before = before;
            this.after = after;
        }

        /** One named Keycloak step. A 4xx {@link ApiException} from it passes through unchanged. */
        void keycloak(String step, Runnable call) {
            try {
                call.run();
            } catch (ApiException e) {
                throw e;
            } catch (RuntimeException e) {
                throw failed(step, e, "The Keycloak step " + step + " failed. Submit the same request again.");
            }
            completed.add(step);
        }

        void local(String step) {
            localStep = step;
        }

        KeycloakSyncFailedException failed(String failedStep, RuntimeException cause, String message) {
            List<String> completedSteps = List.copyOf(completed);
            UUID auditLogId = null;
            if (!completedSteps.isEmpty()) {
                auditLogId = recordFailure(new AccountLinker.Failure(operation, failedStep, completedSteps, keycloakId,
                        describe(cause)));
            }
            log.warn("{} failed at step {} for user {}", operation, failedStep, userId, cause);
            return new KeycloakSyncFailedException(message,
                    new PartialState(operation, userId, failedStep, completedSteps, keycloakId, auditLogId));
        }

        /** Rules 3 and 5: the failure row in its own transaction, or null when it cannot be written. */
        private UUID recordFailure(AccountLinker.Failure failure) {
            try {
                return auditWriter.recordInNewTransaction(
                        AuditEntry.failed(action, AuditEntityType.USER, userId, before, after, failure));
            } catch (RuntimeException e) {
                log.error("Could not write the failure row for {} {}", operation, userId, e);
                return null;
            }
        }
    }

    private static String describe(Throwable e) {
        return e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }
}
