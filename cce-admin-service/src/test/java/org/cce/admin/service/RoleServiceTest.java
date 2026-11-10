package org.cce.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.cce.admin.client.KeycloakCallFailedException;
import org.cce.admin.client.SyncResult;
import org.cce.admin.controller.dto.RoleDtos.CreateRoleRequest;
import org.cce.admin.controller.dto.RoleDtos.Role;
import org.cce.admin.controller.dto.RoleDtos.UpdateRoleRequest;
import org.cce.admin.entity.ApiPermissionEntity;
import org.cce.admin.entity.AuditEntityType;
import org.cce.admin.entity.AuditLogEntity;
import org.cce.admin.entity.AuditOutcome;
import org.cce.admin.entity.RoleEntity;
import org.cce.admin.entity.RoleMenuEntity;
import org.cce.admin.entity.SyncStatus;
import org.cce.admin.entity.UserEntity;
import org.cce.admin.entity.UserRoleEntity;
import org.cce.admin.entity.UserStatus;
import org.cce.admin.repository.ApiPermissionRepository;
import org.cce.admin.repository.AuditLogRepository;
import org.cce.admin.repository.RoleMenuRepository;
import org.cce.admin.repository.RoleRepository;
import org.cce.admin.repository.UserRepository;
import org.cce.admin.repository.UserRoleRepository;
import org.cce.admin.service.exception.ApiException;
import org.cce.admin.service.exception.ErrorCode;
import org.cce.admin.service.exception.KeycloakSyncFailedException;
import org.cce.admin.support.DatabaseTestBase;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.RoleRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Role list, get, create (5.6), update (5.12) and delete (5.9), with the audit rows of section 8. */
class RoleServiceTest extends DatabaseTestBase {

    private static final UUID SUPER_ADMIN_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID DASHBOARD_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");

    @Autowired
    private RoleService roleService;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private ApiPermissionRepository apiPermissionRepository;

    @Autowired
    private RoleMenuRepository roleMenuRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private AuditLogRepository auditLog;

    @Autowired
    private PlatformTransactionManager transactionManager;

    // ------------------------------------------------------------------------------- list, get

    @Test
    void listIsOrderedByNameWithUserCountsAndTheTrueTotal() {
        RoleEntity alpha = storedRole("ALPHA_ROLE");
        storedRole("BETA_ROLE");
        holdRole(newUser(UserStatus.ACTIVE), alpha);
        holdRole(newUser(UserStatus.INACTIVE), alpha);

        RoleService.RolePage first = roleService.list(PageRequest.of(0, 2, RoleService.SORT));
        assertThat(first.totalItems()).isEqualTo(4);
        assertThat(first.items()).extracting(Role::name).containsExactly("ALPHA_ROLE", "BETA_ROLE");
        assertThat(first.items().get(0).userCount()).isEqualTo(2);
        assertThat(first.items().get(1).userCount()).isZero();

        RoleService.RolePage second = roleService.list(PageRequest.of(1, 2, RoleService.SORT));
        assertThat(second.items()).extracting(Role::name).containsExactly("CCE_ADMIN_SERVICE", "SUPER_ADMIN");
        assertThat(second.items().get(0).isAssignable()).isFalse();
        assertThat(second.items().get(1).isSystem()).isTrue();

        assertThat(roleService.list(PageRequest.of(5, 2, RoleService.SORT)).items()).isEmpty();
    }

    @Test
    void getReturnsTheRoleOr404() {
        Role role = roleService.get(SUPER_ADMIN_ID);
        assertThat(role.name()).isEqualTo("SUPER_ADMIN");
        assertThat(role.syncStatus()).isEqualTo(SyncStatus.NEVER);
        assertThat(role.lastSyncAt()).isNull();

        expectCode(() -> roleService.get(UUID.randomUUID()), ErrorCode.ROLE_NOT_FOUND);
    }

    // ---------------------------------------------------------------------------- create (5.6)

