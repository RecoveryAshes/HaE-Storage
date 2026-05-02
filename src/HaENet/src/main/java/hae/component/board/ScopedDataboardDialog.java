package hae.component.board;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.UserInterface;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import hae.component.board.message.MessageEntry;
import hae.component.board.message.MessageRenderer;
import hae.component.board.table.Datatable;
import hae.instances.http.utils.MessageProcessor;
import hae.repository.MessageRepository;
import hae.repository.ScopedDataboardRepository;
import hae.storage.SqliteMessageStore;
import hae.utils.ConfigLoader;
import hae.utils.string.StringProcessor;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumnModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import static burp.api.montoya.ui.editor.EditorOptions.READ_ONLY;

public class ScopedDataboardDialog extends JDialog {
    public static final String SOURCE_SELECTED_MESSAGES = "selected-http-messages";
    public static final String SOURCE_MAIN_MESSAGE_IDS = "selected-main-message-ids";

    private final MontoyaApi api;
    private final ConfigLoader configLoader;
    private final ScopedDataboardRepository scopedRepository;
    private final ScopedAnalysisService analysisService;
    private final List<HttpRequestResponse> selectedMessages;
    private final String label;

    private JTabbedPane dataTabbedPane;
    private JSplitPane splitPane;
    private JProgressBar progressBar;
    private JLabel statusLabel;
    private ScopedMessageTableModel scopedMessageTableModel;
    private ScopedMessageTableModel.ScopedMessageTable messageTable;
    private SwingWorker<ScopedAnalysisResult, Void> analysisWorker;
    private volatile boolean scopedResourcesClosed;
    private volatile boolean scopedScopeDeleted;
    private String scopeId;

    public static ScopedDataboardDialog fromSelectedMessages(Window owner,
                                                            MontoyaApi api,
                                                            ConfigLoader configLoader,
                                                            ScopedDataboardRepository scopedRepository,
                                                            List<HttpRequestResponse> selectedMessages,
                                                            String label) {
        return new ScopedDataboardDialog(owner, api, configLoader, scopedRepository, selectedMessages, label);
    }

    public static ScopedDataboardDialog fromSelectedMainMessageIds(Window owner,
                                                                  MontoyaApi api,
                                                                  ConfigLoader configLoader,
                                                                  MessageRepository messageRepository,
                                                                  ScopedDataboardRepository scopedRepository,
                                                                  List<String> messageIds,
                                                                  String label) {
        ScopedAnalysisService service = new ScopedAnalysisService(api, configLoader, scopedRepository);
        List<HttpRequestResponse> selectedMessages = service.loadSelectedMainMessages(messageRepository, messageIds);
        return fromSelectedMessages(owner, api, configLoader, scopedRepository, selectedMessages, label);
    }

    public ScopedDataboardDialog(Window owner,
                                 MontoyaApi api,
                                 ConfigLoader configLoader,
                                 ScopedDataboardRepository scopedRepository,
                                 List<HttpRequestResponse> selectedMessages,
                                 String label) {
        super(owner, buildTitle(label), Dialog.ModalityType.MODELESS);
        this.api = Objects.requireNonNull(api, "api");
        this.configLoader = Objects.requireNonNull(configLoader, "configLoader");
        this.scopedRepository = Objects.requireNonNull(scopedRepository, "scopedRepository");
        this.analysisService = new ScopedAnalysisService(api, configLoader, scopedRepository);
        this.selectedMessages = normalizeMessages(selectedMessages);
        this.label = label == null || label.isBlank() ? "Scoped Databoard" : label.trim();

        initComponents();
        startAnalysis();
    }

    private static String buildTitle(String label) {
        if (label == null || label.isBlank()) {
            return "Scoped Databoard";
        }
        return "Scoped Databoard - " + label.trim();
    }

    private static List<HttpRequestResponse> normalizeMessages(List<HttpRequestResponse> messages) {
        List<HttpRequestResponse> normalizedMessages = new ArrayList<>();
        if (messages == null) {
            return normalizedMessages;
        }
        for (HttpRequestResponse message : messages) {
            if (message != null) {
                normalizedMessages.add(message);
            }
        }
        return Collections.unmodifiableList(normalizedMessages);
    }

    private void initComponents() {
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(5, 5));
        setSize(new Dimension(1100, 700));
        setLocationRelativeTo(getOwner());

