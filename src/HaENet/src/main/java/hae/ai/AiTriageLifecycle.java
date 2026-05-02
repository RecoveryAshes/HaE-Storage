package hae.ai;

import burp.api.montoya.MontoyaApi;
import hae.ai.client.AiClient;
import hae.ai.client.AiClientException;
import hae.ai.client.OpenAiCompatibleAiClient;
import hae.ai.parser.AiTextSanitizer;
import hae.ai.parser.AiVerdictParser;
import hae.ai.prompt.AiPromptBuilder;
import hae.ai.worker.AiTriageWorker;
import hae.ai.worker.AiTriageWorkerConfig;
import hae.ai.worker.RepositoryAiTriageMessageContextLoader;
import hae.storage.SqliteMessageStore;
import hae.utils.ConfigLoader;

import java.util.Objects;

public final class AiTriageLifecycle implements AutoCloseable {
    private final MontoyaApi api;
    private final ConfigLoader configLoader;
    private final SqliteMessageStore messageStore;
    private volatile AiTriageWorker worker;
    private volatile String disabledReason;

    private AiTriageLifecycle(MontoyaApi api,
                              ConfigLoader configLoader,
                              SqliteMessageStore messageStore,
                              AiTriageWorker worker,
                              String disabledReason) {
        this.api = api;
        this.configLoader = configLoader;
        this.messageStore = messageStore;
        this.worker = worker;
        this.disabledReason = disabledReason == null ? "" : disabledReason;
    }

    public static AiTriageLifecycle startIfEnabled(MontoyaApi api,
                                                   ConfigLoader configLoader,
                                                   SqliteMessageStore messageStore) {
        Objects.requireNonNull(configLoader, "configLoader");
        Objects.requireNonNull(messageStore, "messageStore");
        AiTriageLifecycle lifecycle = new AiTriageLifecycle(api, configLoader, messageStore, null, "AI disabled");
        lifecycle.reconcileWithCurrentConfig();
        return lifecycle;
    }

    static AiTriageLifecycle startIfEnabled(MontoyaApi api,
                                            AiConfig aiConfig,
                                            SqliteMessageStore messageStore,
                                            AiTriageWorkerConfig workerConfig) {
        Objects.requireNonNull(messageStore, "messageStore");
        if (aiConfig == null || !aiConfig.isEnabled()) {
            return disabled("AI disabled");
        }

        try {
            return start(aiConfig, messageStore, OpenAiCompatibleAiClient.fromConfig(aiConfig), workerConfig);
        } catch (AiClientException | RuntimeException e) {
            logStartupFailure(api, e);
            return disabled("AI config is invalid");
        }
    }

    static AiTriageLifecycle start(AiConfig aiConfig,
                                   SqliteMessageStore messageStore,
                                   AiClient aiClient,
                                   AiTriageWorkerConfig workerConfig) {
        if (aiConfig == null || !aiConfig.isEnabled()) {
            return disabled("AI disabled");
        }
        Objects.requireNonNull(messageStore, "messageStore");
        Objects.requireNonNull(aiClient, "aiClient");
        return new AiTriageLifecycle(null, null, messageStore, createWorker(aiConfig, messageStore, aiClient, workerConfig), "");
    }

    private static AiTriageWorker createWorker(AiConfig aiConfig,
                                               SqliteMessageStore messageStore,
                                               AiClient aiClient,
                                               AiTriageWorkerConfig workerConfig) {
        return new AiTriageWorker(
                messageStore,
                messageStore,
                new RepositoryAiTriageMessageContextLoader(messageStore),
                new AiPromptBuilder(),
                new AiVerdictParser(),
                aiClient,
                aiConfig,
                workerConfig == null ? AiTriageWorkerConfig.fromAiConfig(aiConfig) : workerConfig
        );
    }

    private static AiTriageLifecycle disabled(String reason) {
        return new AiTriageLifecycle(null, null, null, null, reason);
    }

    private static void logStartupFailure(MontoyaApi api, Exception exception) {
        if (api == null || exception == null) {
            return;
        }
        try {
            String message = AiTextSanitizer.redactSecrets(exception.getMessage());
            api.logging().logToError("startAiTriageWorker: " + message);
        } catch (Exception ignored) {
        }
    }

    public boolean isStarted() {
        return worker != null;
    }

    public String getDisabledReason() {
        return disabledReason;
    }

    public AiTriageWorker getWorker() {
        return worker;
    }

    public synchronized void reconcileWithCurrentConfig() {
        if (configLoader == null || messageStore == null) {
            return;
        }

        AiConfig aiConfig;
        try {
            aiConfig = configLoader.getAiConfig();
        } catch (RuntimeException e) {
            replaceWorker(null, "AI config is invalid");
            logStartupFailure(api, e);
            return;
        }

        if (aiConfig == null || !aiConfig.isEnabled()) {
            replaceWorker(null, "AI disabled");
            return;
        }

        replaceWorker(null, "AI restarting");
        try {
            AiTriageWorker newWorker = createWorker(
                    aiConfig,
                    messageStore,
                    OpenAiCompatibleAiClient.fromConfig(aiConfig),
                    AiTriageWorkerConfig.fromAiConfig(aiConfig)
            );
            this.worker = newWorker;
            this.disabledReason = "";
        } catch (AiClientException | RuntimeException e) {
            this.worker = null;
            this.disabledReason = "AI config is invalid";
            logStartupFailure(api, e);
        }
    }

    public String statusSummary() {
        AiTriageWorker currentWorker = worker;
        if (currentWorker == null) {
            return "status=DISABLED reason=" + getDisabledReason();
        }
        return String.format(
                "status=%s concurrency=%d active=%d inFlightChars=%d",
                currentWorker.getStatus(),
                currentWorker.getConfiguredConcurrency(),
                currentWorker.getActiveWorkerCount(),
                currentWorker.getInFlightChars()
        );
    }

    private void replaceWorker(AiTriageWorker newWorker, String reason) {
        AiTriageWorker previousWorker = this.worker;
        this.worker = newWorker;
        this.disabledReason = reason == null ? "" : reason;
        if (previousWorker != null && previousWorker != newWorker) {
            previousWorker.shutdown();
        }
    }

    public void shutdown() {
        if (worker != null) {
            worker.shutdown();
        }
    }

    @Override
    public void close() {
        shutdown();
    }
}
