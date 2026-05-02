package hae.ai.client;

public final class AiClientException extends Exception {
    private static final int NO_STATUS_CODE = -1;

    private final AiClientFailureCategory category;
    private final int statusCode;

    public AiClientException(String message, AiClientFailureCategory category) {
        this(message, category, NO_STATUS_CODE, null);
    }

    public AiClientException(String message, AiClientFailureCategory category, int statusCode) {
        this(message, category, statusCode, null);
    }

    public AiClientException(String message, AiClientFailureCategory category, Throwable cause) {
        this(message, category, NO_STATUS_CODE, cause);
    }

    public AiClientException(String message, AiClientFailureCategory category, int statusCode, Throwable cause) {
        super(message, cause);
        this.category = category;
        this.statusCode = statusCode;
    }

    public AiClientFailureCategory getCategory() {
        return category;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public boolean isRetryable() {
        return category.isRetryable();
    }

    public boolean isPermanent() {
        return category.isPermanent();
    }
}
