package hae.component.board.message;

public class AiSummaryDisplay {
    private static final AiSummaryDisplay EMPTY = new AiSummaryDisplay("", "", "");

    private final String aiStatus;
    private final String aiVerdict;
    private final String aiConfidence;

    public AiSummaryDisplay(String aiStatus, String aiVerdict, String aiConfidence) {
        this.aiStatus = aiStatus == null ? "" : aiStatus;
        this.aiVerdict = aiVerdict == null ? "" : aiVerdict;
        this.aiConfidence = aiConfidence == null ? "" : aiConfidence;
    }

    public static AiSummaryDisplay empty() {
        return EMPTY;
    }

    public static AiSummaryDisplay disallowedRule() {
        return new AiSummaryDisplay("不分析", "白名单外规则", "-");
    }

    public static AiSummaryDisplay pending() {
        return new AiSummaryDisplay("PENDING", "排队中", "-");
    }

    public static AiSummaryDisplay running() {
        return new AiSummaryDisplay("RUNNING", "运行中", "-");
    }

    public static AiSummaryDisplay failed(String verdict) {
        return new AiSummaryDisplay("FAILED", verdict == null || verdict.isBlank() ? "建议重试" : verdict, "-");
    }

    public String getAiStatus() {
        return aiStatus;
    }

    public String getAiVerdict() {
        return aiVerdict;
    }

    public String getAiConfidence() {
        return aiConfidence;
    }
}
