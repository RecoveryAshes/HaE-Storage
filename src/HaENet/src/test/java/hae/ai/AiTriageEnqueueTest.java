package hae.ai;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import hae.TestFixtures;
import hae.storage.SqliteMessageStore;
import hae.utils.ConfigLoader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiTriageEnqueueTest {
    private static final String AI_TASK_TABLE = "ai_triage_task";
    private static final String MESSAGE_TABLE = "message_history";

    @TempDir
    Path tempDirectory;

    @Test
    void sensitiveMatchEnqueuesOncePerTarget() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("sensitive-once"));
        SqliteMessageStore store = context.store();
        HttpRequestResponse requestResponse = requestResponse("Example.TEST", "POST", "/api/login?debug=true");
        String messageId = "message-sensitive";
        String contentHash = "content-hash-sensitive";
        assertTrue(store.savePendingMessage(
                messageId,
                requestResponse,
                "https://example.test/api/login?debug=true",
                "Example.TEST",
                "POST",
                "200",
                "128",
                contentHash,
                "",
                "",
                false
        ).isSaved());
        Map<String, List<String>> matches = Map.of(
                "JSON Web Token", List.of("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0"),
                "JWT", List.of("eyJraWQiOiJhIn0.eyJyb2xlIjoiYWRtaW4ifQ"),
                "All URL", List.of("https://noisy.example.test/path")
        );

        assertTrue(store.completeRegexProcessing(messageId, "JSON Web Token, JWT, All URL", "green", matches));
        AiTriageEnqueueService service = new AiTriageEnqueueService(store);
        AiConfig config = enabledConfig(10, List.of(new AiWhitelistRule("Sensitive Information", List.of("JSON Web Token", "JWT"))));

        AiTriageEnqueueService.EnqueueResult first = service.enqueueAfterRegexPersistence(
                messageId,
                contentHash,
                requestResponse,
                matches,
                config
        );
        AiTriageEnqueueService.EnqueueResult second = service.enqueueAfterRegexPersistence(
                messageId,
                contentHash,
                requestResponse,
                matches,
                config
        );

        try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(context.databasePath()))) {
            assertAll(
                    () -> assertTrue(first.isEnqueued()),
                    () -> assertEquals("duplicate", second.getStatus()),
                    () -> assertEquals(2, TestFixtures.countRows(connection, AI_TASK_TABLE)),
                    () -> assertEquals(2, first.getMatchCount()),
                    () -> assertEquals(2, first.getTargetCount()),
                    () -> assertEquals(0, first.getDuplicateCount()),
                    () -> assertEquals(2, second.getDuplicateCount()),
                    () -> assertEquals(2, distinctCount(connection, "analysis_key", messageId)),
                    () -> assertEquals(2, distinctCount(connection, "match_signature_hash", messageId)),
                    () -> assertEquals(AiTriageSchema.SCHEMA_VERSION, singleString(connection, "schema_version", "message_id", messageId)),
                    () -> assertEquals(AiTriageSchema.PROMPT_VERSION, singleString(connection, "prompt_version", "message_id", messageId)),
                    () -> assertEquals("model-a", singleString(connection, "model", "message_id", messageId)),
                    () -> assertEquals(first.getConfigHash(), singleString(connection, "config_hash", "message_id", messageId)),
                    () -> assertEquals(contentHash, singleString(connection, "content_hash", "message_id", messageId)),
                    () -> assertEquals("PENDING", singleString(connection, "status", "message_id", messageId))
            );
        }
    }

    @Test
    void noisyRulesDoNotEnqueueByDefault() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("noisy-default"));
        SqliteMessageStore store = context.store();
        HttpRequestResponse requestResponse = requestResponse("example.test", "GET", "/assets/app.js");
        Map<String, List<String>> noisyMatches = Map.of(
                "Linkfinder", List.of("/assets/app.js"),
                "All URL", List.of("https://example.test/assets/app.js")
        );

        store.saveMessage(
                "message-noisy",
                requestResponse,
                "https://example.test/assets/app.js",
                "GET",
                "200",
                "64",
                "Linkfinder, All URL",
                "gray",
                "content-hash-noisy",
                noisyMatches
        );

        AiTriageEnqueueService.EnqueueResult result = new AiTriageEnqueueService(store).enqueueAfterRegexPersistence(
                "message-noisy",
                "content-hash-noisy",
                requestResponse,
                noisyMatches,
                enabledConfig(10, List.of(new AiWhitelistRule("Sensitive Information", List.of("JSON Web Token", "JWT", "idCard", "身份证"))))
        );

        assertAll(
                () -> assertEquals("skipped_no_whitelisted_match", result.getStatus()),
                () -> assertEquals(0, tableCount(context.databasePath(), AI_TASK_TABLE))
        );
    }

    @Test
    void aiDisabledDoesNotEnqueue() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("disabled"));
        SqliteMessageStore store = context.store();
        HttpRequestResponse requestResponse = requestResponse("example.test", "GET", "/api/token");
        Map<String, List<String>> matches = Map.of("JSON Web Token", List.of("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0"));
        store.saveMessage("message-disabled", requestResponse, "https://example.test/api/token", "GET", "200", "42",
                "JSON Web Token", "green", "content-hash-disabled", matches);

        AiTriageEnqueueService.EnqueueResult result = new AiTriageEnqueueService(store).enqueueAfterRegexPersistence(
                "message-disabled",
                "content-hash-disabled",
                requestResponse,
                matches,
                disabledConfig()
        );

        assertAll(
                () -> assertEquals("skipped_disabled", result.getStatus()),
                () -> assertEquals(0, tableCount(context.databasePath(), AI_TASK_TABLE))
        );
    }

    @Test
    void duplicateReprocessingDoesNotCreateSecondTask() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("duplicate-reprocess"));
        SqliteMessageStore store = context.store();
        HttpRequestResponse requestResponse = requestResponse("example.test", "GET", "/api/token");
        Map<String, List<String>> matches = Map.of("JSON Web Token", List.of("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0"));
        store.saveMessage("message-reprocess", requestResponse, "https://example.test/api/token", "GET", "200", "42",
                "JSON Web Token", "green", "content-hash-reprocess", matches);
        AiTriageEnqueueService service = new AiTriageEnqueueService(store);
        AiConfig config = enabledConfig(10, List.of(new AiWhitelistRule("Sensitive Information", List.of("JSON Web Token"))));

        AiTriageEnqueueService.EnqueueResult first = service.enqueueAfterRegexPersistence(
                "message-reprocess", "content-hash-reprocess", requestResponse, matches, config);
        AiTriageEnqueueService.EnqueueResult second = service.enqueueAfterRegexPersistence(
                "message-reprocess", "content-hash-reprocess", requestResponse, matches, config);

        assertAll(
                () -> assertTrue(first.isEnqueued()),
                () -> assertEquals("duplicate", second.getStatus()),
                () -> assertEquals(1, tableCount(context.databasePath(), AI_TASK_TABLE))
        );
    }

    @Test
    void analyzeOncePerMessageBlocksReanalysisWhenConfigChanges() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("once-per-message-config-change"));
        SqliteMessageStore store = context.store();
        HttpRequestResponse requestResponse = requestResponse("example.test", "GET", "/api/token");
        Map<String, List<String>> matches = Map.of("JSON Web Token", List.of("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0"));
        store.saveMessage("message-once", requestResponse, "https://example.test/api/token", "GET", "200", "42",
                "JSON Web Token", "green", "content-hash-once", matches);
        AiTriageEnqueueService service = new AiTriageEnqueueService(store);
        AiConfig firstConfig = enabledConfig(10, List.of(new AiWhitelistRule("Sensitive Information", List.of("JSON Web Token"))));
        AiConfig changedConfig = configWithModel(firstConfig, "model-b", true);

        AiTriageEnqueueService.EnqueueResult first = service.enqueueAfterRegexPersistence(
                "message-once", "content-hash-once", requestResponse, matches, firstConfig);
        AiTriageEnqueueService.EnqueueResult second = service.enqueueAfterRegexPersistence(
                "message-once", "content-hash-once", requestResponse, matches, changedConfig);

        assertAll(
                () -> assertTrue(first.isEnqueued()),
                () -> assertEquals("duplicate", second.getStatus()),
                () -> assertEquals(1, tableCount(context.databasePath(), AI_TASK_TABLE)),
                () -> assertTrue(store.hasAiTriageForMessage("message-once"))
        );
    }

    @Test
    void analyzeOncePerMessageAllowsReanalysisAfterEmptyAdvisoryResult() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("once-empty-result-retry"));
        SqliteMessageStore store = context.store();
        HttpRequestResponse requestResponse = requestResponse("example.test", "GET", "/api/token");
        Map<String, List<String>> matches = Map.of("JSON Web Token", List.of("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0"));
        store.saveMessage("message-empty-result", requestResponse, "https://example.test/api/token", "GET", "200", "42",
                "JSON Web Token", "green", "content-hash-empty-result", matches);
        insertLegacyEmptyAiResult(context.databasePath(), "message-empty-result", "content-hash-empty-result");
        AiTriageEnqueueService service = new AiTriageEnqueueService(store);
        AiConfig config = enabledConfig(10, List.of(new AiWhitelistRule("Sensitive Information", List.of("JSON Web Token"))));

        AiTriageEnqueueService.EnqueueResult result = service.enqueueAfterRegexPersistence(
                "message-empty-result", "content-hash-empty-result", requestResponse, matches, config);

        assertAll(
                () -> assertTrue(result.isEnqueued()),
                () -> assertEquals(1, tableCount(context.databasePath(), AI_TASK_TABLE)),
                () -> assertTrue(store.hasAiTriageForMessage("message-empty-result")),
                () -> assertTrue(store.hasBlockingAiTriageForMessage("message-empty-result"))
        );
    }

    @Test
    void analyzeOncePerMessageAllowsReanalysisAfterLowQualityNonEmptyAdvisoryResult() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("once-low-quality-result-retry"));
        SqliteMessageStore store = context.store();
        HttpRequestResponse requestResponse = requestResponse("example.test", "GET", "/api/password.js");
        Map<String, List<String>> matches = Map.of("密码", List.of("accSetPwd:\"/static/setpwd.js\""));
        store.saveMessage("message-low-quality-result", requestResponse, "https://example.test/api/password.js", "GET", "200", "42",
                "密码", "green", "content-hash-low-quality-result", matches);
        insertLegacyLowQualityAiResult(context.databasePath(), "message-low-quality-result", "content-hash-low-quality-result");
        AiTriageEnqueueService service = new AiTriageEnqueueService(store);
        AiConfig config = enabledConfig(10, List.of(new AiWhitelistRule("敏感信息", List.of("密码"))));

        AiTriageEnqueueService.EnqueueResult result = service.enqueueAfterRegexPersistence(
                "message-low-quality-result", "content-hash-low-quality-result", requestResponse, matches, config);

        assertAll(
                () -> assertTrue(result.isEnqueued()),
                () -> assertEquals(1, tableCount(context.databasePath(), AI_TASK_TABLE)),
                () -> assertTrue(store.hasBlockingAiTriageForMessage("message-low-quality-result"))
        );
    }

    @Test
    void analyzeOncePerMessageAllowsReanalysisAfterMissingOverallStrongItemResult() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("once-missing-overall-strong-item-retry"));
        SqliteMessageStore store = context.store();
        HttpRequestResponse requestResponse = requestResponse("example.test", "GET", "/api/user.js");
        Map<String, List<String>> matches = Map.of("用户名", List.of("realUserTag:\"/static/user.js\""));
        store.saveMessage("message-strong-item-result", requestResponse, "https://example.test/api/user.js", "GET", "200", "42",
                "用户名", "green", "content-hash-strong-item-result", matches);
        insertLegacyStrongItemMissingOverallAiResult(context.databasePath(), "message-strong-item-result", "content-hash-strong-item-result");
        AiTriageEnqueueService service = new AiTriageEnqueueService(store);
        AiConfig config = enabledConfig(10, List.of(new AiWhitelistRule("敏感信息", List.of("用户名"))));

        AiTriageEnqueueService.EnqueueResult result = service.enqueueAfterRegexPersistence(
                "message-strong-item-result", "content-hash-strong-item-result", requestResponse, matches, config);

        assertAll(
                () -> assertTrue(result.isEnqueued()),
                () -> assertEquals(1, tableCount(context.databasePath(), AI_TASK_TABLE)),
                () -> assertTrue(store.hasBlockingAiTriageForMessage("message-strong-item-result"))
        );
    }

    @Test
    void analyzeMoreThanOncePerMessageAllowsReanalysisWhenConfigChanges() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("more-than-once-config-change"));
        SqliteMessageStore store = context.store();
        HttpRequestResponse requestResponse = requestResponse("example.test", "GET", "/api/token");
        Map<String, List<String>> matches = Map.of("JSON Web Token", List.of("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0"));
        store.saveMessage("message-repeat", requestResponse, "https://example.test/api/token", "GET", "200", "42",
                "JSON Web Token", "green", "content-hash-repeat", matches);
        AiTriageEnqueueService service = new AiTriageEnqueueService(store);
        AiConfig firstConfig = configWithAnalyzeOnce(enabledConfig(10, List.of(new AiWhitelistRule("Sensitive Information", List.of("JSON Web Token")))), false);
        AiConfig changedConfig = configWithModel(firstConfig, "model-b", false);

        AiTriageEnqueueService.EnqueueResult first = service.enqueueAfterRegexPersistence(
                "message-repeat", "content-hash-repeat", requestResponse, matches, firstConfig);
        AiTriageEnqueueService.EnqueueResult second = service.enqueueAfterRegexPersistence(
                "message-repeat", "content-hash-repeat", requestResponse, matches, changedConfig);

        assertAll(
                () -> assertTrue(first.isEnqueued()),
                () -> assertTrue(second.isEnqueued()),
                () -> assertEquals(2, tableCount(context.databasePath(), AI_TASK_TABLE)),
                () -> assertTrue(store.hasAiTriageForMessage("message-repeat"))
        );
    }

    @Test
    void queueFullDoesNotFailRegexCompletion() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("queue-full"));
        SqliteMessageStore store = context.store();
        HttpRequestResponse firstRequestResponse = requestResponse("example.test", "GET", "/api/first");
        HttpRequestResponse secondRequestResponse = requestResponse("example.test", "GET", "/api/second");
        Map<String, List<String>> matches = Map.of("JSON Web Token", List.of("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0"));
        AiConfig config = enabledConfig(1, List.of(new AiWhitelistRule("Sensitive Information", List.of("JSON Web Token"))));
        AiTriageEnqueueService service = new AiTriageEnqueueService(store);

        store.saveMessage("message-existing", firstRequestResponse, "https://example.test/api/first", "GET", "200", "42",
                "JSON Web Token", "green", "content-hash-existing", matches);
        assertTrue(service.enqueueAfterRegexPersistence("message-existing", "content-hash-existing", firstRequestResponse, matches, config).isEnqueued());

        assertTrue(store.savePendingMessage(
                "message-queue-full",
                secondRequestResponse,
                "https://example.test/api/second",
                "example.test",
                "GET",
                "200",
                "42",
                "content-hash-queue-full",
                "",
                "",
                false
        ).isSaved());
        boolean regexCompleted = store.completeRegexProcessing("message-queue-full", "JSON Web Token", "green", matches);
        AiTriageEnqueueService.EnqueueResult skipped = service.enqueueAfterRegexPersistence(
                "message-queue-full",
                "content-hash-queue-full",
                secondRequestResponse,
                matches,
                config
        );

        try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(context.databasePath()))) {
            assertAll(
                    () -> assertTrue(regexCompleted),
                    () -> assertEquals("DONE", regexStatus(connection, "message-queue-full")),
                    () -> assertEquals("skipped_queue_full", skipped.getStatus()),
                    () -> assertEquals(1, TestFixtures.countRows(connection, AI_TASK_TABLE))
            );
        }
    }

    @Test
    void requestOnlyMessagesAreUnsupportedAndDoNotEnqueue() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("request-only"));
        SqliteMessageStore store = context.store();
        HttpRequestResponse requestOnly = requestOnlyRequestResponse("example.test", "GET", "/api/request-only");
        Map<String, List<String>> matches = Map.of("JSON Web Token", List.of("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0"));

        AiTriageEnqueueService.EnqueueResult result = new AiTriageEnqueueService(store).enqueueAfterRegexPersistence(
                "message-request-only",
                "content-hash-request-only",
                requestOnly,
                matches,
                enabledConfig(10, List.of(new AiWhitelistRule("Sensitive Information", List.of("JSON Web Token"))))
        );

        assertAll(
                () -> assertEquals("skipped_unsupported_message_type", result.getStatus()),
                () -> assertEquals(0, tableCount(context.databasePath(), AI_TASK_TABLE))
        );
    }

    private StoreContext createStoreContext(Path home) throws Exception {
        Files.createDirectories(home.resolve(".config").resolve("HaE"));
        String originalHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", home.toString());
            MontoyaApi api = proxyFor(MontoyaApi.class);
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

    private static AiConfig enabledConfig(int maxQueueSize, List<AiWhitelistRule> whitelist) {
        return new AiConfig(
                true,
                false,
                "openai-compatible",
                "https://ai.example.test/v1",
                "model-a",
                "test-api-key",
                180,
                2,
                8,
                2_000_000,
                800_000,
                200_000,
                600_000,
                50,
                true,
                true,
                true,
                true,
                true,
                maxQueueSize,
                false,
                whitelist
        );
    }

    private static AiConfig configWithModel(AiConfig base, String model, boolean analyzeOncePerMessage) {
        return new AiConfig(
                base.isEnabled(),
                base.isUseBurpProxy(),
                base.getProviderType(),
                base.getBaseUrl(),
                model,
                base.getApiKey(),
                base.getRequestTimeoutSeconds(),
                base.getConcurrency(),
                base.getMaxConcurrency(),
                base.getMaxInFlightChars(),
                base.getMaxTotalChars(),
                base.getMaxRequestChars(),
                base.getMaxResponseChars(),
                base.getMaxItemsPerMessage(),
                analyzeOncePerMessage,
                base.isSendFullRequest(),
                base.isSendFullResponse(),
                base.isSkipBinary(),
                base.isSkipStaticResources(),
                base.getMaxQueueSize(),
                base.isSaveFullPrompt(),
                base.getWhitelist()
        );
    }

    private static AiConfig configWithAnalyzeOnce(AiConfig base, boolean analyzeOncePerMessage) {
        return configWithModel(base, base.getModel(), analyzeOncePerMessage);
    }

    private static AiConfig disabledConfig() {
        AiConfig enabled = enabledConfig(10, List.of(new AiWhitelistRule("Sensitive Information", List.of("JSON Web Token"))));
        return new AiConfig(
                false,
                enabled.isUseBurpProxy(),
                enabled.getProviderType(),
                enabled.getBaseUrl(),
                enabled.getModel(),
                enabled.getApiKey(),
                enabled.getRequestTimeoutSeconds(),
                enabled.getConcurrency(),
                enabled.getMaxConcurrency(),
                enabled.getMaxInFlightChars(),
                enabled.getMaxTotalChars(),
                enabled.getMaxRequestChars(),
                enabled.getMaxResponseChars(),
                enabled.getMaxItemsPerMessage(),
                enabled.isAnalyzeOncePerMessage(),
                enabled.isSendFullRequest(),
                enabled.isSendFullResponse(),
                enabled.isSkipBinary(),
                enabled.isSkipStaticResources(),
                enabled.getMaxQueueSize(),
                enabled.isSaveFullPrompt(),
                enabled.getWhitelist()
        );
    }

    private static HttpRequestResponse requestResponse(String host, String methodName, String path) {
        HttpService service = httpServiceProxy(host);
        HttpRequest request = httpRequestProxy(service, host, methodName, path);
        HttpResponse response = httpResponseProxy("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "request" -> request;
            case "response" -> response;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpRequestResponse.class, handler);
    }

    private static HttpRequestResponse requestOnlyRequestResponse(String host, String methodName, String path) {
        HttpService service = httpServiceProxy(host);
        HttpRequest request = httpRequestProxy(service, host, methodName, path);
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "request" -> request;
            case "response" -> null;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpRequestResponse.class, handler);
    }

    private static HttpService httpServiceProxy(String host) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "host" -> host;
            case "port" -> 443;
            case "secure" -> true;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpService.class, handler);
    }

    private static HttpRequest httpRequestProxy(HttpService service, String host, String methodName, String path) {
        byte[] requestBytes = (methodName + " " + path + " HTTP/1.1\r\nHost: " + host + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        ByteArray byteArray = byteArrayProxy(requestBytes);
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "httpService" -> service;
            case "method" -> methodName;
            case "path" -> path;
            case "url" -> "https://" + host.toLowerCase() + path;
            case "toByteArray" -> byteArray;
            case "body" -> byteArrayProxy(new byte[0]);
            case "headers" -> List.of();
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpRequest.class, handler);
    }

    private static HttpResponse httpResponseProxy(byte[] responseBytes) {
        ByteArray byteArray = byteArrayProxy(responseBytes);
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "toByteArray" -> byteArray;
            case "body" -> byteArrayProxy(new byte[0]);
            case "headers" -> List.of();
            case "statusCode" -> (short) 200;
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

    private static long tableCount(Path databasePath, String tableName) throws SQLException {
        try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(databasePath))) {
            return TestFixtures.countRows(connection, tableName);
        }
    }

    private static int distinctCount(Connection connection, String columnName, String messageId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(DISTINCT " + columnName + ") FROM " + AI_TASK_TABLE + " WHERE message_id = ?")) {
            statement.setString(1, messageId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        }
    }

    private static void insertLegacyEmptyAiResult(Path databasePath, String messageId, String contentHash) throws SQLException {
        try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(databasePath));
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO ai_triage_result (
                         message_id, content_hash, analysis_key, status, overall_verdict,
                         overall_severity, confidence, summary, result_json, analyzed_at,
                         schema_version, prompt_version, model, config_hash
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, messageId);
            statement.setString(2, contentHash);
            statement.setString(3, "analysis-empty");
            statement.setString(4, "DONE");
            statement.setString(5, "unknown");
            statement.setString(6, "unknown");
            statement.setDouble(7, 0.0);
            statement.setString(8, "");
            statement.setString(9, "{\"overall_verdict\":\"unknown\",\"overall_severity\":\"unknown\",\"confidence\":0.0,\"summary\":\"\",\"items\":[]}");
            statement.setLong(10, 30_000L);
            statement.setString(11, AiTriageSchema.SCHEMA_VERSION);
            statement.setString(12, AiTriageSchema.PROMPT_VERSION);
            statement.setString(13, "model-a");
            statement.setString(14, "config-empty");
            statement.executeUpdate();
        }
    }

    private static void insertLegacyLowQualityAiResult(Path databasePath, String messageId, String contentHash) throws SQLException {
        try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(databasePath));
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO ai_triage_result (
                         message_id, content_hash, analysis_key, status, overall_verdict,
                         overall_severity, confidence, summary, result_json, analyzed_at,
                         schema_version, prompt_version, model, config_hash
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, messageId);
            statement.setString(2, contentHash);
            statement.setString(3, "analysis-low-quality");
            statement.setString(4, "DONE");
            statement.setString(5, "false_positive");
            statement.setString(6, "unknown");
            statement.setDouble(7, 0.0);
            statement.setString(8, "");
            statement.setString(9, lowQualityFalsePositiveJson());
            statement.setLong(10, 31_000L);
            statement.setString(11, AiTriageSchema.SCHEMA_VERSION);
            statement.setString(12, AiTriageSchema.PROMPT_VERSION);
            statement.setString(13, "model-a");
            statement.setString(14, "config-low-quality");
            statement.executeUpdate();
        }
    }

    private static void insertLegacyStrongItemMissingOverallAiResult(Path databasePath, String messageId, String contentHash) throws SQLException {
        try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(databasePath));
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO ai_triage_result (
                         message_id, content_hash, analysis_key, status, overall_verdict,
                         overall_severity, confidence, summary, result_json, analyzed_at,
                         schema_version, prompt_version, model, config_hash
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, messageId);
            statement.setString(2, contentHash);
            statement.setString(3, "analysis-missing-overall");
            statement.setString(4, "DONE");
            statement.setString(5, "unknown");
            statement.setString(6, "unknown");
            statement.setDouble(7, 0.0);
            statement.setString(8, "");
            statement.setString(9, missingOverallStrongItemJson());
            statement.setLong(10, 32_000L);
            statement.setString(11, AiTriageSchema.SCHEMA_VERSION);
            statement.setString(12, AiTriageSchema.PROMPT_VERSION);
            statement.setString(13, "model-a");
            statement.setString(14, "config-missing-overall");
            statement.executeUpdate();
        }
    }

    private static String lowQualityFalsePositiveJson() {
        return "{\"overall_verdict\":\"false_positive\",\"overall_severity\":\"unknown\",\"confidence\":0.0,\"summary\":\"\"," +
                "\"items\":[{\"rule_group\":\"敏感信息\",\"rule_name\":\"密码\",\"rule_hash\":\"hash-1\"," +
                "\"matched_value_redacted\":\"accSetPwd:\\\"/[redacted]\\\"\",\"match_location\":\"match context pending\"," +
                "\"verdict\":\"false_positive\",\"is_sensitive\":false,\"is_exposed\":false," +
                "\"confidence\":0.0,\"severity\":\"unknown\",\"reason\":\"static asset\",\"recommended_actions\":[]}] }";
    }

    private static String missingOverallStrongItemJson() {
        return "{\"overall_verdict\":\"unknown\",\"overall_severity\":\"unknown\",\"confidence\":0.0,\"summary\":\"," +
                "\"items_truncated\":false,\"omitted_item_count\":0," +
                "\"items\":[{\"rule_group\":\"敏感信息\",\"rule_name\":\"用户名\",\"rule_hash\":\"hash-1\"," +
                "\"matched_value_redacted\":\"\",\"match_location\":\"\",\"verdict\":\"false_positive\"," +
                "\"is_sensitive\":false,\"is_exposed\":false,\"confidence\":0.98,\"severity\":\"info\"," +
                "\"reason\":\"Static JavaScript route name, no account identifier present\",\"recommended_actions\":[]}] }";
    }

    private static String singleString(Connection connection, String columnName, String idColumnName, String idValue) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + columnName + " FROM " + AI_TASK_TABLE + " WHERE " + idColumnName + " = ?")) {
            statement.setString(1, idValue);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private static String regexStatus(Connection connection, String messageId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT regex_status FROM " + MESSAGE_TABLE + " WHERE message_id = ?")) {
            statement.setString(1, messageId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private static Path haeDatabasePath(Path home) {
        return home.resolve(".config").resolve("HaE").resolve("History.db");
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
        if (returnType == Short.TYPE) {
            return (short) 0;
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
