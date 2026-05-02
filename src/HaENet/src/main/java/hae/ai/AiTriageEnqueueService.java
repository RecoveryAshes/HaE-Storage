package hae.ai;

import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import hae.repository.AiTaskRepository;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class AiTriageEnqueueService {
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final String SKIPPED_DISABLED = "skipped_disabled";
    private static final String SKIPPED_INVALID_CONFIG = "skipped_invalid_config";
    private static final String SKIPPED_NO_WHITELISTED_MATCH = "skipped_no_whitelisted_match";
    private static final String SKIPPED_QUEUE_FULL = "skipped_queue_full";
    private static final String SKIPPED_UNSUPPORTED_MESSAGE_TYPE = "skipped_unsupported_message_type";
    private static final String ENQUEUED = "enqueued";
    private static final String DUPLICATE = "duplicate";
    private static final String FAILED = "failed";

    private final AiTaskRepository taskRepository;

    public AiTriageEnqueueService(AiTaskRepository taskRepository) {
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
    }

    public EnqueueResult enqueueAfterRegexPersistence(String messageId,
                                                      String contentHash,
                                                      HttpRequestResponse requestResponse,
                                                      Map<String, List<String>> extractedDataByRule,
                                                      AiConfig config) {
        if (messageId == null || messageId.isBlank()) {
            return EnqueueResult.skipped(SKIPPED_UNSUPPORTED_MESSAGE_TYPE, "missing message id");
        }

        if (config == null || !config.isEnabled()) {
            return EnqueueResult.skipped(SKIPPED_DISABLED, "AI disabled");
        }

        if (!isQueueConfigValid(config)) {
            return EnqueueResult.skipped(SKIPPED_INVALID_CONFIG, "AI config is incomplete");
        }

        if (!isSupportedHttpMessage(requestResponse)) {
            return EnqueueResult.skipped(SKIPPED_UNSUPPORTED_MESSAGE_TYPE, "unsupported message type");
        }

        List<WhitelistedMatch> whitelistedMatches = whitelistedMatches(extractedDataByRule, config.getWhitelist());
        if (whitelistedMatches.isEmpty()) {
            return EnqueueResult.skipped(SKIPPED_NO_WHITELISTED_MATCH, "no whitelisted matches");
        }

        int activeTaskCount;
        try {
            activeTaskCount = taskRepository.countActiveAiTriageTasks();
        } catch (Exception e) {
            return EnqueueResult.failed("AI queue status unavailable");
        }
        int remainingQueueCapacity = config.getMaxQueueSize() <= 0 ? Integer.MAX_VALUE : config.getMaxQueueSize() - activeTaskCount;
        if (remainingQueueCapacity <= 0) {
            return EnqueueResult.skipped(SKIPPED_QUEUE_FULL, "AI queue full");
        }

        HttpRequest request = requestResponse.request();
        String normalizedMethod = normalizeMethod(request);
        String normalizedHost = normalizeHost(request);
        String normalizedPath = normalizePath(request);
        String safeContentHash = safeString(contentHash);
        String configHash = hash(canonicalConfig(config));
        String firstAnalysisKey = "";
        String firstMatchSignatureHash = "";
        int insertedCount = 0;
        int duplicateCount = 0;

        try {
            for (WhitelistedMatch match : whitelistedMatches) {
                String matchSignatureHash = AiTriageTargetSignature.matchSignatureHash(match.ruleName(), match.value());
                String analysisKey = analysisKey(
                        messageId,
                        normalizedMethod,
                        normalizedHost,
                        normalizedPath,
                        safeContentHash,
                        matchSignatureHash,
                        AiTriageSchema.SCHEMA_VERSION,
                        AiTriageSchema.PROMPT_VERSION,
                        config.getModel(),
                        configHash
                );
                if (firstAnalysisKey.isBlank()) {
                    firstAnalysisKey = analysisKey;
                    firstMatchSignatureHash = matchSignatureHash;
                }

                if (config.isAnalyzeOncePerMessage() && taskRepository.hasBlockingAiTriageForTarget(messageId, matchSignatureHash)) {
                    duplicateCount++;
                    continue;
                }
                if (insertedCount >= remainingQueueCapacity) {
                    continue;
                }

                boolean inserted = taskRepository.enqueueAiTriageTask(
                        UUID.randomUUID().toString(),
                        messageId,
                        safeContentHash,
                        analysisKey,
                        matchSignatureHash,
                        AiTriageSchema.SCHEMA_VERSION,
                        AiTriageSchema.PROMPT_VERSION,
                        config.getModel(),
                        configHash,
                        1,
                        DEFAULT_MAX_ATTEMPTS,
                        0L
                );
                if (inserted) {
                    insertedCount++;
                } else {
                    duplicateCount++;
                }
            }
        } catch (Exception e) {
            return EnqueueResult.failed("AI enqueue unavailable");
        }

        if (insertedCount > 0) {
            return EnqueueResult.enqueued(firstAnalysisKey, firstMatchSignatureHash, configHash, insertedCount, whitelistedMatches.size(), duplicateCount);
        }
        return EnqueueResult.duplicate(firstAnalysisKey, firstMatchSignatureHash, configHash, whitelistedMatches.size());
    }

    private boolean isQueueConfigValid(AiConfig config) {
        return !isBlank(config.getBaseUrl())
                && !isBlank(config.getModel())
                && !isBlank(config.getApiKey())
                && config.getMaxQueueSize() > 0;
    }

    private boolean isSupportedHttpMessage(HttpRequestResponse requestResponse) {
        if (requestResponse == null) {
            return false;
        }

        // AI v1 is HTTP-only; WebSocket and request-only messages are skipped because triage needs a persisted response.
        try {
            HttpRequest request = requestResponse.request();
            HttpResponse response = requestResponse.response();
            return request != null && response != null;
        } catch (Exception e) {
            return false;
        }
    }

    private List<WhitelistedMatch> whitelistedMatches(Map<String, List<String>> extractedDataByRule,
                                                      List<AiWhitelistRule> whitelist) {
        if (extractedDataByRule == null || extractedDataByRule.isEmpty() || whitelist == null || whitelist.isEmpty()) {
            return List.of();
        }

        Map<String, String> whitelistedRules = whitelistedRuleGroups(whitelist);
        Map<String, WhitelistedMatch> uniqueMatches = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : extractedDataByRule.entrySet()) {
            String ruleName = normalizeRuleName(entry.getKey());
            String ruleGroup = whitelistedRules.get(ruleName.toLowerCase(Locale.ROOT));
            if (ruleGroup == null) {
                continue;
            }

            List<String> values = entry.getValue();
            if (values == null || values.isEmpty()) {
                continue;
            }

            for (String value : values) {
                if (isBlank(value)) {
                    continue;
                }
                WhitelistedMatch match = new WhitelistedMatch(ruleGroup, ruleName, value.trim());
                uniqueMatches.putIfAbsent(match.canonicalKey(), match);
            }
        }

        List<WhitelistedMatch> matches = new ArrayList<>(uniqueMatches.values());
        matches.sort(Comparator.comparing(WhitelistedMatch::canonicalKey));
        return matches;
    }

    private Map<String, String> whitelistedRuleGroups(List<AiWhitelistRule> whitelist) {
        Map<String, String> whitelistedRules = new LinkedHashMap<>();
        for (AiWhitelistRule rule : whitelist) {
            if (rule == null) {
                continue;
            }

            String group = safeString(rule.getGroup()).trim();
            for (String name : rule.getNames()) {
                String normalizedName = normalizeRuleName(name);
                if (!normalizedName.isBlank()) {
                    whitelistedRules.putIfAbsent(normalizedName.toLowerCase(Locale.ROOT), group);
                }
            }
        }
        return whitelistedRules;
    }

    private String normalizeRuleName(String ruleName) {
        return ruleName == null ? "" : ruleName.trim();
    }

    private String normalizeMethod(HttpRequest request) {
        try {
            String method = request.method();
            return method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    private String normalizeHost(HttpRequest request) {
        String host = "";
        try {
            HttpService service = request.httpService();
            if (service != null) {
                host = safeString(service.host());
            }
        } catch (Exception ignored) {
        }

        if (host.isBlank()) {
            try {
                URI uri = URI.create(request.url());
                host = safeString(uri.getHost());
            } catch (Exception ignored) {
            }
        }

        return host.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizePath(HttpRequest request) {
        String path = "";
        try {
            path = safeString(request.path());
        } catch (Exception ignored) {
        }

        if (path.isBlank()) {
            try {
                URI uri = URI.create(request.url());
                path = safeString(uri.getRawPath());
                String query = uri.getRawQuery();
                if (!isBlank(query)) {
                    path = path + "?" + query;
                }
            } catch (Exception ignored) {
            }
        }

        if (path.isBlank()) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private String canonicalConfig(AiConfig config) {
        return String.join("\n",
                safeString(config.getProviderType()),
                safeString(config.getBaseUrl()),
                safeString(config.getModel()),
                String.valueOf(config.getRequestTimeoutSeconds()),
                String.valueOf(config.getMaxItemsPerMessage()),
                String.valueOf(config.isSendFullRequest()),
                String.valueOf(config.isSendFullResponse()),
                String.valueOf(config.isSkipBinary()),
                String.valueOf(config.isSkipStaticResources()),
                String.valueOf(config.getMaxQueueSize()),
                canonicalWhitelist(config.getWhitelist())
        );
    }

    private String canonicalWhitelist(List<AiWhitelistRule> whitelist) {
        List<String> entries = new ArrayList<>();
        for (AiWhitelistRule rule : whitelist) {
            if (rule == null) {
                continue;
            }
            for (String name : rule.getNames()) {
                String normalizedName = normalizeRuleName(name);
                if (!normalizedName.isBlank()) {
                    entries.add(safeString(rule.getGroup()).trim() + ":" + normalizedName);
                }
            }
        }
        entries.sort(String::compareTo);
        return String.join("|", entries);
    }

    private String analysisKey(String messageId,
                               String normalizedMethod,
                               String normalizedHost,
                               String normalizedPath,
                               String contentHash,
                               String matchSignatureHash,
                               String schemaVersion,
                               String promptVersion,
                               String model,
                               String configHash) {
        return hash(String.join("\n",
                safeString(messageId),
                safeString(normalizedMethod),
                safeString(normalizedHost),
                safeString(normalizedPath),
                safeString(contentHash),
                safeString(matchSignatureHash),
                safeString(schemaVersion),
                safeString(promptVersion),
                safeString(model),
                safeString(configHash)
        ));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String safeString(String value) {
        return value == null ? "" : value;
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(safeString(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hashed.length * 2);
            for (byte current : hashed) {
                builder.append(String.format("%02x", current));
            }
            return builder.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private record WhitelistedMatch(String ruleGroup, String ruleName, String value) {
        private String canonicalKey() {
            return ruleGroup + "\n" + ruleName + "\n" + value;
        }
    }

    public static final class EnqueueResult {
        private final String status;
        private final String analysisKey;
        private final String matchSignatureHash;
        private final String configHash;
        private final int matchCount;
        private final int targetCount;
        private final int duplicateCount;
        private final String reason;

        private EnqueueResult(String status,
                              String analysisKey,
                              String matchSignatureHash,
                              String configHash,
                              int matchCount,
                              int targetCount,
                              int duplicateCount,
                              String reason) {
            this.status = status;
            this.analysisKey = safeString(analysisKey);
            this.matchSignatureHash = safeString(matchSignatureHash);
            this.configHash = safeString(configHash);
            this.matchCount = Math.max(0, matchCount);
            this.targetCount = Math.max(0, targetCount);
            this.duplicateCount = Math.max(0, duplicateCount);
            this.reason = safeString(reason);
        }

        private static EnqueueResult skipped(String status, String reason) {
            return new EnqueueResult(status, "", "", "", 0, 0, 0, reason);
        }

        private static EnqueueResult enqueued(String analysisKey,
                                              String matchSignatureHash,
                                              String configHash,
                                              int matchCount,
                                              int targetCount,
                                              int duplicateCount) {
            return new EnqueueResult(ENQUEUED, analysisKey, matchSignatureHash, configHash, matchCount, targetCount, duplicateCount, "");
        }

        private static EnqueueResult duplicate(String analysisKey, String matchSignatureHash, String configHash, int matchCount) {
            return new EnqueueResult(DUPLICATE, analysisKey, matchSignatureHash, configHash, matchCount, matchCount, matchCount, "already queued");
        }

        public static EnqueueResult failed(String reason) {
            return new EnqueueResult(FAILED, "", "", "", 0, 0, 0, reason);
        }

        public String getStatus() {
            return status;
        }

        public String getAnalysisKey() {
            return analysisKey;
        }

        public String getMatchSignatureHash() {
            return matchSignatureHash;
        }

        public String getConfigHash() {
            return configHash;
        }

        public int getMatchCount() {
            return matchCount;
        }

        public int getTargetCount() {
            return targetCount;
        }

        public int getDuplicateCount() {
            return duplicateCount;
        }

        public String getReason() {
            return reason;
        }

        public boolean isEnqueued() {
            return ENQUEUED.equals(status);
        }
    }
}
