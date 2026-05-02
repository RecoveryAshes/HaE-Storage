package hae.ai;

import java.util.List;

public final class AiTriageSchema {
    public static final String SCHEMA_VERSION = "ai-triage-schema-v1";
    public static final String PROMPT_VERSION = "ai-triage-prompt-v2";

    public static final String FIELD_RULE_GROUP = "rule_group";
    public static final String FIELD_RULE_NAME = "rule_name";
    public static final String FIELD_RULE_HASH = "rule_hash";
    public static final String FIELD_MATCHED_VALUE_REDACTED = "matched_value_redacted";
    public static final String FIELD_MATCH_LOCATION = "match_location";
    public static final String FIELD_VERDICT = "verdict";
    public static final String FIELD_IS_SENSITIVE = "is_sensitive";
    public static final String FIELD_IS_EXPOSED = "is_exposed";
    public static final String FIELD_CONFIDENCE = "confidence";
    public static final String FIELD_SEVERITY = "severity";
    public static final String FIELD_REASON = "reason";
    public static final String FIELD_RECOMMENDED_ACTIONS = "recommended_actions";

    public static final String FIELD_OVERALL_VERDICT = "overall_verdict";
    public static final String FIELD_OVERALL_SEVERITY = "overall_severity";
    public static final String FIELD_SUMMARY = "summary";
    public static final String FIELD_ITEMS = "items";
    public static final String FIELD_ITEMS_TRUNCATED = "items_truncated";
    public static final String FIELD_OMITTED_ITEM_COUNT = "omitted_item_count";

    public static final List<String> ITEM_FIELDS = List.of(
            FIELD_RULE_GROUP,
            FIELD_RULE_NAME,
            FIELD_RULE_HASH,
            FIELD_MATCHED_VALUE_REDACTED,
            FIELD_MATCH_LOCATION,
            FIELD_VERDICT,
            FIELD_IS_SENSITIVE,
            FIELD_IS_EXPOSED,
            FIELD_CONFIDENCE,
            FIELD_SEVERITY,
            FIELD_REASON,
            FIELD_RECOMMENDED_ACTIONS
    );

    public static final List<String> OVERALL_FIELDS = List.of(
            FIELD_OVERALL_VERDICT,
            FIELD_OVERALL_SEVERITY,
            FIELD_CONFIDENCE,
            FIELD_SUMMARY,
            FIELD_ITEMS,
            FIELD_ITEMS_TRUNCATED,
            FIELD_OMITTED_ITEM_COUNT
    );

    private AiTriageSchema() {
    }
}
