package org.cce.admin.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.StreamUtils;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A new, uniquely named database in the shared test container ({@link DatabaseTestBase#POSTGRES}),
 * for the migration tests. No Spring context.
 */
public final class FreshDatabase {

    private final String name;
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final JdbcTemplate jdbc;

    public FreshDatabase() {
        PostgreSQLContainer<?> container = DatabaseTestBase.POSTGRES;
        this.name = "fresh_" + UUID.randomUUID().toString().replace("-", "");
        this.username = container.getUsername();
        this.password = container.getPassword();
        try (Connection connection = DriverManager.getConnection(container.getJdbcUrl(), username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("could not create database " + name, e);
        }
        this.jdbcUrl = "jdbc:postgresql://" + container.getHost() + ":"
                + container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + name;
        this.jdbc = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, username, password));
    }

    public String name() {
        return name;
    }

    /** A template on this database. Each call runs on its own connection, in auto-commit. */
    public JdbcTemplate jdbc() {
        return jdbc;
    }

    /** Sends the whole file in one {@code Statement.execute}, so {@code $$} bodies are not split. */
    public void runSql(Resource resource) {
        String sql;
        try {
            sql = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("could not run " + resource, e);
        }
    }

    /** Runs Flyway through its API with the {@code spring.flyway.*} settings of application.yml. */
    public MigrateResult migrate() {
        Properties settings = applicationYml();
        return Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations("classpath:db/migration")
                .baselineOnMigrate(Boolean.parseBoolean(required(settings, "spring.flyway.baseline-on-migrate")))
                .baselineVersion(required(settings, "spring.flyway.baseline-version"))
                .table(required(settings, "spring.flyway.table"))
                .load()
                .migrate();
    }

    private static Properties applicationYml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        return yaml.getObject();
    }

    private static String required(Properties settings, String key) {
        String value = settings.getProperty(key);
        if (value == null) {
            throw new IllegalStateException("application.yml has no " + key);
        }
        return value;
    }
}
