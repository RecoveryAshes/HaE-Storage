package hae.repository;

import hae.ai.AiQueueCounts;
import hae.storage.SqliteMessageStore;

import java.util.List;

/**
 * SQLite-backed repository boundary for AI triage task state.
 *
 * <p>AI triage queue state must be recovered from SQLite rows and status
 * columns. Implementations must not use memory-first repositories, Burp
 * extension persistence objects, process-local caches, or global maps as the
 * source of truth for pending, leased, completed, failed, or recoverable AI
 * work.</p>
 */
public interface AiTaskRepository {
    record AiTriageResultWrite(String messageId,
                               String contentHash,
                               String analysisKey,
                               String matchSignatureHash,
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
        public AiTriageResultWrite(String messageId,
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
            this(messageId,
                    contentHash,
                    analysisKey,
                    "",
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
                    configHash);
        }
    }

    /** Enqueue one AI triage task if the message and analysis key are not already queued. */
    boolean enqueueAiTriageTask(String taskId,
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
                                long nextAttemptAt);

    /** Count AI triage tasks that still occupy queue capacity. */
    int countActiveAiTriageTasks();

    /** Return whether any AI triage task or result already exists for one message. */
    boolean hasAiTriageForMessage(String messageId);

    /** Return whether an existing task/result should block analyze-once enqueue for one message. */
    boolean hasBlockingAiTriageForMessage(String messageId);

    /** Return whether an existing task should block analyze-once enqueue for one message target. */
    boolean hasBlockingAiTriageForTarget(String messageId, String matchSignatureHash);

    /** Load queue and result counts for the AI settings/status UI. */
    AiQueueCounts loadAiQueueCounts();

    /** Load lightweight task state for a message target without reading prompts or HTTP bodies. */
    List<SqliteMessageStore.AiTriageTask> loadAiTriageTaskSummaries(List<String> messageIds,
                                                                    String matchSignatureHash);

    /** Delete pending AI triage tasks before they are leased by a worker. */
    int clearPendingAiTriageTasks();

    /** Return failed AI triage tasks to pending so the worker can retry them. */
    int retryFailedAiTriageTasks(long nextAttemptAt);

    /** Atomically lease pending or retryable failed AI triage tasks. */
    List<SqliteMessageStore.AiTriageTask> leaseNextAiTriageTasks(int limit,
                                                                 long nowEpochMillis,
                                                                 long leaseDurationMillis);

    /** Atomically lease pending or retryable failed AI triage tasks for one worker owner. */
    List<SqliteMessageStore.AiTriageTask> leaseNextAiTriageTasks(int limit,
                                                                 long nowEpochMillis,
                                                                 long leaseDurationMillis,
                                                                 String leaseOwner);

    /** Mark a leased AI triage task as complete after its result has been persisted. */
    boolean completeAiTriageTask(String taskId);

    /** Mark a leased AI triage task as complete only when the lease owner/token still match. */
    boolean completeAiTriageTask(String taskId, String leaseOwner, String leaseToken);

    /** Persist the AI result and complete the task in one token-guarded SQLite transaction. */
    boolean completeAiTriageTaskWithResult(String taskId,
                                           String leaseOwner,
                                           String leaseToken,
                                           AiTriageResultWrite resultWrite);

    /** Mark a leased AI triage task as failed and store sanitized retry metadata. */
    boolean failAiTriageTask(String taskId,
                             String errorCode,
                             String errorMessageSanitized,
                             long nextAttemptAt);

    /** Mark a leased AI triage task as failed only when the lease owner/token still match. */
    boolean failAiTriageTask(String taskId,
                             String leaseOwner,
                             String leaseToken,
                             String errorCode,
                             String errorMessageSanitized,
                             long nextAttemptAt);

    /** Return a locally leased AI triage task to pending without consuming the lease attempt. */
    boolean releaseAiTriageTask(String taskId, long nextAttemptAt);

    /** Release a leased AI triage task only when the lease owner/token still match. */
    boolean releaseAiTriageTask(String taskId, String leaseOwner, String leaseToken, long nextAttemptAt);

    /** Return stale leased/running AI triage tasks to pending state for retry. */
    int recoverStaleAiTriageTasks(long nowEpochMillis);

    /** Delete AI triage tasks whose message ids no longer exist in message history. */
    int cleanupOrphanAiTriageTasks();
}
