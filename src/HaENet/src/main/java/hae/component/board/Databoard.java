package hae.component.board;

import burp.api.montoya.MontoyaApi;
import hae.cache.DataCache;
import hae.component.board.message.MessageTableModel;
import hae.component.board.message.MessageTableModel.MessageTable;
import hae.component.board.table.Datatable;
import hae.utils.ConfigLoader;
import hae.utils.UIEnhancer;
import hae.utils.string.StringProcessor;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.TableColumnModel;
import java.awt.*;
import java.awt.event.*;
import java.text.Collator;
import java.util.*;
import java.util.List;

public class Databoard extends JPanel {
    private static Boolean isMatchHost = false;
    private final MontoyaApi api;
    private final ConfigLoader configLoader;
    private final MessageTableModel messageTableModel;
    private final DefaultComboBoxModel<String> comboBoxModel = new DefaultComboBoxModel<>();
    private final JComboBox<String> hostComboBox = new JComboBox<>(comboBoxModel);
    private JTextField hostTextField;
    private JTabbedPane dataTabbedPane;
    private JSplitPane splitPane;
    private MessageTable messageTable;
    private JProgressBar progressBar;
    private SwingWorker<Map<String, List<String>>, Integer> handleComboBoxWorker;
    private SwingWorker<Void, Void> applyHostFilterWorker;
    private SwingWorker<List<String>, Void> hostListWorker;
    private List<String> cachedHostList = new ArrayList<>();
    private long cachedHostListAt = 0L;
    private static final long HOST_LIST_CACHE_TTL_MILLIS = 3000L;

    public Databoard(MontoyaApi api, ConfigLoader configLoader, MessageTableModel messageTableModel) {
        this.api = api;
        this.configLoader = configLoader;
        this.messageTableModel = messageTableModel;

        initComponents();
    }

    private void initComponents() {
        setLayout(new GridBagLayout());
        ((GridBagLayout) getLayout()).columnWidths = new int[]{25, 0, 0, 0, 20, 0};
        ((GridBagLayout) getLayout()).rowHeights = new int[]{0, 65, 20, 0, 0};
        ((GridBagLayout) getLayout()).columnWeights = new double[]{0.0, 0.0, 1.0, 0.0, 0.0, 1.0E-4};
        ((GridBagLayout) getLayout()).rowWeights = new double[]{0.0, 1.0, 0.0, 0.0, 1.0E-4};
        JLabel hostLabel = new JLabel("Host:");

        JButton clearDataButton = new JButton("Clear data");
        JButton clearCacheButton = new JButton("Clear cache");
        JButton clearStorageButton = new JButton("Clear storage");
        JButton actionButton = new JButton("Action");
        JPanel menuPanel = new JPanel(new GridLayout(3, 1, 0, 5));
        menuPanel.setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
        JPopupMenu menu = new JPopupMenu();
        menuPanel.add(clearDataButton);
        menuPanel.add(clearCacheButton);
        menuPanel.add(clearStorageButton);
        menu.add(menuPanel);

        hostTextField = new JTextField();
        String defaultText = "Please enter the host";
        UIEnhancer.setTextFieldPlaceholder(hostTextField, defaultText);
        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);

        dataTabbedPane = new JTabbedPane(JTabbedPane.TOP);
        dataTabbedPane.setPreferredSize(new Dimension(500, 0));
        dataTabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        dataTabbedPane.addChangeListener(e -> {
            int selectedIndex = dataTabbedPane.getSelectedIndex();
            String selectedTitle = "";
            if (selectedIndex != -1) {
                selectedTitle = dataTabbedPane.getTitleAt(selectedIndex);
            }

            messageTableModel.applyCommentFilter(StringProcessor.extractItemName(selectedTitle));
        });

        actionButton.addActionListener(e -> {
            int x = 0;
            int y = actionButton.getHeight();
            menu.show(actionButton, x, y);
        });

        clearDataButton.addActionListener(this::clearDataActionPerformed);
        clearCacheButton.addActionListener(this::clearCacheActionPerformed);
        clearStorageButton.addActionListener(this::clearStorageActionPerformed);

