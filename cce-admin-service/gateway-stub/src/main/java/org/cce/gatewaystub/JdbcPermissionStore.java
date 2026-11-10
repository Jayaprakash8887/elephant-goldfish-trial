package org.cce.gatewaystub;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.TreeSet;

/** {@link PermissionStore} over JDBC: one short connection per sync. */
public class JdbcPermissionStore implements PermissionStore {

    private final String url;
    private final String username;
    private final String password;

    public JdbcPermissionStore(String url, String username, String password) {
        this.url = url;
        this.username = username;
        this.password = password;
    }

    @Override
    public Set<String> distinctPermissionNames() {
        try (Connection connection = DriverManager.getConnection(url, username, password);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT DISTINCT permission_name FROM api_permissions")) {
            Set<String> names = new TreeSet<>();
            while (rows.next()) {
                names.add(rows.getString(1));
            }
            return names;
        } catch (SQLException e) {
            throw new IllegalStateException("Reading api_permissions failed: " + e.getMessage(), e);
        }
    }
}
