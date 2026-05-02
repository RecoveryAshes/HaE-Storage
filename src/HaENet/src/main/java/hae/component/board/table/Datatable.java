package hae.component.board.table;

import burp.api.montoya.MontoyaApi;
import hae.component.board.message.AiSummaryDisplay;
import hae.component.board.message.MessageTableModel;
import hae.utils.ConfigLoader;
import hae.utils.UIEnhancer;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class Datatable extends JPanel {
    private static final int INFORMATION_COLUMN = 1;
    private static final int AI_STATUS_COLUMN = 2;
    private static final int AI_VERDICT_COLUMN = 3;
    private static final int AI_CONFIDENCE_COLUMN = 4;
    private static final int INFORMATION_MIN_WIDTH = 260;
    private static final int INFORMATION_PREFERRED_WIDTH = 760;
    private static final int MEASURED_COLUMN_PADDING = 28;
    private static final int ID_PREFERRED_WIDTH = 52;
    private static final int AI_STATUS_PREFERRED_WIDTH = 112;
    private static final int AI_VERDICT_PREFERRED_WIDTH = 140;
    private static final int AI_CONFIDENCE_PREFERRED_WIDTH = 122;

    public interface TableFilterListener {
        void applyMessageFilter(String tableName, String filterText);
    }

    public interface AiSummaryProvider {
        AiSummaryDisplay aiSummaryFor(String ruleName, String value);
    }

    private final JTable dataTable;
    private final DefaultTableModel dataTableModel;
    private final JTextField searchField;
    private final JTextField secondSearchField;
    private final TableRowSorter<DefaultTableModel> sorter;
    private final JCheckBox searchMode = new JCheckBox("Reverse search");
    private final JCheckBox regexMode = new JCheckBox("Regex mode");
    private final JLabel statusLabel;
    private final String tabName;
    private final JPanel footerPanel;
    private AiSummaryProvider aiSummaryProvider;
    private SwingWorker<Void, Void> doubleClickWorker;
    private boolean invalidRegexActive;
    private TableFilterListener tableFilterListener;

    public Datatable(MontoyaApi api, ConfigLoader configLoader, String tabName, List<String> dataList) {
        this(api, configLoader, tabName, dataList, null);
    }

    public Datatable(MontoyaApi api, ConfigLoader configLoader, String tabName, List<String> dataList, AiSummaryProvider aiSummaryProvider) {
        this.tabName = tabName;
        this.aiSummaryProvider = aiSummaryProvider == null ? (ruleName, value) -> AiSummaryDisplay.empty() : aiSummaryProvider;

        String[] columnNames = {"#", "Information", "AI状态", "AI结论", "AI置信度"};
        this.dataTableModel = new DefaultTableModel(columnNames, 0);

        this.dataTable = new JTable(dataTableModel);
        this.sorter = new TableRowSorter<>(dataTableModel);
        this.searchField = new JTextField(10);
        this.secondSearchField = new JTextField(10);
        this.statusLabel = new JLabel();
        this.footerPanel = new JPanel(new BorderLayout(0, 5));

        initComponents(dataList);
    }

    private void initComponents(List<String> dataList) {
        dataTable.setRowSorter(sorter);
        dataTable.setName("datatable.table");
        searchField.setName("datatable.search");
        secondSearchField.setName("datatable.secondSearch");
        searchMode.setName("datatable.reverseSearch");
        regexMode.setName("datatable.regexMode");
        statusLabel.setName("datatable.status");

        // 设置ID排序
        sorter.setComparator(0, (Comparator<Integer>) Integer::compareTo);

        for (String item : dataList) {
            if (!item.isEmpty()) {
                addRowToTable(item);
            }
        }

        UIEnhancer.setTextFieldPlaceholder(searchField, "Search");
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                performSearch();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                performSearch();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                performSearch();
            }

        });

        UIEnhancer.setTextFieldPlaceholder(secondSearchField, "Second search");
        secondSearchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                performSearch();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                performSearch();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                performSearch();
            }

        });

        // 设置布局
        JScrollPane scrollPane = new JScrollPane(dataTable);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);

        configureColumnWidths();

        setLayout(new BorderLayout(0, 5));

        JPanel optionsPanel = new JPanel();
        optionsPanel.setLayout(new BoxLayout(optionsPanel, BoxLayout.X_AXIS));

        // Settings按钮
        JPanel settingMenuPanel = new JPanel(new GridLayout(2, 1));
        settingMenuPanel.setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
        JPopupMenu settingMenu = new JPopupMenu();
        settingMenuPanel.add(searchMode);
        settingMenuPanel.add(regexMode);
        regexMode.setSelected(true);
        searchMode.addItemListener(e -> performSearch());
        regexMode.addItemListener(e -> performSearch());
        settingMenu.add(settingMenuPanel);

        JButton settingsButton = new JButton("Settings");
        setMenuShow(settingMenu, settingsButton);

        optionsPanel.add(settingsButton);
        optionsPanel.add(Box.createHorizontalStrut(5));
        optionsPanel.add(searchField);
        optionsPanel.add(Box.createHorizontalStrut(5));
        optionsPanel.add(secondSearchField);

        footerPanel.setBorder(BorderFactory.createEmptyBorder(2, 3, 5, 3));
        footerPanel.add(optionsPanel, BorderLayout.CENTER);
        footerPanel.add(statusLabel, BorderLayout.EAST);

        dataTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateStatusLabel();
                notifySelectedValue();
            }
        });

        add(scrollPane, BorderLayout.CENTER);
        add(footerPanel, BorderLayout.SOUTH);
        updateStatusLabel();
    }

    private void setMenuShow(JPopupMenu menu, JButton button) {
        button.addActionListener(e -> {
            Point buttonLocation = button.getLocationOnScreen();
            Dimension menuSize = menu.getPreferredSize();
            int x = buttonLocation.x + (button.getWidth() - menuSize.width) / 2;
            int y = buttonLocation.y - menuSize.height;
            menu.show(button, x - buttonLocation.x, y - buttonLocation.y);
        });
    }


    private void addRowToTable(String value) {
        int rowCount = dataTableModel.getRowCount();
        int id = rowCount > 0 ? (Integer) dataTableModel.getValueAt(rowCount - 1, 0) + 1 : 1;
        AiSummaryDisplay aiSummary = loadAiSummary(value);
        Object[] rowData = new Object[]{
                id,
                value,
                aiSummary.getAiStatus(),
                aiSummary.getAiVerdict(),
                aiSummary.getAiConfidence()
        };
        dataTableModel.addRow(rowData);
    }

    private AiSummaryDisplay loadAiSummary(String value) {
        try {
            AiSummaryDisplay aiSummary = aiSummaryProvider.aiSummaryFor(tabName, value);
            return aiSummary == null ? AiSummaryDisplay.empty() : aiSummary;
        } catch (RuntimeException e) {
            return AiSummaryDisplay.empty();
        }
    }

    private void configureColumnWidths() {
        dataTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);

        configureMeasuredPreferredWidthColumn(0, ID_PREFERRED_WIDTH);

        TableColumn informationColumn = dataTable.getColumnModel().getColumn(INFORMATION_COLUMN);
        informationColumn.setMinWidth(INFORMATION_MIN_WIDTH);
        informationColumn.setPreferredWidth(INFORMATION_PREFERRED_WIDTH);
        informationColumn.setMaxWidth(Integer.MAX_VALUE);
        informationColumn.setResizable(true);

        configureMeasuredPreferredWidthColumn(AI_STATUS_COLUMN, AI_STATUS_PREFERRED_WIDTH);
        configureMeasuredPreferredWidthColumn(AI_VERDICT_COLUMN, AI_VERDICT_PREFERRED_WIDTH);
        configureMeasuredPreferredWidthColumn(AI_CONFIDENCE_COLUMN, AI_CONFIDENCE_PREFERRED_WIDTH);
    }

    private void configureMeasuredPreferredWidthColumn(int columnIndex, int preferredWidthFloor) {
        int width = Math.max(measuredColumnWidth(columnIndex), preferredWidthFloor);
        TableColumn column = dataTable.getColumnModel().getColumn(columnIndex);
        column.setPreferredWidth(width);
        column.setResizable(true);
    }

    private int measuredColumnWidth(int columnIndex) {
        int width = textWidth(dataTable.getColumnName(columnIndex), dataTable.getTableHeader().getFontMetrics(dataTable.getTableHeader().getFont()));
        FontMetrics cellMetrics = dataTable.getFontMetrics(dataTable.getFont());
        for (int row = 0; row < dataTableModel.getRowCount(); row++) {
            Object value = dataTableModel.getValueAt(row, columnIndex);
            width = Math.max(width, textWidth(value == null ? "" : value.toString(), cellMetrics));
        }
        return width + MEASURED_COLUMN_PADDING;
    }

    private int textWidth(String text, FontMetrics metrics) {
        return metrics.stringWidth(text == null ? "" : text);
    }

    public List<String> getInformationValues() {
        List<String> values = new ArrayList<>(dataTableModel.getRowCount());
        for (int row = 0; row < dataTableModel.getRowCount(); row++) {
            Object value = dataTableModel.getValueAt(row, INFORMATION_COLUMN);
            if (value != null) {
                values.add(value.toString());
            }
        }
        return values;
    }

    public void refreshAiSummaries(AiSummaryProvider aiSummaryProvider) {
        if (aiSummaryProvider != null) {
            this.aiSummaryProvider = aiSummaryProvider;
        }
        for (int row = 0; row < dataTableModel.getRowCount(); row++) {
            Object value = dataTableModel.getValueAt(row, INFORMATION_COLUMN);
            AiSummaryDisplay aiSummary = loadAiSummary(value == null ? "" : value.toString());
            dataTableModel.setValueAt(aiSummary.getAiStatus(), row, AI_STATUS_COLUMN);
            dataTableModel.setValueAt(aiSummary.getAiVerdict(), row, AI_VERDICT_COLUMN);
            dataTableModel.setValueAt(aiSummary.getAiConfidence(), row, AI_CONFIDENCE_COLUMN);
        }
        dataTable.revalidate();
        dataTable.repaint();
    }

    private void performSearch() {
        invalidRegexActive = false;
        List<RowFilter<Object, Object>> filters = new ArrayList<>();

        if (hasSearchInput(searchField)) {
            RowFilter<Object, Object> rowFilter = getObjectObjectRowFilter(searchField, true);
            if (rowFilter != null) {
                filters.add(rowFilter);
            }
        }

        if (hasSearchInput(secondSearchField)) {
            RowFilter<Object, Object> rowFilter = getObjectObjectRowFilter(secondSearchField, false);
            if (rowFilter != null) {
                filters.add(rowFilter);
            }
        }

        sorter.setRowFilter(filters.isEmpty() ? null : RowFilter.andFilter(filters));
        updateStatusLabel();
    }

    private RowFilter<Object, Object> getObjectObjectRowFilter(JTextField searchField, boolean firstFlag) {
        String searchFieldTextText = searchField.getText().toLowerCase();
        boolean firstFlagReturn = searchMode.isSelected() && firstFlag;

        if (regexMode.isSelected()) {
            Pattern pattern;
            try {
                pattern = Pattern.compile(searchFieldTextText, Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException e) {
                invalidRegexActive = true;
                return null;
            }

            Pattern finalPattern = pattern;
            return new RowFilter<>() {
                public boolean include(Entry<?, ?> entry) {
                    String entryValue = ((String) entry.getValue(INFORMATION_COLUMN)).toLowerCase();
                    return finalPattern.matcher(entryValue).find() != firstFlagReturn;
                }
            };
        }

        return new RowFilter<>() {
            public boolean include(Entry<?, ?> entry) {
                String entryValue = ((String) entry.getValue(INFORMATION_COLUMN)).toLowerCase();
                return entryValue.contains(searchFieldTextText) != firstFlagReturn;
            }
        };
    }

    private boolean hasSearchInput(JTextField field) {
        if (field.getText().isEmpty()) {
            return false;
        }

        if (UIEnhancer.hasUserInput(field)) {
            return true;
        }

        Object placeholderText = field.getClientProperty("placeholderText");
        return placeholderText != null && !field.getText().isEmpty() && !field.getText().equals(placeholderText.toString());
    }

    private void updateStatusLabel() {
        String statusText = "Showing " + dataTable.getRowCount() + " of " + dataTableModel.getRowCount();
        int selectedCount = dataTable.getSelectedRowCount();
        if (selectedCount > 0) {
            statusText += " · Selected " + selectedCount;
        }

        if (invalidRegexActive) {
            statusText = "Invalid regex - " + statusText;
        }

        statusLabel.setText(statusText);
    }

    private void handleDoubleClick(int selectedRow, TableFilterListener messagePanel) {
        if (doubleClickWorker != null && !doubleClickWorker.isDone()) {
            doubleClickWorker.cancel(true);
        }

        doubleClickWorker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                String rowData = dataTable.getValueAt(selectedRow, INFORMATION_COLUMN).toString();
                SwingUtilities.invokeLater(() -> {
                    if (!isCancelled()) {
                        messagePanel.applyMessageFilter(tabName, rowData);
                    }
                });
                return null;
            }
        };
        doubleClickWorker.execute();
    }

    private void notifySelectedValue() {
        if (tableFilterListener == null) {
            return;
        }
        int selectedRow = dataTable.getSelectedRow();
        if (selectedRow < 0) {
            return;
        }
        Object value = dataTable.getValueAt(selectedRow, INFORMATION_COLUMN);
        if (value == null) {
            return;
        }
        tableFilterListener.applyMessageFilter(tabName, value.toString());
    }

    public void setTableListener(MessageTableModel messagePanel) {
        setTableListener((tableName, filterText) -> messagePanel.applyMessageFilter(tableName, filterText));
    }

    public void setTableListener(TableFilterListener messagePanel) {
        if (messagePanel == null) {
            return;
        }
        this.tableFilterListener = messagePanel;

        // 表格复制功能
        dataTable.setTransferHandler(new TransferHandler() {
            @Override
            public void exportToClipboard(JComponent comp, Clipboard clip, int action) throws IllegalStateException {
                if (comp instanceof JTable) {
                    StringSelection stringSelection = new StringSelection(getSelectedDataAtTable((JTable) comp).replace("\0", "").replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", ""));
                    clip.setContents(stringSelection, null);
                } else {
                    super.exportToClipboard(comp, clip, action);
                }
            }
        });

        dataTable.setDefaultEditor(Object.class, null);

        // 表格内容双击事件
        dataTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int selectedRow = dataTable.getSelectedRow();
                    if (selectedRow != -1) {
                        handleDoubleClick(selectedRow, messagePanel);
                    }
                }
            }
        });
    }

    public String getSelectedDataAtTable(JTable table) {
        int[] selectRows = table.getSelectedRows();
        StringBuilder selectData = new StringBuilder();

        for (int row : selectRows) {
            selectData.append(table.getValueAt(row, INFORMATION_COLUMN).toString()).append("\r\n");
        }

        if (!selectData.isEmpty()) {
            selectData.delete(selectData.length() - 2, selectData.length());
        } else {
            return "";
        }

        return selectData.toString();
    }


    public JTable getDataTable() {
        return this.dataTable;
    }

    JLabel getStatusLabel() {
        return statusLabel;
    }

    JTextField getSearchField() {
        return searchField;
    }

    JTextField getSecondSearchField() {
        return secondSearchField;
    }

    JCheckBox getReverseSearchCheckBox() {
        return searchMode;
    }

    JCheckBox getRegexModeCheckBox() {
        return regexMode;
    }
}
