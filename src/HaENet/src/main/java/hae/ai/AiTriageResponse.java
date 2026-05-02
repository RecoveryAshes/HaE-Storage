package hae.ai;

import java.util.List;

public final class AiTriageResponse {
    private final AiTriageVerdict overallVerdict;
    private final AiTriageRiskLevel overallRiskLevel;
    private final double confidence;
    private final String summary;
    private final List<AiTriageVerdictItem> items;
    private final boolean itemsTruncated;
    private final int omittedItemCount;

    public AiTriageResponse(
            AiTriageVerdict overallVerdict,
            AiTriageRiskLevel overallRiskLevel,
            double confidence,
            String summary,
            List<AiTriageVerdictItem> items,
            boolean itemsTruncated,
            int omittedItemCount
    ) {
        this.overallVerdict = overallVerdict == null ? AiTriageVerdict.UNKNOWN : overallVerdict;
        this.overallRiskLevel = overallRiskLevel == null ? AiTriageRiskLevel.UNKNOWN : overallRiskLevel;
        this.confidence = confidence;
        this.summary = summary == null ? "" : summary;
        this.items = items == null ? List.of() : List.copyOf(items);
        this.itemsTruncated = itemsTruncated;
        this.omittedItemCount = omittedItemCount;
    }

    public AiTriageVerdict getOverallVerdict() {
        return overallVerdict;
    }

    public AiTriageRiskLevel getOverallRiskLevel() {
        return overallRiskLevel;
    }

    public double getConfidence() {
        return confidence;
    }

    public String getSummary() {
        return summary;
    }

    public List<AiTriageVerdictItem> getItems() {
        return items;
    }

    public boolean isItemsTruncated() {
        return itemsTruncated;
    }

    public int getOmittedItemCount() {
        return omittedItemCount;
    }
}
