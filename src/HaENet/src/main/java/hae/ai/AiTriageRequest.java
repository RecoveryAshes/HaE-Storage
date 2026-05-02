package hae.ai;

import java.util.List;

public final class AiTriageRequest {
    private final String schemaVersion;
    private final String promptVersion;
    private final List<AiTriageRequestItem> items;
    private final boolean itemsTruncated;
    private final int omittedItemCount;

    public AiTriageRequest(List<AiTriageRequestItem> items, boolean itemsTruncated, int omittedItemCount) {
        this(AiTriageSchema.SCHEMA_VERSION, AiTriageSchema.PROMPT_VERSION, items, itemsTruncated, omittedItemCount);
    }

    public AiTriageRequest(String schemaVersion, String promptVersion, List<AiTriageRequestItem> items, boolean itemsTruncated, int omittedItemCount) {
        this.schemaVersion = textOrEmpty(schemaVersion);
        this.promptVersion = textOrEmpty(promptVersion);
        this.items = items == null ? List.of() : List.copyOf(items);
        this.itemsTruncated = itemsTruncated;
        this.omittedItemCount = omittedItemCount;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public List<AiTriageRequestItem> getItems() {
        return items;
    }

    public boolean isItemsTruncated() {
        return itemsTruncated;
    }

    public int getOmittedItemCount() {
        return omittedItemCount;
    }

    private static String textOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
