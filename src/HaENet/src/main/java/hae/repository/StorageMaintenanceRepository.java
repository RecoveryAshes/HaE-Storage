package hae.repository;

/**
 * SQLite-backed repository boundary for storage maintenance operations.
 *
 * <p>Deletion and cleanup methods must operate against SQLite tables as the
 * source of truth. Implementations must not hide a memory-first repository,
 * Burp extension persistence object, process-local cache, or global map behind
 * this contract.</p>
 */
public interface StorageMaintenanceRepository {
    /** Delete message history and match rows whose hosts match the requested pattern. */
    int deleteByHostPattern(String hostPattern);

    /** Delete all current message history and match rows from SQLite. */
    int deleteAllMessages();

    /** Delete all scoped Databoard rows from SQLite without touching main history tables. */
    int deleteAllScopedDataboardScopes();

    /** Return the concrete SQLite database path used by the backing implementation. */
    String getDatabasePath();
}
