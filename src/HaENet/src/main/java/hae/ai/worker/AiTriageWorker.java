package hae.ai.worker;

import hae.ai.AiConfig;
import hae.ai.AiTriageResponse;
import hae.ai.AiTriageSchema;
import hae.ai.AiTriageTargetSignature;
import hae.ai.AiTriageVerdictItem;
import hae.ai.client.AiClient;
import hae.ai.client.AiClientException;
import hae.ai.client.AiClientFailureCategory;
import hae.ai.client.AiClientResult;
import hae.ai.parser.AiTextSanitizer;
import hae.ai.parser.AiVerdictParseResult;
import hae.ai.parser.AiVerdictParser;
import hae.ai.prompt.AiPromptBuildResult;
import hae.ai.prompt.AiPromptBuilder;
import hae.repository.AiResultRepository;
import hae.repository.AiTaskRepository.AiTriageResultWrite;
import hae.repository.AiTaskRepository;
import hae.storage.SqliteMessageStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class AiTriageWorker implements AutoCloseable {
    public static final String RESULT_STATUS_DONE = "DONE";
    public static final String RESULT_STATUS_SKIPPED = "SKIPPED";
    public static final String ERROR_PROMPT_SKIPPED = "PROMPT_SKIPPED";
    public static final String ERROR_PARSE_FAILED = "PARSE_FAILED";
    public static final String ERROR_MESSAGE_UNAVAILABLE = "MESSAGE_UNAVAILABLE";
    private static final long RESERVATION_CONTENTION = -1L;
    private static final long RESERVATION_TOO_LARGE = -2L;

    private final AiTaskRepository taskRepository;
    private final AiTriageMessageContextLoader messageContextLoader;
    private final AiPromptBuilder promptBuilder;
    private final AiVerdictParser verdictParser;
    private final AiClient aiClient;
    private final AiConfig aiConfig;
    private final AiTriageWorkerConfig workerConfig;
    private final AiTriageWorkerClock clock;
    private final AiTriageWorkerSleeper sleeper;
    private final AiTriageWorkerLogger logger;
    private final Random jitterRandom;
    private final String leaseOwner;
    private final ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicReference<AiTriageWorkerStatus> status = new AtomicReference<>(AiTriageWorkerStatus.CREATED);
    private final AtomicInteger workerIndex = new AtomicInteger(1);
    private final AtomicInteger activeWorkerCount = new AtomicInteger(0);
    private final AtomicLong inFlightChars = new AtomicLong(0L);
    private final Map<String, SqliteMessageStore.AiTriageTask> activeTasks = new ConcurrentHashMap<>();
    private volatile long lastRecoveryAt;

    public AiTriageWorker(AiTaskRepository taskRepository,
                          AiResultRepository resultRepository,
                          AiTriageMessageContextLoader messageContextLoader,
                          AiPromptBuilder promptBuilder,
                          AiVerdictParser verdictParser,
                          AiClient aiClient,
                          AiConfig aiConfig,
                          AiTriageWorkerConfig workerConfig) {
        this(taskRepository,
                resultRepository,
                messageContextLoader,
                promptBuilder,
                verdictParser,
                aiClient,
                aiConfig,
                workerConfig,
                System::currentTimeMillis,
                Thread::sleep,
                ignored -> {
                },
                new Random());
    }

    public AiTriageWorker(AiTaskRepository taskRepository,
                          AiResultRepository resultRepository,
                          AiTriageMessageContextLoader messageContextLoader,
                          AiPromptBuilder promptBuilder,
                          AiVerdictParser verdictParser,
                          AiClient aiClient,
                          AiConfig aiConfig,
                          AiTriageWorkerConfig workerConfig,
                          AiTriageWorkerClock clock,
                          AiTriageWorkerSleeper sleeper,
                          AiTriageWorkerLogger logger,
        Random jitterRandom) {
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
        Objects.requireNonNull(resultRepository, "resultRepository");
        this.messageContextLoader = Objects.requireNonNull(messageContextLoader, "messageContextLoader");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        this.verdictParser = Objects.requireNonNull(verdictParser, "verdictParser");
        this.aiClient = Objects.requireNonNull(aiClient, "aiClient");
        this.aiConfig = Objects.requireNonNull(aiConfig, "aiConfig");
        this.workerConfig = workerConfig == null ? AiTriageWorkerConfig.defaults() : workerConfig;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.logger = logger == null ? ignored -> {
        } : logger;
        this.jitterRandom = jitterRandom == null ? new Random() : jitterRandom;
        this.leaseOwner = "worker-" + UUID.randomUUID();
        this.executorService = Executors.newFixedThreadPool(this.workerConfig.getConcurrency(), workerThreadFactory());
        if (this.workerConfig.isAutoStart()) {
            start();
        }
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        paused.set(false);
        status.set(AiTriageWorkerStatus.RUNNING);
        for (int i = 0; i < workerConfig.getConcurrency(); i++) {
            executorService.execute(this::runLoop);
        }
    }

    public void pause() {
        if (status.get() == AiTriageWorkerStatus.SHUTDOWN) {
            return;
        }
        paused.set(true);
        status.set(AiTriageWorkerStatus.PAUSED);
    }

    public void resume() {
        if (status.get() == AiTriageWorkerStatus.SHUTDOWN) {
            return;
        }
        paused.set(false);
        if (running.get()) {
            status.set(AiTriageWorkerStatus.RUNNING);
        } else {
            status.set(AiTriageWorkerStatus.CREATED);
        }
    }

    public void shutdown() {
        if (status.getAndSet(AiTriageWorkerStatus.SHUTDOWN) == AiTriageWorkerStatus.SHUTDOWN) {
            return;
        }
        running.set(false);
        paused.set(false);
        releaseActiveLeases(clock.nowMillis());
        executorService.shutdownNow();
        recoverStaleLeases();
    }

    public void unload() {
        shutdown();
    }

    @Override
    public void close() {
        shutdown();
    }

    public AiTriageWorkerStatus getStatus() {
        return status.get();
    }

    public int getConfiguredConcurrency() {
        return workerConfig.getConcurrency();
    }

    public int getActiveWorkerCount() {
        return activeWorkerCount.get();
    }

    public long getInFlightChars() {
        return inFlightChars.get();
    }

    public boolean isShutdown() {
        return executorService.isShutdown();
    }

    public boolean processOneForTest() {
        return processOneClaimCycle();
    }

    public void recoverStaleLeases() {
        try {
            taskRepository.recoverStaleAiTriageTasks(clock.nowMillis());
        } catch (Exception e) {
            logger.log("recoverStaleLeases: " + safeErrorMessage(e));
        }
    }

    private void runLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                if (paused.get()) {
                    sleep(workerConfig.getIdlePollMillis());
                    continue;
                }
                maybeRecoverStaleLeases();
                if (!processOneClaimCycle()) {
                    sleep(workerConfig.getIdlePollMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                logger.log("runLoop: " + safeErrorMessage(e));
            }
        }
    }

    private boolean processOneClaimCycle() {
        if (paused.get() || status.get() == AiTriageWorkerStatus.SHUTDOWN) {
            return false;
        }
        long availableChars = workerConfig.getMaxInFlightChars() - inFlightChars.get();
        if (availableChars <= 0L) {
            return false;
        }

        List<SqliteMessageStore.AiTriageTask> leasedTasks = taskRepository.leaseNextAiTriageTasks(
                1,
                clock.nowMillis(),
                workerConfig.getLeaseDurationMillis(),
                leaseOwner
        );
        if (leasedTasks.isEmpty()) {
            return false;
        }

        SqliteMessageStore.AiTriageTask task = leasedTasks.get(0);
        activeTasks.put(task.getTaskId(), task);
        activeWorkerCount.incrementAndGet();
        long reservedChars = 0L;
        try {
            if (isShuttingDown()) {
                releaseForShutdown(task);
                return false;
            }
            AiTriageMessageContext context = messageContextLoader.load(task.getMessageId());
            if (context == null || context.getRequestResponse() == null) {
                failPermanently(task, ERROR_MESSAGE_UNAVAILABLE, "Stored message is unavailable");
                return true;
            }

            AiPromptBuildResult promptResult = promptBuilder.build(
                    task.getMessageId(),
                    context.getRequestResponse(),
                    extractedDataForTask(task, context.getExtractedDataByRule()),
                    aiConfig
            );
            if (isShuttingDown()) {
                releaseForShutdown(task);
                return false;
            }
            reservedChars = reservePromptChars(task, promptResult);
            if (reservedChars == RESERVATION_TOO_LARGE) {
                failPermanently(task, "PROMPT_TOO_LARGE", "AI prompt exceeds max in-flight character budget");
                return true;
            }
            if (reservedChars == RESERVATION_CONTENTION) {
                releaseForBudgetContention(task);
                return false;
            }
            if (promptResult.isSkipped()) {
                saveSkippedResult(task, context, promptResult);
                return true;
            }

            if (isShuttingDown()) {
                releaseForShutdown(task);
                return false;
            }
            AiClientResult clientResult = aiClient.complete(promptResult.getPrompt());
            if (isShuttingDown()) {
                releaseForShutdown(task);
                return false;
            }
            AiVerdictParseResult parseResult = verdictParser.parse(clientResult.getResponseBody());
            if (isShuttingDown()) {
                releaseForShutdown(task);
                return false;
            }
            if (!parseResult.isParsed()) {
                AiVerdictParseResult repairedParseResult = tryRepairParseFailure(promptResult, clientResult.getResponseBody(), parseResult.getErrorMessage());
                if (!repairedParseResult.isParsed()) {
                    handleParseFailure(task, repairedParseResult.getErrorMessage());
                    return true;
                }
                parseResult = repairedParseResult;
            }
            if (!completeParsedResult(task, context, parseResult.getResponse())) {
                failPermanently(task, "RESULT_SAVE_FAILED", "Unable to persist AI triage result");
            }
            return true;
        } catch (AiClientException e) {
            if (isShuttingDown()) {
                releaseForShutdown(task);
                return false;
            }
            handleClientFailure(task, e);
            return true;
        } catch (Exception e) {
            if (isShuttingDown()) {
                releaseForShutdown(task);
                return false;
            }
            failPermanently(task, "WORKER_ERROR", safeErrorMessage(e));
            logger.log("processOneClaimCycle: " + safeErrorMessage(e));
            return true;
        } finally {
            if (reservedChars > 0L) {
                inFlightChars.addAndGet(-reservedChars);
            }
            activeWorkerCount.decrementAndGet();
            activeTasks.remove(task.getTaskId());
        }
    }

    private AiVerdictParseResult tryRepairParseFailure(AiPromptBuildResult promptResult,
                                                       String invalidResponseBody,
                                                       String parseErrorMessage) throws AiClientException {
        String repairPrompt = repairPrompt(promptResult, invalidResponseBody, parseErrorMessage);
        AiClientResult repairResult = aiClient.complete(repairPrompt);
        AiVerdictParseResult repairParseResult = verdictParser.parse(repairResult.getResponseBody());
        if (repairParseResult.isParsed()) {
            return repairParseResult;
        }
        String originalError = parseErrorMessage == null ? "" : parseErrorMessage;
        return AiVerdictParseResult.invalid("repair_failed: " + repairParseResult.getErrorMessage()
                + (originalError.isBlank() ? "" : "; original_error: " + originalError));
    }

    private String repairPrompt(AiPromptBuildResult promptResult, String invalidResponseBody, String parseErrorMessage) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("The previous answer for this AI triage task was invalid. Return ONLY one strict JSON object.\n");
        prompt.append("Missing or invalid information: ").append(firstNonBlank(parseErrorMessage, "unknown parse or quality failure")).append("\n");
        prompt.append("Required top-level fields: ").append(AiTriageSchema.OVERALL_FIELDS).append("\n");
        prompt.append("Required item fields: ").append(AiTriageSchema.ITEM_FIELDS).append("\n");
        prompt.append("Allowed overall_verdict/verdict values: sensitive_exposure, sensitive_but_expected, possible_sensitive, false_positive, not_sensitive, security_signal_not_secret, unknown.\n");
        prompt.append("Allowed overall_severity/severity values: critical, high, medium, low, info, unknown.\n");
        prompt.append("The fields summary, reason, and recommended_actions must be Simplified Chinese when present.\n");
        prompt.append("If your item-level judgment is already clear, synthesize overall_verdict, overall_severity, confidence, and summary from the best item.\n");
        prompt.append("Do not include markdown fences or explanations outside JSON.\n");
        prompt.append("Previous invalid answer:\n").append(truncateForRepair(invalidResponseBody)).append("\n");
        if (promptResult != null && promptResult.getTriageRequest() != null) {
            prompt.append("Original triage request item count: ")
                    .append(promptResult.getTriageRequest().getItems().size())
                    .append(", items_truncated=")
                    .append(promptResult.getTriageRequest().isItemsTruncated())
                    .append(", omitted_item_count=")
                    .append(promptResult.getTriageRequest().getOmittedItemCount())
                    .append("\n");
        }
        return prompt.toString();
    }

    private String truncateForRepair(String value) {
        String sanitized = AiTextSanitizer.redactSecrets(value == null ? "" : value).trim();
        if (sanitized.length() <= 4_000) {
            return sanitized;
        }
        return sanitized.substring(0, 4_000) + "\n[truncated]";
    }

    private long reservePromptChars(SqliteMessageStore.AiTriageTask task, AiPromptBuildResult promptResult) {
        long promptChars = Math.max(1L, promptResult.getPromptCharCount());
        long maxInFlightChars = workerConfig.getMaxInFlightChars();
        if (promptChars > maxInFlightChars) {
            return RESERVATION_TOO_LARGE;
        }

        while (true) {
            long current = inFlightChars.get();
            long updated = current + promptChars;
            if (updated > maxInFlightChars) {
                return RESERVATION_CONTENTION;
            }
            if (inFlightChars.compareAndSet(current, updated)) {
                return promptChars;
            }
        }
    }

    private Map<String, List<String>> extractedDataForTask(SqliteMessageStore.AiTriageTask task,
                                                           Map<String, List<String>> extractedDataByRule) {
        if (task == null || task.getMatchSignatureHash() == null || task.getMatchSignatureHash().isBlank()
                || extractedDataByRule == null || extractedDataByRule.isEmpty()) {
            return extractedDataByRule;
        }
        Map<String, List<String>> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : extractedDataByRule.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            List<String> values = new ArrayList<>();
            for (String value : entry.getValue()) {
                String targetSignature = AiTriageTargetSignature.matchSignatureHash(entry.getKey(), value);
                if (targetSignature.equals(task.getMatchSignatureHash())) {
                    values.add(value.trim());
                }
            }
            if (!values.isEmpty()) {
                filtered.put(entry.getKey(), values);
            }
        }
        return filtered;
    }

    private void releaseForBudgetContention(SqliteMessageStore.AiTriageTask task) {
        long nextAttemptAt = clock.nowMillis() + workerConfig.getIdlePollMillis();
        releaseTask(task, nextAttemptAt);
    }

    private void releaseForShutdown(SqliteMessageStore.AiTriageTask task) {
        releaseTask(task, clock.nowMillis());
    }

    private long retryDelayMillis(int attemptNumber) {
        long base = workerConfig.getRetryBaseDelayMillis();
        long exponential = base;
        int shifts = Math.max(0, attemptNumber - 1);
        for (int i = 0; i < shifts; i++) {
            if (exponential >= workerConfig.getRetryMaxDelayMillis() / 2L) {
                exponential = workerConfig.getRetryMaxDelayMillis();
                break;
            }
            exponential *= 2L;
        }
        long capped = Math.min(exponential, workerConfig.getRetryMaxDelayMillis());
        long jitterBound = Math.max(1L, capped / 4L);
        long jitter = Math.floorMod(jitterRandom.nextLong(), jitterBound + 1L);
        return Math.min(workerConfig.getRetryMaxDelayMillis(), capped + jitter);
    }

    private void handleClientFailure(SqliteMessageStore.AiTriageTask task, AiClientException exception) {
        if (exception.isPermanent()) {
            failPermanently(task, errorCode(exception), exception.getMessage());
            return;
        }

        int maxAttempts = effectiveMaxAttempts(task);
        if (task.getAttemptCount() >= maxAttempts) {
            failPermanently(task, errorCode(exception), exception.getMessage());
            return;
        }
        taskRepository.failAiTriageTask(
                task.getTaskId(),
                leaseOwner,
                task.getLeaseToken(),
                errorCode(exception),
                sanitizeError(exception.getMessage()),
                clock.nowMillis() + retryDelayMillis(task.getAttemptCount())
        );
    }

    private void handleParseFailure(SqliteMessageStore.AiTriageTask task, String errorMessage) {
        int maxAttempts = effectiveMaxAttempts(task);
        if (task.getAttemptCount() >= maxAttempts) {
            failPermanently(task, ERROR_PARSE_FAILED, errorMessage);
            return;
        }
        taskRepository.failAiTriageTask(
                task.getTaskId(),
                leaseOwner,
                task.getLeaseToken(),
                ERROR_PARSE_FAILED,
                sanitizeError(errorMessage),
                clock.nowMillis() + retryDelayMillis(task.getAttemptCount())
        );
    }

    private int effectiveMaxAttempts(SqliteMessageStore.AiTriageTask task) {
        int taskMaxAttempts = task == null ? workerConfig.getDefaultMaxAttempts() : task.getMaxAttempts();
        return Math.max(1, taskMaxAttempts);
    }

    private boolean completeParsedResult(SqliteMessageStore.AiTriageTask task,
                                         AiTriageMessageContext context,
                                         AiTriageResponse response) {
        return taskRepository.completeAiTriageTaskWithResult(
                task.getTaskId(),
                leaseOwner,
                task.getLeaseToken(),
                new AiTriageResultWrite(
                task.getMessageId(),
                firstNonBlank(context.getContentHash(), task.getContentHash()),
                task.getAnalysisKey(),
                firstNonBlank(task.getMatchSignatureHash(), ""),
                RESULT_STATUS_DONE,
                response.getOverallVerdict().getWireValue(),
                response.getOverallRiskLevel().getWireValue(),
                response.getConfidence(),
                response.getSummary(),
                canonicalResultJson(response),
                clock.nowMillis(),
                firstNonBlank(task.getSchemaVersion(), ""),
                firstNonBlank(task.getPromptVersion(), ""),
                firstNonBlank(task.getModel(), aiConfig.getModel()),
                firstNonBlank(task.getConfigHash(), "")
                )
        );
    }

    private void saveSkippedResult(SqliteMessageStore.AiTriageTask task,
                                   AiTriageMessageContext context,
                                   AiPromptBuildResult promptResult) {
        String summary = "AI triage skipped: " + promptResult.getStatus();
        if (!promptResult.getReason().isBlank()) {
            summary = summary + " (" + promptResult.getReason() + ")";
        }
        boolean saved = taskRepository.completeAiTriageTaskWithResult(
                task.getTaskId(),
                leaseOwner,
                task.getLeaseToken(),
                new AiTriageResultWrite(
                task.getMessageId(),
                firstNonBlank(context.getContentHash(), task.getContentHash()),
                task.getAnalysisKey(),
                firstNonBlank(task.getMatchSignatureHash(), ""),
                RESULT_STATUS_SKIPPED,
                "unknown",
                "unknown",
                0.0,
                summary,
                "{}",
                clock.nowMillis(),
                firstNonBlank(task.getSchemaVersion(), ""),
                firstNonBlank(task.getPromptVersion(), ""),
                firstNonBlank(task.getModel(), aiConfig.getModel()),
                firstNonBlank(task.getConfigHash(), "")
                )
        );
        if (saved) {
            return;
        } else {
            failPermanently(task, "RESULT_SAVE_FAILED", "Unable to persist skipped AI triage result");
        }
    }

    private void failPermanently(SqliteMessageStore.AiTriageTask task, String errorCode, String message) {
        taskRepository.failAiTriageTask(task.getTaskId(), leaseOwner, task.getLeaseToken(), errorCode, sanitizeError(message), Long.MAX_VALUE);
    }

    private void releaseActiveLeases(long nextAttemptAt) {
        for (SqliteMessageStore.AiTriageTask activeTask : List.copyOf(activeTasks.values())) {
            try {
                releaseTask(activeTask, nextAttemptAt);
            } catch (Exception e) {
                logger.log("releaseActiveLeases: " + safeErrorMessage(e));
            }
        }
    }

    private boolean releaseTask(SqliteMessageStore.AiTriageTask task, long nextAttemptAt) {
        return taskRepository.releaseAiTriageTask(task.getTaskId(), leaseOwner, task.getLeaseToken(), nextAttemptAt);
    }

    private boolean isShuttingDown() {
        return status.get() == AiTriageWorkerStatus.SHUTDOWN;
    }

    private void maybeRecoverStaleLeases() {
        long now = clock.nowMillis();
        if (now - lastRecoveryAt < workerConfig.getRecoveryIntervalMillis()) {
            return;
        }
        lastRecoveryAt = now;
        recoverStaleLeases();
    }

    private void sleep(long millis) throws InterruptedException {
        sleeper.sleepMillis(Math.max(1L, millis));
    }

    private ThreadFactory workerThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "HaE-AI-Triage-Worker-" + workerIndex.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    private String errorCode(AiClientException exception) {
        AiClientFailureCategory category = exception.getCategory();
        if (category == null) {
            return "AI_CLIENT_ERROR";
        }
        if (category == AiClientFailureCategory.RETRYABLE && exception.getStatusCode() == 429) {
            return "RATE_LIMIT";
        }
        if (category == AiClientFailureCategory.RETRYABLE) {
            return "RETRYABLE_AI_FAILURE";
        }
        return category.name();
    }

    private String safeErrorMessage(Exception exception) {
        return exception == null ? "unknown" : sanitizeError(exception.getMessage());
    }

    private String sanitizeError(String message) {
        String sanitized = AiTextSanitizer.redactSecrets(message == null ? "" : message);
        if (sanitized.length() <= 300) {
            return sanitized;
        }
        return sanitized.substring(0, 300);
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? (second == null ? "" : second) : first;
    }

    private String canonicalResultJson(AiTriageResponse response) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        appendStringField(json, AiTriageSchema.FIELD_OVERALL_VERDICT, response.getOverallVerdict().getWireValue());
        json.append(',');
        appendStringField(json, AiTriageSchema.FIELD_OVERALL_SEVERITY, response.getOverallRiskLevel().getWireValue());
        json.append(',');
        appendNumberField(json, AiTriageSchema.FIELD_CONFIDENCE, response.getConfidence());
        json.append(',');
        appendStringField(json, AiTriageSchema.FIELD_SUMMARY, response.getSummary());
        json.append(',');
        appendBooleanField(json, AiTriageSchema.FIELD_ITEMS_TRUNCATED, response.isItemsTruncated());
        json.append(',');
        appendNumberField(json, AiTriageSchema.FIELD_OMITTED_ITEM_COUNT, response.getOmittedItemCount());
        json.append(',');
        json.append('"').append(AiTriageSchema.FIELD_ITEMS).append("\":[");
        for (int i = 0; i < response.getItems().size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            appendItem(json, response.getItems().get(i));
        }
        json.append(']');
        json.append('}');
        return json.toString();
    }

    private void appendItem(StringBuilder json, AiTriageVerdictItem item) {
        json.append('{');
        appendStringField(json, AiTriageSchema.FIELD_RULE_GROUP, item.getRuleGroup());
        json.append(',');
        appendStringField(json, AiTriageSchema.FIELD_RULE_NAME, item.getRuleName());
        json.append(',');
        appendStringField(json, AiTriageSchema.FIELD_RULE_HASH, item.getRuleHash());
        json.append(',');
        appendStringField(json, AiTriageSchema.FIELD_MATCHED_VALUE_REDACTED, item.getMatchedValueRedacted());
        json.append(',');
        appendStringField(json, AiTriageSchema.FIELD_MATCH_LOCATION, item.getMatchLocation());
        json.append(',');
        appendStringField(json, AiTriageSchema.FIELD_VERDICT, item.getVerdict().getWireValue());
        json.append(',');
        appendBooleanField(json, AiTriageSchema.FIELD_IS_SENSITIVE, item.isSensitive());
        json.append(',');
        appendBooleanField(json, AiTriageSchema.FIELD_IS_EXPOSED, item.isExposed());
        json.append(',');
        appendNumberField(json, AiTriageSchema.FIELD_CONFIDENCE, item.getConfidence());
        json.append(',');
        appendStringField(json, AiTriageSchema.FIELD_SEVERITY, item.getRiskLevel().getWireValue());
        json.append(',');
        appendStringField(json, AiTriageSchema.FIELD_REASON, item.getReason());
        json.append(',');
        json.append('"').append(AiTriageSchema.FIELD_RECOMMENDED_ACTIONS).append("\":[");
        for (int i = 0; i < item.getRecommendedActions().size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            appendString(json, item.getRecommendedActions().get(i));
        }
        json.append(']');
        json.append('}');
    }

    private void appendStringField(StringBuilder json, String name, String value) {
        json.append('"').append(name).append("\":");
        appendString(json, value);
    }

    private void appendBooleanField(StringBuilder json, String name, boolean value) {
        json.append('"').append(name).append("\":").append(value);
    }

    private void appendNumberField(StringBuilder json, String name, int value) {
        json.append('"').append(name).append("\":").append(value);
    }

    private void appendNumberField(StringBuilder json, String name, double value) {
        json.append('"').append(name).append("\":").append(value);
    }

    private void appendString(StringBuilder json, String value) {
        json.append('"').append(escapeJson(value)).append('"');
    }

    private String escapeJson(String value) {
        String safeValue = value == null ? "" : value;
        StringBuilder escaped = new StringBuilder(safeValue.length());
        for (int i = 0; i < safeValue.length(); i++) {
            char ch = safeValue.charAt(i);
            switch (ch) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (ch >= 0x20) {
                        escaped.append(ch);
                    } else {
                        String hex = Integer.toHexString(ch);
                        escaped.append("\\u");
                        for (int j = hex.length(); j < 4; j++) {
                            escaped.append('0');
                        }
                        escaped.append(hex);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
