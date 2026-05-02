package hae.component.board;

import hae.ai.AiQueueCounts;
import hae.ai.AiTriageEnqueueService;
import hae.ai.AiTriageLifecycle;
import hae.ai.worker.AiTriageWorker;
import hae.component.board.message.MessageTableModel;
import hae.repository.AiTaskRepository;
import hae.utils.ConfigLoader;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DataboardAiSettingsController {
    private final ConfigLoader configLoader;
    private final AiTaskRepository taskRepository;
    private final WorkerControls workerControls;
    private final MessageTableModel messageTableModel;
    private final Executor executor;
    private final ExecutorService ownedExecutorService;

    public DataboardAiSettingsController(ConfigLoader configLoader, AiTaskRepository taskRepository) {
        this(configLoader, taskRepository, null, newDaemonExecutor(), true);
    }

    public DataboardAiSettingsController(ConfigLoader configLoader,
                                         AiTaskRepository taskRepository,
                                         WorkerControls workerControls,
                                         Executor executor) {
        this(configLoader, taskRepository, workerControls, executor, false);
    }

    public DataboardAiSettingsController(ConfigLoader configLoader,
                                         AiTaskRepository taskRepository,
                                         WorkerControls workerControls,
                                         MessageTableModel messageTableModel,
                                         Executor executor) {
        this(configLoader, taskRepository, workerControls, messageTableModel, executor, false);
    }

    private DataboardAiSettingsController(ConfigLoader configLoader,
                                          AiTaskRepository taskRepository,
                                          WorkerControls workerControls,
                                          Executor executor,
                                          boolean ownsExecutor) {
        this(configLoader, taskRepository, workerControls, null, executor, ownsExecutor);
    }

    private DataboardAiSettingsController(ConfigLoader configLoader,
                                          AiTaskRepository taskRepository,
                                          WorkerControls workerControls,
                                          MessageTableModel messageTableModel,
                                          Executor executor,
                                          boolean ownsExecutor) {
        this.configLoader = Objects.requireNonNull(configLoader, "configLoader");
        this.taskRepository = taskRepository;
        this.workerControls = workerControls;
        this.messageTableModel = messageTableModel;
        ExecutorService createdExecutor = executor == null ? newDaemonExecutor() : null;
        this.executor = createdExecutor == null ? executor : createdExecutor;
        this.ownedExecutorService = createdExecutor != null
                ? createdExecutor
                : ownsExecutor && executor instanceof ExecutorService executorService ? executorService : null;
    }

    public static WorkerControls workerControls(AiTriageWorker worker) {
        if (worker == null) {
            return null;
        }
        return new WorkerControls() {
            @Override
            public void pause() {
                worker.pause();
            }

            @Override
            public void resume() {
                worker.resume();
            }

            @Override
            public String statusSummary() {
                return String.format(
                        "status=%s concurrency=%d active=%d inFlightChars=%d",
                        worker.getStatus(),
                        worker.getConfiguredConcurrency(),
                        worker.getActiveWorkerCount(),
                        worker.getInFlightChars()
                );
            }
        };
    }

    public static WorkerControls workerControls(AiTriageLifecycle lifecycle) {
        if (lifecycle == null) {
            return null;
        }
        return new WorkerControls() {
            @Override
            public void pause() {
                AiTriageWorker worker = lifecycle.getWorker();
                if (worker != null) {
                    worker.pause();
                }
            }

            @Override
            public void resume() {
                AiTriageWorker worker = lifecycle.getWorker();
                if (worker != null) {
                    worker.resume();
                }
            }

            @Override
            public void reconcileAfterSettingsSave() {
                lifecycle.reconcileWithCurrentConfig();
            }

            @Override
            public String statusSummary() {
                return lifecycle.statusSummary();
            }
        };
    }

    public DataboardAiSettingsModel loadModel() {
        return DataboardAiSettingsModel.from(configLoader, loadQueueCounts());
    }

    public List<String> loadAvailableRuleNames() {
        Set<String> normalizedNames = new TreeSet<>();
        List<String> ruleNames = new ArrayList<>();
        synchronized (hae.Config.globalRules) {
            for (Object[][] rules : hae.Config.globalRules.values()) {
                if (rules == null) {
                    continue;
                }
                for (Object[] rule : rules) {
                    if (rule == null || rule.length < 2 || rule[1] == null) {
                        continue;
                    }
                    String ruleName = rule[1].toString().trim();
                    if (ruleName.isBlank()) {
                        continue;
                    }
                    String normalized = ruleName.toLowerCase(Locale.ROOT);
                    if (normalizedNames.add(normalized)) {
                        ruleNames.add(ruleName);
                    }
                }
            }
        }
        ruleNames.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(ruleNames);
    }

    public DataboardAiSettingsModel.SaveResult saveSettings(DataboardAiSettingsModel model, boolean sensitiveWarningAcknowledged) {
        Objects.requireNonNull(model, "model");
        DataboardAiSettingsModel.SaveResult result = model.saveTo(configLoader, sensitiveWarningAcknowledged);
        if (!result.isSaved() || workerControls == null) {
            return result;
        }
        try {
            workerControls.reconcileAfterSettingsSave();
            return DataboardAiSettingsModel.SaveResult.saved(result.getMessage() + " Worker：" + workerControls.statusSummary());
        } catch (RuntimeException e) {
            return DataboardAiSettingsModel.SaveResult.saved(result.getMessage() + " Worker 更新失败：" + safeMessage(e));
        }
    }

    public CompletableFuture<ActionResult> refreshQueueStatusAsync() {
        return CompletableFuture.supplyAsync(() -> {
            AiQueueCounts counts = loadQueueCounts();
            return ActionResult.success(counts.toStatusText());
        }, executor);
    }

    public CompletableFuture<ActionResult> pauseWorkerAsync() {
        if (workerControls == null) {
            return CompletableFuture.completedFuture(ActionResult.unsupported("AI worker 尚未接入生命周期控制。"));
        }
        return CompletableFuture.supplyAsync(() -> {
            workerControls.pause();
            return ActionResult.success("AI worker 已暂停。" + workerControls.statusSummary());
        }, executor);
    }

    public CompletableFuture<ActionResult> resumeWorkerAsync() {
        if (workerControls == null) {
            return CompletableFuture.completedFuture(ActionResult.unsupported("AI worker 尚未接入生命周期控制。"));
        }
        return CompletableFuture.supplyAsync(() -> {
            workerControls.resume();
            return ActionResult.success("AI worker 已继续。" + workerControls.statusSummary());
        }, executor);
    }

    public CompletableFuture<ActionResult> clearPendingAsync() {
        if (taskRepository == null) {
            return CompletableFuture.completedFuture(ActionResult.unsupported("AI 队列存储不可用。"));
        }
        return CompletableFuture.supplyAsync(() -> {
            int cleared = taskRepository.clearPendingAiTriageTasks();
            return ActionResult.success("已清空待处理 AI 任务：" + cleared);
        }, executor);
    }

    public CompletableFuture<ActionResult> retryFailedAsync() {
        if (taskRepository == null) {
            return CompletableFuture.completedFuture(ActionResult.unsupported("AI 队列存储不可用。"));
        }
        return CompletableFuture.supplyAsync(() -> {
            int retried = taskRepository.retryFailedAiTriageTasks(System.currentTimeMillis());
            return ActionResult.success("已重试失败 AI 任务：" + retried);
        }, executor);
    }

    public CompletableFuture<ActionResult> analyzeSelectedAsync() {
        if (messageTableModel == null) {
            return CompletableFuture.completedFuture(ActionResult.unsupported("当前页面没有可分析的选中历史记录。"));
        }
        return CompletableFuture.supplyAsync(() -> toAnalyzeSelectedResult(messageTableModel.enqueueSelectedAiTriage()), executor);
    }

    public boolean isAnalyzeSelectedSupported() {
        return messageTableModel != null;
    }

    private ActionResult toAnalyzeSelectedResult(AiTriageEnqueueService.EnqueueResult result) {
        if (result == null) {
            return ActionResult.unsupported("AI 分析入队失败：未知错误");
        }

        String status = result.getStatus();
        if ("enqueued".equals(status)) {
            return ActionResult.success("已加入 AI 分析队列，命中白名单规则数：" + result.getMatchCount());
        }
        if ("duplicate".equals(status)) {
            return ActionResult.success("该历史记录已经在 AI 队列或已有 AI 结果中，不重复入队。");
        }
        if ("skipped_disabled".equals(status)) {
            return ActionResult.unsupported("AI 未启用，请先保存并启用 AI 设置。");
        }
        if ("skipped_no_whitelisted_match".equals(status)) {
            return ActionResult.unsupported("选中历史记录没有命中当前 AI 白名单规则。");
        }
        if ("skipped_invalid_config".equals(status)) {
            return ActionResult.unsupported("AI 配置不完整，请检查 Base URL、模型和 API key。");
        }
        if ("skipped_queue_full".equals(status)) {
            return ActionResult.unsupported("AI 队列已满，请稍后再试或清理队列。");
        }
        if ("skipped_unsupported_message_type".equals(status)) {
            return ActionResult.unsupported("选中历史记录不是支持的 HTTP 请求/响应。");
        }
        return ActionResult.unsupported("AI 分析入队失败：" + result.getReason());
    }

    private AiQueueCounts loadQueueCounts() {
        if (taskRepository == null) {
            return AiQueueCounts.zero();
        }
        return taskRepository.loadAiQueueCounts();
    }

    private static ExecutorService newDaemonExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "HaE-AI-Settings-Control");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static String safeMessage(Exception exception) {
        if (exception == null || exception.getMessage() == null || exception.getMessage().isBlank()) {
            return "unknown";
        }
        return exception.getMessage();
    }

    public void shutdown() {
        if (ownedExecutorService != null) {
            ownedExecutorService.shutdownNow();
        }
    }

    boolean isClosedForTest() {
        return ownedExecutorService != null && ownedExecutorService.isShutdown();
    }

    public interface WorkerControls {
        void pause();

        void resume();

        default void reconcileAfterSettingsSave() {
        }

        String statusSummary();
    }

    public static final class ActionResult {
        private final boolean supported;
        private final boolean success;
        private final String message;
        private final boolean ranOnEdt;

        private ActionResult(boolean supported, boolean success, String message) {
            this.supported = supported;
            this.success = success;
            this.message = message == null ? "" : message;
            this.ranOnEdt = SwingUtilities.isEventDispatchThread();
        }

        public static ActionResult success(String message) {
            return new ActionResult(true, true, message);
        }

        public static ActionResult unsupported(String message) {
            return new ActionResult(false, false, message);
        }

        public boolean isSupported() {
            return supported;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public boolean isRanOnEdt() {
            return ranOnEdt;
        }
    }
}
