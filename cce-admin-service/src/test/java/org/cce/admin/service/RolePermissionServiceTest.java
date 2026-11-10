package org.cce.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.cce.admin.client.SyncResult;
import org.cce.admin.controller.dto.CommonDtos.FieldErrorDto;
import org.cce.admin.controller.dto.RoleDtos.CreateRoleRequest;
import org.cce.admin.controller.dto.RoleDtos.PermissionRow;
import org.cce.admin.controller.dto.RoleDtos.PermissionRowInput;
import org.cce.admin.controller.dto.RoleDtos.ReplacePermissionsRequest;
import org.cce.admin.controller.dto.RoleDtos.RolePermissions;
import org.cce.admin.controller.dto.RoleDtos.RoleSyncResult;
import org.cce.admin.entity.ApiPermissionEntity;
import org.cce.admin.entity.AuditEntityType;
import org.cce.admin.entity.AuditLogEntity;
import org.cce.admin.entity.AuditOutcome;
import org.cce.admin.entity.SyncStatus;
import org.cce.admin.entity.UserEntity;
import org.cce.admin.entity.UserStatus;
import org.cce.admin.repository.ApiPermissionRepository;
import org.cce.admin.repository.AuditLogRepository;
import org.cce.admin.repository.RoleRepository;
import org.cce.admin.repository.UserRepository;
import org.cce.admin.service.exception.ApiException;
import org.cce.admin.service.exception.ErrorCode;
import org.cce.admin.service.exception.KeycloakSyncFailedException;
import org.cce.admin.support.DatabaseTestBase;
import org.cce.admin.support.TestJwtFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Permissions get and replace (5.7) and manual sync (5.8), with the audit rows of section 8. */
@ExtendWith(OutputCaptureExtension.class)
class RolePermissionServiceTest extends DatabaseTestBase {

    private static final UUID SUPER_ADMIN_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");

    @Autowired
    private RolePermissionService rolePermissionService;

    @Autowired
    private RoleService roleService;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private ApiPermissionRepository apiPermissionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuditLogRepository auditLog;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MockMvc mockMvc;

    private UUID roleId;

    /** A synced console role with only its implicit rows, and an empty audit log. */
    @BeforeEach
    void createRole() {
        when(gatewaySyncClient.sync()).thenReturn(new SyncResult(true, 200, null));
        roleId = roleService.create(new CreateRoleRequest("PERM_ROLE", "Permissions", null)).role().id();
        jdbcTemplate.execute("TRUNCATE audit_log");
        org.mockito.Mockito.reset(gatewaySyncClient);
    }

    // -------------------------------------------------------------------------------------- get

    @Test
    void getListsImplicitRowsFirstThenExplicitRowsByPatternAndMethod() {
        save(List.of(row("post", "/admin/v1/users"), row("GET", "/admin/v1/users/*"), row("GET", "/admin/v1/users")));

        RolePermissions result = rolePermissionService.get(roleId);

        assertThat(result.roleId()).isEqualTo(roleId);
        assertThat(result.syncStatus()).isEqualTo(SyncStatus.SYNCED);
        assertThat(result.permissions()).extracting(PermissionRow::httpMethod, PermissionRow::uriPattern,
                PermissionRow::implicit).containsExactly(
                        tuple("GET", "/admin/v1/me", true),
                        tuple("GET", "/admin/v1/me/menus", true),
                        tuple("GET", "/admin/v1/users", false),
                        tuple("POST", "/admin/v1/users", false),
                        tuple("GET", "/admin/v1/users/*", false));
        assertThat(result.permissions()).allSatisfy(row -> assertThat(row.id()).isNotNull());
    }

    @Test
    void getOfASystemRoleHasNoImplicitRows() {
        RolePermissions result = rolePermissionService.get(SUPER_ADMIN_ID);
        assertThat(result.permissions()).hasSize(5).noneMatch(PermissionRow::implicit);
        assertThat(result.permissions()).extracting(PermissionRow::uriPattern).containsOnly("/admin/v1/**");
    }

