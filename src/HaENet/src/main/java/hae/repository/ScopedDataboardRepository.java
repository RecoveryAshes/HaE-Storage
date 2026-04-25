package hae.repository;

import burp.api.montoya.http.message.HttpRequestResponse;
import hae.storage.SqliteMessageStore;

import java.util.List;
import java.util.Map;

/**
 * Planned SQLite-backed repository boundary for isolated scoped Databoard data.
 *
 * <p>This task defines signatures only. Future implementations must store scoped
 * analysis in dedicated SQLite scope/message/match tables and must not write
 * scoped results into the main {@code message_history} or {@code message_match}
 * tables. No memory-first repository, Burp extension persistence object,
 * process-local cache, or global map may become the default source of scoped
 * Databoard data.</p>
 */
public interface ScopedDataboardRepository {
    /** Create a scoped Databoard analysis scope and return its SQLite scope id. */
    String createScopedDataboardScope(String source, String label);

    /** Persist one scoped message into the planned scoped message table. */
    void saveScopedMessage(String scopeId,
                           String scopedMessageId,
                           HttpRequestResponse messageInfo,
                           String url,
                           String host,
                           String method,
                           String status,
                           String length,
                           String comment,
                           String color,
                           String contentHash);

    /** Persist extracted values for one scoped message into the planned scoped match table. */
    void saveScopedMatches(String scopeId, String scopedMessageId, Map<String, List<String>> extractedDataByRule);

    /** Count scoped metadata rows by scope, host, comment, rule-name, and extracted-value filters. */
    int countScopedMessageMetadata(String scopeId,
                                   String hostPattern,
                                   String commentKeyword,
                                   String messageTableName,
                                   String messageFilterValue);

    /** Load a page of scoped metadata rows by scope, host, comment, rule-name, and extracted-value filters. */
    List<SqliteMessageStore.MessageMetadata> loadScopedMessageMetadataPage(String scopeId,
                                                                           String hostPattern,
                                                                           String commentKeyword,
                                                                           String messageTableName,
                                                                           String messageFilterValue,
                                                                           int limit,
                                                                           int offset);

    /** Load scoped metadata rows by scope, host, comment, rule-name, and extracted-value filters. */
    List<SqliteMessageStore.MessageMetadata> loadScopedMessageMetadataByFilter(String scopeId,
                                                                               String hostPattern,
                                                                               String commentKeyword,
                                                                               String messageTableName,
                                                                               String messageFilterValue);

    /** Load scoped extracted values grouped by rule name for the requested scope and filters. */
    Map<String, List<String>> loadScopedExtractedData(String scopeId,
                                                      String hostPattern,
                                                      String ruleName,
                                                      String extractedValue);

    /** Load matched hosts available inside a scoped Databoard scope. */
    List<String> loadScopedMatchedHosts(String scopeId);

    /** Load the stored request/response bytes for a scoped message id. */
    HttpRequestResponse loadScopedMessage(String scopeId, String scopedMessageId);

    /** Delete one scoped Databoard scope and its planned scoped rows. */
    int deleteScopedDataboardScope(String scopeId);

    /** Delete all scoped Databoard scopes and planned scoped rows. */
    int deleteAllScopedDataboardScopes();
}
