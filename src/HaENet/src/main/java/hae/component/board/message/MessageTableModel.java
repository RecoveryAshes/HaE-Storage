package hae.component.board.message;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Annotations;
import burp.api.montoya.core.HighlightColor;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.UserInterface;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import hae.Config;
import hae.ai.AiTriageEnqueueService;
import hae.ai.AiTriageTargetSignature;
import hae.ai.AiWhitelistRules;
import hae.repository.AiResultRepository;
import hae.instances.http.utils.MessageProcessor;
import hae.repository.AiTaskRepository;
import hae.repository.ExtractedDataRepository;
import hae.repository.MessageRepository;
import hae.repository.RegexWorkRepository;
import hae.repository.StorageMaintenanceRepository;
import hae.storage.SqliteMessageStore;
import hae.utils.ConfigLoader;
import hae.utils.http.HttpUtils;
import hae.utils.string.StringProcessor;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static burp.api.montoya.ui.editor.EditorOptions.READ_ONLY;

public class MessageTableModel extends AbstractTableModel {
    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int REGEX_WORKER_COUNT = 2;
    private static final int REGEX_QUEUE_CAPACITY = 10000;
    private static final int PENDING_ANNOTATION_CAPACITY = 10000;
    private static final int PENDING_RECOVERY_BATCH_SIZE = 200;
    private static final long PENDING_RECOVERY_IDLE_MILLIS = 5000L;
    private static final int BASE_COLUMN_COUNT = 6;
    private static final double TABLE_DETAIL_INITIAL_HEIGHT_RATIO = 0.50;

    private final MontoyaApi api;
    private final ConfigLoader configLoader;
    private final MessageRepository messageRepository;
    private final AiResultRepository aiResultRepository;
    private final AiTaskRepository aiTaskRepository;
    private final RegexWorkRepository regexWorkRepository;
    private final ExtractedDataRepository extractedDataRepository;
    private final StorageMaintenanceRepository storageMaintenanceRepository;
    private final MessageProcessor messageProcessor;
    private final AiTriageEnqueueService aiTriageEnqueueService;
    private final HttpUtils httpUtils;
    private final MessageTable messageTable;
    private final JTextArea aiDetailArea;
    private final JSplitPane splitPane;
    private final JSplitPane tableAndMessagePane;
    private final LinkedList<MessageEntry> pageLog;

    private final JButton previousPageButton = new JButton("<");
    private final JButton nextPageButton = new JButton(">");
    private final JLabel pageInfoLabel = new JLabel("Page 1/1 · Rows 0-0/0");
    private final JComboBox<Integer> pageSizeComboBox = new JComboBox<>(new Integer[]{50, 100, 200, 500, 1000});

    private int currentPage = 1;
    private int pageSize = DEFAULT_PAGE_SIZE;
    private int totalRows = 0;

    private String currentHostFilter = "*";
    private String currentCommentFilter = "";
    private String currentMessageTable = "";
    private String currentMessageFilter = "";

    private SwingWorker<Void, Void> currentWorker;
    private SwingWorker<PageQueryResult, Void> pageWorker;
    private final AtomicInteger queryVersion = new AtomicInteger(0);
    private final AtomicLong selectionGeneration = new AtomicLong(0L);
    private final BlockingQueue<String> regexQueue = new LinkedBlockingQueue<>(REGEX_QUEUE_CAPACITY);
    private final Set<String> queuedRegexMessageIds = ConcurrentHashMap.newKeySet();
    private final ExecutorService regexExecutorService;
    private final AtomicBoolean regexWorkerRunning = new AtomicBoolean(true);
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
    private final AtomicBoolean pageRefreshQueued = new AtomicBoolean(false);
    private final Map<String, Annotations> pendingAnnotations = Collections.synchronizedMap(new LinkedHashMap<>());

    private static class PageQueryResult {
        private final List<MessageEntry> entries;
        private final int totalRows;
        private final int currentPage;

        private PageQueryResult(List<MessageEntry> entries, int totalRows, int currentPage) {
            this.entries = entries;
            this.totalRows = totalRows;
            this.currentPage = currentPage;
        }
    }

    private static class RequestMetadata {
        private final String url;
        private final String host;
        private final String urlParseError;

        private RequestMetadata(String url, String host, String urlParseError) {
            this.url = url;
            this.host = host;
            this.urlParseError = urlParseError;
        }
    }

    private record AiTargetSnapshot(String ruleName, String value, String matchSignatureHash) {
        private boolean exact() {
            return !ruleName.isBlank() && !value.isBlank() && !matchSignatureHash.isBlank();
        }

        private static AiTargetSnapshot empty() {
            return new AiTargetSnapshot("", "", "");
        }

        private static AiTargetSnapshot exact(String ruleName, String value) {
            String normalizedRuleName = ruleName == null ? "" : ruleName.trim();
            String normalizedValue = value == null ? "" : value.trim();
            if (normalizedRuleName.isEmpty() || normalizedValue.isEmpty()
                    || "*".equals(normalizedRuleName) || "*".equals(normalizedValue)) {
                return empty();
            }
            return new AiTargetSnapshot(
                    normalizedRuleName,
                    normalizedValue,
                    AiTriageTargetSignature.matchSignatureHash(normalizedRuleName, normalizedValue)
            );
        }
    }

    public MessageTableModel(MontoyaApi api,
                             ConfigLoader configLoader,
                             MessageRepository messageRepository,
                             RegexWorkRepository regexWorkRepository,
                             ExtractedDataRepository extractedDataRepository,
                             StorageMaintenanceRepository storageMaintenanceRepository) {
        this(api, configLoader, messageRepository, regexWorkRepository, extractedDataRepository,
                storageMaintenanceRepository, defaultAiTriageEnqueueService(messageRepository), true);
    }

    MessageTableModel(MontoyaApi api,
                      ConfigLoader configLoader,
                      MessageRepository messageRepository,
                      RegexWorkRepository regexWorkRepository,
                      ExtractedDataRepository extractedDataRepository,
                      StorageMaintenanceRepository storageMaintenanceRepository,
                      boolean startRegexWorkers) {
        this(api, configLoader, messageRepository, regexWorkRepository, extractedDataRepository,
                storageMaintenanceRepository, defaultAiTriageEnqueueService(messageRepository), startRegexWorkers);
    }

