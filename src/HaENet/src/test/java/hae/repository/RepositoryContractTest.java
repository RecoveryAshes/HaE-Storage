package hae.repository;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hae.storage.SqliteMessageStore;

import org.junit.jupiter.api.Test;

class RepositoryContractTest {
    @Test
    void sqliteMessageStoreImplementsCurrentRepositoryBoundaries() {
        Class<SqliteMessageStore> implementation = SqliteMessageStore.class;

        assertAll(
                () -> assertTrue(MessageRepository.class.isAssignableFrom(implementation)),
                () -> assertTrue(RegexWorkRepository.class.isAssignableFrom(implementation)),
                () -> assertTrue(ExtractedDataRepository.class.isAssignableFrom(implementation)),
                () -> assertTrue(ScopedDataboardRepository.class.isAssignableFrom(implementation)),
                () -> assertTrue(AiTaskRepository.class.isAssignableFrom(implementation)),
                () -> assertTrue(AiResultRepository.class.isAssignableFrom(implementation)),
                () -> assertTrue(StorageMaintenanceRepository.class.isAssignableFrom(implementation))
        );
    }

    @Test
    void repositoryContractsExposeExistingSqliteMethods() {
        assertAll(
                () -> assertDoesNotThrow(() -> MessageRepository.class.getMethod(
                        "savePendingMessage",
                        String.class,
                        burp.api.montoya.http.message.HttpRequestResponse.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        boolean.class
                )),
                () -> assertDoesNotThrow(() -> MessageRepository.class.getMethod(
                        "loadMessageMetadataPage",
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        int.class,
                        int.class
                )),
                () -> assertDoesNotThrow(() -> MessageRepository.class.getMethod("loadStoredMessage", String.class)),
                () -> assertDoesNotThrow(() -> RegexWorkRepository.class.getMethod("markRegexProcessing", String.class)),
                () -> assertDoesNotThrow(() -> RegexWorkRepository.class.getMethod(
                        "completeRegexProcessing",
                        String.class,
                        String.class,
                        String.class,
                        java.util.Map.class
                )),
                () -> assertDoesNotThrow(() -> RegexWorkRepository.class.getMethod("loadPendingRegexMessageIds", int.class)),
                () -> assertDoesNotThrow(() -> ExtractedDataRepository.class.getMethod("loadExtractedDataByHost", String.class)),
                () -> assertDoesNotThrow(() -> ScopedDataboardRepository.class.getMethod(
                        "loadScopedMessageMetadataPage",
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        int.class,
                        int.class
                )),
                () -> assertDoesNotThrow(() -> ScopedDataboardRepository.class.getMethod(
                        "loadScopedExtractedData",
                        String.class,
                        String.class,
                        String.class,
                        String.class
                )),
                () -> assertDoesNotThrow(() -> ScopedDataboardRepository.class.getMethod(
                        "loadScopedMessage",
                        String.class,
                        String.class
                )),
                () -> assertDoesNotThrow(() -> AiTaskRepository.class.getMethod(
                        "enqueueAiTriageTask",
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        int.class,
                        int.class,
                        long.class
                )),
                () -> assertDoesNotThrow(() -> AiTaskRepository.class.getMethod(
                        "leaseNextAiTriageTasks",
                        int.class,
                        long.class,
                        long.class
                )),
                () -> assertDoesNotThrow(() -> AiTaskRepository.class.getMethod(
                        "leaseNextAiTriageTasks",
                        int.class,
                        long.class,
                        long.class,
                        String.class
                )),
                () -> assertDoesNotThrow(() -> AiTaskRepository.class.getMethod("countActiveAiTriageTasks")),
                () -> assertDoesNotThrow(() -> AiTaskRepository.class.getMethod("hasAiTriageForMessage", String.class)),
                () -> assertDoesNotThrow(() -> AiTaskRepository.class.getMethod("hasBlockingAiTriageForMessage", String.class)),
                () -> assertDoesNotThrow(() -> AiTaskRepository.class.getMethod("hasBlockingAiTriageForTarget", String.class, String.class)),
                () -> assertDoesNotThrow(() -> AiTaskRepository.class.getMethod("completeAiTriageTask", String.class)),
                () -> assertDoesNotThrow(() -> AiTaskRepository.class.getMethod("completeAiTriageTask", String.class, String.class, String.class)),
                () -> assertDoesNotThrow(() -> AiTaskRepository.class.getMethod(
                        "completeAiTriageTaskWithResult",
                        String.class,
                        String.class,
                        String.class,
                        AiTaskRepository.AiTriageResultWrite.class
                )),
                () -> assertDoesNotThrow(() -> AiTaskRepository.class.getMethod(
                        "failAiTriageTask",
                        String.class,
                        String.class,
                        String.class,
                        long.class
                )),
                () -> assertDoesNotThrow(() -> AiTaskRepository.class.getMethod(
                        "failAiTriageTask",
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        long.class
                )),
                () -> assertDoesNotThrow(() -> AiTaskRepository.class.getMethod("releaseAiTriageTask", String.class, long.class)),
                () -> assertDoesNotThrow(() -> AiTaskRepository.class.getMethod("releaseAiTriageTask", String.class, String.class, String.class, long.class)),
                () -> assertDoesNotThrow(() -> AiTaskRepository.class.getMethod("recoverStaleAiTriageTasks", long.class)),
                () -> assertDoesNotThrow(() -> AiTaskRepository.class.getMethod("cleanupOrphanAiTriageTasks")),
                () -> assertDoesNotThrow(() -> AiResultRepository.class.getMethod(
                        "saveAiTriageResult",
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        double.class,
                        String.class,
                        String.class,
                        long.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class
                )),
                () -> assertDoesNotThrow(() -> AiResultRepository.class.getMethod("loadAiTriageResultSummaries", java.util.List.class)),
                () -> assertDoesNotThrow(() -> AiResultRepository.class.getMethod("loadAiTriageResultSummaries", java.util.List.class, String.class)),
                () -> assertDoesNotThrow(() -> AiResultRepository.class.getMethod("loadAiTriageResultJson", String.class)),
                () -> assertDoesNotThrow(() -> AiResultRepository.class.getMethod("loadAiTriageResultJson", String.class, String.class)),
                () -> assertDoesNotThrow(() -> AiResultRepository.class.getMethod("cleanupOrphanAiTriageResults")),
                () -> assertDoesNotThrow(() -> StorageMaintenanceRepository.class.getMethod("deleteByHostPattern", String.class)),
                () -> assertDoesNotThrow(() -> StorageMaintenanceRepository.class.getMethod("deleteAllMessages")),
                () -> assertDoesNotThrow(() -> StorageMaintenanceRepository.class.getMethod("deleteAllScopedDataboardScopes"))
        );
    }

    @Test
    void scopedRepositoryIsSqliteBackedContract() {
        assertTrue(ScopedDataboardRepository.class.isInterface());
        assertTrue(ScopedDataboardRepository.class.isAssignableFrom(SqliteMessageStore.class));
    }
}
