package org.cce.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.cce.admin.audit.AuditAction;
import org.cce.admin.audit.AuditEntry;
import org.cce.admin.audit.AuditWriter;
import org.cce.admin.client.KeycloakGateway;
import org.cce.admin.controller.dto.CommonDtos.Warning;
import org.cce.admin.controller.dto.RoleDtos.CreateRoleRequest;
import org.cce.admin.controller.dto.RoleDtos.PermissionRow;
import org.cce.admin.controller.dto.RoleDtos.Role;
import org.cce.admin.controller.dto.RoleDtos.UpdateRoleRequest;
import org.cce.admin.entity.AuditEntityType;
import org.cce.admin.entity.RoleEntity;
import org.cce.admin.entity.RoleMenuEntity;
import org.cce.admin.entity.SyncStatus;
import org.cce.admin.repository.ApiPermissionRepository;
import org.cce.admin.repository.RoleMenuRepository;
import org.cce.admin.repository.RoleRepository;
import org.cce.admin.repository.UserRoleRepository;
import org.cce.admin.service.exception.ApiException;
import org.cce.admin.service.exception.ErrorCode;
import org.cce.admin.service.exception.KeycloakSyncFailedException;
import org.cce.admin.service.exception.PartialState;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Roles: list, get, create (5.6), update display fields (5.12) and delete (5.9). No class- or
 * method-level {@code @Transactional}: each database step runs in its own {@link TransactionTemplate}
 * and commits before the next Keycloak or gateway call.
 */
@Service
public class RoleService {

    /** The sort of {@code GET /roles}: {@code name}, then {@code id} (section 4.3). */
    public static final Sort SORT = Sort.by("name", "id");

    // DESIGN-GAP: section 4.2 has no operation or step names for a Keycloak call that fails in
    // POST /roles (the realm role check, 5.6 step 1) or DELETE /roles/:id (the member check, 5.9
    // step 2). Both fail before anything changed, so DEV-8 applies: 502 KEYCLOAK_SYNC_FAILED with
    // completedSteps [] and no audit row (rule 4). These names are used for that case.
    public static final String OPERATION_ROLE_CREATE = "ROLE_CREATE";
    public static final String OPERATION_ROLE_DELETE = "ROLE_DELETE";
    public static final String STEP_KEYCLOAK_REALM_ROLE_CHECKED = "KEYCLOAK_REALM_ROLE_CHECKED";
    public static final String STEP_KEYCLOAK_ROLE_MEMBERS_CHECKED = "KEYCLOAK_ROLE_MEMBERS_CHECKED";

    /** One page of roles, with the true total (section 4.3). */
    public record RolePage(List<Role> items, long totalItems) {
    }

    /** The created role, and the warning {@code ROLE_SYNC_FAILED} when its first sync failed. */
    public record RoleCreated(Role role, List<Warning> warnings) {
    }

    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleMenuRepository roleMenuRepository;
    private final ApiPermissionRepository apiPermissionRepository;
    private final KeycloakGateway keycloakGateway;
    private final GatewaySyncService gatewaySyncService;
    private final AuditWriter auditWriter;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;

