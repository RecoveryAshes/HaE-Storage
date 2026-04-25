package hae;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.UUID;

public final class TestFixtures {
    private TestFixtures() {
    }

    public static Path temporarySqliteDatabasePath(Path tempDirectory) {
        Objects.requireNonNull(tempDirectory, "tempDirectory");
        return tempDirectory.resolve("hae-test-" + UUID.randomUUID() + ".db");
    }

    public static String sqliteJdbcUrl(Path databasePath) {
        Objects.requireNonNull(databasePath, "databasePath");
        return "jdbc:sqlite:" + databasePath.toAbsolutePath();
    }

    public static byte[] minimalHttpRequestBytes() {
        return "GET / HTTP/1.1\r\nHost: example.test\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] minimalHttpResponseBytes() {
        return "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    }

    public static void assertSqlCount(Path databasePath, String tableName, long expectedCount) throws SQLException {
        try (Connection connection = DriverManager.getConnection(sqliteJdbcUrl(databasePath))) {
            assertSqlCount(connection, tableName, expectedCount);
        }
    }

    public static void assertSqlCount(Connection connection, String tableName, long expectedCount) throws SQLException {
        long actualCount = countRows(connection, tableName);
        assertEquals(expectedCount, actualCount, () -> "Unexpected row count for table " + tableName);
    }

    public static long countRows(Connection connection, String tableName) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        requireSimpleSqlIdentifier(tableName);

        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            return resultSet.next() ? resultSet.getLong(1) : 0;
        }
    }

    private static void requireSimpleSqlIdentifier(String identifier) {
        if (identifier == null || !identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Only simple SQL identifiers are supported: " + identifier);
        }
    }
}
