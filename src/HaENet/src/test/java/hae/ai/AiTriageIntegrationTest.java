package hae.ai;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import burp.api.montoya.logging.Logging;
import burp.api.montoya.proxy.websocket.InterceptedTextMessage;
import burp.api.montoya.proxy.websocket.TextMessageReceivedAction;
import burp.api.montoya.ui.UserInterface;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import hae.TestFixtures;
import hae.ai.client.AiClient;
import hae.ai.client.AiClientException;
import hae.ai.client.AiClientFailureCategory;
import hae.ai.client.AiClientResult;
import hae.ai.worker.AiTriageWorkerConfig;
import hae.ai.worker.AiTriageWorkerStatus;
import hae.component.board.message.MessageTableModel;
import hae.instances.websocket.WebSocketMessageHandler;
import hae.storage.SqliteMessageStore;
import hae.utils.ConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JPanel;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class AiTriageIntegrationTest {
    private static final String AI_TASK_TABLE = "ai_triage_task";

    @TempDir
    Path tempDirectory;

    @Test
    void aiDisabledDoesNotStartWorkerOrEnqueue() throws Exception {
        IntegrationContext context = createIntegrationContext(tempDirectory.resolve("disabled"));
        configureAi(context.configLoader(), false);

        AiTriageLifecycle lifecycle = AiTriageLifecycle.start(
                context.configLoader().getAiConfig(),
                context.store(),
                FakeAiClient.success(successJson()),
                AiTriageWorkerConfig.builder().autoStart(true).build()
        );
        MessageTableModel model = newTestModel(context);
        try {
            context.store().saveMessage(
                    "message-disabled",
                    requestResponse("example.test", "/api/token", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0"),
                    "https://example.test/api/token",
                    "GET",
                    "200",
                    "42",
                    "JSON Web Token",
                    "green",
                    "hash-disabled",
                    Map.of("JSON Web Token", List.of("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0"))
            );
            model.add(requestResponse("example.test", "/api/token", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0"), true, null, "Proxy");

            assertAll(
                    () -> assertFalse(lifecycle.isStarted()),
                    () -> assertNull(lifecycle.getWorker()),
                    () -> assertEquals("AI disabled", lifecycle.getDisabledReason()),
                    () -> assertEquals(0, tableCount(context.databasePath(), AI_TASK_TABLE))
            );
        } finally {
            lifecycle.shutdown();
            model.clearAllDataOnShutdown();
        }
    }

    @Test
    void enabledCompositionStartsWorkerAndProcessesQueuedTask() throws Exception {
        IntegrationContext context = createIntegrationContext(tempDirectory.resolve("enabled"));
        configureAi(context.configLoader(), true);
        CountDownLatch aiCalled = new CountDownLatch(1);
        FakeAiClient aiClient = new FakeAiClient(prompt -> {
            aiCalled.countDown();
            return new AiClientResult(200, successJson());
        });
        AiTriageLifecycle lifecycle = AiTriageLifecycle.start(
                context.configLoader().getAiConfig(),
                context.store(),
                aiClient,
                AiTriageWorkerConfig.builder().concurrency(1).idlePollMillis(25).autoStart(true).build()
        );

        assertTrue(lifecycle.isStarted());
        assertTrue(lifecycle.getWorker().getStatus() == AiTriageWorkerStatus.RUNNING);

        AiTriageEnqueueService.EnqueueResult enqueueResult = new AiTriageEnqueueService(context.store()).enqueueAfterRegexPersistence(
                "message-enabled",
                "hash-enabled",
                saveMatchedMessage(context.store(), "message-enabled", "hash-enabled"),
                Map.of("JSON Web Token", List.of("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0")),
                context.configLoader().getAiConfig()
        );

        try {
            assertTrue(enqueueResult.isEnqueued());
            assertTrue(aiCalled.await(3, TimeUnit.SECONDS), "auto-started worker should process the queued AI task");
            waitUntilTaskStatus(context.databasePath(), "message-enabled", "DONE");
            assertAll(
                    () -> assertEquals(1, aiClient.callCount()),
                    () -> assertEquals("DONE", taskColumn(context.databasePath(), "message-enabled", "status")),
                    () -> assertEquals("DONE", resultColumn(context.databasePath(), "message-enabled", "status")),
                    () -> assertEquals("Token looks exposed", resultColumn(context.databasePath(), "message-enabled", "summary"))
            );
        } finally {
            lifecycle.shutdown();
        }
    }

    @Test
    void lifecycleReconcileStopsWorkerWhenSettingsDisableAi() throws Exception {
        IntegrationContext context = createIntegrationContext(tempDirectory.resolve("reconcile-disable"));
        configureAi(context.configLoader(), true);

        AiTriageLifecycle lifecycle = AiTriageLifecycle.startIfEnabled(context.api(), context.configLoader(), context.store());
        try {
            assertTrue(lifecycle.isStarted());
            context.configLoader().setAIEnabled(false);

            lifecycle.reconcileWithCurrentConfig();

            assertAll(
                    () -> assertFalse(lifecycle.isStarted()),
                    () -> assertNull(lifecycle.getWorker()),
                    () -> assertEquals("AI disabled", lifecycle.getDisabledReason()),
                    () -> assertTrue(lifecycle.statusSummary().contains("DISABLED"))
            );
        } finally {
            lifecycle.shutdown();
        }
    }

    @Test
    void unloadShutsDownWorkerAndRecoversLeases() throws Exception {
        IntegrationContext context = createIntegrationContext(tempDirectory.resolve("unload"));
        configureAi(context.configLoader(), true);
        CountDownLatch aiCallEntered = new CountDownLatch(1);
        CountDownLatch releaseAiCall = new CountDownLatch(1);
        FakeAiClient aiClient = new FakeAiClient(prompt -> {
            aiCallEntered.countDown();
            awaitInFakeAi(releaseAiCall);
            return new AiClientResult(200, successJson());
        });
        AiTriageLifecycle lifecycle = AiTriageLifecycle.start(
                context.configLoader().getAiConfig(),
                context.store(),
                aiClient,
                AiTriageWorkerConfig.builder().concurrency(1).idlePollMillis(25).leaseDurationMillis(60_000L).autoStart(true).build()
        );
        new AiTriageEnqueueService(context.store()).enqueueAfterRegexPersistence(
                "message-unload",
                "hash-unload",
                saveMatchedMessage(context.store(), "message-unload", "hash-unload"),
                Map.of("JSON Web Token", List.of("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0")),
                context.configLoader().getAiConfig()
        );

        assertTrue(aiCallEntered.await(3, TimeUnit.SECONDS));
        waitUntilTaskStatus(context.databasePath(), "message-unload", "LEASED");
        lifecycle.shutdown();
        releaseAiCall.countDown();

        assertAll(
                () -> assertEquals(AiTriageWorkerStatus.SHUTDOWN, lifecycle.getWorker().getStatus()),
                () -> assertTrue(lifecycle.getWorker().isShutdown()),
                () -> assertEquals("PENDING", taskColumn(context.databasePath(), "message-unload", "status")),
                () -> assertEquals("0", taskColumn(context.databasePath(), "message-unload", "leased_until")),
                () -> assertEquals("0", taskColumn(context.databasePath(), "message-unload", "attempt_count"))
        );
    }

    @Test
    void regexCompletionSurvivesQueueFullAiEnqueueFailure() throws Exception {
        IntegrationContext context = createIntegrationContext(tempDirectory.resolve("model-queue-full"));
        configureAi(context.configLoader(), true);
        context.configLoader().setAIMaxQueueSize(1);
        AiTriageEnqueueService.EnqueueResult existingTask = new AiTriageEnqueueService(context.store()).enqueueAfterRegexPersistence(
                "message-existing-queue-full",
                "hash-existing-queue-full",
                saveMatchedMessage(context.store(), "message-existing-queue-full", "hash-existing-queue-full"),
                Map.of("JSON Web Token", List.of("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0")),
                context.configLoader().getAiConfig()
        );
        MessageTableModel model = newTestModel(context);
        String queuedUrl = "https://example.test/api/model-queue-full";

        try {
            assertTrue(existingTask.isEnqueued());
            model.add(requestResponse("example.test", "/api/model-queue-full", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0"), true, null, "Proxy");
            waitUntilRegexStatusByUrl(context.databasePath(), queuedUrl, "DONE");

            assertAll(
                    () -> assertEquals("DONE", messageColumnByUrl(context.databasePath(), queuedUrl, "regex_status")),
                    () -> assertEquals("", messageColumnByUrl(context.databasePath(), queuedUrl, "regex_error")),
                    () -> assertEquals(1, tableCount(context.databasePath(), AI_TASK_TABLE))
            );
        } finally {
            model.clearAllDataOnShutdown();
        }
    }

    @Test
    void websocketMessagesRemainUnsupportedAndDoNotEnqueue() throws Exception {
        IntegrationContext context = createIntegrationContext(tempDirectory.resolve("websocket"));
        configureAi(context.configLoader(), true);
        WebSocketMessageHandler handler = new WebSocketMessageHandler(context.api(), context.configLoader());
        MontoyaObjectFactory originalFactory = ObjectFactoryLocator.FACTORY;

        try {
            ObjectFactoryLocator.FACTORY = montoyaObjectFactoryProxy();
            assertEquals(0, tableCount(context.databasePath(), AI_TASK_TABLE));
            TextMessageReceivedAction action = handler.handleTextMessageReceived(webSocketTextMessage("token=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0"));

            assertAll(
                    () -> assertEquals("token=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0", action.payload()),
                    () -> assertEquals(0, tableCount(context.databasePath(), AI_TASK_TABLE))
            );
        } finally {
            ObjectFactoryLocator.FACTORY = originalFactory;
        }
    }

    private static MessageTableModel newTestModel(IntegrationContext context) {
        SqliteMessageStore store = context.store();
        return new MessageTableModel(
                context.api(),
                context.configLoader(),
                store,
                store,
                store,
                store
        );
    }

    private static HttpRequestResponse saveMatchedMessage(SqliteMessageStore store, String messageId, String contentHash) {
        HttpRequestResponse requestResponse = requestResponse("example.test", "/api/token", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0");
        store.saveMessage(
                messageId,
                requestResponse,
                "https://example.test/api/token",
                "GET",
                "200",
                "42",
                "JSON Web Token",
                "green",
                contentHash,
                Map.of("JSON Web Token", List.of("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0"))
        );
        return requestResponse;
    }

    private IntegrationContext createIntegrationContext(Path home) throws Exception {
        Files.createDirectories(home.resolve(".config").resolve("HaE"));
        String originalHome = System.getProperty("user.home");
        MontoyaApi api = montoyaApiProxy(home);
        try {
            System.setProperty("user.home", home.toString());
            ConfigLoader configLoader = new ConfigLoader(api);
            configLoader.setBlockHost("");
            configLoader.setExcludeSuffix("");
            configLoader.setExcludeStatus("");
            configLoader.setScope("Proxy");
            SqliteMessageStore store = new SqliteMessageStore(api, configLoader);
            return new IntegrationContext(api, configLoader, store, haeDatabasePath(home));
        } finally {
            if (originalHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", originalHome);
            }
        }
    }

    private static void configureAi(ConfigLoader configLoader, boolean enabled) {
        configLoader.setAIEnabled(enabled);
        configLoader.setAIProviderType("openai-compatible");
        configLoader.setAIBaseUrl("https://ai.example.test/v1");
        configLoader.setAIModel("model-a");
        configLoader.setAIApiKey("test-api-key");
        configLoader.setAIConcurrency(1);
        configLoader.setAIMaxConcurrency(1);
        configLoader.setAIMaxQueueSize(10);
        configLoader.setAIWhitelist(List.of(new AiWhitelistRule("Sensitive Information", List.of("JSON Web Token", "JWT"))));
    }

    private static HttpRequestResponse requestResponse(String host, String path, String secretValue) {
        HttpService service = httpServiceProxy(host);
        HttpRequest request = httpRequestProxy(service, host, path);
        HttpResponse response = httpResponseProxy(secretValue);
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "request" -> request;
            case "response" -> response;
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

    private static HttpRequest httpRequestProxy(HttpService service, String host, String path) {
        byte[] requestBytes = ("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nAccept: application/json\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        ByteArray byteArray = byteArrayProxy(requestBytes);
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "httpService" -> service;
            case "method" -> "GET";
            case "path" -> path;
            case "url" -> "https://" + host + path;
            case "fileExtension" -> "json";
            case "headers" -> Collections.emptyList();
            case "toByteArray" -> byteArray;
            case "body" -> byteArrayProxy(new byte[0]);
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpRequest.class, handler);
    }

    private static HttpResponse httpResponseProxy(String secretValue) {
        byte[] responseBytes = ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"token\":\"" + secretValue + "\"}")
                .getBytes(StandardCharsets.ISO_8859_1);
        ByteArray byteArray = byteArrayProxy(responseBytes);
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "statusCode" -> (short) 200;
            case "headers" -> Collections.emptyList();
            case "toByteArray" -> byteArray;
            case "body" -> byteArrayProxy(("{\"token\":\"" + secretValue + "\"}").getBytes(StandardCharsets.ISO_8859_1));
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpResponse.class, handler);
    }

    private static ByteArray byteArrayProxy(byte[] bytes) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getBytes" -> bytes;
            case "length" -> bytes.length;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(ByteArray.class, handler);
    }

    private static InterceptedTextMessage webSocketTextMessage(String payload) {
        burp.api.montoya.core.Annotations annotations = proxyFor(burp.api.montoya.core.Annotations.class);
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "payload" -> payload;
            case "annotations" -> annotations;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(InterceptedTextMessage.class, handler);
    }

    private static MontoyaObjectFactory montoyaObjectFactoryProxy() {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "highlightColor" -> burp.api.montoya.core.HighlightColor.GREEN;
            case "followUserRulesInitialProxyTextMessage" -> textMessageReceivedActionProxy((String) args[0]);
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(MontoyaObjectFactory.class, handler);
    }

    private static TextMessageReceivedAction textMessageReceivedActionProxy(String payload) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "payload" -> payload;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(TextMessageReceivedAction.class, handler);
    }

    private static MontoyaApi montoyaApiProxy(Path home) {
        UserInterface userInterface = userInterfaceProxy();
        Logging logging = proxyFor(Logging.class, (proxy, method, args) -> null);
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "userInterface" -> userInterface;
            case "logging" -> logging;
            case "extension" -> extensionProxy(home);
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(MontoyaApi.class, handler);
    }

    private static Object extensionProxy(Path home) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "filename" -> home.resolve("HaE.jar").toString();
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(burp.api.montoya.extension.Extension.class, handler);
    }

    private static UserInterface userInterfaceProxy() {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "createHttpRequestEditor" -> httpRequestEditorProxy();
            case "createHttpResponseEditor" -> httpResponseEditorProxy();
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(UserInterface.class, handler);
    }

    private static HttpRequestEditor httpRequestEditorProxy() {
        JPanel panel = new JPanel();
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "uiComponent" -> panel;
            case "setRequest" -> null;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpRequestEditor.class, handler);
    }

    private static HttpResponseEditor httpResponseEditorProxy() {
        JPanel panel = new JPanel();
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "uiComponent" -> panel;
            case "setResponse" -> null;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpResponseEditor.class, handler);
    }

    private static String successJson() {
        return "{\"overall_verdict\":\"sensitive_exposure\"," +
                "\"overall_severity\":\"high\"," +
                "\"confidence\":0.75," +
                "\"summary\":\"Token looks exposed\"," +
                "\"items_truncated\":false," +
                "\"omitted_item_count\":0," +
                "\"items\":[]}";
    }

    private static long tableCount(Path databasePath, String tableName) throws Exception {
        try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(databasePath))) {
            return TestFixtures.countRows(connection, tableName);
        }
    }

    private static String taskColumn(Path databasePath, String messageId, String columnName) throws Exception {
        return singleString(databasePath, AI_TASK_TABLE, columnName, "message_id", messageId);
    }

    private static String resultColumn(Path databasePath, String messageId, String columnName) throws Exception {
        return singleString(databasePath, "ai_triage_result", columnName, "message_id", messageId);
    }

    private static String singleString(Path databasePath,
                                       String tableName,
                                       String columnName,
                                       String idColumnName,
                                       String idValue) throws Exception {
        try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(databasePath));
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT " + columnName + " FROM " + tableName + " WHERE " + idColumnName + " = ?")) {
            statement.setString(1, idValue);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private static void waitUntilTaskStatus(Path databasePath, String messageId, String status) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (status.equals(taskColumn(databasePath, messageId, "status"))) {
                return;
            }
            Thread.sleep(25L);
        }
        assertEquals(status, taskColumn(databasePath, messageId, "status"));
    }

    private static void waitUntilRegexStatusByUrl(Path databasePath, String url, String status) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (status.equals(messageColumnByUrl(databasePath, url, "regex_status"))) {
                return;
            }
            Thread.sleep(25L);
        }
        assertEquals(status,
                messageColumnByUrl(databasePath, url, "regex_status"),
                "regex_error=" + messageColumnByUrl(databasePath, url, "regex_error"));
    }

    private static String messageColumnByUrl(Path databasePath, String url, String columnName) throws Exception {
        try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(databasePath));
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT " + columnName + " FROM message_history WHERE url = ?")) {
            statement.setString(1, url);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private static void awaitInFakeAi(CountDownLatch latch) throws AiClientException {
        try {
            latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiClientException("Interrupted fake AI wait", AiClientFailureCategory.RETRYABLE);
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
            return switch (method.getName()) {
                case "toString" -> "test proxy for " + proxy.getClass().getInterfaces()[0].getName();
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> args != null && args.length > 0 && proxy == args[0];
                default -> null;
            };
        }
        if ("utilities".equals(method.getName()) && method.getReturnType().isInterface()) {
            return utilitiesProxy(method.getReturnType());
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
        if (returnType == List.class) {
            return Collections.emptyList();
        }
        if (returnType.isInterface()) {
            return proxyFor(returnType);
        }
        return null;
    }

    private static Object utilitiesProxy(Class<?> utilitiesType) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("byteUtils".equals(method.getName()) && method.getReturnType().isInterface()) {
                return byteUtilsProxy(method.getReturnType());
            }
            return defaultProxyValue(proxy, method, args);
        };
        return proxyFor(utilitiesType, handler);
    }

    private static Object byteUtilsProxy(Class<?> byteUtilsType) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "convertFromString" -> args[0].toString().getBytes(StandardCharsets.ISO_8859_1);
            case "indexOf" -> indexOf((byte[]) args[0], (byte[]) args[1], (Boolean) args[2], (Integer) args[3], (Integer) args[4]);
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(byteUtilsType, handler);
    }

    private static int indexOf(byte[] haystack, byte[] needle, boolean caseSensitive, int fromInclusive, int toExclusive) {
        if (haystack == null || needle == null || needle.length == 0) {
            return -1;
        }
        int start = Math.max(0, fromInclusive);
        int end = Math.min(haystack.length, Math.max(start, toExclusive));
        for (int i = start; i <= end - needle.length; i++) {
            boolean matched = true;
            for (int j = 0; j < needle.length; j++) {
                byte left = haystack[i + j];
                byte right = needle[j];
                if (!caseSensitive) {
                    left = (byte) Character.toLowerCase((char) (left & 0xff));
                    right = (byte) Character.toLowerCase((char) (right & 0xff));
                }
                if (left != right) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return i;
            }
        }
        return -1;
    }

    private record IntegrationContext(MontoyaApi api,
                                      ConfigLoader configLoader,
                                      SqliteMessageStore store,
                                      Path databasePath) {
    }

    private static class FakeAiClient implements AiClient {
        private final AtomicReference<AiClientHandler> handler = new AtomicReference<>();
        private final AtomicInteger calls = new AtomicInteger(0);

        private FakeAiClient(AiClientHandler handler) {
            this.handler.set(handler);
        }

        private static FakeAiClient success(String responseBody) {
            return new FakeAiClient(prompt -> new AiClientResult(200, responseBody));
        }

        @Override
        public AiClientResult complete(String prompt) throws AiClientException {
            calls.incrementAndGet();
            return handler.get().complete(prompt);
        }

        private int callCount() {
            return calls.get();
        }
    }

    @FunctionalInterface
    private interface AiClientHandler {
        AiClientResult complete(String prompt) throws AiClientException;
    }

    private static class DefaultProxyHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return defaultProxyValue(proxy, method, args);
        }
    }
}
