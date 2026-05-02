package hae.component.board;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.extension.Extension;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.logging.Logging;
import hae.ai.AiQueueCounts;
import hae.ai.AiTriageSchema;
import hae.ai.AiWhitelistRule;
import hae.component.board.message.MessageTableModel;
import hae.storage.SqliteMessageStore;
import hae.utils.ConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import javax.swing.JList;
import javax.swing.JTextArea;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

class DataboardAiSettingsTest {
    private static final String AI_TASK_TABLE = "ai_triage_task";

    @TempDir
    Path tempDirectory;

    @Test
    void enableRequiresWarningAcknowledgement() throws Exception {
        BoardContext context = createBoardContext(tempDirectory.resolve("enable-warning"));
        AtomicInteger reconcileCalls = new AtomicInteger(0);
        DataboardAiSettingsController controller = new DataboardAiSettingsController(
                context.configLoader(),
                context.store(),
                new DataboardAiSettingsController.WorkerControls() {
                    @Override
                    public void pause() {
                    }

                    @Override
                    public void resume() {
                    }

                    @Override
                    public void reconcileAfterSettingsSave() {
                        reconcileCalls.incrementAndGet();
                    }

                    @Override
                    public String statusSummary() {
                        return "test-reconciled";
                    }
                },
                Runnable::run
        );
        try {
            DataboardAiSettingsModel model = controller.loadModel();
            model.setEnabled(true);
            model.setBaseUrl("https://ai.example.test/v1");
            model.setModel("model-a");
            model.setApiKey("sk-sensitive-value-123456");

            DataboardAiSettingsModel.SaveResult blocked = controller.saveSettings(model, false);
            boolean enabledAfterBlockedSave = context.configLoader().getAIEnabled();
            DataboardAiSettingsModel.SaveResult saved = controller.saveSettings(model, true);

            assertAll(
                    () -> assertFalse(blocked.isSaved()),
                    () -> assertTrue(blocked.getMessage().contains("Cookie")),
                    () -> assertTrue(blocked.getMessage().contains("Authorization")),
                    () -> assertTrue(blocked.getMessage().contains("个人信息")),
                    () -> assertFalse(enabledAfterBlockedSave),
                    () -> assertTrue(saved.isSaved()),
                    () -> assertTrue(saved.getMessage().contains("test-reconciled")),
                    () -> assertTrue(context.configLoader().getAIEnabled()),
                    () -> assertEquals(1, reconcileCalls.get())
            );
        } finally {
            controller.shutdown();
        }
    }

    @Test
    void apiKeyMaskedInUiModel() throws Exception {
        BoardContext context = createBoardContext(tempDirectory.resolve("masked-key"));
        context.configLoader().setAIApiKey("sk-live-secret-value-987654321");
        saveMessage(context.store(), "message-1", "hash-1");
        enqueue(context.store(), "task-1", "message-1", "hash-1");

        DataboardAiSettingsModel model = DataboardAiSettingsModel.from(
                context.configLoader(),
                context.store().loadAiQueueCounts()
        );
        String masked = model.getMaskedApiKey();
        String queueStatusText = model.getQueueStatusText();

        assertAll(
                () -> assertEquals("sk****4321", masked),
                () -> assertFalse(masked.contains("live-secret-value")),
                    () -> assertTrue(queueStatusText.contains("待处理=1")),
                    () -> assertTrue(queueStatusText.contains("API key=sk****4321")),
                () -> assertFalse(queueStatusText.contains("sk-live-secret-value-987654321"))
        );
    }

    @Test
    void aiSettingsPanelRendersWarningsAsWrappedPlainText() throws Exception {
        BoardContext context = createBoardContext(tempDirectory.resolve("panel-warnings"));
        DataboardAiSettingsController controller = new DataboardAiSettingsController(
                context.configLoader(),
                context.store(),
                null,
                Runnable::run
        );
        AtomicReference<DataboardAiSettingsPanel> panelReference = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> panelReference.set(new DataboardAiSettingsPanel(controller)));
            DataboardAiSettingsPanel panel = panelReference.get();
            List<JTextArea> textAreas = textAreasIn(panel);