    @Test
    void createSavesTheRoleAndItsImplicitRowsThenSyncs() {
        when(gatewaySyncClient.sync()).thenReturn(new SyncResult(true, 200, null));

        RoleService.RoleCreated created = roleService.create(
                new CreateRoleRequest("ANALYST", "Analyst", "Reads dashboards"));

        Role role = created.role();
        assertThat(created.warnings()).isEmpty();
        assertThat(role.name()).isEqualTo("ANALYST");
        assertThat(role.displayName()).isEqualTo("Analyst");
        assertThat(role.description()).isEqualTo("Reads dashboards");
        assertThat(role.isSystem()).isFalse();
        assertThat(role.isAssignable()).isTrue();
        assertThat(role.syncStatus()).isEqualTo(SyncStatus.SYNCED);
        assertThat(role.lastSyncAt()).isNotNull();
        assertThat(role.userCount()).isZero();

        List<ApiPermissionEntity> rows = apiPermissionRepository.findByPermissionNameOrderByUriPatternAscHttpMethodAsc("ANALYST");
        assertThat(rows).extracting(ApiPermissionEntity::getHttpMethod, ApiPermissionEntity::getUriPattern,
                        ApiPermissionEntity::getDescription, ApiPermissionEntity::getResourceCategory)
                .containsExactly(
                        tuple("GET", "/admin/v1/me", "Read own profile", "Self"),
                        tuple("GET", "/admin/v1/me/menus", "Read own menu tree", "Self"));

        List<AuditLogEntity> audit = roleRows(role.id());
        assertThat(audit).extracting(AuditLogEntity::getAction, AuditLogEntity::getOutcome).containsExactly(
                tuple("ROLE_CREATED", AuditOutcome.SUCCESS),
                tuple("ROLE_SYNC_REQUESTED", AuditOutcome.SUCCESS));
        assertThat(auditLog.count()).isEqualTo(2);
        AuditLogEntity createdRow = audit.get(0);
        assertThat(createdRow.getBeforeState()).isNull();
        JsonNode after = createdRow.getAfterState();
        assertThat(after.path("name").asText()).isEqualTo("ANALYST");
        assertThat(after.path("syncStatus").asText()).isEqualTo("NEVER");
        assertThat(after.path("isSystem").asBoolean()).isFalse();
        assertThat(after.path("implicit")).hasSize(2);
        assertThat(after.path("implicit").get(0).path("uriPattern").asText()).isEqualTo("/admin/v1/me");
        assertThat(after.path("implicit").get(1).path("implicit").asBoolean()).isTrue();
        assertThat(audit.get(1).getAfterState().path("outcome").asText()).isEqualTo("SUCCESS");
    }

    @Test
    void createWithAFailedSyncReturnsTheRoleWithTheWarningAndWritesTwoRows() {
        when(gatewaySyncClient.sync()).thenReturn(new SyncResult(false, 503, "HTTP 503: stub in fail mode"));

        RoleService.RoleCreated created = roleService.create(new CreateRoleRequest("FAILING_SYNC", "Failing", null));

        assertThat(created.role().syncStatus()).isEqualTo(SyncStatus.FAILED);
        assertThat(created.warnings()).hasSize(1);
        assertThat(created.warnings().get(0).code()).isEqualTo("ROLE_SYNC_FAILED");
        assertThat(created.warnings().get(0).menuId()).isNull();
        assertThat(roleRepository.findByName("FAILING_SYNC")).isPresent();
        assertThat(roleRows(created.role().id())).extracting(AuditLogEntity::getAction, AuditLogEntity::getOutcome)
                .containsExactly(
                        tuple("ROLE_CREATED", AuditOutcome.SUCCESS),
                        tuple("ROLE_SYNC_REQUESTED", AuditOutcome.FAILED));
    }

    @Test
    void createCommitsTheRoleAndItsRowsBeforeTheSync() {
        AtomicReference<List<String>> seen = new AtomicReference<>();
        TransactionTemplate separate = new TransactionTemplate(transactionManager);
        separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        when(gatewaySyncClient.sync()).thenAnswer(invocation -> {
            seen.set(separate.execute(status -> {
                List<String> found = new ArrayList<>();
                roleRepository.findByName("COMMIT_FIRST").ifPresent(r -> found.add(r.getName()));
                apiPermissionRepository.findByPermissionNameOrderByUriPatternAscHttpMethodAsc("COMMIT_FIRST")
                        .forEach(r -> found.add(r.getUriPattern()));
                return found;
            }));
            return new SyncResult(true, 200, null);
        });

        roleService.create(new CreateRoleRequest("COMMIT_FIRST", "Commit first", null));

        assertThat(seen.get()).containsExactly("COMMIT_FIRST", "/admin/v1/me", "/admin/v1/me/menus");
    }

    @Test
    void createRefusesANameUsedInRoles() {
        expectCode(() -> roleService.create(new CreateRoleRequest("SUPER_ADMIN", "x", null)),
                ErrorCode.DUPLICATE_ROLE_NAME);
        assertNothingWritten();
    }