        progressBar = new JProgressBar();
        splitPane.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                resizePanel();
            }
        });

        splitPane.setVisible(false);
        progressBar.setVisible(false);

        add(hostLabel, new GridBagConstraints(1, 0, 1, 1, 0.0, 0.0, GridBagConstraints.CENTER, GridBagConstraints.BOTH,
                new Insets(8, 0, 5, 5), 0, 0));
        add(hostTextField, new GridBagConstraints(2, 0, 1, 1, 0.0, 0.0, GridBagConstraints.CENTER, GridBagConstraints.BOTH,
                new Insets(8, 0, 5, 5), 0, 0));
        add(actionButton, new GridBagConstraints(3, 0, 1, 1, 0.0, 0.0, GridBagConstraints.CENTER, GridBagConstraints.BOTH,
                new Insets(8, 0, 5, 5), 0, 0));

        add(splitPane, new GridBagConstraints(1, 1, 3, 1, 0.0, 1.0,
                GridBagConstraints.CENTER, GridBagConstraints.BOTH,
                new Insets(0, 5, 0, 5), 0, 0));
        add(progressBar, new GridBagConstraints(1, 2, 3, 1, 1.0, 0.0,
                GridBagConstraints.CENTER, GridBagConstraints.HORIZONTAL,
                new Insets(0, 5, 0, 5), 0, 0));
        hostComboBox.setMaximumRowCount(5);
        add(hostComboBox, new GridBagConstraints(2, 0, 1, 1, 0.0, 0.0, GridBagConstraints.CENTER, GridBagConstraints.BOTH,
                new Insets(8, 0, 5, 5), 0, 0));

        setAutoMatch();
    }

    private void resizePanel() {
        splitPane.setDividerLocation(0.4);
        TableColumnModel columnModel = messageTable.getColumnModel();
        int totalWidth = (int) (getWidth() * 0.6);
        columnModel.getColumn(0).setPreferredWidth((int) (totalWidth * 0.1));
        columnModel.getColumn(1).setPreferredWidth((int) (totalWidth * 0.3));
        columnModel.getColumn(2).setPreferredWidth((int) (totalWidth * 0.3));
        columnModel.getColumn(3).setPreferredWidth((int) (totalWidth * 0.1));
        columnModel.getColumn(4).setPreferredWidth((int) (totalWidth * 0.1));
        columnModel.getColumn(5).setPreferredWidth((int) (totalWidth * 0.1));
    }

    private void setProgressBar(boolean status, String message, int progress) {
        progressBar.setIndeterminate(status && progress <= 0);
        progressBar.setString(message);
        progressBar.setStringPainted(true);
        progressBar.setMaximum(100);

        if (progress > 0) {
            progressBar.setValue(progress);
        } else if (!status) {
            progressBar.setValue(progressBar.getMaximum());
        }
    }

    private void setAutoMatch() {
        hostComboBox.setSelectedItem(null);
        hostComboBox.addActionListener(this::handleComboBoxAction);

        hostTextField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                handleKeyEvents(e);
            }
        });

        hostTextField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                filterComboBoxList();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                filterComboBoxList();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                filterComboBoxList();
            }

        });
    }

    private void handleComboBoxAction(ActionEvent e) {
        if (!isMatchHost && hostComboBox.getSelectedItem() != null) {
            String selectedHost = hostComboBox.getSelectedItem().toString();

            if (getHostByList().contains(selectedHost)) {
                hostTextField.setText(selectedHost);
                hostComboBox.setPopupVisible(false);

                if (handleComboBoxWorker != null && !handleComboBoxWorker.isDone()) {
                    progressBar.setVisible(false);
                    handleComboBoxWorker.cancel(true);
                }

                handleComboBoxWorker = new DataLoadingWorker(selectedHost);

                handleComboBoxWorker.execute();
            }
        }
    }

    private void handleKeyEvents(KeyEvent e) {
        isMatchHost = true;
        int keyCode = e.getKeyCode();

        if (keyCode == KeyEvent.VK_SPACE && hostComboBox.isPopupVisible()) {
            e.setKeyCode(KeyEvent.VK_ENTER);
        }

        if (Arrays.asList(KeyEvent.VK_DOWN, KeyEvent.VK_UP).contains(keyCode)) {
            hostComboBox.dispatchEvent(e);
        }

        if (keyCode == KeyEvent.VK_ENTER) {
            isMatchHost = false;
            handleComboBoxAction(null);
        }

        if (keyCode == KeyEvent.VK_ESCAPE) {
            hostComboBox.setPopupVisible(false);
        }

        isMatchHost = false;
    }

    private Map<String, List<String>> getSelectedMapByHost(String selectedHost, DataLoadingWorker worker) {
        Map<String, List<String>> selectedDataMap = messageTableModel.loadExtractedDataByHost(selectedHost);
        if (worker != null) {
            worker.publishProgress(90);
        }

        return selectedDataMap != null ? selectedDataMap : new HashMap<>();
    }

    private void filterComboBoxList() {
        isMatchHost = true;
        comboBoxModel.removeAllElements();
        String input = hostTextField.getText().toLowerCase();

        if (!input.isEmpty()) {
            refreshHostListAsync(false);
            for (String host : getCachedHostList()) {
                String lowerCaseHost = host.toLowerCase();
                if (lowerCaseHost.contains(input)) {
                    if (lowerCaseHost.equals(input)) {
                        comboBoxModel.insertElementAt(lowerCaseHost, 0);
                        comboBoxModel.setSelectedItem(lowerCaseHost);
                    } else {
                        comboBoxModel.addElement(host);
                    }
                }
            }
        }

        hostComboBox.setPopupVisible(comboBoxModel.getSize() > 0);
        isMatchHost = false;
    }

    private void applyHostFilter(String filterText) {
        if (applyHostFilterWorker != null && !applyHostFilterWorker.isDone()) {
            applyHostFilterWorker.cancel(true);
        }

        applyHostFilterWorker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                messageTableModel.applyHostFilter(filterText);

                return null;
            }
        };

        applyHostFilterWorker.execute();
    }

    private List<String> getHostByList() {
        refreshHostListAsync(true);
        return getCachedHostList();
    }

    private List<String> getCachedHostList() {
        synchronized (this) {
            return new ArrayList<>(cachedHostList);
        }
    }

    private void refreshHostListAsync(boolean force) {
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (!force && now - cachedHostListAt < HOST_LIST_CACHE_TTL_MILLIS) {
                return;
            }
            if (hostListWorker != null && !hostListWorker.isDone()) {
                return;
            }
        }

        hostListWorker = new SwingWorker<>() {
            @Override
            protected List<String> doInBackground() {
                return messageTableModel.loadMatchedHosts();
            }

            @Override
            protected void done() {
                try {
                    List<String> hosts = get();
                    synchronized (Databoard.this) {
                        cachedHostList = hosts == null ? new ArrayList<>() : new ArrayList<>(hosts);
                        cachedHostListAt = System.currentTimeMillis();
                    }
                } catch (Exception e) {
                    api.logging().logToOutput("refreshHostListAsync: " + e.getMessage());
                }
            }
        };
        hostListWorker.execute();
    }

    private void clearCacheActionPerformed(ActionEvent e) {
        int retCode = JOptionPane.showConfirmDialog(this, "Do you want to clear cache?", "Info",
                JOptionPane.YES_NO_OPTION);
        if (retCode == JOptionPane.YES_OPTION) {
            DataCache.clear();
        }
    }

    private void clearDataActionPerformed(ActionEvent e) {
        int retCode = JOptionPane.showConfirmDialog(this, "Do you want to clear data?", "Info",
                JOptionPane.YES_NO_OPTION);
        String host = hostTextField.getText();
        if (retCode == JOptionPane.YES_OPTION && !host.isEmpty()) {
            dataTabbedPane.removeAll();
            splitPane.setVisible(false);
            progressBar.setVisible(false);

            messageTableModel.deleteByHost(host);

            hostTextField.setText("");
        }
    }

    private void clearStorageActionPerformed(ActionEvent e) {
        int retCode = JOptionPane.showConfirmDialog(this, "Do you want to clear all stored history?", "Info", JOptionPane.YES_NO_OPTION);
        if (retCode == JOptionPane.YES_OPTION) {
            int deletedCount = messageTableModel.clearStorageHistory();
            splitPane.setVisible(false);
            progressBar.setVisible(false);
            dataTabbedPane.removeAll();
            hostTextField.setText("");
            JOptionPane.showMessageDialog(this, String.format("Storage cleared. Deleted %s history entries.", deletedCount), "Info", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    // 定义为内部类
    private class DataLoadingWorker extends SwingWorker<Map<String, List<String>>, Integer> {
        private final String selectedHost;

        public DataLoadingWorker(String selectedHost) {
            this.selectedHost = selectedHost;
            progressBar.setVisible(true);
        }

        @Override
        protected Map<String, List<String>> doInBackground() throws Exception {
            return getSelectedMapByHost(selectedHost, this);
        }

        @Override
        protected void process(List<Integer> chunks) {
            if (!chunks.isEmpty()) {
                int progress = chunks.get(chunks.size() - 1);
                setProgressBar(true, "Loading... " + progress + "%", progress);
            }
        }

        @Override
        protected void done() {
            if (!isCancelled()) {
                try {
                    Map<String, List<String>> selectedDataMap = get();
                    if (selectedDataMap != null && !selectedDataMap.isEmpty()) {
                        dataTabbedPane.removeAll();

                        for (Map.Entry<String, List<String>> entry : selectedDataMap.entrySet()) {
                            String tabTitle = String.format("%s (%s)", entry.getKey(), entry.getValue().size());
                            Datatable datatablePanel = new Datatable(api, configLoader, entry.getKey(), entry.getValue());
                            datatablePanel.setTableListener(messageTableModel);
                            insertTabSorted(dataTabbedPane, tabTitle, datatablePanel);
                        }

                        JSplitPane messageSplitPane = messageTableModel.getSplitPane();
                        splitPane.setLeftComponent(dataTabbedPane);
                        splitPane.setRightComponent(messageSplitPane);
                        messageTable = messageTableModel.getMessageTable();
                        resizePanel();

                        splitPane.setVisible(true);
                        dataTabbedPane.setSelectedIndex(0);
                        applyHostFilter(selectedHost);
                        setProgressBar(false, "OK", 100);
                    } else {
                        setProgressBar(false, "Error", 0);
                    }
                } catch (Exception e) {
                    api.logging().logToOutput("DataLoadingWorker: " + e.getMessage());
                    setProgressBar(false, "Error", 0);
                }
            }
        }

        public static void insertTabSorted(JTabbedPane tabbedPane, String title, Component component) {
            int insertIndex = 0;
            int tabCount = tabbedPane.getTabCount();

            // 使用 Collator 实现更友好的语言排序（支持中文、特殊字符等）
            Collator collator = Collator.getInstance(Locale.getDefault());
            collator.setStrength(Collator.PRIMARY); // 忽略大小写和重音

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

        // 提供一个公共方法来发布进度
        public void publishProgress(int progress) {
            publish(progress);
        }
    }
}
