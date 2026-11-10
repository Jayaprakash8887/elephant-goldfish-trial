package org.cce.admin.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class of every database test. One Postgres container (the singleton-container pattern) and
 * one Spring context are shared by every subclass. Each test starts from the seed rows only.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class DatabaseTestBase {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.15-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /**
     * Removes everything the tests created and keeps the seed rows (section 6). Row triggers do not
     * fire on TRUNCATE, so the append-only trigger of audit_log allows it.
     */
    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.execute("TRUNCATE audit_log, user_roles, role_menus, users");
        jdbcTemplate.execute("DELETE FROM api_permissions WHERE permission_name NOT IN ('SUPER_ADMIN', 'CCE_ADMIN_SERVICE')");
        jdbcTemplate.execute("DELETE FROM roles WHERE is_system = false");
        jdbcTemplate.execute("UPDATE roles SET sync_status = 'NEVER', last_sync_at = NULL");
        jdbcTemplate.execute("DELETE FROM menus WHERE parent_id IS NOT NULL AND id::text NOT LIKE '10000000-0000-4000-8000-%'");
        jdbcTemplate.execute("DELETE FROM menus WHERE id::text NOT LIKE '10000000-0000-4000-8000-%'");
    }
}
