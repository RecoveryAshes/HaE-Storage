package hae.component.board;

import burp.api.montoya.MontoyaApi;
import hae.ai.AiConfig;
import hae.cache.DataCache;
import hae.component.board.message.AiSummaryDisplay;
import hae.component.board.message.MessageTableModel;
import hae.component.board.message.MessageTableModel.MessageTable;
import hae.component.board.table.Datatable;
import hae.repository.AiTaskRepository;
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
    private final DataboardAiSettingsController.WorkerControls aiWorkerControls;
    private final DefaultComboBoxModel<String> comboBoxModel = new DefaultComboBoxModel<>();
    private final JComboBox<String> hostComboBox = new JComboBox<>(comboBoxModel);
    private JTextField hostTextField;
    private JTabbedPane dataTabbedPane;
    private JSplitPane splitPane;
    private MessageTable messageTable;
    private JProgressBar progressBar;
    private JLabel aiStatusLabel;
    private JButton aiSettingsToolbarButton;
    private SwingWorker<DataLoadingResult, Integer> handleComboBoxWorker;
    private SwingWorker<Void, Void> applyHostFilterWorker;
    private SwingWorker<List<String>, Void> hostListWorker;
    private SwingWorker<Map<String, AiSummaryDisplay>, Void> aiSummaryRefreshWorker;
    private List<String> cachedHostList = new ArrayList<>();
    private long cachedHostListAt = 0L;
    private String activeHostFilter = "*";
    private static final long HOST_LIST_CACHE_TTL_MILLIS = 3000L;

    public Databoard(MontoyaApi api, ConfigLoader configLoader, MessageTableModel messageTableModel) {
        this(api, configLoader, messageTableModel, null);
    }

    public Databoard(MontoyaApi api,
                     ConfigLoader configLoader,
                     MessageTableModel messageTableModel,
                     DataboardAiSettingsController.WorkerControls aiWorkerControls) {
        this.api = api;
        this.configLoader = configLoader;
        this.messageTableModel = messageTableModel;
        this.aiWorkerControls = aiWorkerControls;

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
        JButton aiSettingsButton = new JButton("AI 设置");
        aiSettingsToolbarButton = new JButton("AI 设置");
        JButton actionButton = new JButton("Action");
        aiStatusLabel = new JLabel();
        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        actionPanel.add(actionButton);
        actionPanel.add(aiStatusLabel);
        actionPanel.add(aiSettingsToolbarButton);
        JPanel menuPanel = new JPanel(new GridLayout(4, 1, 0, 5));
        menuPanel.setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
        JPopupMenu menu = new JPopupMenu();
        menuPanel.add(clearDataButton);
        menuPanel.add(clearCacheButton);
        menuPanel.add(clearStorageButton);
        menuPanel.add(aiSettingsButton);
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

            String ruleName = StringProcessor.extractItemName(selectedTitle);
            refreshSelectedRuleAiSummaries(ruleName);
            messageTableModel.applyCommentFilter(ruleName);
        });

        actionButton.addActionListener(e -> {
            int x = 0;
            int y = actionButton.getHeight();
            menu.show(actionButton, x, y);
        });

        clearDataButton.addActionListener(this::clearDataActionPerformed);
        clearCacheButton.addActionListener(this::clearCacheActionPerformed);
        clearStorageButton.addActionListener(this::clearStorageActionPerformed);
        aiSettingsButton.addActionListener(this::aiSettingsActionPerformed);
        aiSettingsToolbarButton.addActionListener(this::aiSettingsActionPerformed);

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
        add(actionPanel, new GridBagConstraints(3, 0, 2, 1, 0.0, 0.0, GridBagConstraints.CENTER, GridBagConstraints.BOTH,
                new Insets(8, 0, 5, 5), 0, 0));

        add(splitPane, new GridBagConstraints(1, 1, 4, 1, 1.0, 1.0,
                GridBagConstraints.CENTER, GridBagConstraints.BOTH,
                new Insets(0, 5, 0, 5), 0, 0));
        add(progressBar, new GridBagConstraints(1, 2, 4, 1, 1.0, 0.0,
                GridBagConstraints.CENTER, GridBagConstraints.HORIZONTAL,
                new Insets(0, 5, 0, 5), 0, 0));
        hostComboBox.setMaximumRowCount(5);
        add(hostComboBox, new GridBagConstraints(2, 0, 1, 1, 0.0, 0.0, GridBagConstraints.CENTER, GridBagConstraints.BOTH,
                new Insets(8, 0, 5, 5), 0, 0));

        refreshAiStatusLabel();
        setAutoMatch();
    }

    private void resizePanel() {
        splitPane.setDividerLocation(0.4);
        TableColumnModel columnModel = messageTable.getColumnModel();
        if (columnModel.getColumnCount() < 6) {
            return;
        }
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
                startDataLoadingWorker(selectedHost);
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
            loadHostFromInput();
        }

        if (keyCode == KeyEvent.VK_ESCAPE) {
            hostComboBox.setPopupVisible(false);
        }

        isMatchHost = false;
    }

    void loadHostFromInputForTest() {
        loadHostFromInput();
    }

    boolean isSplitPaneVisibleForTest() {
        return splitPane.isVisible();
    }

    int getDataTabCountForTest() {
        return dataTabbedPane.getTabCount();
    }

    int getMessageTableRowCountForTest() {
        return messageTableModel.getRowCount();
    }

    Datatable getDataTabComponentForTest(int index) {
        Component component = dataTabbedPane.getComponentAt(index);
        return component instanceof Datatable datatable ? datatable : null;
    }

    void selectDataTabForTest(int index) {
        dataTabbedPane.setSelectedIndex(index);
        String title = dataTabbedPane.getTitleAt(index);
        refreshSelectedRuleAiSummaries(StringProcessor.extractItemName(title));
    }

    JTextField getHostTextFieldForTest() {
        return hostTextField;
    }

    String getAiStatusTextForTest() {
        return aiStatusLabel == null ? "" : aiStatusLabel.getText();
    }

    boolean isAiSettingsToolbarVisibleForTest() {
        return aiSettingsToolbarButton != null && aiSettingsToolbarButton.isVisible();
    }

    GridBagConstraints getMainSplitPaneConstraintsForTest() {
        return ((GridBagLayout) getLayout()).getConstraints(splitPane);
    }

    private void loadHostFromInput() {
        String selectedHost = hostTextField.getText() == null ? "" : hostTextField.getText().trim();
        if (selectedHost.isEmpty()) {
            return;
        }
        hostComboBox.setPopupVisible(false);
        startDataLoadingWorker(selectedHost);
    }

    private void startDataLoadingWorker(String selectedHost) {
        if (handleComboBoxWorker != null && !handleComboBoxWorker.isDone()) {
            progressBar.setVisible(false);
            handleComboBoxWorker.cancel(true);
        }

        activeHostFilter = (selectedHost == null || selectedHost.trim().isEmpty()) ? "*" : selectedHost.trim();

        handleComboBoxWorker = new DataLoadingWorker(selectedHost);
        handleComboBoxWorker.execute();
    }

    private void refreshSelectedRuleAiSummaries(String ruleName) {
        if (ruleName == null || ruleName.isBlank() || dataTabbedPane == null) {
            return;
        }
        int selectedIndex = dataTabbedPane.getSelectedIndex();
        if (selectedIndex < 0) {
            return;
        }
        Component selectedComponent = dataTabbedPane.getComponentAt(selectedIndex);
        if (!(selectedComponent instanceof Datatable datatable)) {
            return;
        }
        List<String> values = datatable.getInformationValues();
        if (values.isEmpty()) {
            return;
        }
        if (aiSummaryRefreshWorker != null && !aiSummaryRefreshWorker.isDone()) {
            aiSummaryRefreshWorker.cancel(true);
        }
        String hostFilter = activeHostFilter;
        Map<String, List<String>> requestedRuleValues = Map.of(ruleName, values);
        aiSummaryRefreshWorker = new SwingWorker<>() {
            @Override
            protected Map<String, AiSummaryDisplay> doInBackground() {
                Map<String, Map<String, AiSummaryDisplay>> summariesByRule = messageTableModel.loadAiSummariesByRuleValue(hostFilter, requestedRuleValues);
                return summariesByRule.getOrDefault(ruleName, Collections.emptyMap());
            }

            @Override
            protected void done() {
                if (isCancelled()) {
                    return;
                }
                try {
                    Map<String, AiSummaryDisplay> summariesByValue = get();
                    datatable.refreshAiSummaries((targetRuleName, value) -> summariesByValue.getOrDefault(value, AiSummaryDisplay.empty()));
                } catch (Exception e) {
                    api.logging().logToOutput("refreshSelectedRuleAiSummaries: " + e.getMessage());
                }
            }
        };
        aiSummaryRefreshWorker.execute();
    }

    private DataLoadingResult getSelectedMapByHost(String selectedHost, DataLoadingWorker worker) {
        Map<String, List<String>> selectedDataMap = messageTableModel.loadExtractedDataByHost(selectedHost);
        if (worker != null) {
            worker.publishProgress(70);
        }
        Map<String, List<String>> extractedDataByRule = selectedDataMap != null ? selectedDataMap : new HashMap<>();
        Map<String, Map<String, AiSummaryDisplay>> aiSummariesByRuleValue = messageTableModel.loadAiSummariesByRuleValue(selectedHost, extractedDataByRule);
        if (worker != null) {
            worker.publishProgress(90);
        }

        return new DataLoadingResult(extractedDataByRule, aiSummariesByRuleValue);
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

    private void aiSettingsActionPerformed(ActionEvent e) {
        AiTaskRepository aiTaskRepository = messageTableModel.getAiTaskRepositoryForSettings();
        DataboardAiSettingsController controller = new DataboardAiSettingsController(
                configLoader,
                aiTaskRepository,
                aiWorkerControls,
                messageTableModel,
                null
        );
        DataboardAiSettingsPanel settingsPanel = new DataboardAiSettingsPanel(controller);
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this), "AI 设置与队列控制", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setContentPane(settingsPanel);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                controller.shutdown();
            }
        });
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
        refreshAiStatusLabel();
    }

    private void refreshAiStatusLabel() {
        if (aiStatusLabel == null) {
            return;
        }

        String statusText = "AI：" + aiEnabledStatus();
        String workerSummary = aiWorkerStatusSummary();
        aiStatusLabel.setText(statusText);
        aiStatusLabel.setToolTipText(workerSummary.isBlank()
                ? "AI 分析设置与队列控制"
                : workerSummary);
    }

    private String aiEnabledStatus() {
        try {
            AiConfig aiConfig = configLoader.getAiConfig();
            return aiConfig.isEnabled() ? "已启用" : "未启用";
        } catch (RuntimeException e) {
            return "配置异常";
        }
    }

    private String aiWorkerStatusSummary() {
        if (aiWorkerControls == null) {
            return "AI worker 控制不可用";
        }
        try {
            return aiWorkerControls.statusSummary();
        } catch (RuntimeException e) {
            return "AI worker 状态不可用";
        }
    }

    // 定义为内部类
    private record DataLoadingResult(Map<String, List<String>> extractedDataByRule,
                                     Map<String, Map<String, AiSummaryDisplay>> aiSummariesByRuleValue) {
    }

    private class DataLoadingWorker extends SwingWorker<DataLoadingResult, Integer> {
        private final String selectedHost;

        public DataLoadingWorker(String selectedHost) {
            this.selectedHost = selectedHost;
            progressBar.setVisible(true);
        }

        @Override
        protected DataLoadingResult doInBackground() throws Exception {
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
                    DataLoadingResult result = get();
                    Map<String, List<String>> selectedDataMap = result == null ? Collections.emptyMap() : result.extractedDataByRule();
                    Map<String, Map<String, AiSummaryDisplay>> aiSummariesByRuleValue = result == null ? Collections.emptyMap() : result.aiSummariesByRuleValue();
                    if (selectedDataMap != null && !selectedDataMap.isEmpty()) {
                        dataTabbedPane.removeAll();

                        for (Map.Entry<String, List<String>> entry : selectedDataMap.entrySet()) {
                            String ruleName = entry.getKey();
                            String tabTitle = String.format("%s (%s)", entry.getKey(), entry.getValue().size());
                            Map<String, AiSummaryDisplay> summariesByValue = aiSummariesByRuleValue.getOrDefault(ruleName, Collections.emptyMap());
                            Datatable datatablePanel = new Datatable(api, configLoader, ruleName, entry.getValue(),
                                    (targetRuleName, value) -> summariesByValue.getOrDefault(value, AiSummaryDisplay.empty()));
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
