package hae.repository;

import java.util.List;
import java.util.Map;

/**
 * SQLite-backed repository boundary for extracted rule/value queries.
 *
 * <p>Extracted data must be read from SQLite match tables. Implementations must
 * not fall back to an in-memory repository, Burp extension persistence object,
 * process-local cache, or global map as the default source of extracted data.</p>
 */
public interface ExtractedDataRepository {
    /** Load distinct extracted values grouped by rule name for the requested host pattern. */
    Map<String, List<String>> loadExtractedDataByHost(String hostPattern);

    /** Load matched hosts, including existing aggregation entries, from SQLite history rows. */
    List<String> loadMatchedHosts();
}
