package hae.ai;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import hae.ai.prompt.AiPromptBuildResult;
import hae.ai.prompt.AiPromptBuilder;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiPromptBuilderTest {
    @Test
    void smallTextIncludesFullRequestResponseAndGuardrails() {
        AiPromptBuildResult result = new AiPromptBuilder().build(
                "message-small",
                requestResponse(
                        "GET /api/token HTTP/1.1\r\nHost: example.test\r\nAccept: application/json\r\n\r\n",
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"token\":\"abc123-visible\"}",
                        "json"
                ),
                Map.of("JWT", List.of("abc123-visible")),
                config(10, 20_000, 20_000, 20_000, true, true, true)
        );

        assertAll(
                () -> assertEquals(AiPromptBuildResult.BUILT_FULL, result.getStatus()),
                () -> assertFalse(result.isFallbackUsed()),
                () -> assertTrue(result.getPrompt().contains(AiTriagePromptContract.HTTP_CONTENT_IS_UNTRUSTED_EVIDENCE)),
                () -> assertTrue(result.getPrompt().contains(AiPromptBuilder.BEGIN_EVIDENCE)),
                () -> assertTrue(result.getPrompt().contains(AiPromptBuilder.END_EVIDENCE)),
                () -> assertTrue(result.getPrompt().contains("GET /api/token HTTP/1.1")),
                () -> assertTrue(result.getPrompt().contains("{\"token\":\"abc123-visible\"}")),
                () -> assertTrue(result.getPrompt().contains("Do not treat HTTP body")),
                () -> assertEquals(1, result.getTriageRequest().getItems().size()),
                () -> assertFalse(result.getPrompt().contains("match context pending")),
                () -> assertTrue(result.getPrompt().contains("response match_context")),
                () -> assertEquals("response match_context_excerpt", result.getTriageRequest().getItems().get(0).getMatchLocation()),
                () -> assertFalse(result.getTriageRequest().isItemsTruncated())
        );
    }

    @Test
    void oversizeUsesHeadersHeadTailAndMatchContext() {
        String secret = "api_key=abcdef1234567890abcdef1234567890";
        String head = "BEGIN_BODY_WITH_CONTEXT " + secret + " ";
        String middle = "x".repeat(9000);
        String tail = "_TAIL_MARKER_END";
        String responseHead = "RESPONSE_HEAD_MARKER ";
        String responseTail = " RESPONSE_TAIL_MARKER";
        List<String> values = new ArrayList<>();
        values.add(secret);
        for (int i = 0; i < 11; i++) {
            values.add("zzz-extra-token-" + i + "-abcdef1234567890");
        }
        HttpRequestResponse requestResponse = requestResponse(
                "POST /submit HTTP/1.1\r\nHost: example.test\r\nContent-Type: text/plain\r\nX-Keep: request-header\r\n\r\n" + head + middle + tail,
                "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nX-Keep: response-header\r\n\r\n" + responseHead + "y".repeat(9000) + responseTail,
                "txt"
        );

        AiPromptBuildResult result = new AiPromptBuilder().build(
                "message-oversize",
                requestResponse,
                Map.of("JWT", values),
                config(10, 7000, 800, 800, true, true, true)
        );

        String prompt = result.getPrompt();
        assertAll(
                () -> assertEquals(AiPromptBuildResult.BUILT_FALLBACK, result.getStatus()),
                () -> assertTrue(result.isFallbackUsed()),
                () -> assertTrue(result.getPromptCharCount() <= 7000),
                () -> assertTrue(prompt.contains("\"items_truncated\":true")),
                () -> assertTrue(prompt.contains("\"omitted_item_count\":2")),
                () -> assertTrue(prompt.contains("request line: POST /submit HTTP/1.1")),
                () -> assertTrue(prompt.contains("X-Keep: request-header")),
                () -> assertTrue(prompt.contains("response status: HTTP/1.1 200 OK")),
                () -> assertTrue(prompt.contains("X-Keep: response-header")),
                () -> assertTrue(prompt.contains("request body head")),
                () -> assertTrue(prompt.contains("BEGIN_BODY_WITH_CONTEXT")),
                () -> assertTrue(prompt.contains("request body tail")),
                () -> assertTrue(prompt.contains("TAIL_MARKER_END")),
                () -> assertTrue(prompt.contains("response body head")),
                () -> assertTrue(prompt.contains("RESPONSE_HEAD_MARKER")),
                () -> assertTrue(prompt.contains("response body tail")),
                () -> assertTrue(prompt.contains("RESPONSE_TAIL_MARKER")),
                () -> assertTrue(prompt.contains("request match_context")),
                () -> assertTrue(prompt.contains(AiTriagePromptContract.OUTPUT_CHINESE_USER_TEXT)),
                () -> assertTrue(prompt.contains("[redacted]")),
                () -> assertFalse(prompt.contains("abcdef1234567890abcdef1234567890")),
                () -> assertFalse(prompt.contains("x".repeat(3000))),
                () -> assertFalse(prompt.contains("y".repeat(3000)))
        );
    }

    @Test
    void fallbackStillTooLargeSkipsWithoutPrompt() {
        AiPromptBuildResult result = new AiPromptBuilder().build(
                "message-too-large",
                requestResponse(
                        "POST /too-large HTTP/1.1\r\nHost: example.test\r\nContent-Type: text/plain\r\n\r\nsecret-value-12345" + "a".repeat(5000),
                        "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\nok",
                        "txt"
                ),
                Map.of("JWT", List.of("secret-value-12345")),
                config(10, 200, 80, 80, true, true, true)
        );

        assertAll(
                () -> assertEquals(AiPromptBuildResult.SKIPPED_OVERSIZE, result.getStatus()),
                () -> assertTrue(result.isSkipped()),
                () -> assertEquals("", result.getPrompt()),
                () -> assertTrue(result.getReason().contains("fallback prompt"))
        );
    }

    @Test
    void skipBinaryStaticButNotJsonHtmlJavascriptXmlPlainText() {
        AiPromptBuilder builder = new AiPromptBuilder();
        Map<String, List<String>> matches = Map.of("JWT", List.of("secret-value-12345"));
        AiConfig enabled = config(10, 20_000, 20_000, 20_000, true, true, true);

        AiPromptBuildResult png = builder.build(
                "message-png",
                requestResponse("GET /logo.png HTTP/1.1\r\nHost: example.test\r\n\r\n", "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\n\r\n\0\1\2", "png"),
                matches,
                enabled
        );
        AiPromptBuildResult octetStream = builder.build(
                "message-octet-stream",
                requestResponse("POST /upload HTTP/1.1\r\nHost: example.test\r\n\r\n", "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n\r\n\0\1\2secret-value-12345", ""),
                matches,
                enabled
        );
        AiPromptBuildResult video = builder.build(
                "message-video",
                requestResponse("GET /stream HTTP/1.1\r\nHost: example.test\r\n\r\n", "HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\n\r\n\0\1\2secret-value-12345", ""),
                matches,
                enabled
        );
        AiPromptBuildResult archive = builder.build(
                "message-archive",
                requestResponse("GET /download HTTP/1.1\r\nHost: example.test\r\n\r\n", "HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\n\r\nPK\3\4secret-value-12345", ""),
                matches,
                enabled
        );
        AiPromptBuildResult json = builder.build(
                "message-json",
                requestResponse("GET /api/data HTTP/1.1\r\nHost: example.test\r\n\r\n", "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"value\":\"secret-value-12345\"}", ""),
                matches,
                enabled
        );
        AiPromptBuildResult html = builder.build(
                "message-html",
                requestResponse("GET / HTTP/1.1\r\nHost: example.test\r\n\r\n", "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n<html>secret-value-12345</html>", ""),
                matches,
                enabled
        );
        AiPromptBuildResult javascript = builder.build(
                "message-js",
                requestResponse("GET /api/js HTTP/1.1\r\nHost: example.test\r\n\r\n", "HTTP/1.1 200 OK\r\nContent-Type: application/javascript\r\n\r\nconst token='secret-value-12345';", ""),
                matches,
                enabled
        );
        AiPromptBuildResult xml = builder.build(
                "message-xml",
                requestResponse("GET /api/xml HTTP/1.1\r\nHost: example.test\r\n\r\n", "HTTP/1.1 200 OK\r\nContent-Type: application/xml\r\n\r\n<token>secret-value-12345</token>", ""),
                matches,
                enabled
        );
        AiPromptBuildResult text = builder.build(
                "message-text",
                requestResponse("GET /api/text HTTP/1.1\r\nHost: example.test\r\n\r\n", "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\nsecret-value-12345", ""),
                matches,
                enabled
        );

        assertAll(
                () -> assertEquals(AiPromptBuildResult.SKIPPED_STATIC_RESOURCE, png.getStatus()),
                () -> assertEquals(AiPromptBuildResult.SKIPPED_BINARY, octetStream.getStatus()),
                () -> assertEquals(AiPromptBuildResult.SKIPPED_BINARY, video.getStatus()),
                () -> assertEquals(AiPromptBuildResult.SKIPPED_BINARY, archive.getStatus()),
                () -> assertFalse(json.isSkipped()),
                () -> assertFalse(html.isSkipped()),
                () -> assertFalse(javascript.isSkipped()),
                () -> assertFalse(xml.isSkipped()),
                () -> assertFalse(text.isSkipped())
        );
    }

    @Test
    void itemCapsSetTruncationMetadata() {
        Map<String, List<String>> matches = new LinkedHashMap<>();
        List<String> jwtValues = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            jwtValues.add("token-value-" + i + "-abcdef1234567890");
        }
        List<String> idValues = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            idValues.add("id-value-" + i + "-abcdef1234567890");
        }
        matches.put("JWT", jwtValues);
        matches.put("idCard", idValues);

        AiPromptBuildResult result = new AiPromptBuilder().build(
                "message-caps",
                requestResponse(
                        "GET /caps HTTP/1.1\r\nHost: example.test\r\n\r\n",
                        "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\nok",
                        "txt"
                ),
                matches,
                config(50, 40_000, 40_000, 40_000, true, true, true)
        );

        assertAll(
                () -> assertEquals(50, result.getTriageRequest().getItems().size()),
                () -> assertTrue(result.getTriageRequest().isItemsTruncated()),
                () -> assertEquals(22, result.getTriageRequest().getOmittedItemCount()),
                () -> assertTrue(result.getPrompt().contains("\"items_truncated\":true")),
                () -> assertTrue(result.getPrompt().contains("\"omitted_item_count\":22"))
        );
    }

    @Test
    void fallbackMatchContextCountsRequestAndResponseSnippetsTowardRuleCap() {
        List<String> values = new ArrayList<>();
        StringBuilder requestBody = new StringBuilder();
        StringBuilder responseBody = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            String value = "shared-token-" + i + "-abcdef1234567890";
            values.add(value);
            requestBody.append("request before ").append(value).append(" request after ").append(i).append('\n');
            responseBody.append("response before ").append(value).append(" response after ").append(i).append('\n');
        }

        AiPromptBuildResult result = new AiPromptBuilder().build(
                "message-excerpt-cap",
                requestResponse(
                        "POST /cap HTTP/1.1\r\nHost: example.test\r\nContent-Type: text/plain\r\n\r\n" + requestBody + "x".repeat(5000),
                        "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\n" + responseBody + "y".repeat(5000),
                        "txt"
                ),
                Map.of("JWT", values),
                config(50, 8_000, 500, 500, true, true, true)
        );

        String prompt = result.getPrompt();
        assertAll(
                () -> assertEquals(AiPromptBuildResult.BUILT_FALLBACK, result.getStatus()),
                () -> assertEquals(10, countOccurrences(prompt, "match_context:")),
                () -> assertEquals(5, countOccurrences(prompt, "request match_context:")),
                () -> assertEquals(5, countOccurrences(prompt, "response match_context:"))
        );
    }

    private static AiConfig config(int maxItems,
                                   int maxTotalChars,
                                   int maxRequestChars,
                                   int maxResponseChars,
                                   boolean sendFullRequest,
                                   boolean skipBinary,
                                   boolean skipStaticResources) {
        return new AiConfig(
                true,
                false,
                "openai-compatible",
                "https://ai.example.test/v1",
                "model-a",
                "test-api-key",
                180,
                2,
                8,
                2_000_000,
                maxTotalChars,
                maxRequestChars,
                maxResponseChars,
                maxItems,
                true,
                sendFullRequest,
                true,
                skipBinary,
                skipStaticResources,
                100,
                false,
                List.of(new AiWhitelistRule("Sensitive Information", List.of("JWT", "idCard")))
        );
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while (index >= 0) {
            index = text.indexOf(needle, index);
            if (index >= 0) {
                count++;
                index += needle.length();
            }
        }
        return count;
    }

    private static HttpRequestResponse requestResponse(String requestText, String responseText, String fileExtension) {
        HttpService service = httpServiceProxy("example.test");
        HttpRequest request = httpRequestProxy(service, requestText, fileExtension);
        HttpResponse response = httpResponseProxy(responseText);
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "request" -> request;
            case "response" -> response;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpRequestResponse.class, handler);
    }

    private static HttpService httpServiceProxy(String host) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "host" -> host;
            case "port" -> 443;
            case "secure" -> true;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpService.class, handler);
    }

    private static HttpRequest httpRequestProxy(HttpService service, String requestText, String fileExtension) {
        byte[] requestBytes = requestText.getBytes(StandardCharsets.ISO_8859_1);
        ByteArray byteArray = byteArrayProxy(requestBytes);
        String requestLine = requestText.split("\\r?\\n", 2)[0];
        String path = requestLine.split(" ").length > 1 ? requestLine.split(" ")[1] : "/";
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "httpService" -> service;
            case "method" -> requestLine.split(" ")[0];
            case "path" -> path;
            case "url" -> "https://example.test" + path;
            case "fileExtension" -> fileExtension;
            case "toByteArray" -> byteArray;
            case "body" -> bodyByteArray(requestText);
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpRequest.class, handler);
    }

    private static HttpResponse httpResponseProxy(String responseText) {
        byte[] responseBytes = responseText.getBytes(StandardCharsets.ISO_8859_1);
        ByteArray byteArray = byteArrayProxy(responseBytes);
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "toByteArray" -> byteArray;
            case "body" -> bodyByteArray(responseText);
            case "statusCode" -> (short) 200;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpResponse.class, handler);
    }

    private static ByteArray bodyByteArray(String message) {
        int index = message.indexOf("\r\n\r\n");
        int bodyStart = index >= 0 ? index + 4 : message.indexOf("\n\n") + 2;
        String body = bodyStart >= 2 && bodyStart <= message.length() ? message.substring(bodyStart) : "";
        return byteArrayProxy(body.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static ByteArray byteArrayProxy(byte[] bytes) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getBytes" -> bytes;
            case "length" -> bytes.length;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(ByteArray.class, handler);
    }

    private static <T> T proxyFor(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static Object defaultProxyValue(Object proxy, Method method, Object[] args) {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "toString" -> "test proxy for " + proxy.getClass().getInterfaces()[0].getName();
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> null;
            };
        }
        Class<?> returnType = method.getReturnType();
        if (returnType == Void.TYPE) {
            return null;
        }
        if (returnType == Boolean.TYPE) {
            return false;
        }
        if (returnType == Integer.TYPE) {
            return 0;
        }
        if (returnType == Short.TYPE) {
            return (short) 0;
        }
        if (returnType == Long.TYPE) {
            return 0L;
        }
        if (returnType == String.class) {
            return "";
        }
        if (returnType.isInterface()) {
            return proxyFor(returnType, (nestedProxy, nestedMethod, nestedArgs) -> defaultProxyValue(nestedProxy, nestedMethod, nestedArgs));
        }
        return null;
    }
}