    MessageTableModel(MontoyaApi api,
                      ConfigLoader configLoader,
                      MessageRepository messageRepository,
                      RegexWorkRepository regexWorkRepository,
                      ExtractedDataRepository extractedDataRepository,
                      StorageMaintenanceRepository storageMaintenanceRepository,
                      AiTriageEnqueueService aiTriageEnqueueService,
                      boolean startRegexWorkers) {
        this.api = Objects.requireNonNull(api, "api");
        this.configLoader = Objects.requireNonNull(configLoader, "configLoader");
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository");
        this.aiResultRepository = messageRepository instanceof AiResultRepository resultRepository ? resultRepository : null;
        this.aiTaskRepository = messageRepository instanceof AiTaskRepository taskRepository ? taskRepository : null;
        this.regexWorkRepository = Objects.requireNonNull(regexWorkRepository, "regexWorkRepository");
        this.extractedDataRepository = Objects.requireNonNull(extractedDataRepository, "extractedDataRepository");
        this.storageMaintenanceRepository = Objects.requireNonNull(storageMaintenanceRepository, "storageMaintenanceRepository");
        this.messageProcessor = new MessageProcessor(api, configLoader);
        this.aiTriageEnqueueService = aiTriageEnqueueService;
        this.httpUtils = new HttpUtils(api, configLoader);
        this.pageLog = new LinkedList<>();
        this.regexExecutorService = Executors.newFixedThreadPool(REGEX_WORKER_COUNT);

        UserInterface userInterface = api.userInterface();
        HttpRequestEditor requestViewer = userInterface.createHttpRequestEditor(READ_ONLY);
        HttpResponseEditor responseViewer = userInterface.createHttpResponseEditor(READ_ONLY);
        JSplitPane messagePane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        messagePane.setLeftComponent(requestViewer.uiComponent());
        messagePane.setRightComponent(responseViewer.uiComponent());
        messagePane.setResizeWeight(0.5);
        aiDetailArea = new JTextArea("选中一条消息后显示 AI 分析详情。", 8, 32);
        aiDetailArea.setEditable(false);
        aiDetailArea.setLineWrap(true);
        aiDetailArea.setWrapStyleWord(true);
        JScrollPane aiDetailScrollPane = new JScrollPane(aiDetailArea);
        aiDetailScrollPane.setBorder(BorderFactory.createTitledBorder("AI 详情"));

        messageTable = new MessageTable(this, requestViewer, responseViewer);
        messageTable.setDefaultRenderer(Object.class, new MessageRenderer(pageLog, messageTable));
        messageTable.setAutoCreateRowSorter(true);

        TableRowSorter<TableModel> sorter = getDefaultTableModelTableRowSorter();
        messageTable.setRowSorter(sorter);
        messageTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);

