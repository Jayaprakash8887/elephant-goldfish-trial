package org.cce.admin.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.cce.admin.audit.AuditAction;
import org.cce.admin.audit.AuditEntry;
import org.cce.admin.audit.AuditWriter;
import org.cce.admin.controller.dto.RoleDtos.PermissionRow;
import org.cce.admin.controller.dto.RoleDtos.ReplacePermissionsRequest;
import org.cce.admin.controller.dto.RoleDtos.RolePermissions;
import org.cce.admin.controller.dto.RoleDtos.RoleSyncResult;
import org.cce.admin.entity.ApiPermissionEntity;
import org.cce.admin.entity.AuditEntityType;
import org.cce.admin.entity.RoleEntity;
import org.cce.admin.entity.SyncStatus;
import org.cce.admin.repository.ApiPermissionRepository;
import org.cce.admin.repository.RoleRepository;
import org.cce.admin.service.exception.ApiException;
import org.cce.admin.service.exception.ErrorCode;
import org.cce.admin.service.exception.KeycloakSyncFailedException;
import org.cce.admin.service.exception.PartialState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A role's permission rows: get, replace (5.7) and manual sync (5.8). No class- or method-level
 * {@code @Transactional}: the {@code PERMISSIONS_SAVED} transaction commits before
 * {@link GatewaySyncService} is called.
 */
@Service
public class RolePermissionService {

    public static final String OPERATION_ROLE_PERMISSIONS_SAVE = "ROLE_PERMISSIONS_SAVE";
    public static final String OPERATION_ROLE_SYNC = "ROLE_SYNC";
    public static final String STEP_PERMISSIONS_SAVED = "PERMISSIONS_SAVED";
    public static final String STEP_GATEWAY_SYNC = "GATEWAY_SYNC";

    /** One stored or requested explicit row, compared on all four fields (5.7 step 3). */
    private record Explicit(String httpMethod, String uriPattern, String description, String resourceCategory) {
    }

    private final RoleRepository roleRepository;
    private final ApiPermissionRepository apiPermissionRepository;
    private final GatewaySyncService gatewaySyncService;
    private final AuditWriter auditWriter;
    private final TransactionTemplate transaction;