        dataTabbedPane = new JTabbedPane(JTabbedPane.TOP);
        dataTabbedPane.setPreferredSize(new Dimension(500, 0));
        dataTabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        dataTabbedPane.addChangeListener(e -> {
            if (scopedMessageTableModel == null) {
                return;
            }

            int selectedIndex = dataTabbedPane.getSelectedIndex();
            if (selectedIndex == -1) {
                scopedMessageTableModel.applyCommentFilter("");
                return;
            }

            String selectedTitle = dataTabbedPane.getTitleAt(selectedIndex);
            scopedMessageTableModel.applyCommentFilter(StringProcessor.extractItemName(selectedTitle));
        });

        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setResizeWeight(0.4);
        splitPane.setVisible(false);
        splitPane.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                resizePanel();
            }
        });

        progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        statusLabel = new JLabel("Preparing scoped analysis...");
        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> dispose());

        JPanel footerPanel = new JPanel(new BorderLayout(8, 4));
        footerPanel.setBorder(BorderFactory.createEmptyBorder(0, 5, 5, 5));
        footerPanel.add(progressBar, BorderLayout.CENTER);
        footerPanel.add(statusLabel, BorderLayout.WEST);
        footerPanel.add(closeButton, BorderLayout.EAST);

        add(splitPane, BorderLayout.CENTER);
        add(footerPanel, BorderLayout.SOUTH);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeScopedResources();
            }

            @Override
            public void windowClosed(WindowEvent e) {
                closeScopedResources();
            }
        });
    }

    private void startAnalysis() {
        setProgress("Analyzing selected messages...", true, 0);
        analysisWorker = new SwingWorker<>() {
            @Override
            protected ScopedAnalysisResult doInBackground() {
                return analysisService.analyzeSelectedMessages(selectedMessages, SOURCE_SELECTED_MESSAGES, label);
            }

            @Override
            protected void done() {
                if (isCancelled() || scopedResourcesClosed) {
                    return;
                }

                try {
                    ScopedAnalysisResult result = get();
                    scopeId = result.getScopeId();
                    installAnalysisResult(result);
                    ScopedDataboardDialog.this.setProgress(String.format("Scoped analysis complete · Messages %d · Matches %d",
                            result.getProcessedMessageCount(), result.getMatchedMessageCount()), false, 100);
                } catch (CancellationException e) {
                    ScopedDataboardDialog.this.setProgress("Scoped analysis cancelled", false, 0);
                } catch (Exception e) {
                    logError("ScopedDataboardDialog", e);
                    ScopedDataboardDialog.this.setProgress("Scoped analysis failed: " + e.getMessage(), false, 0);
                }
            }
        };
        analysisWorker.execute();
    }

    private void installAnalysisResult(ScopedAnalysisResult result) {
        scopedMessageTableModel = new ScopedMessageTableModel(api, configLoader, scopedRepository, result.getScopeId());
        dataTabbedPane.removeAll();

        for (Map.Entry<String, List<String>> entry : result.getExtractedDataByRule().entrySet()) {
            String ruleName = entry.getKey();
            List<String> values = entry.getValue();
            String tabTitle = String.format("%s (%s)", ruleName, values.size());
            Datatable datatablePanel = new Datatable(api, configLoader, ruleName, values);
            datatablePanel.setTableListener(scopedMessageTableModel);
            insertTabSorted(dataTabbedPane, tabTitle, datatablePanel);
        }

        splitPane.setLeftComponent(dataTabbedPane);
        splitPane.setRightComponent(scopedMessageTableModel.getSplitPane());
        messageTable = scopedMessageTableModel.getMessageTable();
        splitPane.setVisible(true);
        resizePanel();

        if (dataTabbedPane.getTabCount() > 0) {
            dataTabbedPane.setSelectedIndex(0);
            scopedMessageTableModel.applyCommentFilter(StringProcessor.extractItemName(dataTabbedPane.getTitleAt(0)));
        } else {
            scopedMessageTableModel.applyHostFilter("*");
            statusLabel.setText("No extracted values found in the scoped messages.");
        }
    }

    private void resizePanel() {
        if (splitPane == null || messageTable == null) {
            return;
        }

        splitPane.setDividerLocation(0.4);
        TableColumnModel columnModel = messageTable.getColumnModel();
        if (columnModel.getColumnCount() < 6) {
            return;
        }

        int totalWidth = (int) (Math.max(getWidth(), 1) * 0.6);
        columnModel.getColumn(0).setPreferredWidth((int) (totalWidth * 0.1));
        columnModel.getColumn(1).setPreferredWidth((int) (totalWidth * 0.3));
        columnModel.getColumn(2).setPreferredWidth((int) (totalWidth * 0.3));
        columnModel.getColumn(3).setPreferredWidth((int) (totalWidth * 0.1));
        columnModel.getColumn(4).setPreferredWidth((int) (totalWidth * 0.1));
        columnModel.getColumn(5).setPreferredWidth((int) (totalWidth * 0.1));
    }

    private void setProgress(String message, boolean active, int progress) {
        progressBar.setIndeterminate(active && progress <= 0);
        progressBar.setMaximum(100);
        progressBar.setString(message);
        if (progress > 0) {
            progressBar.setValue(progress);
        } else if (!active) {
            progressBar.setValue(progressBar.getMinimum());
        }
        statusLabel.setText(message);
    }

    static void insertTabSorted(JTabbedPane tabbedPane, String title, Component component) {
        int insertIndex = 0;
        int tabCount = tabbedPane.getTabCount();
        Collator collator = Collator.getInstance(Locale.getDefault());
        collator.setStrength(Collator.PRIMARY);

        for (int i = 0; i < tabCount; i++) {
            String existingTitle = tabbedPane.getTitleAt(i);
            if (collator.compare(existingTitle, title) > 0) {
                insertIndex = i;
                break;
            }
            insertIndex = i + 1;
        }

        tabbedPane.insertTab(title, null, component, null, insertIndex);
    }

    @Override
    public void dispose() {
        closeScopedResources();
        super.dispose();
    }

    public void closeScopedResources() {
        scopedResourcesClosed = true;
        if (analysisWorker != null && !analysisWorker.isDone()) {
            analysisWorker.cancel(true);
        }
        if (scopedMessageTableModel != null) {
            scopedMessageTableModel.shutdown();
        }
        deleteCurrentScope();
    }

    private void deleteCurrentScope() {
        if (scopedScopeDeleted || scopeId == null || scopeId.isBlank()) {
            return;
        }
        scopedScopeDeleted = true;
        try {
            scopedRepository.deleteScopedDataboardScope(scopeId);
        } catch (Exception e) {
            logError("deleteScopedDataboardScope", e);
        }
    }

    boolean isScopedResourcesClosedForTest() {
        return scopedResourcesClosed;
    }

    boolean isAnalysisWorkerRunningForTest() {
        return analysisWorker != null && !analysisWorker.isDone();
    }

    ScopedMessageTableModel getScopedMessageTableModelForTest() {
        return scopedMessageTableModel;
    }

    String getScopeIdForTest() {
        return scopeId;
    }

    private void logError(String methodName, Exception exception) {
        try {
            api.logging().logToError(methodName + ": " + (exception == null ? "unknown" : exception.getMessage()));
        } catch (Exception ignored) {
        }
    }

    public static class ScopedAnalysisService {
        private final ScopedDataboardRepository scopedRepository;
        private final MessageProcessor messageProcessor;

        public ScopedAnalysisService(MontoyaApi api,
                                     ConfigLoader configLoader,
                                     ScopedDataboardRepository scopedRepository) {
            this.scopedRepository = Objects.requireNonNull(scopedRepository, "scopedRepository");
            this.messageProcessor = new MessageProcessor(
                    Objects.requireNonNull(api, "api"),
                    Objects.requireNonNull(configLoader, "configLoader")
            );
        }

        public ScopedAnalysisResult analyzeSelectedMainMessageIds(MessageRepository messageRepository,
                                                                 List<String> messageIds,
                                                                 String source,
                                                                 String label) {
            return analyzeSelectedMessages(loadSelectedMainMessages(messageRepository, messageIds), source, label);
        }

        public List<HttpRequestResponse> loadSelectedMainMessages(MessageRepository messageRepository, List<String> messageIds) {
            Objects.requireNonNull(messageRepository, "messageRepository");
            List<HttpRequestResponse> selectedMessages = new ArrayList<>();
            if (messageIds == null) {
                return selectedMessages;
            }

            for (String messageId : messageIds) {
                if (messageId == null || messageId.isBlank()) {
                    continue;
                }

                HttpRequestResponse requestResponse = messageRepository.loadMessage(messageId.trim());
                if (requestResponse != null) {
                    selectedMessages.add(requestResponse);
                }
            }
            return selectedMessages;
        }

        public ScopedAnalysisResult analyzeSelectedMessages(List<HttpRequestResponse> selectedMessages,
                                                           String source,
                                                           String label) {
            List<HttpRequestResponse> messages = normalizeMessages(selectedMessages);
            if (messages.isEmpty()) {
                throw new IllegalArgumentException("At least one selected message is required for scoped analysis.");
            }

            String scopeId = scopedRepository.createScopedDataboardScope(source, label);
            if (scopeId == null || scopeId.isBlank()) {
                throw new IllegalStateException("Unable to create scoped Databoard scope.");
            }

            int processedMessages = 0;
            int matchedMessages = 0;
            try {
                for (HttpRequestResponse message : messages) {
                    throwIfInterrupted();
                    ScopedMessageAnalysis analysis = analyzeOneMessage(message);
                    String scopedMessageId = StringProcessor.getRandomUUID();
                    scopedRepository.saveScopedMessage(
                            scopeId,
                            scopedMessageId,
                            message,
                            analysis.url(),
                            analysis.host(),
                            analysis.method(),
                            analysis.status(),
                            analysis.length(),
                            analysis.comment(),
                            analysis.color(),
                            ""
                    );
                    scopedRepository.saveScopedMatches(scopeId, scopedMessageId, analysis.extractedDataByRule());
                    processedMessages++;
                    if (!analysis.extractedDataByRule().isEmpty()) {
                        matchedMessages++;
                    }
                }
            } catch (CancellationException e) {
                scopedRepository.deleteScopedDataboardScope(scopeId);
                throw e;
            }

            Map<String, List<String>> extractedData = scopedRepository.loadScopedExtractedData(scopeId, "*", "*", "*");
            return new ScopedAnalysisResult(scopeId, processedMessages, matchedMessages, extractedData);
        }

        private ScopedMessageAnalysis analyzeOneMessage(HttpRequestResponse message) {
            HttpRequest request = message.request();
            HttpResponse response = message.response();
            RequestMetadata requestMetadata = buildRequestMetadata(request);
            MessageProcessor.ProcessedMessage processedMessage = messageProcessor.processRequestResponse(
                    requestMetadata.host(),
                    request,
                    response
            );

            Map<String, List<String>> extractedData = processedMessage.getExtractedDataByRule() == null
                    ? Collections.emptyMap()
                    : processedMessage.getExtractedDataByRule();
            return new ScopedMessageAnalysis(
                    requestMetadata.url(),
                    requestMetadata.host(),
                    safeMethod(request),
                    safeStatus(response),
                    safeLength(response),
                    processedMessage.getComment(),
                    processedMessage.getColor(),
                    extractedData
            );
        }

        private RequestMetadata buildRequestMetadata(HttpRequest request) {
            HttpService service = request == null ? null : request.httpService();
            String fallbackHost = service == null ? "" : service.host();
            String fallbackUrl = buildFallbackUrl(request, service);

            try {
                String url = request.url();
                String host = StringProcessor.getHostByUrl(url);
                if (host == null || host.isBlank()) {
                    host = fallbackHost;
                }
                return new RequestMetadata(url, host);
            } catch (Exception e) {
                return new RequestMetadata(fallbackUrl, fallbackHost);
            }
        }

        private String buildFallbackUrl(HttpRequest request, HttpService service) {
            if (request == null) {
                return "";
            }
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
                return "/";
            }
        }

        private String safeMethod(HttpRequest request) {
            try {
                String method = request.method();
                return method == null ? "" : method;
            } catch (Exception e) {
                return "";
            }
        }

        private String safeStatus(HttpResponse response) {
            try {
                return String.valueOf(response.statusCode());
            } catch (Exception e) {
                return "";
            }
        }

        private String safeLength(HttpResponse response) {
            try {
                return String.valueOf(response.toByteArray().length());
            } catch (Exception e) {
                return "0";
            }
        }

        private void throwIfInterrupted() {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Scoped analysis interrupted");
            }
        }

        private record RequestMetadata(String url, String host) {
        }

        private record ScopedMessageAnalysis(String url,
                                             String host,
                                             String method,
                                             String status,
                                             String length,
                                             String comment,
                                             String color,
                                             Map<String, List<String>> extractedDataByRule) {
        }
    }

    public static class ScopedAnalysisResult {
        private final String scopeId;
        private final int processedMessageCount;
        private final int matchedMessageCount;
        private final Map<String, List<String>> extractedDataByRule;

        private ScopedAnalysisResult(String scopeId,
                                     int processedMessageCount,
                                     int matchedMessageCount,
                                     Map<String, List<String>> extractedDataByRule) {
            this.scopeId = scopeId;
            this.processedMessageCount = processedMessageCount;
            this.matchedMessageCount = matchedMessageCount;
            this.extractedDataByRule = immutableCopy(extractedDataByRule);
        }

        private static Map<String, List<String>> immutableCopy(Map<String, List<String>> source) {
            Map<String, List<String>> copy = new LinkedHashMap<>();
            if (source != null) {
                for (Map.Entry<String, List<String>> entry : source.entrySet()) {
                    copy.put(entry.getKey(), List.copyOf(entry.getValue()));
                }
            }
            return Collections.unmodifiableMap(copy);
        }

        public String getScopeId() {
            return scopeId;
        }

        public int getProcessedMessageCount() {
            return processedMessageCount;
        }

        public int getMatchedMessageCount() {
            return matchedMessageCount;
        }

        public Map<String, List<String>> getExtractedDataByRule() {
            return extractedDataByRule;
        }
    }

    public static class ScopedMessageTableModel extends AbstractTableModel implements Datatable.TableFilterListener {
        private static final int DEFAULT_PAGE_SIZE = 100;

        private final MontoyaApi api;
        private final ConfigLoader configLoader;
        private final ScopedDataboardRepository scopedRepository;
        private final String scopeId;
        private final LinkedList<MessageEntry> pageLog = new LinkedList<>();
        private final ScopedMessageTable messageTable;
        private final JSplitPane splitPane;
        private final JLabel pageInfoLabel = new JLabel("Page 1/1 · Rows 0-0/0");
        private final JButton previousPageButton = new JButton("<");
        private final JButton nextPageButton = new JButton(">");
        private final AtomicInteger queryVersion = new AtomicInteger(0);

        private int currentPage = 1;
        private int pageSize = DEFAULT_PAGE_SIZE;
        private int totalRows = 0;
        private String currentHostFilter = "*";
        private String currentCommentFilter = "";
        private String currentMessageTable = "";
        private String currentMessageFilter = "";
        private SwingWorker<PageQueryResult, Void> pageWorker;

        public ScopedMessageTableModel(MontoyaApi api,
                                       ConfigLoader configLoader,
                                       ScopedDataboardRepository scopedRepository,
                                       String scopeId) {
            this.api = Objects.requireNonNull(api, "api");
            this.configLoader = Objects.requireNonNull(configLoader, "configLoader");
            this.scopedRepository = Objects.requireNonNull(scopedRepository, "scopedRepository");
            this.scopeId = Objects.requireNonNull(scopeId, "scopeId");

            UserInterface userInterface = api.userInterface();
            HttpRequestEditor requestViewer = userInterface.createHttpRequestEditor(READ_ONLY);
            HttpResponseEditor responseViewer = userInterface.createHttpResponseEditor(READ_ONLY);
            JSplitPane messagePane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
            messagePane.setLeftComponent(requestViewer.uiComponent());
            messagePane.setRightComponent(responseViewer.uiComponent());
            messagePane.setResizeWeight(0.5);

            messageTable = new ScopedMessageTable(this, requestViewer, responseViewer);
            messageTable.setDefaultRenderer(Object.class, new MessageRenderer(pageLog, messageTable));
            messageTable.setAutoCreateRowSorter(true);
            messageTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);

            JScrollPane scrollPane = new JScrollPane(messageTable);
            JPanel tablePanel = new JPanel(new BorderLayout());
            tablePanel.add(scrollPane, BorderLayout.CENTER);
            tablePanel.add(createPaginationPanel(), BorderLayout.SOUTH);

            splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
            splitPane.setLeftComponent(tablePanel);
            splitPane.setRightComponent(messagePane);
            updatePaginationControls();
        }

        private JPanel createPaginationPanel() {
            JPanel paginationPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
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
            return paginationPanel;
        }

        private int calculateTotalPages(int rowCount) {
            return Math.max(1, (rowCount + pageSize - 1) / pageSize);
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
                    int count = scopedRepository.countScopedMessageMetadata(scopeId, hostFilter, commentFilter, messageTableFilter, messageValueFilter);
                    int totalPages = Math.max(1, (count + requestedPageSize - 1) / requestedPageSize);
                    int safePage = Math.max(1, Math.min(requestedPage, totalPages));
                    int offset = (safePage - 1) * requestedPageSize;
                    List<SqliteMessageStore.MessageMetadata> metadata = scopedRepository.loadScopedMessageMetadataPage(
                            scopeId,
                            hostFilter,
                            commentFilter,
                            messageTableFilter,
                            messageValueFilter,
                            requestedPageSize,
                            offset
                    );
                    return new PageQueryResult(toMessageEntries(metadata), count, safePage);
                }

                @Override
                protected void done() {
                    if (isCancelled() || requestId != queryVersion.get()) {
                        return;
                    }

                    try {
                        PageQueryResult result = get();
                        totalRows = result.totalRows();
                        currentPage = result.currentPage();
                        applyPageEntries(result.entries());
                        updatePaginationControls();
                    } catch (Exception e) {
                        logError("ScopedMessageTableModel.loadPageFromDatabase", e);
                    }
                }
            };
            pageWorker.execute();
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

        private void applyPageEntries(List<MessageEntry> entries) {
            Runnable uiUpdate = () -> {
                synchronized (pageLog) {
                    pageLog.clear();
                    pageLog.addAll(entries);
                }
                fireTableDataChanged();
                messageTable.resetSelectionState();
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

        public List<SqliteMessageStore.MessageMetadata> loadCurrentMetadataForTest() {
            return scopedRepository.loadScopedMessageMetadataByFilter(
                    scopeId,
                    currentHostFilter,
                    currentCommentFilter,
                    currentMessageTable,
                    currentMessageFilter
            );
        }

        public void applyHostFilter(String filterText) {
            currentHostFilter = (filterText == null || filterText.trim().isEmpty()) ? "*" : filterText.trim();
            resetMessageFilterState();
            loadPageFromCurrentState(true);
        }

        @Override
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

        private void resetMessageFilterState() {
            currentMessageTable = "";
            currentMessageFilter = "";
        }

        public JSplitPane getSplitPane() {
            return splitPane;
        }

        public ScopedMessageTable getMessageTable() {
            return messageTable;
        }

        public void shutdown() {
            queryVersion.incrementAndGet();
            if (pageWorker != null && !pageWorker.isDone()) {
                pageWorker.cancel(true);
            }
            synchronized (pageLog) {
                pageLog.clear();
            }
            messageTable.shutdown();
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

                MessageEntry messageEntry = pageLog.get(rowIndex);
                return switch (columnIndex) {
                    case 0 -> messageEntry.getMethod();
                    case 1 -> messageEntry.getUrl();
                    case 2 -> messageEntry.getComment();
                    case 3 -> messageEntry.getStatus();
                    case 4 -> messageEntry.getLength();
                    case 5 -> messageEntry.getColor();
                    default -> "";
                };
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

        private void logError(String methodName, Exception exception) {
            try {
                api.logging().logToError(methodName + ": " + (exception == null ? "unknown" : exception.getMessage()));
            } catch (Exception ignored) {
            }
        }

        private record PageQueryResult(List<MessageEntry> entries, int totalRows, int currentPage) {
        }

        public class ScopedMessageTable extends JTable {
            private final ExecutorService executorService;
            private final HttpRequestEditor requestEditor;
            private final HttpResponseEditor responseEditor;
            private volatile int lastSelectedIndex = -1;

            ScopedMessageTable(ScopedMessageTableModel tableModel,
                               HttpRequestEditor requestEditor,
                               HttpResponseEditor responseEditor) {
                super(tableModel);
                this.requestEditor = requestEditor;
                this.responseEditor = responseEditor;
                this.executorService = Executors.newSingleThreadExecutor(scopedViewerThreadFactory());
            }

            private ThreadFactory scopedViewerThreadFactory() {
                return runnable -> {
                    Thread thread = new Thread(runnable, "hae-scoped-databoard-viewer");
                    thread.setDaemon(true);
                    return thread;
                };
            }

            private void resetSelectionState() {
                lastSelectedIndex = -1;
            }

            private void shutdown() {
                executorService.shutdownNow();
            }

            public boolean isViewerShutdownForTest() {
                return executorService.isShutdown();
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

                HttpRequestResponse httpRequestResponse = scopedRepository.loadScopedMessage(scopeId, messageEntry.getMessageId());
                if (httpRequestResponse == null) {
                    return;
                }

                HttpRequest request = httpRequestResponse.request();
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
}
