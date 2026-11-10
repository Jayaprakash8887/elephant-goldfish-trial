package org.cce.admin.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.cce.admin.support.FreshDatabase;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Where gateway-service's own script already created api_permissions, the migrations succeed,
 * leave the table and its rows alone, and add the six seed rows (AD-02).
 */
class MigrationExistingGatewayTableTest {

    private static final String ROWS = "SELECT id || '|' || permission_name || '|' || http_method || '|' || uri_pattern"
            + " || '|' || coalesce(description, '-') || '|' || coalesce(resource_category, '-')"
            + " || '|' || created_at || '|' || updated_at FROM api_permissions ORDER BY id";

    @Test
    void migratesAroundTheExistingTableWithoutChangingIt() {
        FreshDatabase db = new FreshDatabase();
        JdbcTemplate jdbc = db.jdbc();
        db.runSql(new ClassPathResource("gateway/gateway-api-permissions.sql"));

        List<String> definitionBefore = tableDefinition(jdbc);
        List<String> rowsBefore = jdbc.queryForList(ROWS, String.class);
        assertThat(rowsBefore).hasSize(5);

        MigrateResult result = db.migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT version FROM admin_service_flyway_history"
                + " WHERE type <> 'BASELINE' AND success ORDER BY installed_rank", String.class))
                .containsExactly("1", "2", "3");

        // The table's definition is unchanged.
        assertThat(tableDefinition(jdbc)).isEqualTo(definitionBefore);

        // The five fixture rows are unchanged, and the six seed rows are added after them.
        List<String> rowsAfter = jdbc.queryForList(ROWS, String.class);
        assertThat(rowsAfter).hasSize(11);
        assertThat(rowsAfter.subList(0, 5)).isEqualTo(rowsBefore);
        assertThat(jdbc.queryForList("SELECT permission_name || ' ' || http_method || ' ' || uri_pattern"
                + " FROM api_permissions WHERE permission_name IN ('SUPER_ADMIN', 'CCE_ADMIN_SERVICE') ORDER BY id",
                String.class)).containsExactly(
                "SUPER_ADMIN GET /admin/v1/**",
                "SUPER_ADMIN POST /admin/v1/**",
                "SUPER_ADMIN PUT /admin/v1/**",
                "SUPER_ADMIN PATCH /admin/v1/**",
                "SUPER_ADMIN DELETE /admin/v1/**",
                "CCE_ADMIN_SERVICE POST /api/permissions/sync-to-keycloak");

        // The service's own tables and seed exist as on an empty database.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM menus", Integer.class)).isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM roles", Integer.class)).isEqualTo(2);
    }

    /** Columns, constraints, indexes, triggers and the trigger function of api_permissions. */
    private static List<String> tableDefinition(JdbcTemplate jdbc) {
        List<String> definition = new ArrayList<>();
        definition.addAll(jdbc.queryForList("SELECT 'column ' || column_name || ' ' || udt_name"
                + " || coalesce('(' || character_maximum_length || ')', '') || ' ' || is_nullable"
                + " || ' ' || coalesce(column_default, '-') FROM information_schema.columns"
                + " WHERE table_schema = 'public' AND table_name = 'api_permissions' ORDER BY ordinal_position",
                String.class));
        definition.addAll(jdbc.queryForList("SELECT 'constraint ' || conname || ' ' || pg_get_constraintdef(oid)"
                + " FROM pg_constraint WHERE conrelid = 'public.api_permissions'::regclass ORDER BY conname",
                String.class));
        definition.addAll(jdbc.queryForList("SELECT 'index ' || indexdef FROM pg_indexes"
                + " WHERE schemaname = 'public' AND tablename = 'api_permissions' ORDER BY indexname", String.class));
        definition.addAll(jdbc.queryForList("SELECT 'trigger ' || pg_get_triggerdef(oid) FROM pg_trigger"
                + " WHERE tgrelid = 'public.api_permissions'::regclass AND NOT tgisinternal ORDER BY tgname",
                String.class));
        definition.addAll(jdbc.queryForList("SELECT 'function ' || p.oid || ' ' || pg_get_functiondef(p.oid)"
                + " FROM pg_proc p WHERE p.proname = 'update_api_permissions_updated_at'", String.class));
        return definition;
    }
}
