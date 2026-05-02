package hae.ai.prompt;

import hae.ai.AiTriageRequest;

public final class AiPromptBuildResult {
    public static final String BUILT_FULL = "built_full";
    public static final String BUILT_FALLBACK = "built_fallback";
    public static final String SKIPPED_BINARY = "skipped_binary";
    public static final String SKIPPED_STATIC_RESOURCE = "skipped_static_resource";
    public static final String SKIPPED_OVERSIZE = "skipped_oversize";
    public static final String SKIPPED_UNSUPPORTED_MESSAGE_TYPE = "skipped_unsupported_message_type";
    public static final String SKIPPED_NO_WHITELISTED_MATCH = "skipped_no_whitelisted_match";

    private final String status;
    private final String prompt;
    private final AiTriageRequest triageRequest;
    private final boolean fallbackUsed;
    private final int promptCharCount;
    private final String reason;

    private AiPromptBuildResult(String status,
                                String prompt,
                                AiTriageRequest triageRequest,
                                boolean fallbackUsed,
                                String reason) {
        this.status = status == null ? "" : status;
        this.prompt = prompt == null ? "" : prompt;
        this.triageRequest = triageRequest;
        this.fallbackUsed = fallbackUsed;
        this.promptCharCount = this.prompt.length();
        this.reason = reason == null ? "" : reason;
    }

    public static AiPromptBuildResult builtFull(String prompt, AiTriageRequest triageRequest) {
        return new AiPromptBuildResult(BUILT_FULL, prompt, triageRequest, false, "");
    }

    public static AiPromptBuildResult builtFallback(String prompt, AiTriageRequest triageRequest) {
        return new AiPromptBuildResult(BUILT_FALLBACK, prompt, triageRequest, true, "");
    }

    public static AiPromptBuildResult skipped(String status, AiTriageRequest triageRequest, String reason) {
        return new AiPromptBuildResult(status, "", triageRequest, false, reason);
    }

    public String getStatus() {
        return status;
    }

    public String getPrompt() {
        return prompt;
    }

    public AiTriageRequest getTriageRequest() {
        return triageRequest;
    }

    public boolean isFallbackUsed() {
        return fallbackUsed;
    }

    public boolean isSkipped() {
        return status.startsWith("skipped_");
    }

    public int getPromptCharCount() {
        return promptCharCount;
    }

    public String getReason() {
        return reason;
    }
}