    @Test
    void getOfAMissingRoleIs404() {
        expectCode(() -> rolePermissionService.get(UUID.randomUUID()), ErrorCode.ROLE_NOT_FOUND);
    }

    // -------------------------------------------------------------------------- replace (5.7)

    @Test
    void replaceSavesTheExplicitRowsKeepsTheImplicitOnesSyncsAndWritesTwoRows() {
        when(gatewaySyncClient.sync()).thenReturn(new SyncResult(true, 200, null));

        RolePermissions result = rolePermissionService.replace(roleId, request(List.of(
                new PermissionRowInput("get", "/admin/v1/users/**", "Read users", "Users"),
                new PermissionRowInput("GET", "/admin/v1/me", "dropped: equals an implicit row", null),
                new PermissionRowInput("Patch", "/admin/v1/users/*/status", null, null))));

        assertThat(result.syncStatus()).isEqualTo(SyncStatus.SYNCED);
        assertThat(result.lastSyncAt()).isNotNull();
        assertThat(result.permissions()).extracting(PermissionRow::httpMethod, PermissionRow::uriPattern,
                PermissionRow::description, PermissionRow::implicit).containsExactly(
                        tuple("GET", "/admin/v1/me", "Read own profile", true),
                        tuple("GET", "/admin/v1/me/menus", "Read own menu tree", true),
                        tuple("GET", "/admin/v1/users/**", "Read users", false),
                        tuple("PATCH", "/admin/v1/users/*/status", null, false));

        List<AuditLogEntity> rows = roleRows();
        assertThat(rows).extracting(AuditLogEntity::getAction, AuditLogEntity::getOutcome).containsExactly(
                tuple("ROLE_PERMISSIONS_UPDATED", AuditOutcome.SUCCESS),
                tuple("ROLE_SYNC_REQUESTED", AuditOutcome.SUCCESS));
        assertThat(auditLog.count()).isEqualTo(2);
        JsonNode before = rows.get(0).getBeforeState();
        JsonNode after = rows.get(0).getAfterState();
        assertThat(before.path("explicit")).isEmpty();
        assertThat(before.path("implicit")).hasSize(2);
        assertThat(after.path("explicit")).hasSize(2);
        assertThat(after.path("explicit").get(0).path("uriPattern").asText()).isEqualTo("/admin/v1/users/**");
        assertThat(after.path("implicit")).hasSize(2);
        assertThat(after.path("implicit").get(0).path("implicit").asBoolean()).isTrue();
    }