    public RolePermissionService(RoleRepository roleRepository, ApiPermissionRepository apiPermissionRepository,
            GatewaySyncService gatewaySyncService, AuditWriter auditWriter,
            PlatformTransactionManager transactionManager) {
        this.roleRepository = roleRepository;
        this.apiPermissionRepository = apiPermissionRepository;
        this.gatewaySyncService = gatewaySyncService;
        this.auditWriter = auditWriter;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** {@code GET /roles/:id/permissions}: implicit rows first, then explicit rows by pattern and method. */
    public RolePermissions get(UUID roleId) {
        RoleEntity role = load(roleId);
        return view(role);
    }

    /** {@code PUT /roles/:id/permissions} (5.7). */
    public RolePermissions replace(UUID roleId, ReplacePermissionsRequest request) {
        // Step 1.
        RoleEntity role = load(roleId);
        RoleService.requireNotSystem(role);

        // Step 2: upper-case, check, drop the implicit rows, reject duplicates.
        List<PermissionRowRules.Row> requested = PermissionRowRules.normalize(request.permissions());

        // Step 3: no-op, with no sync, when the explicit rows are unchanged.
        List<ApiPermissionEntity> stored = rows(role.getName());
        if (explicitSet(stored).equals(requestedSet(requested))) {
            return view(role);
        }

        // Step 4: PERMISSIONS_SAVED, in one transaction that commits before the sync.
        transaction.executeWithoutResult(status -> {
            Map<String, List<PermissionRow>> before = split(rows(role.getName()));
            apiPermissionRepository.deleteExplicitRows(role.getName(), ImplicitPermissions.URIS);
            for (PermissionRowRules.Row row : requested) {
                ApiPermissionEntity entity = new ApiPermissionEntity();
                entity.setPermissionName(role.getName());
                entity.setHttpMethod(row.httpMethod());
                entity.setUriPattern(row.uriPattern());
                entity.setDescription(row.description());
                entity.setResourceCategory(row.resourceCategory());
                apiPermissionRepository.save(entity);
            }
            List<ApiPermissionEntity> remaining = rows(role.getName());
            for (ImplicitPermissions.Definition definition : ImplicitPermissions.ROWS) {
                boolean present = remaining.stream().anyMatch(r -> definition.httpMethod().equals(r.getHttpMethod())
                        && definition.uriPattern().equals(r.getUriPattern()));
                if (!present) {
                    apiPermissionRepository.save(definition.toEntity(role.getName()));
                }
            }
            Map<String, List<PermissionRow>> after = split(rows(role.getName()));
            auditWriter.record(AuditEntry.success(AuditAction.ROLE_PERMISSIONS_UPDATED, AuditEntityType.ROLE,
                    roleId, before, after));
        });

        // Step 5: GATEWAY_SYNC.
        GatewaySyncService.Outcome outcome = gatewaySyncService.sync(roleId);

        // Step 6: 200, or 502 with the rows kept.
        if (!outcome.success()) {
            throw new KeycloakSyncFailedException(
                    "The permissions were saved, but the gateway sync failed. Use Re-sync, and do not assign"
                            + " the role until a sync succeeds.",
                    new PartialState(OPERATION_ROLE_PERMISSIONS_SAVE, roleId, STEP_GATEWAY_SYNC,
                            List.of(STEP_PERMISSIONS_SAVED), null, outcome.auditLogId()));
        }
        return view(load(roleId));
    }

    /** {@code POST /roles/:id/sync} (5.8). Allowed for system roles. */
    public RoleSyncResult sync(UUID roleId) {
        load(roleId);
        GatewaySyncService.Outcome outcome = gatewaySyncService.sync(roleId);
        if (!outcome.success()) {
            throw new KeycloakSyncFailedException("The gateway sync failed. The rows were already saved. Try Re-sync again.",
                    new PartialState(OPERATION_ROLE_SYNC, roleId, STEP_GATEWAY_SYNC, List.of(), null,
                            outcome.auditLogId()));
        }
        return new RoleSyncResult(roleId, outcome.syncStatus(), RoleService.micros(outcome.lastSyncAt()));
    }

    private RoleEntity load(UUID roleId) {
        return roleRepository.findById(roleId)
                .orElseThrow(() -> new ApiException(ErrorCode.ROLE_NOT_FOUND, "No role has this id."));
    }

    private RolePermissions view(RoleEntity role) {
        Map<String, List<PermissionRow>> split = split(rows(role.getName()));
        List<PermissionRow> ordered = new ArrayList<>(split.get("implicit"));
        ordered.addAll(split.get("explicit"));
        return new RolePermissions(role.getId(), role.getSyncStatus(), RoleService.micros(role.getLastSyncAt()),
                List.copyOf(ordered));
    }

    private List<ApiPermissionEntity> rows(String roleName) {
        return apiPermissionRepository.findByPermissionNameOrderByUriPatternAscHttpMethodAsc(roleName);
    }

    /**
     * {@code { explicit, implicit }}, the before and after JSON of {@code ROLE_PERMISSIONS_UPDATED}
     * (section 8). Implicit rows are in the order of {@link ImplicitPermissions#ROWS}; explicit rows
     * by pattern, then method.
     */
    private static Map<String, List<PermissionRow>> split(List<ApiPermissionEntity> rows) {
        List<PermissionRow> explicit = new ArrayList<>();
        List<PermissionRow> implicit = new ArrayList<>();
        for (ImplicitPermissions.Definition definition : ImplicitPermissions.ROWS) {
            rows.stream()
                    .filter(r -> definition.httpMethod().equals(r.getHttpMethod())
                            && definition.uriPattern().equals(r.getUriPattern()))
                    .map(RolePermissionService::toRow)
                    .forEach(implicit::add);
        }
        for (ApiPermissionEntity row : rows) {
            if (!ImplicitPermissions.isImplicit(row.getHttpMethod(), row.getUriPattern())) {
                explicit.add(toRow(row));
            }
        }
        // A LinkedHashMap keeps "explicit" first in the audit JSON.
        Map<String, List<PermissionRow>> split = new LinkedHashMap<>();
        split.put("explicit", List.copyOf(explicit));
        split.put("implicit", List.copyOf(implicit));
        return split;
    }

    private static Set<Explicit> explicitSet(List<ApiPermissionEntity> stored) {
        Set<Explicit> set = new HashSet<>();
        for (ApiPermissionEntity row : stored) {
            if (!ImplicitPermissions.isImplicit(row.getHttpMethod(), row.getUriPattern())) {
                set.add(new Explicit(row.getHttpMethod(), row.getUriPattern(), row.getDescription(),
                        row.getResourceCategory()));
            }
        }
        return set;
    }

    private static Set<Explicit> requestedSet(List<PermissionRowRules.Row> requested) {
        Set<Explicit> set = new HashSet<>();
        for (PermissionRowRules.Row row : requested) {
            set.add(new Explicit(row.httpMethod(), row.uriPattern(), row.description(), row.resourceCategory()));
        }
        return set;
    }

    /** The Permission row shape of section 4.4, with {@code implicit} computed (AD-16). */
    public static PermissionRow toRow(ApiPermissionEntity row) {
        return new PermissionRow(row.getId(), row.getHttpMethod(), row.getUriPattern(), row.getDescription(),
                row.getResourceCategory(), ImplicitPermissions.isImplicit(row.getHttpMethod(), row.getUriPattern()));
    }
}
