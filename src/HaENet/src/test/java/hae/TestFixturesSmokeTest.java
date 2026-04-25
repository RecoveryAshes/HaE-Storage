package hae;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestFixturesSmokeTest {
    @TempDir
    Path tempDirectory;

    @Test
    void providesTemporarySqliteDatabasePath() {
        Path databasePath = TestFixtures.temporarySqliteDatabasePath(tempDirectory);

        assertTrue(databasePath.startsWith(tempDirectory));
        assertTrue(databasePath.getFileName().toString().endsWith(".db"));
    }

    @Test
    void providesMinimalHttpBytePayloads() {
        assertArrayEquals(
                "GET / HTTP/1.1\r\nHost: example.test\r\n\r\n".getBytes(StandardCharsets.US_ASCII),
                TestFixtures.minimalHttpRequestBytes()
        );
        assertArrayEquals(
                "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII),
                TestFixtures.minimalHttpResponseBytes()
        );
    }

    @Test
    void assertsSqlCountsAgainstTemporarySqliteDatabase() throws Exception {
        Path databasePath = TestFixtures.temporarySqliteDatabasePath(tempDirectory);

        try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(databasePath));
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE message_history (message_id TEXT PRIMARY KEY)");
            statement.executeUpdate("INSERT INTO message_history (message_id) VALUES ('one')");
            statement.executeUpdate("INSERT INTO message_history (message_id) VALUES ('two')");

            TestFixtures.assertSqlCount(connection, "message_history", 2);
        }

        TestFixtures.assertSqlCount(databasePath, "message_history", 2);
    }
}
