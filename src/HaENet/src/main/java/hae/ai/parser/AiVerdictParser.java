package hae.ai.parser;

import hae.ai.AiTriageResponse;
import hae.ai.AiTriageResultQuality;
import hae.ai.AiTriageRiskLevel;
import hae.ai.AiTriageSchema;
import hae.ai.AiTriageVerdict;
import hae.ai.AiTriageVerdictItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AiVerdictParser {
    public AiVerdictParseResult parse(String rawResponse) {
        String normalized = stripCommonJsonFence(rawResponse);
        if (normalized.isBlank()) {
            return AiVerdictParseResult.invalid("AI response body is empty");
        }

        Object parsed;
        try {
            parsed = new JsonParser(normalized).parse();
        } catch (IllegalArgumentException e) {
            return AiVerdictParseResult.invalid("AI response JSON is invalid");
        }
        if (!(parsed instanceof Map<?, ?> fields)) {
            return AiVerdictParseResult.invalid("AI response JSON root must be an object");
        }

        AiTriageVerdict overallVerdict = AiTriageVerdict.fromWireValue(stringValue(fields.get(AiTriageSchema.FIELD_OVERALL_VERDICT)));
        AiTriageRiskLevel overallRiskLevel = normalizeOverallRiskLevel(
                overallVerdict,
                AiTriageRiskLevel.fromWireValue(stringValue(fields.get(AiTriageSchema.FIELD_OVERALL_SEVERITY)))
        );
        AiTriageResponse response = new AiTriageResponse(
                overallVerdict,
                overallRiskLevel,
                normalizeConfidence(fields.get(AiTriageSchema.FIELD_CONFIDENCE)),
                AiTextSanitizer.redactModelEcho(stringValue(fields.get(AiTriageSchema.FIELD_SUMMARY))),
                parseItems(fields.get(AiTriageSchema.FIELD_ITEMS)),
                booleanValue(fields.get(AiTriageSchema.FIELD_ITEMS_TRUNCATED)),
                intValue(fields.get(AiTriageSchema.FIELD_OMITTED_ITEM_COUNT))
        );
        response = synthesizeOverallFromActionableItem(response);
        String quality = AiTriageResultQuality.classify(response);
        if (AiTriageResultQuality.shouldRetry(quality)) {
            return AiVerdictParseResult.invalid("AI response quality check failed: " + quality);
        }
        return AiVerdictParseResult.parsed(response);
    }

    private static AiTriageResponse synthesizeOverallFromActionableItem(AiTriageResponse response) {
        if (response == null) {
            return null;
        }
        if (response.getConfidence() > 0.0 && !response.getSummary().isBlank()) {
            return response;
        }
        AiTriageVerdictItem item = bestActionableItem(response.getItems());
        if (item == null) {
            return response;
        }
        String summary = firstNonBlank(
                item.getReason(),
                item.getMatchedValueRedacted().isBlank() ? "" : "AI 明细已给出判断，匹配值已脱敏。",
                "AI 明细已给出可执行判断。"
        );
        return new AiTriageResponse(
                item.getVerdict(),
                normalizeOverallRiskLevel(item.getVerdict(), item.getRiskLevel()),
                item.getConfidence(),
                summary,
                response.getItems(),
                response.isItemsTruncated(),
                response.getOmittedItemCount()
        );
    }

    private static AiTriageVerdictItem bestActionableItem(List<AiTriageVerdictItem> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        AiTriageVerdictItem best = null;
        for (AiTriageVerdictItem item : items) {
            if (!isActionableItem(item)) {
                continue;
            }
            if (best == null || item.getConfidence() > best.getConfidence()) {
                best = item;
            }
        }
        return best;
    }

    private static boolean isActionableItem(AiTriageVerdictItem item) {
        if (item == null || item.getVerdict() == AiTriageVerdict.UNKNOWN || item.getConfidence() <= 0.0) {
            return false;
        }
        return !item.getReason().isBlank()
                || !item.getMatchedValueRedacted().isBlank()
                || !item.getMatchLocation().isBlank()
                || !item.getRecommendedActions().isEmpty();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static AiTriageRiskLevel normalizeOverallRiskLevel(AiTriageVerdict verdict, AiTriageRiskLevel riskLevel) {
        if (riskLevel != AiTriageRiskLevel.UNKNOWN) {
            return riskLevel;
        }
        return switch (verdict) {
            case FALSE_POSITIVE, NOT_SENSITIVE, SECURITY_SIGNAL_NOT_SECRET -> AiTriageRiskLevel.INFO;
            default -> AiTriageRiskLevel.UNKNOWN;
        };
    }

    public static String stripCommonJsonFence(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (!trimmed.startsWith("```") || !trimmed.endsWith("```")) {
            return trimmed;
        }

        int firstLineEnd = trimmed.indexOf('\n');
        if (firstLineEnd < 0) {
            return trimmed;
        }
        String fenceHeader = trimmed.substring(3, firstLineEnd).trim();
        if (!fenceHeader.isBlank() && !"json".equalsIgnoreCase(fenceHeader)) {
            return trimmed;
        }
        return trimmed.substring(firstLineEnd + 1, trimmed.length() - 3).trim();
    }

    private List<AiTriageVerdictItem> parseItems(Object value) {
        if (!(value instanceof List<?> rawItems)) {
            return List.of();
        }
        List<AiTriageVerdictItem> items = new ArrayList<>();
        for (Object rawItem : rawItems) {
            if (!(rawItem instanceof Map<?, ?> fields)) {
                continue;
            }
            items.add(new AiTriageVerdictItem(
                    stringValue(fields.get(AiTriageSchema.FIELD_RULE_GROUP)),
                    stringValue(fields.get(AiTriageSchema.FIELD_RULE_NAME)),
                    stringValue(fields.get(AiTriageSchema.FIELD_RULE_HASH)),
                    AiTextSanitizer.redactModelEcho(stringValue(fields.get(AiTriageSchema.FIELD_MATCHED_VALUE_REDACTED))),
                    stringValue(fields.get(AiTriageSchema.FIELD_MATCH_LOCATION)),
                    AiTriageVerdict.fromWireValue(stringValue(fields.get(AiTriageSchema.FIELD_VERDICT))),
                    booleanValue(fields.get(AiTriageSchema.FIELD_IS_SENSITIVE)),
                    booleanValue(fields.get(AiTriageSchema.FIELD_IS_EXPOSED)),
                    normalizeConfidence(fields.get(AiTriageSchema.FIELD_CONFIDENCE)),
                    AiTriageRiskLevel.fromWireValue(stringValue(fields.get(AiTriageSchema.FIELD_SEVERITY))),
                    AiTextSanitizer.redactModelEcho(stringValue(fields.get(AiTriageSchema.FIELD_REASON))),
                    parseActions(fields.get(AiTriageSchema.FIELD_RECOMMENDED_ACTIONS))
            ));
        }
        return items;
    }

    private List<String> parseActions(Object value) {
        if (!(value instanceof List<?> rawActions)) {
            return List.of();
        }
        List<String> actions = new ArrayList<>();
        for (Object rawAction : rawActions) {
            actions.add(AiTextSanitizer.redactModelEcho(stringValue(rawAction)));
        }
        return actions;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String stringValue) {
            return Boolean.parseBoolean(stringValue);
        }
        return false;
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        try {
            return Math.max(0, Integer.parseInt(stringValue(value)));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double normalizeConfidence(Object value) {
        double confidence;
        if (value instanceof Number number) {
            confidence = number.doubleValue();
        } else {
            try {
                confidence = Double.parseDouble(stringValue(value));
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
        if (Double.isNaN(confidence) || Double.isInfinite(confidence)) {
            return 0.0;
        }
        if (confidence > 1.0 && confidence <= 100.0) {
            confidence = confidence / 100.0;
        }
        if (confidence < 0.0) {
            return 0.0;
        }
        if (confidence > 1.0) {
            return 1.0;
        }
        return confidence;
    }

    private static final class JsonParser {
        private final String json;
        private int position;

        private JsonParser(String json) {
            this.json = json;
        }

        private Object parse() {
            Object value = parseValue();
            skipWhitespace();
            if (position != json.length()) {
                throw error();
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (position >= json.length()) {
                throw error();
            }
            char ch = json.charAt(position);
            return switch (ch) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> {
                    if (ch == '-' || Character.isDigit(ch)) {
                        yield parseNumber();
                    }
                    throw error();
                }
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> object = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                position++;
                return object;
            }
            while (true) {
                skipWhitespace();
                if (!peek('"')) {
                    throw error();
                }
                String key = parseString();
                skipWhitespace();
                expect(':');
                object.put(key, parseValue());
                skipWhitespace();
                if (peek('}')) {
                    position++;
                    return object;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> array = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                position++;
                return array;
            }
            while (true) {
                array.add(parseValue());
                skipWhitespace();
                if (peek(']')) {
                    position++;
                    return array;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (position < json.length()) {
                char ch = json.charAt(position++);
                if (ch == '"') {
                    return builder.toString();
                }
                if (ch == '\\') {
                    builder.append(parseEscapedChar());
                    continue;
                }
                if (ch < 0x20) {
                    throw error();
                }
                builder.append(ch);
            }
            throw error();
        }

        private char parseEscapedChar() {
            if (position >= json.length()) {
                throw error();
            }
            char escaped = json.charAt(position++);
            return switch (escaped) {
                case '"' -> '"';
                case '\\' -> '\\';
                case '/' -> '/';
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'u' -> parseUnicode();
                default -> throw error();
            };
        }

        private char parseUnicode() {
            if (position + 4 > json.length()) {
                throw error();
            }
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(json.charAt(position++), 16);
                if (digit < 0) {
                    throw error();
                }
                value = (value << 4) + digit;
            }
            return (char) value;
        }

        private Object parseNumber() {
            int start = position;
            if (peek('-')) {
                position++;
            }
            parseDigits();
            boolean floatingPoint = false;
            if (peek('.')) {
                floatingPoint = true;
                position++;
                parseDigits();
            }
            if (peek('e') || peek('E')) {
                floatingPoint = true;
                position++;
                if (peek('+') || peek('-')) {
                    position++;
                }
                parseDigits();
            }
            String number = json.substring(start, position);
            try {
                if (floatingPoint) {
                    return Double.parseDouble(number);
                }
                long longValue = Long.parseLong(number);
                if (longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE) {
                    return (int) longValue;
                }
                return longValue;
            } catch (NumberFormatException e) {
                throw error();
            }
        }

        private void parseDigits() {
            int start = position;
            while (position < json.length() && Character.isDigit(json.charAt(position))) {
                position++;
            }
            if (start == position) {
                throw error();
            }
        }

        private Object parseLiteral(String literal, Object value) {
            if (!json.startsWith(literal, position)) {
                throw error();
            }
            position += literal.length();
            return value;
        }

        private void expect(char expected) {
            skipWhitespace();
            if (!peek(expected)) {
                throw error();
            }
            position++;
        }

        private boolean peek(char expected) {
            return position < json.length() && json.charAt(position) == expected;
        }

        private void skipWhitespace() {
            while (position < json.length()) {
                char ch = json.charAt(position);
                if (ch != ' ' && ch != '\n' && ch != '\r' && ch != '\t') {
                    return;
                }
                position++;
            }
        }

        private IllegalArgumentException error() {
            return new IllegalArgumentException("invalid JSON at position " + position);
        }
    }
}
