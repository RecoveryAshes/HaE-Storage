package hae.ai;

import java.util.Locale;

public enum AiTriageVerdict {
    SENSITIVE_EXPOSURE("sensitive_exposure"),
    SENSITIVE_BUT_EXPECTED("sensitive_but_expected"),
    POSSIBLE_SENSITIVE("possible_sensitive"),
    FALSE_POSITIVE("false_positive"),
    NOT_SENSITIVE("not_sensitive"),
    SECURITY_SIGNAL_NOT_SECRET("security_signal_not_secret"),
    UNKNOWN("unknown");

    private final String wireValue;

    AiTriageVerdict(String wireValue) {
        this.wireValue = wireValue;
    }

    public String getWireValue() {
        return wireValue;
    }

    public static AiTriageVerdict fromWireValue(String value) {
        String normalized = normalize(value);
        for (AiTriageVerdict verdict : values()) {
            if (verdict.wireValue.equals(normalized)) {
                return verdict;
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
