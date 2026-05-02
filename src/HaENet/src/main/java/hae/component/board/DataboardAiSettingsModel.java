package hae.component.board;

import hae.ai.AiConfig;
import hae.ai.AiQueueCounts;
import hae.ai.AiWhitelistRule;
import hae.utils.ConfigLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class DataboardAiSettingsModel {
    public static final String SENSITIVE_DATA_WARNING = "完整 HTTP 请求/响应可能包含 Cookie、Authorization、Token、个人信息和业务数据，启用后会发送到你配置的 AI API。";
    public static final String BURP_PROXY_UNSUPPORTED_WARNING = "v1 暂不支持 AIUseBurpProxy；选中该项时不允许保存。";
    public static final String OPERATOR_GUARDRAIL_NOTES = String.join(" ",
            "AI 默认关闭；只有正则处理成功且命中白名单规则的 HTTP 消息才会进入 AI 队列。",
            "v1 仅支持 HTTP；WebSocket AI 分析暂不支持，会被跳过。",
            "AI 结论仅作为辅助分诊提示，不代表漏洞可利用、数据泄露已确认或合规结论。",
            "默认客户端直连且不走代理，不使用 Burp Montoya HTTP send、Burp Proxy、Burp HTTP API 或系统代理。",
            "如果未来启用代理支持，AI API 调用可能出现在 Burp Logger/Proxy 中。",
            "保存到 Config.yml 的 API key 是明文；可使用 env:HAE_AI_API_KEY 只在运行时读取密钥。",
            "SQLite 只保存 AI 任务/结果元数据和 AI 响应 JSON，不保存完整 prompt 或重复的原始请求/响应负载。",
            "默认 AI 白名单排除了 Linkfinder、All URL 等噪声规则。"
    );
    private static final String MASKED_EMPTY_KEY = "（未设置）";
    private static final String MASKED_SHORT_KEY = "****";

    private boolean enabled;
    private boolean useBurpProxy;
    private String providerType;
    private String baseUrl;
    private String apiKey;
    private String model;
    private int requestTimeoutSeconds;
    private int concurrency;
    private int maxInFlightChars;
    private int maxTotalChars;
    private int maxRequestChars;
    private int maxResponseChars;
    private int maxQueueSize;
    private boolean sendFullRequest;
    private boolean sendFullResponse;
    private boolean skipBinary;
    private boolean skipStaticResources;
    private String whitelistGroup;
    private List<String> whitelistNames;
    private List<AiWhitelistRule> whitelistRules;
    private AiQueueCounts queueCounts;

    public DataboardAiSettingsModel() {
        this.providerType = hae.Config.AIProviderType;
        this.baseUrl = hae.Config.AIBaseUrl;
        this.apiKey = hae.Config.AIApiKey;
        this.model = hae.Config.AIModel;
        this.requestTimeoutSeconds = hae.Config.AIRequestTimeoutSeconds;
        this.concurrency = hae.Config.AIConcurrency;
        this.maxInFlightChars = hae.Config.AIMaxInFlightChars;
        this.maxTotalChars = hae.Config.AIMaxTotalChars;
        this.maxRequestChars = hae.Config.AIMaxRequestChars;
        this.maxResponseChars = hae.Config.AIMaxResponseChars;
        this.maxQueueSize = hae.Config.AIMaxQueueSize;
        this.sendFullRequest = hae.Config.AISendFullRequest;
        this.sendFullResponse = hae.Config.AISendFullResponse;
        this.skipBinary = hae.Config.AISkipBinary;
        this.skipStaticResources = hae.Config.AISkipStaticResources;
        this.whitelistGroup = hae.Config.AIWhitelistGroup;
        this.whitelistNames = new ArrayList<>(hae.Config.AIWhitelistNames);
        this.whitelistRules = List.of(new AiWhitelistRule(this.whitelistGroup, this.whitelistNames));
        this.queueCounts = AiQueueCounts.zero();
    }

    public static DataboardAiSettingsModel from(ConfigLoader configLoader, AiQueueCounts queueCounts) {
        Objects.requireNonNull(configLoader, "configLoader");
        AiConfig aiConfig = configLoader.getAiConfig();
        DataboardAiSettingsModel model = new DataboardAiSettingsModel();
        model.enabled = aiConfig.isEnabled();
        model.useBurpProxy = aiConfig.isUseBurpProxy();
        model.providerType = safe(aiConfig.getProviderType());
        model.baseUrl = safe(aiConfig.getBaseUrl());
        model.apiKey = safe(aiConfig.getApiKey());
        model.model = safe(aiConfig.getModel());
        model.requestTimeoutSeconds = aiConfig.getRequestTimeoutSeconds();
        model.concurrency = aiConfig.getConcurrency();
        model.maxInFlightChars = aiConfig.getMaxInFlightChars();
        model.maxTotalChars = aiConfig.getMaxTotalChars();
        model.maxRequestChars = aiConfig.getMaxRequestChars();
        model.maxResponseChars = aiConfig.getMaxResponseChars();
        model.maxQueueSize = aiConfig.getMaxQueueSize();
        model.sendFullRequest = aiConfig.isSendFullRequest();
        model.sendFullResponse = aiConfig.isSendFullResponse();
        model.skipBinary = aiConfig.isSkipBinary();
        model.skipStaticResources = aiConfig.isSkipStaticResources();
        model.queueCounts = queueCounts == null ? AiQueueCounts.zero() : queueCounts;
        model.applyWhitelist(aiConfig.getWhitelist());
        return model;
    }

    public SaveResult saveTo(ConfigLoader configLoader, boolean sensitiveWarningAcknowledged) {
        Objects.requireNonNull(configLoader, "configLoader");
        if (enabled && !sensitiveWarningAcknowledged) {
            return SaveResult.blocked("启用 AI 前必须先确认敏感数据提示。" + SENSITIVE_DATA_WARNING);
        }
        if (useBurpProxy) {
            return SaveResult.blocked(BURP_PROXY_UNSUPPORTED_WARNING);
        }

        configLoader.setAIEnabled(enabled);
        configLoader.setAIUseBurpProxy(false);
        configLoader.setAIProviderType(providerType);
        configLoader.setAIBaseUrl(baseUrl);
        configLoader.setAIApiKey(apiKey);
        configLoader.setAIModel(model);
        configLoader.setAIRequestTimeoutSeconds(requestTimeoutSeconds);
        configLoader.setAIConcurrency(concurrency);
        configLoader.setAIMaxInFlightChars(maxInFlightChars);
        configLoader.setAIMaxTotalChars(maxTotalChars);
        configLoader.setAIMaxRequestChars(maxRequestChars);
        configLoader.setAIMaxResponseChars(maxResponseChars);
        configLoader.setAIMaxQueueSize(maxQueueSize);
        configLoader.setAISendFullRequest(sendFullRequest);
        configLoader.setAISendFullResponse(sendFullResponse);
        configLoader.setAISkipBinary(skipBinary);
        configLoader.setAISkipStaticResources(skipStaticResources);
        syncPrimaryWhitelistRule();
        configLoader.setAIWhitelist(whitelistRules);
        return SaveResult.saved("AI 设置已保存。API key 显示：" + getMaskedApiKey());
    }

    public String getMaskedApiKey() {
        String trimmed = apiKey == null ? "" : apiKey.trim();
        if (trimmed.isEmpty()) {
            return MASKED_EMPTY_KEY;
        }
        if (trimmed.startsWith("env:") && trimmed.length() > "env:".length()) {
            return "env:" + MASKED_SHORT_KEY + trimmed.substring(Math.max("env:".length(), trimmed.length() - 4));
        }
        if (trimmed.length() <= 8) {
            return MASKED_SHORT_KEY;
        }
        return trimmed.substring(0, 2) + MASKED_SHORT_KEY + trimmed.substring(trimmed.length() - 4);
    }

    public String getQueueStatusText() {
        AiQueueCounts counts = queueCounts == null ? AiQueueCounts.zero() : queueCounts;
        return counts.toStatusText() + " API key=" + getMaskedApiKey();
    }

    public boolean whitelistExcludesNoisyDefaults() {
        if (containsNoisyWhitelistValue(whitelistGroup)) {
            return false;
        }
        for (String name : whitelistNames) {
            if (containsNoisyWhitelistValue(name)) {
                return false;
            }
        }
        return true;
    }

    private boolean containsNoisyWhitelistValue(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        return "linkfinder".equals(normalized) || "all url".equals(normalized);
    }

    private void applyWhitelist(List<AiWhitelistRule> whitelist) {
        List<AiWhitelistRule> safeRules = sanitizeWhitelistRules(whitelist);
        whitelistRules = safeRules;
        AiWhitelistRule first = safeRules.isEmpty()
                ? new AiWhitelistRule("", List.of())
                : safeRules.get(0);
        whitelistGroup = safe(first.getGroup());
        whitelistNames = new ArrayList<>(first.getNames());
    }

    private void syncPrimaryWhitelistRule() {
        AiWhitelistRule primaryRule = new AiWhitelistRule(whitelistGroup, whitelistNames);
        List<AiWhitelistRule> safeRules = sanitizeWhitelistRules(whitelistRules);
        List<AiWhitelistRule> updatedRules = new ArrayList<>();
        updatedRules.add(primaryRule);
        if (safeRules.size() > 1) {
            updatedRules.addAll(safeRules.subList(1, safeRules.size()));
        }
        whitelistRules = List.copyOf(updatedRules);
    }

    private static List<AiWhitelistRule> sanitizeWhitelistRules(List<AiWhitelistRule> whitelist) {
        if (whitelist == null || whitelist.isEmpty()) {
            return List.of();
        }
        List<AiWhitelistRule> safeRules = new ArrayList<>();
        for (AiWhitelistRule rule : whitelist) {
            if (rule == null) {
                continue;
            }
            AiWhitelistRule safeRule = new AiWhitelistRule(safe(rule.getGroup()), rule.getNames());
            if (!safeRule.getGroup().isBlank() || !safeRule.getNames().isEmpty()) {
                safeRules.add(safeRule);
            }
        }
        return List.copyOf(safeRules);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isUseBurpProxy() {
        return useBurpProxy;
    }

    public void setUseBurpProxy(boolean useBurpProxy) {
        this.useBurpProxy = useBurpProxy;
    }

    public String getProviderType() {
        return providerType;
    }

    public void setProviderType(String providerType) {
        this.providerType = safe(providerType);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = safe(baseUrl);
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = safe(apiKey);
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = safe(model);
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = Math.max(1, requestTimeoutSeconds);
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = Math.max(1, concurrency);
    }

    public int getMaxInFlightChars() {
        return maxInFlightChars;
    }

    public void setMaxInFlightChars(int maxInFlightChars) {
        this.maxInFlightChars = Math.max(1, maxInFlightChars);
    }

    public int getMaxTotalChars() {
        return maxTotalChars;
    }

    public void setMaxTotalChars(int maxTotalChars) {
        this.maxTotalChars = Math.max(1, maxTotalChars);
    }

    public int getMaxRequestChars() {
        return maxRequestChars;
    }

    public void setMaxRequestChars(int maxRequestChars) {
        this.maxRequestChars = Math.max(1, maxRequestChars);
    }

    public int getMaxResponseChars() {
        return maxResponseChars;
    }

    public void setMaxResponseChars(int maxResponseChars) {
        this.maxResponseChars = Math.max(1, maxResponseChars);
    }

    public int getMaxQueueSize() {
        return maxQueueSize;
    }

    public void setMaxQueueSize(int maxQueueSize) {
        this.maxQueueSize = Math.max(1, maxQueueSize);
    }

    public boolean isSendFullRequest() {
        return sendFullRequest;
    }

    public void setSendFullRequest(boolean sendFullRequest) {
        this.sendFullRequest = sendFullRequest;
    }

    public boolean isSendFullResponse() {
        return sendFullResponse;
    }

    public void setSendFullResponse(boolean sendFullResponse) {
        this.sendFullResponse = sendFullResponse;
    }

    public boolean isSkipBinary() {
        return skipBinary;
    }

    public void setSkipBinary(boolean skipBinary) {
        this.skipBinary = skipBinary;
    }

    public boolean isSkipStaticResources() {
        return skipStaticResources;
    }

    public void setSkipStaticResources(boolean skipStaticResources) {
        this.skipStaticResources = skipStaticResources;
    }

    public String getWhitelistGroup() {
        return whitelistGroup;
    }

    public void setWhitelistGroup(String whitelistGroup) {
        this.whitelistGroup = safe(whitelistGroup);
        syncPrimaryWhitelistRule();
    }

    public List<String> getWhitelistNames() {
        return List.copyOf(whitelistNames);
    }

    public void setWhitelistNames(List<String> whitelistNames) {
        this.whitelistNames = new ArrayList<>();
        if (whitelistNames == null) {
            syncPrimaryWhitelistRule();
            return;
        }
        for (String whitelistName : whitelistNames) {
            if (whitelistName != null && !whitelistName.isBlank()) {
                this.whitelistNames.add(whitelistName.trim());
            }
        }
        syncPrimaryWhitelistRule();
    }

    public List<AiWhitelistRule> getWhitelistRules() {
        syncPrimaryWhitelistRule();
        return List.copyOf(whitelistRules);
    }

    public void setWhitelistRules(List<AiWhitelistRule> whitelistRules) {
        applyWhitelist(whitelistRules);
    }

    public AiQueueCounts getQueueCounts() {
        return queueCounts;
    }

    public void setQueueCounts(AiQueueCounts queueCounts) {
        this.queueCounts = queueCounts == null ? AiQueueCounts.zero() : queueCounts;
    }

    public static final class SaveResult {
        private final boolean saved;
        private final String message;

        private SaveResult(boolean saved, String message) {
            this.saved = saved;
            this.message = message == null ? "" : message;
        }

        public static SaveResult saved(String message) {
            return new SaveResult(true, message);
        }

        public static SaveResult blocked(String message) {
            return new SaveResult(false, message);
        }

        public boolean isSaved() {
            return saved;
        }

        public String getMessage() {
            return message;
        }
    }
}
