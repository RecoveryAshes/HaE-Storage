package hae.repository;

import java.util.List;
import java.util.Map;

/**
 * SQLite-backed repository boundary for regex processing work state.
 *
 * <p>Regex queue state must be recovered from SQLite rows and status columns;
 * implementations must not introduce a memory-first repository, Burp extension
 * persistence object, process-local cache, or global map as the source of truth
 * for pending, processing, completed, or failed work.</p>
 */
public interface RegexWorkRepository {
    /** Atomically transition a pending or retryable failed row into processing state. */
    boolean markRegexProcessing(String messageId);

    /** Mark regex work complete and persist comment, color, and extracted data in SQLite. */
    boolean completeRegexProcessing(String messageId,
                                    String comment,
                                    String color,
                                    Map<String, List<String>> extractedDataByRule);

    /** Mark regex work failed and store the truncated error message in SQLite. */
    boolean failRegexProcessing(String messageId, String errorMessage);

    /** Return a failed or interrupted regex row to pending state for retry. */
    boolean resetRegexProcessing(String messageId, String errorMessage);

    /** Load pending or retryable failed message ids in SQLite-created order. */
    List<String> loadPendingRegexMessageIds(int limit);
}
