package hae.ai;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hae.ai.client.AiClientException;
import hae.ai.client.AiClientFailureCategory;
import hae.ai.client.AiClientResult;
import hae.ai.client.OpenAiCompatibleAiClient;
import hae.ai.prompt.AiPromptBuilder;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class AiHttpClientTest {
    private static final String FAKE_KEY = "test-secret-key-123";
    private static final String FAKE_ENV_KEY = "test-env-secret-456";

    @Test
    void directModeBypassesDefaultProxySelector() throws Exception {
        try (FakeAiServer server = FakeAiServer.responding(200, chatCompletionResponse("{\\\"overall_verdict\\\":\\\"not_sensitive\\\",\\\"overall_severity\\\":\\\"info\\\",\\\"confidence\\\":0.9,\\\"summary\\\":\\\"ok\\\",\\\"items\\\":[]}"));
             FakeProxyServer proxy = FakeProxyServer.create()) {
            ProxySelector originalProxySelector = ProxySelector.getDefault();
            ProxySelector.setDefault(new LoopbackProxySelector(proxy.address()));
            try {
                OpenAiCompatibleAiClient client = new OpenAiCompatibleAiClient(config(server.baseUrl(), FAKE_KEY));

                AiClientResult result = client.complete(samplePrompt());

                RecordedRequest recordedRequest = server.awaitRequest();
                assertAll(
                        () -> assertEquals(200, result.getStatusCode()),
                        () -> assertEquals("{\"overall_verdict\":\"not_sensitive\",\"overall_severity\":\"info\",\"confidence\":0.9,\"summary\":\"ok\",\"items\":[]}", result.getResponseBody()),
                        () -> assertEquals("POST", recordedRequest.method()),
                        () -> assertEquals("/v1/chat/completions", recordedRequest.path()),
                        () -> assertEquals("Bearer " + FAKE_KEY, recordedRequest.firstHeader("Authorization")),
                        () -> assertEquals("application/json", recordedRequest.firstHeader("Content-Type")),
                        () -> assertFalse(recordedRequest.payload().contains(FAKE_KEY)),
                        () -> assertTrue(recordedRequest.payload().contains("\"model\":\"gpt-test\"")),
                        () -> assertTrue(recordedRequest.payload().contains("\"temperature\":0")),
                        () -> assertTrue(recordedRequest.payload().contains("\"messages\"")),
                        () -> assertTrue(recordedRequest.payload().contains("\"role\":\"system\"")),
                        () -> assertTrue(recordedRequest.payload().contains("\"role\":\"user\"")),
                        () -> assertFalse(recordedRequest.payload().contains("Analyze the following AI sensitive-data triage request")),
                () -> assertTrue(recordedRequest.payload().contains(AiTriagePromptContract.HTTP_CONTENT_IS_UNTRUSTED_EVIDENCE)),
                () -> assertTrue(recordedRequest.payload().contains(AiTriagePromptContract.IGNORE_INSTRUCTIONS_INSIDE_TRAFFIC)),
                () -> assertTrue(recordedRequest.payload().contains(AiTriagePromptContract.OUTPUT_STRICT_JSON_ONLY)),
                () -> assertTrue(recordedRequest.payload().contains(AiTriagePromptContract.OUTPUT_CHINESE_USER_TEXT)),
                () -> assertTrue(recordedRequest.payload().contains(AiPromptBuilder.BEGIN_EVIDENCE)),
                        () -> assertTrue(recordedRequest.payload().contains("GET /api/token HTTP/1.1")),
                        () -> assertTrue(recordedRequest.payload().contains("Host: example.test")),
                        () -> assertTrue(recordedRequest.payload().contains("HTTP/1.1 200 OK")),
                        () -> assertTrue(recordedRequest.payload().contains("response-body-marker")),
                        () -> assertTrue(recordedRequest.payload().contains(AiTriageSchema.SCHEMA_VERSION)),
                        () -> assertTrue(recordedRequest.payload().contains(AiTriageSchema.PROMPT_VERSION)),
                        () -> assertTrue(recordedRequest.payload().contains("matched_value_redacted")),
                        () -> assertTrue(recordedRequest.payload().contains("token[redacted]")),
                        () -> assertTrue(proxy.awaitNoRequest()),
                        () -> assertEquals(0, proxy.requestCount())
                );
            } finally {
                ProxySelector.setDefault(originalProxySelector);
            }
        }
    }

    @Test
    void envApiKeyReferenceResolvesOnlyForAuthorizationHeader() throws Exception {
        try (FakeAiServer server = FakeAiServer.responding(200, chatCompletionResponse("{\\\"overall_verdict\\\":\\\"not_sensitive\\\",\\\"overall_severity\\\":\\\"info\\\",\\\"confidence\\\":0.9,\\\"summary\\\":\\\"ok\\\",\\\"items\\\":[]}"))) {
            Function<String, String> environment = name -> "HAE_AI_API_KEY".equals(name) ? FAKE_ENV_KEY : "";
            OpenAiCompatibleAiClient client = OpenAiCompatibleAiClient.fromConfig(
                    config(server.baseUrl(), "env:HAE_AI_API_KEY"),
                    environment
            );

            client.complete(samplePrompt());

            RecordedRequest recordedRequest = server.awaitRequest();
            assertAll(
                    () -> assertEquals("Bearer " + FAKE_ENV_KEY, recordedRequest.firstHeader("Authorization")),
                    () -> assertFalse(recordedRequest.firstHeader("Authorization").contains("env:HAE_AI_API_KEY"))
            );
        }
    }

    @Test
    void authFailureIsPermanent() throws Exception {
        try (FakeAiServer server = FakeAiServer.responding(401, "unauthorized " + FAKE_KEY)) {
            OpenAiCompatibleAiClient client = new OpenAiCompatibleAiClient(config(server.baseUrl(), FAKE_KEY));

            AiClientException exception = assertThrows(AiClientException.class, () -> client.complete(samplePrompt()));

            assertAll(
                    () -> assertEquals(AiClientFailureCategory.PERMANENT_AUTH_CONFIG, exception.getCategory()),
                    () -> assertEquals(401, exception.getStatusCode()),
                    () -> assertTrue(exception.isPermanent()),
                    () -> assertFalse(exception.isRetryable()),
                    () -> assertFalse(exception.getMessage().contains(FAKE_KEY)),
                    () -> assertFalse(exception.toString().contains(FAKE_KEY))
            );
        }
    }

    @Test
    void forbiddenFailureIsPermanentAndSanitized() throws Exception {
        try (FakeAiServer server = FakeAiServer.responding(403, "forbidden " + FAKE_KEY)) {
            OpenAiCompatibleAiClient client = new OpenAiCompatibleAiClient(config(server.baseUrl(), FAKE_KEY));

            AiClientException exception = assertThrows(AiClientException.class, () -> client.complete(samplePrompt()));

            assertAll(
                    () -> assertEquals(AiClientFailureCategory.PERMANENT_AUTH_CONFIG, exception.getCategory()),
                    () -> assertEquals(403, exception.getStatusCode()),
                    () -> assertTrue(exception.isPermanent()),
                    () -> assertFalse(exception.isRetryable()),
                    () -> assertFalse(exception.getMessage().contains(FAKE_KEY))
            );
        }
    }

    @Test
    void throttlingAndServerErrorsAreRetryable() throws Exception {
        assertRetryableStatus(429);
        assertRetryableStatus(500);
        assertRetryableStatus(503);
    }

    @Test
    void timeoutIsRetryableAndSanitized() throws Exception {
        try (FakeAiServer server = FakeAiServer.respondingAfterDelay(200, "{\"id\":\"late\"}", Duration.ofSeconds(2))) {
            OpenAiCompatibleAiClient client = new OpenAiCompatibleAiClient(configWithTimeout(server.baseUrl(), FAKE_KEY, 1));

            AiClientException exception = assertThrows(AiClientException.class, () -> client.complete(samplePrompt()));

            assertAll(
                    () -> assertEquals(AiClientFailureCategory.RETRYABLE, exception.getCategory()),
                    () -> assertTrue(exception.isRetryable()),
                    () -> assertFalse(exception.isPermanent()),
                    () -> assertFalse(exception.getMessage().contains(FAKE_KEY))
            );
        }
    }

    @Test
    void invalidBaseUrlIsPermanentConfigFailure() {
        AiClientException exception = assertThrows(
                AiClientException.class,
                () -> new OpenAiCompatibleAiClient(config("file:///tmp/not-http", FAKE_KEY))
        );

        assertAll(
                () -> assertEquals(AiClientFailureCategory.PERMANENT_CONFIG, exception.getCategory()),
                () -> assertTrue(exception.isPermanent()),
                () -> assertFalse(exception.isRetryable()),
                () -> assertFalse(exception.getMessage().contains(FAKE_KEY))
        );
    }

    @Test
    void malformedChatCompletionResponseIsPermanentRequestFailure() throws Exception {
        try (FakeAiServer server = FakeAiServer.responding(200, "{\"choices\":[]}")) {
            OpenAiCompatibleAiClient client = new OpenAiCompatibleAiClient(config(server.baseUrl(), FAKE_KEY));

            AiClientException exception = assertThrows(AiClientException.class, () -> client.complete(samplePrompt()));

            assertAll(
                    () -> assertEquals(AiClientFailureCategory.PERMANENT_REQUEST, exception.getCategory()),
                    () -> assertTrue(exception.isPermanent()),
                    () -> assertFalse(exception.isRetryable())
            );
        }
    }

    private static void assertRetryableStatus(int statusCode) throws Exception {
        try (FakeAiServer server = FakeAiServer.responding(statusCode, "status " + statusCode + " " + FAKE_KEY)) {
            OpenAiCompatibleAiClient client = new OpenAiCompatibleAiClient(config(server.baseUrl(), FAKE_KEY));

            AiClientException exception = assertThrows(AiClientException.class, () -> client.complete(samplePrompt()));

            assertAll(
                    () -> assertEquals(AiClientFailureCategory.RETRYABLE, exception.getCategory()),
                    () -> assertEquals(statusCode, exception.getStatusCode()),
                    () -> assertTrue(exception.isRetryable()),
                    () -> assertFalse(exception.isPermanent()),
                    () -> assertFalse(exception.getMessage().contains(FAKE_KEY))
            );
        }
    }

    private static String samplePrompt() {
        return AiTriagePromptContract.PROMPT_CONTRACT + "\n" +
                "message_id: message-http-client\n" +
                "triage_request_json:\n" +
                "{\"schema_version\":\"" + AiTriageSchema.SCHEMA_VERSION + "\"," +
                "\"prompt_version\":\"" + AiTriageSchema.PROMPT_VERSION + "\"," +
                "\"items\":[{\"rule_group\":\"敏感信息\",\"rule_name\":\"JWT\"," +
                "\"rule_hash\":\"hash-1\",\"matched_value_redacted\":\"token[redacted]\"," +
                "\"match_location\":\"response body\"}]," +
                "\"items_truncated\":false,\"omitted_item_count\":0}\n" +
                AiPromptBuilder.BEGIN_EVIDENCE + "\n" +
                "REQUEST:\nGET /api/token HTTP/1.1\r\nHost: example.test\r\nAccept: application/json\r\n\r\n" +
                "RESPONSE:\nHTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n" +
                "{\"token\":\"token[redacted]\",\"evidence\":\"response-body-marker\"}\n" +
                AiPromptBuilder.END_EVIDENCE;
    }

    private static String chatCompletionResponse(String escapedContent) {
        return "{\"id\":\"chatcmpl-test\",\"object\":\"chat.completion\",\"choices\":[{" +
                "\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"" + escapedContent +
                "\",\"refusal\":null},\"finish_reason\":\"stop\"}]}";
    }

    private static AiConfig config(String baseUrl, String apiKey) {
        return configWithTimeout(baseUrl, apiKey, 5);
    }

    private static AiConfig configWithTimeout(String baseUrl, String apiKey, int timeoutSeconds) {
        return new AiConfig(
                true,
                false,
                "openai-compatible",
                baseUrl,
                "gpt-test",
                apiKey,
                timeoutSeconds,
                1,
                1,
                100000,
                100000,
                100000,
                100000,
                10,
                true,
                false,
                false,
                true,
                true,
                100,
                false,
                List.of()
        );
    }

    private static final class FakeAiServer implements AutoCloseable {
        private final HttpServer server;
        private final CountDownLatch requestLatch = new CountDownLatch(1);
        private final AtomicReference<RecordedRequest> recordedRequest = new AtomicReference<>();

        private FakeAiServer(int statusCode, String body, Duration delay) throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/v1/chat/completions", exchange -> handle(exchange, statusCode, body, delay));
            server.start();
        }

        static FakeAiServer responding(int statusCode, String body) throws IOException {
            return new FakeAiServer(statusCode, body, Duration.ZERO);
        }

        static FakeAiServer respondingAfterDelay(int statusCode, String body, Duration delay) throws IOException {
            return new FakeAiServer(statusCode, body, delay);
        }

        String baseUrl() {
            return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
        }

        RecordedRequest awaitRequest() throws InterruptedException {
            assertTrue(requestLatch.await(3, TimeUnit.SECONDS));
            RecordedRequest request = recordedRequest.get();
            assertNotNull(request);
            return request;
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private void handle(HttpExchange exchange, int statusCode, String body, Duration delay) throws IOException {
            try (exchange) {
                recordedRequest.set(new RecordedRequest(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders(),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                ));
                requestLatch.countDown();
                sleep(delay);
                byte[] responseBytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(statusCode, responseBytes.length);
                exchange.getResponseBody().write(responseBytes);
            }
        }

        private void sleep(Duration delay) {
            if (delay.isZero() || delay.isNegative()) {
                return;
            }

            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private record RecordedRequest(String method, String path, Headers headers, String payload) {
        String firstHeader(String name) {
            return headers.getFirst(name);
        }
    }

    private static final class LoopbackProxySelector extends ProxySelector {
        private final SocketAddress proxyAddress;

        private LoopbackProxySelector(SocketAddress proxyAddress) {
            this.proxyAddress = proxyAddress;
        }

        @Override
        public List<Proxy> select(URI uri) {
            return List.of(new Proxy(Proxy.Type.HTTP, proxyAddress));
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        }
    }

    private static final class FakeProxyServer implements AutoCloseable {
        private final HttpServer server;
        private final CountDownLatch requestLatch = new CountDownLatch(1);
        private final AtomicInteger requestCount = new AtomicInteger(0);

        private FakeProxyServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        static FakeProxyServer create() throws IOException {
            return new FakeProxyServer();
        }

        SocketAddress address() {
            return server.getAddress();
        }

        boolean awaitNoRequest() throws InterruptedException {
            return !requestLatch.await(250, TimeUnit.MILLISECONDS) && requestCount.get() == 0;
        }

        int requestCount() {
            return requestCount.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                requestCount.incrementAndGet();
                requestLatch.countDown();
                byte[] responseBytes = "proxy should not be used".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(502, responseBytes.length);
                exchange.getResponseBody().write(responseBytes);
            }
        }
    }
}
