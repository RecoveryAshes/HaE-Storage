package hae.repository;

import burp.api.montoya.http.message.HttpRequestResponse;
import hae.storage.SqliteMessageStore;

import java.util.List;
import java.util.Map;

/**
 * SQLite-backed repository boundary for persisted HTTP message history.
 *
 * <p>The SQLite database is the source of truth for every method in this
 * contract. Implementations must not use memory-first repositories, Burp
 * extension persistence objects, process-local caches, or global maps as the
 * default source of persisted message data. Method shapes intentionally mirror
 * {@link SqliteMessageStore} so callers can be rewired later without changing
 * current storage behavior.</p>
 */
public interface MessageRepository {
    /** Save a pending message row and its request/response bytes for later regex processing. */
    SqliteMessageStore.PendingMessageSaveResult savePendingMessage(String messageId,
                                                                    HttpRequestResponse messageInfo,
                                                                    String url,
                                                                    String host,
                                                                    String method,
                                                                    String status,
                                                                    String length,
                                                                    String contentHash,
                                                                    String urlParseError,
                                                                    String filterReason,
                                                                    boolean deduplicate);

    /** Save an already-processed message row and extracted match data into SQLite. */
    void saveMessage(String messageId,
                     HttpRequestResponse messageInfo,
                     String url,
                     String method,
                     String status,
                     String length,
                     String comment,
                     String color,
                     String contentHash,
                     Map<String, List<String>> extractedDataByRule);

    /** Load all matched metadata rows using current SQLite ordering semantics. */
    List<SqliteMessageStore.MessageMetadata> loadMessageMetadata();

    /** Check for an existing processed row with the same visible match metadata. */
    boolean existsDuplicate(String url, String comment, String color, String contentHash);

    /** Check for an existing row with the same exact request/response content hash. */
    boolean existsDuplicateContent(String contentHash);

    /** Count matched metadata rows by host and comment filters. */
    int countMessageMetadata(String hostPattern, String commentKeyword);

    /** Count matched metadata rows by host, comment, rule-name, and extracted-value filters. */
    int countMessageMetadata(String hostPattern, String commentKeyword, String messageTableName, String messageFilterValue);

    /** Load a page of matched metadata rows by host and comment filters. */
    List<SqliteMessageStore.MessageMetadata> loadMessageMetadataPage(String hostPattern,
                                                                     String commentKeyword,
                                                                     int limit,
                                                                     int offset);

    /** Load a page of matched metadata rows by host, comment, rule-name, and extracted-value filters. */
    List<SqliteMessageStore.MessageMetadata> loadMessageMetadataPage(String hostPattern,
                                                                     String commentKeyword,
                                                                     String messageTableName,
                                                                     String messageFilterValue,
                                                                     int limit,
                                                                     int offset);

    /** Load matched metadata rows by host and comment filters without pagination. */
    List<SqliteMessageStore.MessageMetadata> loadMessageMetadataByFilter(String hostPattern, String commentKeyword);

    /** Load matched metadata rows by host, comment, rule-name, and extracted-value filters without pagination. */
    List<SqliteMessageStore.MessageMetadata> loadMessageMetadataByFilter(String hostPattern,
                                                                         String commentKeyword,
                                                                         String messageTableName,
                                                                         String messageFilterValue);

    /** Load the stored request/response bytes for a message id. */
    HttpRequestResponse loadMessage(String messageId);

    /** Load the stored request/response bytes with host metadata for regex workers. */
    SqliteMessageStore.StoredMessage loadStoredMessage(String messageId);

    /** Load extracted regex match data for exactly one persisted message id. */
    Map<String, List<String>> loadMessageExtractedData(String messageId);
}
