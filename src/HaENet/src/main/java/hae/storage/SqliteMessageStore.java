package hae.storage;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import hae.ai.AiQueueCounts;
import hae.ai.AiTriageResultQuality;
import hae.repository.AiResultRepository;
import hae.repository.AiTaskRepository;
import hae.repository.ExtractedDataRepository;
import hae.repository.MessageRepository;
import hae.repository.RegexWorkRepository;
import hae.repository.ScopedDataboardRepository;
import hae.repository.StorageMaintenanceRepository;
import hae.utils.ConfigLoader;
import hae.utils.string.StringProcessor;
import org.sqlite.SQLiteDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class SqliteMessageStore implements MessageRepository,
        RegexWorkRepository,
        ExtractedDataRepository,
        ScopedDataboardRepository,
        AiTaskRepository,
        AiResultRepository,
        StorageMaintenanceRepository {
    private static final String TABLE_NAME = "message_history";
    private static final String MATCH_TABLE_NAME = "message_match";
    private static final String SCOPED_SCOPE_TABLE_NAME = "scoped_databoard_scope";
    private static final String SCOPED_MESSAGE_TABLE_NAME = "scoped_databoard_message";
    private static final String SCOPED_MATCH_TABLE_NAME = "scoped_databoard_match";
    private static final String AI_TRIAGE_TASK_TABLE_NAME = "ai_triage_task";
    private static final String AI_TRIAGE_RESULT_TABLE_NAME = "ai_triage_result";
    private static final String REGEX_STATUS_PENDING = "PENDING";
    private static final String REGEX_STATUS_PROCESSING = "PROCESSING";
    private static final String REGEX_STATUS_DONE = "DONE";
    private static final String REGEX_STATUS_FAILED = "FAILED";
    private static final String AI_STATUS_PENDING = "PENDING";
    private static final String AI_STATUS_LEASED = "LEASED";
    private static final String AI_STATUS_RUNNING = "RUNNING";
    private static final String AI_STATUS_DONE = "DONE";
    private static final String AI_STATUS_FAILED = "FAILED";
    private static final String AI_RESULT_STATUS_SKIPPED = "SKIPPED";
    private static final int MAX_REGEX_ATTEMPTS = 3;
    private static final int MAX_REGEX_ERROR_LENGTH = 1000;
    private static final int MAX_AI_ERROR_LENGTH = 1000;

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

    public static class StoredMessage {
        private final String messageId;
        private final String host;
        private final String contentHash;
        private final HttpRequestResponse requestResponse;

        private StoredMessage(String messageId, String host, String contentHash, HttpRequestResponse requestResponse) {
            this.messageId = messageId;
            this.host = host;
            this.contentHash = contentHash;
            this.requestResponse = requestResponse;
        }

        public String getMessageId() {
            return messageId;
        }

        public String getHost() {
            return host;
        }

        public String getContentHash() {
            return contentHash;
        }

        public HttpRequestResponse getRequestResponse() {
            return requestResponse;
        }
    }

    public static class PendingMessageSaveResult {
        private final String messageId;
        private final boolean saved;
        private final boolean duplicate;

        private PendingMessageSaveResult(String messageId, boolean saved, boolean duplicate) {
            this.messageId = messageId;
            this.saved = saved;
            this.duplicate = duplicate;
        }

        public String getMessageId() {
            return messageId;
        }

        public boolean isSaved() {
            return saved;
        }

        public boolean isDuplicate() {
            return duplicate;
        }
    }

    public static class AiTriageTask {
        private final String taskId;
        private final String messageId;
        private final String contentHash;
        private final String analysisKey;
        private final String matchSignatureHash;
        private final String schemaVersion;
        private final String promptVersion;
        private final String model;
        private final String configHash;
        private final String status;
        private final int priority;
        private final int attemptCount;
        private final int maxAttempts;
        private final long nextAttemptAt;
        private final String lastErrorCode;
        private final String lastErrorMessageSanitized;
        private final long leasedUntil;
        private final String leaseOwner;
        private final String leaseToken;
        private final long createdAt;
        private final long updatedAt;

        private AiTriageTask(String taskId,
                            String messageId,
                            String contentHash,
                            String analysisKey,
                            String matchSignatureHash,
                            String schemaVersion,
                            String promptVersion,
                            String model,
                            String configHash,
                            String status,
                            int priority,
                            int attemptCount,
                            int maxAttempts,
                            long nextAttemptAt,
                            String lastErrorCode,
                            String lastErrorMessageSanitized,
                            long leasedUntil,
                            String leaseOwner,
                            String leaseToken,
                            long createdAt,
                            long updatedAt) {
            this.taskId = taskId;
            this.messageId = messageId;
            this.contentHash = contentHash;
            this.analysisKey = analysisKey;
            this.matchSignatureHash = matchSignatureHash;
            this.schemaVersion = schemaVersion;
            this.promptVersion = promptVersion;
            this.model = model;
            this.configHash = configHash;
            this.status = status;
            this.priority = priority;
            this.attemptCount = attemptCount;
            this.maxAttempts = maxAttempts;
            this.nextAttemptAt = nextAttemptAt;
            this.lastErrorCode = lastErrorCode;
            this.lastErrorMessageSanitized = lastErrorMessageSanitized;
            this.leasedUntil = leasedUntil;
            this.leaseOwner = leaseOwner;
            this.leaseToken = leaseToken;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }

        public String getTaskId() {
            return taskId;
        }

        public String getMessageId() {
            return messageId;
        }

        public String getContentHash() {
            return contentHash;
        }

        public String getAnalysisKey() {
            return analysisKey;
        }

        public String getMatchSignatureHash() {
            return matchSignatureHash;
        }

        public String getSchemaVersion() {
            return schemaVersion;
        }

        public String getPromptVersion() {
            return promptVersion;
        }

        public String getModel() {
            return model;
        }

        public String getConfigHash() {
            return configHash;
        }

        public String getStatus() {
            return status;
        }

        public int getPriority() {
            return priority;
        }

        public int getAttemptCount() {
            return attemptCount;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public long getNextAttemptAt() {
            return nextAttemptAt;
        }

        public String getLastErrorCode() {
            return lastErrorCode;
        }

        public String getLastErrorMessageSanitized() {
            return lastErrorMessageSanitized;
        }

        public long getLeasedUntil() {
            return leasedUntil;
        }

        public String getLeaseOwner() {
            return leaseOwner;
        }

        public String getLeaseToken() {
            return leaseToken;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public long getUpdatedAt() {
            return updatedAt;
        }
    }

    public static class AiTriageResultSummary {
        private final String messageId;
        private final String contentHash;
        private final String analysisKey;
        private final String matchSignatureHash;
        private final String status;
        private final String overallVerdict;
        private final String overallRiskLevel;
        private final double confidence;
        private final String summary;
        private final long analyzedAt;
        private final String schemaVersion;
        private final String promptVersion;
        private final String model;
        private final String configHash;
        private final boolean emptyAdvisoryResult;
        private final boolean lowQualityAdvisoryResult;

        private AiTriageResultSummary(String messageId,
                                      String contentHash,
                                      String analysisKey,
                                      String matchSignatureHash,
                                      String status,
                                      String overallVerdict,
                                      String overallRiskLevel,
                                      double confidence,
                                      String summary,
                                      long analyzedAt,
                                      String schemaVersion,
                                      String promptVersion,
                                      String model,
                                      String configHash,
                                      boolean emptyAdvisoryResult,
                                      boolean lowQualityAdvisoryResult) {
            this.messageId = messageId;
            this.contentHash = contentHash;
            this.analysisKey = analysisKey;
            this.matchSignatureHash = matchSignatureHash;
            this.status = status;
            this.overallVerdict = overallVerdict;
            this.overallRiskLevel = overallRiskLevel;
            this.confidence = confidence;
            this.summary = summary;
            this.analyzedAt = analyzedAt;
            this.schemaVersion = schemaVersion;
            this.promptVersion = promptVersion;
            this.model = model;
            this.configHash = configHash;
            this.emptyAdvisoryResult = emptyAdvisoryResult;
            this.lowQualityAdvisoryResult = lowQualityAdvisoryResult;
        }

        public String getMessageId() {
            return messageId;
        }

        public String getContentHash() {
            return contentHash;
        }

        public String getAnalysisKey() {
            return analysisKey;
        }

        public String getMatchSignatureHash() {
            return matchSignatureHash;
        }

        public String getStatus() {
            return status;
        }

        public String getOverallVerdict() {
            return overallVerdict;
        }

        public String getOverallRiskLevel() {
            return overallRiskLevel;
        }

        public double getConfidence() {
            return confidence;
        }

        public String getSummary() {
            return summary;
        }

        public long getAnalyzedAt() {
            return analyzedAt;
        }

        public String getSchemaVersion() {
            return schemaVersion;
        }

        public String getPromptVersion() {
            return promptVersion;
        }

        public String getModel() {
            return model;
        }

        public String getConfigHash() {
            return configHash;
        }

        public boolean isEmptyAdvisoryResult() {
            return emptyAdvisoryResult;
        }

        public boolean isLowQualityAdvisoryResult() {
            return lowQualityAdvisoryResult;
        }

        public boolean needsRetry() {
            return emptyAdvisoryResult || lowQualityAdvisoryResult;
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
                    response_bytes BLOB NOT NULL,
                    regex_status TEXT NOT NULL DEFAULT 'PENDING',
                    regex_error TEXT NOT NULL DEFAULT '',
                    regex_attempts INTEGER NOT NULL DEFAULT 0,
                    request_length INTEGER NOT NULL DEFAULT 0,
                    response_length INTEGER NOT NULL DEFAULT 0,
                    url_parse_error TEXT NOT NULL DEFAULT '',
                    filter_reason TEXT NOT NULL DEFAULT ''
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

        String createScopedScopeTableSql = String.format("""
                CREATE TABLE IF NOT EXISTS %s (
                    scope_id TEXT PRIMARY KEY,
                    created_at INTEGER NOT NULL,
                    source TEXT NOT NULL,
                    label TEXT NOT NULL
                )
                """, SCOPED_SCOPE_TABLE_NAME);

        String createScopedMessageTableSql = String.format("""
                CREATE TABLE IF NOT EXISTS %s (
                    scope_id TEXT NOT NULL,
                    scoped_message_id TEXT NOT NULL,
                    scoped_order INTEGER NOT NULL,
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
                    response_bytes BLOB NOT NULL,
                    request_length INTEGER NOT NULL DEFAULT 0,
                    response_length INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (scope_id, scoped_message_id)
                )
                """, SCOPED_MESSAGE_TABLE_NAME);

        String createScopedMatchTableSql = String.format("""
                CREATE TABLE IF NOT EXISTS %s (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    scope_id TEXT NOT NULL,
                    scoped_message_id TEXT NOT NULL,
                    rule_name TEXT NOT NULL,
                    extracted_value TEXT NOT NULL
                )
                """, SCOPED_MATCH_TABLE_NAME);

        String createAiTriageTaskTableSql = String.format("""
                CREATE TABLE IF NOT EXISTS %s (
                    task_id TEXT PRIMARY KEY,
                    message_id TEXT NOT NULL,
                    content_hash TEXT NOT NULL,
                    analysis_key TEXT NOT NULL,
                    match_signature_hash TEXT NOT NULL,
                    schema_version TEXT NOT NULL,
                    prompt_version TEXT NOT NULL,
                    model TEXT NOT NULL,
                    config_hash TEXT NOT NULL,
                    status TEXT NOT NULL,
                    priority INTEGER NOT NULL DEFAULT 0,
                    attempt_count INTEGER NOT NULL DEFAULT 0,
                    max_attempts INTEGER NOT NULL DEFAULT 3,
                    next_attempt_at INTEGER NOT NULL DEFAULT 0,
                    last_error_code TEXT NOT NULL DEFAULT '',
                    last_error_message_sanitized TEXT NOT NULL DEFAULT '',
                    leased_until INTEGER NOT NULL DEFAULT 0,
                    lease_owner TEXT NOT NULL DEFAULT '',
                    lease_token TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    UNIQUE(message_id, analysis_key)
                )
                """, AI_TRIAGE_TASK_TABLE_NAME);

        String createAiTriageResultTableSql = String.format("""
                CREATE TABLE IF NOT EXISTS %s (
                    message_id TEXT NOT NULL,
                    content_hash TEXT NOT NULL,
                    analysis_key TEXT NOT NULL,
                    match_signature_hash TEXT NOT NULL DEFAULT '',
                    status TEXT NOT NULL,
                    overall_verdict TEXT NOT NULL,
                    overall_severity TEXT NOT NULL,
                    confidence REAL NOT NULL DEFAULT 0,
                    summary TEXT NOT NULL,
                    result_json TEXT NOT NULL,
                    analyzed_at INTEGER NOT NULL,
                    schema_version TEXT NOT NULL,
                    prompt_version TEXT NOT NULL,
                    model TEXT NOT NULL,
                    config_hash TEXT NOT NULL,
                    PRIMARY KEY (message_id, analysis_key)
                )
                """, AI_TRIAGE_RESULT_TABLE_NAME);

        String createCreatedAtIndex = String.format("CREATE INDEX IF NOT EXISTS idx_%s_created_at ON %s(created_at)", TABLE_NAME, TABLE_NAME);
        String createHostIndex = String.format("CREATE INDEX IF NOT EXISTS idx_%s_host ON %s(host)", TABLE_NAME, TABLE_NAME);
        String createHashIndex = String.format("CREATE INDEX IF NOT EXISTS idx_%s_hash ON %s(content_hash)", TABLE_NAME, TABLE_NAME);
        String createRegexStatusIndex = String.format("CREATE INDEX IF NOT EXISTS idx_%s_regex_status ON %s(regex_status, created_at)", TABLE_NAME, TABLE_NAME);
        String createMatchMessageIndex = String.format("CREATE INDEX IF NOT EXISTS idx_%s_message_id ON %s(message_id)", MATCH_TABLE_NAME, MATCH_TABLE_NAME);
        String createMatchRuleValueIndex = String.format("CREATE INDEX IF NOT EXISTS idx_%s_rule_value ON %s(rule_name, extracted_value)", MATCH_TABLE_NAME, MATCH_TABLE_NAME);
        String createScopedMessageScopeIndex = String.format("CREATE INDEX IF NOT EXISTS idx_%s_scope_id ON %s(scope_id)", SCOPED_MESSAGE_TABLE_NAME, SCOPED_MESSAGE_TABLE_NAME);
        String createScopedMessageIdIndex = String.format("CREATE INDEX IF NOT EXISTS idx_%s_scoped_message_id ON %s(scoped_message_id)", SCOPED_MESSAGE_TABLE_NAME, SCOPED_MESSAGE_TABLE_NAME);
        String createScopedMatchScopeIndex = String.format("CREATE INDEX IF NOT EXISTS idx_%s_scope_id ON %s(scope_id)", SCOPED_MATCH_TABLE_NAME, SCOPED_MATCH_TABLE_NAME);
        String createScopedMatchMessageIndex = String.format("CREATE INDEX IF NOT EXISTS idx_%s_message_id ON %s(scope_id, scoped_message_id)", SCOPED_MATCH_TABLE_NAME, SCOPED_MATCH_TABLE_NAME);
        String createScopedMatchRuleValueIndex = String.format("CREATE INDEX IF NOT EXISTS idx_%s_scope_rule_value ON %s(scope_id, rule_name, extracted_value)", SCOPED_MATCH_TABLE_NAME, SCOPED_MATCH_TABLE_NAME);
        String createAiTaskMessageIndex = String.format("CREATE INDEX IF NOT EXISTS idx_%s_message_id ON %s(message_id)", AI_TRIAGE_TASK_TABLE_NAME, AI_TRIAGE_TASK_TABLE_NAME);
        String createAiTaskAnalysisKeyIndex = String.format("CREATE INDEX IF NOT EXISTS idx_%s_analysis_key ON %s(analysis_key)", AI_TRIAGE_TASK_TABLE_NAME, AI_TRIAGE_TASK_TABLE_NAME);
        String createAiTaskStatusNextAttemptIndex = String.format("CREATE INDEX IF NOT EXISTS idx_%s_status_next_attempt_at ON %s(status, next_attempt_at, priority, created_at)", AI_TRIAGE_TASK_TABLE_NAME, AI_TRIAGE_TASK_TABLE_NAME);
        String createAiTaskContentHashIndex = String.format("CREATE INDEX IF NOT EXISTS idx_%s_content_hash ON %s(content_hash)", AI_TRIAGE_TASK_TABLE_NAME, AI_TRIAGE_TASK_TABLE_NAME);
        String createAiResultMessageIndex = String.format("CREATE INDEX IF NOT EXISTS idx_%s_message_id ON %s(message_id)", AI_TRIAGE_RESULT_TABLE_NAME, AI_TRIAGE_RESULT_TABLE_NAME);
        String createAiResultAnalysisKeyIndex = String.format("CREATE INDEX IF NOT EXISTS idx_%s_analysis_key ON %s(analysis_key)", AI_TRIAGE_RESULT_TABLE_NAME, AI_TRIAGE_RESULT_TABLE_NAME);
        String createAiResultMatchSignatureIndex = String.format("CREATE INDEX IF NOT EXISTS idx_%s_match_signature_hash ON %s(message_id, match_signature_hash)", AI_TRIAGE_RESULT_TABLE_NAME, AI_TRIAGE_RESULT_TABLE_NAME);
        String createAiResultStatusIndex = String.format("CREATE INDEX IF NOT EXISTS idx_%s_status ON %s(status)", AI_TRIAGE_RESULT_TABLE_NAME, AI_TRIAGE_RESULT_TABLE_NAME);
        String createAiResultContentHashIndex = String.format("CREATE INDEX IF NOT EXISTS idx_%s_content_hash ON %s(content_hash)", AI_TRIAGE_RESULT_TABLE_NAME, AI_TRIAGE_RESULT_TABLE_NAME);

        try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute(createTableSql);
            statement.execute(createMatchTableSql);
            statement.execute(createScopedScopeTableSql);
            statement.execute(createScopedMessageTableSql);
            statement.execute(createScopedMatchTableSql);
            statement.execute(createAiTriageTaskTableSql);
            statement.execute(createAiTriageResultTableSql);
            migrateMessageHistorySchema(connection);
            migrateAiTriageTaskSchema(connection);
            migrateAiTriageResultSchema(connection);
            promoteMigratedMatchedRows(connection);
            resetInterruptedRegexWork(connection);
            statement.execute(createCreatedAtIndex);
            statement.execute(createHostIndex);
            statement.execute(createHashIndex);
            statement.execute(createRegexStatusIndex);
            statement.execute(createMatchMessageIndex);
            statement.execute(createMatchRuleValueIndex);
            statement.execute(createScopedMessageScopeIndex);
            statement.execute(createScopedMessageIdIndex);
            statement.execute(createScopedMatchScopeIndex);
            statement.execute(createScopedMatchMessageIndex);
            statement.execute(createScopedMatchRuleValueIndex);
            statement.execute(createAiTaskMessageIndex);
            statement.execute(createAiTaskAnalysisKeyIndex);
            statement.execute(createAiTaskStatusNextAttemptIndex);
            statement.execute(createAiTaskContentHashIndex);
            statement.execute(createAiResultMessageIndex);
            statement.execute(createAiResultAnalysisKeyIndex);
            statement.execute(createAiResultMatchSignatureIndex);
            statement.execute(createAiResultStatusIndex);
            statement.execute(createAiResultContentHashIndex);
        } catch (Exception e) {
            logDatabaseError("initializeDatabase", e);
        }
    }

    private void promoteMigratedMatchedRows(Connection connection) throws SQLException {
        String updateSql = String.format("""
                UPDATE %s
                SET regex_status = ?
                WHERE regex_status = ?
                  AND comment <> ''
                  AND EXISTS (SELECT 1 FROM %s mm WHERE mm.message_id = %s.message_id)
                """, TABLE_NAME, MATCH_TABLE_NAME, TABLE_NAME);
        try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
            statement.setString(1, REGEX_STATUS_DONE);
            statement.setString(2, REGEX_STATUS_PENDING);
            statement.executeUpdate();
        }
    }

    private void resetInterruptedRegexWork(Connection connection) throws SQLException {
        String updateSql = String.format("UPDATE %s SET regex_status = ? WHERE regex_status = ?", TABLE_NAME);
        try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
            statement.setString(1, REGEX_STATUS_PENDING);
            statement.setString(2, REGEX_STATUS_PROCESSING);
            statement.executeUpdate();
        }
    }

    private void migrateMessageHistorySchema(Connection connection) throws SQLException {
        addColumnIfMissing(connection, TABLE_NAME, "regex_status", "TEXT NOT NULL DEFAULT 'PENDING'");
        addColumnIfMissing(connection, TABLE_NAME, "regex_error", "TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing(connection, TABLE_NAME, "regex_attempts", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(connection, TABLE_NAME, "request_length", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(connection, TABLE_NAME, "response_length", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(connection, TABLE_NAME, "url_parse_error", "TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing(connection, TABLE_NAME, "filter_reason", "TEXT NOT NULL DEFAULT ''");
    }

    private void migrateAiTriageTaskSchema(Connection connection) throws SQLException {
        addColumnIfMissing(connection, AI_TRIAGE_TASK_TABLE_NAME, "lease_owner", "TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing(connection, AI_TRIAGE_TASK_TABLE_NAME, "lease_token", "TEXT NOT NULL DEFAULT ''");
    }

    private void migrateAiTriageResultSchema(Connection connection) throws SQLException {
        addColumnIfMissing(connection, AI_TRIAGE_RESULT_TABLE_NAME, "match_signature_hash", "TEXT NOT NULL DEFAULT ''");
    }

    private void addColumnIfMissing(Connection connection, String tableName, String columnName, String columnDefinition) throws SQLException {
        if (columnExists(connection, tableName, columnName)) {
            return;
        }

        try (Statement statement = connection.createStatement()) {
            statement.execute(String.format("ALTER TABLE %s ADD COLUMN %s %s", tableName, columnName, columnDefinition));
        }
    }

    private boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
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

    @Override
    public synchronized PendingMessageSaveResult savePendingMessage(String messageId,
                                                                    HttpRequestResponse messageInfo,
                                                                    String url,
                                                                    String host,
                                                                    String method,
                                                                    String status,
                                                                    String length,
                                                                    String contentHash,
                                                                    String urlParseError,
                                                                    String filterReason,
                                                                    boolean deduplicate) {
        if (messageInfo == null) {
            return new PendingMessageSaveResult(messageId, false, false);
        }

        try {
            HttpRequest request = messageInfo.request();
            HttpResponse response = messageInfo.response();
            if (request == null || response == null) {
                return new PendingMessageSaveResult(messageId, false, false);
            }

            HttpService service = request.httpService();
            if (service == null) {
                return new PendingMessageSaveResult(messageId, false, false);
            }

            byte[] requestBytes = request.toByteArray().getBytes();
            byte[] responseBytes = response.toByteArray().getBytes();
            String safeContentHash = contentHash;
            if (safeContentHash == null || safeContentHash.isBlank()) {
                safeContentHash = calculateContentHash(requestBytes, responseBytes);
            }

            if (deduplicate && existsDuplicateContent(safeContentHash)) {
                return new PendingMessageSaveResult(messageId, false, true);
            }

            String safeHost = host;
            if (safeHost == null || safeHost.isBlank()) {
                safeHost = service.host();
            }

            int requestLength = requestBytes.length;
            int responseLength = responseBytes.length;
            String safeLength = (length == null || length.isBlank()) ? String.valueOf(responseLength) : length;

            String insertSql = String.format("""
                    INSERT INTO %s (
                        message_id, created_at, host, url, method, status, length, comment, color, content_hash,
                        service_host, service_port, service_secure, request_bytes, response_bytes,
                        regex_status, regex_error, regex_attempts, request_length, response_length, url_parse_error, filter_reason
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, TABLE_NAME);

            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement(insertSql)) {
                statement.setString(1, messageId);
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, safeHost == null ? "" : safeHost);
                statement.setString(4, url == null ? "" : url);
                statement.setString(5, method == null ? "" : method);
                statement.setString(6, status == null ? "" : status);
                statement.setString(7, safeLength);
                statement.setString(8, "");
                statement.setString(9, "none");
                statement.setString(10, safeContentHash == null ? "" : safeContentHash);
                statement.setString(11, service.host());
                statement.setInt(12, service.port());
                statement.setInt(13, service.secure() ? 1 : 0);
                statement.setBytes(14, requestBytes);
                statement.setBytes(15, responseBytes);
                statement.setString(16, REGEX_STATUS_PENDING);
                statement.setString(17, "");
                statement.setInt(18, 0);
                statement.setInt(19, requestLength);
                statement.setInt(20, responseLength);
                statement.setString(21, truncateError(urlParseError));
                statement.setString(22, filterReason == null ? "" : filterReason);
                statement.executeUpdate();
                return new PendingMessageSaveResult(messageId, true, false);
            }
        } catch (Exception e) {
            logDatabaseError("savePendingMessage", e);
            return new PendingMessageSaveResult(messageId, false, false);
        }
    }

    @Override
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
                        service_host, service_port, service_secure, request_bytes, response_bytes,
                        regex_status, regex_error, regex_attempts, request_length, response_length, url_parse_error, filter_reason
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                statement.setString(16, REGEX_STATUS_DONE);
                statement.setString(17, "");
                statement.setInt(18, 0);
                statement.setInt(19, requestBytes.length);
                statement.setInt(20, responseBytes.length);
                statement.setString(21, "");
                statement.setString(22, "");
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

    @Override
    public synchronized boolean markRegexProcessing(String messageId) {
        String updateSql = String.format("""
                UPDATE %s
                SET regex_status = ?, regex_attempts = regex_attempts + 1, regex_error = ''
                WHERE message_id = ? AND (regex_status = ? OR (regex_status = ? AND regex_attempts < ?))
                """, TABLE_NAME);

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(updateSql)) {
            statement.setString(1, REGEX_STATUS_PROCESSING);
            statement.setString(2, messageId);
            statement.setString(3, REGEX_STATUS_PENDING);
            statement.setString(4, REGEX_STATUS_FAILED);
            statement.setInt(5, MAX_REGEX_ATTEMPTS);
            return statement.executeUpdate() > 0;
        } catch (Exception e) {
            logDatabaseError("markRegexProcessing", e);
            return false;
        }
    }

    @Override
    public synchronized boolean completeRegexProcessing(String messageId,
                                                        String comment,
                                                        String color,
                                                        Map<String, List<String>> extractedDataByRule) {
        String updateSql = String.format("""
                UPDATE %s
                SET regex_status = ?, regex_error = '', comment = ?, color = ?
                WHERE message_id = ?
                """, TABLE_NAME);

        try (Connection connection = getConnection();
             PreparedStatement updateStatement = connection.prepareStatement(updateSql);
             PreparedStatement deleteMatchStatement = connection.prepareStatement(String.format("DELETE FROM %s WHERE message_id = ?", MATCH_TABLE_NAME));
             PreparedStatement insertMatchStatement = connection.prepareStatement(String.format("INSERT INTO %s (message_id, rule_name, extracted_value) VALUES (?, ?, ?)", MATCH_TABLE_NAME))) {

            connection.setAutoCommit(false);
            updateStatement.setString(1, REGEX_STATUS_DONE);
            updateStatement.setString(2, comment == null ? "" : comment);
            updateStatement.setString(3, color == null || color.isBlank() ? "none" : color);
            updateStatement.setString(4, messageId);
            if (updateStatement.executeUpdate() == 0) {
                connection.rollback();
                return false;
            }

            saveMatchData(messageId, extractedDataByRule, deleteMatchStatement, insertMatchStatement);

            connection.commit();
            return true;
        } catch (Exception e) {
            logDatabaseError("completeRegexProcessing", e);
            return false;
        }
    }

    @Override
    public synchronized boolean failRegexProcessing(String messageId, String errorMessage) {
        String updateSql = String.format("""
                UPDATE %s
                SET regex_status = ?, regex_error = ?
                WHERE message_id = ?
                """, TABLE_NAME);

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(updateSql)) {
            statement.setString(1, REGEX_STATUS_FAILED);
            statement.setString(2, truncateError(errorMessage));
            statement.setString(3, messageId);
            return statement.executeUpdate() > 0;
        } catch (Exception e) {
            logDatabaseError("failRegexProcessing", e);
            return false;
        }
    }

    @Override
    public synchronized boolean resetRegexProcessing(String messageId, String errorMessage) {
        String updateSql = String.format("""
                UPDATE %s
                SET regex_status = ?, regex_error = ?
                WHERE message_id = ?
                """, TABLE_NAME);

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(updateSql)) {
            statement.setString(1, REGEX_STATUS_PENDING);
            statement.setString(2, truncateError(errorMessage));
            statement.setString(3, messageId);
            return statement.executeUpdate() > 0;
        } catch (Exception e) {
            logDatabaseError("resetRegexProcessing", e);
            return false;
        }
    }

    @Override
    public synchronized List<String> loadPendingRegexMessageIds(int limit) {
        int safeLimit = Math.max(1, limit);
        List<String> result = new ArrayList<>();
        String querySql = String.format("""
                SELECT message_id
                FROM %s
                WHERE regex_status = ? OR (regex_status = ? AND regex_attempts < ?)
                ORDER BY created_at ASC, message_id ASC
                LIMIT ?
                """, TABLE_NAME);

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(querySql)) {
            statement.setString(1, REGEX_STATUS_PENDING);
            statement.setString(2, REGEX_STATUS_FAILED);
            statement.setInt(3, MAX_REGEX_ATTEMPTS);
            statement.setInt(4, safeLimit);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    result.add(resultSet.getString("message_id"));
                }
            }
        } catch (Exception e) {
            logDatabaseError("loadPendingRegexMessageIds", e);
        }

        return result;
    }

    @Override
    public synchronized boolean enqueueAiTriageTask(String taskId,
                                                    String messageId,
                                                    String contentHash,
                                                    String analysisKey,
                                                    String matchSignatureHash,
                                                    String schemaVersion,
                                                    String promptVersion,
                                                    String model,
                                                    String configHash,
                                                    int priority,
                                                    int maxAttempts,
                                                    long nextAttemptAt) {
        if (isBlank(taskId) || isBlank(messageId) || isBlank(analysisKey)) {
            return false;
        }

        long now = System.currentTimeMillis();
        String insertSql = String.format("""
                INSERT OR IGNORE INTO %s (
                    task_id, message_id, content_hash, analysis_key, match_signature_hash,
                    schema_version, prompt_version, model, config_hash, status, priority,
                    attempt_count, max_attempts, next_attempt_at, last_error_code,
                    last_error_message_sanitized, leased_until, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, AI_TRIAGE_TASK_TABLE_NAME);

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(insertSql)) {
            statement.setString(1, taskId);
            statement.setString(2, messageId);
            statement.setString(3, safeString(contentHash));
            statement.setString(4, analysisKey);
            statement.setString(5, safeString(matchSignatureHash));
            statement.setString(6, safeString(schemaVersion));
            statement.setString(7, safeString(promptVersion));
            statement.setString(8, safeString(model));
            statement.setString(9, safeString(configHash));
            statement.setString(10, AI_STATUS_PENDING);
            statement.setInt(11, priority);
            statement.setInt(12, 0);
            statement.setInt(13, Math.max(1, maxAttempts));
            statement.setLong(14, Math.max(0L, nextAttemptAt));
            statement.setString(15, "");
            statement.setString(16, "");
            statement.setLong(17, 0L);
            statement.setLong(18, now);
            statement.setLong(19, now);
            return statement.executeUpdate() > 0;
        } catch (Exception e) {
            logDatabaseError("enqueueAiTriageTask", e);
            return false;
        }
    }

    @Override
    public synchronized int countActiveAiTriageTasks() {
        String querySql = String.format("""
                SELECT COUNT(1)
                FROM %s
                WHERE status <> ?
                """, AI_TRIAGE_TASK_TABLE_NAME);

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(querySql)) {
            statement.setString(1, AI_STATUS_DONE);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        } catch (Exception e) {
            logDatabaseError("countActiveAiTriageTasks", e);
            return 0;
        }
    }

    @Override
    public synchronized boolean hasAiTriageForMessage(String messageId) {
        if (isBlank(messageId)) {
            return false;
        }

        String querySql = String.format("""
                SELECT 1
                FROM %s
                WHERE message_id = ?
                UNION ALL
                SELECT 1
                FROM %s
                WHERE message_id = ?
                LIMIT 1
                """, AI_TRIAGE_TASK_TABLE_NAME, AI_TRIAGE_RESULT_TABLE_NAME);
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(querySql)) {
            statement.setString(1, messageId);
            statement.setString(2, messageId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (Exception e) {
            logDatabaseError("hasAiTriageForMessage", e);
            return false;
        }
    }

    @Override
    public synchronized boolean hasBlockingAiTriageForMessage(String messageId) {
        if (isBlank(messageId)) {
            return false;
        }

        String taskQuerySql = String.format("SELECT 1 FROM %s WHERE message_id = ? AND status <> ? LIMIT 1", AI_TRIAGE_TASK_TABLE_NAME);
        String resultQuerySql = String.format("""
                SELECT status, overall_verdict, overall_severity, confidence, summary, result_json
                FROM %s
                WHERE message_id = ?
                """, AI_TRIAGE_RESULT_TABLE_NAME);
        try (Connection connection = getConnection();
            PreparedStatement taskStatement = connection.prepareStatement(taskQuerySql);
             PreparedStatement resultStatement = connection.prepareStatement(resultQuerySql)) {
            taskStatement.setString(1, messageId);
            taskStatement.setString(2, AI_STATUS_DONE);
            try (ResultSet resultSet = taskStatement.executeQuery()) {
                if (resultSet.next()) {
                    return true;
                }
            }

            resultStatement.setString(1, messageId);
            try (ResultSet resultSet = resultStatement.executeQuery()) {
                while (resultSet.next()) {
                    if (isBlockingAiResult(
                            resultSet.getString("status"),
                            resultSet.getString("overall_verdict"),
                            resultSet.getString("overall_severity"),
                            resultSet.getDouble("confidence"),
                            resultSet.getString("summary"),
                            resultSet.getString("result_json")
                    )) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            logDatabaseError("hasBlockingAiTriageForMessage", e);
            return false;
        }
    }

    @Override
    public synchronized boolean hasBlockingAiTriageForTarget(String messageId, String matchSignatureHash) {
        if (isBlank(messageId) || isBlank(matchSignatureHash)) {
            return false;
        }

        String querySql = String.format("""
                SELECT 1
                FROM %s
                WHERE message_id = ?
                  AND match_signature_hash = ?
                  AND status <> ?
                LIMIT 1
                """, AI_TRIAGE_TASK_TABLE_NAME);
        String resultQuerySql = String.format("""
                SELECT status, overall_verdict, overall_severity, confidence, summary, result_json
                FROM %s
                WHERE message_id = ?
                  AND match_signature_hash = ?
                """, AI_TRIAGE_RESULT_TABLE_NAME);
        try (Connection connection = getConnection();
             PreparedStatement taskStatement = connection.prepareStatement(querySql);
             PreparedStatement resultStatement = connection.prepareStatement(resultQuerySql)) {
            taskStatement.setString(1, messageId);
            taskStatement.setString(2, matchSignatureHash);
            taskStatement.setString(3, AI_STATUS_DONE);
            try (ResultSet resultSet = taskStatement.executeQuery()) {
                if (resultSet.next()) {
                    return true;
                }
            }

            resultStatement.setString(1, messageId);
            resultStatement.setString(2, matchSignatureHash);
            try (ResultSet resultSet = resultStatement.executeQuery()) {
                while (resultSet.next()) {
                    if (isBlockingAiResult(
                            resultSet.getString("status"),
                            resultSet.getString("overall_verdict"),
                            resultSet.getString("overall_severity"),
                            resultSet.getDouble("confidence"),
                            resultSet.getString("summary"),
                            resultSet.getString("result_json")
                    )) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            logDatabaseError("hasBlockingAiTriageForTarget", e);
            return false;
        }
    }

    private String classifyAiResult(String status,
                                    String overallVerdict,
                                    String overallRiskLevel,
                                    double confidence,
                                    String summary,
                                    String resultJson) {
        return AiTriageResultQuality.classifyDoneResult(
                status,
                overallVerdict,
                overallRiskLevel,
                confidence,
                summary,
                resultJson
        );
    }

    private boolean isBlockingAiResult(String status,
                                       String overallVerdict,
                                       String overallRiskLevel,
                                       double confidence,
                                       String summary,
                                       String resultJson) {
        return AI_STATUS_DONE.equals(status)
                && !AiTriageResultQuality.shouldRetry(classifyAiResult(
                status,
                overallVerdict,
                overallRiskLevel,
                confidence,
                summary,
                resultJson
        ));
    }

    private boolean shouldRejectAiResult(AiTriageResultWrite resultWrite) {
        return resultWrite != null && AiTriageResultQuality.shouldRetry(classifyAiResult(
                resultWrite.status(),
                resultWrite.overallVerdict(),
                resultWrite.overallRiskLevel(),
                resultWrite.confidence(),
                resultWrite.summary(),
                resultWrite.resultJson()
        ));
    }

    @Override
    public synchronized AiQueueCounts loadAiQueueCounts() {
        String taskCountsSql = String.format("""
                SELECT status, COUNT(1) AS status_count
                FROM %s
                GROUP BY status
                """, AI_TRIAGE_TASK_TABLE_NAME);
        String resultCountsSql = String.format("""
                SELECT status, COUNT(1) AS status_count
                FROM %s
                GROUP BY status
                """, AI_TRIAGE_RESULT_TABLE_NAME);
        Map<String, Integer> taskCounts = new HashMap<>();
        Map<String, Integer> resultCounts = new HashMap<>();

        try (Connection connection = getConnection();
             Statement taskStatement = connection.createStatement();
             ResultSet taskResultSet = taskStatement.executeQuery(taskCountsSql);
             Statement resultStatement = connection.createStatement()) {
            while (taskResultSet.next()) {
                taskCounts.put(taskResultSet.getString("status"), taskResultSet.getInt("status_count"));
            }

            try (ResultSet resultSet = resultStatement.executeQuery(resultCountsSql)) {
                while (resultSet.next()) {
                    resultCounts.put(resultSet.getString("status"), resultSet.getInt("status_count"));
                }
            }
        } catch (Exception e) {
            logDatabaseError("loadAiQueueCounts", e);
            return AiQueueCounts.zero();
        }

        int running = taskCounts.getOrDefault(AI_STATUS_LEASED, 0) + taskCounts.getOrDefault(AI_STATUS_RUNNING, 0);
        int skipped = resultCounts.getOrDefault(AI_RESULT_STATUS_SKIPPED, 0);
        int succeeded = resultCounts.getOrDefault(AI_STATUS_DONE, 0);
        return new AiQueueCounts(
                taskCounts.getOrDefault(AI_STATUS_PENDING, 0),
                running,
                succeeded,
                taskCounts.getOrDefault(AI_STATUS_FAILED, 0),
                skipped
        );
    }

    @Override
    public synchronized List<AiTriageTask> loadAiTriageTaskSummaries(List<String> messageIds, String matchSignatureHash) {
        List<String> safeMessageIds = distinctNonBlankValues(messageIds);
        String safeMatchSignatureHash = safeString(matchSignatureHash);
        if (safeMessageIds.isEmpty() || safeMatchSignatureHash.isBlank()) {
            return new ArrayList<>();
        }

        String placeholders = String.join(",", Collections.nCopies(safeMessageIds.size(), "?"));
        String querySql = String.format("""
                SELECT task_id, message_id, content_hash, analysis_key, match_signature_hash,
                       schema_version, prompt_version, model, config_hash, status, priority,
                       attempt_count, max_attempts, next_attempt_at, last_error_code,
                       last_error_message_sanitized, leased_until, lease_owner, lease_token, created_at, updated_at
                FROM %s
                WHERE message_id IN (%s)
                  AND match_signature_hash = ?
                  AND status <> ?
                ORDER BY updated_at DESC, priority DESC, created_at DESC, task_id ASC
                """, AI_TRIAGE_TASK_TABLE_NAME, placeholders);
        List<AiTriageTask> result = new ArrayList<>();
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(querySql)) {
            for (int i = 0; i < safeMessageIds.size(); i++) {
                statement.setString(i + 1, safeMessageIds.get(i));
            }
            statement.setString(safeMessageIds.size() + 1, safeMatchSignatureHash);
            statement.setString(safeMessageIds.size() + 2, AI_STATUS_DONE);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    result.add(toAiTriageTask(resultSet));
                }
            }
        } catch (Exception e) {
            logDatabaseError("loadAiTriageTaskSummaries", e);
        }
        return result;
    }

    @Override
    public synchronized int clearPendingAiTriageTasks() {
        String deleteSql = String.format("DELETE FROM %s WHERE status = ?", AI_TRIAGE_TASK_TABLE_NAME);
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(deleteSql)) {
            statement.setString(1, AI_STATUS_PENDING);
            return statement.executeUpdate();
        } catch (Exception e) {
            logDatabaseError("clearPendingAiTriageTasks", e);
            return 0;
        }
    }

    @Override
    public synchronized int retryFailedAiTriageTasks(long nextAttemptAt) {
        String updateSql = String.format("""
                UPDATE %s
                SET status = ?,
                    attempt_count = 0,
                    next_attempt_at = ?,
                    last_error_code = '',
                    last_error_message_sanitized = '',
                    leased_until = 0,
                    lease_owner = '',
                    lease_token = '',
                    updated_at = ?
                WHERE status = ?
                """, AI_TRIAGE_TASK_TABLE_NAME);
        long now = System.currentTimeMillis();
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(updateSql)) {
            statement.setString(1, AI_STATUS_PENDING);
            statement.setLong(2, Math.max(0L, nextAttemptAt));
            statement.setLong(3, now);
            statement.setString(4, AI_STATUS_FAILED);
            return statement.executeUpdate();
        } catch (Exception e) {
            logDatabaseError("retryFailedAiTriageTasks", e);
            return 0;
        }
    }

    @Override
    public synchronized List<AiTriageTask> leaseNextAiTriageTasks(int limit, long nowEpochMillis, long leaseDurationMillis) {
        return leaseNextAiTriageTasks(limit, nowEpochMillis, leaseDurationMillis, "");
    }

    @Override
    public synchronized List<AiTriageTask> leaseNextAiTriageTasks(int limit,
                                                                 long nowEpochMillis,
                                                                 long leaseDurationMillis,
                                                                 String leaseOwner) {
        int safeLimit = Math.max(1, limit);
        long safeLeaseDuration = Math.max(1L, leaseDurationMillis);
        long leasedUntil = nowEpochMillis + safeLeaseDuration;
        String safeLeaseOwner = safeString(leaseOwner);
        List<String> taskIds = new ArrayList<>();
        String selectSql = String.format("""
                SELECT task_id
                FROM %s
                WHERE (status = ? OR status = ?)
                  AND attempt_count < max_attempts
                  AND next_attempt_at <= ?
                ORDER BY priority DESC, created_at ASC, task_id ASC
                LIMIT ?
                """, AI_TRIAGE_TASK_TABLE_NAME);
        String leaseSql = String.format("""
                UPDATE %s
                SET status = ?, attempt_count = attempt_count + 1, leased_until = ?, lease_owner = ?, lease_token = ?, updated_at = ?
                WHERE task_id = ?
                  AND (status = ? OR status = ?)
                  AND attempt_count < max_attempts
                  AND next_attempt_at <= ?
                """, AI_TRIAGE_TASK_TABLE_NAME);

        try (Connection connection = getConnection();
             PreparedStatement selectStatement = connection.prepareStatement(selectSql);
             PreparedStatement leaseStatement = connection.prepareStatement(leaseSql)) {
            connection.setAutoCommit(false);
            selectStatement.setString(1, AI_STATUS_PENDING);
            selectStatement.setString(2, AI_STATUS_FAILED);
            selectStatement.setLong(3, nowEpochMillis);
            selectStatement.setInt(4, safeLimit);
            try (ResultSet resultSet = selectStatement.executeQuery()) {
                while (resultSet.next()) {
                    taskIds.add(resultSet.getString("task_id"));
                }
            }

            for (String selectedTaskId : taskIds) {
                String leaseToken = UUID.randomUUID().toString();
                leaseStatement.setString(1, AI_STATUS_LEASED);
                leaseStatement.setLong(2, leasedUntil);
                leaseStatement.setString(3, safeLeaseOwner);
                leaseStatement.setString(4, leaseToken);
                leaseStatement.setLong(5, nowEpochMillis);
                leaseStatement.setString(6, selectedTaskId);
                leaseStatement.setString(7, AI_STATUS_PENDING);
                leaseStatement.setString(8, AI_STATUS_FAILED);
                leaseStatement.setLong(9, nowEpochMillis);
                if (leaseStatement.executeUpdate() == 0) {
                    connection.rollback();
                    return new ArrayList<>();
                }
            }

            connection.commit();
        } catch (Exception e) {
            logDatabaseError("leaseNextAiTriageTasks", e);
            return new ArrayList<>();
        }

        return loadAiTriageTasksByIds(taskIds);
    }

    @Override
    public synchronized boolean completeAiTriageTask(String taskId) {
        if (isBlank(taskId)) {
            return false;
        }

        String updateSql = String.format("""
                UPDATE %s
                SET status = ?, leased_until = 0, lease_owner = '', lease_token = '', updated_at = ?
                WHERE task_id = ?
                """, AI_TRIAGE_TASK_TABLE_NAME);
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(updateSql)) {
            statement.setString(1, AI_STATUS_DONE);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, taskId);
            return statement.executeUpdate() > 0;
        } catch (Exception e) {
            logDatabaseError("completeAiTriageTask", e);
            return false;
        }
    }

    @Override
    public synchronized boolean completeAiTriageTask(String taskId, String leaseOwner, String leaseToken) {
        if (isBlank(taskId) || isBlank(leaseToken)) {
            return false;
        }

        String updateSql = String.format("""
                UPDATE %s
                SET status = ?, leased_until = 0, lease_owner = '', lease_token = '', updated_at = ?
                WHERE task_id = ?
                  AND lease_owner = ?
                  AND lease_token = ?
                  AND (status = ? OR status = ?)
                """, AI_TRIAGE_TASK_TABLE_NAME);
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(updateSql)) {
            statement.setString(1, AI_STATUS_DONE);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, taskId);
            statement.setString(4, safeString(leaseOwner));
            statement.setString(5, leaseToken);
            statement.setString(6, AI_STATUS_LEASED);
            statement.setString(7, AI_STATUS_RUNNING);
            return statement.executeUpdate() > 0;
        } catch (Exception e) {
            logDatabaseError("completeAiTriageTask(guarded)", e);
            return false;
        }
    }

    @Override
    public synchronized boolean completeAiTriageTaskWithResult(String taskId,
                                                              String leaseOwner,
                                                              String leaseToken,
                                                              AiTriageResultWrite resultWrite) {
        if (isBlank(taskId) || isBlank(leaseToken) || resultWrite == null || isBlank(resultWrite.messageId()) ||
                isBlank(resultWrite.analysisKey())) {
            return false;
        }
        if (shouldRejectAiResult(resultWrite)) {
            return false;
        }

        String verifyLeaseSql = String.format("""
                SELECT 1
                FROM %s
                WHERE task_id = ?
                  AND lease_owner = ?
                  AND lease_token = ?
                  AND (status = ? OR status = ?)
                """, AI_TRIAGE_TASK_TABLE_NAME);
        String upsertResultSql = String.format("""
                INSERT INTO %s (
                    message_id, content_hash, analysis_key, match_signature_hash, status, overall_verdict,
                    overall_severity, confidence, summary, result_json, analyzed_at,
                    schema_version, prompt_version, model, config_hash
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(message_id, analysis_key) DO UPDATE SET
                    content_hash = excluded.content_hash,
                    match_signature_hash = excluded.match_signature_hash,
                    status = excluded.status,
                    overall_verdict = excluded.overall_verdict,
                    overall_severity = excluded.overall_severity,
                    confidence = excluded.confidence,
                    summary = excluded.summary,
                    result_json = excluded.result_json,
                    analyzed_at = excluded.analyzed_at,
                    schema_version = excluded.schema_version,
                    prompt_version = excluded.prompt_version,
                    model = excluded.model,
                    config_hash = excluded.config_hash
                """, AI_TRIAGE_RESULT_TABLE_NAME);
        String completeTaskSql = String.format("""
                UPDATE %s
                SET status = ?, leased_until = 0, lease_owner = '', lease_token = '', updated_at = ?
                WHERE task_id = ?
                  AND lease_owner = ?
                  AND lease_token = ?
                  AND (status = ? OR status = ?)
                """, AI_TRIAGE_TASK_TABLE_NAME);

        try (Connection connection = getConnection();
             PreparedStatement verifyStatement = connection.prepareStatement(verifyLeaseSql);
             PreparedStatement resultStatement = connection.prepareStatement(upsertResultSql);
             PreparedStatement completeStatement = connection.prepareStatement(completeTaskSql)) {
            connection.setAutoCommit(false);

            verifyStatement.setString(1, taskId);
            verifyStatement.setString(2, safeString(leaseOwner));
            verifyStatement.setString(3, leaseToken);
            verifyStatement.setString(4, AI_STATUS_LEASED);
            verifyStatement.setString(5, AI_STATUS_RUNNING);
            try (ResultSet resultSet = verifyStatement.executeQuery()) {
                if (!resultSet.next()) {
                    connection.rollback();
                    return false;
                }
            }

            bindAiTriageResultWrite(resultStatement, resultWrite);
            if (resultStatement.executeUpdate() == 0) {
                connection.rollback();
                return false;
            }

            completeStatement.setString(1, AI_STATUS_DONE);
            completeStatement.setLong(2, System.currentTimeMillis());
            completeStatement.setString(3, taskId);
            completeStatement.setString(4, safeString(leaseOwner));
            completeStatement.setString(5, leaseToken);
            completeStatement.setString(6, AI_STATUS_LEASED);
            completeStatement.setString(7, AI_STATUS_RUNNING);
            if (completeStatement.executeUpdate() == 0) {
                connection.rollback();
                return false;
            }

            connection.commit();
            return true;
        } catch (Exception e) {
            logDatabaseError("completeAiTriageTaskWithResult", e);
            return false;
        }
    }

    @Override
    public synchronized boolean failAiTriageTask(String taskId,
                                                String errorCode,
                                                String errorMessageSanitized,
                                                long nextAttemptAt) {
        if (isBlank(taskId)) {
            return false;
        }

        String updateSql = String.format("""
                UPDATE %s
                SET status = ?, next_attempt_at = ?, last_error_code = ?,
                    last_error_message_sanitized = ?, leased_until = 0, lease_owner = '', lease_token = '', updated_at = ?
                WHERE task_id = ?
                """, AI_TRIAGE_TASK_TABLE_NAME);
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(updateSql)) {
            statement.setString(1, AI_STATUS_FAILED);
            statement.setLong(2, Math.max(0L, nextAttemptAt));
            statement.setString(3, safeString(errorCode));
            statement.setString(4, truncateAiError(errorMessageSanitized));
            statement.setLong(5, System.currentTimeMillis());
            statement.setString(6, taskId);
            return statement.executeUpdate() > 0;
        } catch (Exception e) {
            logDatabaseError("failAiTriageTask", e);
            return false;
        }
    }

    @Override
    public synchronized boolean failAiTriageTask(String taskId,
                                                String leaseOwner,
                                                String leaseToken,
                                                String errorCode,
                                                String errorMessageSanitized,
                                                long nextAttemptAt) {
        if (isBlank(taskId) || isBlank(leaseToken)) {
            return false;
        }

        String updateSql = String.format("""
                UPDATE %s
                SET status = ?, next_attempt_at = ?, last_error_code = ?,
                    last_error_message_sanitized = ?, leased_until = 0, lease_owner = '', lease_token = '', updated_at = ?
                WHERE task_id = ?
                  AND lease_owner = ?
                  AND lease_token = ?
                  AND (status = ? OR status = ?)
                """, AI_TRIAGE_TASK_TABLE_NAME);
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(updateSql)) {
            statement.setString(1, AI_STATUS_FAILED);
            statement.setLong(2, Math.max(0L, nextAttemptAt));
            statement.setString(3, safeString(errorCode));
            statement.setString(4, truncateAiError(errorMessageSanitized));
            statement.setLong(5, System.currentTimeMillis());
            statement.setString(6, taskId);
            statement.setString(7, safeString(leaseOwner));
            statement.setString(8, leaseToken);
            statement.setString(9, AI_STATUS_LEASED);
            statement.setString(10, AI_STATUS_RUNNING);
            return statement.executeUpdate() > 0;
        } catch (Exception e) {
            logDatabaseError("failAiTriageTask(guarded)", e);
            return false;
        }
    }

    @Override
    public synchronized boolean releaseAiTriageTask(String taskId, long nextAttemptAt) {
        if (isBlank(taskId)) {
            return false;
        }

        String updateSql = String.format("""
                UPDATE %s
                SET status = ?,
                    attempt_count = CASE WHEN attempt_count > 0 THEN attempt_count - 1 ELSE 0 END,
                    next_attempt_at = ?,
                    last_error_code = '',
                    last_error_message_sanitized = '',
                    leased_until = 0,
                    lease_owner = '',
                    lease_token = '',
                    updated_at = ?
                WHERE task_id = ?
                  AND (status = ? OR status = ?)
                """, AI_TRIAGE_TASK_TABLE_NAME);
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(updateSql)) {
            statement.setString(1, AI_STATUS_PENDING);
            statement.setLong(2, Math.max(0L, nextAttemptAt));
            statement.setLong(3, System.currentTimeMillis());
            statement.setString(4, taskId);
            statement.setString(5, AI_STATUS_LEASED);
            statement.setString(6, AI_STATUS_RUNNING);
            return statement.executeUpdate() > 0;
        } catch (Exception e) {
            logDatabaseError("releaseAiTriageTask", e);
            return false;
        }
    }

    @Override
    public synchronized boolean releaseAiTriageTask(String taskId, String leaseOwner, String leaseToken, long nextAttemptAt) {
        if (isBlank(taskId) || isBlank(leaseToken)) {
            return false;
        }

        String updateSql = String.format("""
                UPDATE %s
                SET status = ?,
                    attempt_count = CASE WHEN attempt_count > 0 THEN attempt_count - 1 ELSE 0 END,
                    next_attempt_at = ?,
                    last_error_code = '',
                    last_error_message_sanitized = '',
                    leased_until = 0,
                    lease_owner = '',
                    lease_token = '',
                    updated_at = ?
                WHERE task_id = ?
                  AND lease_owner = ?
                  AND lease_token = ?
                  AND (status = ? OR status = ?)
                """, AI_TRIAGE_TASK_TABLE_NAME);
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(updateSql)) {
            statement.setString(1, AI_STATUS_PENDING);
            statement.setLong(2, Math.max(0L, nextAttemptAt));
            statement.setLong(3, System.currentTimeMillis());
            statement.setString(4, taskId);
            statement.setString(5, safeString(leaseOwner));
            statement.setString(6, leaseToken);
            statement.setString(7, AI_STATUS_LEASED);
            statement.setString(8, AI_STATUS_RUNNING);
            return statement.executeUpdate() > 0;
        } catch (Exception e) {
            logDatabaseError("releaseAiTriageTask(guarded)", e);
            return false;
        }
    }

    @Override
    public synchronized int recoverStaleAiTriageTasks(long nowEpochMillis) {
        String recoverRetryableSql = String.format("""
                UPDATE %s
                SET status = ?, leased_until = 0, lease_owner = '', lease_token = '', next_attempt_at = ?, updated_at = ?
                WHERE (status = ? OR status = ?)
                  AND leased_until > 0
                  AND leased_until <= ?
                  AND attempt_count < max_attempts
                """, AI_TRIAGE_TASK_TABLE_NAME);
        String markExhaustedSql = String.format("""
                UPDATE %s
                SET status = ?, leased_until = 0, next_attempt_at = ?, last_error_code = ?,
                    last_error_message_sanitized = ?, lease_owner = '', lease_token = '', updated_at = ?
                WHERE (status = ? OR status = ?)
                  AND leased_until > 0
                  AND leased_until <= ?
                  AND attempt_count >= max_attempts
                """, AI_TRIAGE_TASK_TABLE_NAME);
        try (Connection connection = getConnection();
             PreparedStatement recoverStatement = connection.prepareStatement(recoverRetryableSql);
             PreparedStatement exhaustedStatement = connection.prepareStatement(markExhaustedSql)) {
            connection.setAutoCommit(false);
            recoverStatement.setString(1, AI_STATUS_PENDING);
            recoverStatement.setLong(2, nowEpochMillis);
            recoverStatement.setLong(3, nowEpochMillis);
            recoverStatement.setString(4, AI_STATUS_LEASED);
            recoverStatement.setString(5, AI_STATUS_RUNNING);
            recoverStatement.setLong(6, nowEpochMillis);
            int recovered = recoverStatement.executeUpdate();

            exhaustedStatement.setString(1, AI_STATUS_FAILED);
            exhaustedStatement.setLong(2, nowEpochMillis);
            exhaustedStatement.setString(3, "LEASE_EXPIRED");
            exhaustedStatement.setString(4, "AI triage lease expired");
            exhaustedStatement.setLong(5, nowEpochMillis);
            exhaustedStatement.setString(6, AI_STATUS_LEASED);
            exhaustedStatement.setString(7, AI_STATUS_RUNNING);
            exhaustedStatement.setLong(8, nowEpochMillis);
            int exhausted = exhaustedStatement.executeUpdate();

            connection.commit();
            return recovered + exhausted;
        } catch (Exception e) {
            logDatabaseError("recoverStaleAiTriageTasks", e);
            return 0;
        }
    }

    @Override
    public synchronized int cleanupOrphanAiTriageTasks() {
        String deleteSql = String.format("""
                DELETE FROM %s
                WHERE NOT EXISTS (
                    SELECT 1 FROM %s mh WHERE mh.message_id = %s.message_id
                )
                """, AI_TRIAGE_TASK_TABLE_NAME, TABLE_NAME, AI_TRIAGE_TASK_TABLE_NAME);
        try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
            return statement.executeUpdate(deleteSql);
        } catch (Exception e) {
            logDatabaseError("cleanupOrphanAiTriageTasks", e);
            return 0;
        }
    }

    @Override
    public synchronized boolean saveAiTriageResult(String messageId,
                                                   String contentHash,
                                                   String analysisKey,
                                                   String status,
                                                   String overallVerdict,
                                                   String overallRiskLevel,
                                                   double confidence,
                                                   String summary,
                                                   String resultJson,
                                                   long analyzedAt,
                                                   String schemaVersion,
                                                   String promptVersion,
                                                   String model,
                                                   String configHash) {
        if (isBlank(messageId) || isBlank(analysisKey)) {
            return false;
        }
        AiTriageResultWrite resultWrite = new AiTriageResultWrite(
                messageId,
                contentHash,
                analysisKey,
                status,
                overallVerdict,
                overallRiskLevel,
                confidence,
                summary,
                resultJson,
                analyzedAt,
                schemaVersion,
                promptVersion,
                model,
                configHash
        );
        if (shouldRejectAiResult(resultWrite)) {
            return false;
        }

        String upsertSql = String.format("""
                INSERT INTO %s (
                    message_id, content_hash, analysis_key, match_signature_hash, status, overall_verdict,
                    overall_severity, confidence, summary, result_json, analyzed_at,
                    schema_version, prompt_version, model, config_hash
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(message_id, analysis_key) DO UPDATE SET
                    content_hash = excluded.content_hash,
                    match_signature_hash = excluded.match_signature_hash,
                    status = excluded.status,
                    overall_verdict = excluded.overall_verdict,
                    overall_severity = excluded.overall_severity,
                    confidence = excluded.confidence,
                    summary = excluded.summary,
                    result_json = excluded.result_json,
                    analyzed_at = excluded.analyzed_at,
                    schema_version = excluded.schema_version,
                    prompt_version = excluded.prompt_version,
                    model = excluded.model,
                    config_hash = excluded.config_hash
                """, AI_TRIAGE_RESULT_TABLE_NAME);
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(upsertSql)) {
            bindAiTriageResultWrite(statement, resultWrite);
            return statement.executeUpdate() > 0;
        } catch (Exception e) {
            logDatabaseError("saveAiTriageResult", e);
            return false;
        }
    }

    @Override
    public synchronized List<AiTriageResultSummary> loadAiTriageResultSummaries(List<String> messageIds) {
        return loadAiTriageResultSummaries(messageIds, "");
    }

    @Override
    public synchronized List<AiTriageResultSummary> loadAiTriageResultSummaries(List<String> messageIds, String matchSignatureHash) {
        List<String> safeMessageIds = distinctNonBlankValues(messageIds);
        if (safeMessageIds.isEmpty()) {
            return new ArrayList<>();
        }
        String safeMatchSignatureHash = safeString(matchSignatureHash);
        boolean filterByTarget = !safeMatchSignatureHash.isBlank();

        String placeholders = String.join(",", Collections.nCopies(safeMessageIds.size(), "?"));
        String querySql = String.format("""
                SELECT message_id, content_hash, analysis_key, status, overall_verdict,
                       match_signature_hash, overall_severity, confidence, summary,
                       CASE WHEN result_json LIKE '%%"items":[]%%'
                              OR result_json LIKE '%%"items": []%%'
                              OR result_json LIKE '%%"items" :[]%%'
                              OR result_json LIKE '%%"items" : []%%'
                            THEN '{"items":[]}' ELSE '' END AS result_json,
                       analyzed_at,
                       schema_version, prompt_version, model, config_hash
                FROM %s
                WHERE message_id IN (%s)
                %s
                ORDER BY analyzed_at DESC, message_id ASC, analysis_key ASC
                """, AI_TRIAGE_RESULT_TABLE_NAME, placeholders, filterByTarget ? "AND match_signature_hash = ?" : "");
        List<AiTriageResultSummary> result = new ArrayList<>();
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(querySql)) {
            for (int i = 0; i < safeMessageIds.size(); i++) {
                statement.setString(i + 1, safeMessageIds.get(i));
            }
            if (filterByTarget) {
                statement.setString(safeMessageIds.size() + 1, safeMatchSignatureHash);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    result.add(toAiTriageResultSummary(resultSet));
                }
            }
        } catch (Exception e) {
            logDatabaseError("loadAiTriageResultSummaries", e);
        }

        return result;
    }

    @Override
    public synchronized String loadAiTriageResultJson(String messageId) {
        return loadAiTriageResultJson(messageId, "");
    }

    @Override
    public synchronized String loadAiTriageResultJson(String messageId, String matchSignatureHash) {
        if (isBlank(messageId)) {
            return null;
        }
        String safeMatchSignatureHash = safeString(matchSignatureHash);
        boolean filterByTarget = !safeMatchSignatureHash.isBlank();

        String querySql = String.format("""
                SELECT result_json
                FROM %s
                WHERE message_id = ?
                %s
                ORDER BY analyzed_at DESC, analysis_key ASC
                LIMIT 1
                """, AI_TRIAGE_RESULT_TABLE_NAME, filterByTarget ? "AND match_signature_hash = ?" : "");
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(querySql)) {
            statement.setString(1, messageId);
            if (filterByTarget) {
                statement.setString(2, safeMatchSignatureHash);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString("result_json") : null;
            }
        } catch (Exception e) {
            logDatabaseError("loadAiTriageResultJson", e);
            return null;
        }
    }

    @Override
    public synchronized int cleanupOrphanAiTriageResults() {
        String deleteSql = String.format("""
                DELETE FROM %s
                WHERE NOT EXISTS (
                    SELECT 1 FROM %s mh WHERE mh.message_id = %s.message_id
                )
                """, AI_TRIAGE_RESULT_TABLE_NAME, TABLE_NAME, AI_TRIAGE_RESULT_TABLE_NAME);
        try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
            return statement.executeUpdate(deleteSql);
        } catch (Exception e) {
            logDatabaseError("cleanupOrphanAiTriageResults", e);
            return 0;
        }
    }

    private List<AiTriageTask> loadAiTriageTasksByIds(List<String> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return new ArrayList<>();
        }

        String placeholders = String.join(",", Collections.nCopies(taskIds.size(), "?"));
        String querySql = String.format("""
                SELECT task_id, message_id, content_hash, analysis_key, match_signature_hash,
                       schema_version, prompt_version, model, config_hash, status, priority,
                       attempt_count, max_attempts, next_attempt_at, last_error_code,
                       last_error_message_sanitized, leased_until, lease_owner, lease_token, created_at, updated_at
                FROM %s
                WHERE task_id IN (%s)
                ORDER BY priority DESC, created_at ASC, task_id ASC
                """, AI_TRIAGE_TASK_TABLE_NAME, placeholders);
        List<AiTriageTask> result = new ArrayList<>();
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(querySql)) {
            for (int i = 0; i < taskIds.size(); i++) {
                statement.setString(i + 1, taskIds.get(i));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    result.add(toAiTriageTask(resultSet));
                }
            }
        } catch (Exception e) {
            logDatabaseError("loadAiTriageTasksByIds", e);
        }

        return result;
    }

    private AiTriageTask toAiTriageTask(ResultSet resultSet) throws SQLException {
        return new AiTriageTask(
                resultSet.getString("task_id"),
                resultSet.getString("message_id"),
                resultSet.getString("content_hash"),
                resultSet.getString("analysis_key"),
                resultSet.getString("match_signature_hash"),
                resultSet.getString("schema_version"),
                resultSet.getString("prompt_version"),
                resultSet.getString("model"),
                resultSet.getString("config_hash"),
                resultSet.getString("status"),
                resultSet.getInt("priority"),
                resultSet.getInt("attempt_count"),
                resultSet.getInt("max_attempts"),
                resultSet.getLong("next_attempt_at"),
                resultSet.getString("last_error_code"),
                resultSet.getString("last_error_message_sanitized"),
                resultSet.getLong("leased_until"),
                resultSet.getString("lease_owner"),
                resultSet.getString("lease_token"),
                resultSet.getLong("created_at"),
                resultSet.getLong("updated_at")
        );
    }

    private void bindAiTriageResultWrite(PreparedStatement statement, AiTriageResultWrite resultWrite) throws SQLException {
        statement.setString(1, resultWrite.messageId());
        statement.setString(2, safeString(resultWrite.contentHash()));
        statement.setString(3, resultWrite.analysisKey());
        statement.setString(4, safeString(resultWrite.matchSignatureHash()));
        statement.setString(5, safeString(resultWrite.status()));
        statement.setString(6, safeString(resultWrite.overallVerdict()));
        statement.setString(7, safeString(resultWrite.overallRiskLevel()));
        statement.setDouble(8, resultWrite.confidence());
        statement.setString(9, safeString(resultWrite.summary()));
        statement.setString(10, safeString(resultWrite.resultJson()));
        statement.setLong(11, Math.max(0L, resultWrite.analyzedAt()));
        statement.setString(12, safeString(resultWrite.schemaVersion()));
        statement.setString(13, safeString(resultWrite.promptVersion()));
        statement.setString(14, safeString(resultWrite.model()));
        statement.setString(15, safeString(resultWrite.configHash()));
    }

    private AiTriageResultSummary toAiTriageResultSummary(ResultSet resultSet) throws SQLException {
        String resultQuality = classifyAiResult(
                resultSet.getString("status"),
                resultSet.getString("overall_verdict"),
                resultSet.getString("overall_severity"),
                resultSet.getDouble("confidence"),
                resultSet.getString("summary"),
                resultSet.getString("result_json")
        );
        return new AiTriageResultSummary(
                resultSet.getString("message_id"),
                resultSet.getString("content_hash"),
                resultSet.getString("analysis_key"),
                resultSet.getString("match_signature_hash"),
                resultSet.getString("status"),
                resultSet.getString("overall_verdict"),
                resultSet.getString("overall_severity"),
                resultSet.getDouble("confidence"),
                resultSet.getString("summary"),
                resultSet.getLong("analyzed_at"),
                resultSet.getString("schema_version"),
                resultSet.getString("prompt_version"),
                resultSet.getString("model"),
                resultSet.getString("config_hash"),
                AiTriageResultQuality.EMPTY_ADVISORY.equals(resultQuality),
                AiTriageResultQuality.LOW_QUALITY_ADVISORY.equals(resultQuality)
        );
    }

    private List<String> distinctNonBlankValues(List<String> values) {
        List<String> result = new ArrayList<>();
        if (values == null || values.isEmpty()) {
            return result;
        }
        for (String value : values) {
            if (isBlank(value) || result.contains(value)) {
                continue;
            }
            result.add(value);
        }
        return result;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    private String truncateAiError(String errorMessage) {
        if (errorMessage == null) {
            return "";
        }

        if (errorMessage.length() <= MAX_AI_ERROR_LENGTH) {
            return errorMessage;
        }

        return errorMessage.substring(0, MAX_AI_ERROR_LENGTH);
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

    @Override
    public synchronized String createScopedDataboardScope(String source, String label) {
        String scopeId = UUID.randomUUID().toString();
        String insertSql = String.format("""
                INSERT INTO %s (scope_id, created_at, source, label)
                VALUES (?, ?, ?, ?)
                """, SCOPED_SCOPE_TABLE_NAME);

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(insertSql)) {
            statement.setString(1, scopeId);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, source == null ? "" : source);
            statement.setString(4, label == null ? "" : label);
            statement.executeUpdate();
            return scopeId;
        } catch (Exception e) {
            logDatabaseError("createScopedDataboardScope", e);
            return null;
        }
    }

    @Override
    public synchronized void saveScopedMessage(String scopeId,
                                               String scopedMessageId,
                                               HttpRequestResponse messageInfo,
                                               String url,
                                               String host,
                                               String method,
                                               String status,
                                               String length,
                                               String comment,
                                               String color,
                                               String contentHash) {
        if (scopeId == null || scopeId.isBlank() || scopedMessageId == null || scopedMessageId.isBlank() || messageInfo == null) {
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

            byte[] requestBytes = request.toByteArray().getBytes();
            byte[] responseBytes = response.toByteArray().getBytes();
            String safeContentHash = contentHash;
            if (safeContentHash == null || safeContentHash.isBlank()) {
                safeContentHash = calculateContentHash(requestBytes, responseBytes);
            }

            String safeHost = host;
            if (safeHost == null || safeHost.isBlank()) {
                safeHost = service.host();
            }

            String insertSql = String.format("""
                    INSERT INTO %s (
                        scope_id, scoped_message_id, scoped_order, created_at, host, url, method, status, length,
                        comment, color, content_hash, service_host, service_port, service_secure, request_bytes,
                        response_bytes, request_length, response_length
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, SCOPED_MESSAGE_TABLE_NAME);

            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement(insertSql)) {
                statement.setString(1, scopeId);
                statement.setString(2, scopedMessageId);
                statement.setLong(3, nextScopedMessageOrder(connection, scopeId));
                statement.setLong(4, System.currentTimeMillis());
                statement.setString(5, safeHost == null ? "" : safeHost);
                statement.setString(6, url == null ? "" : url);
                statement.setString(7, method == null ? "" : method);
                statement.setString(8, status == null ? "" : status);
                statement.setString(9, length == null ? String.valueOf(responseBytes.length) : length);
                statement.setString(10, comment == null ? "" : comment);
                statement.setString(11, color == null ? "" : color);
                statement.setString(12, safeContentHash == null ? "" : safeContentHash);
                statement.setString(13, service.host());
                statement.setInt(14, service.port());
                statement.setInt(15, service.secure() ? 1 : 0);
                statement.setBytes(16, requestBytes);
                statement.setBytes(17, responseBytes);
                statement.setInt(18, requestBytes.length);
                statement.setInt(19, responseBytes.length);
                statement.executeUpdate();
            }
        } catch (Exception e) {
            logDatabaseError("saveScopedMessage", e);
        }
    }

    @Override
    public synchronized void saveScopedMatches(String scopeId,
                                               String scopedMessageId,
                                               Map<String, List<String>> extractedDataByRule) {
        if (scopeId == null || scopeId.isBlank() || scopedMessageId == null || scopedMessageId.isBlank()) {
            return;
        }

        String deleteSql = String.format("DELETE FROM %s WHERE scope_id = ? AND scoped_message_id = ?", SCOPED_MATCH_TABLE_NAME);
        String insertSql = String.format("""
                INSERT INTO %s (scope_id, scoped_message_id, rule_name, extracted_value)
                VALUES (?, ?, ?, ?)
                """, SCOPED_MATCH_TABLE_NAME);

        try (Connection connection = getConnection();
             PreparedStatement deleteStatement = connection.prepareStatement(deleteSql);
             PreparedStatement insertStatement = connection.prepareStatement(insertSql)) {
            connection.setAutoCommit(false);
            saveScopedMatchData(scopeId, scopedMessageId, extractedDataByRule, deleteStatement, insertStatement);
            connection.commit();
        } catch (Exception e) {
            logDatabaseError("saveScopedMatches", e);
        }
    }

    @Override
    public synchronized int countScopedMessageMetadata(String scopeId,
                                                       String hostPattern,
                                                       String commentKeyword,
                                                       String messageTableName,
                                                       String messageFilterValue) {
        if (scopeId == null || scopeId.isBlank()) {
            return 0;
        }

        StringBuilder querySql = new StringBuilder(String.format("SELECT COUNT(*) AS total FROM %s WHERE scope_id = ?", SCOPED_MESSAGE_TABLE_NAME));
        List<Object> parameters = new ArrayList<>();
        parameters.add(scopeId);
        appendScopedMetadataFilterClause(querySql, parameters, hostPattern, commentKeyword, messageTableName, messageFilterValue);

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(querySql.toString())) {
            bindParameters(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getInt("total");
                }
            }
        } catch (Exception e) {
            logDatabaseError("countScopedMessageMetadata", e);
        }

        return 0;
    }

    @Override
    public synchronized List<MessageMetadata> loadScopedMessageMetadataPage(String scopeId,
                                                                            String hostPattern,
                                                                            String commentKeyword,
                                                                            String messageTableName,
                                                                            String messageFilterValue,
                                                                            int limit,
                                                                            int offset) {
        if (scopeId == null || scopeId.isBlank()) {
            return new ArrayList<>();
        }

        int safeLimit = Math.max(1, limit);
        int safeOffset = Math.max(0, offset);
        StringBuilder querySql = new StringBuilder(String.format("""
                SELECT scoped_message_id, method, url, comment, length, color, status, content_hash
                FROM %s
                WHERE scope_id = ?
                """, SCOPED_MESSAGE_TABLE_NAME));
        List<Object> parameters = new ArrayList<>();
        parameters.add(scopeId);
        appendScopedMetadataFilterClause(querySql, parameters, hostPattern, commentKeyword, messageTableName, messageFilterValue);
        querySql.append(" ORDER BY scoped_order ASC, created_at ASC, scoped_message_id ASC LIMIT ? OFFSET ?");
        parameters.add(safeLimit);
        parameters.add(safeOffset);

        return loadScopedMessageMetadata(querySql.toString(), parameters, "loadScopedMessageMetadataPage");
    }

    @Override
    public synchronized List<MessageMetadata> loadScopedMessageMetadataByFilter(String scopeId,
                                                                                String hostPattern,
                                                                                String commentKeyword,
                                                                                String messageTableName,
                                                                                String messageFilterValue) {
        if (scopeId == null || scopeId.isBlank()) {
            return new ArrayList<>();
        }

        StringBuilder querySql = new StringBuilder(String.format("""
                SELECT scoped_message_id, method, url, comment, length, color, status, content_hash
                FROM %s
                WHERE scope_id = ?
                """, SCOPED_MESSAGE_TABLE_NAME));
        List<Object> parameters = new ArrayList<>();
        parameters.add(scopeId);
        appendScopedMetadataFilterClause(querySql, parameters, hostPattern, commentKeyword, messageTableName, messageFilterValue);
        querySql.append(" ORDER BY scoped_order ASC, created_at ASC, scoped_message_id ASC");

        return loadScopedMessageMetadata(querySql.toString(), parameters, "loadScopedMessageMetadataByFilter");
    }

    @Override
    public synchronized Map<String, List<String>> loadScopedExtractedData(String scopeId,
                                                                          String hostPattern,
                                                                          String ruleName,
                                                                          String extractedValue) {
        Map<String, List<String>> result = new java.util.LinkedHashMap<>();
        if (scopeId == null || scopeId.isBlank()) {
            return result;
        }

        StringBuilder querySql = new StringBuilder(String.format("""
                SELECT DISTINCT sm.rule_name, sm.extracted_value
                FROM %s sm
                INNER JOIN %s mh ON mh.scope_id = sm.scope_id AND mh.scoped_message_id = sm.scoped_message_id
                WHERE sm.scope_id = ?
                """, SCOPED_MATCH_TABLE_NAME, SCOPED_MESSAGE_TABLE_NAME));
        List<Object> parameters = new ArrayList<>();
        parameters.add(scopeId);
        appendHostFilterClause(querySql, parameters, hostPattern);

        boolean hasRuleFilter = ruleName != null && !ruleName.isBlank() && !isAllToken(ruleName);
        boolean hasValueFilter = extractedValue != null && !extractedValue.isBlank() && !isAllToken(extractedValue);
        if (hasRuleFilter) {
            querySql.append(" AND sm.rule_name = ?");
            parameters.add(ruleName);
        }
        if (hasValueFilter) {
            querySql.append(" AND sm.extracted_value = ?");
            parameters.add(extractedValue);
        }
        querySql.append(" ORDER BY sm.rule_name ASC, sm.extracted_value ASC");

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(querySql.toString())) {
            bindParameters(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String matchedRuleName = resultSet.getString("rule_name");
                    String matchedExtractedValue = resultSet.getString("extracted_value");
                    if (matchedRuleName == null || matchedRuleName.isBlank()
                            || matchedExtractedValue == null || matchedExtractedValue.isBlank()) {
                        continue;
                    }
                    List<String> values = result.computeIfAbsent(matchedRuleName, ignored -> new ArrayList<>());
                    if (!values.contains(matchedExtractedValue)) {
                        values.add(matchedExtractedValue);
                    }
                }
            }
        } catch (Exception e) {
            logDatabaseError("loadScopedExtractedData", e);
        }

        return result;
    }

    @Override
    public synchronized List<String> loadScopedMatchedHosts(String scopeId) {
        List<String> result = new ArrayList<>();
        if (scopeId == null || scopeId.isBlank()) {
            return result;
        }

        String querySql = String.format("""
                SELECT DISTINCT host
                FROM %s
                WHERE scope_id = ? AND host <> ''
                ORDER BY host ASC
                """, SCOPED_MESSAGE_TABLE_NAME);

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(querySql)) {
            statement.setString(1, scopeId);
            try (ResultSet resultSet = statement.executeQuery()) {
                boolean hasMatchedHost = false;
                while (resultSet.next()) {
                    String host = resultSet.getString("host");
                    if (host == null || host.isBlank()) {
                        continue;
                    }
                    hasMatchedHost = true;
                    addHostAggregationEntries(result, host);
                }
                if (hasMatchedHost && !result.contains("*")) {
                    result.add("*");
                }
            }
        } catch (Exception e) {
            logDatabaseError("loadScopedMatchedHosts", e);
        }

        return result;
    }

    @Override
    public synchronized HttpRequestResponse loadScopedMessage(String scopeId, String scopedMessageId) {
        if (scopeId == null || scopeId.isBlank() || scopedMessageId == null || scopedMessageId.isBlank()) {
            return null;
        }

        String querySql = String.format("""
                SELECT service_host, service_port, service_secure, request_bytes, response_bytes
                FROM %s
                WHERE scope_id = ? AND scoped_message_id = ?
                """, SCOPED_MESSAGE_TABLE_NAME);

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(querySql)) {
            statement.setString(1, scopeId);
            statement.setString(2, scopedMessageId);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }

                return toHttpRequestResponse(resultSet);
            }
        } catch (Exception e) {
            logDatabaseError("loadScopedMessage", e);
            return null;
        }
    }

    @Override
    public synchronized int deleteScopedDataboardScope(String scopeId) {
        if (scopeId == null || scopeId.isBlank()) {
            return 0;
        }

        String deleteMatchSql = String.format("DELETE FROM %s WHERE scope_id = ?", SCOPED_MATCH_TABLE_NAME);
        String deleteMessageSql = String.format("DELETE FROM %s WHERE scope_id = ?", SCOPED_MESSAGE_TABLE_NAME);
        String deleteScopeSql = String.format("DELETE FROM %s WHERE scope_id = ?", SCOPED_SCOPE_TABLE_NAME);

        try (Connection connection = getConnection();
             PreparedStatement deleteMatchStatement = connection.prepareStatement(deleteMatchSql);
             PreparedStatement deleteMessageStatement = connection.prepareStatement(deleteMessageSql);
             PreparedStatement deleteScopeStatement = connection.prepareStatement(deleteScopeSql)) {
            connection.setAutoCommit(false);
            deleteMatchStatement.setString(1, scopeId);
            deleteMatchStatement.executeUpdate();
            deleteMessageStatement.setString(1, scopeId);
            deleteMessageStatement.executeUpdate();
            deleteScopeStatement.setString(1, scopeId);
            int deletedScopes = deleteScopeStatement.executeUpdate();
            connection.commit();
            return deletedScopes;
        } catch (Exception e) {
            logDatabaseError("deleteScopedDataboardScope", e);
            return 0;
        }
    }

    @Override
    public synchronized int deleteAllScopedDataboardScopes() {
        String deleteMatchSql = String.format("DELETE FROM %s", SCOPED_MATCH_TABLE_NAME);
        String deleteMessageSql = String.format("DELETE FROM %s", SCOPED_MESSAGE_TABLE_NAME);
        String deleteScopeSql = String.format("DELETE FROM %s", SCOPED_SCOPE_TABLE_NAME);

        try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeUpdate(deleteMatchSql);
            statement.executeUpdate(deleteMessageSql);
            int deletedScopes = statement.executeUpdate(deleteScopeSql);
            connection.commit();
            return deletedScopes;
        } catch (Exception e) {
            logDatabaseError("deleteAllScopedDataboardScopes", e);
            return 0;
        }
    }

    private long nextScopedMessageOrder(Connection connection, String scopeId) throws SQLException {
        String querySql = String.format("SELECT COALESCE(MAX(scoped_order), 0) + 1 FROM %s WHERE scope_id = ?", SCOPED_MESSAGE_TABLE_NAME);
        try (PreparedStatement statement = connection.prepareStatement(querySql)) {
            statement.setString(1, scopeId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 1;
            }
        }
    }

    private void saveScopedMatchData(String scopeId,
                                     String scopedMessageId,
                                     Map<String, List<String>> extractedDataByRule,
                                     PreparedStatement deleteMatchStatement,
                                     PreparedStatement insertMatchStatement) throws SQLException {
        deleteMatchStatement.setString(1, scopeId);
        deleteMatchStatement.setString(2, scopedMessageId);
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

                insertMatchStatement.setString(1, scopeId);
                insertMatchStatement.setString(2, scopedMessageId);
                insertMatchStatement.setString(3, ruleName);
                insertMatchStatement.setString(4, extractedValue);
                insertMatchStatement.addBatch();
            }
        }

        insertMatchStatement.executeBatch();
    }

    private List<MessageMetadata> loadScopedMessageMetadata(String querySql,
                                                            List<Object> parameters,
                                                            String methodName) {
        List<MessageMetadata> result = new ArrayList<>();

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(querySql)) {
            bindParameters(statement, parameters);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    result.add(new MessageMetadata(
                            resultSet.getString("scoped_message_id"),
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
            logDatabaseError(methodName, e);
        }

        return result;
    }

    private void appendScopedMetadataFilterClause(StringBuilder querySql,
                                                  List<Object> parameters,
                                                  String hostPattern,
                                                  String commentKeyword,
                                                  String messageTableName,
                                                  String messageFilterValue) {
        appendHostFilterClause(querySql, parameters, hostPattern);
        appendCommentFilterClause(querySql, parameters, commentKeyword);
        appendScopedMessageFilterClause(querySql, parameters, messageTableName, messageFilterValue);
    }

    private void appendScopedMessageFilterClause(StringBuilder querySql,
                                                 List<Object> parameters,
                                                 String messageTableName,
                                                 String messageFilterValue) {
        boolean hasTableFilter = messageTableName != null && !messageTableName.isBlank() && !isAllToken(messageTableName);
        boolean hasValueFilter = messageFilterValue != null && !messageFilterValue.isBlank() && !isAllToken(messageFilterValue);

        if (!hasTableFilter && !hasValueFilter) {
            return;
        }

        querySql.append(" AND EXISTS (SELECT 1 FROM ")
                .append(SCOPED_MATCH_TABLE_NAME)
                .append(" sm WHERE sm.scope_id = ")
                .append(SCOPED_MESSAGE_TABLE_NAME)
                .append(".scope_id AND sm.scoped_message_id = ")
                .append(SCOPED_MESSAGE_TABLE_NAME)
                .append(".scoped_message_id");

        if (hasTableFilter) {
            querySql.append(" AND sm.rule_name = ?");
            parameters.add(messageTableName);
        }

        if (hasValueFilter) {
            querySql.append(" AND sm.extracted_value = ?");
            parameters.add(messageFilterValue);
        }

        querySql.append(")");
    }

    @Override
    public synchronized List<MessageMetadata> loadMessageMetadata() {
        List<MessageMetadata> result = new ArrayList<>();
        String querySql = String.format("""
                SELECT message_id, method, url, comment, length, color, status, content_hash
                FROM %s
                WHERE regex_status = 'DONE' AND comment <> ''
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

    @Override
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

    @Override
    public synchronized boolean existsDuplicateContent(String contentHash) {
        if (contentHash == null || contentHash.isBlank()) {
            return false;
        }

        String querySql = String.format("""
                SELECT 1
                FROM %s
                WHERE content_hash = ?
                LIMIT 1
                """, TABLE_NAME);

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(querySql)) {
            statement.setString(1, contentHash);

            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (Exception e) {
            logDatabaseError("existsDuplicateContent", e);
            return false;
        }
    }

    @Override
    public synchronized int countMessageMetadata(String hostPattern, String commentKeyword) {
        return countMessageMetadata(hostPattern, commentKeyword, "", "");
    }

    @Override
    public synchronized int countMessageMetadata(String hostPattern, String commentKeyword, String messageTableName, String messageFilterValue) {
        StringBuilder querySql = new StringBuilder(String.format("SELECT COUNT(*) AS total FROM %s WHERE regex_status = 'DONE' AND comment <> ''", TABLE_NAME));
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

    @Override
    public synchronized List<MessageMetadata> loadMessageMetadataPage(String hostPattern, String commentKeyword, int limit, int offset) {
        return loadMessageMetadataPage(hostPattern, commentKeyword, "", "", limit, offset);
    }

    @Override
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
                WHERE regex_status = 'DONE' AND comment <> ''
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

    @Override
    public synchronized List<MessageMetadata> loadMessageMetadataByFilter(String hostPattern, String commentKeyword) {
        return loadMessageMetadataByFilter(hostPattern, commentKeyword, "", "");
    }

    @Override
    public synchronized List<MessageMetadata> loadMessageMetadataByFilter(String hostPattern,
                                                                          String commentKeyword,
                                                                          String messageTableName,
                                                                          String messageFilterValue) {
        StringBuilder querySql = new StringBuilder(String.format("""
                SELECT message_id, method, url, comment, length, color, status, content_hash
                FROM %s
                WHERE regex_status = 'DONE' AND comment <> ''
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

    @Override
    public synchronized Map<String, List<String>> loadExtractedDataByHost(String hostPattern) {
        Map<String, List<String>> result = new java.util.LinkedHashMap<>();
        StringBuilder querySql = new StringBuilder(String.format("""
                SELECT mm.rule_name, mm.extracted_value
                FROM %s mm
                INNER JOIN %s mh ON mh.message_id = mm.message_id
                WHERE mh.regex_status = 'DONE'
                """, MATCH_TABLE_NAME, TABLE_NAME));
        List<Object> parameters = new ArrayList<>();
        appendHostFilterClause(querySql, parameters, hostPattern);
        querySql.append(" ORDER BY mm.rule_name ASC, mm.extracted_value ASC");

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(querySql.toString())) {
            bindParameters(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String ruleName = resultSet.getString("rule_name");
                    String extractedValue = resultSet.getString("extracted_value");
                    if (ruleName == null || ruleName.isBlank() || extractedValue == null || extractedValue.isBlank()) {
                        continue;
                    }
                    List<String> values = result.computeIfAbsent(ruleName, ignored -> new ArrayList<>());
                    if (!values.contains(extractedValue)) {
                        values.add(extractedValue);
                    }
                }
            }
        } catch (Exception e) {
            logDatabaseError("loadExtractedDataByHost", e);
        }

        return result;
    }

    @Override
    public synchronized List<String> loadMatchedHosts() {
        List<String> result = new ArrayList<>();
        String querySql = String.format("""
                SELECT DISTINCT host
                FROM %s
                WHERE regex_status = 'DONE' AND comment <> '' AND host <> ''
                ORDER BY host ASC
                """, TABLE_NAME);

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(querySql);
             ResultSet resultSet = statement.executeQuery()) {
            boolean hasMatchedHost = false;
            while (resultSet.next()) {
                String host = resultSet.getString("host");
                if (host == null || host.isBlank()) {
                    continue;
                }
                hasMatchedHost = true;
                addHostAggregationEntries(result, host);
            }
            if (hasMatchedHost && !result.contains("*")) {
                result.add("*");
            }
        } catch (Exception e) {
            logDatabaseError("loadMatchedHosts", e);
        }

        return result;
    }

    private void addHostAggregationEntries(List<String> hosts, String host) {
        if (!hosts.contains(host)) {
            hosts.add(host);
        }

        String hostWithoutPort = StringProcessor.extractHostname(host);
        if (hostWithoutPort == null || hostWithoutPort.isBlank() || StringProcessor.matchHostIsIp(hostWithoutPort)) {
            return;
        }

        String[] segments = hostWithoutPort.split("\\.");
        if (segments.length <= 2) {
            return;
        }

        String wildcardHost = StringProcessor.replaceFirstOccurrence(hostWithoutPort, segments[0], "*");
        if (!wildcardHost.isBlank() && !hosts.contains(wildcardHost)) {
            hosts.add(wildcardHost);
        }
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

    @Override
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

                return toHttpRequestResponse(resultSet);
            }
        } catch (Exception e) {
            logDatabaseError("loadMessage", e);
            return null;
        }
    }

    @Override
    public synchronized StoredMessage loadStoredMessage(String messageId) {
        String querySql = String.format("""
                SELECT host, content_hash, service_host, service_port, service_secure, request_bytes, response_bytes
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

                String host = resultSet.getString("host");
                String contentHash = resultSet.getString("content_hash");
                HttpRequestResponse requestResponse = toHttpRequestResponse(resultSet);
                if (requestResponse == null) {
                    return null;
                }

                return new StoredMessage(messageId, host, contentHash, requestResponse);
            }
        } catch (Exception e) {
            logDatabaseError("loadStoredMessage", e);
            return null;
        }
    }

    @Override
    public synchronized Map<String, List<String>> loadMessageExtractedData(String messageId) {
        Map<String, List<String>> result = new java.util.LinkedHashMap<>();
        if (messageId == null || messageId.isBlank()) {
            return result;
        }

        String querySql = String.format("""
                SELECT rule_name, extracted_value
                FROM %s
                WHERE message_id = ?
                ORDER BY rule_name ASC, extracted_value ASC
                """, MATCH_TABLE_NAME);

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(querySql)) {
            statement.setString(1, messageId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String ruleName = resultSet.getString("rule_name");
                    String extractedValue = resultSet.getString("extracted_value");
                    if (ruleName == null || ruleName.isBlank() || extractedValue == null || extractedValue.isBlank()) {
                        continue;
                    }
                    List<String> values = result.computeIfAbsent(ruleName, ignored -> new ArrayList<>());
                    if (!values.contains(extractedValue)) {
                        values.add(extractedValue);
                    }
                }
            }
        } catch (Exception e) {
            logDatabaseError("loadMessageExtractedData", e);
        }

        return result;
    }

    private HttpRequestResponse toHttpRequestResponse(ResultSet resultSet) throws SQLException {
        String serviceHost = resultSet.getString("service_host");
        int servicePort = resultSet.getInt("service_port");
        boolean serviceSecure = resultSet.getInt("service_secure") == 1;
        byte[] requestBytes = resultSet.getBytes("request_bytes");
        byte[] responseBytes = resultSet.getBytes("response_bytes");
        if (requestBytes == null) {
            requestBytes = new byte[0];
        }
        if (responseBytes == null) {
            responseBytes = new byte[0];
        }

        HttpService httpService = createHttpService(serviceHost, servicePort, serviceSecure);
        if (httpService == null) {
            httpService = fallbackHttpService(serviceHost, servicePort, serviceSecure);
        }
        ByteArray requestByteArray = createByteArray(requestBytes);
        if (requestByteArray == null) {
            requestByteArray = fallbackByteArray(requestBytes);
        }
        ByteArray responseByteArray = createByteArray(responseBytes);
        if (responseByteArray == null) {
            responseByteArray = fallbackByteArray(responseBytes);
        }
        HttpRequest httpRequest = createHttpRequest(httpService, requestByteArray);
        if (httpRequest == null) {
            httpRequest = fallbackHttpRequest(httpService, requestBytes);
        }
        HttpResponse httpResponse = createHttpResponse(responseByteArray);
        if (httpResponse == null) {
            httpResponse = fallbackHttpResponse(responseBytes);
        }

        HttpRequestResponse requestResponse = createHttpRequestResponse(httpRequest, httpResponse);
        if (requestResponse == null) {
            return fallbackHttpRequestResponse(httpRequest, httpResponse);
        }

        return requestResponse;
    }

    private HttpService createHttpService(String host, int port, boolean secure) {
        try {
            return HttpService.httpService(host, port, secure);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private ByteArray createByteArray(byte[] bytes) {
        try {
            return ByteArray.byteArray(bytes);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private HttpRequest createHttpRequest(HttpService service, ByteArray bytes) {
        try {
            return HttpRequest.httpRequest(service, bytes);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private HttpResponse createHttpResponse(ByteArray bytes) {
        try {
            return HttpResponse.httpResponse(bytes);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private HttpRequestResponse createHttpRequestResponse(HttpRequest request, HttpResponse response) {
        try {
            return HttpRequestResponse.httpRequestResponse(request, response);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private HttpRequestResponse fallbackHttpRequestResponse(HttpRequest request, HttpResponse response) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "request" -> request;
            case "response" -> response;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpRequestResponse.class, handler);
    }

    private HttpRequest fallbackHttpRequest(HttpService service, byte[] requestBytes) {
        ByteArray byteArray = fallbackByteArray(requestBytes);
        ByteArray bodyByteArray = fallbackByteArray(messageBodyBytes(requestBytes));
        String firstLine = messageFirstLine(requestBytes);
        String[] requestLineParts = firstLine.split("\\s+", 3);
        String methodName = requestLineParts.length > 0 && !requestLineParts[0].isBlank() ? requestLineParts[0] : "";
        String path = requestLineParts.length > 1 && !requestLineParts[1].isBlank() ? requestLineParts[1] : "/";
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "httpService" -> service;
            case "toByteArray" -> byteArray;
            case "body" -> bodyByteArray;
            case "headers" -> Collections.emptyList();
            case "method" -> methodName;
            case "path" -> path;
            case "url" -> fallbackUrl(service, path);
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpRequest.class, handler);
    }

    private HttpResponse fallbackHttpResponse(byte[] responseBytes) {
        ByteArray byteArray = fallbackByteArray(responseBytes);
        ByteArray bodyByteArray = fallbackByteArray(messageBodyBytes(responseBytes));
        short statusCode = fallbackStatusCode(responseBytes);
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "toByteArray" -> byteArray;
            case "body" -> bodyByteArray;
            case "headers" -> Collections.emptyList();
            case "statusCode" -> statusCode;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpResponse.class, handler);
    }

    private byte[] messageBodyBytes(byte[] messageBytes) {
        if (messageBytes == null || messageBytes.length == 0) {
            return new byte[0];
        }
        for (int i = 0; i < messageBytes.length - 3; i++) {
            if (messageBytes[i] == '\r'
                    && messageBytes[i + 1] == '\n'
                    && messageBytes[i + 2] == '\r'
                    && messageBytes[i + 3] == '\n') {
                return java.util.Arrays.copyOfRange(messageBytes, i + 4, messageBytes.length);
            }
        }
        for (int i = 0; i < messageBytes.length - 1; i++) {
            if (messageBytes[i] == '\n' && messageBytes[i + 1] == '\n') {
                return java.util.Arrays.copyOfRange(messageBytes, i + 2, messageBytes.length);
            }
        }
        return new byte[0];
    }

    private String messageFirstLine(byte[] messageBytes) {
        if (messageBytes == null || messageBytes.length == 0) {
            return "";
        }
        String message = new String(messageBytes, StandardCharsets.ISO_8859_1);
        int crlf = message.indexOf("\r\n");
        if (crlf >= 0) {
            return message.substring(0, crlf);
        }
        int lf = message.indexOf('\n');
        return lf >= 0 ? message.substring(0, lf) : message;
    }

    private short fallbackStatusCode(byte[] responseBytes) {
        String firstLine = messageFirstLine(responseBytes);
        String[] parts = firstLine.split("\\s+", 3);
        if (parts.length > 1) {
            try {
                return Short.parseShort(parts[1]);
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private String fallbackUrl(HttpService service, String path) {
        if (service == null) {
            return path == null || path.isBlank() ? "/" : path;
        }
        String scheme = service.secure() ? "https" : "http";
        String host = service.host() == null ? "" : service.host();
        int port = service.port();
        String safePath = path == null || path.isBlank() ? "/" : path;
        StringBuilder builder = new StringBuilder(scheme).append("://").append(host);
        if (port > 0 && !((service.secure() && port == 443) || (!service.secure() && port == 80))) {
            builder.append(':').append(port);
        }
        builder.append(safePath.startsWith("/") ? safePath : "/" + safePath);
        return builder.toString();
    }

    private HttpService fallbackHttpService(String host, int port, boolean secure) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "host" -> host == null ? "" : host;
            case "port" -> port;
            case "secure" -> secure;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpService.class, handler);
    }

    private ByteArray fallbackByteArray(byte[] bytes) {
        byte[] safeBytes = bytes == null ? new byte[0] : bytes;
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getBytes" -> safeBytes;
            case "length" -> safeBytes.length;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(ByteArray.class, handler);
    }

    private <T> T proxyFor(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private Object defaultProxyValue(Object proxy, Method method, Object[] args) {
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
            return proxyFor(returnType, (nestedProxy, nestedMethod, nestedArgs) -> defaultProxyValue(nestedProxy, nestedMethod, nestedArgs));
        }
        return null;
    }

    private Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "fallback proxy for " + proxy.getClass().getInterfaces()[0].getName();
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> null;
        };
    }

    @Override
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
        String deleteAiTaskSql = String.format("DELETE FROM %s WHERE message_id = ?", AI_TRIAGE_TASK_TABLE_NAME);
        String deleteAiResultSql = String.format("DELETE FROM %s WHERE message_id = ?", AI_TRIAGE_RESULT_TABLE_NAME);
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(deleteSql);
             PreparedStatement deleteMatchStatement = connection.prepareStatement(deleteMatchSql);
             PreparedStatement deleteAiTaskStatement = connection.prepareStatement(deleteAiTaskSql);
             PreparedStatement deleteAiResultStatement = connection.prepareStatement(deleteAiResultSql)) {
            connection.setAutoCommit(false);
            for (String id : idsToDelete) {
                statement.setString(1, id);
                statement.addBatch();

                deleteMatchStatement.setString(1, id);
                deleteMatchStatement.addBatch();

                deleteAiTaskStatement.setString(1, id);
                deleteAiTaskStatement.addBatch();

                deleteAiResultStatement.setString(1, id);
                deleteAiResultStatement.addBatch();
            }
            deleteAiResultStatement.executeBatch();
            deleteAiTaskStatement.executeBatch();
            deleteMatchStatement.executeBatch();
            statement.executeBatch();
            connection.commit();
            return idsToDelete.size();
        } catch (Exception e) {
            logDatabaseError("deleteByHostPattern(delete)", e);
            return 0;
        }
    }

    @Override
    public synchronized int deleteAllMessages() {
        String deleteSql = String.format("DELETE FROM %s", TABLE_NAME);
        String deleteMatchSql = String.format("DELETE FROM %s", MATCH_TABLE_NAME);
        String deleteAiTaskSql = String.format("DELETE FROM %s", AI_TRIAGE_TASK_TABLE_NAME);
        String deleteAiResultSql = String.format("DELETE FROM %s", AI_TRIAGE_RESULT_TABLE_NAME);
        try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeUpdate(deleteAiResultSql);
            statement.executeUpdate(deleteAiTaskSql);
            statement.executeUpdate(deleteMatchSql);
            int deletedRows = statement.executeUpdate(deleteSql);
            connection.commit();
            return deletedRows;
        } catch (Exception e) {
            logDatabaseError("deleteAllMessages", e);
            return 0;
        }
    }

    private String calculateContentHash(byte[] requestBytes, byte[] responseBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            if (requestBytes != null) {
                digest.update(requestBytes);
            }
            if (responseBytes != null) {
                digest.update(responseBytes);
            }
            return bytesToHex(digest.digest());
        } catch (Exception e) {
            logDatabaseError("calculateContentHash", e);
            return "";
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    private String truncateError(String errorMessage) {
        if (errorMessage == null) {
            return "";
        }

        if (errorMessage.length() <= MAX_REGEX_ERROR_LENGTH) {
            return errorMessage;
        }

        return errorMessage.substring(0, MAX_REGEX_ERROR_LENGTH);
    }

    @Override
    public String getDatabasePath() {
        return dbPath;
    }
}