        tableAndMessagePane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        JScrollPane scrollPane = new JScrollPane(messageTable);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        JPanel tablePanel = new JPanel(new BorderLayout());
        tablePanel.add(scrollPane, BorderLayout.CENTER);
        tablePanel.add(createPaginationPanel(), BorderLayout.SOUTH);
        tablePanel.setMinimumSize(new Dimension(0, 180));
        messagePane.setMinimumSize(new Dimension(0, 220));
        tableAndMessagePane.setLeftComponent(tablePanel);
        tableAndMessagePane.setRightComponent(messagePane);
        tableAndMessagePane.setResizeWeight(TABLE_DETAIL_INITIAL_HEIGHT_RATIO);
        tableAndMessagePane.addComponentListener(new ComponentAdapter() {
            private boolean dividerInitialized;

            @Override
            public void componentResized(ComponentEvent e) {
                initializeTableAndMessageDivider(this);
            }

            private void initializeTableAndMessageDivider(ComponentAdapter listener) {
                if (dividerInitialized || tableAndMessagePane.getHeight() <= 0) {
                    return;
                }
                dividerInitialized = true;
                tableAndMessagePane.setDividerLocation(TABLE_DETAIL_INITIAL_HEIGHT_RATIO);
                tableAndMessagePane.removeComponentListener(listener);
            }
        });
        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tableAndMessagePane, aiDetailScrollPane);
        splitPane.setResizeWeight(0.86);

        updatePaginationControls();
        if (startRegexWorkers) {
            startRegexWorkers();
        }
    }

    private static AiTriageEnqueueService defaultAiTriageEnqueueService(MessageRepository messageRepository) {
        if (messageRepository instanceof AiTaskRepository aiTaskRepository) {
            return new AiTriageEnqueueService(aiTaskRepository);
        }
        return null;
    }

    private JPanel createPaginationPanel() {
        JPanel paginationPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        paginationPanel.add(new JLabel("Page size:"));
        pageSizeComboBox.setSelectedItem(pageSize);
        paginationPanel.add(pageSizeComboBox);
        paginationPanel.add(previousPageButton);
        paginationPanel.add(nextPageButton);
        paginationPanel.add(pageInfoLabel);

        previousPageButton.addActionListener(e -> {
            if (currentPage > 1) {
                currentPage--;
                loadPageFromCurrentState(false);
            }
        });

        nextPageButton.addActionListener(e -> {
            int totalPages = calculateTotalPages(totalRows);
            if (currentPage < totalPages) {
                currentPage++;
                loadPageFromCurrentState(false);
            }
        });

        pageSizeComboBox.addActionListener(e -> {
            Object selectedItem = pageSizeComboBox.getSelectedItem();
            if (selectedItem instanceof Integer selectedPageSize) {
                pageSize = selectedPageSize;
                currentPage = 1;
                loadPageFromCurrentState(true);
            }
        });

        return paginationPanel;
    }

    public void loadPersistedMessages() {
        resetMessageFilterState();
        loadPageFromCurrentState(true);
    }

    private int calculateTotalPages(int rowCount) {
        return Math.max(1, (rowCount + pageSize - 1) / pageSize);
    }

    private void applyPageEntries(List<MessageEntry> entries) {
        Runnable uiUpdate = () -> {
            synchronized (pageLog) {
                pageLog.clear();
                pageLog.addAll(entries);
            }
            fireTableDataChanged();
            messageTable.lastSelectedIndex = -1;
            selectionGeneration.incrementAndGet();
            refreshSelectionAfterPageLoad();
        };

        if (SwingUtilities.isEventDispatchThread()) {
            uiUpdate.run();
        } else {
            SwingUtilities.invokeLater(uiUpdate);
        }
    }

    private void refreshSelectionAfterPageLoad() {
        if (pageLog.isEmpty()) {
            aiDetailArea.setText("暂无 AI 结果。");
            return;
        }
        if (messageTable.getSelectedRow() < 0) {
            messageTable.changeSelection(0, 0, false, false);
            return;
        }
        messageTable.refreshSelectedMessageAsync();
    }

    private void updatePaginationControls() {
        int totalPages = calculateTotalPages(totalRows);
        currentPage = Math.max(1, Math.min(currentPage, totalPages));

        int startRow = 0;
        int endRow = 0;
        if (totalRows > 0) {
            startRow = (currentPage - 1) * pageSize + 1;
            endRow = Math.min(currentPage * pageSize, totalRows);
        }

        int finalStartRow = startRow;
        int finalEndRow = endRow;
        Runnable uiUpdate = () -> {
            previousPageButton.setEnabled(currentPage > 1);
            nextPageButton.setEnabled(currentPage < totalPages);
            pageInfoLabel.setText(String.format("Page %d/%d · Rows %d-%d/%d", currentPage, totalPages, finalStartRow, finalEndRow, totalRows));
        };

        if (SwingUtilities.isEventDispatchThread()) {
            uiUpdate.run();
        } else {
            SwingUtilities.invokeLater(uiUpdate);
        }
    }

    private List<MessageEntry> toMessageEntries(List<SqliteMessageStore.MessageMetadata> metadataList, AiTargetSnapshot aiTarget) {
        List<MessageEntry> entries = new ArrayList<>(metadataList.size());
        for (SqliteMessageStore.MessageMetadata metadata : metadataList) {
            entries.add(new MessageEntry(
                    metadata.getMessageId(),
                    metadata.getMethod(),
                    metadata.getUrl(),
                    metadata.getComment(),
                    metadata.getLength(),
                    metadata.getColor(),
                    metadata.getStatus(),
                    metadata.getContentHash(),
                    aiTarget.exact() ? aiTarget.ruleName() : "",
                    aiTarget.exact() ? aiTarget.value() : "",
                    aiTarget.exact() ? aiTarget.matchSignatureHash() : ""
            ));
        }
        return entries;
    }

    private void loadPageFromCurrentState(boolean resetToFirstPage) {
        if (resetToFirstPage) {
            currentPage = 1;
        }

        loadPageFromDatabase();
    }

    private void loadPageFromDatabase() {
        if (shutdownRequested.get()) {
            return;
        }
        if (pageWorker != null && !pageWorker.isDone()) {
            pageWorker.cancel(true);
        }

        int requestId = queryVersion.incrementAndGet();
        int requestedPage = currentPage;
        int requestedPageSize = pageSize;
        String hostFilter = currentHostFilter;
        String commentFilter = currentCommentFilter;
        String messageTableFilter = currentMessageTable;
        String messageValueFilter = currentMessageFilter;
        AiTargetSnapshot aiTarget = AiTargetSnapshot.exact(messageTableFilter, messageValueFilter);

        pageWorker = new SwingWorker<>() {
            @Override
            protected PageQueryResult doInBackground() {
                if (shutdownRequested.get()) {
                    return new PageQueryResult(Collections.emptyList(), 0, 1);
                }
                int count = messageRepository.countMessageMetadata(hostFilter, commentFilter, messageTableFilter, messageValueFilter);
                int totalPages = Math.max(1, (count + requestedPageSize - 1) / requestedPageSize);
                int safePage = Math.max(1, Math.min(requestedPage, totalPages));
                int offset = (safePage - 1) * requestedPageSize;
                List<SqliteMessageStore.MessageMetadata> metadata = messageRepository.loadMessageMetadataPage(hostFilter, commentFilter, messageTableFilter, messageValueFilter, requestedPageSize, offset);
                return new PageQueryResult(toMessageEntries(metadata, aiTarget), count, safePage);
            }

            @Override
            protected void done() {
                if (isCancelled() || shutdownRequested.get() || requestId != queryVersion.get()) {
                    return;
                }

                try {
                    PageQueryResult result = get();
                    totalRows = result.totalRows;
                    currentPage = result.currentPage;
                    applyPageEntries(result.entries);
                    updatePaginationControls();
                } catch (Exception e) {
                    api.logging().logToError("loadPageFromDatabase: " + e.getMessage());
                }
            }
        };
        pageWorker.execute();
    }

    private void resetMessageFilterState() {
        currentMessageTable = "";
        currentMessageFilter = "";
    }

    private void startRegexWorkers() {
        for (int i = 0; i < REGEX_WORKER_COUNT; i++) {
            regexExecutorService.execute(this::runRegexWorker);
        }
        recoverPendingRegexWork();
    }

    private void runRegexWorker() {
        while (regexWorkerRunning.get() && !Thread.currentThread().isInterrupted()) {
            String messageId = null;
            try {
                messageId = regexQueue.poll(PENDING_RECOVERY_IDLE_MILLIS, TimeUnit.MILLISECONDS);
                if (messageId == null) {
                    recoverPendingRegexWork();
                    continue;
                }

                queuedRegexMessageIds.remove(messageId);
                processRegexMessage(messageId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                api.logging().logToError("runRegexWorker: " + e.getMessage());
                if (messageId != null) {
                    regexWorkRepository.failRegexProcessing(messageId, e.getMessage());
                }
            }
        }
    }

    private void recoverPendingRegexWork() {
        List<String> pendingIds = regexWorkRepository.loadPendingRegexMessageIds(PENDING_RECOVERY_BATCH_SIZE);
        for (String pendingId : pendingIds) {
            enqueueRegexMessage(pendingId);
        }
    }

    private void enqueueRegexMessage(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return;
        }

        if (queuedRegexMessageIds.add(messageId) && !regexQueue.offer(messageId)) {
            queuedRegexMessageIds.remove(messageId);
        }
    }

    int queuedRegexMessageIdCount() {
        return queuedRegexMessageIds.size();
    }

    boolean hasQueuedRegexMessageId(String messageId) {
        return queuedRegexMessageIds.contains(messageId);
    }

    private void processRegexMessage(String messageId) {
        if (!regexWorkRepository.markRegexProcessing(messageId)) {
            return;
        }

        try {
            SqliteMessageStore.StoredMessage storedMessage = messageRepository.loadStoredMessage(messageId);
            if (storedMessage == null || storedMessage.getRequestResponse() == null) {
                regexWorkRepository.failRegexProcessing(messageId, "Stored message is unavailable");
                return;
            }

            HttpRequestResponse requestResponse = storedMessage.getRequestResponse();
            MessageProcessor.ProcessedMessage processedMessage = messageProcessor.processRequestResponse(
                    storedMessage.getHost(),
                    requestResponse.request(),
                    requestResponse.response()
            );

            if (!processedMessage.hasMatches()) {
                if (!regexWorkRepository.completeRegexProcessing(messageId, "", "none", Collections.emptyMap())) {
                    regexWorkRepository.resetRegexProcessing(messageId, "Unable to mark unmatched regex processing as complete");
                    enqueueRegexMessage(messageId);
                }
                removePendingAnnotation(messageId);
                return;
            }

            boolean completed = regexWorkRepository.completeRegexProcessing(
                    messageId,
                    processedMessage.getComment(),
                    processedMessage.getColor(),
                    processedMessage.getExtractedDataByRule()
            );
            if (completed) {
                enqueueAiTriageAfterRegexCompletion(storedMessage, requestResponse, processedMessage);
                applyPendingAnnotation(messageId, processedMessage);
                refreshCurrentPageLater();
            } else {
                regexWorkRepository.resetRegexProcessing(messageId, "Unable to complete regex processing");
                enqueueRegexMessage(messageId);
            }
        } catch (Exception e) {
            regexWorkRepository.failRegexProcessing(messageId, e.getMessage());
            removePendingAnnotation(messageId);
            api.logging().logToError("processRegexMessage: " + e.getMessage());
        }
    }

    private void enqueueAiTriageAfterRegexCompletion(SqliteMessageStore.StoredMessage storedMessage,
                                                     HttpRequestResponse requestResponse,
                                                     MessageProcessor.ProcessedMessage processedMessage) {
        if (aiTriageEnqueueService == null || storedMessage == null || processedMessage == null) {
            return;
        }

        try {
            AiTriageEnqueueService.EnqueueResult result = aiTriageEnqueueService.enqueueAfterRegexPersistence(
                    storedMessage.getMessageId(),
                    storedMessage.getContentHash(),
                    requestResponse,
                    processedMessage.getExtractedDataByRule(),
                    configLoader.getAiConfig()
            );
            if ("failed".equals(result.getStatus())) {
                api.logging().logToError("enqueueAiTriageAfterRegexCompletion: " + result.getReason());
            }
        } catch (Exception e) {
            api.logging().logToError("enqueueAiTriageAfterRegexCompletion: " + e.getMessage());
        }
    }

    private void applyPendingAnnotation(String messageId, MessageProcessor.ProcessedMessage processedMessage) {
        Annotations annotations = removePendingAnnotation(messageId);
        if (annotations == null || processedMessage == null || !processedMessage.hasMatches()) {
            return;
        }

        try {
            annotations.setHighlightColor(HighlightColor.highlightColor(processedMessage.getColor()));
            annotations.setNotes(processedMessage.getComment());
        } catch (Exception e) {
            api.logging().logToError("applyPendingAnnotation: " + e.getMessage());
        }
    }

    private Annotations removePendingAnnotation(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return null;
        }

        synchronized (pendingAnnotations) {
            return pendingAnnotations.remove(messageId);
        }
    }

    private void refreshCurrentPageLater() {
        if (shutdownRequested.get()) {
            return;
        }
        if (!pageRefreshQueued.compareAndSet(false, true)) {
            return;
        }

        SwingUtilities.invokeLater(() -> {
            pageRefreshQueued.set(false);
            if (shutdownRequested.get()) {
                return;
            }
            loadPageFromCurrentState(false);
        });
    }

    private RequestMetadata buildRequestMetadata(HttpRequest request) {
        HttpService service = request.httpService();
        String fallbackHost = service == null ? "" : service.host();
        String fallbackUrl = buildFallbackUrl(request, service);

        try {
            String url = request.url();
            String host = StringProcessor.getHostByUrl(url);
            if (host == null || host.isBlank()) {
                host = fallbackHost;
            }
            return new RequestMetadata(url, host, "");
        } catch (Exception e) {
            return new RequestMetadata(fallbackUrl, fallbackHost, e.getMessage());
        }
    }

    private String buildFallbackUrl(HttpRequest request, HttpService service) {
        if (service == null) {
            return safePath(request);
        }

        String scheme = service.secure() ? "https" : "http";
        String host = service.host() == null ? "" : service.host();
        int port = service.port();
        String path = safePath(request);
        StringBuilder builder = new StringBuilder();
        builder.append(scheme).append("://").append(host);
        if (port > 0 && !isDefaultPort(port, service.secure())) {
            builder.append(":").append(port);
        }
        if (path == null || path.isBlank()) {
            builder.append("/");
        } else if (path.startsWith("/")) {
            builder.append(path);
        } else {
            builder.append("/").append(path);
        }
        return builder.toString();
    }

    private boolean isDefaultPort(int port, boolean secure) {
        return (secure && port == 443) || (!secure && port == 80);
    }

    private String safePath(HttpRequest request) {
        try {
            String path = request.path();
            return path == null || path.isBlank() ? "/" : path;
        } catch (Exception e) {
            api.logging().logToError("safePath: " + e.getMessage());
            return "/";
        }
    }

    private String safeMethod(HttpRequest request) {
        try {
            String method = request.method();
            return method == null ? "" : method;
        } catch (Exception e) {
            api.logging().logToError("safeMethod: " + e.getMessage());
            return "";
        }
    }

    private String safeStatus(HttpResponse response) {
        try {
            return String.valueOf(response.statusCode());
        } catch (Exception e) {
            api.logging().logToError("safeStatus: " + e.getMessage());
            return "";
        }
    }

    private String safeLength(HttpResponse response) {
        try {
            return String.valueOf(response.toByteArray().length());
        } catch (Exception e) {
            api.logging().logToError("safeLength: " + e.getMessage());
            return "0";
        }
    }

    private TableRowSorter<TableModel> getDefaultTableModelTableRowSorter() {
        TableRowSorter<TableModel> sorter = new TableRowSorter<>(this);

        sorter.setComparator(4, (value1, value2) -> {
            Integer length1 = Integer.parseInt(value1 == null ? "0" : value1.toString());
            Integer length2 = Integer.parseInt(value2 == null ? "0" : value2.toString());
            return length1.compareTo(length2);
        });

        sorter.setComparator(5, new Comparator<String>() {
            @Override
            public int compare(String s1, String s2) {
                int index1 = getIndex(s1);
                int index2 = getIndex(s2);
                return Integer.compare(index1, index2);
            }

            private int getIndex(String color) {
                for (int i = 0; i < Config.color.length; i++) {
                    if (Config.color[i].equals(color)) {
                        return i;
                    }
                }
                return -1;
            }
        });
        return sorter;
    }

    public synchronized void add(HttpRequestResponse messageInfo, boolean flag) {
        add(messageInfo, flag, null, "");
    }

    public synchronized void add(HttpRequestResponse messageInfo, boolean flag, Annotations annotations) {
        add(messageInfo, flag, annotations, "");
    }

    public synchronized void add(HttpRequestResponse messageInfo, boolean flag, Annotations annotations, String toolType) {
        if (messageInfo == null) {
            return;
        }

        HttpRequest request = messageInfo.request();
        HttpResponse response = messageInfo.response();
        if (request == null || response == null) {
            return;
        }

        RequestMetadata requestMetadata = buildRequestMetadata(request);
        String method = safeMethod(request);
        String status = safeStatus(response);
        String length = safeLength(response);
        String filterReason = httpUtils.getFilterReason(messageInfo, toolType == null ? "" : toolType);
        if (!filterReason.isBlank()) {
            return;
        }
        String messageId = StringProcessor.getRandomUUID();

        if (flag) {
            SqliteMessageStore.PendingMessageSaveResult saveResult = messageRepository.savePendingMessage(
                    messageId,
                    messageInfo,
                    requestMetadata.url,
                    requestMetadata.host,
                    method,
                    status,
                    length,
                    "",
                    requestMetadata.urlParseError,
                    filterReason,
                    true
            );

            if (saveResult.isSaved()) {
                rememberPendingAnnotation(saveResult.getMessageId(), annotations);
                enqueueRegexMessage(saveResult.getMessageId());
            }
        }
    }

    private void rememberPendingAnnotation(String messageId, Annotations annotations) {
        if (messageId == null || messageId.isBlank() || annotations == null) {
            return;
        }

        synchronized (pendingAnnotations) {
            pendingAnnotations.put(messageId, annotations);
            while (pendingAnnotations.size() > PENDING_ANNOTATION_CAPACITY) {
                Iterator<String> iterator = pendingAnnotations.keySet().iterator();
                if (!iterator.hasNext()) {
                    break;
                }
                iterator.next();
                iterator.remove();
            }
        }
    }

    public void deleteByHost(String filterText) {
        if (currentWorker != null && !currentWorker.isDone()) {
            currentWorker.cancel(true);
        }

        currentWorker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                storageMaintenanceRepository.deleteByHostPattern(filterText);
                return null;
            }

            @Override
            protected void done() {
                resetMessageFilterState();
                loadPageFromCurrentState(false);
            }
        };

        currentWorker.execute();
    }

    public int clearStorageHistory() {
        int deletedCount = storageMaintenanceRepository.deleteAllMessages();
        storageMaintenanceRepository.deleteAllScopedDataboardScopes();

        resetMessageFilterState();
        regexQueue.clear();
        queuedRegexMessageIds.clear();
        pendingAnnotations.clear();
        if (pageWorker != null && !pageWorker.isDone()) {
            pageWorker.cancel(true);
        }

        queryVersion.incrementAndGet();
        totalRows = 0;
        currentPage = 1;
        applyPageEntries(Collections.emptyList());
        updatePaginationControls();

        return deletedCount;
    }

    public void clearAllDataOnShutdown() {
        shutdownRequested.set(true);
        try {
            if (currentWorker != null && !currentWorker.isDone()) {
                currentWorker.cancel(true);
                awaitWorkerCompletion(currentWorker, "clearAllDataOnShutdown(currentWorker)");
            }

            if (pageWorker != null && !pageWorker.isDone()) {
                pageWorker.cancel(true);
                awaitWorkerCompletion(pageWorker, "clearAllDataOnShutdown(pageWorker)");
            }
        } catch (Exception e) {
            api.logging().logToError("clearAllDataOnShutdown(cancel): " + e.getMessage());
        }

        try {
            regexWorkerRunning.set(false);
            regexExecutorService.shutdownNow();
            if (!regexExecutorService.awaitTermination(2, TimeUnit.SECONDS)) {
                api.logging().logToError("clearAllDataOnShutdown(regex): regex executor did not terminate promptly");
            }
            regexQueue.clear();
            queuedRegexMessageIds.clear();
            pendingAnnotations.clear();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            api.logging().logToError("clearAllDataOnShutdown(regex): interrupted while waiting for shutdown");
        } catch (Exception e) {
            api.logging().logToError("clearAllDataOnShutdown(regex): " + e.getMessage());
        }

        try {
            messageTable.shutdown();
        } catch (Exception e) {
            api.logging().logToError("clearAllDataOnShutdown(table): " + e.getMessage());
        }

        try {
            queryVersion.incrementAndGet();
            storageMaintenanceRepository.deleteAllMessages();
            storageMaintenanceRepository.deleteAllScopedDataboardScopes();
            synchronized (pageLog) {
                pageLog.clear();
            }
            selectionGeneration.incrementAndGet();
            totalRows = 0;
            currentPage = 1;
        } catch (Exception e) {
            api.logging().logToError("clearAllDataOnShutdown(delete): " + e.getMessage());
        }
    }

    private void awaitWorkerCompletion(SwingWorker<?, ?> worker, String context) {
        if (worker == null || worker.isDone()) {
            return;
        }

        try {
            worker.get(2, TimeUnit.SECONDS);
        } catch (java.util.concurrent.CancellationException ignored) {
        } catch (java.util.concurrent.TimeoutException e) {
            api.logging().logToError(context + ": worker did not terminate promptly");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            api.logging().logToError(context + ": interrupted while waiting for worker shutdown");
        } catch (Exception e) {
            api.logging().logToError(context + ": " + e.getMessage());
        }
    }

    public String getStoragePath() {
        return storageMaintenanceRepository.getDatabasePath();
    }

    public AiTaskRepository getAiTaskRepositoryForSettings() {
        return messageRepository instanceof AiTaskRepository aiTaskRepository ? aiTaskRepository : null;
    }

    public Map<String, List<String>> loadExtractedDataByHost(String hostPattern) {
        return extractedDataRepository.loadExtractedDataByHost(hostPattern);
    }

    public Map<String, Map<String, AiSummaryDisplay>> loadAiSummariesByRuleValue(String hostPattern,
                                                                                  Map<String, List<String>> extractedDataByRule) {
        if (aiResultRepository == null || extractedDataByRule == null || extractedDataByRule.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Map<String, AiSummaryDisplay>> result = new LinkedHashMap<>();
        String normalizedHostPattern = (hostPattern == null || hostPattern.trim().isEmpty()) ? "*" : hostPattern.trim();
        for (Map.Entry<String, List<String>> entry : extractedDataByRule.entrySet()) {
            String ruleName = entry.getKey();
            if (ruleName == null || ruleName.isBlank()) {
                continue;
            }
            Map<String, AiSummaryDisplay> summariesByValue = new LinkedHashMap<>();
            for (String value : entry.getValue()) {
                AiSummaryDisplay summary = loadAiSummaryForRuleValue(normalizedHostPattern, ruleName, value);
                summariesByValue.put(value, summary);
            }
            result.put(ruleName, summariesByValue);
        }
        return result;
    }

    private AiSummaryDisplay loadAiSummaryForRuleValue(String hostPattern, String ruleName, String value) {
        if (ruleName == null || ruleName.isBlank() || value == null || value.isBlank()) {
            return AiSummaryDisplay.empty();
        }
        List<SqliteMessageStore.MessageMetadata> metadataList = messageRepository.loadMessageMetadataByFilter(hostPattern, "", ruleName, value);
        if (metadataList.isEmpty()) {
            return AiSummaryDisplay.empty();
        }

        List<String> messageIds = new ArrayList<>(metadataList.size());
        for (SqliteMessageStore.MessageMetadata metadata : metadataList) {
            if (metadata != null && metadata.getMessageId() != null && !metadata.getMessageId().isBlank()) {
                messageIds.add(metadata.getMessageId());
            }
        }
        if (messageIds.isEmpty()) {
            return AiSummaryDisplay.empty();
        }

        String matchSignatureHash = AiTriageTargetSignature.matchSignatureHash(ruleName, value);
        List<SqliteMessageStore.AiTriageResultSummary> summaries = aiResultRepository.loadAiTriageResultSummaries(messageIds, matchSignatureHash);
        AiSummaryDisplay resultSummary = latestAiSummaryDisplay(summaries);
        if (isDisplayPresent(resultSummary)) {
            return resultSummary;
        }
        if (!isAiRuleAllowed(ruleName)) {
            return AiSummaryDisplay.disallowedRule();
        }
        if (aiTaskRepository != null) {
            AiSummaryDisplay taskSummary = latestTaskSummaryDisplay(aiTaskRepository.loadAiTriageTaskSummaries(messageIds, matchSignatureHash));
            if (isDisplayPresent(taskSummary)) {
                return taskSummary;
            }
        }
        return AiSummaryDisplay.empty();
    }

    private AiSummaryDisplay latestAiSummaryDisplay(List<SqliteMessageStore.AiTriageResultSummary> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return AiSummaryDisplay.empty();
        }
        SqliteMessageStore.AiTriageResultSummary latestSummary = summaries.get(0);
        for (SqliteMessageStore.AiTriageResultSummary summary : summaries) {
            if (summary != null && (latestSummary == null || summary.getAnalyzedAt() > latestSummary.getAnalyzedAt())) {
                latestSummary = summary;
            }
        }
        return AiSummaryFormatter.display(latestSummary);
    }

    private AiSummaryDisplay latestTaskSummaryDisplay(List<SqliteMessageStore.AiTriageTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return AiSummaryDisplay.empty();
        }
        SqliteMessageStore.AiTriageTask latestTask = tasks.get(0);
        for (SqliteMessageStore.AiTriageTask task : tasks) {
            if (task != null && (latestTask == null || task.getUpdatedAt() > latestTask.getUpdatedAt())) {
                latestTask = task;
            }
        }
        return AiSummaryFormatter.displayTask(latestTask);
    }

    private boolean isDisplayPresent(AiSummaryDisplay display) {
        return display != null && (!display.getAiStatus().isBlank()
                || !display.getAiVerdict().isBlank()
                || !display.getAiConfidence().isBlank());
    }

    private boolean isAiRuleAllowed(String ruleName) {
        try {
            return AiWhitelistRules.allowsRule(configLoader.getAiConfig().getWhitelist(), ruleName);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public List<String> loadMatchedHosts() {
        return extractedDataRepository.loadMatchedHosts();
    }

    public void applyHostFilter(String filterText) {
        selectionGeneration.incrementAndGet();
        currentHostFilter = (filterText == null || filterText.trim().isEmpty()) ? "*" : filterText.trim();
        resetMessageFilterState();
        loadPageFromCurrentState(true);
    }

    public void applyMessageFilter(String tableName, String filterText) {
        selectionGeneration.incrementAndGet();
        String normalizedTableName = tableName == null ? "" : tableName.trim();
        String normalizedFilterText = filterText == null ? "" : filterText.trim();

        if (normalizedTableName.isEmpty() || normalizedFilterText.isEmpty()) {
            resetMessageFilterState();
            loadPageFromCurrentState(true);
            return;
        }

        if ("*".equals(normalizedTableName) && "*".equals(normalizedFilterText)) {
            resetMessageFilterState();
            loadPageFromCurrentState(true);
            return;
        }

        currentMessageTable = normalizedTableName;
        currentMessageFilter = normalizedFilterText;
        loadPageFromCurrentState(true);
    }

    public void applyCommentFilter(String tableName) {
        selectionGeneration.incrementAndGet();
        currentCommentFilter = tableName == null ? "" : tableName.trim();
        resetMessageFilterState();
        loadPageFromCurrentState(true);
    }

    public JSplitPane getSplitPane() {
        return splitPane;
    }

    public JSplitPane getTableAndMessageSplitPaneForTest() {
        return tableAndMessagePane;
    }

    public String getAiDetailTextForTest() {
        if (SwingUtilities.isEventDispatchThread()) {
            return aiDetailArea.getText();
        }
        String[] text = new String[1];
        try {
            SwingUtilities.invokeAndWait(() -> text[0] = aiDetailArea.getText());
            return text[0] == null ? "" : text[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    public MessageTable getMessageTable() {
        return messageTable;
    }

    public String loadSelectedAiResultJson() {
        if (aiResultRepository == null) {
            return null;
        }

        int selectedRow = messageTable.getSelectedRow();
        if (selectedRow < 0) {
            return null;
        }

        int modelRow = messageTable.convertRowIndexToModel(selectedRow);
        return loadAiResultJsonAtModelRow(modelRow);
    }

    public AiTriageEnqueueService.EnqueueResult enqueueSelectedAiTriage() {
        if (aiTriageEnqueueService == null) {
            return AiTriageEnqueueService.EnqueueResult.failed("AI 入队服务不可用");
        }

        int selectedRow = selectedModelRowForAiTriage();
        if (selectedRow < 0) {
            return AiTriageEnqueueService.EnqueueResult.failed("请先在右侧消息表中选中一条历史记录");
        }
        return enqueueAiTriageAtModelRow(selectedRow);
    }

    private int selectedModelRowForAiTriage() {
        if (SwingUtilities.isEventDispatchThread()) {
            return selectedModelRowOnEdt();
        }
        final int[] selectedModelRow = {-1};
        try {
            SwingUtilities.invokeAndWait(() -> selectedModelRow[0] = selectedModelRowOnEdt());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (Exception e) {
            return -1;
        }
        return selectedModelRow[0];
    }

    private int selectedModelRowOnEdt() {
        int selectedRow = messageTable.getSelectedRow();
        return selectedRow < 0 ? -1 : messageTable.convertRowIndexToModel(selectedRow);
    }

    AiTriageEnqueueService.EnqueueResult enqueueAiTriageAtModelRow(int modelRow) {
        MessageEntry messageEntry;
        synchronized (pageLog) {
            if (modelRow < 0 || modelRow >= pageLog.size()) {
                return AiTriageEnqueueService.EnqueueResult.failed("选中的历史记录不存在");
            }
            messageEntry = pageLog.get(modelRow);
        }

        if (messageEntry == null || messageEntry.getMessageId() == null || messageEntry.getMessageId().isBlank()) {
            return AiTriageEnqueueService.EnqueueResult.failed("选中的历史记录缺少 messageId");
        }

        HttpRequestResponse requestResponse = messageRepository.loadMessage(messageEntry.getMessageId());
        if (requestResponse == null) {
            return AiTriageEnqueueService.EnqueueResult.failed("无法读取选中历史记录的请求/响应");
        }

        Map<String, List<String>> extractedData = messageRepository.loadMessageExtractedData(messageEntry.getMessageId());
        if (extractedData == null || extractedData.isEmpty()) {
            return AiTriageEnqueueService.EnqueueResult.failed("选中历史记录没有可用于 AI 分析的正则命中数据");
        }

        try {
            return aiTriageEnqueueService.enqueueAfterRegexPersistence(
                    messageEntry.getMessageId(),
                    messageEntry.getContentHash(),
                    requestResponse,
                    extractedData,
                    configLoader.getAiConfig()
            );
        } catch (Exception e) {
            return AiTriageEnqueueService.EnqueueResult.failed(e.getMessage());
        }
    }

    String loadAiResultJsonAtModelRow(int modelRow) {
        if (aiResultRepository == null) {
            return null;
        }

        MessageEntry messageEntry;
        synchronized (pageLog) {
            if (modelRow < 0 || modelRow >= pageLog.size()) {
                return null;
            }
            messageEntry = pageLog.get(modelRow);
            if (messageEntry == null || messageEntry.getMessageId() == null || messageEntry.getMessageId().isBlank()) {
                return null;
            }
        }
        if (messageEntry.getAiTargetSignatureHash() == null || messageEntry.getAiTargetSignatureHash().isBlank()) {
            return null;
        }
        return aiResultRepository.loadAiTriageResultJson(messageEntry.getMessageId(), messageEntry.getAiTargetSignatureHash());
    }

    private String loadAiDetailText(MessageEntry messageEntry) {
        if (aiResultRepository == null || messageEntry == null
                || messageEntry.getMessageId() == null || messageEntry.getMessageId().isBlank()) {
            return "暂无 AI 结果。";
        }
        if (messageEntry.getAiTargetSignatureHash() == null || messageEntry.getAiTargetSignatureHash().isBlank()) {
            return "请选择左侧具体规则取值查看 AI 详情。";
        }

        List<SqliteMessageStore.AiTriageResultSummary> summaries = aiResultRepository.loadAiTriageResultSummaries(
                List.of(messageEntry.getMessageId()),
                messageEntry.getAiTargetSignatureHash()
        );
        if (summaries.isEmpty()) {
            return "暂无当前规则/取值的 AI 结果。";
        }

        SqliteMessageStore.AiTriageResultSummary summary = summaries.get(0);
        String resultJson = aiResultRepository.loadAiTriageResultJson(messageEntry.getMessageId(), messageEntry.getAiTargetSignatureHash());
        StringBuilder builder = new StringBuilder();
        builder.append("AI目标：").append(safeDetail(messageEntry.getAiTargetRuleName()))
                .append(" / ").append(safeDetail(messageEntry.getAiTargetValue())).append('\n');
        builder.append("AI状态：").append(AiSummaryFormatter.displayAiStatus(summary)).append('\n');
        builder.append("AI结论：").append(AiSummaryFormatter.displayAiVerdict(summary)).append('\n');
        builder.append("AI风险：").append(AiSummaryFormatter.displayAiRisk(summary)).append('\n');
        builder.append("AI置信度：").append(AiSummaryFormatter.formatConfidence(summary.getConfidence())).append('\n');
        builder.append("模型：").append(safeDetail(summary.getModel())).append('\n');
        builder.append("摘要：").append(safeDetail(AiSummaryFormatter.displayAiSummary(summary))).append('\n');
        if (summary.isEmptyAdvisoryResult()) {
            builder.append('\n').append("提示：AI 返回了空结论。请在 AI 设置中点击“分析选中项”重新分析。").append('\n');
        } else if (summary.isLowQualityAdvisoryResult()) {
            builder.append('\n').append("提示：AI 返回了低质量结论（摘要为空、总体置信度为 0，且逐项风险/置信度无有效判断）。请在 AI 设置中点击“分析选中项”重新分析。").append('\n');
        }
        if (resultJson != null && !resultJson.isBlank()) {
            builder.append('\n').append("原始AI结果：").append('\n').append(formatJsonForDisplay(resultJson));
        }
        return builder.toString();
    }

    private static String formatJsonForDisplay(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        StringBuilder formatted = new StringBuilder(json.length() + 64);
        int indent = 0;
        boolean inString = false;
        boolean escaping = false;
        for (int i = 0; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (escaping) {
                formatted.append(ch);
                escaping = false;
                continue;
            }
            if (ch == '\\' && inString) {
                formatted.append(ch);
                escaping = true;
                continue;
            }
            if (ch == '"') {
                formatted.append(ch);
                inString = !inString;
                continue;
            }
            if (inString) {
                formatted.append(ch);
                continue;
            }
            switch (ch) {
                case '{', '[' -> {
                    formatted.append(ch).append('\n');
                    indent++;
                    appendIndent(formatted, indent);
                }
                case '}', ']' -> {
                    formatted.append('\n');
                    indent = Math.max(0, indent - 1);
                    appendIndent(formatted, indent);
                    formatted.append(ch);
                }
                case ',' -> {
                    formatted.append(ch).append('\n');
                    appendIndent(formatted, indent);
                }
                case ':' -> formatted.append(": ");
                default -> {
                    if (!Character.isWhitespace(ch)) {
                        formatted.append(ch);
                    }
                }
            }
        }
        return formatted.toString();
    }

    private static void appendIndent(StringBuilder builder, int indent) {
        for (int i = 0; i < indent; i++) {
            builder.append("  ");
        }
    }

    private static String safeDetail(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    @Override
    public int getRowCount() {
        synchronized (pageLog) {
            return pageLog.size();
        }
    }

    @Override
    public int getColumnCount() {
        return BASE_COLUMN_COUNT;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        synchronized (pageLog) {
            if (rowIndex < 0 || rowIndex >= pageLog.size()) {
                return "";
            }

            try {
                MessageEntry messageEntry = pageLog.get(rowIndex);
                if (messageEntry == null) {
                    return "";
                }

                return switch (columnIndex) {
                    case 0 -> messageEntry.getMethod();
                    case 1 -> messageEntry.getUrl();
                    case 2 -> messageEntry.getComment();
                    case 3 -> messageEntry.getStatus();
                    case 4 -> messageEntry.getLength();
                    case 5 -> messageEntry.getColor();
                    default -> "";
                };
            } catch (Exception e) {
                api.logging().logToError("getValueAt: " + e.getMessage());
                return "";
            }
        }
    }

    @Override
    public String getColumnName(int columnIndex) {
        return switch (columnIndex) {
            case 0 -> "Method";
            case 1 -> "URL";
            case 2 -> "Comment";
            case 3 -> "Status";
            case 4 -> "Length";
            case 5 -> "Color";
            default -> "";
        };
    }

    public class MessageTable extends JTable {
        private final ExecutorService executorService;
        private final HttpRequestEditor requestEditor;
        private final HttpResponseEditor responseEditor;
        private int lastSelectedIndex = -1;

        public MessageTable(TableModel messageTableModel, HttpRequestEditor requestEditor, HttpResponseEditor responseEditor) {
            super(messageTableModel);
            this.requestEditor = requestEditor;
            this.responseEditor = responseEditor;
            this.executorService = Executors.newSingleThreadExecutor();
            getSelectionModel().addListSelectionListener(event -> {
                if (!event.getValueIsAdjusting()) {
                    refreshSelectedMessageAsync();
                }
            });
        }

        private void shutdown() {
            executorService.shutdownNow();
            try {
                if (!executorService.awaitTermination(2, TimeUnit.SECONDS)) {
                    api.logging().logToError("MessageTable.shutdown: selection executor did not terminate promptly");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                api.logging().logToError("MessageTable.shutdown: interrupted while waiting for selection executor shutdown");
            }
        }

        @Override
        public void changeSelection(int row, int col, boolean toggle, boolean extend) {
            super.changeSelection(row, col, toggle, extend);
            refreshSelectedMessageAsync();
        }

        private void refreshSelectedMessageAsync() {
            if (shutdownRequested.get()) {
                return;
            }
            int selectedRow = getSelectedRow();
            if (selectedRow < 0) {
                aiDetailArea.setText("暂无 AI 结果。");
                return;
            }
            int selectedIndex = convertRowIndexToModel(selectedRow);
            if (lastSelectedIndex != selectedIndex) {
                lastSelectedIndex = selectedIndex;
                long selectedGeneration = selectionGeneration.incrementAndGet();
                String selectedMessageId = messageIdAtModelRow(selectedIndex);
                if (selectedMessageId == null || executorService.isShutdown()) {
                    return;
                }
                try {
                    executorService.execute(() -> getSelectedMessage(selectedIndex, selectedMessageId, selectedGeneration));
                } catch (RejectedExecutionException e) {
                    if (!shutdownRequested.get()) {
                        api.logging().logToError("refreshSelectedMessageAsync: " + e.getMessage());
                    }
                }
            }
        }

        private void getSelectedMessage(int selectedIndexSnapshot, String messageIdSnapshot, long generationSnapshot) {
            if (shutdownRequested.get()) {
                return;
            }
            MessageEntry messageEntry;
            synchronized (pageLog) {
                if (selectedIndexSnapshot < 0 || selectedIndexSnapshot >= pageLog.size()) {
                    return;
                }
                messageEntry = pageLog.get(selectedIndexSnapshot);
            }
            if (messageEntry == null || !Objects.equals(messageEntry.getMessageId(), messageIdSnapshot)) {
                return;
            }

            String aiDetailText = loadAiDetailText(messageEntry);
            HttpRequestResponse httpRequestResponse = messageRepository.loadMessage(messageIdSnapshot);
            if (httpRequestResponse == null) {
                SwingUtilities.invokeLater(() -> {
                    if (!isCurrentSelection(selectedIndexSnapshot, messageIdSnapshot, generationSnapshot)) {
                        return;
                    }
                    aiDetailArea.setText(aiDetailText);
                    aiDetailArea.setCaretPosition(0);
                });
                return;
            }

            HttpRequest request = null;
            HttpResponse response = null;
            try {
                request = HttpRequest.httpRequest(httpRequestResponse.httpService(), httpRequestResponse.request().toByteArray());
                response = buildDisplayResponse(httpRequestResponse.response());
            } catch (Exception e) {
                api.logging().logToError("getSelectedMessage editors: " + e.getMessage());
            }
            HttpRequest displayRequest = request;
            HttpResponse displayResponse = response;
            SwingUtilities.invokeLater(() -> {
                if (!isCurrentSelection(selectedIndexSnapshot, messageIdSnapshot, generationSnapshot)) {
                    return;
                }
                aiDetailArea.setText(aiDetailText);
                aiDetailArea.setCaretPosition(0);
                if (displayRequest != null) {
                    requestEditor.setRequest(displayRequest);
                }
                if (displayResponse != null) {
                    responseEditor.setResponse(displayResponse);
                }
            });
        }

        private String messageIdAtModelRow(int modelRow) {
            synchronized (pageLog) {
                if (modelRow < 0 || modelRow >= pageLog.size()) {
                    return null;
                }
                MessageEntry messageEntry = pageLog.get(modelRow);
                if (messageEntry == null || messageEntry.getMessageId() == null || messageEntry.getMessageId().isBlank()) {
                    return null;
                }
                return messageEntry.getMessageId();
            }
        }

        private boolean isCurrentSelection(int selectedIndexSnapshot, String messageIdSnapshot, long generationSnapshot) {
            if (shutdownRequested.get() || lastSelectedIndex != selectedIndexSnapshot || selectionGeneration.get() != generationSnapshot) {
                return false;
            }
            int currentSelectedRow = getSelectedRow();
            if (currentSelectedRow < 0) {
                return false;
            }
            int currentModelRow = convertRowIndexToModel(currentSelectedRow);
            if (currentModelRow != selectedIndexSnapshot) {
                return false;
            }
            return Objects.equals(messageIdAtModelRow(currentModelRow), messageIdSnapshot);
        }

        private HttpResponse buildDisplayResponse(HttpResponse response) {
            int responseSizeWithMb = response.toByteArray().length() / 1024 / 1024;
            if ((responseSizeWithMb < Integer.parseInt(configLoader.getLimitSize())) || configLoader.getLimitSize().equals("0")) {
                return response;
            }
            return HttpResponse.httpResponse("Exceeds length limit.");
        }
    }
}
