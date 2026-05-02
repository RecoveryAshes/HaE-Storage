package hae.ai.worker;

import hae.repository.MessageRepository;
import hae.storage.SqliteMessageStore;

import java.util.Objects;

public final class RepositoryAiTriageMessageContextLoader implements AiTriageMessageContextLoader {
    private final MessageRepository messageRepository;

    public RepositoryAiTriageMessageContextLoader(MessageRepository messageRepository) {
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository");
    }

    @Override
    public AiTriageMessageContext load(String messageId) {
        SqliteMessageStore.StoredMessage storedMessage = messageRepository.loadStoredMessage(messageId);
        if (storedMessage == null) {
            return null;
        }
        return new AiTriageMessageContext(
                storedMessage.getMessageId(),
                storedMessage.getContentHash(),
                storedMessage.getRequestResponse(),
                messageRepository.loadMessageExtractedData(messageId)
        );
    }
}
