package hae.component.board.message;

public class MessageEntry {

    private final String messageId;
    private final String comment;
    private final String url;
    private final String length;
    private final String status;
    private final String color;
    private final String method;
    private final String contentHash;
    private final String aiStatus;
    private final String aiVerdict;
    private final String aiRiskLevel;
    private final String aiConfidence;
    private final String aiSummary;
    private final String aiTargetRuleName;
    private final String aiTargetValue;
    private final String aiTargetSignatureHash;

    public MessageEntry(String messageId, String method, String url, String comment, String length, String color, String status, String contentHash) {
        this(messageId, method, url, comment, length, color, status, contentHash, "", "", "", "", "");
    }

    public MessageEntry(String messageId,
                        String method,
                        String url,
                        String comment,
                        String length,
                        String color,
                        String status,
                        String contentHash,
                        String aiTargetRuleName,
                        String aiTargetValue,
                        String aiTargetSignatureHash) {
        this(messageId, method, url, comment, length, color, status, contentHash,
                "", "", "", "", "", aiTargetRuleName, aiTargetValue, aiTargetSignatureHash);
    }

    public MessageEntry(String messageId,
                        String method,
                        String url,
                        String comment,
                        String length,
                        String color,
                        String status,
                        String contentHash,
                        String aiStatus,
                        String aiVerdict,
                        String aiRiskLevel,
                        String aiConfidence,
                        String aiSummary) {
        this(messageId, method, url, comment, length, color, status, contentHash, aiStatus, aiVerdict,
                aiRiskLevel, aiConfidence, aiSummary, "", "", "");
    }

    public MessageEntry(String messageId,
                        String method,
                        String url,
                        String comment,
                        String length,
                        String color,
                        String status,
                        String contentHash,
                        String aiStatus,
                        String aiVerdict,
                        String aiRiskLevel,
                        String aiConfidence,
                        String aiSummary,
                        String aiTargetRuleName,
                        String aiTargetValue,
                        String aiTargetSignatureHash) {
        this.messageId = messageId;
        this.method = method;
        this.url = url;
        this.comment = comment;
        this.length = length;
        this.color = color;
        this.status = status;
        this.contentHash = contentHash;
        this.aiStatus = aiStatus;
        this.aiVerdict = aiVerdict;
        this.aiRiskLevel = aiRiskLevel;
        this.aiConfidence = aiConfidence;
        this.aiSummary = aiSummary;
        this.aiTargetRuleName = aiTargetRuleName;
        this.aiTargetValue = aiTargetValue;
        this.aiTargetSignatureHash = aiTargetSignatureHash;
    }

    public String getMessageId() {
        return this.messageId;
    }

    public String getColor() {
        return this.color;
    }

    public String getUrl() {
        return this.url;
    }

    public String getLength() {
        return this.length;
    }

    public String getComment() {
        return this.comment;
    }

    public String getMethod() {
        return this.method;
    }

    public String getStatus() {
        return this.status;
    }

    public String getContentHash() {
        return this.contentHash;
    }

    public String getAiStatus() {
        return this.aiStatus;
    }

    public String getAiVerdict() {
        return this.aiVerdict;
    }

    public String getAiRiskLevel() {
        return this.aiRiskLevel;
    }

    public String getAiConfidence() {
        return this.aiConfidence;
    }

    public String getAiSummary() {
        return this.aiSummary;
    }

    public String getAiTargetRuleName() {
        return this.aiTargetRuleName;
    }

    public String getAiTargetValue() {
        return this.aiTargetValue;
    }

    public String getAiTargetSignatureHash() {
        return this.aiTargetSignatureHash;
    }
}
