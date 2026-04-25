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
import hae.instances.http.utils.MessageProcessor;
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
import java.awt.FlowLayout;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static burp.api.montoya.ui.editor.EditorOptions.READ_ONLY;

public class MessageTableModel extends AbstractTableModel {
    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int REGEX_WORKER_COUNT = 2;
    private static final int REGEX_QUEUE_CAPACITY = 10000;
    private static final int PENDING_ANNOTATION_CAPACITY = 10000;
    private static final int PENDING_RECOVERY_BATCH_SIZE = 200;
    private static final long PENDING_RECOVERY_IDLE_MILLIS = 5000L;

    private final MontoyaApi api;
    private final ConfigLoader configLoader;
    private final MessageRepository messageRepository;
    private final RegexWorkRepository regexWorkRepository;
    private final ExtractedDataRepository extractedDataRepository;
    private final StorageMaintenanceRepository storageMaintenanceRepository;
    private final MessageProcessor messageProcessor;
    private final HttpUtils httpUtils;
    private final MessageTable messageTable;
    private final JSplitPane splitPane;
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
    private final BlockingQueue<String> regexQueue = new LinkedBlockingQueue<>(REGEX_QUEUE_CAPACITY);
    private final Set<String> queuedRegexMessageIds = ConcurrentHashMap.newKeySet();
    private final ExecutorService regexExecutorService;
    private final AtomicBoolean regexWorkerRunning = new AtomicBoolean(true);
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

    public MessageTableModel(MontoyaApi api,
                             ConfigLoader configLoader,
                             MessageRepository messageRepository,
                             RegexWorkRepository regexWorkRepository,
                             ExtractedDataRepository extractedDataRepository,
                             StorageMaintenanceRepository storageMaintenanceRepository) {
        this(api, configLoader, messageRepository, regexWorkRepository, extractedDataRepository, storageMaintenanceRepository, true);
    }

    MessageTableModel(MontoyaApi api,
                      ConfigLoader configLoader,
                      MessageRepository messageRepository,
                      RegexWorkRepository regexWorkRepository,
                      ExtractedDataRepository extractedDataRepository,
                      StorageMaintenanceRepository storageMaintenanceRepository,
                      boolean startRegexWorkers) {
        this.api = Objects.requireNonNull(api, "api");
        this.configLoader = Objects.requireNonNull(configLoader, "configLoader");
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository");
        this.regexWorkRepository = Objects.requireNonNull(regexWorkRepository, "regexWorkRepository");
        this.extractedDataRepository = Objects.requireNonNull(extractedDataRepository, "extractedDataRepository");
        this.storageMaintenanceRepository = Objects.requireNonNull(storageMaintenanceRepository, "storageMaintenanceRepository");
        this.messageProcessor = new MessageProcessor(api, configLoader);
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

        messageTable = new MessageTable(this, requestViewer, responseViewer);
        messageTable.setDefaultRenderer(Object.class, new MessageRenderer(pageLog, messageTable));
        messageTable.setAutoCreateRowSorter(true);

        TableRowSorter<TableModel> sorter = getDefaultTableModelTableRowSorter();
        messageTable.setRowSorter(sorter);
        messageTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);

        splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        JScrollPane scrollPane = new JScrollPane(messageTable);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        JPanel tablePanel = new JPanel(new BorderLayout());
        tablePanel.add(scrollPane, BorderLayout.CENTER);
        tablePanel.add(createPaginationPanel(), BorderLayout.SOUTH);
        splitPane.setLeftComponent(tablePanel);
        splitPane.setRightComponent(messagePane);

