package org.cce.admin.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.cce.admin.support.FreshDatabase;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/** An existing api_permissions with a different column type stops the migration at V2 (AD-02). */
class MigrationShapeMismatchTest {

    @Test
    void failsAtV2BeforeAnySeedRowIsWritten() {
        FreshDatabase db = new FreshDatabase();
        JdbcTemplate jdbc = db.jdbc();
        db.runSql(new ClassPathResource("gateway/mismatched-api-permissions.sql"));

        assertThatThrownBy(db::migrate)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("V2__verify_api_permissions.sql")
                .hasMessageContaining("api_permissions check failed")
                .hasMessageContaining("uri_pattern character varying length=255");

        // V1 applied; V2 failed and was rolled back; V3 never ran.
        assertThat(jdbc.queryForList("SELECT version FROM admin_service_flyway_history"
                + " WHERE type <> 'BASELINE' AND success ORDER BY installed_rank", String.class))
                .containsExactly("1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM menus", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM roles", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM api_permissions"
                + " WHERE permission_name IN ('SUPER_ADMIN', 'CCE_ADMIN_SERVICE')", Integer.class)).isZero();
        // The table itself is still the one the fixture made, with its five rows.
        assertThat(jdbc.queryForObject("SELECT character_maximum_length FROM information_schema.columns"
                + " WHERE table_name = 'api_permissions' AND column_name = 'uri_pattern'", Integer.class))
                .isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM api_permissions", Integer.class)).isEqualTo(5);
    }
}
