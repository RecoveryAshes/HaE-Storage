package hae;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Registration;
import burp.api.montoya.core.BurpSuiteEdition;
import burp.api.montoya.logging.Logging;
import hae.ai.AiTriageLifecycle;
import hae.component.board.DataboardAiSettingsController;
import hae.cache.DataCache;
import hae.component.Main;
import hae.component.board.message.MessageTableModel;
import hae.instances.editor.RequestEditor;
import hae.instances.editor.ResponseEditor;
import hae.instances.editor.WebSocketEditor;
import hae.instances.menu.DataboardContextMenuProvider;
import hae.instances.websocket.WebSocketMessageHandler;
import hae.storage.SqliteMessageStore;
import hae.utils.ConfigLoader;
import hae.utils.DataManager;

import java.util.concurrent.atomic.AtomicBoolean;

public class HaE implements BurpExtension {
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private Registration databoardContextMenuRegistration;
    private Registration unloadingHandlerRegistration;

    @Override
    public void initialize(MontoyaApi api) {
        if (!initialized.compareAndSet(false, true)) {
            return;
        }

        // 设置扩展名称
        api.extension().setName("HaE-Storage - Highlighter and Extractor");
        String version = "1.0.0";

        // 加载扩展后输出的项目信息
        Logging logging = api.logging();
        logging.logToOutput("[ HaE-Storage ]");
        logging.logToOutput("[#] Author: RecoveryAshes");
        logging.logToOutput("[#] Github: https://github.com/RecoveryAshes/HaE-Storage");
        logging.logToOutput("[#] Version: " + version);

        // 配置文件加载
        ConfigLoader configLoader = new ConfigLoader(api);

        SqliteMessageStore messageStore = new SqliteMessageStore(api, configLoader);
        AiTriageLifecycle aiTriageLifecycle = AiTriageLifecycle.startIfEnabled(api, configLoader, messageStore);
        DataboardAiSettingsController.WorkerControls aiWorkerControls = DataboardAiSettingsController.workerControls(aiTriageLifecycle);
        MessageTableModel messageTableModel = new MessageTableModel(
                api,
                configLoader,
                messageStore,
                messageStore,
                messageStore,
                messageStore
        );

        // 设置BurpSuite专业版状态
        Config.proVersionStatus = getBurpSuiteProStatus(api);

        // 注册Tab页（用于查询数据）
        api.userInterface().registerSuiteTab("HaE", new Main(api, configLoader, messageTableModel, aiWorkerControls));

        // 注册右键菜单（用于基于选中的 HTTP 消息打开 Scoped Databoard）
        registerDataboardContextMenu(api, configLoader, messageStore);

        // 注册WebSocket处理器
        api.proxy().registerWebSocketCreationHandler(proxyWebSocketCreation -> proxyWebSocketCreation.proxyWebSocket().registerProxyMessageHandler(new WebSocketMessageHandler(api, configLoader)));

        // 注册消息编辑框（用于展示数据）
        api.userInterface().registerHttpRequestEditorProvider(new RequestEditor(api, configLoader));
        api.userInterface().registerHttpResponseEditorProvider(new ResponseEditor(api, configLoader));
        api.userInterface().registerWebSocketMessageEditorProvider(new WebSocketEditor(api, configLoader));

        // 从BurpSuite里加载数据
        DataManager dataManager = new DataManager(api);
        dataManager.loadData(messageTableModel);

        AtomicBoolean cleanupExecuted = new AtomicBoolean(false);
        Runnable cleanupTask = () -> {
            if (!cleanupExecuted.compareAndSet(false, true)) {
                return;
            }

            try {
                aiTriageLifecycle.shutdown();
            } catch (Exception ignored) {
            }

            // 关闭Burp时清空所有数据（SQLite / PersistedData / 内存缓存）
            try {
                messageTableModel.clearAllDataOnShutdown();
            } catch (Exception ignored) {
            }

            try {
                dataManager.clearAllPersistedData();
            } catch (Exception ignored) {
            }

            try {
                Config.globalDataMap.clear();
                DataCache.clear();
            } catch (Exception ignored) {
            }

            try {
                deregisterDataboardContextMenu();
            } catch (Exception ignored) {
            }

            unloadingHandlerRegistration = null;
            initialized.set(false);
        };

        if (unloadingHandlerRegistration == null || !unloadingHandlerRegistration.isRegistered()) {
            unloadingHandlerRegistration = api.extension().registerUnloadingHandler(cleanupTask::run);
        }

        try {
            Runtime.getRuntime().addShutdownHook(new Thread(cleanupTask, "HaE-Shutdown-Cleanup"));
        } catch (Exception e) {
            logging.logToError("registerShutdownHook: " + e.getMessage());
        }
    }

    private void registerDataboardContextMenu(MontoyaApi api, ConfigLoader configLoader, SqliteMessageStore messageStore) {
        if (databoardContextMenuRegistration != null && databoardContextMenuRegistration.isRegistered()) {
            return;
        }

        databoardContextMenuRegistration = api.userInterface().registerContextMenuItemsProvider(
                new DataboardContextMenuProvider(api, configLoader, messageStore)
        );
    }

    private void deregisterDataboardContextMenu() {
        if (databoardContextMenuRegistration == null) {
            return;
        }

        if (databoardContextMenuRegistration.isRegistered()) {
            databoardContextMenuRegistration.deregister();
        }
        databoardContextMenuRegistration = null;
    }

    void registerDataboardContextMenuForTest(MontoyaApi api, ConfigLoader configLoader, SqliteMessageStore messageStore) {
        registerDataboardContextMenu(api, configLoader, messageStore);
    }

    void cleanupRegistrationsForTest() {
        deregisterDataboardContextMenu();
    }

    private Boolean getBurpSuiteProStatus(MontoyaApi api) {
        boolean burpSuiteProStatus = false;

        try {
            burpSuiteProStatus = api.burpSuite().version().edition() == BurpSuiteEdition.PROFESSIONAL;
        } catch (Exception ignored) {
        }

        return burpSuiteProStatus;
    }
}
