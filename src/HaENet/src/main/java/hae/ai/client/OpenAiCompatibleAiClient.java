package hae.ai.client;

import hae.ai.AiConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Function;

public final class OpenAiCompatibleAiClient implements AiClient {
    private static final String PROVIDER_TYPE = "openai-compatible";
    private static final String ENV_PREFIX = "env:";

    private final URI endpointUri;
    private final String model;
    private final String apiKey;
    private final Duration timeout;
    private final HttpClient httpClient;

    public static OpenAiCompatibleAiClient fromConfig(AiConfig config) throws AiClientException {
        return fromConfig(config, System::getenv);
    }

    public static OpenAiCompatibleAiClient fromConfig(AiConfig config, Function<String, String> environmentLookup)
            throws AiClientException {
        return new OpenAiCompatibleAiClient(config, environmentLookup);
    }

    public OpenAiCompatibleAiClient(AiConfig config) throws AiClientException {
        this(config, System::getenv);
    }

    public OpenAiCompatibleAiClient(AiConfig config, Function<String, String> environmentLookup) throws AiClientException {
        requireConfig(config);
        requireProviderType(config.getProviderType());
        this.endpointUri = chatCompletionsUri(config.getBaseUrl());
        this.model = requireText(config.getModel(), "AI model is not configured.", AiClientFailureCategory.PERMANENT_CONFIG);
        this.apiKey = resolveApiKey(config.getApiKey(), environmentLookup);
        this.timeout = requestTimeout(config.getRequestTimeoutSeconds());
        // AI v1 calls the provider through direct no-proxy Java HTTP only; do not route this through Burp Montoya HTTP send.
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .proxy(DirectProxySelector.INSTANCE)
                .build();
    }

    @Override
    public AiClientResult complete(String prompt) throws AiClientException {
        if (prompt == null || prompt.isBlank()) {
            throw new AiClientException("AI prompt is required.", AiClientFailureCategory.PERMANENT_REQUEST);
        }

        String payload = OpenAiChatJson.chatCompletionPayload(model, prompt);
        HttpRequest httpRequest = HttpRequest.newBuilder(endpointUri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            int statusCode = response.statusCode();
            if (statusCode >= 200 && statusCode < 300) {
                return new AiClientResult(statusCode, OpenAiChatJson.extractAssistantContent(response.body()));
            }
            throw classifyStatus(statusCode);
        } catch (HttpTimeoutException e) {
            throw new AiClientException(
                    "AI provider request timed out.",
                    AiClientFailureCategory.RETRYABLE,
                    e
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiClientException("AI provider request was interrupted.", AiClientFailureCategory.RETRYABLE, e);
        } catch (IOException e) {
            throw new AiClientException(
                    "AI provider request failed with a retryable transport error.",
                    AiClientFailureCategory.RETRYABLE,
                    e
            );
        }
    }

    private static void requireConfig(AiConfig config) throws AiClientException {
        if (config == null) {
            throw new AiClientException("AI config is required.", AiClientFailureCategory.PERMANENT_CONFIG);
        }
    }

    private static void requireProviderType(String providerType) throws AiClientException {
        if (!PROVIDER_TYPE.equals(text(providerType).toLowerCase(Locale.ROOT))) {
            throw new AiClientException("AI provider type is not supported.", AiClientFailureCategory.PERMANENT_CONFIG);
        }
    }

    private static URI chatCompletionsUri(String baseUrl) throws AiClientException {
        String value = text(baseUrl);
        if (value.isBlank()) {
            throw new AiClientException("AI base URL is not configured.", AiClientFailureCategory.PERMANENT_CONFIG);
        }

        URI baseUri;
        try {
            baseUri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new AiClientException("AI base URL is invalid.", AiClientFailureCategory.PERMANENT_CONFIG, e);
        }

        if (!isHttpUri(baseUri) || baseUri.getHost() == null || baseUri.getUserInfo() != null ||
                baseUri.getQuery() != null || baseUri.getFragment() != null) {
            throw new AiClientException("AI base URL is invalid.", AiClientFailureCategory.PERMANENT_CONFIG);
        }

        String normalized = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        try {
            if (normalized.endsWith("/chat/completions")) {
                return URI.create(normalized);
            }
            if (normalized.endsWith("/v1")) {
                return URI.create(normalized + "/chat/completions");
            }
            return URI.create(normalized + "/v1/chat/completions");
        } catch (IllegalArgumentException e) {
            throw new AiClientException("AI base URL is invalid.", AiClientFailureCategory.PERMANENT_CONFIG, e);
        }
    }

    private static boolean isHttpUri(URI uri) {
        return "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
    }

    private static String resolveApiKey(String configuredApiKey, Function<String, String> environmentLookup)
            throws AiClientException {
        String configured = text(configuredApiKey);
        if (configured.startsWith(ENV_PREFIX)) {
            String variableName = configured.substring(ENV_PREFIX.length()).trim();
            if (variableName.isBlank()) {
                throw new AiClientException("AI API key environment reference is invalid.",
                        AiClientFailureCategory.PERMANENT_AUTH_CONFIG);
            }
            String resolved = environmentLookup == null ? "" : text(environmentLookup.apply(variableName));
            return requireText(resolved, "AI API key environment value is not configured.",
                    AiClientFailureCategory.PERMANENT_AUTH_CONFIG);
        }
        return requireText(configured, "AI API key is not configured.", AiClientFailureCategory.PERMANENT_AUTH_CONFIG);
    }

    private static Duration requestTimeout(int timeoutSeconds) throws AiClientException {
        if (timeoutSeconds <= 0) {
            throw new AiClientException("AI request timeout is invalid.", AiClientFailureCategory.PERMANENT_CONFIG);
        }
        return Duration.ofSeconds(timeoutSeconds);
    }

    private static String requireText(String value, String message, AiClientFailureCategory category)
            throws AiClientException {
        String text = text(value);
        if (text.isBlank()) {
            throw new AiClientException(message, category);
        }
        return text;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static AiClientException classifyStatus(int statusCode) {
        if (statusCode == 401 || statusCode == 403) {
            return new AiClientException(
                    "AI provider rejected authentication or configuration (HTTP " + statusCode + ").",
                    AiClientFailureCategory.PERMANENT_AUTH_CONFIG,
                    statusCode
            );
        }
        if (statusCode == 429 || statusCode >= 500) {
            return new AiClientException(
                    "AI provider returned retryable HTTP status " + statusCode + ".",
                    AiClientFailureCategory.RETRYABLE,
                    statusCode
            );
        }
        if (statusCode >= 400) {
            return new AiClientException(
                    "AI provider returned non-retryable HTTP status " + statusCode + ".",
                    AiClientFailureCategory.PERMANENT_REQUEST,
                    statusCode
            );
        }
        return new AiClientException(
                "AI provider returned unexpected HTTP status " + statusCode + ".",
                AiClientFailureCategory.PERMANENT_CONFIG,
                statusCode
        );
    }
}
