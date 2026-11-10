package org.cce.admin.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.cce.admin.entity.ApiPermissionEntity;
import org.cce.admin.entity.AuditEntityType;
import org.cce.admin.entity.AuditLogEntity;
import org.cce.admin.entity.AuditOutcome;
import org.cce.admin.entity.MenuEntity;
import org.cce.admin.entity.MenuType;
import org.cce.admin.entity.RoleEntity;
import org.cce.admin.entity.RoleMenuEntity;
import org.cce.admin.entity.SyncStatus;
import org.cce.admin.entity.UserEntity;
import org.cce.admin.entity.UserRoleEntity;
import org.cce.admin.entity.UserStatus;
import org.cce.admin.support.DatabaseTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.support.TransactionTemplate;

/** Every repository method of the Phase 2 table, against the migrated test database. */
class RepositoryQueriesTest extends DatabaseTestBase {

    private static final UUID SUPER_ADMIN_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final List<String> IMPLICIT_URIS = List.of("/admin/v1/me", "/admin/v1/me/menus");

    @Autowired
    private UserRepository users;

    @Autowired
    private RoleRepository roles;

    @Autowired
    private MenuRepository menus;

    @Autowired
    private RoleMenuRepository roleMenus;

    @Autowired
    private UserRoleRepository userRoles;

    @Autowired
    private ApiPermissionRepository permissions;

    @Autowired
    private AuditLogRepository auditLog;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------------------------------------------------------------------------------- entities

    @Test
    void prePersistAssignsIdAndTimestampsAndPreUpdateMovesUpdatedAt() {
        UserEntity saved = users.save(user("alice", UserStatus.ACTIVE));
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull().isEqualTo(saved.getUpdatedAt());

        UUID fixed = UUID.randomUUID();
        UserEntity withId = user("bob", UserStatus.ACTIVE);
        withId.setId(fixed);
        assertThat(users.save(withId).getId()).isEqualTo(fixed);

        Instant updatedBefore = updatedAt("users", saved.getId());
        saved.setFirstName("Changed");
        users.save(saved);
        assertThat(updatedAt("users", saved.getId())).isAfter(updatedBefore);
    }