    @Test
    void createRefusesANameThatApiPermissionsAlreadyUses() {
        ApiPermissionEntity row = new ApiPermissionEntity();
        row.setPermissionName("INSIGHTS_READ");
        row.setHttpMethod("GET");
        row.setUriPattern("/v1/insights/**");
        apiPermissionRepository.save(row);

        expectCode(() -> roleService.create(new CreateRoleRequest("INSIGHTS_READ", "Insights", null)),
                ErrorCode.DUPLICATE_ROLE_NAME);
        assertNothingWritten();
        assertThat(roleRepository.findByName("INSIGHTS_READ")).isEmpty();
    }

    @Test
    void createRefusesANameThatIsAKeycloakRealmRole() {
        when(keycloakGateway.findRealmRole("LEFTOVER_ROLE")).thenReturn(Optional.of(new RoleRepresentation()));

        expectCode(() -> roleService.create(new CreateRoleRequest("LEFTOVER_ROLE", "Leftover", null)),
                ErrorCode.DUPLICATE_ROLE_NAME);
        assertNothingWritten();
    }

    @Test
    void aFailedRealmRoleCheckIs502BeforeAnythingChanged() {
        when(keycloakGateway.findRealmRole(anyString())).thenThrow(new KeycloakCallFailedException("down", 500, null));

        assertThatThrownBy(() -> roleService.create(new CreateRoleRequest("UNCHECKED", "Unchecked", null)))
                .isInstanceOfSatisfying(KeycloakSyncFailedException.class, e -> {
                    assertThat(e.partialState().operation()).isEqualTo("ROLE_CREATE");
                    assertThat(e.partialState().completedSteps()).isEmpty();
                    assertThat(e.partialState().auditLogId()).isNull();
                    assertThat(e.partialState().entityId()).isNotNull();
                });
        assertNothingWritten();
        assertThat(roleRepository.findByName("UNCHECKED")).isEmpty();
    }

    // ---------------------------------------------------------------------------- update (5.12)

    @Test
    void updateChangesTheDisplayFieldsAndWritesRoleUpdated() {
        RoleEntity role = storedRole("EDITABLE");

        Role updated = roleService.update(role.getId(), new UpdateRoleRequest("Edited", "New description"));

        assertThat(updated.displayName()).isEqualTo("Edited");
        assertThat(updated.description()).isEqualTo("New description");
        assertThat(updated.updatedAt()).isAfter(role.getUpdatedAt());
        List<AuditLogEntity> rows = roleRows(role.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAction()).isEqualTo("ROLE_UPDATED");
        assertThat(rows.get(0).getBeforeState().path("displayName").asText()).isEqualTo("EDITABLE");
        assertThat(rows.get(0).getAfterState().path("displayName").asText()).isEqualTo("Edited");
        assertThat(rows.get(0).getAfterState().path("description").asText()).isEqualTo("New description");
        verify(gatewaySyncClient, never()).sync();
    }

    @Test
    void anUnchangedUpdateIsANoOp() {
        RoleEntity role = roleRepository.findById(storedRole("UNCHANGED").getId()).orElseThrow();

        Role same = roleService.update(role.getId(), new UpdateRoleRequest("UNCHANGED", null));

        assertThat(same.updatedAt()).isEqualTo(role.getUpdatedAt());
        assertNothingWritten();
    }

    @Test
    void updateOfASystemRoleOrAMissingRoleIsRefused() {
        expectCode(() -> roleService.update(SUPER_ADMIN_ID, new UpdateRoleRequest("x", null)),
                ErrorCode.SYSTEM_ROLE_IMMUTABLE);
        expectCode(() -> roleService.update(UUID.randomUUID(), new UpdateRoleRequest("x", null)),
                ErrorCode.ROLE_NOT_FOUND);
        assertNothingWritten();
    }

    // ---------------------------------------------------------------------------- delete (5.9)