            assertAll(
                    () -> assertTrue(textAreas.stream().anyMatch(area -> area.getText().contains("完整 HTTP 请求/响应"))),
                    () -> assertTrue(textAreas.stream().anyMatch(area -> area.getText().contains("v1 暂不支持 AIUseBurpProxy"))),
                    () -> assertFalse(textAreas.stream().anyMatch(area -> area.getText().contains("<html>"))),
                    () -> assertTrue(textAreas.stream().filter(JTextArea::getLineWrap).count() >= 3)
            );
        } finally {
            controller.shutdown();
        }
    }

    @Test
    void whitelistRulePickerUsesExistingRuleNamesAndKeepsLegacySelections() throws Exception {
        BoardContext context = createBoardContext(tempDirectory.resolve("whitelist-picker"));
        context.configLoader().setAIWhitelist(List.of(
                new AiWhitelistRule("敏感信息", List.of("JWT", "Legacy Missing Rule"))
        ));
        DataboardAiSettingsController controller = new DataboardAiSettingsController(
                context.configLoader(),
                context.store(),
                null,
                Runnable::run
        );
        AtomicReference<DataboardAiSettingsPanel> panelReference = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> panelReference.set(new DataboardAiSettingsPanel(controller)));
            JList<?> ruleList = firstListIn(panelReference.get());

            assertAll(
                    () -> assertTrue(listValues(ruleList).contains("JWT")),
                    () -> assertTrue(listValues(ruleList).contains("JSON Web Token")),
                    () -> assertTrue(listValues(ruleList).contains("Legacy Missing Rule")),
                    () -> assertEquals(List.of("JWT", "Legacy Missing Rule"), selectedListValues(ruleList))
            );
        } finally {
            controller.shutdown();
        }
    }

    @Test
    void controllerLoadsRuleNamesFromCurrentRulesFile() throws Exception {
        BoardContext context = createBoardContext(tempDirectory.resolve("rule-name-source"));
        DataboardAiSettingsController controller = new DataboardAiSettingsController(
                context.configLoader(),
                context.store(),
                null,
                Runnable::run
        );
        try {
            List<String> ruleNames = controller.loadAvailableRuleNames();

            assertAll(
                    () -> assertTrue(ruleNames.contains("JSON Web Token")),
                    () -> assertTrue(ruleNames.contains("Chinese IDCard")),
                    () -> assertTrue(ruleNames.contains("Cloud Key"))
            );
        } finally {
            controller.shutdown();
        }
    }

    @Test
    void defaultWhitelistExcludesNoisyRules() throws Exception {
        BoardContext context = createBoardContext(tempDirectory.resolve("whitelist-default"));

        DataboardAiSettingsModel model = DataboardAiSettingsModel.from(
                context.configLoader(),
                AiQueueCounts.zero()
        );

        assertAll(
                () -> assertEquals("敏感信息", model.getWhitelistGroup()),
                () -> assertEquals(List.of("JSON Web Token", "JWT", "idCard", "身份证"), model.getWhitelistNames()),
                () -> assertTrue(model.whitelistExcludesNoisyDefaults()),
                () -> assertFalse(model.getWhitelistNames().contains("Linkfinder")),
                () -> assertFalse(model.getWhitelistNames().contains("All URL"))
        );
    }

    @Test
    void savingPrimaryWhitelistRulePreservesAdditionalRules() throws Exception {
        BoardContext context = createBoardContext(tempDirectory.resolve("whitelist-preserve"));
        context.configLoader().setAIWhitelist(List.of(
                new AiWhitelistRule("敏感信息", List.of("JWT")),
                new AiWhitelistRule("Custom Secrets", List.of("Session Token", "Private Key"))
        ));
        DataboardAiSettingsModel model = DataboardAiSettingsModel.from(
                context.configLoader(),
                AiQueueCounts.zero()
        );

        model.setWhitelistGroup("Updated Sensitive");
        model.setWhitelistNames(List.of("Authorization Header", "Cookie"));
        DataboardAiSettingsModel.SaveResult result = model.saveTo(context.configLoader(), true);
        List<AiWhitelistRule> reloadedRules = context.configLoader().getAiConfig().getWhitelist();

        assertAll(
                () -> assertTrue(result.isSaved()),
                () -> assertEquals(2, reloadedRules.size()),
                () -> assertEquals("Updated Sensitive", reloadedRules.get(0).getGroup()),
                () -> assertEquals(List.of("Authorization Header", "Cookie"), reloadedRules.get(0).getNames()),
                () -> assertEquals("Custom Secrets", reloadedRules.get(1).getGroup()),
                () -> assertEquals(List.of("Session Token", "Private Key"), reloadedRules.get(1).getNames())
        );
    }

    @Test
    void queueStatusCountsAllStates() throws Exception {
        BoardContext context = createBoardContext(tempDirectory.resolve("queue-counts"));
        SqliteMessageStore store = context.store();
        saveMessage(store, "message-pending", "hash-pending");
        saveMessage(store, "message-running", "hash-running");
        saveMessage(store, "message-succeeded", "hash-succeeded");
        saveMessage(store, "message-failed", "hash-failed");
        saveMessage(store, "message-skipped", "hash-skipped");
        enqueue(store, "task-pending", "message-pending", "hash-pending");
        enqueue(store, "task-running", "message-running", "hash-running");
        enqueue(store, "task-succeeded", "message-succeeded", "hash-succeeded");
        enqueue(store, "task-failed", "message-failed", "hash-failed");
        enqueue(store, "task-skipped", "message-skipped", "hash-skipped");

        assertEquals(5, store.leaseNextAiTriageTasks(5, 10_000L, 60_000L).size());
        store.releaseAiTriageTask("task-pending", 0);
        store.saveAiTriageResult(
                "message-succeeded",
                "hash-succeeded",
                "analysis-default",
                "DONE",
                "not_sensitive",
                "info",
                0.97,
                "AI triage completed.",
                "{}",
                30_000L,
                AiTriageSchema.SCHEMA_VERSION,
                AiTriageSchema.PROMPT_VERSION,
                "model-a",
                "config-a"
        );
        store.completeAiTriageTask("task-succeeded");
        store.failAiTriageTask("task-failed", "RATE_LIMIT", "retry later", 20_000L);
        store.saveAiTriageResult(
                "message-skipped",
                "hash-skipped",
                "analysis-default",
                "SKIPPED",
                "unknown",
                "unknown",
                0.0,
                "AI triage skipped: skipped_binary",
                "{}",
                30_000L,
                AiTriageSchema.SCHEMA_VERSION,
                AiTriageSchema.PROMPT_VERSION,
                "model-a",
                "config-a"
        );
        store.completeAiTriageTask("task-skipped");

        AiQueueCounts counts = store.loadAiQueueCounts();
        context.configLoader().setAIApiKey("sk-queue-secret-value-1234567890");
        DataboardAiSettingsModel model = DataboardAiSettingsModel.from(context.configLoader(), counts);
        String queueStatusText = model.getQueueStatusText();

        assertAll(
                () -> assertEquals(1, counts.getPending()),
                () -> assertEquals(1, counts.getRunning()),
                () -> assertEquals(1, counts.getSucceeded()),
                () -> assertEquals(1, counts.getFailed()),
                () -> assertEquals(1, counts.getSkipped()),
                () -> assertEquals(50.0, counts.getCompletionSuccessRate()),
                () -> assertEquals("待处理=1 运行中=1 成功=1 失败=1 跳过=1 成功率=50.0%", counts.toStatusText()),
                () -> assertEquals("待处理=1 运行中=1 成功=1 失败=1 跳过=1 成功率=50.0% API key=sk****7890", queueStatusText),
                () -> assertFalse(queueStatusText.contains("sk-queue-secret-value-1234567890"))
        );
    }

    @Test
    void queueActionsClearPendingAndRetryFailed() throws Exception {
        BoardContext context = createBoardContext(tempDirectory.resolve("queue-actions"));
        SqliteMessageStore store = context.store();
        saveMessage(store, "message-pending", "hash-pending");
        saveMessage(store, "message-failed", "hash-failed");
        enqueue(store, "task-pending", "message-pending", "hash-pending");
        enqueue(store, "task-failed", "message-failed", "hash-failed");
        store.leaseNextAiTriageTasks(1, 1_000L, 60_000L);
        store.failAiTriageTask("task-pending", "RATE_LIMIT", "retry", 10_000L);
        store.failAiTriageTask("task-failed", "AUTH", "bad key", Long.MAX_VALUE);
        DataboardAiSettingsController controller = new DataboardAiSettingsController(
                context.configLoader(),
                store,
                null,
                Runnable::run
        );
        try {
            DataboardAiSettingsController.ActionResult retryResult = controller.retryFailedAsync().get(2, TimeUnit.SECONDS);
            DataboardAiSettingsController.ActionResult clearResult = controller.clearPendingAsync().get(2, TimeUnit.SECONDS);

            assertAll(
                    () -> assertTrue(retryResult.isSuccess()),
                    () -> assertTrue(retryResult.getMessage().contains("2")),
                    () -> assertTrue(clearResult.isSuccess()),
                    () -> assertTrue(clearResult.getMessage().contains("2")),
                    () -> assertEquals(0, store.loadAiQueueCounts().getPending()),
                    () -> assertEquals(0, tableCount(context.databasePath(), AI_TASK_TABLE))
            );
        } finally {
            controller.shutdown();
        }
    }

    @Test
    void pauseResumeControllerCallsWithoutEdtBlocking() throws Exception {
        BoardContext context = createBoardContext(tempDirectory.resolve("pause-resume"));
        AtomicInteger pauseCalls = new AtomicInteger(0);
        AtomicInteger resumeCalls = new AtomicInteger(0);
        AtomicBoolean pauseRanOnEdt = new AtomicBoolean(true);
        AtomicBoolean resumeRanOnEdt = new AtomicBoolean(true);
        CountDownLatch pauseEntered = new CountDownLatch(1);
        CountDownLatch resumeEntered = new CountDownLatch(1);
        CountDownLatch releaseControls = new CountDownLatch(1);
        Executor backgroundExecutor = command -> {
            Thread thread = new Thread(command, "ai-settings-test-worker");
            thread.setDaemon(true);
            thread.start();
        };
        DataboardAiSettingsController.WorkerControls controls = new DataboardAiSettingsController.WorkerControls() {
            @Override
            public void pause() {
                pauseRanOnEdt.set(SwingUtilities.isEventDispatchThread());
                pauseCalls.incrementAndGet();
                pauseEntered.countDown();
                awaitRelease();
            }

            @Override
            public void resume() {
                resumeRanOnEdt.set(SwingUtilities.isEventDispatchThread());
                resumeCalls.incrementAndGet();
                resumeEntered.countDown();
                awaitRelease();
            }

            @Override
            public String statusSummary() {
                return "test-worker";
            }

            private void awaitRelease() {
                try {
                    assertTrue(releaseControls.await(2, TimeUnit.SECONDS), "test should release fake worker controls");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted while waiting for fake worker release", e);
                }
            }
        };
        DataboardAiSettingsController controller = new DataboardAiSettingsController(
                context.configLoader(),
                context.store(),
                controls,
                backgroundExecutor
        );
        try {
            AtomicReference<CompletableFuture<DataboardAiSettingsController.ActionResult>> pauseFuture = new AtomicReference<>();
            AtomicReference<CompletableFuture<DataboardAiSettingsController.ActionResult>> resumeFuture = new AtomicReference<>();

            long startedAt = System.nanoTime();
            SwingUtilities.invokeAndWait(() -> {
                pauseFuture.set(controller.pauseWorkerAsync());
                resumeFuture.set(controller.resumeWorkerAsync());
            });
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertTrue(pauseEntered.await(2, TimeUnit.SECONDS), "pause control should run on the background executor");
            assertTrue(resumeEntered.await(2, TimeUnit.SECONDS), "resume control should run on the background executor");
            assertAll(
                    () -> assertTrue(elapsedMillis < 500, "EDT should return after scheduling pause/resume without waiting for worker controls"),
                    () -> assertFalse(pauseFuture.get().isDone(), "pause action should remain asynchronous while fake worker is blocked"),
                    () -> assertFalse(resumeFuture.get().isDone(), "resume action should remain asynchronous while fake worker is blocked")
            );

            releaseControls.countDown();
            DataboardAiSettingsController.ActionResult pauseResult = pauseFuture.get().get(2, TimeUnit.SECONDS);
            DataboardAiSettingsController.ActionResult resumeResult = resumeFuture.get().get(2, TimeUnit.SECONDS);

            assertAll(
                    () -> assertEquals(1, pauseCalls.get()),
                    () -> assertEquals(1, resumeCalls.get()),
                    () -> assertFalse(pauseRanOnEdt.get()),
                    () -> assertFalse(resumeRanOnEdt.get()),
                    () -> assertTrue(pauseResult.isSuccess()),
                    () -> assertTrue(resumeResult.isSuccess()),
                    () -> assertTrue(pauseResult.getMessage().contains("test-worker")),
                    () -> assertTrue(resumeResult.getMessage().contains("test-worker")),
                    () -> assertFalse(pauseResult.isRanOnEdt()),
                    () -> assertFalse(resumeResult.isRanOnEdt())
            );
        } finally {
            releaseControls.countDown();
            controller.shutdown();
        }
    }

    @Test
    void burpProxySelectionBlocksSaveBecauseUnsupported() throws Exception {
        BoardContext context = createBoardContext(tempDirectory.resolve("burp-proxy-unsupported"));
        DataboardAiSettingsController controller = new DataboardAiSettingsController(
                context.configLoader(),
                context.store(),
                null,
                Runnable::run
        );
        try {
            DataboardAiSettingsModel model = controller.loadModel();
            model.setUseBurpProxy(true);

            DataboardAiSettingsModel.SaveResult result = controller.saveSettings(model, true);

            assertAll(
                    () -> assertFalse(result.isSaved()),
                    () -> assertTrue(result.getMessage().contains("暂不支持")),
                    () -> assertFalse(context.configLoader().getAIUseBurpProxy())
            );
        } finally {
            controller.shutdown();
        }
    }

    @Test
    void unsupportedAnalyzeSelectedReturnsClearStatus() throws Exception {
        BoardContext context = createBoardContext(tempDirectory.resolve("analyze-unsupported"));
        DataboardAiSettingsController controller = new DataboardAiSettingsController(
                context.configLoader(),
                context.store(),
                null,
                Runnable::run
        );
        try {
            DataboardAiSettingsController.ActionResult result = controller.analyzeSelectedAsync().get(2, TimeUnit.SECONDS);

            assertAll(
                    () -> assertFalse(controller.isAnalyzeSelectedSupported()),
                    () -> assertFalse(result.isSupported()),
                    () -> assertTrue(result.getMessage().contains("当前页面没有可分析"))
            );
        } finally {
            controller.shutdown();
        }
    }

    @Test
    void analyzeSelectedQueuesExistingPersistedMessage() throws Exception {
        BoardContext context = createBoardContext(tempDirectory.resolve("analyze-selected-existing"));
        context.configLoader().setAIEnabled(true);
        context.configLoader().setAIBaseUrl("https://ai.example.test/v1");
        context.configLoader().setAIModel("model-a");
        context.configLoader().setAIApiKey("sk-test");
        context.configLoader().setAIAnalyzeOncePerMessage(false);
        saveMessage(context.store(), "message-selected", "hash-selected");
        MessageTableModel tableModel = newTestModel(context);
        DataboardAiSettingsController controller = new DataboardAiSettingsController(
                context.configLoader(),
                context.store(),
                null,
                tableModel,
                Runnable::run
        );
        try {
            tableModel.loadPersistedMessages();
            waitUntil(() -> tableModel.getRowCount() == 1, "persisted message should load into table");
            SwingUtilities.invokeAndWait(() -> tableModel.getMessageTable().changeSelection(0, 0, false, false));

            DataboardAiSettingsController.ActionResult result = controller.analyzeSelectedAsync().get(2, TimeUnit.SECONDS);

            assertAll(
                    () -> assertTrue(controller.isAnalyzeSelectedSupported()),
                    () -> assertTrue(result.isSupported()),
                    () -> assertTrue(result.isSuccess()),
                    () -> assertTrue(result.getMessage().contains("已加入 AI 分析队列")),
                    () -> assertEquals(1, context.store().loadAiQueueCounts().getPending()),
                    () -> assertEquals(1, tableCount(context.databasePath(), AI_TASK_TABLE))
            );
        } finally {
            controller.shutdown();
            tableModel.clearAllDataOnShutdown();
        }
    }

    @Test
    void analyzeSelectedFromBackgroundExecutorReadsSwingSelectionOnEdt() throws Exception {
        BoardContext context = createBoardContext(tempDirectory.resolve("analyze-selected-background"));
        context.configLoader().setAIEnabled(true);
        context.configLoader().setAIBaseUrl("https://ai.example.test/v1");
        context.configLoader().setAIModel("model-a");
        context.configLoader().setAIApiKey("sk-test");
        context.configLoader().setAIAnalyzeOncePerMessage(false);
        saveMessage(context.store(), "message-selected-bg", "hash-selected-bg");
        MessageTableModel tableModel = newTestModel(context);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        DataboardAiSettingsController controller = new DataboardAiSettingsController(
                context.configLoader(),
                context.store(),
                null,
                tableModel,
                executor
        );
        try {
            tableModel.loadPersistedMessages();
            waitUntil(() -> tableModel.getRowCount() == 1, "persisted message should load into table");
            SwingUtilities.invokeAndWait(() -> tableModel.getMessageTable().changeSelection(0, 0, false, false));

            DataboardAiSettingsController.ActionResult result = controller.analyzeSelectedAsync().get(2, TimeUnit.SECONDS);

            assertAll(
                    () -> assertTrue(result.isSupported()),
                    () -> assertTrue(result.isSuccess()),
                    () -> assertEquals(1, context.store().loadAiQueueCounts().getPending())
            );
        } finally {
            controller.shutdown();
            executor.shutdownNow();
            tableModel.clearAllDataOnShutdown();
        }
    }

    @Test
    void closeShutsDownControllerOwnedExecutor() throws Exception {
        BoardContext context = createBoardContext(tempDirectory.resolve("controller-close"));
        DataboardAiSettingsController controller = new DataboardAiSettingsController(
                context.configLoader(),
                context.store()
        );

        controller.shutdown();

        assertTrue(controller.isClosedForTest());
    }

    private static void enqueue(SqliteMessageStore store, String taskId, String messageId, String contentHash) {
        assertTrue(store.enqueueAiTriageTask(
                taskId,
                messageId,
                contentHash,
                "analysis-default",
                "signature-" + taskId,
                AiTriageSchema.SCHEMA_VERSION,
                AiTriageSchema.PROMPT_VERSION,
                "model-a",
                "config-a",
                1,
                3,
                0
        ));
    }

    private static void saveMessage(SqliteMessageStore store, String messageId, String contentHash) {
        store.saveMessage(
                messageId,
                httpRequestResponse("ai.example.test", "/" + messageId, 200),
                "https://ai.example.test/" + messageId,
                "GET",
                "200",
                "42",
                "Token (1)",
                "yellow",
                contentHash,
                Map.of("JSON Web Token", List.of("token-" + messageId))
        );
    }

    private static void waitUntil(BooleanSupplier condition, String message) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
        }
        throw new AssertionError(message);
    }

    private static MessageTableModel newTestModel(BoardContext context) {
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

    private static BoardContext createBoardContext(Path home) throws Exception {
        Files.createDirectories(home.resolve(".config").resolve("HaE"));
        String originalHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", home.toString());
            MontoyaApi api = montoyaApiProxy(home);
            ConfigLoader configLoader = new ConfigLoader(api);
            return new BoardContext(api, configLoader, new SqliteMessageStore(api, configLoader), haeDatabasePath(home));
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

    private static int tableCount(Path databasePath, String tableName) throws Exception {
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
             java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet resultSet = statement.executeQuery("SELECT COUNT(1) FROM " + tableName)) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    private static HttpRequestResponse httpRequestResponse(String host, String path, int statusCode) {
        HttpService service = httpServiceProxy(host);
        HttpRequest request = httpRequestProxy(service, host, path);
        HttpResponse response = httpResponseProxy(statusCode);
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
        byte[] requestBytes = ("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nAuthorization: Bearer test-token\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        ByteArray byteArray = byteArrayProxy(requestBytes);
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "httpService" -> service;
            case "url" -> "https://" + host + path;
            case "path" -> path;
            case "method" -> "GET";
            case "fileExtension" -> "";
            case "toByteArray" -> byteArray;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpRequest.class, handler);
    }

    private static HttpResponse httpResponseProxy(int statusCode) {
        byte[] responseBytes = ("HTTP/1.1 " + statusCode + " OK\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        ByteArray byteArray = byteArrayProxy(responseBytes);
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "statusCode" -> primitiveNumber(method, statusCode);
            case "toByteArray" -> byteArray;
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

    private static MontoyaApi montoyaApiProxy(Path home) {
        Extension extension = extensionProxy(home);
        Logging logging = loggingProxy();
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "extension" -> extension;
            case "logging" -> logging;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(MontoyaApi.class, handler);
    }

    private static Extension extensionProxy(Path home) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "filename" -> home.resolve("HaE.jar").toString();
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(Extension.class, handler);
    }

    private static Logging loggingProxy() {
        InvocationHandler handler = (proxy, method, args) -> null;
        return proxyFor(Logging.class, handler);
    }

    private static Object primitiveNumber(Method method, int value) {
        Class<?> returnType = method.getReturnType();
        if (returnType == Short.TYPE || returnType == Short.class) {
            return (short) value;
        }
        if (returnType == Long.TYPE || returnType == Long.class) {
            return (long) value;
        }
        return value;
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
        if (returnType == List.class) {
            return Collections.emptyList();
        }
        if (returnType.isInterface()) {
            return proxyFor(returnType);
        }
        return null;
    }

    private static List<JTextArea> textAreasIn(Container container) {
        java.util.ArrayList<JTextArea> result = new java.util.ArrayList<>();
        collectTextAreas(container, result);
        return result;
    }

    private static JList<?> firstListIn(Container container) {
        java.util.ArrayList<JList<?>> result = new java.util.ArrayList<>();
        collectLists(container, result);
        if (result.isEmpty()) {
            throw new AssertionError("Expected at least one JList in AI settings panel");
        }
        return result.get(0);
    }

    private static List<String> listValues(JList<?> list) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (int i = 0; i < list.getModel().getSize(); i++) {
            result.add(list.getModel().getElementAt(i).toString());
        }
        return result;
    }

    private static List<String> selectedListValues(JList<?> list) throws Exception {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (int i = 0; i < list.getModel().getSize(); i++) {
            Object item = list.getModel().getElementAt(i);
            java.lang.reflect.Method selectedMethod = item.getClass().getDeclaredMethod("selected");
            selectedMethod.setAccessible(true);
            if (Boolean.TRUE.equals(selectedMethod.invoke(item))) {
                result.add(item.toString());
            }
        }
        return result;
    }

    private static void collectTextAreas(Container container, List<JTextArea> result) {
        for (Component component : container.getComponents()) {
            if (component instanceof JTextArea textArea) {
                result.add(textArea);
            }
            if (component instanceof Container child) {
                collectTextAreas(child, result);
            }
        }
    }

    private static void collectLists(Container container, List<JList<?>> result) {
        for (Component component : container.getComponents()) {
            if (component instanceof JList<?> list) {
                result.add(list);
            }
            if (component instanceof Container child) {
                collectLists(child, result);
            }
        }
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "test proxy for " + proxy.getClass().getInterfaces()[0].getName();
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> args != null && args.length > 0 && proxy == args[0];
            default -> null;
        };
    }

    private record BoardContext(MontoyaApi api, ConfigLoader configLoader, SqliteMessageStore store, Path databasePath) {
    }

    private static class DefaultProxyHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return defaultProxyValue(proxy, method, args);
        }
    }
}
