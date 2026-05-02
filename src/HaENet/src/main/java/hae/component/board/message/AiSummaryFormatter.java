package hae.component.board.message;

import hae.storage.SqliteMessageStore;

public final class AiSummaryFormatter {
    private AiSummaryFormatter() {
    }

    public static AiSummaryDisplay display(SqliteMessageStore.AiTriageResultSummary summary) {
        if (summary == null) {
            return AiSummaryDisplay.empty();
        }
        return new AiSummaryDisplay(
                displayAiStatus(summary),
                displayAiVerdict(summary),
                formatConfidence(summary.getConfidence())
        );
    }

    public static AiSummaryDisplay displayTask(SqliteMessageStore.AiTriageTask task) {
        if (task == null) {
            return AiSummaryDisplay.empty();
        }
        String status = task.getStatus() == null ? "" : task.getStatus().trim();
        return switch (status) {
            case "PENDING" -> AiSummaryDisplay.pending();
            case "LEASED", "RUNNING" -> AiSummaryDisplay.running();
            case "FAILED" -> AiSummaryDisplay.failed(displayTaskFailure(task));
            default -> new AiSummaryDisplay(status, "", "-");
        };
    }

    public static String formatConfidence(double confidence) {
        return String.format(java.util.Locale.ROOT, "%.2f", confidence);
    }

    public static String displayAiStatus(SqliteMessageStore.AiTriageResultSummary summary) {
        if (summary.isEmptyAdvisoryResult()) {
            return "无有效结论";
        }
        if (summary.isLowQualityAdvisoryResult()) {
            return "低质量结论";
        }
        return safeText(summary.getStatus());
    }

    public static String displayAiVerdict(SqliteMessageStore.AiTriageResultSummary summary) {
        return summary.needsRetry() ? "建议重试" : localizeVerdict(summary.getOverallVerdict());
    }

    public static String displayAiRisk(SqliteMessageStore.AiTriageResultSummary summary) {
        return localizeRisk(summary.getOverallRiskLevel(), summary.getOverallVerdict());
    }

    public static String displayAiSummary(SqliteMessageStore.AiTriageResultSummary summary) {
        if (summary == null) {
            return "";
        }
        if (summary.isEmptyAdvisoryResult()) {
            return "AI 返回了空结论，建议重新分析。";
        }
        if (summary.isLowQualityAdvisoryResult()) {
            return "AI 返回了低质量结论，建议重新分析。";
        }
        String summaryText = safeText(summary.getSummary()).trim();
        if (summaryText.isEmpty()) {
            return "";
        }
        if (containsCjk(summaryText)) {
            return summaryText;
        }
        String verdict = safeText(summary.getOverallVerdict());
        if ("false_positive".equals(verdict)) {
            return "误报：命中文本不像真实敏感信息。";
        }
        if ("not_sensitive".equals(verdict)) {
            return "未发现敏感信息暴露。";
        }
        if ("security_signal_not_secret".equals(verdict)) {
            return "安全信号：需要关注，但不是直接的密钥或敏感值。";
        }
        return "AI 已给出英文摘要，请查看原始 AI 结果。";
    }

    private static String displayTaskFailure(SqliteMessageStore.AiTriageTask task) {
        String errorCode = safeText(task.getLastErrorCode()).trim();
        return switch (errorCode) {
            case "PARSE_FAILED" -> "解析失败";
            case "RATE_LIMIT", "RETRYABLE_AI_FAILURE" -> task.getNextAttemptAt() == Long.MAX_VALUE ? "重试耗尽" : "等待重试";
            case "PERMANENT_AUTH_CONFIG" -> "认证/配置失败";
            case "PERMANENT_CONFIG" -> "配置失败";
            case "PERMANENT_REQUEST" -> "请求失败";
            case "PROMPT_TOO_LARGE" -> "请求过大";
            case "MESSAGE_UNAVAILABLE" -> "消息不可用";
            default -> "建议重试";
        };
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    private static String localizeVerdict(String verdict) {
        return switch (verdict == null ? "" : verdict.trim()) {
            case "sensitive_exposure" -> "敏感信息暴露";
            case "sensitive_but_expected" -> "预期敏感信息";
            case "possible_sensitive" -> "疑似敏感信息";
            case "false_positive" -> "误报";
            case "not_sensitive" -> "未发现敏感信息";
            case "security_signal_not_secret" -> "安全信号（非密钥）";
            case "unknown" -> "未知";
            default -> safeDetail(verdict);
        };
    }

    private static String localizeRisk(String riskLevel, String verdict) {
        String normalizedRisk = riskLevel == null ? "" : riskLevel.trim();
        String normalizedVerdict = verdict == null ? "" : verdict.trim();
        if ("unknown".equals(normalizedRisk)
                && ("false_positive".equals(normalizedVerdict)
                || "not_sensitive".equals(normalizedVerdict)
                || "security_signal_not_secret".equals(normalizedVerdict))) {
            normalizedRisk = "info";
        }
        return switch (normalizedRisk) {
            case "critical" -> "严重";
            case "high" -> "高";
            case "medium" -> "中";
            case "low" -> "低";
            case "info" -> "信息";
            case "unknown" -> "未知";
            default -> safeDetail(riskLevel);
        };
    }

    private static boolean containsCjk(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(value.charAt(i));
            if (script == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }

    private static String safeDetail(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
