package hae.ai.worker;

import burp.api.montoya.http.message.HttpRequestResponse;

import java.util.List;
import java.util.Map;

public final class AiTriageMessageContext {
    private final String messageId;
    private final String contentHash;
    private final HttpRequestResponse requestResponse;
    private final Map<String, List<String>> extractedDataByRule;

    public AiTriageMessageContext(String messageId,
                                  String contentHash,
                                  HttpRequestResponse requestResponse,
                                  Map<String, List<String>> extractedDataByRule) {
        this.messageId = messageId == null ? "" : messageId;
        this.contentHash = contentHash == null ? "" : contentHash;
        this.requestResponse = requestResponse;
        this.extractedDataByRule = extractedDataByRule == null ? Map.of() : Map.copyOf(extractedDataByRule);
    }

    public String getMessageId() {
        return messageId;
    }

    public String getContentHash() {
        return contentHash;
    }

    public HttpRequestResponse getRequestResponse() {
        return requestResponse;
    }

    public Map<String, List<String>> getExtractedDataByRule() {
        return extractedDataByRule;
    }
}
