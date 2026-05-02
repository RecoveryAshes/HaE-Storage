package hae.ai.client;

import hae.ai.AiTriagePromptContract;
import hae.ai.AiTriageSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class OpenAiChatJson {
    private OpenAiChatJson() {
    }

    static String chatCompletionPayload(String model, String prompt) {
        StringBuilder payload = new StringBuilder();
        payload.append('{');
        appendStringField(payload, "model", model);
        payload.append(',');
        payload.append("\"messages\":[");
        appendMessage(payload, "system", AiTriagePromptContract.PROMPT_CONTRACT);
        payload.append(',');
        appendMessage(payload, "user", prompt);
        payload.append(']');
        payload.append(',');
        payload.append("\"temperature\":0");
        payload.append('}');
        return payload.toString();
    }

    static String extractAssistantContent(String responseBody) throws AiClientException {
        if (responseBody == null || responseBody.isBlank()) {
            throw invalidResponse("AI provider response body is empty.");
        }

        Object parsed;
        try {
            parsed = new JsonParser(responseBody).parse();
        } catch (IllegalArgumentException e) {
            throw invalidResponse("AI provider response JSON is invalid.");
        }
        if (!(parsed instanceof Map<?, ?> root)) {
            throw invalidResponse("AI provider response JSON root must be an object.");
        }
        if (root.containsKey(AiTriageSchema.FIELD_OVERALL_VERDICT) || root.containsKey(AiTriageSchema.FIELD_ITEMS)) {
            return responseBody.trim();
        }

        Object choicesValue = root.get("choices");
        if (!(choicesValue instanceof List<?> choices) || choices.isEmpty()) {
            throw invalidResponse("AI provider response did not include choices.");
        }
        Object firstChoice = choices.get(0);
        if (!(firstChoice instanceof Map<?, ?> choice)) {
            throw invalidResponse("AI provider response choice is invalid.");
        }

        String finishReason = stringValue(choice.get("finish_reason"));
        if (!finishReason.isBlank() && !"stop".equals(finishReason)) {
            throw invalidResponse("AI provider response did not finish with complete assistant content: " + finishReason + ".");
        }

        Object messageValue = choice.get("message");
        if (!(messageValue instanceof Map<?, ?> message)) {
            throw invalidResponse("AI provider response did not include an assistant message.");
        }

        String role = stringValue(message.get("role"));
        if (!role.isBlank() && !"assistant".equals(role)) {
            throw invalidResponse("AI provider response message role is not assistant.");
        }

        String refusal = stringValue(message.get("refusal"));
        if (!refusal.isBlank()) {
            throw invalidResponse("AI provider returned a refusal instead of triage JSON.");
        }

        Object contentValue = message.get("content");
        if (!(contentValue instanceof String content) || content.isBlank()) {
            throw invalidResponse("AI provider response assistant content is empty.");
        }
        return content;
    }

    private static AiClientException invalidResponse(String message) {
        return new AiClientException(message, AiClientFailureCategory.PERMANENT_REQUEST);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private static void appendMessage(StringBuilder json, String role, String content) {
        json.append('{');
        appendStringField(json, "role", role);
        json.append(',');
        appendStringField(json, "content", content);
        json.append('}');
    }

    private static void appendStringField(StringBuilder json, String name, String value) {
        json.append('"').append(name).append("\":\"").append(escape(value)).append('"');
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }

        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> appendEscapedChar(escaped, ch);
            }
        }
        return escaped.toString();
    }

    private static void appendEscapedChar(StringBuilder escaped, char ch) {
        if (ch >= 0x20) {
            escaped.append(ch);
            return;
        }

        String hex = Integer.toHexString(ch);
        escaped.append("\\u");
        for (int i = hex.length(); i < 4; i++) {
            escaped.append('0');
        }
        escaped.append(hex);
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
            StringBuilder value = new StringBuilder();
            while (position < json.length()) {
                char ch = json.charAt(position++);
                if (ch == '"') {
                    return value.toString();
                }
                if (ch == '\\') {
                    value.append(parseEscape());
                } else {
                    value.append(ch);
                }
            }
            throw error();
        }

        private char parseEscape() {
            if (position >= json.length()) {
                throw error();
            }
            char ch = json.charAt(position++);
            return switch (ch) {
                case '"' -> '"';
                case '\\' -> '\\';
                case '/' -> '/';
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'u' -> parseUnicodeEscape();
                default -> throw error();
            };
        }

        private char parseUnicodeEscape() {
            if (position + 4 > json.length()) {
                throw error();
            }
            String hex = json.substring(position, position + 4);
            position += 4;
            try {
                return (char) Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw error();
            }
        }

        private Object parseNumber() {
            int start = position;
            if (peek('-')) {
                position++;
            }
            while (position < json.length() && Character.isDigit(json.charAt(position))) {
                position++;
            }
            if (peek('.')) {
                position++;
                while (position < json.length() && Character.isDigit(json.charAt(position))) {
                    position++;
                }
            }
            if (peek('e') || peek('E')) {
                position++;
                if (peek('+') || peek('-')) {
                    position++;
                }
                while (position < json.length() && Character.isDigit(json.charAt(position))) {
                    position++;
                }
            }
            String number = json.substring(start, position);
            try {
                return number.contains(".") || number.contains("e") || number.contains("E")
                        ? Double.parseDouble(number)
                        : Long.parseLong(number);
            } catch (NumberFormatException e) {
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

        private void skipWhitespace() {
            while (position < json.length() && Character.isWhitespace(json.charAt(position))) {
                position++;
            }
        }

        private boolean peek(char expected) {
            return position < json.length() && json.charAt(position) == expected;
        }

        private void expect(char expected) {
            if (!peek(expected)) {
                throw error();
            }
            position++;
        }

        private IllegalArgumentException error() {
            return new IllegalArgumentException("Invalid JSON at position " + position);
        }
    }
}
