package hae.ai;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import hae.TestFixtures;
import hae.repository.AiTaskRepository.AiTriageResultWrite;
import hae.storage.SqliteMessageStore;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiTaskRepositoryTest {
    private static final String MESSAGE_TABLE = "message_history";
    private static final String MATCH_TABLE = "message_match";
    private static final String AI_TASK_TABLE = "ai_triage_task";
    private static final String AI_RESULT_TABLE = "ai_triage_result";

    @TempDir
    Path tempDirectory;

    @Test
    void createsTablesAndIndexes() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("fresh-ai-schema"));

        try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(context.databasePath()))) {
            assertAll(
                    () -> assertSqliteObjectExists(connection, "table", AI_TASK_TABLE),
                    () -> assertSqliteObjectExists(connection, "table", AI_RESULT_TABLE),
                    () -> assertSqliteObjectExists(connection, "index", "idx_ai_triage_task_message_id"),
                    () -> assertSqliteObjectExists(connection, "index", "idx_ai_triage_task_analysis_key"),
                    () -> assertSqliteObjectExists(connection, "index", "idx_ai_triage_task_status_next_attempt_at"),
                    () -> assertSqliteObjectExists(connection, "index", "idx_ai_triage_task_content_hash"),
                    () -> assertSqliteObjectExists(connection, "index", "idx_ai_triage_result_message_id"),
                    () -> assertSqliteObjectExists(connection, "index", "idx_ai_triage_result_analysis_key"),
                    () -> assertSqliteObjectExists(connection, "index", "idx_ai_triage_result_match_signature_hash"),
                    () -> assertSqliteObjectExists(connection, "index", "idx_ai_triage_result_status"),
                    () -> assertSqliteObjectExists(connection, "index", "idx_ai_triage_result_content_hash"),
                    () -> assertColumnExists(connection, AI_RESULT_TABLE, "match_signature_hash")
            );
        }
    }

    @Test
    void preventsDuplicateActiveTask() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("duplicate-active-ai-task"));
        SqliteMessageStore store = context.store();
        saveMainMessage(store, "message-1", "hash-1");

        assertAll(
                () -> assertTrue(enqueue(store, "task-original", "message-1", "hash-1", "analysis-default", "signature-1", 5, 3, 0)),
                () -> assertFalse(enqueue(store, "task-duplicate", "message-1", "hash-1", "analysis-default", "signature-2", 99, 3, 0)),
                () -> assertEquals(1, tableCount(context.databasePath(), AI_TASK_TABLE))
        );
    }

    @Test
    void legacyMainSchemaMigrationAddsAiTablesWithoutChangingMainRows() throws Exception {
        Path home = tempDirectory.resolve("legacy-ai-schema");
        Path databasePath = haeDatabasePath(home);
        Files.createDirectories(databasePath.getParent());
        createExistingMainTables(databasePath);

        StoreContext context = createStoreContext(home);

        try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(context.databasePath()))) {
            assertAll(
                    () -> TestFixtures.assertSqlCount(connection, MESSAGE_TABLE, 1),
                    () -> TestFixtures.assertSqlCount(connection, MATCH_TABLE, 1),
                    () -> assertEquals("https://example.test/existing", singleString(connection,
                            "SELECT url FROM message_history WHERE message_id = ?",
                            "existing-main-1")),
                    () -> assertSqliteObjectExists(connection, "table", AI_TASK_TABLE),
                    () -> assertSqliteObjectExists(connection, "table", AI_RESULT_TABLE)
            );
        }
    }

    @Test
    void enqueueDeduplicatesAndLeaseOrdersRetryableTasksByPriorityAndAge() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("lease-ai-tasks"));
        SqliteMessageStore store = context.store();
        saveMainMessage(store, "message-1", "hash-1");
        saveMainMessage(store, "message-2", "hash-2");
        saveMainMessage(store, "message-3", "hash-3");
        long now = 10_000L;

        assertTrue(enqueue(store, "task-low", "message-1", "hash-1", "analysis-default", "signature-1", 1, 3, 0));
        assertTrue(enqueue(store, "task-high", "message-2", "hash-2", "analysis-default", "signature-2", 10, 3, 0));
        assertTrue(enqueue(store, "task-later", "message-3", "hash-3", "analysis-default", "signature-3", 20, 3, now + 1_000));
        assertFalse(enqueue(store, "task-duplicate", "message-1", "hash-1", "analysis-default", "signature-1b", 99, 3, 0));

        List<SqliteMessageStore.AiTriageTask> leased = store.leaseNextAiTriageTasks(5, now, 5_000);
        assertAll(
                () -> assertEquals(List.of("task-high", "task-low"), leased.stream().map(SqliteMessageStore.AiTriageTask::getTaskId).toList()),
                () -> assertEquals(List.of(1, 1), leased.stream().map(SqliteMessageStore.AiTriageTask::getAttemptCount).toList()),
                () -> assertEquals(List.of("LEASED", "LEASED"), leased.stream().map(SqliteMessageStore.AiTriageTask::getStatus).toList()),
                () -> assertEquals(List.of("", ""), leased.stream().map(SqliteMessageStore.AiTriageTask::getLeaseOwner).toList()),
                () -> assertTrue(leased.stream().allMatch(task -> task.getLeaseToken() != null && !task.getLeaseToken().isBlank())),
                () -> assertEquals(List.of(now + 5_000, now + 5_000), leased.stream().map(SqliteMessageStore.AiTriageTask::getLeasedUntil).toList()),
                () -> assertEquals(3, tableCount(context.databasePath(), AI_TASK_TABLE))
        );
    }

    @Test
    void failureRecoveryCompletionAndDeleteCleanupStayInSqlite() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("recovery-cleanup"));
        SqliteMessageStore store = context.store();
        saveMainMessage(store, "message-1", "hash-1");
        saveMainMessage(store, "message-2", "hash-2");
        saveMainMessage(store, "message-orphan", "hash-orphan");
        long now = 20_000L;

        assertTrue(enqueue(store, "task-complete", "message-1", "hash-1", "analysis-default", "signature-1", 5, 3, 0));
        assertTrue(enqueue(store, "task-retry", "message-2", "hash-2", "analysis-default", "signature-2", 5, 3, 0));
        assertTrue(enqueue(store, "task-orphan", "message-orphan", "hash-orphan", "analysis-default", "signature-orphan", 5, 3, 0));

        List<SqliteMessageStore.AiTriageTask> leased = store.leaseNextAiTriageTasks(3, now, 100);
        assertEquals(3, leased.size());
        assertTrue(store.completeAiTriageTask("task-complete"));
        assertTrue(store.failAiTriageTask("task-retry", "RATE_LIMIT", "sanitized retry message", now + 1_000));
        assertEquals(1, store.recoverStaleAiTriageTasks(now + 200));

        store.deleteByHostPattern("orphan.example.test");
        assertAll(
                () -> assertEquals(0, store.cleanupOrphanAiTriageTasks()),
                () -> assertEquals("DONE", singleString(context.databasePath(), AI_TASK_TABLE, "status", "task_id", "task-complete")),
                () -> assertEquals("FAILED", singleString(context.databasePath(), AI_TASK_TABLE, "status", "task_id", "task-retry")),
                () -> assertNull(singleString(context.databasePath(), AI_TASK_TABLE, "status", "task_id", "task-orphan")),
                () -> assertEquals("RATE_LIMIT", singleString(context.databasePath(), AI_TASK_TABLE, "last_error_code", "task_id", "task-retry")),
                () -> assertEquals(2, tableCount(context.databasePath(), AI_TASK_TABLE))
        );
    }

    @Test
    void recoversStaleLeasedAndRunningTasksAccordingToRetryBudget() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("stale-recovery"));
        SqliteMessageStore store = context.store();
        saveMainMessage(store, "message-leased", "hash-leased");
        saveMainMessage(store, "message-running", "hash-running");
        saveMainMessage(store, "message-exhausted", "hash-exhausted");
        long leaseNow = 50_000L;
        long recoverNow = 50_200L;

        assertTrue(enqueue(store, "task-leased", "message-leased", "hash-leased", "analysis-leased", "signature-leased", 5, 2, 0));
        assertTrue(enqueue(store, "task-running", "message-running", "hash-running", "analysis-running", "signature-running", 5, 2, 0));
        assertTrue(enqueue(store, "task-exhausted", "message-exhausted", "hash-exhausted", "analysis-exhausted", "signature-exhausted", 5, 1, 0));
        assertEquals(3, store.leaseNextAiTriageTasks(3, leaseNow, 100).size());
        updateTaskStatus(context.databasePath(), "task-running", "RUNNING");

        int recovered = store.recoverStaleAiTriageTasks(recoverNow);

        assertAll(
                () -> assertEquals(3, recovered),
                () -> assertEquals("PENDING", singleString(context.databasePath(), AI_TASK_TABLE, "status", "task_id", "task-leased")),
                () -> assertEquals("PENDING", singleString(context.databasePath(), AI_TASK_TABLE, "status", "task_id", "task-running")),
                () -> assertEquals("FAILED", singleString(context.databasePath(), AI_TASK_TABLE, "status", "task_id", "task-exhausted")),
                () -> assertEquals(recoverNow, singleLong(context.databasePath(), AI_TASK_TABLE, "next_attempt_at", "task_id", "task-leased")),
                () -> assertEquals(recoverNow, singleLong(context.databasePath(), AI_TASK_TABLE, "next_attempt_at", "task_id", "task-running")),
                () -> assertEquals(0L, singleLong(context.databasePath(), AI_TASK_TABLE, "leased_until", "task_id", "task-leased")),
                () -> assertEquals(0L, singleLong(context.databasePath(), AI_TASK_TABLE, "leased_until", "task_id", "task-running")),
                () -> assertEquals("", singleString(context.databasePath(), AI_TASK_TABLE, "lease_owner", "task_id", "task-leased")),
                () -> assertEquals("", singleString(context.databasePath(), AI_TASK_TABLE, "lease_token", "task_id", "task-running")),
                () -> assertEquals("LEASE_EXPIRED", singleString(context.databasePath(), AI_TASK_TABLE, "last_error_code", "task_id", "task-exhausted"))
        );
    }

    @Test
    void staleLeaseCannotCompleteFailOrReleaseAfterRecovery() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("stale-lease-token-guard"));
        SqliteMessageStore store = context.store();
        saveMainMessage(store, "message-stale", "hash-stale");
        assertTrue(enqueue(store, "task-stale", "message-stale", "hash-stale", "analysis-stale", "signature-stale", 5, 3, 0));
        SqliteMessageStore.AiTriageTask firstLease = store.leaseNextAiTriageTasks(1, 1_000L, 100L, "owner-a").get(0);

        assertEquals(1, store.recoverStaleAiTriageTasks(1_200L));
        assertFalse(store.completeAiTriageTask(firstLease.getTaskId(), firstLease.getLeaseOwner(), firstLease.getLeaseToken()));
        assertFalse(store.failAiTriageTask(firstLease.getTaskId(), firstLease.getLeaseOwner(), firstLease.getLeaseToken(), "STALE", "stale", 2_000L));
        assertFalse(store.releaseAiTriageTask(firstLease.getTaskId(), firstLease.getLeaseOwner(), firstLease.getLeaseToken(), 2_000L));

        SqliteMessageStore.AiTriageTask secondLease = store.leaseNextAiTriageTasks(1, 1_200L, 100L, "owner-b").get(0);
        assertTrue(store.failAiTriageTask(secondLease.getTaskId(), secondLease.getLeaseOwner(), secondLease.getLeaseToken(), "CURRENT", "current", 3_000L));

        assertAll(
                () -> assertEquals("FAILED", singleString(context.databasePath(), AI_TASK_TABLE, "status", "task_id", "task-stale")),
                () -> assertEquals("CURRENT", singleString(context.databasePath(), AI_TASK_TABLE, "last_error_code", "task_id", "task-stale")),
                () -> assertEquals("", singleString(context.databasePath(), AI_TASK_TABLE, "lease_owner", "task_id", "task-stale")),
                () -> assertEquals("", singleString(context.databasePath(), AI_TASK_TABLE, "lease_token", "task_id", "task-stale"))
        );
    }

    @Test
    void atomicResultCompletionRequiresMatchingLeaseOwnerAndToken() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("guarded-result-complete"));
        SqliteMessageStore store = context.store();
        saveMainMessage(store, "message-guarded", "hash-guarded");
        assertTrue(enqueue(store, "task-guarded", "message-guarded", "hash-guarded", "analysis-guarded", "signature-guarded", 5, 3, 0));
        SqliteMessageStore.AiTriageTask lease = store.leaseNextAiTriageTasks(1, 4_000L, 1_000L, "owner-current").get(0);

        assertFalse(store.completeAiTriageTaskWithResult(
                lease.getTaskId(),
                "owner-other",
                lease.getLeaseToken(),
                resultWrite("message-guarded", "hash-guarded", "analysis-guarded", "wrong-owner")
        ));
        assertFalse(store.completeAiTriageTaskWithResult(
                lease.getTaskId(),
                lease.getLeaseOwner(),
                "wrong-token",
                resultWrite("message-guarded", "hash-guarded", "analysis-guarded", "wrong-token")
        ));

        assertAll(
                () -> assertEquals("LEASED", singleString(context.databasePath(), AI_TASK_TABLE, "status", "task_id", "task-guarded")),
                () -> assertNull(store.loadAiTriageResultJson("message-guarded"))
        );

        assertTrue(store.completeAiTriageTaskWithResult(
                lease.getTaskId(),
                lease.getLeaseOwner(),
                lease.getLeaseToken(),
                resultWrite("message-guarded", "hash-guarded", "analysis-guarded", "signature-guarded", "current-token")
        ));

        assertAll(
                () -> assertEquals("DONE", singleString(context.databasePath(), AI_TASK_TABLE, "status", "task_id", "task-guarded")),
                () -> assertEquals("", singleString(context.databasePath(), AI_TASK_TABLE, "lease_owner", "task_id", "task-guarded")),
                () -> assertEquals("", singleString(context.databasePath(), AI_TASK_TABLE, "lease_token", "task_id", "task-guarded")),
                () -> assertEquals("DONE", singleString(context.databasePath(), AI_RESULT_TABLE, "status", "message_id", "message-guarded")),
                () -> assertEquals("signature-guarded", singleString(context.databasePath(), AI_RESULT_TABLE, "match_signature_hash", "message_id", "message-guarded")),
                () -> assertTrue(store.loadAiTriageResultJson("message-guarded").contains("current-token"))
        );
    }

    @Test
    void completedTargetResultBlocksSameTargetButNotSiblingTarget() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("target-result-dedupe"));
        SqliteMessageStore store = context.store();
        saveMainMessage(store, "message-target-dedupe", "hash-target-dedupe");
        assertTrue(enqueue(store, "task-target-dedupe", "message-target-dedupe", "hash-target-dedupe", "analysis-target-dedupe", "signature-target-a", 5, 3, 0));
        SqliteMessageStore.AiTriageTask lease = store.leaseNextAiTriageTasks(1, 5_000L, 1_000L, "owner-target").get(0);

        assertTrue(store.completeAiTriageTaskWithResult(
                lease.getTaskId(),
                lease.getLeaseOwner(),
                lease.getLeaseToken(),
                resultWrite("message-target-dedupe", "hash-target-dedupe", "analysis-target-dedupe", "signature-target-a", "target-a")
        ));
        deleteTask(context.databasePath(), "task-target-dedupe");

        assertAll(
                () -> assertTrue(store.hasBlockingAiTriageForTarget("message-target-dedupe", "signature-target-a")),
                () -> assertFalse(store.hasBlockingAiTriageForTarget("message-target-dedupe", "signature-target-b")),
                () -> assertEquals("signature-target-a", singleString(context.databasePath(), AI_RESULT_TABLE, "match_signature_hash", "message_id", "message-target-dedupe"))
        );
    }

    @Test
    void skippedTargetResultDoesNotBlockReanalysisAfterConfigurationChanges() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("skipped-target-retry"));
        SqliteMessageStore store = context.store();
        saveMainMessage(store, "message-skipped", "hash-skipped");

        assertTrue(store.saveAiTriageResult(
                "message-skipped",
                "hash-skipped",
                "analysis-skipped",
                "SKIPPED",
                "unknown",
                "unknown",
                0.0,
                "AI triage skipped: skipped_no_whitelisted_match (no whitelisted matches)",
                "{}",
                50_000L,
                AiTriageSchema.SCHEMA_VERSION,
                AiTriageSchema.PROMPT_VERSION,
                "model-a",
                "config-a"
        ));
        setResultSignature(context.databasePath(), "message-skipped", "analysis-skipped", "signature-skipped");

        assertFalse(store.hasBlockingAiTriageForTarget("message-skipped", "signature-skipped"));
    }

    @Test
    void queueFullSkipsWithoutThrowingOrGrowingTaskTable() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("queue-full"));
        SqliteMessageStore store = context.store();
        AiTriageEnqueueService enqueueService = new AiTriageEnqueueService(store);
        AiConfig config = enabledAiConfig(1);
        saveMainMessage(store, "message-1", "hash-1");
        saveMainMessage(store, "message-2", "hash-2");

        AiTriageEnqueueService.EnqueueResult first = enqueueService.enqueueAfterRegexPersistence(
                "message-1",
                "hash-1",
                minimalRequestResponse("keep.example.test"),
                Map.of("MainRule", List.of("value-1")),
                config
        );
        AiTriageEnqueueService.EnqueueResult second = assertDoesNotThrow(() -> enqueueService.enqueueAfterRegexPersistence(
                "message-2",
                "hash-2",
                minimalRequestResponse("keep.example.test"),
                Map.of("MainRule", List.of("value-2")),
                config
        ));

        assertAll(
                () -> assertTrue(first.isEnqueued()),
                () -> assertEquals("skipped_queue_full", second.getStatus()),
                () -> assertEquals("AI queue full", second.getReason()),
                () -> assertEquals(1, store.countActiveAiTriageTasks()),
                () -> assertEquals(1, tableCount(context.databasePath(), AI_TASK_TABLE))
        );
    }

    @Test
    void resultSummariesAvoidFullJsonAndExplicitLookupLoadsLatestJson() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("ai-results"));
        SqliteMessageStore store = context.store();
        saveMainMessage(store, "message-1", "hash-1");
        saveMainMessage(store, "message-2", "hash-2");
        String fullJson = "{\"overall_verdict\":\"possible_sensitive\",\"details\":\"FULL_RESULT_JSON_ONLY_EXPLICIT\"}";
        String replacementJson = "{\"overall_verdict\":\"false_positive\"}";

        assertTrue(store.saveAiTriageResult(
                "message-1",
                "hash-1",
                "analysis-default",
                "DONE",
                AiTriageVerdict.POSSIBLE_SENSITIVE.getWireValue(),
                AiTriageRiskLevel.MEDIUM.getWireValue(),
                0.82,
                "One possible sensitive exposure.",
                fullJson,
                30_000L,
                AiTriageSchema.SCHEMA_VERSION,
                AiTriageSchema.PROMPT_VERSION,
                "model-a",
                "config-a"
        ));
        assertTrue(store.saveAiTriageResult(
                "message-2",
                "hash-2",
                "analysis-default",
                "DONE",
                AiTriageVerdict.FALSE_POSITIVE.getWireValue(),
                AiTriageRiskLevel.INFO.getWireValue(),
                0.91,
                "Likely false positive.",
                replacementJson,
                31_000L,
                AiTriageSchema.SCHEMA_VERSION,
                AiTriageSchema.PROMPT_VERSION,
                "model-a",
                "config-a"
        ));

        List<SqliteMessageStore.AiTriageResultSummary> summaries = store.loadAiTriageResultSummaries(List.of("message-1", "message-2", "message-1"));
        SqliteMessageStore.AiTriageResultSummary first = summaries.stream()
                .filter(summary -> summary.getMessageId().equals("message-1"))
                .findFirst()
                .orElseThrow();

        assertAll(
                () -> assertEquals(2, summaries.size()),
                () -> assertEquals("analysis-default", first.getAnalysisKey()),
                () -> assertEquals(AiTriageVerdict.POSSIBLE_SENSITIVE.getWireValue(), first.getOverallVerdict()),
                () -> assertEquals(AiTriageRiskLevel.MEDIUM.getWireValue(), first.getOverallRiskLevel()),
                () -> assertEquals(0.82, first.getConfidence(), 0.0001),
                () -> assertEquals("One possible sensitive exposure.", first.getSummary()),
                () -> assertFalse(first.getSummary().contains("FULL_RESULT_JSON_ONLY_EXPLICIT")),
                () -> assertFalse(summaryTypeExposesFullJson()),
                () -> assertEquals(fullJson, store.loadAiTriageResultJson("message-1")),
                () -> assertNull(store.loadAiTriageResultJson("missing-message"))
        );
    }

    @Test
    void resultSummaryClassifiesLegacyEmptyDoneWithoutLoadingFullJson() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("summary-empty-no-json"));
        SqliteMessageStore store = context.store();
        saveMainMessage(store, "message-empty-summary", "hash-empty-summary");

        insertLegacyEmptyAiResult(context.databasePath(),
                "message-empty-summary",
                "hash-empty-summary",
                "analysis-empty-summary",
                "signature-empty-summary");

        SqliteMessageStore.AiTriageResultSummary summary = store.loadAiTriageResultSummaries(List.of("message-empty-summary")).get(0);

        assertAll(
                () -> assertTrue(summary.isEmptyAdvisoryResult()),
                () -> assertTrue(summary.needsRetry()),
                () -> assertFalse(summaryTypeExposesFullJson())
        );
    }

    @Test
    void targetSpecificResultLookupDoesNotMixSiblingTargets() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("target-specific-result-lookup"));
        SqliteMessageStore store = context.store();
        saveMainMessage(store, "message-target-lookup", "hash-target-lookup");
        String signatureA = "signature-a";
        String signatureB = "signature-b";

        assertTrue(store.saveAiTriageResult(
                "message-target-lookup",
                "hash-target-lookup",
                "analysis-target-a",
                "DONE",
                AiTriageVerdict.NOT_SENSITIVE.getWireValue(),
                AiTriageRiskLevel.INFO.getWireValue(),
                0.91,
                "Target A summary.",
                "{\"target\":\"a\"}",
                80_000L,
                AiTriageSchema.SCHEMA_VERSION,
                AiTriageSchema.PROMPT_VERSION,
                "model-a",
                "config-a"
        ));
        setResultSignature(context.databasePath(), "message-target-lookup", "analysis-target-a", signatureA);
        assertTrue(store.saveAiTriageResult(
                "message-target-lookup",
                "hash-target-lookup",
                "analysis-target-b",
                "DONE",
                AiTriageVerdict.FALSE_POSITIVE.getWireValue(),
                AiTriageRiskLevel.INFO.getWireValue(),
                0.92,
                "Target B summary.",
                "{\"target\":\"b\"}",
                81_000L,
                AiTriageSchema.SCHEMA_VERSION,
                AiTriageSchema.PROMPT_VERSION,
                "model-a",
                "config-a"
        ));
        setResultSignature(context.databasePath(), "message-target-lookup", "analysis-target-b", signatureB);

        List<SqliteMessageStore.AiTriageResultSummary> targetASummaries = store.loadAiTriageResultSummaries(List.of("message-target-lookup"), signatureA);
        List<SqliteMessageStore.AiTriageResultSummary> targetBSummaries = store.loadAiTriageResultSummaries(List.of("message-target-lookup"), signatureB);

        assertAll(
                () -> assertEquals(1, targetASummaries.size()),
                () -> assertEquals("analysis-target-a", targetASummaries.get(0).getAnalysisKey()),
                () -> assertEquals(1, targetBSummaries.size()),
                () -> assertEquals("analysis-target-b", targetBSummaries.get(0).getAnalysisKey()),
                () -> assertEquals("{\"target\":\"a\"}", store.loadAiTriageResultJson("message-target-lookup", signatureA)),
                () -> assertEquals("{\"target\":\"b\"}", store.loadAiTriageResultJson("message-target-lookup", signatureB)),
                () -> assertNull(store.loadAiTriageResultJson("message-target-lookup", "missing-signature"))
        );
    }

    @Test
    void repositoryRejectsNewEmptyDoneAiResults() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("reject-empty-ai-result"));
        SqliteMessageStore store = context.store();
        saveMainMessage(store, "message-empty", "hash-empty");

        boolean saved = store.saveAiTriageResult(
                "message-empty",
                "hash-empty",
                "analysis-empty",
                "DONE",
                "unknown",
                "unknown",
                0.0,
                "",
                "{\"overall_verdict\":\"unknown\",\"overall_severity\":\"unknown\",\"confidence\":0.0,\"summary\":\"\",\"items\":[]}",
                30_000L,
                AiTriageSchema.SCHEMA_VERSION,
                AiTriageSchema.PROMPT_VERSION,
                "model-a",
                "config-empty"
        );

        assertAll(
                () -> assertFalse(saved),
                () -> assertEquals(0, tableCount(context.databasePath(), AI_RESULT_TABLE)),
                () -> assertFalse(store.hasBlockingAiTriageForMessage("message-empty"))
        );
    }

    @Test
    void repositoryRejectsLowQualityNonEmptyDoneAiResults() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("reject-low-quality-ai-result"));
        SqliteMessageStore store = context.store();
        saveMainMessage(store, "message-low-quality", "hash-low-quality");

        boolean saved = store.saveAiTriageResult(
                "message-low-quality",
                "hash-low-quality",
                "analysis-low-quality",
                "DONE",
                "false_positive",
                "unknown",
                0.0,
                "",
                lowQualityFalsePositiveJson(),
                31_000L,
                AiTriageSchema.SCHEMA_VERSION,
                AiTriageSchema.PROMPT_VERSION,
                "model-a",
                "config-low-quality"
        );

        assertAll(
                () -> assertFalse(saved),
                () -> assertEquals(0, tableCount(context.databasePath(), AI_RESULT_TABLE)),
                () -> assertFalse(store.hasBlockingAiTriageForMessage("message-low-quality"))
        );
    }

    @Test
    void repositoryRejectsMissingOverallEvenWhenItemIsConfident() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("reject-missing-overall-strong-item"));
        SqliteMessageStore store = context.store();
        saveMainMessage(store, "message-strong-item", "hash-strong-item");

        boolean saved = store.saveAiTriageResult(
                "message-strong-item",
                "hash-strong-item",
                "analysis-strong-item",
                "DONE",
                "unknown",
                "unknown",
                0.0,
                "",
                missingOverallStrongItemJson(),
                32_000L,
                AiTriageSchema.SCHEMA_VERSION,
                AiTriageSchema.PROMPT_VERSION,
                "model-a",
                "config-strong-item"
        );

        assertAll(
                () -> assertFalse(saved),
                () -> assertEquals(0, tableCount(context.databasePath(), AI_RESULT_TABLE)),
                () -> assertFalse(store.hasBlockingAiTriageForMessage("message-strong-item"))
        );
    }

    @Test
    void doneTaskRowsDoNotBlockRetryableLegacyResults() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("done-task-retryable-result"));
        SqliteMessageStore store = context.store();
        saveMainMessage(store, "message-empty-done-task", "hash-empty-done-task");
        saveMainMessage(store, "message-low-quality-done-task", "hash-low-quality-done-task");
        assertTrue(enqueue(store, "task-empty-done", "message-empty-done-task", "hash-empty-done-task", "analysis-empty-done", "signature-empty-done", 5, 3, 0));
        assertTrue(enqueue(store, "task-low-quality-done", "message-low-quality-done-task", "hash-low-quality-done-task", "analysis-low-quality-done", "signature-low-quality-done", 5, 3, 0));
        updateTaskStatus(context.databasePath(), "task-empty-done", "DONE");
        updateTaskStatus(context.databasePath(), "task-low-quality-done", "DONE");
        insertLegacyEmptyAiResult(context.databasePath(), "message-empty-done-task", "hash-empty-done-task", "analysis-empty-done", "signature-empty-done");
        insertLegacyLowQualityAiResult(context.databasePath(), "message-low-quality-done-task", "hash-low-quality-done-task", "analysis-low-quality-done", "signature-low-quality-done");

        assertAll(
                () -> assertFalse(store.hasBlockingAiTriageForMessage("message-empty-done-task")),
                () -> assertFalse(store.hasBlockingAiTriageForTarget("message-empty-done-task", "signature-empty-done")),
                () -> assertFalse(store.hasBlockingAiTriageForMessage("message-low-quality-done-task")),
                () -> assertFalse(store.hasBlockingAiTriageForTarget("message-low-quality-done-task", "signature-low-quality-done"))
        );
    }

    @Test
    void doesNotStoreFullPrompt() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("no-full-prompt"));
        SqliteMessageStore store = context.store();
        String fullPrompt = "FULL_PROMPT_FIXTURE_DO_NOT_STORE_" + "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        String rawRequestFixture = "POST /login HTTP/1.1\\r\\nAuthorization: Bearer prompt-token-fixture";
        String rawResponseFixture = "HTTP/1.1 200 OK\\r\\nSet-Cookie: prompt-cookie-fixture";
        saveMainMessage(store, "message-1", "hash-1");

        assertTrue(enqueue(store, "task-1", "message-1", "hash-1", "analysis-default", "prompt-signature-hash", 5, 3, 0));
        assertTrue(store.saveAiTriageResult(
                "message-1",
                "hash-1",
                "analysis-default",
                "DONE",
                AiTriageVerdict.POSSIBLE_SENSITIVE.getWireValue(),
                AiTriageRiskLevel.HIGH.getWireValue(),
                0.7,
                "Stored summary only.",
                "{\"overall_verdict\":\"possible_sensitive\",\"evidence_count\":1}",
                60_000L,
                AiTriageSchema.SCHEMA_VERSION,
                AiTriageSchema.PROMPT_VERSION,
                "model-a",
                "config-hash-only"
        ));

        String aiStorageText = storedAiTableText(context.databasePath());

        assertAll(
                () -> assertTrue(aiStorageText.contains("prompt-signature-hash")),
                () -> assertTrue(aiStorageText.contains("config-hash-only")),
                () -> assertTrue(aiStorageText.contains("evidence_count")),
                () -> assertFalse(aiStorageText.contains(fullPrompt)),
                () -> assertFalse(aiStorageText.contains(rawRequestFixture)),
                () -> assertFalse(aiStorageText.contains(rawResponseFixture)),
                () -> assertFalse(aiStorageText.contains("prompt-token-fixture")),
                () -> assertFalse(aiStorageText.contains("prompt-cookie-fixture"))
        );
    }

    @Test
    void deleteByHostRemovesAiResultsForDeletedMessages() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("orphan-results"));
        SqliteMessageStore store = context.store();
        saveMainMessage(store, "message-keep", "hash-keep");
        saveMainMessage(store, "message-delete", "hash-delete");
        saveResult(store, "message-keep", "hash-keep");
        saveResult(store, "message-delete", "hash-delete");

        store.deleteByHostPattern("delete.example.test");

        assertAll(
                () -> assertEquals(0, store.cleanupOrphanAiTriageResults()),
                () -> assertNotNull(store.loadAiTriageResultJson("message-keep")),
                () -> assertNull(store.loadAiTriageResultJson("message-delete")),
                () -> assertEquals(1, tableCount(context.databasePath(), AI_RESULT_TABLE))
        );
    }

    @Test
    void deleteAllMessagesRemovesAiTasksAndResults() throws Exception {
        StoreContext context = createStoreContext(tempDirectory.resolve("delete-all-ai-rows"));
        SqliteMessageStore store = context.store();
        saveMainMessage(store, "message-1", "hash-1");
        saveMainMessage(store, "message-2", "hash-2");
        assertTrue(enqueue(store, "task-1", "message-1", "hash-1", "analysis-1", "signature-1", 5, 3, 0));
        assertTrue(enqueue(store, "task-2", "message-2", "hash-2", "analysis-2", "signature-2", 5, 3, 0));
        saveResult(store, "message-1", "hash-1");
        saveResult(store, "message-2", "hash-2");

        int deleted = store.deleteAllMessages();

        assertAll(
                () -> assertEquals(2, deleted),
                () -> assertEquals(0, tableCount(context.databasePath(), MESSAGE_TABLE)),
                () -> assertEquals(0, tableCount(context.databasePath(), MATCH_TABLE)),
                () -> assertEquals(0, tableCount(context.databasePath(), AI_TASK_TABLE)),
                () -> assertEquals(0, tableCount(context.databasePath(), AI_RESULT_TABLE)),
                () -> assertEquals(0, store.countActiveAiTriageTasks())
        );
    }

    private static boolean enqueue(SqliteMessageStore store,
                                   String taskId,
                                   String messageId,
                                   String contentHash,
                                   String analysisKey,
                                   String matchSignatureHash,
                                   int priority,
                                   int maxAttempts,
                                   long nextAttemptAt) {
        return store.enqueueAiTriageTask(
                taskId,
                messageId,
                contentHash,
                analysisKey,
                matchSignatureHash,
                AiTriageSchema.SCHEMA_VERSION,
                AiTriageSchema.PROMPT_VERSION,
                "model-a",
                "config-a",
                priority,
                maxAttempts,
                nextAttemptAt
        );
    }

    private static void saveResult(SqliteMessageStore store, String messageId, String contentHash) {
        assertTrue(store.saveAiTriageResult(
                messageId,
                contentHash,
                "analysis-default",
                "DONE",
                AiTriageVerdict.NOT_SENSITIVE.getWireValue(),
                AiTriageRiskLevel.INFO.getWireValue(),
                0.99,
                "No sensitive exposure.",
                "{\"overall_verdict\":\"not_sensitive\"}",
                40_000L,
                AiTriageSchema.SCHEMA_VERSION,
                AiTriageSchema.PROMPT_VERSION,
                "model-a",
                "config-a"
        ));
    }

    private static AiTriageResultWrite resultWrite(String messageId, String contentHash, String analysisKey, String marker) {
        return resultWrite(messageId, contentHash, analysisKey, "", marker);
    }

    private static AiTriageResultWrite resultWrite(String messageId,
                                                   String contentHash,
                                                   String analysisKey,
                                                   String matchSignatureHash,
                                                   String marker) {
        return new AiTriageResultWrite(
                messageId,
                contentHash,
                analysisKey,
                matchSignatureHash,
                "DONE",
                AiTriageVerdict.NOT_SENSITIVE.getWireValue(),
                AiTriageRiskLevel.INFO.getWireValue(),
                0.99,
                "Guarded result complete.",
                "{\"marker\":\"" + marker + "\"}",
                70_000L,
                AiTriageSchema.SCHEMA_VERSION,
                AiTriageSchema.PROMPT_VERSION,
                "model-a",
                "config-a"
        );
    }

    private static AiConfig enabledAiConfig(int maxQueueSize) {
        return new AiConfig(
                true,
                false,
                "openai-compatible",
                "https://ai.example.test/v1",
                "model-a",
                "env:HAE_AI_API_KEY",
                180,
                2,
                8,
                2000000,
                800000,
                200000,
                600000,
                50,
                true,
                true,
                true,
                true,
                true,
                maxQueueSize,
                false,
                List.of(new AiWhitelistRule("敏感信息", List.of("MainRule")))
        );
    }

    private static void saveMainMessage(SqliteMessageStore store, String messageId, String contentHash) {
        String host = switch (messageId) {
            case "message-delete" -> "delete.example.test";
            case "message-orphan" -> "orphan.example.test";
            default -> "keep.example.test";
        };
        store.saveMessage(
                messageId,
                minimalRequestResponse(host),
                "https://" + host + "/" + messageId,
                "GET",
                "200",
                "42",
                "main comment " + messageId,
                "green",
                contentHash,
                Map.of("MainRule", List.of("value-" + messageId))
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

    private static HttpRequestResponse minimalRequestResponse(String host) {
        HttpService service = httpServiceProxy(host);
        HttpRequest request = httpRequestProxy(service, TestFixtures.minimalHttpRequestBytes());
        HttpResponse response = httpResponseProxy(TestFixtures.minimalHttpResponseBytes());
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
                insertMessage.setString(8, "existing comment");
                insertMessage.setString(9, "green");
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

    private static void assertSqliteObjectExists(Connection connection, String type, String name) throws SQLException {
        assertTrue(sqliteObjectExists(connection, type, name), () -> "Missing SQLite " + type + ": " + name);
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

    private static void assertColumnExists(Connection connection, String tableName, String columnName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("PRAGMA table_info(" + tableName + ")")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    if (columnName.equals(resultSet.getString("name"))) {
                        return;
                    }
                }
            }
        }
        throw new AssertionError("Missing SQLite column: " + tableName + "." + columnName);
    }

    private static long tableCount(Path databasePath, String tableName) throws SQLException {
        try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(databasePath))) {
            return TestFixtures.countRows(connection, tableName);
        }
    }

    private static void updateTaskStatus(Path databasePath, String taskId, String status) throws SQLException {
        try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(databasePath));
             PreparedStatement statement = connection.prepareStatement("UPDATE " + AI_TASK_TABLE + " SET status = ? WHERE task_id = ?")) {
            statement.setString(1, status);
            statement.setString(2, taskId);
            statement.executeUpdate();
        }
    }

    private static void deleteTask(Path databasePath, String taskId) throws SQLException {
        try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(databasePath));
             PreparedStatement statement = connection.prepareStatement("DELETE FROM " + AI_TASK_TABLE + " WHERE task_id = ?")) {
            statement.setString(1, taskId);
            statement.executeUpdate();
        }
    }

    private static void setResultSignature(Path databasePath,
                                           String messageId,
                                           String analysisKey,
                                           String matchSignatureHash) throws SQLException {
        try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(databasePath));
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE " + AI_RESULT_TABLE + " SET match_signature_hash = ? WHERE message_id = ? AND analysis_key = ?")) {
            statement.setString(1, matchSignatureHash);
            statement.setString(2, messageId);
            statement.setString(3, analysisKey);
            statement.executeUpdate();
        }
    }

    private static void insertLegacyEmptyAiResult(Path databasePath,
                                                  String messageId,
                                                  String contentHash,
                                                  String analysisKey,
                                                  String matchSignatureHash) throws SQLException {
        insertRawAiResult(
                databasePath,
                messageId,
                contentHash,
                analysisKey,
                matchSignatureHash,
                "unknown",
                "unknown",
                0.0,
                "",
                "{\"overall_verdict\":\"unknown\",\"overall_severity\":\"unknown\",\"confidence\":0.0,\"summary\":\"\",\"items\":[]}",
                80_000L
        );
    }

    private static void insertLegacyLowQualityAiResult(Path databasePath,
                                                       String messageId,
                                                       String contentHash,
                                                       String analysisKey,
                                                       String matchSignatureHash) throws SQLException {
        insertRawAiResult(
                databasePath,
                messageId,
                contentHash,
                analysisKey,
                matchSignatureHash,
                "false_positive",
                "unknown",
                0.0,
                "",
                lowQualityFalsePositiveJson(),
                81_000L
        );
    }

    private static void insertRawAiResult(Path databasePath,
                                          String messageId,
                                          String contentHash,
                                          String analysisKey,
                                          String matchSignatureHash,
                                          String overallVerdict,
                                          String overallRiskLevel,
                                          double confidence,
                                          String summary,
                                          String resultJson,
                                          long analyzedAt) throws SQLException {
        try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(databasePath));
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO ai_triage_result (
                         message_id, content_hash, analysis_key, match_signature_hash, status, overall_verdict,
                         overall_severity, confidence, summary, result_json, analyzed_at,
                         schema_version, prompt_version, model, config_hash
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, messageId);
            statement.setString(2, contentHash);
            statement.setString(3, analysisKey);
            statement.setString(4, matchSignatureHash);
            statement.setString(5, "DONE");
            statement.setString(6, overallVerdict);
            statement.setString(7, overallRiskLevel);
            statement.setDouble(8, confidence);
            statement.setString(9, summary);
            statement.setString(10, resultJson);
            statement.setLong(11, analyzedAt);
            statement.setString(12, AiTriageSchema.SCHEMA_VERSION);
            statement.setString(13, AiTriageSchema.PROMPT_VERSION);
            statement.setString(14, "model-a");
            statement.setString(15, "config-legacy");
            statement.executeUpdate();
        }
    }

    private static long singleLong(Path databasePath,
                                   String tableName,
                                   String columnName,
                                   String idColumnName,
                                   String idValue) throws SQLException {
        try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(databasePath));
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT " + columnName + " FROM " + tableName + " WHERE " + idColumnName + " = ?")) {
            statement.setString(1, idValue);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : Long.MIN_VALUE;
            }
        }
    }

    private static String storedAiTableText(Path databasePath) throws SQLException {
        return selectJoinedText(databasePath, """
                SELECT task_id, message_id, content_hash, analysis_key, match_signature_hash,
                       schema_version, prompt_version, model, config_hash, status,
                       last_error_code, last_error_message_sanitized
                FROM ai_triage_task
                """) + selectJoinedText(databasePath, """
                SELECT message_id, content_hash, analysis_key, status, overall_verdict,
                       overall_severity, summary, result_json, schema_version,
                       prompt_version, model, config_hash
                FROM ai_triage_result
                """);
    }

    private static String selectJoinedText(Path databasePath, String sql) throws SQLException {
        StringBuilder builder = new StringBuilder();
        try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(databasePath));
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            int columnCount = resultSet.getMetaData().getColumnCount();
            while (resultSet.next()) {
                for (int column = 1; column <= columnCount; column++) {
                    builder.append(resultSet.getString(column)).append('\n');
                }
            }
        }
        return builder.toString();
    }

    private static boolean summaryTypeExposesFullJson() {
        return Arrays.stream(SqliteMessageStore.AiTriageResultSummary.class.getDeclaredFields())
                .anyMatch(field -> field.getName().equals("resultJson"))
                || Arrays.stream(SqliteMessageStore.AiTriageResultSummary.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("getResultJson"));
    }

    private static String singleString(Path databasePath,
                                       String tableName,
                                       String columnName,
                                       String idColumnName,
                                       String idValue) throws SQLException {
        try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(databasePath));
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT " + columnName + " FROM " + tableName + " WHERE " + idColumnName + " = ?")) {
            statement.setString(1, idValue);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private static String singleString(Connection connection, String sql, String parameter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
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

    private static class DefaultProxyHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return defaultProxyValue(proxy, method, args);
        }
    }
}
