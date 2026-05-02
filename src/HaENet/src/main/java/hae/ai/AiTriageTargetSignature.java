package hae.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class AiTriageTargetSignature {
    private AiTriageTargetSignature() {
    }

    public static String matchSignatureHash(String ruleName, String value) {
        String normalizedRuleName = safe(ruleName).trim();
        String normalizedValue = safe(value).trim();
        return hash(normalizedRuleName + "|" + hash(normalizedValue));
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(safe(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            return Integer.toHexString(safe(value).hashCode());
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
