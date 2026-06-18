package com.pos.automation.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reusable MySQL database validation utility.
 *
 * <p>Maintains a single lazy-initialised {@link Connection} per JVM session.
 * The connection is re-created if it has been closed. All credentials are read
 * from {@link ConfigReader}.
 *
 * <p>Resource management:
 * <ul>
 *   <li>{@link #fetchSingleValue}, {@link #fetchAllRecords}, {@link #validateRecord},
 *       and {@link #executeUpdate} fully manage their own {@code Statement}/{@code ResultSet}
 *       lifecycle — callers do not need to close anything.</li>
 *   <li>{@link #executeQuery} returns a live {@code ResultSet} — the caller
 *       <strong>must</strong> close it (and its owning {@code Statement}) when done.</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 *   String username = DatabaseUtil.fetchSingleValue(
 *       "SELECT username FROM users WHERE id = 1", "username");
 *
 *   boolean valid = DatabaseUtil.validateRecord(
 *       "SELECT status FROM orders WHERE order_id = '12345'", "status", "COMPLETED");
 * </pre>
 */
public final class DatabaseUtil {

    private static final Logger log = LoggerFactory.getLogger(DatabaseUtil.class);
    private static Connection connection;

    private DatabaseUtil() {}

    // ── Connection Management ────────────────────────────────────────────────────

    /**
     * Returns a valid, open connection, creating one if needed.
     * Re-creates the connection if the existing one has been closed or is invalid.
     */
    public static synchronized Connection getConnection() {
        try {
            if (connection == null || connection.isClosed() || !connection.isValid(3)) {
                log.info("Creating new database connection to: {}",
                        ConfigReader.getString(ConfigReader.DB_URL));
                connection = createConnection();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Database connection validation failed: " + e.getMessage(), e);
        }
        return connection;
    }

    /** Closes the shared connection; safe to call multiple times. */
    public static synchronized void closeConnection() {
        if (connection != null) {
            try {
                connection.close();
                log.info("Database connection closed");
            } catch (SQLException e) {
                log.warn("Error closing database connection: {}", e.getMessage());
            } finally {
                connection = null;
            }
        }
    }

    // ── Query Execution ──────────────────────────────────────────────────────────

    /**
     * Executes a SELECT query and returns the live {@link ResultSet}.
     * <strong>The caller is responsible for closing the ResultSet and its Statement.</strong>
     *
     * @param sql SELECT statement
     * @return open ResultSet, or {@code null} if the query returned no rows
     */
    public static ResultSet executeQuery(String sql) {
        log.debug("Executing query: {}", sql);
        try {
            Statement stmt = getConnection().createStatement();
            ResultSet rs = stmt.executeQuery(sql);
            return rs.next() ? rs : null;
        } catch (SQLException e) {
            throw new RuntimeException("Query execution failed: " + sql + " — " + e.getMessage(), e);
        }
    }

    /**
     * Returns the String value of {@code column} from the first row of the query result.
     * Returns {@code null} if the query produces no rows or the column is absent.
     */
    public static String fetchSingleValue(String sql, String column) {
        log.debug("fetchSingleValue — query: {}", sql);
        try (Statement stmt = getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                String value = rs.getString(column);
                log.debug("fetchSingleValue — column='{}' value='{}'", column, value);
                return value;
            }
            log.debug("fetchSingleValue — query returned no rows");
            return null;
        } catch (SQLException e) {
            log.warn("fetchSingleValue failed ({}): {}", sql, e.getMessage());
            return null;
        }
    }

    /**
     * Returns all rows from the query as a list of column→value maps.
     * All values are converted to {@code String} via {@code ResultSet.getString()}.
     */
    public static List<Map<String, String>> fetchAllRecords(String sql) {
        log.debug("fetchAllRecords — query: {}", sql);
        List<Map<String, String>> records = new ArrayList<>();
        try (Statement stmt = getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();
            while (rs.next()) {
                Map<String, String> row = new HashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    String colName = meta.getColumnLabel(i);
                    String value   = rs.getString(i);
                    row.put(colName, value != null ? value : "");
                }
                records.add(row);
            }
        } catch (SQLException e) {
            throw new RuntimeException("fetchAllRecords failed: " + sql + " — " + e.getMessage(), e);
        }
        log.info("fetchAllRecords — {} record(s) returned", records.size());
        return records;
    }

    /**
     * Executes a query, reads {@code column} from the first row, and checks if it equals
     * {@code expectedValue} (case-sensitive).
     *
     * @return {@code true} if the column value matches; {@code false} otherwise or on any error
     */
    public static boolean validateRecord(String sql, String column, String expectedValue) {
        log.info("validateRecord — column='{}' expected='{}'", column, expectedValue);
        String actual = fetchSingleValue(sql, column);
        boolean match = expectedValue.equals(actual);
        if (match) {
            log.info("DB validation PASSED — column='{}' actual='{}' expected='{}'",
                    column, actual, expectedValue);
        } else {
            log.warn("DB validation FAILED — column='{}' actual='{}' expected='{}'",
                    column, actual, expectedValue);
        }
        return match;
    }

    /**
     * Executes an INSERT, UPDATE, or DELETE statement.
     *
     * @return number of rows affected
     */
    public static int executeUpdate(String sql) {
        log.debug("Executing update: {}", sql);
        try (Statement stmt = getConnection().createStatement()) {
            int rows = stmt.executeUpdate(sql);
            log.info("executeUpdate — {} row(s) affected", rows);
            return rows;
        } catch (SQLException e) {
            throw new RuntimeException("executeUpdate failed: " + sql + " — " + e.getMessage(), e);
        }
    }

    // ── Private ──────────────────────────────────────────────────────────────────

    private static Connection createConnection() {
        try {
            String driver   = ConfigReader.getString(ConfigReader.DB_DRIVER);
            String url      = ConfigReader.getString(ConfigReader.DB_URL);
            String username = ConfigReader.getString(ConfigReader.DB_USERNAME);
            String password = ConfigReader.getString(ConfigReader.DB_PASSWORD);

            Class.forName(driver);
            Connection conn = DriverManager.getConnection(url, username, password);
            log.info("Database connection established — URL: {}", url);
            return conn;
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("JDBC driver not found: " + e.getMessage(), e);
        } catch (SQLException e) {
            throw new RuntimeException("Database connection failed: " + e.getMessage(), e);
        }
    }
}
