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

    MessageEntry(String messageId, String method, String url, String comment, String length, String color, String status, String contentHash) {
        this.messageId = messageId;
        this.method = method;
        this.url = url;
        this.comment = comment;
        this.length = length;
        this.color = color;
        this.status = status;
        this.contentHash = contentHash;
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
}
