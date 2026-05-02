package hae.ai;

import java.util.regex.Pattern;

public final class AiTriageResultQuality {
    public static final String VALID = "valid";
    public static final String EMPTY_ADVISORY = "empty_advisory";
    public static final String LOW_QUALITY_ADVISORY = "low_quality_advisory";

    private static final Pattern EMPTY_ITEMS_PATTERN = Pattern.compile("\\\"items\\\"\\s*:\\s*\\[\\s*]", Pattern.DOTALL);

    private AiTriageResultQuality() {
    }

    public static String classify(AiTriageResponse response) {
        if (response == null) {
            return EMPTY_ADVISORY;
        }
        boolean missingOverallJudgment = response.getConfidence() <= 0.0 || response.getSummary().isBlank();
        boolean fullyUnknownOverall = response.getOverallRiskLevel() == AiTriageRiskLevel.UNKNOWN
                && response.getOverallVerdict() == AiTriageVerdict.UNKNOWN;
        if (fullyUnknownOverall && missingOverallJudgment && response.getItems().isEmpty()) {
            return EMPTY_ADVISORY;
        }
        if (missingOverallJudgment) {
            return LOW_QUALITY_ADVISORY;
        }
        return VALID;
    }

    public static String classifyDoneResult(String status,
                                            String overallVerdict,
                                            String overallRiskLevel,
                                            double confidence,
                                            String summary,
                                            String resultJson) {
        if (!"DONE".equals(status)) {
            return VALID;
        }
        boolean missingOverallJudgment = confidence <= 0.0 || text(summary).isBlank();
        boolean fullyUnknownOverall = "unknown".equalsIgnoreCase(text(overallRiskLevel).trim())
                && "unknown".equalsIgnoreCase(text(overallVerdict).trim());
        String json = text(resultJson);
        if (fullyUnknownOverall && missingOverallJudgment && EMPTY_ITEMS_PATTERN.matcher(json).find()) {
            return EMPTY_ADVISORY;
        }
        if (missingOverallJudgment) {
            return LOW_QUALITY_ADVISORY;
        }
        return VALID;
    }

    public static boolean shouldRetry(String classification) {
        return EMPTY_ADVISORY.equals(classification) || LOW_QUALITY_ADVISORY.equals(classification);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
