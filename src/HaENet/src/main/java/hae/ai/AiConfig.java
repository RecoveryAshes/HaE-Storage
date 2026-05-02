package hae.ai;

import java.util.List;

public class AiConfig {
    private final boolean enabled;
    private final boolean useBurpProxy;
    private final String providerType;
    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final int requestTimeoutSeconds;
    private final int concurrency;
    private final int maxConcurrency;
    private final int maxInFlightChars;
    private final int maxTotalChars;
    private final int maxRequestChars;
    private final int maxResponseChars;
    private final int maxItemsPerMessage;
    private final boolean analyzeOncePerMessage;
    private final boolean sendFullRequest;
    private final boolean sendFullResponse;
    private final boolean skipBinary;
    private final boolean skipStaticResources;
    private final int maxQueueSize;
    private final boolean saveFullPrompt;
    private final List<AiWhitelistRule> whitelist;

    public AiConfig(boolean enabled,
                    boolean useBurpProxy,
                    String providerType,
                    String baseUrl,
                    String model,
                    String apiKey,
                    int requestTimeoutSeconds,
                    int concurrency,
                    int maxConcurrency,
                    int maxInFlightChars,
                    int maxTotalChars,
                    int maxRequestChars,
                    int maxResponseChars,
                    int maxItemsPerMessage,
                    boolean analyzeOncePerMessage,
                    boolean sendFullRequest,
                    boolean sendFullResponse,
                    boolean skipBinary,
                    boolean skipStaticResources,
                    int maxQueueSize,
                    boolean saveFullPrompt,
                    List<AiWhitelistRule> whitelist) {
        this.enabled = enabled;
        this.useBurpProxy = useBurpProxy;
        this.providerType = providerType;
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        this.concurrency = concurrency;
        this.maxConcurrency = maxConcurrency;
        this.maxInFlightChars = maxInFlightChars;
        this.maxTotalChars = maxTotalChars;
        this.maxRequestChars = maxRequestChars;
        this.maxResponseChars = maxResponseChars;
        this.maxItemsPerMessage = maxItemsPerMessage;
        this.analyzeOncePerMessage = analyzeOncePerMessage;
        this.sendFullRequest = sendFullRequest;
        this.sendFullResponse = sendFullResponse;
        this.skipBinary = skipBinary;
        this.skipStaticResources = skipStaticResources;
        this.maxQueueSize = maxQueueSize;
        this.saveFullPrompt = saveFullPrompt;
        this.whitelist = List.copyOf(whitelist);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isUseBurpProxy() {
        return useBurpProxy;
    }

    public String getProviderType() {
        return providerType;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getModel() {
        return model;
    }

    public String getApiKey() {
        return apiKey;
    }

    public boolean isApiKeyEnvironmentReference() {
        return apiKey != null && apiKey.startsWith("env:") && apiKey.length() > "env:".length();
    }

    public String getApiKeyEnvironmentVariableName() {
        return isApiKeyEnvironmentReference() ? apiKey.substring("env:".length()) : "";
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public int getMaxInFlightChars() {
        return maxInFlightChars;
    }

    public int getMaxTotalChars() {
        return maxTotalChars;
    }

    public int getMaxRequestChars() {
        return maxRequestChars;
    }

    public int getMaxResponseChars() {
        return maxResponseChars;
    }

    public int getMaxItemsPerMessage() {
        return maxItemsPerMessage;
    }

    public boolean isAnalyzeOncePerMessage() {
        return analyzeOncePerMessage;
    }

    public boolean isSendFullRequest() {
        return sendFullRequest;
    }

    public boolean isSendFullResponse() {
        return sendFullResponse;
    }

    public boolean isSkipBinary() {
        return skipBinary;
    }

    public boolean isSkipStaticResources() {
        return skipStaticResources;
    }

    public int getMaxQueueSize() {
        return maxQueueSize;
    }

    public boolean isSaveFullPrompt() {
        return saveFullPrompt;
    }

    public List<AiWhitelistRule> getWhitelist() {
        return whitelist;
    }
}
