package org.cce.admin.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.cce.admin.support.FreshDatabase;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** V1 to V3 on an empty database create every object and seed row of section 6 (B-01, B-02). */
class MigrationEmptyDatabaseTest {

    private static FreshDatabase db;
    private static JdbcTemplate jdbc;
    private static MigrateResult result;

    @BeforeAll
    static void migrate() {
        db = new FreshDatabase();
        jdbc = db.jdbc();
        result = db.migrate();
    }

    @Test
    void appliesTheThreeMigrationsIntoItsOwnHistoryTable() {
        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(3);
        assertThat(rows("SELECT version || '|' || success FROM admin_service_flyway_history ORDER BY installed_rank"))
                .containsExactly("1|true", "2|true", "3|true");
        assertThat(jdbc.queryForObject("SELECT to_regclass('public.flyway_schema_history') IS NULL", Boolean.class))
                .isTrue();
    }

    @Test
    void enablesPgcrypto() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_extension WHERE extname = 'pgcrypto'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void createsExactlyTheSevenTablesAndTheHistoryTable() {
        assertThat(rows("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY 1"))
                .containsExactly("admin_service_flyway_history", "api_permissions", "audit_log", "menus",
                        "role_menus", "roles", "user_roles", "users");
    }

    @Test
    void usersColumns() {
        assertThat(columns("users")).containsExactly(
                "id uuid NOT NULL gen_random_uuid()",
                "keycloak_id uuid NOT NULL",
                "username varchar(50) NOT NULL",
                "email varchar(254) NOT NULL",
                "first_name varchar(100) NOT NULL",
                "last_name varchar(100) NOT NULL",
                "status varchar(10) NOT NULL",
                "facility_scope varchar(50) NULL",
                "last_login_at timestamptz NULL",
                "created_at timestamptz NOT NULL now()",
                "updated_at timestamptz NOT NULL now()");
    }

    @Test
    void rolesColumns() {
        assertThat(columns("roles")).containsExactly(
                "id uuid NOT NULL gen_random_uuid()",
                "name varchar(100) NOT NULL",
                "display_name varchar(100) NOT NULL",
                "description varchar(500) NULL",
                "is_system bool NOT NULL false",
                "is_assignable bool NOT NULL",
                "sync_status varchar(10) NOT NULL 'NEVER'::character varying",
                "last_sync_at timestamptz NULL",
                "created_at timestamptz NOT NULL now()",
                "updated_at timestamptz NOT NULL now()");
    }

    @Test
    void menusColumns() {
        assertThat(columns("menus")).containsExactly(
                "id uuid NOT NULL gen_random_uuid()",
                "key varchar(100) NOT NULL",
                "label varchar(100) NOT NULL",
                "path varchar(255) NOT NULL",
                "icon varchar(100) NULL",
                "type varchar(10) NOT NULL",
                "parent_id uuid NULL",
                "sort_order int4 NOT NULL",
                "is_active bool NOT NULL true",
                "created_at timestamptz NOT NULL now()",
                "updated_at timestamptz NOT NULL now()");
    }

    @Test
    void joinTableColumns() {
        assertThat(columns("role_menus")).containsExactly(
                "id uuid NOT NULL gen_random_uuid()",
                "role_id uuid NOT NULL",
                "menu_id uuid NOT NULL",
                "created_at timestamptz NOT NULL now()");
        assertThat(columns("user_roles")).containsExactly(
                "id uuid NOT NULL gen_random_uuid()",
                "user_id uuid NOT NULL",
                "role_id uuid NOT NULL",
                "created_at timestamptz NOT NULL now()");
    }

    @Test
    void auditLogColumns() {
        assertThat(columns("audit_log")).containsExactly(
                "id uuid NOT NULL gen_random_uuid()",
                "occurred_at timestamptz NOT NULL now()",
                "actor_user_id uuid NULL",
                "actor_keycloak_id varchar(64) NULL",
                "actor_username varchar(100) NOT NULL",
                "action varchar(50) NOT NULL",
                "entity_type varchar(20) NOT NULL",
                "entity_id uuid NOT NULL",
                "outcome varchar(10) NOT NULL",
                "before_state jsonb NULL",
                "after_state jsonb NULL",
                "failure jsonb NULL",
                "ip_address varchar(45) NULL");
    }

    @Test
    void apiPermissionsColumnsHaveTheGatewayShape() {
        assertThat(columns("api_permissions")).containsExactly(
                "id int8 NOT NULL nextval('api_permissions_id_seq'::regclass)",
                "permission_name varchar(100) NOT NULL",
                "http_method varchar(10) NOT NULL",
                "uri_pattern varchar(255) NOT NULL",
                "description text NULL",
                "resource_category varchar(50) NULL",
                "created_at timestamp NULL CURRENT_TIMESTAMP",
                "updated_at timestamp NULL CURRENT_TIMESTAMP");
    }

