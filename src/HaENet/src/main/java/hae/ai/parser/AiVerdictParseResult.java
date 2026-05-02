package hae.ai.parser;

import hae.ai.AiTriageResponse;

public final class AiVerdictParseResult {
    private final boolean parsed;
    private final AiTriageResponse response;
    private final String errorMessage;

    private AiVerdictParseResult(boolean parsed, AiTriageResponse response, String errorMessage) {
        this.parsed = parsed;
        this.response = response;
        this.errorMessage = errorMessage == null ? "" : errorMessage;
    }

    public static AiVerdictParseResult parsed(AiTriageResponse response) {
        return new AiVerdictParseResult(true, response, "");
    }

    public static AiVerdictParseResult invalid(String errorMessage) {
        return new AiVerdictParseResult(false, null, errorMessage);
    }

    public boolean isParsed() {
        return parsed;
    }

    public AiTriageResponse getResponse() {
        return response;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
