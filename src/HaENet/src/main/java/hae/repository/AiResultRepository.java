package hae.repository;

import hae.storage.SqliteMessageStore;

import java.util.List;

/**
 * SQLite-backed repository boundary for AI triage result reads and writes.
 *
 * <p>AI triage results must remain separate from regex match data. Summary
 * lookups should avoid loading the full JSON response unless callers explicitly
 * request it by message id.</p>
 */
public interface AiResultRepository {
    /** Persist the AI response summary and full AI response JSON for one message analysis. */
    boolean saveAiTriageResult(String messageId,
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
                               String configHash);

    /** Batch-load lightweight AI result summaries by message ids without loading result_json. */
    List<SqliteMessageStore.AiTriageResultSummary> loadAiTriageResultSummaries(List<String> messageIds);

    /** Batch-load lightweight AI result summaries for one exact target signature. */
    List<SqliteMessageStore.AiTriageResultSummary> loadAiTriageResultSummaries(List<String> messageIds,
                                                                               String matchSignatureHash);

    /** Explicitly load the latest full AI response JSON for one message id. */
    String loadAiTriageResultJson(String messageId);

    /** Explicitly load the latest full AI response JSON for one exact target signature. */
    String loadAiTriageResultJson(String messageId, String matchSignatureHash);

    /** Delete AI triage results whose message ids no longer exist in message history. */
    int cleanupOrphanAiTriageResults();
}