        updatePaginationControls();
        if (startRegexWorkers) {
            startRegexWorkers();
        }
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
        };

        if (SwingUtilities.isEventDispatchThread()) {
            uiUpdate.run();
        } else {
            SwingUtilities.invokeLater(uiUpdate);
        }
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

    private List<MessageEntry> toMessageEntries(List<SqliteMessageStore.MessageMetadata> metadataList) {
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
                    metadata.getContentHash()
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

        pageWorker = new SwingWorker<>() {
            @Override
            protected PageQueryResult doInBackground() {
                int count = messageRepository.countMessageMetadata(hostFilter, commentFilter, messageTableFilter, messageValueFilter);
                int totalPages = Math.max(1, (count + requestedPageSize - 1) / requestedPageSize);
                int safePage = Math.max(1, Math.min(requestedPage, totalPages));
                int offset = (safePage - 1) * requestedPageSize;
                List<SqliteMessageStore.MessageMetadata> metadata = messageRepository.loadMessageMetadataPage(hostFilter, commentFilter, messageTableFilter, messageValueFilter, requestedPageSize, offset);
                return new PageQueryResult(toMessageEntries(metadata), count, safePage);
            }

            @Override
            protected void done() {
                if (isCancelled() || requestId != queryVersion.get()) {
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
        if (!pageRefreshQueued.compareAndSet(false, true)) {
            return;
        }

        SwingUtilities.invokeLater(() -> {
            pageRefreshQueued.set(false);
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
        try {
            if (currentWorker != null && !currentWorker.isDone()) {
                currentWorker.cancel(true);
            }

            if (pageWorker != null && !pageWorker.isDone()) {
                pageWorker.cancel(true);
            }
        } catch (Exception e) {
            api.logging().logToError("clearAllDataOnShutdown(cancel): " + e.getMessage());
        }

        try {
            regexWorkerRunning.set(false);
            regexExecutorService.shutdownNow();
            regexQueue.clear();
            queuedRegexMessageIds.clear();
            pendingAnnotations.clear();
        } catch (Exception e) {
            api.logging().logToError("clearAllDataOnShutdown(regex): " + e.getMessage());
        }

        try {
            queryVersion.incrementAndGet();
            storageMaintenanceRepository.deleteAllMessages();
            synchronized (pageLog) {
                pageLog.clear();
            }
            totalRows = 0;
            currentPage = 1;
        } catch (Exception e) {
            api.logging().logToError("clearAllDataOnShutdown(delete): " + e.getMessage());
        }

        try {
            messageTable.shutdown();
        } catch (Exception e) {
            api.logging().logToError("clearAllDataOnShutdown(table): " + e.getMessage());
        }
    }

    public String getStoragePath() {
        return storageMaintenanceRepository.getDatabasePath();
    }

    public Map<String, List<String>> loadExtractedDataByHost(String hostPattern) {
        return extractedDataRepository.loadExtractedDataByHost(hostPattern);
    }

    public List<String> loadMatchedHosts() {
        return extractedDataRepository.loadMatchedHosts();
    }

    public void applyHostFilter(String filterText) {
        currentHostFilter = (filterText == null || filterText.trim().isEmpty()) ? "*" : filterText.trim();
        resetMessageFilterState();
        loadPageFromCurrentState(true);
    }

    public void applyMessageFilter(String tableName, String filterText) {
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
        currentCommentFilter = tableName == null ? "" : tableName.trim();
        resetMessageFilterState();
        loadPageFromCurrentState(true);
    }

    public JSplitPane getSplitPane() {
        return splitPane;
    }

    public MessageTable getMessageTable() {
        return messageTable;
    }

    @Override
    public int getRowCount() {
        synchronized (pageLog) {
            return pageLog.size();
        }
    }

    @Override
    public int getColumnCount() {
        return 6;
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
        }

        private void shutdown() {
            executorService.shutdownNow();
        }

        @Override
        public void changeSelection(int row, int col, boolean toggle, boolean extend) {
            super.changeSelection(row, col, toggle, extend);
            int selectedIndex = convertRowIndexToModel(row);
            if (lastSelectedIndex != selectedIndex) {
                lastSelectedIndex = selectedIndex;
                executorService.execute(this::getSelectedMessage);
            }
        }

        private void getSelectedMessage() {
            int selectedIndexSnapshot = lastSelectedIndex;
            MessageEntry messageEntry;
            synchronized (pageLog) {
                if (selectedIndexSnapshot < 0 || selectedIndexSnapshot >= pageLog.size()) {
                    return;
                }
                messageEntry = pageLog.get(selectedIndexSnapshot);
            }

            HttpRequestResponse httpRequestResponse = messageRepository.loadMessage(messageEntry.getMessageId());
            if (httpRequestResponse == null) {
                return;
            }

            HttpRequest request = HttpRequest.httpRequest(httpRequestResponse.httpService(), httpRequestResponse.request().toByteArray());
            HttpResponse response = buildDisplayResponse(httpRequestResponse.response());
            SwingUtilities.invokeLater(() -> {
                if (lastSelectedIndex != selectedIndexSnapshot) {
                    return;
                }
                requestEditor.setRequest(request);
                responseEditor.setResponse(response);
            });
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
