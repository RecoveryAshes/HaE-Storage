package hae.repository;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hae.storage.SqliteMessageStore;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

class RepositoryContractTest {
    @Test
    void sqliteMessageStoreImplementsCurrentRepositoryBoundaries() {
        Class<SqliteMessageStore> implementation = SqliteMessageStore.class;

        assertAll(
                () -> assertTrue(MessageRepository.class.isAssignableFrom(implementation)),
                () -> assertTrue(RegexWorkRepository.class.isAssignableFrom(implementation)),
                () -> assertTrue(ExtractedDataRepository.class.isAssignableFrom(implementation)),
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
                () -> assertDoesNotThrow(() -> StorageMaintenanceRepository.class.getMethod("deleteByHostPattern", String.class)),
                () -> assertDoesNotThrow(() -> StorageMaintenanceRepository.class.getMethod("deleteAllMessages"))
        );
    }

    @Test
    void scopedRepositoryIsSignatureOnlyForFutureSqliteTables() {
        assertTrue(ScopedDataboardRepository.class.isInterface());
        assertFalse(ScopedDataboardRepository.class.isAssignableFrom(SqliteMessageStore.class));

        for (Method method : ScopedDataboardRepository.class.getDeclaredMethods()) {
            assertTrue(Modifier.isAbstract(method.getModifiers()), method.getName() + " should remain a signature only");
        }
    }
}
