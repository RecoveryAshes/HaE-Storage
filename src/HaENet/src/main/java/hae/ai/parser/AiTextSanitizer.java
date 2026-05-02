package hae.ai.parser;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AiTextSanitizer {
    private static final String REDACTION = "[redacted]";
    private static final String PROMPT_EVIDENCE_REDACTION = "[redacted prompt evidence]";
    private static final Pattern JWT_PATTERN = Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}(?:\\.[A-Za-z0-9_-]{8,})?\\b"
    );
    private static final Pattern BEARER_PATTERN = Pattern.compile(
            "(?i)\\b(Bearer\\s+)([A-Za-z0-9._~+/=-]{12,})"
    );
    private static final Pattern KEY_VALUE_PATTERN = Pattern.compile(
            "(?i)\\b(cookie|authorization|password|passwd|pwd|token|api[-_ ]?key|secret|access[-_ ]?token|refresh[-_ ]?token)(\\s*[:=]\\s*)([^\\s,;\\\"']{8,})"
    );
    private static final Pattern LONG_TOKEN_PATTERN = Pattern.compile(
            "\\b(?=[A-Za-z0-9._~+/=-]{24,}\\b)(?=.*[A-Za-z])(?=.*[0-9])[A-Za-z0-9._~+/=-]+\\b"
    );
    private static final Pattern LONG_DIGIT_PATTERN = Pattern.compile("\\b\\d{16,}\\b");
    private static final Pattern HTTP_REQUEST_LINE_PATTERN = Pattern.compile(
            "(?im)\\b(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\\s+\\S+\\s+HTTP/\\d(?:\\.\\d)?\\b"
    );
    private static final Pattern HTTP_STATUS_LINE_PATTERN = Pattern.compile("(?im)\\bHTTP/\\d(?:\\.\\d)?\\s+\\d{3}\\b");
    private static final Pattern HTTP_HOST_HEADER_PATTERN = Pattern.compile("(?im)^Host\\s*:\\s*\\S+");

    private AiTextSanitizer() {
    }

    public static String redactSecrets(String value) {
        if (value == null || value.isBlank()) {
            return value == null ? "" : value;
        }

        String sanitized = JWT_PATTERN.matcher(value).replaceAll(REDACTION);
        sanitized = replaceBearer(sanitized);
        sanitized = replaceKeyValues(sanitized);
        sanitized = LONG_DIGIT_PATTERN.matcher(sanitized).replaceAll(REDACTION);
        sanitized = LONG_TOKEN_PATTERN.matcher(sanitized).replaceAll(REDACTION);
        return sanitized;
    }

    public static String redactModelEcho(String value) {
        String sanitized = redactSecrets(value);
        if (containsPromptEvidence(sanitized)) {
            return PROMPT_EVIDENCE_REDACTION;
        }
        return sanitized;
    }

    private static String replaceBearer(String value) {
        Matcher matcher = BEARER_PATTERN.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group(1) + REDACTION));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String replaceKeyValues(String value) {
        Matcher matcher = KEY_VALUE_PATTERN.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group(1) + matcher.group(2) + REDACTION));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static boolean containsPromptEvidence(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("-----begin untrusted http evidence-----") ||
                lower.contains("-----end untrusted http evidence-----") ||
                lower.contains("triage_request_json:") ||
                lower.contains("match_context_excerpts:")) {
            return true;
        }
        return HTTP_REQUEST_LINE_PATTERN.matcher(value).find() ||
                HTTP_STATUS_LINE_PATTERN.matcher(value).find() ||
                HTTP_HOST_HEADER_PATTERN.matcher(value).find();
    }
}
