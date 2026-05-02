package hae.ai;

import java.util.List;

public final class AiTriageVerdictItem {
    private final String ruleGroup;
    private final String ruleName;
    private final String ruleHash;
    private final String matchedValueRedacted;
    private final String matchLocation;
    private final AiTriageVerdict verdict;
    private final boolean sensitive;
    private final boolean exposed;
    private final double confidence;
    private final AiTriageRiskLevel riskLevel;
    private final String reason;
    private final List<String> recommendedActions;

    public AiTriageVerdictItem(
            String ruleGroup,
            String ruleName,
            String ruleHash,
            String matchedValueRedacted,
            String matchLocation,
            AiTriageVerdict verdict,
            boolean sensitive,
            boolean exposed,
            double confidence,
            AiTriageRiskLevel riskLevel,
            String reason,
            List<String> recommendedActions
    ) {
        this.ruleGroup = textOrEmpty(ruleGroup);
        this.ruleName = textOrEmpty(ruleName);
        this.ruleHash = textOrEmpty(ruleHash);
        this.matchedValueRedacted = textOrEmpty(matchedValueRedacted);
        this.matchLocation = textOrEmpty(matchLocation);
        this.verdict = verdict == null ? AiTriageVerdict.UNKNOWN : verdict;
        this.sensitive = sensitive;
        this.exposed = exposed;
        this.confidence = confidence;
        this.riskLevel = riskLevel == null ? AiTriageRiskLevel.UNKNOWN : riskLevel;
        this.reason = textOrEmpty(reason);
        this.recommendedActions = recommendedActions == null ? List.of() : List.copyOf(recommendedActions);
    }

    public String getRuleGroup() {
        return ruleGroup;
    }

    public String getRuleName() {
        return ruleName;
    }

    public String getRuleHash() {
        return ruleHash;
    }

    public String getMatchedValueRedacted() {
        return matchedValueRedacted;
    }

    public String getMatchLocation() {
        return matchLocation;
    }

    public AiTriageVerdict getVerdict() {
        return verdict;
    }

    public boolean isSensitive() {
        return sensitive;
    }

    public boolean isExposed() {
        return exposed;
    }

    public double getConfidence() {
        return confidence;
    }

    public AiTriageRiskLevel getRiskLevel() {
        return riskLevel;
    }

    public String getReason() {
        return reason;
    }

    public List<String> getRecommendedActions() {
        return recommendedActions;
    }

    private static String textOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
