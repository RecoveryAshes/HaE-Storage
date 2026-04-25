package hae.component.board.table;

import burp.api.montoya.MontoyaApi;
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
    private final MontoyaApi api;
    private final ConfigLoader configLoader;
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
    private SwingWorker<Void, Void> doubleClickWorker;
    private boolean invalidRegexActive;

    public Datatable(MontoyaApi api, ConfigLoader configLoader, String tabName, List<String> dataList) {
        this.api = api;
        this.configLoader = configLoader;
        this.tabName = tabName;

        String[] columnNames = {"#", "Information"};
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
                addRowToTable(new Object[]{item});
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

        TableColumn idColumn = dataTable.getColumnModel().getColumn(0);
        idColumn.setPreferredWidth(50);
        idColumn.setMaxWidth(100);

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


    private void addRowToTable(Object[] data) {
        int rowCount = dataTableModel.getRowCount();
        int id = rowCount > 0 ? (Integer) dataTableModel.getValueAt(rowCount - 1, 0) + 1 : 1;
        Object[] rowData = new Object[data.length + 1];
        rowData[0] = id;
        System.arraycopy(data, 0, rowData, 1, data.length);
        dataTableModel.addRow(rowData);
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
                    String entryValue = ((String) entry.getValue(1)).toLowerCase();
                    return finalPattern.matcher(entryValue).find() != firstFlagReturn;
                }
            };
        }

        return new RowFilter<>() {
            public boolean include(Entry<?, ?> entry) {
                String entryValue = ((String) entry.getValue(1)).toLowerCase();
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

    private void handleDoubleClick(int selectedRow, MessageTableModel messagePanel) {
        if (doubleClickWorker != null && !doubleClickWorker.isDone()) {
            doubleClickWorker.cancel(true);
        }

        doubleClickWorker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                String rowData = dataTable.getValueAt(selectedRow, 1).toString();
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

    public void setTableListener(MessageTableModel messagePanel) {
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
            selectData.append(table.getValueAt(row, 1).toString()).append("\r\n");
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
