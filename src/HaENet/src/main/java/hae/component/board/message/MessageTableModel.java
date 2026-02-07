package hae.component.board.message;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.UserInterface;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import hae.Config;
import hae.instances.http.utils.MessageProcessor;
import hae.storage.SqliteMessageStore;
import hae.utils.ConfigLoader;
import hae.utils.string.HashCalculator;
import hae.utils.string.StringProcessor;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static burp.api.montoya.ui.editor.EditorOptions.READ_ONLY;

public class MessageTableModel extends AbstractTableModel {
    private static final int DEFAULT_PAGE_SIZE = 100;

    private final MontoyaApi api;
    private final ConfigLoader configLoader;
    private final SqliteMessageStore messageStore;
    private final MessageProcessor messageProcessor;
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

    public MessageTableModel(MontoyaApi api, ConfigLoader configLoader) {
        this.api = api;
        this.configLoader = configLoader;
        this.messageStore = new SqliteMessageStore(api, configLoader);
        this.messageProcessor = new MessageProcessor(api, configLoader);
        this.pageLog = new LinkedList<>();

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

        TableRowSorter<DefaultTableModel> sorter = getDefaultTableModelTableRowSorter();
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
                int count = messageStore.countMessageMetadata(hostFilter, commentFilter, messageTableFilter, messageValueFilter);
                int totalPages = Math.max(1, (count + requestedPageSize - 1) / requestedPageSize);
                int safePage = Math.max(1, Math.min(requestedPage, totalPages));
                int offset = (safePage - 1) * requestedPageSize;
                List<SqliteMessageStore.MessageMetadata> metadata = messageStore.loadMessageMetadataPage(hostFilter, commentFilter, messageTableFilter, messageValueFilter, requestedPageSize, offset);
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

    private TableRowSorter<DefaultTableModel> getDefaultTableModelTableRowSorter() {
        TableRowSorter<DefaultTableModel> sorter = (TableRowSorter<DefaultTableModel>) messageTable.getRowSorter();

        sorter.setComparator(4, (Comparator<String>) (s1, s2) -> {
            Integer length1 = Integer.parseInt(s1);
            Integer length2 = Integer.parseInt(s2);
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

    public synchronized void add(HttpRequestResponse messageInfo, String url, String method, String status, String length, String comment, String color, boolean flag) {
        if (messageInfo == null) {
            return;
        }

        if (comment == null || comment.trim().isEmpty()) {
            return;
        }

        if (color == null || color.trim().isEmpty()) {
            return;
        }

        String contentHash = calculateMessageHash(messageInfo);
        String messageId = StringProcessor.getRandomUUID();

        if (flag && messageStore.existsDuplicate(url, comment, color, contentHash)) {
            return;
        }

        if (flag) {
            Map<String, List<String>> extractedDataByRule = buildExtractedDataByRule(messageInfo);
            messageStore.saveMessage(messageId, messageInfo, url, method, status, length, comment, color, contentHash, extractedDataByRule);
        }

        loadPageFromCurrentState(false);
    }

    private Map<String, List<String>> buildExtractedDataByRule(HttpRequestResponse messageInfo) {
        Map<String, Set<String>> mergedMap = new LinkedHashMap<>();
        try {
            String host = StringProcessor.getHostByUrl(messageInfo.request().url());
            appendExtractedData(mergedMap, messageProcessor.processRequest(host, messageInfo.request(), false));
            appendExtractedData(mergedMap, messageProcessor.processResponse(host, messageInfo.response(), false));
        } catch (Exception e) {
            api.logging().logToError("buildExtractedDataByRule: " + e.getMessage());
        }

        Map<String, List<String>> normalizedMap = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : mergedMap.entrySet()) {
            normalizedMap.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }

        return normalizedMap;
    }

    private void appendExtractedData(Map<String, Set<String>> mergedMap, List<Map<String, String>> extractedList) {
        if (extractedList == null || extractedList.isEmpty()) {
            return;
        }

        for (Map<String, String> extractedMap : extractedList) {
            if (extractedMap == null || extractedMap.isEmpty()) {
                continue;
            }

            for (Map.Entry<String, String> entry : extractedMap.entrySet()) {
                String ruleName = StringProcessor.extractItemName(entry.getKey());
                if (ruleName == null || ruleName.isBlank()) {
                    continue;
                }

                String joinedData = entry.getValue();
                if (joinedData == null || joinedData.isBlank()) {
                    continue;
                }

                Set<String> valueSet = mergedMap.computeIfAbsent(ruleName, ignored -> new LinkedHashSet<>());
                String[] values = joinedData.split(Pattern.quote(Config.boundary));
                for (String value : values) {
                    if (value != null) {
                        String normalizedValue = value.trim();
                        if (!normalizedValue.isEmpty()) {
                            valueSet.add(normalizedValue);
                        }
                    }
                }
            }
        }
    }

    private String calculateMessageHash(HttpRequestResponse messageInfo) {
        try {
            byte[] reqBytes = messageInfo.request().toByteArray().getBytes();
            byte[] resBytes = messageInfo.response().toByteArray().getBytes();
            byte[] allBytes = new byte[reqBytes.length + resBytes.length];
            System.arraycopy(reqBytes, 0, allBytes, 0, reqBytes.length);
            System.arraycopy(resBytes, 0, allBytes, reqBytes.length, resBytes.length);
            return HashCalculator.calculateHash(allBytes);
        } catch (Exception e) {
            return "";
        }
    }

    public void deleteByHost(String filterText) {
        if (currentWorker != null && !currentWorker.isDone()) {
            currentWorker.cancel(true);
        }

        currentWorker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                messageStore.deleteByHostPattern(filterText);
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
        int deletedCount = messageStore.deleteAllMessages();

        resetMessageFilterState();
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
        } catch (Exception ignored) {
        }

        try {
            queryVersion.incrementAndGet();
            messageStore.deleteAllMessages();
            synchronized (pageLog) {
                pageLog.clear();
            }
            totalRows = 0;
            currentPage = 1;
        } catch (Exception ignored) {
        }

        try {
            messageTable.shutdown();
        } catch (Exception ignored) {
        }
    }

    public String getStoragePath() {
        return messageStore.getDatabasePath();
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
            MessageEntry messageEntry;
            synchronized (pageLog) {
                if (lastSelectedIndex < 0 || lastSelectedIndex >= pageLog.size()) {
                    return;
                }
                messageEntry = pageLog.get(lastSelectedIndex);
            }

            HttpRequestResponse httpRequestResponse = messageStore.loadMessage(messageEntry.getMessageId());
            if (httpRequestResponse == null) {
                return;
            }

            requestEditor.setRequest(HttpRequest.httpRequest(httpRequestResponse.httpService(), httpRequestResponse.request().toByteArray()));
            int responseSizeWithMb = httpRequestResponse.response().toString().length() / 1024 / 1024;
            if ((responseSizeWithMb < Integer.parseInt(configLoader.getLimitSize())) || configLoader.getLimitSize().equals("0")) {
                responseEditor.setResponse(httpRequestResponse.response());
            } else {
                responseEditor.setResponse(HttpResponse.httpResponse("Exceeds length limit."));
            }
        }
    }
}
