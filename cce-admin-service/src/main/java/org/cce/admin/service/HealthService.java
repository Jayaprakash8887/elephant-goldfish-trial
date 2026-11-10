package org.cce.admin.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The database probe of {@code GET /health} (B-05): {@code SELECT 1}, with at most 2 seconds to get a
 * connection (the pool's {@code connection-timeout}) and 2 seconds to run.
 */
@Service
public class HealthService {

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);
    static final int QUERY_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;

    public HealthService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public boolean isDatabaseUp() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.execute("SELECT 1");
            return true;
        } catch (SQLException e) {
            log.warn("Database health probe failed: {}", e.getMessage());
            return false;
        }
    }
}