    @Test
    void aReplaceWhoseSyncFailsKeepsTheRowsAndIs502WithTheSyncRow() {
        when(gatewaySyncClient.sync()).thenReturn(new SyncResult(false, 503, "HTTP 503: stub in fail mode"));

        assertThatThrownBy(() -> rolePermissionService.replace(roleId,
                request(List.of(row("GET", "/admin/v1/roles")))))
                .isInstanceOfSatisfying(KeycloakSyncFailedException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.KEYCLOAK_SYNC_FAILED);
                    assertThat(e.partialState().operation()).isEqualTo("ROLE_PERMISSIONS_SAVE");
                    assertThat(e.partialState().entityId()).isEqualTo(roleId);
                    assertThat(e.partialState().failedStep()).isEqualTo("GATEWAY_SYNC");
                    assertThat(e.partialState().completedSteps()).containsExactly("PERMISSIONS_SAVED");
                    assertThat(e.partialState().keycloakUserId()).isNull();
                    List<AuditLogEntity> rows = roleRows();
                    assertThat(rows).extracting(AuditLogEntity::getAction, AuditLogEntity::getOutcome)
                            .containsExactly(
                                    tuple("ROLE_PERMISSIONS_UPDATED", AuditOutcome.SUCCESS),
                                    tuple("ROLE_SYNC_REQUESTED", AuditOutcome.FAILED));
                    assertThat(e.partialState().auditLogId()).isEqualTo(rows.get(1).getId());
                });

        RolePermissions kept = rolePermissionService.get(roleId);
        assertThat(kept.syncStatus()).isEqualTo(SyncStatus.FAILED);
        assertThat(kept.permissions()).extracting(PermissionRow::uriPattern)
                .containsExactly("/admin/v1/me", "/admin/v1/me/menus", "/admin/v1/roles");
    }

    @Test
    void replaceCommitsTheRowsBeforeTheSync() {
        AtomicReference<List<String>> seen = new AtomicReference<>();
        TransactionTemplate separate = new TransactionTemplate(transactionManager);
        separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        when(gatewaySyncClient.sync()).thenAnswer(invocation -> {
            seen.set(separate.execute(status -> {
                List<String> found = new ArrayList<>();
                apiPermissionRepository.findByPermissionNameOrderByUriPatternAscHttpMethodAsc("PERM_ROLE")
                        .forEach(r -> found.add(r.getHttpMethod() + " " + r.getUriPattern()));
                return found;
            }));
            return new SyncResult(true, 200, null);
        });

        rolePermissionService.replace(roleId, request(List.of(row("DELETE", "/admin/v1/menus/*"))));

        assertThat(seen.get()).containsExactly("GET /admin/v1/me", "GET /admin/v1/me/menus", "DELETE /admin/v1/menus/*");
    }

    @Test
    void anUnchangedSetIsANoOpWithNoSync() {
        when(gatewaySyncClient.sync()).thenReturn(new SyncResult(true, 200, null));
        save(List.of(new PermissionRowInput("GET", "/admin/v1/users", "List", "Users")));
        jdbcTemplate.execute("TRUNCATE audit_log");
        org.mockito.Mockito.clearInvocations(gatewaySyncClient);

        RolePermissions result = rolePermissionService.replace(roleId, request(List.of(
                new PermissionRowInput("get", "/admin/v1/users", "List", "Users"),
                row("GET", "/admin/v1/me/menus"))));

        assertThat(result.permissions()).hasSize(3);
        assertThat(auditLog.count()).isZero();
        verify(gatewaySyncClient, never()).sync();
    }

    @Test
    void aChangedDescriptionIsAChange() {
        when(gatewaySyncClient.sync()).thenReturn(new SyncResult(true, 200, null));
        save(List.of(new PermissionRowInput("GET", "/admin/v1/users", "List", "Users")));
        jdbcTemplate.execute("TRUNCATE audit_log");

        rolePermissionService.replace(roleId, request(List.of(
                new PermissionRowInput("GET", "/admin/v1/users", "List all users", "Users"))));

        assertThat(roleRows()).extracting(AuditLogEntity::getAction)
                .containsExactly("ROLE_PERMISSIONS_UPDATED", "ROLE_SYNC_REQUESTED");
    }

    @Test
    void anEmptySetRemovesTheExplicitRowsAndKeepsTheImplicitOnes() {
        when(gatewaySyncClient.sync()).thenReturn(new SyncResult(true, 200, null));
        save(List.of(row("GET", "/admin/v1/users")));

        RolePermissions result = rolePermissionService.replace(roleId, request(List.of()));

        assertThat(result.permissions()).extracting(PermissionRow::uriPattern)
                .containsExactly("/admin/v1/me", "/admin/v1/me/menus");
    }

    @Test
    void aMissingImplicitRowIsInsertedAgain() {
        when(gatewaySyncClient.sync()).thenReturn(new SyncResult(true, 200, null));
        jdbcTemplate.update("DELETE FROM api_permissions WHERE permission_name = 'PERM_ROLE' AND uri_pattern = ?",
                "/admin/v1/me/menus");

        RolePermissions result = rolePermissionService.replace(roleId, request(List.of(row("GET", "/admin/v1/roles"))));

        assertThat(result.permissions()).extracting(PermissionRow::uriPattern)
                .containsExactly("/admin/v1/me", "/admin/v1/me/menus", "/admin/v1/roles");
        JsonNode before = roleRows().get(0).getBeforeState();
        assertThat(before.path("implicit")).hasSize(1);
    }

    @Test
    void methodsOutsideTheFixedListAreRefused() {
        assertDetails(List.of(row("HEAD", "/admin/v1/users"), row("GET", "/admin/v1/users"), row("*", "/x")),
                "permissions[0].httpMethod", "permissions[2].httpMethod");
    }

    @Test
    void uriPatternRulesAreChecked() {
        assertDetails(List.of(
                        row("GET", "admin/v1/users"),
                        row("GET", "/admin//users"),
                        row("GET", "/admin/v1/users?x=1"),
                        row("GET", "/admin/**/users"),
                        row("GET", "/admin/v1/users**"),
                        row("GET", "/admin/v1/***"),
                        row("GET", "/v1/insights/**"),
                        row("GET", "/admin/v1/users/*/roles"),
                        row("GET", "/a-b.c_d~e")),
                "permissions[0].uriPattern", "permissions[1].uriPattern", "permissions[2].uriPattern",
                "permissions[3].uriPattern", "permissions[4].uriPattern", "permissions[5].uriPattern");
    }

    @Test
    void aDuplicateRowIsRefusedOnTheLaterOne() {
        assertDetails(List.of(row("GET", "/admin/v1/users"), row("POST", "/admin/v1/users"),
                        row("get", "/admin/v1/users")),
                "permissions[2].uriPattern");
    }

    @Test
    void replaceOfASystemRoleOrAMissingRoleIsRefused() {
        expectCode(() -> rolePermissionService.replace(SUPER_ADMIN_ID, request(List.of())),
                ErrorCode.SYSTEM_ROLE_IMMUTABLE);
        expectCode(() -> rolePermissionService.replace(UUID.randomUUID(), request(List.of())),
                ErrorCode.ROLE_NOT_FOUND);
        assertThat(auditLog.count()).isZero();
        verify(gatewaySyncClient, never()).sync();
    }

    @Test
    void aFailedPermissionSaveLogsTheStructuredLine(CapturedOutput output) throws Exception {
        when(gatewaySyncClient.sync()).thenReturn(new SyncResult(false, 503, "HTTP 503: stub in fail mode"));
        String token = adminToken();

        mockMvc.perform(put("/admin/v1/roles/" + roleId + "/permissions")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[{\"httpMethod\":\"GET\",\"uriPattern\":\"/admin/v1/roles\"}]}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("KEYCLOAK_SYNC_FAILED"))
                .andExpect(jsonPath("$.error.partialState.operation").value("ROLE_PERMISSIONS_SAVE"))
                .andExpect(jsonPath("$.error.partialState.completedSteps[0]").value("PERMISSIONS_SAVED"))
                .andExpect(jsonPath("$.error.partialState.keycloakUserId").isEmpty());

        UUID auditLogId = roleRows().get(1).getId();
        assertThat(output.getOut()).contains("event=keycloak_sync_failed operation=ROLE_PERMISSIONS_SAVE entityId="
                + roleId + " failedStep=GATEWAY_SYNC completedSteps=PERMISSIONS_SAVED keycloakUserId=none auditLogId="
                + auditLogId);
    }

    // ------------------------------------------------------------------------------ sync (5.8)

    @Test
    void aManualSyncWritesOneRowAndReturnsTheResult() {
        when(gatewaySyncClient.sync()).thenReturn(new SyncResult(true, 200, null));

        RoleSyncResult result = rolePermissionService.sync(roleId);

        assertThat(result.roleId()).isEqualTo(roleId);
        assertThat(result.syncStatus()).isEqualTo(SyncStatus.SYNCED);
        assertThat(result.lastSyncAt()).isEqualTo(roleRepository.findById(roleId).orElseThrow().getLastSyncAt());
        assertThat(roleRows()).extracting(AuditLogEntity::getAction, AuditLogEntity::getOutcome)
                .containsExactly(tuple("ROLE_SYNC_REQUESTED", AuditOutcome.SUCCESS));
        assertThat(auditLog.count()).isEqualTo(1);
    }

    @Test
    void aFailedManualSyncIs502WithNoCompletedSteps() {
        when(gatewaySyncClient.sync()).thenReturn(new SyncResult(false, null, "java.net.ConnectException: refused"));

        assertThatThrownBy(() -> rolePermissionService.sync(roleId))
                .isInstanceOfSatisfying(KeycloakSyncFailedException.class, e -> {
                    assertThat(e.partialState().operation()).isEqualTo("ROLE_SYNC");
                    assertThat(e.partialState().entityId()).isEqualTo(roleId);
                    assertThat(e.partialState().failedStep()).isEqualTo("GATEWAY_SYNC");
                    assertThat(e.partialState().completedSteps()).isEmpty();
                    assertThat(e.partialState().auditLogId()).isEqualTo(roleRows().get(0).getId());
                });
        assertThat(roleRows()).extracting(AuditLogEntity::getAction, AuditLogEntity::getOutcome)
                .containsExactly(tuple("ROLE_SYNC_REQUESTED", AuditOutcome.FAILED));
        assertThat(roleRepository.findById(roleId).orElseThrow().getSyncStatus()).isEqualTo(SyncStatus.FAILED);
    }

    @Test
    void aSystemRoleMayBeSyncedAndAMissingRoleIs404() {
        when(gatewaySyncClient.sync()).thenReturn(new SyncResult(true, 200, null));
        assertThat(rolePermissionService.sync(SUPER_ADMIN_ID).syncStatus()).isEqualTo(SyncStatus.SYNCED);
        expectCode(() -> rolePermissionService.sync(UUID.randomUUID()), ErrorCode.ROLE_NOT_FOUND);
    }

    // -------------------------------------------------------------------------------- helpers

    private void save(List<PermissionRowInput> rows) {
        when(gatewaySyncClient.sync()).thenReturn(new SyncResult(true, 200, null));
        rolePermissionService.replace(roleId, request(rows));
    }

    private static PermissionRowInput row(String method, String pattern) {
        return new PermissionRowInput(method, pattern, null, null);
    }

    private static ReplacePermissionsRequest request(List<PermissionRowInput> rows) {
        return new ReplacePermissionsRequest(rows);
    }

    private void assertDetails(List<PermissionRowInput> rows, String... fields) {
        List<ApiPermissionEntity> before = apiPermissionRepository.findByPermissionNameOrderByUriPatternAscHttpMethodAsc("PERM_ROLE");
        assertThatThrownBy(() -> rolePermissionService.replace(roleId, request(rows)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(e.details()).extracting(FieldErrorDto::field).containsExactly(fields);
                });
        assertThat(apiPermissionRepository.findByPermissionNameOrderByUriPatternAscHttpMethodAsc("PERM_ROLE"))
                .hasSameSizeAs(before);
        assertThat(auditLog.count()).isZero();
        verify(gatewaySyncClient, never()).sync();
    }

    private List<AuditLogEntity> roleRows() {
        return auditLog.findByEntityTypeAndEntityIdOrderByOccurredAtAsc(AuditEntityType.ROLE, roleId);
    }

    private String adminToken() {
        UserEntity admin = new UserEntity();
        admin.setKeycloakId(UUID.randomUUID());
        admin.setUsername("perm-admin");
        admin.setEmail("perm-admin@cce.local");
        admin.setFirstName("Perm");
        admin.setLastName("Admin");
        admin.setStatus(UserStatus.ACTIVE);
        UserEntity saved = userRepository.save(admin);
        return TestJwtFactory.token().subject(saved.getKeycloakId()).roles("SUPER_ADMIN").bearer();
    }

    private static void expectCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
