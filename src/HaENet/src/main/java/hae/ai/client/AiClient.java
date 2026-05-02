package hae.ai.client;

public interface AiClient {
    AiClientResult complete(String prompt) throws AiClientException;
}