    @Test
    void deleteRemovesTheRoleItsMenusAndRowsAndWritesOneRowWithoutASync() {
        when(gatewaySyncClient.sync()).thenReturn(new SyncResult(true, 200, null));
        Role role = roleService.create(new CreateRoleRequest("DOOMED", "Doomed", null)).role();
        RoleMenuEntity grant = new RoleMenuEntity();
        grant.setRoleId(role.id());
        grant.setMenuId(DASHBOARD_ID);
        roleMenuRepository.save(grant);
        jdbcTemplate.execute("TRUNCATE audit_log");
        org.mockito.Mockito.clearInvocations(gatewaySyncClient);

        roleService.delete(role.id());

        assertThat(roleRepository.findById(role.id())).isEmpty();
        assertThat(roleMenuRepository.findByRoleId(role.id())).isEmpty();
        assertThat(apiPermissionRepository.existsByPermissionName("DOOMED")).isFalse();
        verify(gatewaySyncClient, never()).sync();

        List<AuditLogEntity> rows = roleRows(role.id());
        assertThat(rows).hasSize(1);
        assertThat(auditLog.count()).isEqualTo(1);
        AuditLogEntity row = rows.get(0);
        assertThat(row.getAction()).isEqualTo("ROLE_DELETED");
        assertThat(row.getAfterState()).isNull();
        assertThat(row.getBeforeState().path("name").asText()).isEqualTo("DOOMED");
        assertThat(row.getBeforeState().path("menuIds").get(0).asText()).isEqualTo(DASHBOARD_ID.toString());
        assertThat(row.getBeforeState().path("permissions")).hasSize(2);
    }

    @Test
    void deleteOfASystemRoleIsRefused() {
        expectCode(() -> roleService.delete(SUPER_ADMIN_ID), ErrorCode.SYSTEM_ROLE_IMMUTABLE);
        expectCode(() -> roleService.delete(UUID.randomUUID()), ErrorCode.ROLE_NOT_FOUND);
        assertNothingWritten();
    }

    @Test
    void deleteOfARoleALocalUserOfAnyStatusHoldsIsRefused() {
        RoleEntity role = storedRole("HELD_LOCALLY");
        holdRole(newUser(UserStatus.LOCKED), role);

        expectCode(() -> roleService.delete(role.getId()), ErrorCode.ROLE_IN_USE);
        assertThat(roleRepository.findById(role.getId())).isPresent();
        assertNothingWritten();
    }

    @Test
    void deleteOfARoleAKeycloakUserHoldsIsRefused() {
        RoleEntity role = storedRole("HELD_IN_KEYCLOAK");
        when(keycloakGateway.hasRealmRoleMembers("HELD_IN_KEYCLOAK")).thenReturn(true);

        expectCode(() -> roleService.delete(role.getId()), ErrorCode.ROLE_IN_USE);
        assertThat(roleRepository.findById(role.getId())).isPresent();
        assertNothingWritten();
    }

    @Test
    void aFailedMemberCheckIs502BeforeAnythingChanged() {
        RoleEntity role = storedRole("MEMBERS_UNKNOWN");
        when(keycloakGateway.hasRealmRoleMembers(anyString())).thenThrow(new KeycloakCallFailedException("down", null, null));

        assertThatThrownBy(() -> roleService.delete(role.getId()))
                .isInstanceOfSatisfying(KeycloakSyncFailedException.class, e -> {
                    assertThat(e.partialState().operation()).isEqualTo("ROLE_DELETE");
                    assertThat(e.partialState().entityId()).isEqualTo(role.getId());
                    assertThat(e.partialState().completedSteps()).isEmpty();
                    assertThat(e.partialState().auditLogId()).isNull();
                });
        assertThat(roleRepository.findById(role.getId())).isPresent();
        assertNothingWritten();
    }

    // -------------------------------------------------------------------------------- helpers

    private RoleEntity storedRole(String name) {
        RoleEntity role = new RoleEntity();
        role.setName(name);
        role.setDisplayName(name);
        role.setSystem(false);
        role.setAssignable(true);
        return roleRepository.save(role);
    }

    private UserEntity newUser(UserStatus status) {
        String name = "role-test-" + UUID.randomUUID().toString().substring(0, 8);
        UserEntity user = new UserEntity();
        user.setKeycloakId(UUID.randomUUID());
        user.setUsername(name);
        user.setEmail(name + "@cce.local");
        user.setFirstName("Role");
        user.setLastName("Test");
        user.setStatus(status);
        return userRepository.save(user);
    }

    private void holdRole(UserEntity user, RoleEntity role) {
        UserRoleEntity row = new UserRoleEntity();
        row.setUserId(user.getId());
        row.setRoleId(role.getId());
        userRoleRepository.save(row);
    }

    private List<AuditLogEntity> roleRows(UUID roleId) {
        return auditLog.findByEntityTypeAndEntityIdOrderByOccurredAtAsc(AuditEntityType.ROLE, roleId);
    }

    private void assertNothingWritten() {
        assertThat(auditLog.count()).isZero();
        verify(gatewaySyncClient, never()).sync();
    }

    private static void expectCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