    @Test
    void isAssignableHasNoDefault() {
        // AD-14: the column has no default, so an insert that leaves it out fails.
        assertThat(jdbc.queryForObject("SELECT column_default FROM information_schema.columns"
                + " WHERE table_name = 'roles' AND column_name = 'is_assignable'", String.class)).isNull();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO roles (name, display_name) VALUES ('NO_DEFAULT', 'x')"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("is_assignable");
    }

    @Test
    void indexes() {
        assertThat(rows("SELECT indexdef FROM pg_indexes WHERE schemaname = 'public'"
                + " AND tablename <> 'admin_service_flyway_history' ORDER BY tablename, indexname")).containsExactly(
                "CREATE UNIQUE INDEX api_permissions_pkey ON public.api_permissions USING btree (id)",
                "CREATE INDEX idx_permission_name ON public.api_permissions USING btree (permission_name)",
                "CREATE INDEX idx_resource_category ON public.api_permissions USING btree (resource_category)",
                "CREATE UNIQUE INDEX unique_permission_method_uri ON public.api_permissions USING btree (permission_name, http_method, uri_pattern)",
                "CREATE UNIQUE INDEX audit_log_pkey ON public.audit_log USING btree (id)",
                "CREATE INDEX ix_audit_log_actor_user_id ON public.audit_log USING btree (actor_user_id)",
                "CREATE INDEX ix_audit_log_entity ON public.audit_log USING btree (entity_type, entity_id, occurred_at)",
                "CREATE INDEX ix_audit_log_occurred_at ON public.audit_log USING btree (occurred_at)",
                "CREATE INDEX ix_audit_log_outcome ON public.audit_log USING btree (outcome)",
                "CREATE INDEX ix_menus_parent_id ON public.menus USING btree (parent_id)",
                "CREATE UNIQUE INDEX menus_pkey ON public.menus USING btree (id)",
                "CREATE UNIQUE INDEX ux_menus_key ON public.menus USING btree (key)",
                "CREATE INDEX ix_role_menus_menu_id ON public.role_menus USING btree (menu_id)",
                "CREATE UNIQUE INDEX role_menus_pkey ON public.role_menus USING btree (id)",
                "CREATE UNIQUE INDEX ux_role_menus_role_menu ON public.role_menus USING btree (role_id, menu_id)",
                "CREATE UNIQUE INDEX roles_pkey ON public.roles USING btree (id)",
                "CREATE UNIQUE INDEX ux_roles_name ON public.roles USING btree (name)",
                "CREATE INDEX ix_user_roles_role_id ON public.user_roles USING btree (role_id)",
                "CREATE UNIQUE INDEX user_roles_pkey ON public.user_roles USING btree (id)",
                "CREATE UNIQUE INDEX ux_user_roles_user_role ON public.user_roles USING btree (user_id, role_id)",
                "CREATE INDEX ix_users_facility_scope ON public.users USING btree (facility_scope)",
                "CREATE INDEX ix_users_status ON public.users USING btree (status)",
                "CREATE UNIQUE INDEX users_pkey ON public.users USING btree (id)",
                "CREATE UNIQUE INDEX ux_users_email_lower ON public.users USING btree (lower((email)::text))",
                "CREATE UNIQUE INDEX ux_users_keycloak_id ON public.users USING btree (keycloak_id)",
                "CREATE UNIQUE INDEX ux_users_username ON public.users USING btree (username)");
    }

    @Test
    void primaryUniqueAndForeignKeyConstraints() {
        assertThat(rows("SELECT conrelid::regclass || ' ' || pg_get_constraintdef(oid) FROM pg_constraint"
                + " WHERE connamespace = 'public'::regnamespace AND contype IN ('p', 'u', 'f')"
                + " AND conrelid <> 'admin_service_flyway_history'::regclass ORDER BY 1")).containsExactly(
                "api_permissions PRIMARY KEY (id)",
                "api_permissions UNIQUE (permission_name, http_method, uri_pattern)",
                "audit_log PRIMARY KEY (id)",
                "menus FOREIGN KEY (parent_id) REFERENCES menus(id) ON DELETE RESTRICT",
                "menus PRIMARY KEY (id)",
                "menus UNIQUE (key)",
                "role_menus FOREIGN KEY (menu_id) REFERENCES menus(id) ON DELETE CASCADE",
                "role_menus FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE",
                "role_menus PRIMARY KEY (id)",
                "role_menus UNIQUE (role_id, menu_id)",
                "roles PRIMARY KEY (id)",
                "roles UNIQUE (name)",
                "user_roles FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE RESTRICT",
                "user_roles FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT",
                "user_roles PRIMARY KEY (id)",
                "user_roles UNIQUE (user_id, role_id)",
                "users PRIMARY KEY (id)",
                "users UNIQUE (keycloak_id)",
                "users UNIQUE (username)");
        assertThat(rows("SELECT conname FROM pg_constraint WHERE contype = 'u'"
                + " AND connamespace = 'public'::regnamespace ORDER BY 1")).containsExactly(
                "unique_permission_method_uri", "ux_menus_key", "ux_role_menus_role_menu", "ux_roles_name",
                "ux_user_roles_user_role", "ux_users_keycloak_id", "ux_users_username");
    }

