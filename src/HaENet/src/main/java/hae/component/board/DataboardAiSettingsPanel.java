package hae.component.board;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.ListSelectionModel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class DataboardAiSettingsPanel extends JPanel {
    private final DataboardAiSettingsController controller;
    private final JCheckBox enabledCheckBox = new JCheckBox("启用 AI");
    private final JCheckBox warningAcknowledgedCheckBox = new JCheckBox("我已理解敏感数据提示");
    private final JTextField providerTypeField = new JTextField();
    private final JTextField baseUrlField = new JTextField();
    private final JPasswordField apiKeyField = new JPasswordField();
    private final JLabel maskedApiKeyLabel = new JLabel();
    private final JTextField modelField = new JTextField();
    private final JSpinner timeoutSpinner = numberSpinner(1, 3600, 1);
    private final JSpinner concurrencySpinner = numberSpinner(1, 64, 1);
    private final JSpinner maxInFlightCharsSpinner = numberSpinner(1, Integer.MAX_VALUE, 1000);
    private final JSpinner maxTotalCharsSpinner = numberSpinner(1, Integer.MAX_VALUE, 1000);
    private final JSpinner maxRequestCharsSpinner = numberSpinner(1, Integer.MAX_VALUE, 1000);
    private final JSpinner maxResponseCharsSpinner = numberSpinner(1, Integer.MAX_VALUE, 1000);
    private final JSpinner maxQueueSizeSpinner = numberSpinner(1, Integer.MAX_VALUE, 100);
    private final JCheckBox sendFullRequestCheckBox = new JCheckBox("发送完整请求");
    private final JCheckBox sendFullResponseCheckBox = new JCheckBox("发送完整响应");
    private final JCheckBox skipBinaryCheckBox = new JCheckBox("跳过二进制/媒体响应");
    private final JCheckBox skipStaticCheckBox = new JCheckBox("跳过静态资源");
    private final JCheckBox useBurpProxyCheckBox = new JCheckBox("AI API 使用 Burp 代理（v1 暂不支持）");
    private final JTextField whitelistGroupField = new JTextField();
    private final DefaultListModel<CheckBoxListItem> whitelistRuleListModel = new DefaultListModel<>();
    private final JList<CheckBoxListItem> whitelistRuleList = new JList<>(whitelistRuleListModel);
    private final JTextArea sensitiveWarningArea = readOnlyTextArea(DataboardAiSettingsModel.SENSITIVE_DATA_WARNING, 3, 32);
    private final JTextArea proxyWarningArea = readOnlyTextArea(DataboardAiSettingsModel.BURP_PROXY_UNSUPPORTED_WARNING, 2, 32);
    private final JTextArea guardrailNotesArea = readOnlyTextArea(DataboardAiSettingsModel.OPERATOR_GUARDRAIL_NOTES, 8, 32);
    private final JLabel queueStatusLabel = new JLabel();
    private final JLabel actionStatusLabel = new JLabel(" ");
    private List<hae.ai.AiWhitelistRule> loadedWhitelistRules = List.of();
    private boolean controllerClosed;

    public DataboardAiSettingsPanel(DataboardAiSettingsController controller) {
        this.controller = controller;
        initComponents();
        loadModel(controller.loadModel());
    }

    private void initComponents() {
        setLayout(new BorderLayout(8, 8));
        setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel formPanel = new JPanel(new GridBagLayout());
        addRow(formPanel, 0, "提供方类型：", providerTypeField);
        addRow(formPanel, 1, "Base URL：", baseUrlField);
        addRow(formPanel, 2, "API key：", apiKeyField);
        addRow(formPanel, 3, "API key 显示：", maskedApiKeyLabel);
        addRow(formPanel, 4, "模型：", modelField);
        addRow(formPanel, 5, "超时时间（秒）：", timeoutSpinner);
        addRow(formPanel, 6, "并发数：", concurrencySpinner);
        addRow(formPanel, 7, "最大并发字符数：", maxInFlightCharsSpinner);
        addRow(formPanel, 8, "最大总字符数：", maxTotalCharsSpinner);
        addRow(formPanel, 9, "最大请求字符数：", maxRequestCharsSpinner);
        addRow(formPanel, 10, "最大响应字符数：", maxResponseCharsSpinner);
        addRow(formPanel, 11, "队列大小：", maxQueueSizeSpinner);
        addRow(formPanel, 12, "AI分析分类：", whitelistGroupField);
        addRow(formPanel, 13, "允许送 AI 的规则名：", whitelistRuleScrollPane());
        addFormFiller(formPanel, 14);

        JPanel togglePanel = new JPanel();
        togglePanel.setLayout(new BoxLayout(togglePanel, BoxLayout.Y_AXIS));
        togglePanel.setBorder(new TitledBorder("AI 安全开关"));
        togglePanel.add(enabledCheckBox);
        togglePanel.add(warningAcknowledgedCheckBox);
        togglePanel.add(sendFullRequestCheckBox);
        togglePanel.add(sendFullResponseCheckBox);
        togglePanel.add(skipBinaryCheckBox);
        togglePanel.add(skipStaticCheckBox);
        togglePanel.add(useBurpProxyCheckBox);
        togglePanel.add(Box.createVerticalStrut(8));
        togglePanel.add(sectionLabel("敏感数据提示"));
        togglePanel.add(sensitiveWarningArea);
        togglePanel.add(Box.createVerticalStrut(8));
        togglePanel.add(sectionLabel("Burp 代理状态"));
        togglePanel.add(proxyWarningArea);
        togglePanel.add(Box.createVerticalStrut(8));
        togglePanel.add(sectionLabel("操作说明"));
        togglePanel.add(guardrailNotesArea);

        JPanel settingsPanel = new JPanel(new BorderLayout(8, 8));
        settingsPanel.setBorder(new TitledBorder("AI 设置"));
        JScrollPane formScrollPane = new JScrollPane(formPanel);
        formScrollPane.setBorder(new TitledBorder("提供方与限制"));
        formScrollPane.setPreferredSize(new Dimension(640, 420));
        JScrollPane toggleScrollPane = new JScrollPane(togglePanel);
        toggleScrollPane.setBorder(new TitledBorder("安全与范围"));
        toggleScrollPane.setPreferredSize(new Dimension(430, 420));
        JSplitPane settingsSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, formScrollPane, toggleScrollPane);
        settingsSplitPane.setResizeWeight(0.6);
        settingsSplitPane.setContinuousLayout(true);
        settingsSplitPane.setBorder(new EmptyBorder(0, 0, 0, 0));
        settingsPanel.add(settingsSplitPane, BorderLayout.CENTER);

        JPanel queuePanel = createQueuePanel();
        add(settingsPanel, BorderLayout.CENTER);
        add(queuePanel, BorderLayout.SOUTH);

        apiKeyField.getDocument().addDocumentListener(SimpleDocumentListener.onChange(this::refreshMaskedLabel));
        useBurpProxyCheckBox.addActionListener(e -> actionStatusLabel.setText(
                useBurpProxyCheckBox.isSelected()
                        ? DataboardAiSettingsModel.BURP_PROXY_UNSUPPORTED_WARNING
                        : " "
        ));
    }

    private JPanel createQueuePanel() {
        JPanel queuePanel = new JPanel(new BorderLayout(8, 8));
        queuePanel.setBorder(new TitledBorder("AI 队列控制"));

        JPanel buttonPanel = new JPanel();
        JButton refreshButton = new JButton("刷新状态");
        JButton pauseButton = new JButton("暂停");
        JButton resumeButton = new JButton("继续");
        JButton clearPendingButton = new JButton("清空待处理");
        JButton retryFailedButton = new JButton("重试失败");
        JButton analyzeSelectedButton = new JButton("分析选中项");
        analyzeSelectedButton.setEnabled(controller.isAnalyzeSelectedSupported());

        buttonPanel.add(refreshButton);
        buttonPanel.add(pauseButton);
        buttonPanel.add(resumeButton);
        buttonPanel.add(clearPendingButton);
        buttonPanel.add(retryFailedButton);
        buttonPanel.add(analyzeSelectedButton);

        JPanel savePanel = new JPanel();
        JButton saveButton = new JButton("保存 AI 设置");
        savePanel.add(saveButton);
        savePanel.add(Box.createHorizontalStrut(8));
        savePanel.add(queueStatusLabel);

        queuePanel.add(savePanel, BorderLayout.NORTH);
        queuePanel.add(buttonPanel, BorderLayout.CENTER);
        queuePanel.add(actionStatusLabel, BorderLayout.SOUTH);

        saveButton.addActionListener(e -> saveSettings());
        refreshButton.addActionListener(e -> controller.refreshQueueStatusAsync().thenAccept(this::showActionResult));
        pauseButton.addActionListener(e -> controller.pauseWorkerAsync().thenAccept(this::showActionResult));
        resumeButton.addActionListener(e -> controller.resumeWorkerAsync().thenAccept(this::showActionResult));
        clearPendingButton.addActionListener(e -> controller.clearPendingAsync().thenAccept(this::showActionResult));
        retryFailedButton.addActionListener(e -> controller.retryFailedAsync().thenAccept(this::showActionResult));
        analyzeSelectedButton.addActionListener(e -> controller.analyzeSelectedAsync().thenAccept(this::showActionResult));
        return queuePanel;
    }

    private void saveSettings() {
        DataboardAiSettingsModel model = collectModel();
        DataboardAiSettingsModel.SaveResult result = controller.saveSettings(model, warningAcknowledgedCheckBox.isSelected());
        actionStatusLabel.setText(result.getMessage());
        if (!result.isSaved()) {
            JOptionPane.showMessageDialog(this, result.getMessage(), "AI 设置", JOptionPane.WARNING_MESSAGE);
        }
        refreshMaskedLabel();
    }

    private void loadModel(DataboardAiSettingsModel model) {
        enabledCheckBox.setSelected(model.isEnabled());
        warningAcknowledgedCheckBox.setSelected(model.isEnabled());
        providerTypeField.setText(model.getProviderType());
        baseUrlField.setText(model.getBaseUrl());
        apiKeyField.setText(model.getApiKey());
        modelField.setText(model.getModel());
        timeoutSpinner.setValue(model.getRequestTimeoutSeconds());
        concurrencySpinner.setValue(model.getConcurrency());
        maxInFlightCharsSpinner.setValue(model.getMaxInFlightChars());
        maxTotalCharsSpinner.setValue(model.getMaxTotalChars());
        maxRequestCharsSpinner.setValue(model.getMaxRequestChars());
        maxResponseCharsSpinner.setValue(model.getMaxResponseChars());
        maxQueueSizeSpinner.setValue(model.getMaxQueueSize());
        sendFullRequestCheckBox.setSelected(model.isSendFullRequest());
        sendFullResponseCheckBox.setSelected(model.isSendFullResponse());
        skipBinaryCheckBox.setSelected(model.isSkipBinary());
        skipStaticCheckBox.setSelected(model.isSkipStaticResources());
        useBurpProxyCheckBox.setSelected(model.isUseBurpProxy());
        loadedWhitelistRules = model.getWhitelistRules();
        whitelistGroupField.setText(model.getWhitelistGroup());
        loadWhitelistRuleChoices(model.getWhitelistNames());
        queueStatusLabel.setText(model.getQueueStatusText());
        maskedApiKeyLabel.setText(model.getMaskedApiKey());
    }

    private DataboardAiSettingsModel collectModel() {
        DataboardAiSettingsModel model = new DataboardAiSettingsModel();
        model.setEnabled(enabledCheckBox.isSelected());
        model.setProviderType(providerTypeField.getText());
        model.setBaseUrl(baseUrlField.getText());
        model.setApiKey(new String(apiKeyField.getPassword()));
        model.setModel(modelField.getText());
        model.setRequestTimeoutSeconds((Integer) timeoutSpinner.getValue());
        model.setConcurrency((Integer) concurrencySpinner.getValue());
        model.setMaxInFlightChars((Integer) maxInFlightCharsSpinner.getValue());
        model.setMaxTotalChars((Integer) maxTotalCharsSpinner.getValue());
        model.setMaxRequestChars((Integer) maxRequestCharsSpinner.getValue());
        model.setMaxResponseChars((Integer) maxResponseCharsSpinner.getValue());
        model.setMaxQueueSize((Integer) maxQueueSizeSpinner.getValue());
        model.setSendFullRequest(sendFullRequestCheckBox.isSelected());
        model.setSendFullResponse(sendFullResponseCheckBox.isSelected());
        model.setSkipBinary(skipBinaryCheckBox.isSelected());
        model.setSkipStaticResources(skipStaticCheckBox.isSelected());
        model.setUseBurpProxy(useBurpProxyCheckBox.isSelected());
        model.setWhitelistRules(loadedWhitelistRules);
        model.setWhitelistGroup(whitelistGroupField.getText());
        model.setWhitelistNames(selectedWhitelistRuleNames());
        return model;
    }

    private JScrollPane whitelistRuleScrollPane() {
        whitelistRuleList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        whitelistRuleList.setVisibleRowCount(8);
        whitelistRuleList.setCellRenderer((list, value, index, isSelected, cellHasFocus) -> {
            JCheckBox checkBox = new JCheckBox(value.name(), value.selected());
            checkBox.setOpaque(true);
            checkBox.setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            checkBox.setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
            checkBox.setFont(list.getFont());
            return checkBox;
        });
        whitelistRuleList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int index = whitelistRuleList.locationToIndex(event.getPoint());
                if (index < 0) {
                    return;
                }
                CheckBoxListItem item = whitelistRuleListModel.get(index);
                item.setSelected(!item.selected());
                whitelistRuleList.repaint(whitelistRuleList.getCellBounds(index, index));
            }
        });
        whitelistRuleList.setPrototypeCellValue(new CheckBoxListItem("XXXXXXXXXXXXXXXXXXXXXXXXXXXX", false));
        return new JScrollPane(whitelistRuleList);
    }

    private void loadWhitelistRuleChoices(List<String> selectedNames) {
        List<String> selected = selectedNames == null ? List.of() : selectedNames;
        List<String> available = controller.loadAvailableRuleNames();
        List<String> choices = mergeRuleChoices(available, selected);
        whitelistRuleListModel.clear();

        Set<String> selectedKeys = normalizedSet(selected);
        for (String choice : choices) {
            whitelistRuleListModel.addElement(new CheckBoxListItem(
                    choice,
                    selectedKeys.contains(normalizeRuleName(choice))
            ));
        }
    }

    private List<String> selectedWhitelistRuleNames() {
        List<String> selected = new ArrayList<>();
        for (int i = 0; i < whitelistRuleListModel.size(); i++) {
            CheckBoxListItem item = whitelistRuleListModel.get(i);
            if (item.selected()) {
                selected.add(item.name());
            }
        }
        return selected.stream()
                .toList();
    }

    private static List<String> mergeRuleChoices(List<String> available, List<String> selected) {
        List<String> choices = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String value : available) {
            addRuleChoice(choices, seen, value);
        }
        for (String value : selected) {
            addRuleChoice(choices, seen, value);
        }
        return choices;
    }

    private static void addRuleChoice(List<String> choices, Set<String> seen, String value) {
        String normalized = normalizeRuleName(value);
        if (normalized.isBlank() || !seen.add(normalized)) {
            return;
        }
        choices.add(value.trim());
    }

    private static Set<String> normalizedSet(List<String> values) {
        Set<String> result = new LinkedHashSet<>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            String normalized = normalizeRuleName(value);
            if (!normalized.isBlank()) {
                result.add(normalized);
            }
        }
        return result;
    }

    private static String normalizeRuleName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static final class CheckBoxListItem {
        private final String name;
        private boolean selected;

        private CheckBoxListItem(String name, boolean selected) {
            this.name = name == null ? "" : name.trim();
            this.selected = selected;
        }

        private String name() {
            return name;
        }

        private boolean selected() {
            return selected;
        }

        private void setSelected(boolean selected) {
            this.selected = selected;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private void refreshMaskedLabel() {
        DataboardAiSettingsModel model = new DataboardAiSettingsModel();
        model.setApiKey(new String(apiKeyField.getPassword()));
        maskedApiKeyLabel.setText(model.getMaskedApiKey());
    }

    private void showActionResult(DataboardAiSettingsController.ActionResult result) {
        SwingUtilities.invokeLater(() -> {
            actionStatusLabel.setText(result.getMessage());
            refreshQueueStatusLabel();
        });
    }

    private void refreshQueueStatusLabel() {
        queueStatusLabel.setText(controller.loadModel().getQueueStatusText());
    }

    @Override
    public void removeNotify() {
        closeController();
        super.removeNotify();
    }

    private void closeController() {
        if (!controllerClosed) {
            controllerClosed = true;
            controller.shutdown();
        }
    }

    private static JSpinner numberSpinner(int minimum, int maximum, int step) {
        return new JSpinner(new SpinnerNumberModel(minimum, minimum, maximum, step));
    }

    private static JLabel sectionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setBorder(new EmptyBorder(4, 0, 2, 0));
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    private static JTextArea readOnlyTextArea(String text, int rows, int columns) {
        JTextArea textArea = new JTextArea(text, rows, columns);
        textArea.setEditable(false);
        textArea.setFocusable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setOpaque(false);
        textArea.setBorder(new EmptyBorder(6, 0, 0, 0));
        textArea.setAlignmentX(LEFT_ALIGNMENT);
        return textArea;
    }

    private void addRow(JPanel panel, int row, String label, java.awt.Component component) {
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.gridy = row;
        labelConstraints.weightx = 0.0;
        labelConstraints.fill = GridBagConstraints.HORIZONTAL;
        labelConstraints.insets = new Insets(3, 3, 3, 5);
        panel.add(new JLabel(label), labelConstraints);

        GridBagConstraints fieldConstraints = new GridBagConstraints();
        fieldConstraints.gridx = 1;
        fieldConstraints.gridy = row;
        fieldConstraints.weightx = 1.0;
        fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        fieldConstraints.insets = new Insets(3, 3, 3, 3);
        panel.add(component, fieldConstraints);
    }

    private void addFormFiller(JPanel panel, int row) {
        GridBagConstraints fillerConstraints = new GridBagConstraints();
        fillerConstraints.gridx = 0;
        fillerConstraints.gridy = row;
        fillerConstraints.gridwidth = 2;
        fillerConstraints.weighty = 1.0;
        fillerConstraints.fill = GridBagConstraints.BOTH;
        panel.add(Box.createVerticalGlue(), fillerConstraints);
    }

    private interface SimpleDocumentListener extends javax.swing.event.DocumentListener {
        static SimpleDocumentListener onChange(Runnable runnable) {
            return new SimpleDocumentListener() {
                @Override
                public void insertUpdate(javax.swing.event.DocumentEvent e) {
                    runnable.run();
                }

                @Override
                public void removeUpdate(javax.swing.event.DocumentEvent e) {
                    runnable.run();
                }

                @Override
                public void changedUpdate(javax.swing.event.DocumentEvent e) {
                    runnable.run();
                }
            };
        }
    }
}
