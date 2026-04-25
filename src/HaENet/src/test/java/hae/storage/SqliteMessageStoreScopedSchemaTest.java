package hae.storage;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import hae.TestFixtures;
import hae.utils.ConfigLoader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteMessageStoreScopedSchemaTest {
    private static final String MAIN_MESSAGE_TABLE = "message_history";
    private static final String MAIN_MATCH_TABLE = "message_match";
    private static final String SCOPED_SCOPE_TABLE = "scoped_databoard_scope";
    private static final String SCOPED_MESSAGE_TABLE = "scoped_databoard_message";
    private static final String SCOPED_MATCH_TABLE = "scoped_databoard_match";

    @TempDir
    Path tempDirectory;

    @Test
    void freshDatabaseInitializesScopedTablesAndIndexes() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("fresh"));

        try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(context.databasePath()))) {
            assertAll(
                    () -> assertSqliteObjectExists(connection, "table", SCOPED_SCOPE_TABLE),
                    () -> assertSqliteObjectExists(connection, "table", SCOPED_MESSAGE_TABLE),
                    () -> assertSqliteObjectExists(connection, "table", SCOPED_MATCH_TABLE),
                    () -> assertSqliteObjectExists(connection, "index", "idx_scoped_databoard_message_scope_id"),
                    () -> assertSqliteObjectExists(connection, "index", "idx_scoped_databoard_message_scoped_message_id"),
                    () -> assertSqliteObjectExists(connection, "index", "idx_scoped_databoard_match_scope_id"),
                    () -> assertSqliteObjectExists(connection, "index", "idx_scoped_databoard_match_message_id"),
                    () -> assertSqliteObjectExists(connection, "index", "idx_scoped_databoard_match_scope_rule_value")
            );
        }
    }

    @Test
    void legacyMainSchemaMigrationAddsScopedTablesAndIndexesWithoutChangingMainRows() throws Exception {
        Path home = tempDirectory.resolve("existing");
        Path databasePath = haeDatabasePath(home);
        Files.createDirectories(databasePath.getParent());
        createExistingMainTables(databasePath);

        try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(databasePath))) {
            assertAll(
                    () -> TestFixtures.assertSqlCount(connection, MAIN_MESSAGE_TABLE, 1),
                    () -> TestFixtures.assertSqlCount(connection, MAIN_MATCH_TABLE, 1),
                    () -> assertSqliteObjectMissing(connection, "table", SCOPED_SCOPE_TABLE),
                    () -> assertSqliteObjectMissing(connection, "table", SCOPED_MESSAGE_TABLE),
                    () -> assertSqliteObjectMissing(connection, "table", SCOPED_MATCH_TABLE)
            );
        }

        StoreContext context = createStoreContext(home);

        try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(context.databasePath()))) {
            assertAll(
                    () -> TestFixtures.assertSqlCount(connection, MAIN_MESSAGE_TABLE, 1),
                    () -> TestFixtures.assertSqlCount(connection, MAIN_MATCH_TABLE, 1),
                    () -> assertEquals("https://example.test/existing", singleString(connection,
                            "SELECT url FROM message_history WHERE message_id = ? AND content_hash = ?",
                            "existing-main-1",
                            "existing-content-hash")),
                    () -> assertEquals("existing extracted value", singleString(connection,
                            "SELECT extracted_value FROM message_match WHERE message_id = ? AND rule_name = ?",
                            "existing-main-1",
                            "ExistingRule")),
                    () -> assertSqlColumnExists(connection, MAIN_MESSAGE_TABLE, "regex_status"),
                    () -> assertSqlColumnExists(connection, MAIN_MESSAGE_TABLE, "regex_error"),
                    () -> assertSqlColumnExists(connection, MAIN_MESSAGE_TABLE, "regex_attempts"),
                    () -> assertSqlColumnExists(connection, MAIN_MESSAGE_TABLE, "request_length"),
                    () -> assertSqlColumnExists(connection, MAIN_MESSAGE_TABLE, "response_length"),
                    () -> assertSqlColumnExists(connection, MAIN_MESSAGE_TABLE, "url_parse_error"),
                    () -> assertSqlColumnExists(connection, MAIN_MESSAGE_TABLE, "filter_reason"),
                    () -> assertSqliteObjectExists(connection, "table", SCOPED_SCOPE_TABLE),
                    () -> assertSqliteObjectExists(connection, "table", SCOPED_MESSAGE_TABLE),
                    () -> assertSqliteObjectExists(connection, "table", SCOPED_MATCH_TABLE),
                    () -> assertSqliteObjectExists(connection, "index", "idx_scoped_databoard_message_scope_id"),
                    () -> assertSqliteObjectExists(connection, "index", "idx_scoped_databoard_message_scoped_message_id"),
                    () -> assertSqliteObjectExists(connection, "index", "idx_scoped_databoard_match_scope_id"),
                    () -> assertSqliteObjectExists(connection, "index", "idx_scoped_databoard_match_message_id"),
                    () -> assertSqliteObjectExists(connection, "index", "idx_scoped_databoard_match_scope_rule_value")
            );
        }
    }

    @Test
    void scopedWritesDoNotChangeMainHistoryOrMatchCounts() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("isolation"));

        assertExistingMainCounts(context.databasePath(), 0, 0);
        String scopeId = context.store().createScopedDataboardScope("test-source", "test-label");
        assertNotNull(scopeId);

        context.store().saveScopedMessage(
                scopeId,
                "scoped-1",
                minimalRequestResponse(),
                "https://example.test/path",
                "example.test",
                "GET",
                "200",
                "42",
                "scoped comment",
                "yellow",
                "scoped-content-hash"
        );
        String longExtractedValue = "scoped-value-" + "x".repeat(512);
        context.store().saveScopedMatches(scopeId, "scoped-1", Map.of("ScopedRule", List.of(longExtractedValue)));

        try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(context.databasePath()))) {
            assertAll(
                    () -> TestFixtures.assertSqlCount(connection, MAIN_MESSAGE_TABLE, 0),
                    () -> TestFixtures.assertSqlCount(connection, MAIN_MATCH_TABLE, 0),
                    () -> TestFixtures.assertSqlCount(connection, SCOPED_SCOPE_TABLE, 1),
                    () -> TestFixtures.assertSqlCount(connection, SCOPED_MESSAGE_TABLE, 1),
                    () -> TestFixtures.assertSqlCount(connection, SCOPED_MATCH_TABLE, 1),
                    () -> assertEquals(longExtractedValue, singleString(connection,
                            "SELECT extracted_value FROM scoped_databoard_match WHERE scope_id = ? AND scoped_message_id = ?",
                            scopeId,
                            "scoped-1"))
            );
        }
    }

    @Test
    void scopedQueriesReturnOnlySelectedMessagesAndLeaveMainDataUnchanged() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("selected-query"));

        saveMainMessage(context.store(), "main-1", "https://alpha.example.test/one", "alpha.example.test", "alpha-value");
        saveMainMessage(context.store(), "main-2", "https://alpha.example.test/two", "alpha.example.test", "beta-value");
        saveMainMessage(context.store(), "main-3", "https://beta.example.test/three", "beta.example.test", "outside-value");

        String scopeId = context.store().createScopedDataboardScope("selected", "two messages");
        assertNotNull(scopeId);
        saveScopedMessageWithHost(context.store(), scopeId, "scoped-main-1", "alpha.example.test", "alpha-value");
        saveScopedMessageWithHost(context.store(), scopeId, "scoped-main-2", "alpha.example.test", "beta-value");

        Map<String, List<String>> scopedData = context.store().loadScopedExtractedData(scopeId, "alpha.example.test", "ScopedRule", "*");
        List<SqliteMessageStore.MessageMetadata> scopedMetadata = context.store().loadScopedMessageMetadataPage(
                scopeId,
                "alpha.example.test",
                "",
                "ScopedRule",
                "*",
                10,
                0
        );
        List<SqliteMessageStore.MessageMetadata> scopedAlphaOnly = context.store().loadScopedMessageMetadataPage(
                scopeId,
                "alpha.example.test",
                "",
                "ScopedRule",
                "alpha-value",
                10,
                0
        );
        Map<String, List<String>> mainData = context.store().loadExtractedDataByHost("alpha.example.test");
        HttpRequestResponse scopedMessage = context.store().loadScopedMessage(scopeId, "scoped-main-1");
        byte[] expectedRequestBytes = TestFixtures.minimalHttpRequestBytes();
        byte[] expectedResponseBytes = TestFixtures.minimalHttpResponseBytes();

        assertAll(
                () -> assertEquals(List.of("alpha-value", "beta-value"), scopedData.get("ScopedRule")),
                () -> assertFalse(scopedData.get("ScopedRule").contains("outside-value")),
                () -> assertEquals(2, context.store().countScopedMessageMetadata(scopeId, "alpha.example.test", "", "ScopedRule", "*")),
                () -> assertEquals(List.of("scoped-main-1", "scoped-main-2"), metadataIds(scopedMetadata)),
                () -> assertEquals(List.of("scoped-main-1"), metadataIds(scopedAlphaOnly)),
                () -> assertEquals(List.of("alpha-value", "beta-value"), mainData.get("MainRule")),
                () -> assertFalse(mainData.get("MainRule").contains("outside-value")),
                () -> assertScopedMessageBytes(scopedMessage, expectedRequestBytes, expectedResponseBytes)
        );

        context.store().deleteAllMessages();
        HttpRequestResponse scopedMessageAfterMainClear = context.store().loadScopedMessage(scopeId, "scoped-main-1");
        Map<String, List<String>> scopedDataAfterMainClear = context.store().loadScopedExtractedData(scopeId, "alpha.example.test", "ScopedRule", "*");

        try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(context.databasePath()))) {
            assertAll(
                    () -> TestFixtures.assertSqlCount(connection, MAIN_MESSAGE_TABLE, 0),
                    () -> TestFixtures.assertSqlCount(connection, MAIN_MATCH_TABLE, 0),
                    () -> TestFixtures.assertSqlCount(connection, SCOPED_SCOPE_TABLE, 1),
                    () -> TestFixtures.assertSqlCount(connection, SCOPED_MESSAGE_TABLE, 2),
                    () -> TestFixtures.assertSqlCount(connection, SCOPED_MATCH_TABLE, 2),
                    () -> assertScopedMessageBytes(scopedMessageAfterMainClear, expectedRequestBytes, expectedResponseBytes),
                    () -> assertEquals(List.of("alpha-value", "beta-value"), scopedDataAfterMainClear.get("ScopedRule"))
            );
        }
    }

    @Test
    void task11MainMatchTruncationRegressionRoundTripsFiftyKilobyteExtractedValue() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("main-large-value"));
        String largeValue = extractedValueOfSize("main-task11-", 50 * 1024);

        context.store().saveMessage(
                "large-main-1",
                minimalRequestResponse(),
                "https://large-main.example.test/path",
                "POST",
                "201",
                String.valueOf(largeValue.length()),
                "main large comment",
                "cyan",
                "large-main-hash",
                Map.of("LargeMainRule", List.of(largeValue))
        );

        Map<String, List<String>> mainData = context.store().loadExtractedDataByHost("large-main.example.test");
        List<SqliteMessageStore.MessageMetadata> filteredMetadata = context.store().loadMessageMetadataPage(
                "large-main.example.test",
                "",
                "LargeMainRule",
                largeValue,
                10,
                0
        );
        String storedValue;
        try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(context.databasePath()))) {
            storedValue = singleString(connection,
                    "SELECT extracted_value FROM message_match WHERE message_id = ? AND rule_name = ?",
                    "large-main-1",
                    "LargeMainRule");
        }

        assertAll(
                () -> assertEquals(50 * 1024, largeValue.length()),
                () -> assertEquals(largeValue.length(), storedValue.length()),
                () -> assertEquals(largeValue, storedValue),
                () -> assertEquals(List.of(largeValue), mainData.get("LargeMainRule")),
                () -> assertEquals(largeValue.length(), mainData.get("LargeMainRule").get(0).length()),
                () -> assertEquals(1, context.store().countMessageMetadata("large-main.example.test", "", "LargeMainRule", largeValue)),
                () -> assertEquals(List.of("large-main-1"), metadataIds(filteredMetadata))
        );
    }

    @Test
    void task11ScopedMatchTruncationRegressionRoundTripsFiftyKilobyteExtractedValue() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("large-value"));
        String scopeId = context.store().createScopedDataboardScope("large", "50kb value");
        assertNotNull(scopeId);
        context.store().saveScopedMessage(
                scopeId,
                "large-scoped-1",
                minimalRequestResponse(),
                "https://large.example.test/path",
                "large.example.test",
                "GET",
                "200",
                "42",
                "large scoped comment",
                "orange",
                "large-scoped-hash"
        );
        String largeValue = extractedValueOfSize("scoped-task11-", 50 * 1024);

        context.store().saveScopedMatches(scopeId, "large-scoped-1", Map.of("LargeRule", List.of(largeValue)));

        Map<String, List<String>> scopedData = context.store().loadScopedExtractedData(scopeId, "large.example.test", "LargeRule", largeValue);
        List<SqliteMessageStore.MessageMetadata> scopedMetadata = context.store().loadScopedMessageMetadataPage(
                scopeId,
                "large.example.test",
                "",
                "LargeRule",
                largeValue,
                10,
                0
        );
        String storedValue;
        try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(context.databasePath()))) {
            storedValue = singleString(connection,
                    "SELECT extracted_value FROM scoped_databoard_match WHERE scope_id = ? AND scoped_message_id = ? AND rule_name = 'LargeRule'",
                    scopeId,
                    "large-scoped-1");
        }

        assertAll(
                () -> assertEquals(50 * 1024, largeValue.length()),
                () -> assertEquals(largeValue.length(), storedValue.length()),
                () -> assertEquals(largeValue, storedValue),
                () -> assertEquals(List.of(largeValue), scopedData.get("LargeRule")),
                () -> assertEquals(largeValue.length(), scopedData.get("LargeRule").get(0).length()),
                () -> assertEquals(List.of("large-scoped-1"), metadataIds(scopedMetadata))
        );
    }

    @Test
    void deleteScopedScopeRemovesOnlyThatScopeAndLeavesMainHistory() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("cleanup"));
        context.store().saveMessage(
                "main-1",
                minimalRequestResponse(),
                "https://example.test/main",
                "GET",
                "200",
                "42",
                "main comment",
                "red",
                "main-content-hash",
                Map.of("MainRule", List.of("main extracted value"))
        );

        String firstScopeId = context.store().createScopedDataboardScope("cleanup", "delete-me");
        String secondScopeId = context.store().createScopedDataboardScope("cleanup", "keep-me");
        assertNotNull(firstScopeId);
        assertNotNull(secondScopeId);
        saveOneScopedMessageWithMatch(context.store(), firstScopeId, "first-message", "first-value");
        saveOneScopedMessageWithMatch(context.store(), secondScopeId, "second-message", "second-value");

        assertEquals(1, context.store().deleteScopedDataboardScope(firstScopeId));

        try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(context.databasePath()))) {
            assertAll(
                    () -> TestFixtures.assertSqlCount(connection, MAIN_MESSAGE_TABLE, 1),
                    () -> TestFixtures.assertSqlCount(connection, MAIN_MATCH_TABLE, 1),
                    () -> TestFixtures.assertSqlCount(connection, SCOPED_SCOPE_TABLE, 1),
                    () -> TestFixtures.assertSqlCount(connection, SCOPED_MESSAGE_TABLE, 1),
                    () -> TestFixtures.assertSqlCount(connection, SCOPED_MATCH_TABLE, 1),
                    () -> assertEquals(0, scopedRowCount(connection, SCOPED_SCOPE_TABLE, firstScopeId)),
                    () -> assertEquals(0, scopedRowCount(connection, SCOPED_MESSAGE_TABLE, firstScopeId)),
                    () -> assertEquals(0, scopedRowCount(connection, SCOPED_MATCH_TABLE, firstScopeId)),
                    () -> assertEquals(1, scopedRowCount(connection, SCOPED_SCOPE_TABLE, secondScopeId)),
                    () -> assertEquals(1, scopedRowCount(connection, SCOPED_MESSAGE_TABLE, secondScopeId)),
                    () -> assertEquals(1, scopedRowCount(connection, SCOPED_MATCH_TABLE, secondScopeId))
            );
        }
    }

    private StoreContext createStoreContext(Path home) throws Exception {
        Files.createDirectories(home.resolve(".config").resolve("HaE"));
        String originalHome = System.getProperty("user.home");
        MontoyaApi api = proxyFor(MontoyaApi.class);
        try {
            System.setProperty("user.home", home.toString());
            ConfigLoader configLoader = new ConfigLoader(api);
            return new StoreContext(new SqliteMessageStore(api, configLoader), haeDatabasePath(home));
        } finally {
            if (originalHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", originalHome);
            }
        }
    }

    private static Path haeDatabasePath(Path home) {
        return home.resolve(".config").resolve("HaE").resolve("History.db");
    }

    private static void saveOneScopedMessageWithMatch(SqliteMessageStore store,
                                                      String scopeId,
                                                      String scopedMessageId,
                                                      String extractedValue) {
        store.saveScopedMessage(
                scopeId,
                scopedMessageId,
                minimalRequestResponse(),
                "https://example.test/" + scopedMessageId,
                "example.test",
                "GET",
                "200",
                "42",
                "scoped comment",
                "blue",
                "hash-" + scopedMessageId
        );
        store.saveScopedMatches(scopeId, scopedMessageId, Map.of("ScopedRule", List.of(extractedValue)));
    }

    private static void saveMainMessage(SqliteMessageStore store,
                                        String messageId,
                                        String url,
                                        String host,
                                        String extractedValue) {
        store.saveMessage(
                messageId,
                minimalRequestResponse(),
                url,
                "GET",
                "200",
                "42",
                "main comment " + messageId,
                "green",
                "main-hash-" + messageId,
                Map.of("MainRule", List.of(extractedValue))
        );
    }

    private static void saveScopedMessageWithHost(SqliteMessageStore store,
                                                  String scopeId,
                                                  String scopedMessageId,
                                                  String host,
                                                  String extractedValue) {
        store.saveScopedMessage(
                scopeId,
                scopedMessageId,
                minimalRequestResponse(),
                "https://" + host + "/" + scopedMessageId,
                host,
                "GET",
                "200",
                "42",
                "scoped comment " + scopedMessageId,
                "purple",
                "scoped-hash-" + scopedMessageId
        );
        store.saveScopedMatches(scopeId, scopedMessageId, Map.of("ScopedRule", List.of(extractedValue)));
    }

    private static List<String> metadataIds(List<SqliteMessageStore.MessageMetadata> metadataRows) {
        List<String> result = new ArrayList<>();
        for (SqliteMessageStore.MessageMetadata metadata : metadataRows) {
            result.add(metadata.getMessageId());
        }
        return result;
    }

    private static void assertScopedMessageBytes(HttpRequestResponse scopedMessage,
                                                 byte[] expectedRequestBytes,
                                                 byte[] expectedResponseBytes) {
        assertNotNull(scopedMessage);
        ByteArray requestByteArray = scopedMessage.request().toByteArray();
        ByteArray responseByteArray = scopedMessage.response().toByteArray();
        assertAll(
                () -> assertEquals(expectedRequestBytes.length, requestByteArray.length()),
                () -> assertEquals(expectedResponseBytes.length, responseByteArray.length()),
                () -> assertArrayEquals(expectedRequestBytes, requestByteArray.getBytes()),
                () -> assertArrayEquals(expectedResponseBytes, responseByteArray.getBytes())
        );
    }

    private static HttpRequestResponse minimalRequestResponse() {
        HttpService service = httpServiceProxy();
        HttpRequest request = httpRequestProxy(service, TestFixtures.minimalHttpRequestBytes());
        HttpResponse response = httpResponseProxy(TestFixtures.minimalHttpResponseBytes());
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "request" -> request;
            case "response" -> response;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpRequestResponse.class, handler);
    }

    private static HttpService httpServiceProxy() {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "host" -> "example.test";
            case "port" -> 443;
            case "secure" -> true;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpService.class, handler);
    }

    private static HttpRequest httpRequestProxy(HttpService service, byte[] requestBytes) {
        ByteArray byteArray = byteArrayProxy(requestBytes);
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "httpService" -> service;
            case "toByteArray" -> byteArray;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpRequest.class, handler);
    }

    private static HttpResponse httpResponseProxy(byte[] responseBytes) {
        ByteArray byteArray = byteArrayProxy(responseBytes);
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "toByteArray" -> byteArray;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpResponse.class, handler);
    }

    private static ByteArray byteArrayProxy(byte[] bytes) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getBytes" -> bytes;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(ByteArray.class, handler);
    }

    private static void createExistingMainTables(Path databasePath) throws SQLException {
        try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(databasePath));
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE message_history (
                        message_id TEXT PRIMARY KEY,
                        created_at INTEGER NOT NULL,
                        host TEXT NOT NULL,
                        url TEXT NOT NULL,
                        method TEXT NOT NULL,
                        status TEXT NOT NULL,
                        length TEXT NOT NULL,
                        comment TEXT NOT NULL,
                        color TEXT NOT NULL,
                        content_hash TEXT NOT NULL,
                        service_host TEXT NOT NULL,
                        service_port INTEGER NOT NULL,
                        service_secure INTEGER NOT NULL,
                        request_bytes BLOB NOT NULL,
                        response_bytes BLOB NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE message_match (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        message_id TEXT NOT NULL,
                        rule_name TEXT NOT NULL,
                        extracted_value TEXT NOT NULL
                    )
                    """);
            try (PreparedStatement insertMessage = connection.prepareStatement("""
                         INSERT INTO message_history (
                             message_id, created_at, host, url, method, status, length, comment, color,
                             content_hash, service_host, service_port, service_secure, request_bytes, response_bytes
                         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                         """);
                 PreparedStatement insertMatch = connection.prepareStatement("""
                         INSERT INTO message_match (message_id, rule_name, extracted_value)
                         VALUES (?, ?, ?)
                         """)) {
                insertMessage.setString(1, "existing-main-1");
                insertMessage.setLong(2, System.currentTimeMillis());
                insertMessage.setString(3, "example.test");
                insertMessage.setString(4, "https://example.test/existing");
                insertMessage.setString(5, "GET");
                insertMessage.setString(6, "200");
                insertMessage.setString(7, "42");
                insertMessage.setString(8, "");
                insertMessage.setString(9, "none");
                insertMessage.setString(10, "existing-content-hash");
                insertMessage.setString(11, "example.test");
                insertMessage.setInt(12, 443);
                insertMessage.setInt(13, 1);
                insertMessage.setBytes(14, TestFixtures.minimalHttpRequestBytes());
                insertMessage.setBytes(15, TestFixtures.minimalHttpResponseBytes());
                insertMessage.executeUpdate();

                insertMatch.setString(1, "existing-main-1");
                insertMatch.setString(2, "ExistingRule");
                insertMatch.setString(3, "existing extracted value");
                insertMatch.executeUpdate();
            }
        }
    }

    private static void assertExistingMainCounts(Path databasePath, long expectedMessages, long expectedMatches) throws SQLException {
        try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(databasePath))) {
            TestFixtures.assertSqlCount(connection, MAIN_MESSAGE_TABLE, expectedMessages);
            TestFixtures.assertSqlCount(connection, MAIN_MATCH_TABLE, expectedMatches);
        }
    }

    private static void assertSqliteObjectExists(Connection connection, String type, String name) throws SQLException {
        assertTrue(sqliteObjectExists(connection, type, name), () -> "Missing SQLite " + type + ": " + name);
    }

    private static void assertSqliteObjectMissing(Connection connection, String type, String name) throws SQLException {
        assertFalse(sqliteObjectExists(connection, type, name), () -> "Unexpected SQLite " + type + ": " + name);
    }

    private static boolean sqliteObjectExists(Connection connection, String type, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = ? AND name = ?")) {
            statement.setString(1, type);
            statement.setString(2, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static void assertSqlColumnExists(Connection connection, String tableName, String columnName) throws SQLException {
        assertTrue(sqlColumnExists(connection, tableName, columnName),
                () -> "Missing SQLite column: " + tableName + "." + columnName);
    }

    private static boolean sqlColumnExists(Connection connection, String tableName, String columnName) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (resultSet.next()) {
                if (columnName.equalsIgnoreCase(resultSet.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static long scopedRowCount(Connection connection, String tableName, String scopeId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + tableName + " WHERE scope_id = ?")) {
            statement.setString(1, scopeId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0;
            }
        }
    }

    private static String singleString(Connection connection, String sql, String firstParameter, String secondParameter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, firstParameter);
            statement.setString(2, secondParameter);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private static String extractedValueOfSize(String prefix, int targetLength) {
        if (prefix.length() > targetLength) {
            throw new IllegalArgumentException("Prefix is longer than target length");
        }
        return prefix + "x".repeat(targetLength - prefix.length());
    }

    private static <T> T proxyFor(Class<T> type) {
        return proxyFor(type, new DefaultProxyHandler());
    }

    private static <T> T proxyFor(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static Object defaultProxyValue(Object proxy, Method method, Object[] args) {
        if (method.getDeclaringClass() == Object.class) {
            return objectMethod(proxy, method, args);
        }

        Class<?> returnType = method.getReturnType();
        if (returnType == Void.TYPE) {
            return null;
        }
        if (returnType == Boolean.TYPE) {
            return false;
        }
        if (returnType == Integer.TYPE) {
            return 0;
        }
        if (returnType == Long.TYPE) {
            return 0L;
        }
        if (returnType == String.class) {
            return "";
        }
        if (returnType.isInterface()) {
            return proxyFor(returnType);
        }
        return null;
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "test proxy for " + proxy.getClass().getInterfaces()[0].getName();
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> null;
        };
    }

    private record StoreContext(SqliteMessageStore store, Path databasePath) {
    }

    private static class DefaultProxyHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return defaultProxyValue(proxy, method, args);
        }
    }
}
