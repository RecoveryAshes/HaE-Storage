package hae.ai.worker;

@FunctionalInterface
public interface AiTriageMessageContextLoader {
    AiTriageMessageContext load(String messageId);
}