    @Test
    void checkConstraintsRejectValuesOutsideSectionSix() {
        assertCheckViolation("INSERT INTO users (keycloak_id, username, email, first_name, last_name, status)"
                + " VALUES (gen_random_uuid(), 'u1', 'u1@x', 'a', 'b', 'DELETED')");
        assertCheckViolation("INSERT INTO roles (name, display_name, is_assignable) VALUES ('lower_case', 'x', true)");
        assertCheckViolation("INSERT INTO roles (name, display_name, is_assignable) VALUES ('A__B', 'x', true)");
        assertCheckViolation("INSERT INTO roles (name, display_name, is_assignable, sync_status)"
                + " VALUES ('CHECK_ROLE', 'x', true, 'PENDING')");
        assertCheckViolation("INSERT INTO menus (key, label, path, type, sort_order) VALUES ('m1', 'x', '/', 'ROUTE', 1)");
        assertCheckViolation("INSERT INTO menus (key, label, path, type, sort_order) VALUES ('m2', 'x', '/', 'NAV', -1)");
        assertCheckViolation("INSERT INTO menus (key, label, path, type, sort_order) VALUES ('m3', 'x', '/', 'NAV', 10001)");
        assertCheckViolation("INSERT INTO audit_log (actor_username, action, entity_type, entity_id, outcome)"
                + " VALUES ('x', 'X', 'PERMISSION', gen_random_uuid(), 'SUCCESS')");
        assertCheckViolation("INSERT INTO audit_log (actor_username, action, entity_type, entity_id, outcome)"
                + " VALUES ('x', 'X', 'USER', gen_random_uuid(), 'PARTIAL')");
    }

