package hae.storage;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import hae.utils.ConfigLoader;
import hae.utils.string.StringProcessor;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class SqliteMessageStore {
    private static final String TABLE_NAME = "message_history";
    private static final String MATCH_TABLE_NAME = "message_match";

    private final MontoyaApi api;
    private final String jdbcUrl;
    private final String dbPath;
    private final AtomicBoolean connectionUnavailableLogged = new AtomicBoolean(false);
    private volatile boolean connectionUnavailable = false;
    private SQLiteDataSource sqliteDataSource;

    public SqliteMessageStore(MontoyaApi api, ConfigLoader configLoader) {
        this.api = api;
        Path databasePath = resolveDatabasePath(configLoader);
        this.dbPath = databasePath.toAbsolutePath().toString();
        this.jdbcUrl = "jdbc:sqlite:" + this.dbPath;
        initializeDatabase();
    }

    public static class MessageMetadata {
        private final String messageId;
        private final String method;
        private final String url;
        private final String comment;
        private final String length;
        private final String color;
        private final String status;
        private final String contentHash;

        public MessageMetadata(String messageId, String method, String url, String comment,
                               String length, String color, String status, String contentHash) {
            this.messageId = messageId;
            this.method = method;
            this.url = url;
            this.comment = comment;
            this.length = length;
            this.color = color;
            this.status = status;
            this.contentHash = contentHash;
        }

        public String getMessageId() {
            return messageId;
        }

        public String getMethod() {
            return method;
        }

        public String getUrl() {
            return url;
        }

        public String getComment() {
            return comment;
        }

        public String getLength() {
            return length;
        }

        public String getColor() {
            return color;
        }

        public String getStatus() {
            return status;
        }

        public String getContentHash() {
            return contentHash;
        }
    }

    private Path resolveDatabasePath(ConfigLoader configLoader) {
        try {
            Path rulesPath = Paths.get(configLoader.getRulesFilePath());
            Path configDir = rulesPath.getParent();
            if (configDir == null) {
                configDir = Paths.get(System.getProperty("user.home"), ".config", "HaE");
            }
            Files.createDirectories(configDir);
            return configDir.resolve("History.db");
        } catch (Exception e) {
            api.logging().logToError("resolveDatabasePath: " + e.getMessage());
            Path fallbackDir = Paths.get(System.getProperty("user.home"), ".config", "HaE");
            try {
                Files.createDirectories(fallbackDir);
            } catch (Exception ignored) {
            }
            return fallbackDir.resolve("History.db");
        }
    }

    private Connection getConnection() throws SQLException {
        if (connectionUnavailable) {
            throw new SQLException("SQLite datasource unavailable");
        }

        try {
            if (sqliteDataSource == null) {
                sqliteDataSource = new SQLiteDataSource();
                sqliteDataSource.setUrl(jdbcUrl);
            }
            return sqliteDataSource.getConnection();
        } catch (Throwable e) {
            connectionUnavailable = true;
            if (connectionUnavailableLogged.compareAndSet(false, true)) {
                api.logging().logToError("SQLite initialize failed: " + e.getMessage());
            }
            throw new SQLException("SQLite datasource unavailable", e);
        }
    }

    private void logDatabaseError(String methodName, Exception exception) {
        String message = exception == null ? "unknown" : exception.getMessage();
        if (connectionUnavailable && connectionUnavailableLogged.get()) {
            return;
        }
        api.logging().logToError(methodName + ": " + message);
    }

    private void initializeDatabase() {
        String createTableSql = String.format("""
                CREATE TABLE IF NOT EXISTS %s (
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
                """, TABLE_NAME);

        String createMatchTableSql = String.format("""
                CREATE TABLE IF NOT EXISTS %s (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    message_id TEXT NOT NULL,
                    rule_name TEXT NOT NULL,
                    extracted_value TEXT NOT NULL
                )
                """, MATCH_TABLE_NAME);

        String createCreatedAtIndex = String.format("CREATE INDEX IF NOT EXISTS idx_%s_created_at ON %s(created_at)", TABLE_NAME, TABLE_NAME);
        String createHostIndex = String.format("CREATE INDEX IF NOT EXISTS idx_%s_host ON %s(host)", TABLE_NAME, TABLE_NAME);
        String createHashIndex = String.format("CREATE INDEX IF NOT EXISTS idx_%s_hash ON %s(content_hash)", TABLE_NAME, TABLE_NAME);
        String createMatchMessageIndex = String.format("CREATE INDEX IF NOT EXISTS idx_%s_message_id ON %s(message_id)", MATCH_TABLE_NAME, MATCH_TABLE_NAME);
        String createMatchRuleValueIndex = String.format("CREATE INDEX IF NOT EXISTS idx_%s_rule_value ON %s(rule_name, extracted_value)", MATCH_TABLE_NAME, MATCH_TABLE_NAME);

        try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute(createTableSql);
            statement.execute(createMatchTableSql);
            statement.execute(createCreatedAtIndex);
            statement.execute(createHostIndex);
            statement.execute(createHashIndex);
            statement.execute(createMatchMessageIndex);
            statement.execute(createMatchRuleValueIndex);
        } catch (Exception e) {
            logDatabaseError("initializeDatabase", e);
        }
    }

    public synchronized void saveMessage(String messageId,
                                         HttpRequestResponse messageInfo,
                                         String url,
                                         String method,
                                         String status,
                                         String length,
                                         String comment,
                                         String color,
                                         String contentHash,
                                         Map<String, List<String>> extractedDataByRule) {
        if (messageInfo == null) {
            return;
        }

        try {
            HttpRequest request = messageInfo.request();
            HttpResponse response = messageInfo.response();
            if (request == null || response == null) {
                return;
            }

            HttpService service = request.httpService();
            if (service == null) {
                return;
            }

            String host = StringProcessor.getHostByUrl(url);
            if (host == null || host.isBlank()) {
                host = service.host();
            }

            byte[] requestBytes = request.toByteArray().getBytes();
            byte[] responseBytes = response.toByteArray().getBytes();

            String insertSql = String.format("""
                    INSERT INTO %s (
                        message_id, created_at, host, url, method, status, length, comment, color, content_hash,
                        service_host, service_port, service_secure, request_bytes, response_bytes
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, TABLE_NAME);

            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement(insertSql);
                 PreparedStatement deleteMatchStatement = connection.prepareStatement(String.format("DELETE FROM %s WHERE message_id = ?", MATCH_TABLE_NAME));
                 PreparedStatement insertMatchStatement = connection.prepareStatement(String.format("INSERT INTO %s (message_id, rule_name, extracted_value) VALUES (?, ?, ?)", MATCH_TABLE_NAME))) {

                connection.setAutoCommit(false);

                statement.setString(1, messageId);
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, host == null ? "" : host);
                statement.setString(4, url == null ? "" : url);
                statement.setString(5, method == null ? "" : method);
                statement.setString(6, status == null ? "" : status);
                statement.setString(7, length == null ? "" : length);
                statement.setString(8, comment == null ? "" : comment);
                statement.setString(9, color == null ? "" : color);
                statement.setString(10, contentHash == null ? "" : contentHash);
                statement.setString(11, service.host());
                statement.setInt(12, service.port());
                statement.setInt(13, service.secure() ? 1 : 0);
                statement.setBytes(14, requestBytes);
                statement.setBytes(15, responseBytes);
                statement.executeUpdate();

                saveMatchData(messageId, extractedDataByRule, deleteMatchStatement, insertMatchStatement);

                connection.commit();
            } catch (Exception e) {
                logDatabaseError("saveMessage", e);
            }
        } catch (Exception e) {
            logDatabaseError("saveMessage", e);
        }
    }

    private void saveMatchData(String messageId,
                               Map<String, List<String>> extractedDataByRule,
                               PreparedStatement deleteMatchStatement,
                               PreparedStatement insertMatchStatement) throws SQLException {
        deleteMatchStatement.setString(1, messageId);
        deleteMatchStatement.executeUpdate();

        if (extractedDataByRule == null || extractedDataByRule.isEmpty()) {
            return;
        }

        for (Map.Entry<String, List<String>> entry : extractedDataByRule.entrySet()) {
            String ruleName = entry.getKey();
            if (ruleName == null || ruleName.isBlank()) {
                continue;
            }

            List<String> extractedValues = entry.getValue();
            if (extractedValues == null || extractedValues.isEmpty()) {
                continue;
            }

            for (String extractedValue : extractedValues) {
                if (extractedValue == null || extractedValue.isBlank()) {
                    continue;
                }

                insertMatchStatement.setString(1, messageId);
                insertMatchStatement.setString(2, ruleName);
                insertMatchStatement.setString(3, extractedValue);
                insertMatchStatement.addBatch();
            }
        }

        insertMatchStatement.executeBatch();
    }

    public synchronized List<MessageMetadata> loadMessageMetadata() {
        List<MessageMetadata> result = new ArrayList<>();
        String querySql = String.format("""
                SELECT message_id, method, url, comment, length, color, status, content_hash
                FROM %s
                ORDER BY created_at ASC
                """, TABLE_NAME);

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(querySql);
             ResultSet resultSet = statement.executeQuery()) {

            while (resultSet.next()) {
                result.add(new MessageMetadata(
                        resultSet.getString("message_id"),
                        resultSet.getString("method"),
                        resultSet.getString("url"),
                        resultSet.getString("comment"),
                        resultSet.getString("length"),
                        resultSet.getString("color"),
                        resultSet.getString("status"),
                        resultSet.getString("content_hash")
                ));
            }
        } catch (Exception e) {
            logDatabaseError("loadMessageMetadata", e);
        }

        return result;
    }

    public synchronized boolean existsDuplicate(String url, String comment, String color, String contentHash) {
        String querySql = String.format("""
                SELECT 1
                FROM %s
                WHERE url = ? AND comment = ? AND color = ? AND content_hash = ?
                LIMIT 1
                """, TABLE_NAME);

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(querySql)) {
            statement.setString(1, url == null ? "" : url);
            statement.setString(2, comment == null ? "" : comment);
            statement.setString(3, color == null ? "" : color);
            statement.setString(4, contentHash == null ? "" : contentHash);

            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (Exception e) {
            logDatabaseError("existsDuplicate", e);
            return false;
        }
    }

    public synchronized int countMessageMetadata(String hostPattern, String commentKeyword) {
        return countMessageMetadata(hostPattern, commentKeyword, "", "");
    }

    public synchronized int countMessageMetadata(String hostPattern, String commentKeyword, String messageTableName, String messageFilterValue) {
        StringBuilder querySql = new StringBuilder(String.format("SELECT COUNT(*) AS total FROM %s WHERE 1=1", TABLE_NAME));
        List<Object> parameters = new ArrayList<>();
        appendFilterClause(querySql, parameters, hostPattern, commentKeyword, messageTableName, messageFilterValue);

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(querySql.toString())) {
            bindParameters(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getInt("total");
                }
            }
        } catch (Exception e) {
            logDatabaseError("countMessageMetadata", e);
        }

        return 0;
    }

    public synchronized List<MessageMetadata> loadMessageMetadataPage(String hostPattern, String commentKeyword, int limit, int offset) {
        return loadMessageMetadataPage(hostPattern, commentKeyword, "", "", limit, offset);
    }

    public synchronized List<MessageMetadata> loadMessageMetadataPage(String hostPattern,
                                                                      String commentKeyword,
                                                                      String messageTableName,
                                                                      String messageFilterValue,
                                                                      int limit,
                                                                      int offset) {
        int safeLimit = Math.max(1, limit);
        int safeOffset = Math.max(0, offset);

        StringBuilder querySql = new StringBuilder(String.format("""
                SELECT message_id, method, url, comment, length, color, status, content_hash
                FROM %s
                WHERE 1=1
                """, TABLE_NAME));
        List<Object> parameters = new ArrayList<>();
        appendFilterClause(querySql, parameters, hostPattern, commentKeyword, messageTableName, messageFilterValue);
        querySql.append(" ORDER BY created_at ASC, message_id ASC LIMIT ? OFFSET ?");
        parameters.add(safeLimit);
        parameters.add(safeOffset);

        List<MessageMetadata> result = new ArrayList<>();

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(querySql.toString())) {
            bindParameters(statement, parameters);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    result.add(new MessageMetadata(
                            resultSet.getString("message_id"),
                            resultSet.getString("method"),
                            resultSet.getString("url"),
                            resultSet.getString("comment"),
                            resultSet.getString("length"),
                            resultSet.getString("color"),
                            resultSet.getString("status"),
                            resultSet.getString("content_hash")
                    ));
                }
            }
        } catch (Exception e) {
            logDatabaseError("loadMessageMetadataPage", e);
        }

        return result;
    }

    public synchronized List<MessageMetadata> loadMessageMetadataByFilter(String hostPattern, String commentKeyword) {
        return loadMessageMetadataByFilter(hostPattern, commentKeyword, "", "");
    }

    public synchronized List<MessageMetadata> loadMessageMetadataByFilter(String hostPattern,
                                                                          String commentKeyword,
                                                                          String messageTableName,
                                                                          String messageFilterValue) {
        StringBuilder querySql = new StringBuilder(String.format("""
                SELECT message_id, method, url, comment, length, color, status, content_hash
                FROM %s
                WHERE 1=1
                """, TABLE_NAME));

        List<Object> parameters = new ArrayList<>();
        appendFilterClause(querySql, parameters, hostPattern, commentKeyword, messageTableName, messageFilterValue);
        querySql.append(" ORDER BY created_at ASC, message_id ASC");

        List<MessageMetadata> result = new ArrayList<>();
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(querySql.toString())) {
            bindParameters(statement, parameters);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    result.add(new MessageMetadata(
                            resultSet.getString("message_id"),
                            resultSet.getString("method"),
                            resultSet.getString("url"),
                            resultSet.getString("comment"),
                            resultSet.getString("length"),
                            resultSet.getString("color"),
                            resultSet.getString("status"),
                            resultSet.getString("content_hash")
                    ));
                }
            }
        } catch (Exception e) {
            logDatabaseError("loadMessageMetadataByFilter", e);
        }

        return result;
    }

    private void appendFilterClause(StringBuilder querySql,
                                    List<Object> parameters,
                                    String hostPattern,
                                    String commentKeyword,
                                    String messageTableName,
                                    String messageFilterValue) {
        appendHostFilterClause(querySql, parameters, hostPattern);
        appendCommentFilterClause(querySql, parameters, commentKeyword);
        appendMessageFilterClause(querySql, parameters, messageTableName, messageFilterValue);
    }

    private void appendMessageFilterClause(StringBuilder querySql,
                                           List<Object> parameters,
                                           String messageTableName,
                                           String messageFilterValue) {
        boolean hasTableFilter = messageTableName != null && !messageTableName.isBlank() && !isAllToken(messageTableName);
        boolean hasValueFilter = messageFilterValue != null && !messageFilterValue.isBlank() && !isAllToken(messageFilterValue);

        if (!hasTableFilter && !hasValueFilter) {
            return;
        }

        querySql.append(" AND EXISTS (SELECT 1 FROM ")
                .append(MATCH_TABLE_NAME)
                .append(" mm WHERE mm.message_id = ")
                .append(TABLE_NAME)
                .append(".message_id");

        if (hasTableFilter) {
            querySql.append(" AND mm.rule_name = ?");
            parameters.add(messageTableName);
        }

        if (hasValueFilter) {
            querySql.append(" AND mm.extracted_value = ?");
            parameters.add(messageFilterValue);
        }

        querySql.append(")");
    }

    private boolean isAllToken(String value) {
        return "*".equals(value.trim());
    }

    private void appendCommentFilterClause(StringBuilder querySql, List<Object> parameters, String commentKeyword) {
        if (commentKeyword == null || commentKeyword.isBlank()) {
            return;
        }

        querySql.append(" AND instr(comment, ?) > 0");
        parameters.add(commentKeyword);
    }

    private void appendHostFilterClause(StringBuilder querySql, List<Object> parameters, String hostPattern) {
        if (hostPattern == null || hostPattern.isBlank() || "*".equals(hostPattern)) {
            return;
        }

        String normalizedPattern = hostPattern.trim().toLowerCase(Locale.ROOT);
        if (normalizedPattern.startsWith("*.")) {
            String suffix = normalizedPattern.substring(2);
            querySql.append(" AND lower(CASE WHEN instr(host, ':') > 0 THEN substr(host, 1, instr(host, ':') - 1) ELSE host END) LIKE ?");
            parameters.add("%" + suffix);
            return;
        }

        querySql.append(" AND lower(host) LIKE ?");
        parameters.add("%" + normalizedPattern);
    }

    private void bindParameters(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int i = 0; i < parameters.size(); i++) {
            Object parameter = parameters.get(i);
            int parameterIndex = i + 1;

            if (parameter instanceof Integer integerValue) {
                statement.setInt(parameterIndex, integerValue);
            } else {
                statement.setString(parameterIndex, parameter == null ? "" : parameter.toString());
            }
        }
    }

    public synchronized HttpRequestResponse loadMessage(String messageId) {
        String querySql = String.format("""
                SELECT service_host, service_port, service_secure, request_bytes, response_bytes
                FROM %s
                WHERE message_id = ?
                """, TABLE_NAME);

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(querySql)) {
            statement.setString(1, messageId);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }

                String serviceHost = resultSet.getString("service_host");
                int servicePort = resultSet.getInt("service_port");
                boolean serviceSecure = resultSet.getInt("service_secure") == 1;
                byte[] requestBytes = resultSet.getBytes("request_bytes");
                byte[] responseBytes = resultSet.getBytes("response_bytes");

                HttpService httpService = HttpService.httpService(serviceHost, servicePort, serviceSecure);
                HttpRequest httpRequest = HttpRequest.httpRequest(httpService, ByteArray.byteArray(requestBytes));
                HttpResponse httpResponse = HttpResponse.httpResponse(ByteArray.byteArray(responseBytes));

                return HttpRequestResponse.httpRequestResponse(httpRequest, httpResponse);
            }
        } catch (Exception e) {
            logDatabaseError("loadMessage", e);
            return null;
        }
    }

    public synchronized int deleteByHostPattern(String hostPattern) {
        if (hostPattern == null || hostPattern.isBlank()) {
            return 0;
        }

        if ("*".equals(hostPattern)) {
            return deleteAllMessages();
        }

        List<String> idsToDelete = new ArrayList<>();
        String querySql = String.format("SELECT message_id, host FROM %s", TABLE_NAME);

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(querySql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String host = resultSet.getString("host");
                if (host != null && StringProcessor.matchesHostPattern(host, hostPattern)) {
                    idsToDelete.add(resultSet.getString("message_id"));
                }
            }
        } catch (Exception e) {
            logDatabaseError("deleteByHostPattern(select)", e);
            return 0;
        }

        if (idsToDelete.isEmpty()) {
            return 0;
        }

        String deleteSql = String.format("DELETE FROM %s WHERE message_id = ?", TABLE_NAME);
        String deleteMatchSql = String.format("DELETE FROM %s WHERE message_id = ?", MATCH_TABLE_NAME);
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(deleteSql);
             PreparedStatement deleteMatchStatement = connection.prepareStatement(deleteMatchSql)) {
            connection.setAutoCommit(false);
            for (String id : idsToDelete) {
                statement.setString(1, id);
                statement.addBatch();

                deleteMatchStatement.setString(1, id);
                deleteMatchStatement.addBatch();
            }
            deleteMatchStatement.executeBatch();
            statement.executeBatch();
            connection.commit();
            return idsToDelete.size();
        } catch (Exception e) {
            logDatabaseError("deleteByHostPattern(delete)", e);
            return 0;
        }
    }

    public synchronized int deleteAllMessages() {
        String deleteSql = String.format("DELETE FROM %s", TABLE_NAME);
        String deleteMatchSql = String.format("DELETE FROM %s", MATCH_TABLE_NAME);
        try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeUpdate(deleteMatchSql);
            int deletedRows = statement.executeUpdate(deleteSql);
            connection.commit();
            return deletedRows;
        } catch (Exception e) {
            logDatabaseError("deleteAllMessages", e);
            return 0;
        }
    }

    public String getDatabasePath() {
        return dbPath;
    }
}