    public RoleService(RoleRepository roleRepository, UserRoleRepository userRoleRepository,
            RoleMenuRepository roleMenuRepository, ApiPermissionRepository apiPermissionRepository,
            KeycloakGateway keycloakGateway, GatewaySyncService gatewaySyncService, AuditWriter auditWriter,
            ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.roleMenuRepository = roleMenuRepository;
        this.apiPermissionRepository = apiPermissionRepository;
        this.keycloakGateway = keycloakGateway;
        this.gatewaySyncService = gatewaySyncService;
        this.auditWriter = auditWriter;
        this.objectMapper = objectMapper;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** {@code GET /roles}: ordered by name, then id, with {@code userCount} from one query per page. */
    public RolePage list(Pageable pageable) {
        Page<RoleEntity> page = roleRepository.findAll(pageable);
        Map<UUID, Long> counts = userCounts(page.getContent().stream().map(RoleEntity::getId).toList());
        List<Role> items = page.getContent().stream()
                .map(role -> toRole(role, counts.getOrDefault(role.getId(), 0L)))
                .toList();
        return new RolePage(items, page.getTotalElements());
    }

    /** {@code GET /roles/:id}. */
    public Role get(UUID id) {
        RoleEntity role = load(id);
        return view(role);
    }

    /** {@code POST /roles} (5.6). The create transaction commits before the sync runs. */
    public RoleCreated create(CreateRoleRequest request) {
        UUID roleId = UUID.randomUUID();
        String name = request.name();

        // Step 1: the name must be unused in roles, in api_permissions and as a realm role.
        if (roleRepository.existsByName(name)) {
            throw duplicateName(name, "A role with this name exists.");
        }
        if (apiPermissionRepository.existsByPermissionName(name)) {
            throw duplicateName(name, "api_permissions already has rows with this permission name.");
        }
        boolean realmRoleExists;
        try {
            realmRoleExists = keycloakGateway.findRealmRole(name).isPresent();
        } catch (RuntimeException e) {
            throw new KeycloakSyncFailedException("Checking the Keycloak realm roles failed. Nothing was changed.",
                    new PartialState(OPERATION_ROLE_CREATE, roleId, STEP_KEYCLOAK_REALM_ROLE_CHECKED, List.of(),
                            null, null));
        }
        if (realmRoleExists) {
            throw duplicateName(name, "A Keycloak realm role with this name exists.");
        }

        // Step 2: the role, its two implicit rows and ROLE_CREATED, in one transaction.
        transaction.executeWithoutResult(status -> {
            RoleEntity role = new RoleEntity();
            role.setId(roleId);
            role.setName(name);
            role.setDisplayName(request.displayName());
            role.setDescription(request.description());
            role.setSystem(false);
            role.setAssignable(true);
            role.setSyncStatus(SyncStatus.NEVER);
            RoleEntity saved = roleRepository.save(role);
            List<PermissionRow> implicitRows = ImplicitPermissions.ROWS.stream()
                    .map(definition -> apiPermissionRepository.save(definition.toEntity(name)))
                    .map(RolePermissionService::toRow)
                    .toList();
            // DESIGN-GAP: section 8 gives ROLE_CREATED's after JSON as "Role and its implicit rows"
            // without naming the property for the rows. They go under "implicit", the name that
            // ROLE_PERMISSIONS_UPDATED uses for the same rows.
            ObjectNode after = objectMapper.valueToTree(toRole(saved, 0));
            after.set("implicit", objectMapper.valueToTree(implicitRows));
            auditWriter.record(AuditEntry.success(AuditAction.ROLE_CREATED, AuditEntityType.ROLE, saved.getId(),
                    null, after));
        });

        // Step 3: the sync procedure (5.10).
        GatewaySyncService.Outcome outcome = gatewaySyncService.sync(roleId);

        // Step 4: 201, with ROLE_SYNC_FAILED when the sync failed.
        Role role = view(load(roleId));
        List<Warning> warnings = outcome.success()
                ? List.of()
                : List.of(Warning.of("ROLE_SYNC_FAILED",
                        "The role was created, but its first gateway sync failed. Use Re-sync before assigning it."));
        return new RoleCreated(role, warnings);
    }

    /** {@code PUT /roles/:id} (5.12): one transaction that writes {@code ROLE_UPDATED}. */
    public Role update(UUID id, UpdateRoleRequest request) {
        transaction.executeWithoutResult(status -> {
            RoleEntity role = load(id);
            requireNotSystem(role);
            if (Objects.equals(role.getDisplayName(), request.displayName())
                    && Objects.equals(role.getDescription(), request.description())) {
                return; // No-op: nothing is written or audited.
            }
            long userCount = userCount(id);
            Role before = toRole(role, userCount);
            role.setDisplayName(request.displayName());
            role.setDescription(request.description());
            RoleEntity saved = roleRepository.saveAndFlush(role);
            auditWriter.record(AuditEntry.success(AuditAction.ROLE_UPDATED, AuditEntityType.ROLE, id, before,
                    toRole(saved, userCount)));
        });
        // Read back after the commit, so the response carries the stored timestamps.
        return get(id);
    }

    /** {@code DELETE /roles/:id} (5.9). No gateway sync runs (AD-12). */
    public void delete(UUID id) {
        // Step 1.
        RoleEntity role = load(id);
        requireNotSystem(role);

        // Step 2: held by a local user of any status, or by a Keycloak user.
        if (userRoleRepository.existsByRoleId(id)) {
            throw new ApiException(ErrorCode.ROLE_IN_USE, "The role is held by at least one user.");
        }
        boolean heldInKeycloak;
        try {
            heldInKeycloak = keycloakGateway.hasRealmRoleMembers(role.getName());
        } catch (RuntimeException e) {
            throw new KeycloakSyncFailedException("Checking the Keycloak role members failed. Nothing was changed.",
                    new PartialState(OPERATION_ROLE_DELETE, id, STEP_KEYCLOAK_ROLE_MEMBERS_CHECKED, List.of(),
                            null, null));
        }
        if (heldInKeycloak) {
            throw new ApiException(ErrorCode.ROLE_IN_USE, "A Keycloak user holds the role's realm role.");
        }

        // Step 3: its role_menus and api_permissions rows, the role, and ROLE_DELETED, in one transaction.
        transaction.executeWithoutResult(status -> {
            RoleEntity current = load(id);
            List<UUID> menuIds = RoleMenuService.sortedIds(
                    roleMenuRepository.findByRoleId(id).stream().map(RoleMenuEntity::getMenuId).toList());
            List<PermissionRow> rows = apiPermissionRepository
                    .findByPermissionNameOrderByUriPatternAscHttpMethodAsc(current.getName()).stream()
                    .map(RolePermissionService::toRow)
                    .toList();
            ObjectNode before = objectMapper.valueToTree(toRole(current, 0));
            before.set("menuIds", objectMapper.valueToTree(menuIds));
            before.set("permissions", objectMapper.valueToTree(rows));
            roleMenuRepository.deleteByRoleId(id);
            apiPermissionRepository.deleteByPermissionName(current.getName());
            roleRepository.deleteById(id);
            roleRepository.flush();
            auditWriter.record(AuditEntry.success(AuditAction.ROLE_DELETED, AuditEntityType.ROLE, id, before, null));
        });
    }

    /** The role, or 404 {@code ROLE_NOT_FOUND}. */
    RoleEntity load(UUID id) {
        return roleRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.ROLE_NOT_FOUND, "No role has this id."));
    }

    /** 409 {@code SYSTEM_ROLE_IMMUTABLE} for an {@code is_system} role (AD-07). */
    static void requireNotSystem(RoleEntity role) {
        if (role.isSystem()) {
            throw new ApiException(ErrorCode.SYSTEM_ROLE_IMMUTABLE,
                    "System role " + role.getName() + " cannot be changed or deleted.");
        }
    }

    private Role view(RoleEntity role) {
        return toRole(role, userCount(role.getId()));
    }

    private long userCount(UUID roleId) {
        return userCounts(List.of(roleId)).getOrDefault(roleId, 0L);
    }

    private Map<UUID, Long> userCounts(Collection<UUID> roleIds) {
        Map<UUID, Long> counts = new HashMap<>();
        if (roleIds.isEmpty()) {
            return counts;
        }
        for (Object[] row : userRoleRepository.countByRoleIds(roleIds)) {
            counts.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    public static Role toRole(RoleEntity role, long userCount) {
        return new Role(
                role.getId(),
                role.getName(),
                role.getDisplayName(),
                role.getDescription(),
                role.isSystem(),
                Boolean.TRUE.equals(role.getAssignable()),
                role.getSyncStatus(),
                micros(role.getLastSyncAt()),
                userCount,
                micros(role.getCreatedAt()),
                micros(role.getUpdatedAt()));
    }

    /**
     * An instant at the database's precision. Section 4.1 lets clients expect at most 6 fractional
     * digits, while an entity not yet read back holds {@code Instant.now()}'s nanoseconds.
     */
    static Instant micros(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }

    private static ApiException duplicateName(String name, String reason) {
        return new ApiException(ErrorCode.DUPLICATE_ROLE_NAME, "The role name " + name + " cannot be used. " + reason);
    }
}
