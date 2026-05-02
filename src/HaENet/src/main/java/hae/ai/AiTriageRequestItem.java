package hae.ai;

public final class AiTriageRequestItem {
    private final String ruleGroup;
    private final String ruleName;
    private final String ruleHash;
    private final String matchedValueRedacted;
    private final String matchLocation;

    public AiTriageRequestItem(String ruleGroup, String ruleName, String ruleHash, String matchedValueRedacted, String matchLocation) {
        this.ruleGroup = textOrEmpty(ruleGroup);
        this.ruleName = textOrEmpty(ruleName);
        this.ruleHash = textOrEmpty(ruleHash);
        this.matchedValueRedacted = textOrEmpty(matchedValueRedacted);
        this.matchLocation = textOrEmpty(matchLocation);
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

    private static String textOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
