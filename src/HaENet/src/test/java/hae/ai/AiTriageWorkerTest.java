package hae.ai;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import hae.TestFixtures;
import hae.ai.client.AiClient;
import hae.ai.client.AiClientException;
import hae.ai.client.AiClientFailureCategory;
import hae.ai.client.AiClientResult;
import hae.ai.parser.AiVerdictParser;
import hae.ai.prompt.AiPromptBuildResult;
import hae.ai.prompt.AiPromptBuilder;
import hae.ai.worker.AiTriageMessageContextLoader;
import hae.ai.worker.AiTriageWorker;
import hae.ai.worker.AiTriageWorkerConfig;
import hae.ai.worker.AiTriageWorkerStatus;
import hae.ai.worker.RepositoryAiTriageMessageContextLoader;
import hae.storage.SqliteMessageStore;
import hae.utils.ConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class AiTriageWorkerTest {
    private static final String AI_TASK_TABLE = "ai_triage_task";
    private static final String AI_RESULT_TABLE = "ai_triage_result";

    @TempDir
    Path tempDirectory;

    @Test
    void processesTaskToSucceeded() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("worker-success"));
        SqliteMessageStore store = context.store();
        saveMainMessage(store, "message-1", "hash-1", "secret-token-12345");
        enqueue(store, "task-1", "message-1", "hash-1", 2, 0);
        FakeAiClient client = FakeAiClient.success(successJson("sensitive_exposure", "high", "Token is exposed"));

        AiTriageWorker worker = worker(store, client, config(2_000_000), AiTriageWorkerConfig.builder().autoStart(false).build());

        assertTrue(worker.processOneForTest());

        assertAll(
                () -> assertEquals(1, client.callCount()),
                () -> assertPromptContainsHttpEvidence(client.lastPrompt()),
                () -> assertTrue(client.lastPrompt().contains(AiTriageSchema.SCHEMA_VERSION)),
                () -> assertTrue(client.lastPrompt().contains("matched_value_redacted")),
                () -> assertEquals("DONE", taskColumn(context.databasePath(), "task-1", "status")),
                () -> assertEquals(1, tableCount(context.databasePath(), AI_RESULT_TABLE)),
                () -> assertEquals("DONE", resultColumn(context.databasePath(), "message-1", "status")),
                () -> assertEquals(AiTriageVerdict.SENSITIVE_EXPOSURE.getWireValue(), resultColumn(context.databasePath(), "message-1", "overall_verdict")),
                () -> assertEquals(AiTriageRiskLevel.HIGH.getWireValue(), resultColumn(context.databasePath(), "message-1", "overall_severity")),
                () -> assertEquals("Token is exposed", resultColumn(context.databasePath(), "message-1", "summary")),
                () -> assertEquals(successJson("sensitive_exposure", "high", "Token is exposed"), store.loadAiTriageResultJson("message-1")),
                () -> assertEquals(0L, worker.getInFlightChars())
        );
    }

    @Test
    void resultJsonDoesNotPersistProviderEchoedHttpEvidence() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("worker-provider-echo-redaction"));
        SqliteMessageStore store = context.store();
        saveMainMessage(store, "message-echo", "hash-echo", "secret-token-12345");
        enqueue(store, "task-echo", "message-echo", "hash-echo", 2, 0);
        String echoedEvidenceJson = "{\"overall_verdict\":\"possible_sensitive\"," +
                "\"overall_severity\":\"medium\"," +
                "\"confidence\":0.55," +
                "\"summary\":\"-----BEGIN UNTRUSTED HTTP EVIDENCE----- GET /api/token HTTP/1.1 Host: example.test\"," +
                "\"items_truncated\":false," +
                "\"omitted_item_count\":0," +
                "\"items\":[{" +
                "\"rule_group\":\"Sensitive Information\"," +
                "\"rule_name\":\"JWT\"," +
                "\"rule_hash\":\"hash-jwt\"," +
                "\"matched_value_redacted\":\"HTTP/1.1 200 OK token\"," +
                "\"match_location\":\"response body\"," +
                "\"verdict\":\"sensitive_exposure\"," +
                "\"is_sensitive\":true," +
                "\"is_exposed\":true," +
                "\"confidence\":0.8," +
                "\"severity\":\"high\"," +
                "\"reason\":\"RESPONSE: HTTP/1.1 200 OK token body\"," +
                "\"recommended_actions\":[\"Do not repeat REQUEST: GET /api/token HTTP/1.1\"]" +
                "}]}";
        FakeAiClient client = FakeAiClient.success(echoedEvidenceJson);
        AiTriageWorker worker = worker(store, client, config(2_000_000), AiTriageWorkerConfig.builder().autoStart(false).build());

        assertTrue(worker.processOneForTest());

        String storedJson = store.loadAiTriageResultJson("message-echo");
        assertAll(
                () -> assertEquals("DONE", resultColumn(context.databasePath(), "message-echo", "status")),
                () -> assertEquals("[redacted prompt evidence]", resultColumn(context.databasePath(), "message-echo", "summary")),
                () -> assertTrue(storedJson.contains("[redacted prompt evidence]")),
                () -> assertFalse(storedJson.contains(AiPromptBuilder.BEGIN_EVIDENCE)),
                () -> assertFalse(storedJson.contains("GET /api/token HTTP/1.1")),
                () -> assertFalse(storedJson.contains("Host: example.test")),
                () -> assertFalse(storedJson.contains("HTTP/1.1 200 OK")),
                () -> assertFalse(storedJson.contains("REQUEST:")),
                () -> assertFalse(storedJson.contains("RESPONSE:")),
                () -> assertFalse(storedJson.contains("secret-token-12345"))
        );
    }

    @Test
    void targetTaskBuildsPromptWithOnlyMatchingSmallTarget() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("worker-single-target-prompt"));
        SqliteMessageStore store = context.store();
        Map<String, List<String>> matches = Map.of(
                "密码", List.of("accSetPwd:\"/passApi/js/accSetPwd.js\"", "uni_accSetPwd:\"/passApi/js/uni_accSetPwd.js\""),
                "用户名", List.of("changeUser:\"/passApi/js/changeUser.js\"")
        );
        store.saveMessage(
                "message-target",
                requestResponse("accSetPwd:/passApi/js/accSetPwd.js"),
                "https://example.test/message-target",
                "GET",
                "200",
                "42",
                "密码 (2), 用户名 (1)",
                "orange",
                "hash-target",
                matches
        );
        String selectedValue = "uni_accSetPwd:\"/passApi/js/uni_accSetPwd.js\"";
        String targetSignature = hashForTest("密码|" + hashForTest(selectedValue));
        assertTrue(store.enqueueAiTriageTask(
                "task-target",
                "message-target",
                "hash-target",
                "analysis-target",
                targetSignature,
                AiTriageSchema.SCHEMA_VERSION,
                AiTriageSchema.PROMPT_VERSION,
                "model-a",
                "config-a",
                1,
                2,
                0L
        ));
        FakeAiClient client = FakeAiClient.success(successJson("false_positive", "info", "Only one static path target was analyzed"));
        AiConfig aiConfig = config(2_000_000, List.of(new AiWhitelistRule("敏感信息", List.of("密码", "用户名"))));
        AiTriageWorker worker = worker(store, client, aiConfig, AiTriageWorkerConfig.builder().autoStart(false).build());

        assertTrue(worker.processOneForTest());

        String prompt = client.lastPrompt();
        assertAll(
                () -> assertEquals(1, client.callCount()),
                () -> assertTrue(prompt.contains("uni_accSetPwd")),
                () -> assertFalse(prompt.contains("changeUser")),
                () -> assertFalse(prompt.contains("accSetPwd:\\\"/passApi/js/accSetPwd.js")),
                () -> assertTrue(prompt.contains("\"items\":[{\"rule_group\":\"")),
                () -> assertEquals("DONE", taskColumn(context.databasePath(), "task-target", "status"))
        );
    }

    @Test
    void targetTaskSkipsWhenSignatureNoLongerMatchesInsteadOfSendingSiblingTargets() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("worker-missing-target-prompt"));
        SqliteMessageStore store = context.store();
        Map<String, List<String>> matches = Map.of(
                "密码", List.of("accSetPwd:\"/passApi/js/accSetPwd.js\""),
                "用户名", List.of("changeUser:\"/passApi/js/changeUser.js\"")
        );
        store.saveMessage(
                "message-missing-target",
                requestResponse("accSetPwd:/passApi/js/accSetPwd.js"),
                "https://example.test/message-missing-target",
                "GET",
                "200",
                "42",
                "密码 (1), 用户名 (1)",
                "orange",
                "hash-missing-target",
                matches
        );
        String removedValue = "removed_conwid=840";
        String targetSignature = hashForTest("GET 明文id|" + hashForTest(removedValue));
        assertTrue(store.enqueueAiTriageTask(
                "task-missing-target",
                "message-missing-target",
                "hash-missing-target",
                "analysis-missing-target",
                targetSignature,
                AiTriageSchema.SCHEMA_VERSION,
                AiTriageSchema.PROMPT_VERSION,
                "model-a",
                "config-a",
                1,
                2,
                0L
        ));
        FakeAiClient client = FakeAiClient.success(successJson("false_positive", "info", "unused"));
        AiConfig aiConfig = config(2_000_000, List.of(new AiWhitelistRule("敏感信息", List.of("GET 明文id", "密码", "用户名"))));
        AiTriageWorker worker = worker(store, client, aiConfig, AiTriageWorkerConfig.builder().autoStart(false).build());

        assertTrue(worker.processOneForTest());

        assertAll(
                () -> assertEquals(0, client.callCount()),
                () -> assertEquals("DONE", taskColumn(context.databasePath(), "task-missing-target", "status")),
                () -> assertEquals("SKIPPED", resultColumn(context.databasePath(), "message-missing-target", "status")),
                () -> assertEquals("AI triage skipped: skipped_no_whitelisted_match (no whitelisted matches)",
                        resultColumn(context.databasePath(), "message-missing-target", "summary")),
                () -> assertEquals(1, tableCount(context.databasePath(), AI_RESULT_TABLE))
        );
    }

    @Test
    void emptyUnknownAiResponseRetriesInsteadOfSavingDoneResult() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("worker-empty-advisory"));
        SqliteMessageStore store = context.store();
        saveMainMessage(store, "message-empty", "hash-empty", "secret-token-12345");
        MutableClock clock = new MutableClock(10_000L);
        enqueue(store, "task-empty", "message-empty", "hash-empty", 2, 0);
        FakeAiClient client = FakeAiClient.success(emptyUnknownJson());

        AiTriageWorker worker = worker(
                store,
                client,
                config(2_000_000),
                AiTriageWorkerConfig.builder().autoStart(false).retryBaseDelayMillis(1_000L).retryMaxDelayMillis(2_000L).build(),
                clock,
                millis -> {
                }
        );

        assertTrue(worker.processOneForTest());

        assertAll(
                () -> assertEquals(2, client.callCount()),
                () -> assertEquals("FAILED", taskColumn(context.databasePath(), "task-empty", "status")),
                () -> assertEquals(AiTriageWorker.ERROR_PARSE_FAILED, taskColumn(context.databasePath(), "task-empty", "last_error_code")),
                () -> assertTrue(Long.parseLong(taskColumn(context.databasePath(), "task-empty", "next_attempt_at")) > clock.nowMillis()),
                () -> assertEquals(0, tableCount(context.databasePath(), AI_RESULT_TABLE)),
                () -> assertEquals(0L, worker.getInFlightChars())
        );
    }

    @Test
    void parseFailureRepairCompletesWhenSecondAnswerProvidesMissingFields() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("worker-repair-parse-failure"));
        SqliteMessageStore store = context.store();
        saveMainMessage(store, "message-repair", "hash-repair", "secret-token-12345");
        enqueue(store, "task-repair", "message-repair", "hash-repair", 2, 0);
        FakeAiClient client = new FakeAiClient(prompt -> {
            if (prompt != null && prompt.contains("Previous invalid answer:")) {
                return new AiClientResult(200, successJson("false_positive", "info", "补全后确认不是敏感信息。"));
            }
            return new AiClientResult(200, emptyUnknownJson());
        });
        AiTriageWorker worker = worker(store, client, config(2_000_000), AiTriageWorkerConfig.builder().autoStart(false).build());

        assertTrue(worker.processOneForTest());

        String repairPrompt = client.lastPrompt();
        assertAll(
                () -> assertEquals(2, client.callCount()),
                () -> assertTrue(repairPrompt.contains("Missing or invalid information:")),
                () -> assertTrue(repairPrompt.contains(AiTriageResultQuality.EMPTY_ADVISORY)),
                () -> assertTrue(repairPrompt.contains("Previous invalid answer:")),
                () -> assertFalse(repairPrompt.contains("secret-token-12345")),
                () -> assertEquals("DONE", taskColumn(context.databasePath(), "task-repair", "status")),
                () -> assertEquals("DONE", resultColumn(context.databasePath(), "message-repair", "status")),
                () -> assertEquals(AiTriageVerdict.FALSE_POSITIVE.getWireValue(), resultColumn(context.databasePath(), "message-repair", "overall_verdict")),
                () -> assertEquals("补全后确认不是敏感信息。", resultColumn(context.databasePath(), "message-repair", "summary")),
                () -> assertEquals(1, tableCount(context.databasePath(), AI_RESULT_TABLE))
        );
    }

    @Test
    void lowQualityNonEmptyAiResponseRetriesInsteadOfSavingDoneResult() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("worker-low-quality-advisory"));
        SqliteMessageStore store = context.store();
        saveMainMessage(store, "message-low-quality", "hash-low-quality", "secret-token-12345");
        MutableClock clock = new MutableClock(10_000L);
        enqueue(store, "task-low-quality", "message-low-quality", "hash-low-quality", 2, 0);
        FakeAiClient client = FakeAiClient.success(lowQualityFalsePositiveJson());

        AiTriageWorker worker = worker(
                store,
                client,
                config(2_000_000),
                AiTriageWorkerConfig.builder().autoStart(false).retryBaseDelayMillis(1_000L).retryMaxDelayMillis(2_000L).build(),
                clock,
                millis -> {
                }
        );

        assertTrue(worker.processOneForTest());

        assertAll(
                () -> assertEquals(2, client.callCount()),
                () -> assertEquals("FAILED", taskColumn(context.databasePath(), "task-low-quality", "status")),
                () -> assertEquals(AiTriageWorker.ERROR_PARSE_FAILED, taskColumn(context.databasePath(), "task-low-quality", "last_error_code")),
                () -> assertTrue(Long.parseLong(taskColumn(context.databasePath(), "task-low-quality", "next_attempt_at")) > clock.nowMillis()),
                () -> assertEquals(0, tableCount(context.databasePath(), AI_RESULT_TABLE)),
                () -> assertEquals(0L, worker.getInFlightChars())
        );
    }

    @Test
    void missingOverallWithStrongItemSynthesizesAndSavesDoneResult() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("worker-missing-overall-strong-item"));
        SqliteMessageStore store = context.store();
        saveMainMessage(store, "message-strong-item", "hash-strong-item", "secret-token-12345");
        MutableClock clock = new MutableClock(10_000L);
        enqueue(store, "task-strong-item", "message-strong-item", "hash-strong-item", 2, 0);
        FakeAiClient client = FakeAiClient.success(missingOverallStrongItemJson());

        AiTriageWorker worker = worker(
                store,
                client,
                config(2_000_000),
                AiTriageWorkerConfig.builder().autoStart(false).retryBaseDelayMillis(1_000L).retryMaxDelayMillis(2_000L).build(),
                clock,
                millis -> {
                }
        );

        assertTrue(worker.processOneForTest());

        assertAll(
                () -> assertEquals(1, client.callCount()),
                () -> assertEquals("DONE", taskColumn(context.databasePath(), "task-strong-item", "status")),
                () -> assertEquals("DONE", resultColumn(context.databasePath(), "message-strong-item", "status")),
                () -> assertEquals(AiTriageVerdict.FALSE_POSITIVE.getWireValue(), resultColumn(context.databasePath(), "message-strong-item", "overall_verdict")),
                () -> assertEquals(AiTriageRiskLevel.INFO.getWireValue(), resultColumn(context.databasePath(), "message-strong-item", "overall_severity")),
                () -> assertEquals("Static JavaScript route name, no account identifier present", resultColumn(context.databasePath(), "message-strong-item", "summary")),
                () -> assertEquals(1, tableCount(context.databasePath(), AI_RESULT_TABLE)),
                () -> assertEquals(0L, worker.getInFlightChars())
        );
    }

    @Test
    void taskMaxAttemptsIsNotCappedByWorkerDefault() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("worker-task-max-attempts"));
        SqliteMessageStore store = context.store();
        saveMainMessage(store, "message-task-max", "hash-task-max", "secret-token-12345");
        enqueue(store, "task-max-attempts", "message-task-max", "hash-task-max", 3, 0);
        MutableClock clock = new MutableClock(10_000L);
        AiTriageWorkerConfig workerConfig = AiTriageWorkerConfig.builder()
                .retryBaseDelayMillis(1_000L)
                .retryMaxDelayMillis(10_000L)
                .defaultMaxAttempts(2)
                .autoStart(false)
                .build();
        FakeAiClient client = FakeAiClient.success(emptyUnknownJson());
        AiTriageWorker worker = worker(store, client, config(2_000_000), workerConfig, clock, millis -> {
        });

        assertTrue(worker.processOneForTest());
        long firstNextAttemptAt = Long.parseLong(taskColumn(context.databasePath(), "task-max-attempts", "next_attempt_at"));
        clock.advanceMillis(firstNextAttemptAt - clock.nowMillis());
        assertTrue(worker.processOneForTest());
        long secondNextAttemptAt = Long.parseLong(taskColumn(context.databasePath(), "task-max-attempts", "next_attempt_at"));
        clock.advanceMillis(secondNextAttemptAt - clock.nowMillis());
        assertTrue(worker.processOneForTest());
        boolean fourthProcessed = worker.processOneForTest();

        assertAll(
                () -> assertFalse(fourthProcessed),
                () -> assertEquals(6, client.callCount()),
                () -> assertEquals("FAILED", taskColumn(context.databasePath(), "task-max-attempts", "status")),
                () -> assertEquals(AiTriageWorker.ERROR_PARSE_FAILED, taskColumn(context.databasePath(), "task-max-attempts", "last_error_code")),
                () -> assertEquals("3", taskColumn(context.databasePath(), "task-max-attempts", "attempt_count")),
                () -> assertEquals(String.valueOf(Long.MAX_VALUE), taskColumn(context.databasePath(), "task-max-attempts", "next_attempt_at"))
        );
    }

    @Test
    void respectsMaxInFlightChars() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("worker-inflight-budget"));
        SqliteMessageStore store = context.store();
        saveMainMessage(store, "message-large-1", "hash-large-1", "secret-token-12345");
        saveMainMessage(store, "message-large-2", "hash-large-2", "secret-token-67890");
        enqueue(store, "task-large-1", "message-large-1", "hash-large-1", 2, 0);
        enqueue(store, "task-large-2", "message-large-2", "hash-large-2", 2, 0);
        CountDownLatch firstCallEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstCall = new CountDownLatch(1);
        FakeAiClient client = new FakeAiClient(prompt -> {
            if (firstCallEntered.getCount() > 0) {
                firstCallEntered.countDown();
                awaitInFakeAi(releaseFirstCall);
            }
            return new AiClientResult(200, successJson("not_sensitive", "info", "No exposure"));
        });
        AiTriageWorkerConfig workerConfig = AiTriageWorkerConfig.builder()
                .maxInFlightChars(300)
                .idlePollMillis(50)
                .autoStart(false)
                .build();
        FixedPromptBuilder promptBuilder = new FixedPromptBuilder(220);
        MutableClock clock = new MutableClock(10_000L);

        AiTriageWorker worker = worker(store, client, config(2_000_000), workerConfig, clock, millis -> {
        }, promptBuilder);
        AtomicReference<Boolean> firstProcessed = new AtomicReference<>(false);
        Thread firstThread = new Thread(() -> firstProcessed.set(worker.processOneForTest()), "first-budget-worker-test");

        firstThread.start();
        assertTrue(firstCallEntered.await(2, TimeUnit.SECONDS));
        assertEquals(220L, worker.getInFlightChars());
        boolean secondProcessedWhileBudgetBusy = worker.processOneForTest();
        releaseFirstCall.countDown();
        firstThread.join(2_000L);
        clock.advanceMillis(50L);
        boolean secondProcessedAfterBudgetFreed = worker.processOneForTest();

        assertAll(
                () -> assertTrue(firstProcessed.get()),
                () -> assertFalse(secondProcessedWhileBudgetBusy),
                () -> assertTrue(secondProcessedAfterBudgetFreed),
                () -> assertEquals(2, client.callCount()),
                () -> assertEquals("DONE", taskColumn(context.databasePath(), "task-large-1", "status")),
                () -> assertEquals("DONE", taskColumn(context.databasePath(), "task-large-2", "status")),
                () -> assertEquals("", taskColumn(context.databasePath(), "task-large-2", "last_error_code")),
                () -> assertEquals("0", taskColumn(context.databasePath(), "task-large-2", "leased_until")),
                () -> assertEquals(2, tableCount(context.databasePath(), AI_RESULT_TABLE)),
                () -> assertEquals(0L, worker.getInFlightChars())
        );
    }

    @Test
    void failsIndividuallyOversizedPrompt() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("worker-oversized-prompt"));
        SqliteMessageStore store = context.store();
        saveMainMessage(store, "message-large", "hash-large", "secret-token-12345");
        enqueue(store, "task-large", "message-large", "hash-large", 2, 0);
        FakeAiClient client = FakeAiClient.success(successJson("not_sensitive", "info", "unused"));
        AiTriageWorkerConfig workerConfig = AiTriageWorkerConfig.builder()
                .maxInFlightChars(200)
                .autoStart(false)
                .build();

        AiTriageWorker worker = worker(store, client, config(2_000_000), workerConfig);

        assertTrue(worker.processOneForTest());

        assertAll(
                () -> assertEquals(0, client.callCount()),
                () -> assertEquals("FAILED", taskColumn(context.databasePath(), "task-large", "status")),
                () -> assertEquals("PROMPT_TOO_LARGE", taskColumn(context.databasePath(), "task-large", "last_error_code")),
                () -> assertEquals(0, tableCount(context.databasePath(), AI_RESULT_TABLE)),
                () -> assertEquals(0L, worker.getInFlightChars())
        );
    }

    @Test
    void concurrencyDefaultsAndCapsAtEight() {
        AiTriageWorkerConfig defaults = AiTriageWorkerConfig.fromAiConfig(config(2_000_000));
        AiTriageWorkerConfig capped = AiTriageWorkerConfig.fromAiConfig(new AiConfig(
                true,
                false,
                "openai-compatible",
                "https://ai.example.test/v1",
                "model-a",
                "test-key",
                180,
                99,
                99,
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
                10_000,
                false,
                List.of(new AiWhitelistRule("Sensitive Information", List.of("JWT")))
        ));

        assertAll(
                () -> assertEquals(2, defaults.getConcurrency()),
                () -> assertEquals(8, capped.getConcurrency()),
                () -> assertEquals(2_000_000, defaults.getMaxInFlightChars())
        );
    }

    @Test
    void pauseResumePreventsClaimsUntilResumed() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("worker-pause-resume"));
        SqliteMessageStore store = context.store();
        saveMainMessage(store, "message-pause", "hash-pause", "secret-token-12345");
        enqueue(store, "task-pause", "message-pause", "hash-pause", 2, 0);
        AiTriageWorker worker = worker(store,
                FakeAiClient.success(successJson("not_sensitive", "info", "No exposure")),
                config(2_000_000),
                AiTriageWorkerConfig.builder().autoStart(false).build());

        worker.pause();
        boolean pausedProcessed = worker.processOneForTest();
        worker.resume();
        boolean resumedProcessed = worker.processOneForTest();

        assertAll(
                () -> assertFalse(pausedProcessed),
                () -> assertTrue(resumedProcessed),
                () -> assertEquals("DONE", taskColumn(context.databasePath(), "task-pause", "status")),
                () -> assertEquals(AiTriageWorkerStatus.CREATED, worker.getStatus())
        );
    }

    @Test
    void shutdownRecoversStaleLeasesAndStopsExecutor() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("worker-shutdown-recovery"));
        SqliteMessageStore store = context.store();
        saveMainMessage(store, "message-stale", "hash-stale", "secret-token-12345");
        enqueue(store, "task-stale", "message-stale", "hash-stale", 2, 0);
        List<SqliteMessageStore.AiTriageTask> leased = store.leaseNextAiTriageTasks(1, 1_000L, 50L);
        assertEquals(1, leased.size());

        MutableClock clock = new MutableClock(1_100L);
        AiTriageWorker worker = worker(store,
                FakeAiClient.success(successJson("not_sensitive", "info", "No exposure")),
                config(2_000_000),
                AiTriageWorkerConfig.builder().autoStart(false).build(),
                clock,
                millis -> {
                });

        worker.shutdown();

        assertAll(
                () -> assertTrue(worker.isShutdown()),
                () -> assertEquals(AiTriageWorkerStatus.SHUTDOWN, worker.getStatus()),
                () -> assertEquals("PENDING", taskColumn(context.databasePath(), "task-stale", "status")),
                () -> assertEquals("0", taskColumn(context.databasePath(), "task-stale", "leased_until"))
        );
    }

    @Test
    void shutdownRecoversActiveLeaseBeforeExpiry() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("worker-shutdown-active-lease"));
        SqliteMessageStore store = context.store();
        saveMainMessage(store, "message-active", "hash-active", "secret-token-12345");
        enqueue(store, "task-active", "message-active", "hash-active", 2, 0);
        CountDownLatch aiCallEntered = new CountDownLatch(1);
        CountDownLatch releaseAiCall = new CountDownLatch(1);
        FakeAiClient client = new FakeAiClient(prompt -> {
            aiCallEntered.countDown();
            awaitInFakeAi(releaseAiCall);
            return new AiClientResult(200, successJson("not_sensitive", "info", "No exposure"));
        });
        MutableClock clock = new MutableClock(5_000L);
        AiTriageWorkerConfig workerConfig = AiTriageWorkerConfig.builder()
                .leaseDurationMillis(60_000L)
                .autoStart(false)
                .build();
        AiTriageWorker worker = worker(store, client, config(2_000_000), workerConfig, clock, millis -> {
        });
        AtomicReference<Boolean> processed = new AtomicReference<>(false);
        Thread workerThread = new Thread(() -> processed.set(worker.processOneForTest()), "shutdown-active-lease-test");

        workerThread.start();
        assertTrue(aiCallEntered.await(2, TimeUnit.SECONDS));
        assertEquals("LEASED", taskColumn(context.databasePath(), "task-active", "status"));
        assertTrue(Long.parseLong(taskColumn(context.databasePath(), "task-active", "leased_until")) > clock.nowMillis());
        worker.shutdown();
        releaseAiCall.countDown();
        workerThread.join(2_000L);

        assertAll(
                () -> assertFalse(processed.get()),
                () -> assertTrue(worker.isShutdown()),
                () -> assertEquals(AiTriageWorkerStatus.SHUTDOWN, worker.getStatus()),
                () -> assertEquals("PENDING", taskColumn(context.databasePath(), "task-active", "status")),
                () -> assertEquals("0", taskColumn(context.databasePath(), "task-active", "leased_until")),
                () -> assertEquals("0", taskColumn(context.databasePath(), "task-active", "attempt_count")),
                () -> assertEquals(0, tableCount(context.databasePath(), AI_RESULT_TABLE)),
                () -> assertEquals(0L, worker.getInFlightChars())
        );
    }

    @Test
    void permanentAuthConfigFailureDoesNotThrowOrStopOtherWorkers() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("worker-permanent-failure"));
        SqliteMessageStore store = context.store();
        saveMainMessage(store, "message-auth", "hash-auth", "secret-token-12345");
        enqueue(store, "task-auth", "message-auth", "hash-auth", 2, 0);
        FakeAiClient client = FakeAiClient.failures(new AiClientException(
                "AI API key is not configured.",
                AiClientFailureCategory.PERMANENT_AUTH_CONFIG
        ));
        AiTriageWorker worker = worker(store, client, config(2_000_000), AiTriageWorkerConfig.builder().autoStart(false).build());

        assertTrue(worker.processOneForTest());

        assertAll(
                () -> assertEquals("FAILED", taskColumn(context.databasePath(), "task-auth", "status")),
                () -> assertEquals("PERMANENT_AUTH_CONFIG", taskColumn(context.databasePath(), "task-auth", "last_error_code")),
                () -> assertEquals(0, tableCount(context.databasePath(), AI_RESULT_TABLE)),
                () -> assertEquals(0, worker.getActiveWorkerCount())
        );
    }

    @Test
    void retryableFailureSchedulesBackoffWithJitter() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("worker-retryable"));
        SqliteMessageStore store = context.store();
        saveMainMessage(store, "message-retry", "hash-retry", "secret-token-12345");
        enqueue(store, "task-retry", "message-retry", "hash-retry", 2, 0);
        MutableClock clock = new MutableClock(10_000L);
        RecordingSleeper sleeper = new RecordingSleeper();
        AiTriageWorkerConfig workerConfig = AiTriageWorkerConfig.builder()
                .retryBaseDelayMillis(1_000L)
                .retryMaxDelayMillis(10_000L)
                .defaultMaxAttempts(2)
                .autoStart(false)
                .build();
        FakeAiClient client = FakeAiClient.failures(new AiClientException(
                "AI provider returned retryable HTTP status 429.",
                AiClientFailureCategory.RETRYABLE,
                429
        ));
        AiTriageWorker worker = worker(store, client, config(2_000_000), workerConfig, clock, sleeper);

        assertTrue(worker.processOneForTest());

        long nextAttemptAt = Long.parseLong(taskColumn(context.databasePath(), "task-retry", "next_attempt_at"));
        assertAll(
                () -> assertEquals(1, client.callCount()),
                () -> assertTrue(sleeper.sleeps().isEmpty()),
                () -> assertEquals("FAILED", taskColumn(context.databasePath(), "task-retry", "status")),
                () -> assertEquals("RATE_LIMIT", taskColumn(context.databasePath(), "task-retry", "last_error_code")),
                () -> assertTrue(nextAttemptAt >= 11_000L),
                () -> assertTrue(nextAttemptAt <= 11_250L)
        );
    }

    @Test
    void retryableFailureStopsAfterMaxAttempts() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("worker-retryable-exhausted"));
        SqliteMessageStore store = context.store();
        saveMainMessage(store, "message-retry-exhausted", "hash-retry-exhausted", "secret-token-12345");
        enqueue(store, "task-retry-exhausted", "message-retry-exhausted", "hash-retry-exhausted", 2, 0);
        MutableClock clock = new MutableClock(10_000L);
        AiTriageWorkerConfig workerConfig = AiTriageWorkerConfig.builder()
                .retryBaseDelayMillis(1_000L)
                .retryMaxDelayMillis(10_000L)
                .defaultMaxAttempts(2)
                .autoStart(false)
                .build();
        FakeAiClient client = new FakeAiClient(prompt -> {
            throw new AiClientException(
                    "AI provider returned retryable HTTP status 503.",
                    AiClientFailureCategory.RETRYABLE,
                    503
            );
        });
        AiTriageWorker worker = worker(store, client, config(2_000_000), workerConfig, clock, millis -> {
        });

        boolean firstProcessed = worker.processOneForTest();
        long nextAttemptAt = Long.parseLong(taskColumn(context.databasePath(), "task-retry-exhausted", "next_attempt_at"));
        clock.advanceMillis(nextAttemptAt - clock.nowMillis());
        boolean secondProcessed = worker.processOneForTest();
        boolean thirdProcessed = worker.processOneForTest();

        assertAll(
                () -> assertTrue(firstProcessed),
                () -> assertTrue(secondProcessed),
                () -> assertFalse(thirdProcessed),
                () -> assertEquals(2, client.callCount()),
                () -> assertEquals("FAILED", taskColumn(context.databasePath(), "task-retry-exhausted", "status")),
                () -> assertEquals("RETRYABLE_AI_FAILURE", taskColumn(context.databasePath(), "task-retry-exhausted", "last_error_code")),
                () -> assertEquals("2", taskColumn(context.databasePath(), "task-retry-exhausted", "attempt_count")),
                () -> assertEquals(String.valueOf(Long.MAX_VALUE), taskColumn(context.databasePath(), "task-retry-exhausted", "next_attempt_at")),
                () -> assertEquals(0, tableCount(context.databasePath(), AI_RESULT_TABLE)),
                () -> assertEquals(0L, worker.getInFlightChars())
        );
    }

    @Test
    void networkCallHappensOutsideRepositorySynchronization() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("worker-no-db-lock-during-ai"));
        SqliteMessageStore store = context.store();
        saveMainMessage(store, "message-lock", "hash-lock", "secret-token-12345");
        enqueue(store, "task-lock", "message-lock", "hash-lock", 2, 0);
        CountDownLatch aiCallEntered = new CountDownLatch(1);
        CountDownLatch dbReadCompleted = new CountDownLatch(1);
        FakeAiClient client = new FakeAiClient(prompt -> {
            aiCallEntered.countDown();
            long count = tableCountUnchecked(context.databasePath(), AI_TASK_TABLE);
            if (count == 1L) {
                dbReadCompleted.countDown();
            }
            return new AiClientResult(200, successJson("not_sensitive", "info", "No exposure"));
        });
        AiTriageWorker worker = worker(store, client, config(2_000_000), AiTriageWorkerConfig.builder().autoStart(false).build());

        assertTrue(worker.processOneForTest());

        assertAll(
                () -> assertTrue(aiCallEntered.await(1, TimeUnit.SECONDS)),
                () -> assertTrue(dbReadCompleted.await(1, TimeUnit.SECONDS)),
                () -> assertEquals("DONE", taskColumn(context.databasePath(), "task-lock", "status"))
        );
    }

    @Test
    void autoStartedThreadsUseHaEAiTriageWorkerNames() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("worker-thread-names"));
        SqliteMessageStore store = context.store();
        CountDownLatch firstSleep = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AiTriageWorkerSleeperForTest sleeper = new AiTriageWorkerSleeperForTest() {
            @Override
            public void sleepMillis(long millis) throws InterruptedException {
                firstSleep.countDown();
                release.await(1, TimeUnit.SECONDS);
                throw new InterruptedException("stop test worker");
            }
        };
        AiTriageWorkerConfig workerConfig = AiTriageWorkerConfig.builder()
                .concurrency(1)
                .autoStart(true)
                .idlePollMillis(10)
                .build();
        AiTriageWorker worker = worker(store,
                FakeAiClient.success(successJson("not_sensitive", "info", "No exposure")),
                config(2_000_000),
                workerConfig,
                new MutableClock(1_000L),
                sleeper);

        assertTrue(firstSleep.await(2, TimeUnit.SECONDS));
        boolean namedThreadSeen = Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.getName().startsWith("HaE-AI-Triage-Worker-"));
        release.countDown();
        worker.shutdown();

        assertTrue(namedThreadSeen);
    }

    private AiTriageWorker worker(SqliteMessageStore store,
                                  AiClient client,
                                  AiConfig config,
                                  AiTriageWorkerConfig workerConfig) {
        return worker(store, client, config, workerConfig, new MutableClock(System.currentTimeMillis()), millis -> {
        });
    }

    private AiTriageWorker worker(SqliteMessageStore store,
                                  AiClient client,
                                  AiConfig config,
                                  AiTriageWorkerConfig workerConfig,
                                  MutableClock clock,
                                  AiTriageWorkerSleeperForTest sleeper) {
        return worker(store, client, config, workerConfig, clock, sleeper, new AiPromptBuilder());
    }

    private AiTriageWorker worker(SqliteMessageStore store,
                                  AiClient client,
                                  AiConfig config,
                                  AiTriageWorkerConfig workerConfig,
                                  MutableClock clock,
                                  AiTriageWorkerSleeperForTest sleeper,
                                  AiPromptBuilder promptBuilder) {
        AiTriageMessageContextLoader loader = new RepositoryAiTriageMessageContextLoader(store);
        return new AiTriageWorker(
                store,
                store,
                loader,
                promptBuilder,
                new AiVerdictParser(),
                client,
                config,
                workerConfig,
                clock::nowMillis,
                sleeper::sleepMillis,
                ignored -> {
                },
                new Random(0L)
        );
    }

    private static AiConfig config(int maxInFlightChars) {
        return config(maxInFlightChars, List.of(new AiWhitelistRule("Sensitive Information", List.of("JWT"))));
    }

    private static AiConfig config(int maxInFlightChars, List<AiWhitelistRule> whitelist) {
        return new AiConfig(
                true,
                false,
                "openai-compatible",
                "https://ai.example.test/v1",
                "model-a",
                "test-key",
                180,
                2,
                8,
                maxInFlightChars,
                800_000,
                200_000,
                600_000,
                50,
                true,
                true,
                true,
                true,
                true,
                10_000,
                false,
                whitelist
        );
    }

    private static String successJson(String verdict, String riskLevel, String summary) {
        return "{\"overall_verdict\":\"" + verdict + "\"," 
                + "\"overall_severity\":\"" + riskLevel + "\","
                + "\"confidence\":0.75,"
                + "\"summary\":\"" + summary + "\","
                + "\"items_truncated\":false,"
                + "\"omitted_item_count\":0,"
                + "\"items\":[]}";
    }

    private static String emptyUnknownJson() {
        return "{\"overall_verdict\":\"unknown\","
                + "\"overall_severity\":\"unknown\","
                + "\"confidence\":0.0,"
                + "\"summary\":\"\","
                + "\"items_truncated\":false,"
                + "\"omitted_item_count\":0,"
                + "\"items\":[]}";
    }

    private static String lowQualityFalsePositiveJson() {
        return "{\"overall_verdict\":\"false_positive\"," +
                "\"overall_severity\":\"unknown\"," +
                "\"confidence\":0.0," +
                "\"summary\":\"\"," +
                "\"items_truncated\":false," +
                "\"omitted_item_count\":0," +
                "\"items\":[{\"rule_group\":\"敏感信息\",\"rule_name\":\"密码\",\"rule_hash\":\"hash-1\"," +
                "\"matched_value_redacted\":\"accSetPwd:\\\"/[redacted]\\\"\",\"match_location\":\"match context pending\"," +
                "\"verdict\":\"false_positive\",\"is_sensitive\":false,\"is_exposed\":false," +
                "\"confidence\":0.0,\"severity\":\"unknown\",\"reason\":\"static asset\",\"recommended_actions\":[]}] }";
    }

    private static String missingOverallStrongItemJson() {
        return "{\"overall_verdict\":\"unknown\"," +
                "\"overall_severity\":\"unknown\"," +
                "\"confidence\":0.0," +
                "\"summary\":\"\"," +
                "\"items_truncated\":false," +
                "\"omitted_item_count\":0," +
                "\"items\":[{\"rule_group\":\"敏感信息\",\"rule_name\":\"用户名\",\"rule_hash\":\"hash-1\"," +
                "\"matched_value_redacted\":\"\",\"match_location\":\"\",\"verdict\":\"false_positive\"," +
                "\"is_sensitive\":false,\"is_exposed\":false,\"confidence\":0.98,\"severity\":\"info\"," +
                "\"reason\":\"Static JavaScript route name, no account identifier present\",\"recommended_actions\":[]}] }";
    }

    private static String hashForTest(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest((value == null ? "" : value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hashed.length * 2);
            for (byte current : hashed) {
                builder.append(String.format("%02x", current));
            }
            return builder.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static void assertPromptContainsHttpEvidence(String prompt) {
        assertAll(
                () -> assertTrue(prompt.contains(AiPromptBuilder.BEGIN_EVIDENCE)),
                () -> assertTrue(prompt.contains(AiPromptBuilder.END_EVIDENCE)),
                () -> assertTrue(prompt.contains("REQUEST:")),
                () -> assertTrue(prompt.contains("GET /api/token HTTP/1.1")),
                () -> assertTrue(prompt.contains("Host: example.test")),
                () -> assertTrue(prompt.contains("RESPONSE:")),
                () -> assertTrue(prompt.contains("HTTP/1.1 200 OK")),
                () -> assertTrue(prompt.contains("Content-Type: application/json")),
                () -> assertTrue(prompt.contains("token")),
                () -> assertTrue(prompt.contains("worker-response-body-evidence"))
        );
    }

    private static void awaitInFakeAi(CountDownLatch latch) throws AiClientException {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiClientException("Interrupted fake AI wait", AiClientFailureCategory.RETRYABLE);
        }
    }

    private static void enqueue(SqliteMessageStore store,
                                String taskId,
                                String messageId,
                                String contentHash,
                                int maxAttempts,
                                long nextAttemptAt) {
        assertTrue(store.enqueueAiTriageTask(
                taskId,
                messageId,
                contentHash,
                "analysis-default",
                "",
                AiTriageSchema.SCHEMA_VERSION,
                AiTriageSchema.PROMPT_VERSION,
                "model-a",
                "config-a",
                10,
                maxAttempts,
                nextAttemptAt
        ));
    }

    private static void saveMainMessage(SqliteMessageStore store, String messageId, String contentHash, String secretValue) {
        store.saveMessage(
                messageId,
                requestResponse(secretValue),
                "https://example.test/" + messageId,
                "GET",
                "200",
                "42",
                "main comment " + messageId,
                "green",
                contentHash,
                Map.of("JWT", List.of(secretValue))
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

    private static Path haeDatabasePath(Path home) {
        return home.resolve(".config").resolve("HaE").resolve("History.db");
    }

    private static HttpRequestResponse requestResponse(String secretValue) {
        String requestText = "GET /api/token HTTP/1.1\r\nHost: example.test\r\nAccept: application/json\r\n\r\n";
        String responseText = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"token\":\"" + secretValue +
                "\",\"evidence\":\"worker-response-body-evidence\"}";
        HttpService service = httpServiceProxy("example.test");
        HttpRequest request = httpRequestProxy(service, requestText);
        HttpResponse response = httpResponseProxy(responseText);
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

    private static HttpRequest httpRequestProxy(HttpService service, String requestText) {
        byte[] requestBytes = requestText.getBytes(StandardCharsets.ISO_8859_1);
        ByteArray byteArray = byteArrayProxy(requestBytes);
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "httpService" -> service;
            case "method" -> "GET";
            case "path" -> "/api/token";
            case "url" -> "https://example.test/api/token";
            case "fileExtension" -> "json";
            case "toByteArray" -> byteArray;
            case "body" -> bodyByteArray(requestText);
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpRequest.class, handler);
    }

    private static HttpResponse httpResponseProxy(String responseText) {
        byte[] responseBytes = responseText.getBytes(StandardCharsets.ISO_8859_1);
        ByteArray byteArray = byteArrayProxy(responseBytes);
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "toByteArray" -> byteArray;
            case "body" -> bodyByteArray(responseText);
            case "statusCode" -> (short) 200;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpResponse.class, handler);
    }

    private static ByteArray bodyByteArray(String message) {
        int index = message.indexOf("\r\n\r\n");
        int bodyStart = index >= 0 ? index + 4 : message.indexOf("\n\n") + 2;
        String body = bodyStart >= 2 && bodyStart <= message.length() ? message.substring(bodyStart) : "";
        return byteArrayProxy(body.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static ByteArray byteArrayProxy(byte[] bytes) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getBytes" -> bytes;
            case "length" -> bytes.length;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(ByteArray.class, handler);
    }

    private static String taskColumn(Path databasePath, String taskId, String columnName) throws Exception {
        return singleString(databasePath, AI_TASK_TABLE, columnName, "task_id", taskId);
    }

    private static String resultColumn(Path databasePath, String messageId, String columnName) throws Exception {
        return singleString(databasePath, AI_RESULT_TABLE, columnName, "message_id", messageId);
    }

    private static long tableCount(Path databasePath, String tableName) throws Exception {
        try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(databasePath))) {
            return TestFixtures.countRows(connection, tableName);
        }
    }

    private static long tableCountUnchecked(Path databasePath, String tableName) {
        try {
            return tableCount(databasePath, tableName);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
                case "equals" -> proxy == args[0];
                default -> null;
            };
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
            return proxyFor(returnType, (nestedProxy, nestedMethod, nestedArgs) -> defaultProxyValue(nestedProxy, nestedMethod, nestedArgs));
        }
        return null;
    }

    private record StoreContext(SqliteMessageStore store, Path databasePath) {
    }

    private interface AiTriageWorkerSleeperForTest {
        void sleepMillis(long millis) throws InterruptedException;
    }

    private static final class MutableClock {
        private long now;

        private MutableClock(long now) {
            this.now = now;
        }

        private long nowMillis() {
            return now;
        }

        private void advanceMillis(long millis) {
            now += millis;
        }
    }

    private static final class FixedPromptBuilder extends AiPromptBuilder {
        private final int promptChars;

        private FixedPromptBuilder(int promptChars) {
            this.promptChars = promptChars;
        }

        @Override
        public AiPromptBuildResult build(String messageId,
                                         HttpRequestResponse requestResponse,
                                         Map<String, List<String>> extractedDataByRule,
                                         AiConfig config) {
            AiTriageRequestItem item = new AiTriageRequestItem(
                    "Sensitive Information",
                    "JWT",
                    "hash-jwt",
                    "[REDACTED]",
                    "response body"
            );
            return AiPromptBuildResult.builtFull("x".repeat(promptChars), new AiTriageRequest(List.of(item), false, 0));
        }
    }

    private static class RecordingSleeper implements AiTriageWorkerSleeperForTest {
        private final List<Long> sleeps = new ArrayList<>();

        @Override
        public void sleepMillis(long millis) {
            sleeps.add(millis);
        }

        private List<Long> sleeps() {
            return sleeps;
        }
    }

    private static final class FakeAiClient implements AiClient {
        private final Queue<Object> outcomes = new ArrayDeque<>();
        private final AtomicInteger calls = new AtomicInteger(0);
        private final AtomicReference<AiClientHandler> handler = new AtomicReference<>();
        private final AtomicReference<String> lastPrompt = new AtomicReference<>("");

        private FakeAiClient(AiClientHandler handler) {
            this.handler.set(handler);
        }

        private static FakeAiClient success(String responseBody) {
            return new FakeAiClient(prompt -> new AiClientResult(200, responseBody));
        }

        private static FakeAiClient failures(AiClientException exception) {
            FakeAiClient client = new FakeAiClient(null);
            client.outcomes.add(exception);
            return client;
        }

        @Override
        public AiClientResult complete(String prompt) throws AiClientException {
            calls.incrementAndGet();
            lastPrompt.set(prompt == null ? "" : prompt);
            AiClientHandler currentHandler = handler.get();
            if (currentHandler != null) {
                return currentHandler.complete(prompt);
            }
            Object outcome = outcomes.poll();
            if (outcome instanceof AiClientException exception) {
                throw exception;
            }
            if (outcome instanceof AiClientResult result) {
                return result;
            }
            throw new AiClientException("No fake AI response configured", AiClientFailureCategory.PERMANENT_CONFIG);
        }

        private int callCount() {
            return calls.get();
        }

        private String lastPrompt() {
            return lastPrompt.get();
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
