package hae.ai.client;

public enum AiClientFailureCategory {
    PERMANENT_AUTH_CONFIG(false, true),
    PERMANENT_CONFIG(false, true),
    PERMANENT_REQUEST(false, true),
    RETRYABLE(true, false);

    private final boolean retryable;
    private final boolean permanent;

    AiClientFailureCategory(boolean retryable, boolean permanent) {
        this.retryable = retryable;
        this.permanent = permanent;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public boolean isPermanent() {
        return permanent;
    }
}