    @Test
    void roleInsertWithoutIsAssignableFails() {
        RoleEntity role = new RoleEntity();
        role.setName("NO_ASSIGNABLE");
        role.setDisplayName("x");
        assertThatThrownBy(() -> roles.save(role)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void apiPermissionTimestampsComeFromTheDatabase() {
        ApiPermissionEntity saved = permissions.save(permission("ROLE_A", "GET", "/a"));
        assertThat(saved.getId()).isNotNull();
        ApiPermissionEntity reloaded = permissions.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void auditLogJsonColumnsRoundTrip() throws Exception {
        AuditLogEntity saved = auditLog.save(audit(UUID.randomUUID(), "ROLE_CREATED"));
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getOccurredAt()).isNotNull();
        AuditLogEntity reloaded = auditLog.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getAfterState()).isEqualTo(objectMapper.readTree("{\"name\":\"X\",\"linked\":true}"));
        assertThat(reloaded.getBeforeState()).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT after_state ->> 'name' FROM audit_log WHERE id = ?",
                String.class, saved.getId())).isEqualTo("X");
    }

    // ------------------------------------------------------------------------------ UserRepository

    @Test
    void findByKeycloakId() {
        UserEntity saved = users.save(user("alice", UserStatus.ACTIVE));
        assertThat(users.findByKeycloakId(saved.getKeycloakId())).map(UserEntity::getId).contains(saved.getId());
        assertThat(users.findByKeycloakId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void lockByIdHoldsARowLockUntilTheTransactionEnds() {
        UserEntity saved = users.save(user("alice", UserStatus.ACTIVE));
        tx.executeWithoutResult(status -> {
            assertThat(users.lockById(saved.getId())).map(UserEntity::getId).contains(saved.getId());
            // A second connection cannot take the same row lock while this transaction is open.
            assertThatThrownBy(() -> tryLockOnOtherConnection(saved.getId()))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("could not obtain lock");
        });
        assertThat(inTransaction(() -> users.lockById(UUID.randomUUID()))).isEmpty();
    }

    @Test
    void existsByUsernameAndEmail() {
        UserEntity alice = users.save(user("alice", UserStatus.ACTIVE));
        assertThat(users.existsByUsername("alice")).isTrue();
        assertThat(users.existsByUsername("bob")).isFalse();
        assertThat(users.existsByEmail("alice@cce.local")).isTrue();
        assertThat(users.existsByEmail("bob@cce.local")).isFalse();
        assertThat(users.existsByEmailAndIdNot("alice@cce.local", alice.getId())).isFalse();
        assertThat(users.existsByEmailAndIdNot("alice@cce.local", UUID.randomUUID())).isTrue();
    }

    @Test
    void emailIsUniqueIgnoringCase() {
        users.save(user("alice", UserStatus.ACTIVE));
        UserEntity other = user("alice2", UserStatus.ACTIVE);
        other.setEmail("ALICE@cce.local");
        assertThatThrownBy(() -> users.save(other)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void countUsersWithRoleAndExcluding() {
        UserEntity a = users.save(user("admin-a", UserStatus.ACTIVE));
        UserEntity b = users.save(user("admin-b", UserStatus.ACTIVE));
        UserEntity c = users.save(user("admin-c", UserStatus.INACTIVE));
        UserEntity d = users.save(user("other-d", UserStatus.ACTIVE));
        RoleEntity other = roles.save(role("OTHER_ROLE"));
        userRoles.save(userRole(a.getId(), SUPER_ADMIN_ID));
        userRoles.save(userRole(b.getId(), SUPER_ADMIN_ID));
        userRoles.save(userRole(c.getId(), SUPER_ADMIN_ID));
        userRoles.save(userRole(d.getId(), other.getId()));

        assertThat(users.countUsersWithRole("SUPER_ADMIN", UserStatus.ACTIVE)).isEqualTo(2);
        assertThat(users.countUsersWithRole("SUPER_ADMIN", UserStatus.INACTIVE)).isEqualTo(1);
        assertThat(users.countUsersWithRole("OTHER_ROLE", UserStatus.ACTIVE)).isEqualTo(1);
        assertThat(users.countUsersWithRoleExcluding("SUPER_ADMIN", UserStatus.ACTIVE, a.getId())).isEqualTo(1);
        assertThat(users.countUsersWithRoleExcluding("SUPER_ADMIN", UserStatus.ACTIVE, d.getId())).isEqualTo(2);
    }

    @Test
    void updateLastLoginIfLaterOnlyMovesForwardAndLeavesUpdatedAtAlone() {
        UserEntity saved = users.save(user("alice", UserStatus.ACTIVE));
        Instant updatedBefore = updatedAt("users", saved.getId());
        Instant t1 = Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(600);

        // From null, it is set. Called outside any transaction: the method carries its own.
        assertThat(users.updateLastLoginIfLater(saved.getId(), t1)).isEqualTo(1);
        assertThat(lastLoginAt(saved.getId())).isEqualTo(t1);

        // An earlier or equal time does not move it back.
        assertThat(users.updateLastLoginIfLater(saved.getId(), t1.minusSeconds(60))).isZero();
        assertThat(users.updateLastLoginIfLater(saved.getId(), t1)).isZero();
        assertThat(lastLoginAt(saved.getId())).isEqualTo(t1);

        // A later time moves it forward.
        Instant t2 = t1.plusSeconds(120);
        assertThat(users.updateLastLoginIfLater(saved.getId(), t2)).isEqualTo(1);
        assertThat(lastLoginAt(saved.getId())).isEqualTo(t2);

        assertThat(updatedAt("users", saved.getId())).isEqualTo(updatedBefore);
    }

    @Test
    void touchUpdatedAt() {
        UserEntity saved = users.save(user("alice", UserStatus.ACTIVE));
        Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(3600);
        assertThat(rowsChanged(() -> users.touchUpdatedAt(saved.getId(), at))).isEqualTo(1);
        assertThat(updatedAt("users", saved.getId())).isEqualTo(at);
    }

    // ------------------------------------------------------------------------------ RoleRepository

    @Test
    void roleLookupsByName() {
        RoleEntity saved = roles.save(role("VIEWER"));
        assertThat(roles.findByName("VIEWER")).map(RoleEntity::getId).contains(saved.getId());
        assertThat(roles.findByName("MISSING")).isEmpty();
        assertThat(roles.existsByName("SUPER_ADMIN")).isTrue();
        assertThat(roles.existsByName("MISSING")).isFalse();
        assertThat(roles.findByNameIn(List.of("VIEWER", "SUPER_ADMIN", "offline_access")))
                .extracting(RoleEntity::getName).containsExactlyInAnyOrder("VIEWER", "SUPER_ADMIN");
    }

    @Test
    void findAllPagedByNameThenId() {
        roles.save(role("BETA"));
        roles.save(role("ALPHA"));
        Page<RoleEntity> first = roles.findAll(PageRequest.of(0, 3, Sort.by("name", "id")));
        assertThat(first.getTotalElements()).isEqualTo(4);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.getContent()).extracting(RoleEntity::getName)
                .containsExactly("ALPHA", "BETA", "CCE_ADMIN_SERVICE");
        assertThat(roles.findAll(PageRequest.of(1, 3, Sort.by("name", "id"))).getContent())
                .extracting(RoleEntity::getName).containsExactly("SUPER_ADMIN");
    }

    @Test
    void lockByName() {
        tx.executeWithoutResult(status -> {
            assertThat(roles.lockByName("SUPER_ADMIN")).map(RoleEntity::getId).contains(SUPER_ADMIN_ID);
            assertThatThrownBy(() -> tryLockRoleOnOtherConnection(SUPER_ADMIN_ID))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("could not obtain lock");
        });
        assertThat(inTransaction(() -> roles.lockByName("MISSING"))).isEmpty();
    }

    @Test
    void updateSyncResultSetsOnlyThatRoleAndLeavesUpdatedAtAlone() {
        RoleEntity target = roles.save(role("TARGET"));
        RoleEntity other = roles.save(role("UNTOUCHED"));
        Instant updatedBefore = updatedAt("roles", target.getId());
        Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);

        assertThat(rowsChanged(() -> roles.updateSyncResult(target.getId(), SyncStatus.FAILED, at))).isEqualTo(1);

        RoleEntity reloaded = roles.findById(target.getId()).orElseThrow();
        assertThat(reloaded.getSyncStatus()).isEqualTo(SyncStatus.FAILED);
        assertThat(reloaded.getLastSyncAt()).isEqualTo(at);
        assertThat(updatedAt("roles", target.getId())).isEqualTo(updatedBefore);
        RoleEntity untouched = roles.findById(other.getId()).orElseThrow();
        assertThat(untouched.getSyncStatus()).isEqualTo(SyncStatus.NEVER);
        assertThat(untouched.getLastSyncAt()).isNull();
    }

    // ------------------------------------------------------------------------------ MenuRepository

    @Test
    void findAllSortedBySortOrderThenLabel() {
        MenuEntity extra = menus.save(navMenu("aaa-extra", "Aardvark", 10));
        List<MenuEntity> sorted = menus.findAll(Sort.by("sortOrder", "label"));
        assertThat(sorted).hasSize(13);
        // sort_order 10: Aardvark (new), Dashboard, Patient detail, Protocol analytics; then 20, ...
        assertThat(sorted.subList(0, 5)).extracting(MenuEntity::getLabel)
                .containsExactly("Aardvark", "Dashboard", "Patient detail", "Protocol analytics", "Facilities");
        assertThat(sorted.get(sorted.size() - 1).getKey()).isEqualTo("exports");
        assertThat(extra.isActive()).isTrue();
    }

    @Test
    void menuExistsByKeyAndByParentId() {
        assertThat(menus.existsByKey("dashboard")).isTrue();
        assertThat(menus.existsByKey("missing")).isFalse();
        assertThat(menus.existsByParentId(UUID.fromString("10000000-0000-4000-8000-000000000003"))).isTrue();
        assertThat(menus.existsByParentId(UUID.fromString("10000000-0000-4000-8000-000000000001"))).isFalse();
    }

    @Test
    void findActiveGrantedToRoleNamesIsADistinctUnionOfActiveMenus() {
        RoleEntity r1 = roles.save(role("ROLE_ONE"));
        RoleEntity r2 = roles.save(role("ROLE_TWO"));
        RoleEntity r3 = roles.save(role("ROLE_THREE"));
        MenuEntity inactive = navMenu("inactive-menu", "Inactive", 500);
        inactive.setActive(false);
        inactive = menus.save(inactive);
        UUID dashboard = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID facilities = UUID.fromString("10000000-0000-4000-8000-000000000002");
        UUID events = UUID.fromString("10000000-0000-4000-8000-000000000006");
        roleMenus.save(roleMenu(r1.getId(), dashboard));
        roleMenus.save(roleMenu(r1.getId(), facilities));
        roleMenus.save(roleMenu(r2.getId(), facilities));
        roleMenus.save(roleMenu(r2.getId(), inactive.getId()));
        roleMenus.save(roleMenu(r3.getId(), events));

        assertThat(menus.findActiveGrantedToRoleNames(List.of("ROLE_ONE", "ROLE_TWO", "offline_access")))
                .extracting(MenuEntity::getId).containsExactlyInAnyOrder(dashboard, facilities);
        assertThat(menus.findActiveGrantedToRoleNames(List.of("NOBODY"))).isEmpty();
    }

    // -------------------------------------------------------------------------- RoleMenuRepository

    @Test
    void roleMenuQueriesAndDeletes() {
        RoleEntity r1 = roles.save(role("ROLE_ONE"));
        RoleEntity r2 = roles.save(role("ROLE_TWO"));
        UUID dashboard = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID facilities = UUID.fromString("10000000-0000-4000-8000-000000000002");
        roleMenus.save(roleMenu(r1.getId(), dashboard));
        roleMenus.save(roleMenu(r1.getId(), facilities));
        roleMenus.save(roleMenu(r2.getId(), dashboard));

        assertThat(roleMenus.findByRoleId(r1.getId())).extracting(RoleMenuEntity::getMenuId)
                .containsExactlyInAnyOrder(dashboard, facilities);
        assertThat(roleMenus.findRoleIdsByMenuId(dashboard)).containsExactlyInAnyOrder(r1.getId(), r2.getId());

        assertThat(rowsChanged(() -> roleMenus.deleteByRoleId(r1.getId()))).isEqualTo(2);
        assertThat(roleMenus.findByRoleId(r1.getId())).isEmpty();
        assertThat(roleMenus.findByRoleId(r2.getId())).hasSize(1);

        assertThat(rowsChanged(() -> roleMenus.deleteByMenuId(dashboard))).isEqualTo(1);
        assertThat(roleMenus.findByRoleId(r2.getId())).isEmpty();
    }

    // -------------------------------------------------------------------------- UserRoleRepository

    @Test
    void userRoleQueriesAndDelete() {
        UserEntity alice = users.save(user("alice", UserStatus.ACTIVE));
        UserEntity bob = users.save(user("bob", UserStatus.LOCKED));
        RoleEntity r1 = roles.save(role("ROLE_ONE"));
        RoleEntity r2 = roles.save(role("ROLE_TWO"));
        RoleEntity unused = roles.save(role("ROLE_UNUSED"));
        userRoles.save(userRole(alice.getId(), r1.getId()));
        userRoles.save(userRole(alice.getId(), r2.getId()));
        userRoles.save(userRole(bob.getId(), r1.getId()));

        assertThat(userRoles.findByUserId(alice.getId())).extracting(UserRoleEntity::getRoleId)
                .containsExactlyInAnyOrder(r1.getId(), r2.getId());
        assertThat(userRoles.existsByRoleId(r1.getId())).isTrue();
        assertThat(userRoles.existsByRoleId(unused.getId())).isFalse();

        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : userRoles.countByRoleIds(List.of(r1.getId(), r2.getId(), unused.getId()))) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        assertThat(counts).containsOnly(Map.entry(r1.getId(), 2L), Map.entry(r2.getId(), 1L));

        assertThat(rowsChanged(() -> userRoles.deleteByUserIdAndRoleIdIn(alice.getId(), List.of(r2.getId(),
                unused.getId())))).isEqualTo(1);
        assertThat(userRoles.findByUserId(alice.getId())).extracting(UserRoleEntity::getRoleId)
                .containsExactly(r1.getId());
        assertThat(userRoles.findByUserId(bob.getId())).hasSize(1);
    }

    // --------------------------------------------------------------------- ApiPermissionRepository

    @Test
    void findByPermissionNameInAndHttpMethod() {
        permissions.save(permission("ROLE_A", "GET", "/admin/v1/users"));
        permissions.save(permission("ROLE_A", "POST", "/admin/v1/users"));
        permissions.save(permission("ROLE_B", "GET", "/admin/v1/roles"));

        assertThat(permissions.findByPermissionNameInAndHttpMethod(List.of("ROLE_A", "SUPER_ADMIN"), "GET"))
                .extracting(p -> p.getPermissionName() + " " + p.getUriPattern())
                .containsExactlyInAnyOrder("ROLE_A /admin/v1/users", "SUPER_ADMIN /admin/v1/**");
        assertThat(permissions.findByPermissionNameInAndHttpMethod(List.of("ROLE_B"), "DELETE")).isEmpty();
    }

    @Test
    void aRolesRowsOrderedByPatternThenMethod() {
        permissions.save(permission("ROLE_A", "POST", "/b"));
        permissions.save(permission("ROLE_A", "GET", "/b"));
        permissions.save(permission("ROLE_A", "PUT", "/a"));
        permissions.save(permission("ROLE_B", "GET", "/a"));

        assertThat(permissions.findByPermissionNameOrderByUriPatternAscHttpMethodAsc("ROLE_A"))
                .extracting(p -> p.getHttpMethod() + " " + p.getUriPattern())
                .containsExactly("PUT /a", "GET /b", "POST /b");
        assertThat(permissions.existsByPermissionName("ROLE_A")).isTrue();
        assertThat(permissions.existsByPermissionName("ROLE_C")).isFalse();
    }

    @Test
    void deleteExplicitRowsKeepsTheImplicitRows() {
        permissions.save(permission("ROLE_A", "GET", "/admin/v1/me"));
        permissions.save(permission("ROLE_A", "GET", "/admin/v1/me/menus"));
        permissions.save(permission("ROLE_A", "GET", "/admin/v1/users"));
        permissions.save(permission("ROLE_A", "POST", "/admin/v1/me")); // same URI, other method: explicit
        permissions.save(permission("ROLE_B", "GET", "/admin/v1/users"));

        assertThat(rowsChanged(() -> permissions.deleteExplicitRows("ROLE_A", IMPLICIT_URIS))).isEqualTo(2);

        assertThat(permissions.findByPermissionNameOrderByUriPatternAscHttpMethodAsc("ROLE_A"))
                .extracting(p -> p.getHttpMethod() + " " + p.getUriPattern())
                .containsExactly("GET /admin/v1/me", "GET /admin/v1/me/menus");
        assertThat(permissions.existsByPermissionName("ROLE_B")).isTrue();
    }

    @Test
    void deleteByPermissionName() {
        permissions.save(permission("ROLE_A", "GET", "/admin/v1/me"));
        permissions.save(permission("ROLE_A", "GET", "/admin/v1/users"));
        permissions.save(permission("ROLE_B", "GET", "/admin/v1/users"));

        assertThat(rowsChanged(() -> permissions.deleteByPermissionName("ROLE_A"))).isEqualTo(2);
        assertThat(permissions.existsByPermissionName("ROLE_A")).isFalse();
        assertThat(permissions.existsByPermissionName("ROLE_B")).isTrue();
    }

    // ------------------------------------------------------------------------- AuditLogRepository

    @Test
    void auditRowsOfOneEntityInTimeOrder() {
        UUID entity = UUID.randomUUID();
        Instant base = Instant.now().truncatedTo(ChronoUnit.MICROS);
        AuditLogEntity second = audit(entity, "ROLE_SYNC_REQUESTED");
        second.setOccurredAt(base.plusSeconds(1));
        AuditLogEntity first = audit(entity, "ROLE_CREATED");
        first.setOccurredAt(base);
        auditLog.save(second);
        auditLog.save(first);
        auditLog.save(audit(UUID.randomUUID(), "ROLE_CREATED"));

        assertThat(auditLog.findByEntityTypeAndEntityIdOrderByOccurredAtAsc(AuditEntityType.ROLE, entity))
                .extracting(AuditLogEntity::getAction).containsExactly("ROLE_CREATED", "ROLE_SYNC_REQUESTED");
        assertThat(auditLog.findByEntityTypeAndEntityIdOrderByOccurredAtAsc(AuditEntityType.USER, entity)).isEmpty();
    }

    @Test
    void auditLogIsAppendOnly() {
        AuditLogEntity saved = auditLog.save(audit(UUID.randomUUID(), "ROLE_CREATED"));

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE audit_log SET outcome = 'FAILED' WHERE id = ?",
                saved.getId())).isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM audit_log WHERE id = ?", saved.getId()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThat(auditLog.findById(saved.getId())).map(AuditLogEntity::getOutcome).contains(AuditOutcome.SUCCESS);
    }

    // ---------------------------------------------------------------------------------- helpers

    private <T> Optional<T> inTransaction(Supplier<Optional<T>> work) {
        return tx.execute(status -> work.get());
    }

    private int rowsChanged(IntSupplier work) {
        Integer changed = tx.execute(status -> work.getAsInt());
        return changed == null ? 0 : changed;
    }

    private void tryLockOnOtherConnection(UUID userId) throws SQLException {
        tryLock("SELECT id FROM users WHERE id = ? FOR UPDATE NOWAIT", userId);
    }

    private void tryLockRoleOnOtherConnection(UUID roleId) throws SQLException {
        tryLock("SELECT id FROM roles WHERE id = ? FOR UPDATE NOWAIT", roleId);
    }

    /** Runs on a new pooled connection, outside the transaction bound to the test thread. */
    private void tryLock(String sql, UUID id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            statement.executeQuery().close();
        }
    }

    private Instant updatedAt(String table, UUID id) {
        return jdbcTemplate.queryForObject("SELECT updated_at FROM " + table + " WHERE id = ?", Timestamp.class, id)
                .toInstant();
    }

    private Instant lastLoginAt(UUID id) {
        return Optional.ofNullable(jdbcTemplate.queryForObject("SELECT last_login_at FROM users WHERE id = ?",
                Timestamp.class, id)).map(Timestamp::toInstant).orElse(null);
    }

    private static UserEntity user(String username, UserStatus status) {
        UserEntity user = new UserEntity();
        user.setKeycloakId(UUID.randomUUID());
        user.setUsername(username);
        user.setEmail(username + "@cce.local");
        user.setFirstName("First");
        user.setLastName("Last");
        user.setStatus(status);
        return user;
    }

    private static RoleEntity role(String name) {
        RoleEntity role = new RoleEntity();
        role.setName(name);
        role.setDisplayName(name);
        role.setAssignable(true);
        return role;
    }

    private static MenuEntity navMenu(String key, String label, int sortOrder) {
        MenuEntity menu = new MenuEntity();
        menu.setKey(key);
        menu.setLabel(label);
        menu.setPath("/" + key);
        menu.setType(MenuType.NAV);
        menu.setSortOrder(sortOrder);
        return menu;
    }

    private static RoleMenuEntity roleMenu(UUID roleId, UUID menuId) {
        RoleMenuEntity roleMenu = new RoleMenuEntity();
        roleMenu.setRoleId(roleId);
        roleMenu.setMenuId(menuId);
        return roleMenu;
    }

    private static UserRoleEntity userRole(UUID userId, UUID roleId) {
        UserRoleEntity userRole = new UserRoleEntity();
        userRole.setUserId(userId);
        userRole.setRoleId(roleId);
        return userRole;
    }

    private static ApiPermissionEntity permission(String name, String method, String uriPattern) {
        ApiPermissionEntity permission = new ApiPermissionEntity();
        permission.setPermissionName(name);
        permission.setHttpMethod(method);
        permission.setUriPattern(uriPattern);
        return permission;
    }

    private AuditLogEntity audit(UUID entityId, String action) {
        AuditLogEntity entry = new AuditLogEntity();
        entry.setActorUsername("system:bootstrap");
        entry.setAction(action);
        entry.setEntityType(AuditEntityType.ROLE);
        entry.setEntityId(entityId);
        entry.setOutcome(AuditOutcome.SUCCESS);
        entry.setAfterState(objectMapper.createObjectNode().put("name", "X").put("linked", true));
        entry.setIpAddress("127.0.0.1");
        return entry;
    }
}