    @Test
    void menuParentCheck() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname = 'ck_menus_parent'"
                + " AND conrelid = 'menus'::regclass AND contype = 'c'", Integer.class)).isEqualTo(1);
        // A NAV with a parent, and a DETAIL without one.
        assertThatThrownBy(() -> jdbc.update("INSERT INTO menus (key, label, path, type, parent_id, sort_order)"
                + " VALUES ('nav-with-parent', 'x', '/', 'NAV', '10000000-0000-4000-8000-000000000001', 1)"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_menus_parent");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO menus (key, label, path, type, sort_order)"
                + " VALUES ('detail-without-parent', 'x', '/', 'DETAIL', 1)"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_menus_parent");
    }

    @Test
    void triggers() {
        assertThat(rows("SELECT pg_get_triggerdef(oid) FROM pg_trigger WHERE NOT tgisinternal ORDER BY tgname"))
                .containsExactly(
                        "CREATE TRIGGER trg_api_permissions_updated_at BEFORE UPDATE ON public.api_permissions"
                                + " FOR EACH ROW EXECUTE FUNCTION update_api_permissions_updated_at()",
                        "CREATE TRIGGER trg_audit_log_append_only BEFORE DELETE OR UPDATE ON public.audit_log"
                                + " FOR EACH ROW EXECUTE FUNCTION audit_log_append_only()");
    }

    @Test
    void apiPermissionsTriggerSetsUpdatedAt() {
        jdbc.update("INSERT INTO api_permissions (permission_name, http_method, uri_pattern, created_at, updated_at)"
                + " VALUES ('TRIGGER_CHECK', 'GET', '/x', '2000-01-01', '2000-01-01')");
        jdbc.update("UPDATE api_permissions SET description = 'changed' WHERE permission_name = 'TRIGGER_CHECK'");
        assertThat(jdbc.queryForObject("SELECT updated_at > '2000-01-02' AND created_at = '2000-01-01'"
                + " FROM api_permissions WHERE permission_name = 'TRIGGER_CHECK'", Boolean.class)).isTrue();
        jdbc.update("DELETE FROM api_permissions WHERE permission_name = 'TRIGGER_CHECK'");
    }

    @Test
    void seedsTheTwelveMenusWithTheirFixedIds() {
        assertThat(rows("SELECT id || '|' || key || '|' || label || '|' || path || '|' || coalesce(icon, '-') || '|'"
                + " || type || '|' || coalesce(parent_id::text, '-') || '|' || sort_order || '|' || is_active"
                + " FROM menus ORDER BY id")).containsExactly(
                "10000000-0000-4000-8000-000000000001|dashboard|Dashboard|/|ChartBarIcon|NAV|-|10|true",
                "10000000-0000-4000-8000-000000000002|facilities|Facilities|/facilities|BuildingOffice2Icon|NAV|-|20|true",
                "10000000-0000-4000-8000-000000000003|compliance|Compliance|/compliance|ClipboardDocumentCheckIcon|NAV|-|30|true",
                "10000000-0000-4000-8000-000000000004|deviations|Deviations|/deviations|ExclamationTriangleIcon|NAV|-|40|true",
                "10000000-0000-4000-8000-000000000005|patients|Patients|/compliance/patients|UserGroupIcon|NAV|-|50|true",
                "10000000-0000-4000-8000-000000000006|events|Events|/events|SignalIcon|NAV|-|60|true",
                "10000000-0000-4000-8000-000000000007|ingestion|Ingestion|/ingestion|CogIcon|NAV|-|70|true",
                "10000000-0000-4000-8000-000000000008|practitioners|Practitioners|/practitioners|UserIcon|NAV|-|80|true",
                "10000000-0000-4000-8000-000000000009|intelligence|Intelligence|/intelligence|LightBulbIcon|NAV|-|90|true",
                "10000000-0000-4000-8000-000000000010|exports|Exports|/exports|ArrowDownTrayIcon|NAV|-|100|true",
                "10000000-0000-4000-8000-000000000011|compliance.protocol-detail|Protocol analytics|/compliance/protocols/:id|-|DETAIL|10000000-0000-4000-8000-000000000003|10|true",
                "10000000-0000-4000-8000-000000000012|patients.patient-detail|Patient detail|/compliance/patients/:id|-|DETAIL|10000000-0000-4000-8000-000000000005|10|true");
    }

    @Test
    void seedsTheTwoSystemRoles() {
        assertThat(rows("SELECT id || '|' || name || '|' || display_name || '|' || description || '|' || is_system"
                + " || '|' || is_assignable || '|' || sync_status || '|' || coalesce(last_sync_at::text, '-')"
                + " FROM roles ORDER BY id")).containsExactly(
                "20000000-0000-4000-8000-000000000001|SUPER_ADMIN|Super administrator"
                        + "|Platform administrator with full access to the admin API.|true|true|NEVER|-",
                "20000000-0000-4000-8000-000000000002|CCE_ADMIN_SERVICE|Admin service account"
                        + "|Service account of cce-admin-service. Calls the gateway sync.|true|false|NEVER|-");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM role_menus", Integer.class)).isZero();
    }

    @Test
    void seedsTheSixPermissionRows() {
        assertThat(rows("SELECT permission_name || '|' || http_method || '|' || uri_pattern || '|' || description"
                + " || '|' || resource_category FROM api_permissions ORDER BY id")).containsExactly(
                "SUPER_ADMIN|GET|/admin/v1/**|Administer the CCE admin module|Admin",
                "SUPER_ADMIN|POST|/admin/v1/**|Administer the CCE admin module|Admin",
                "SUPER_ADMIN|PUT|/admin/v1/**|Administer the CCE admin module|Admin",
                "SUPER_ADMIN|PATCH|/admin/v1/**|Administer the CCE admin module|Admin",
                "SUPER_ADMIN|DELETE|/admin/v1/**|Administer the CCE admin module|Admin",
                "CCE_ADMIN_SERVICE|POST|/api/permissions/sync-to-keycloak|Trigger the gateway's realm role sync|Gateway");
    }

    @Test
    void seedsNoUsersRoleGrantsOrAuditRows() {
        assertThat(jdbc.queryForObject("SELECT (SELECT count(*) FROM users) + (SELECT count(*) FROM user_roles)"
                + " + (SELECT count(*) FROM audit_log)", Integer.class)).isZero();
    }

    private static void assertCheckViolation(String insert) {
        assertThatThrownBy(() -> jdbc.update(insert))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("violates check constraint");
    }

    /** Each column as {@code name type[(length)] NULL|NOT NULL [default]}, in table order. */
    private static List<String> columns(String table) {
        return jdbc.queryForList("SELECT column_name || ' ' || udt_name"
                + " || coalesce('(' || character_maximum_length || ')', '')"
                + " || CASE is_nullable WHEN 'YES' THEN ' NULL' ELSE ' NOT NULL' END"
                + " || coalesce(' ' || column_default, '')"
                + " FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ?"
                + " ORDER BY ordinal_position", String.class, table);
    }

    private static List<String> rows(String sql) {
        return jdbc.queryForList(sql, String.class);
    }
}
