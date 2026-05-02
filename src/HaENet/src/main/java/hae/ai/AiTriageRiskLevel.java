package hae.ai;

import java.util.Locale;

public enum AiTriageRiskLevel {
    CRITICAL("critical"),
    HIGH("high"),
    MEDIUM("medium"),
    LOW("low"),
    INFO("info"),
    UNKNOWN("unknown");

    private final String wireValue;

    AiTriageRiskLevel(String wireValue) {
        this.wireValue = wireValue;
    }

    public String getWireValue() {
        return wireValue;
    }

    public static AiTriageRiskLevel fromWireValue(String value) {
        String normalized = normalize(value);
        for (AiTriageRiskLevel riskLevel : values()) {
            if (riskLevel.wireValue.equals(normalized)) {
                return riskLevel;
            }
        }
        return UNKNOWN;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
    }
}
