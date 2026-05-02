package hae.component.board;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.logging.Logging;
import burp.api.montoya.ui.UserInterface;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import hae.ai.AiTriageRiskLevel;
import hae.ai.AiTriageSchema;
import hae.ai.AiTriageTargetSignature;
import hae.ai.AiTriageVerdict;
import hae.ai.AiWhitelistRule;
import hae.component.board.message.AiSummaryDisplay;
import hae.component.board.message.MessageTableModel;
import hae.component.board.table.Datatable;
import hae.storage.SqliteMessageStore;
import hae.utils.ConfigLoader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.border.TitledBorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataboardAiModelTest {
    private static final String FIRST_JSON = "{\"overall_verdict\":\"possible_sensitive\",\"id\":\"message-1\"}";
    private static final String SECOND_JSON = "{\"overall_verdict\":\"not_sensitive\",\"id\":\"message-2\"}";
    private static final String TEST_RULE = "AiTestRule";

    @TempDir
    Path tempDirectory;

    @Test
    void summaryFieldsLoadInBatch() throws Exception {
        BoardContext context = createBoardContext(tempDirectory.resolve("summary-batch"));
        saveMessagesAndAiResults(context.store());
        MessageTableModel model = newTestModel(context);
        try {
            AtomicBoolean updateOnEdt = loadPersistedMessagesAndCaptureEdt(model, 1, TEST_RULE, "/one");
            waitUntil(() -> model.getAiDetailTextForTest().contains("AI 已给出英文摘要"),
                    "auto AI detail load should finish before measuring table rendering queries");
            int jsonQueriesBeforeRendering = context.store().jsonQueryCount();

            renderAllCells(model);

            Map<String, Map<String, AiSummaryDisplay>> leftTableSummaries = model.loadAiSummariesByRuleValue("*", Map.of(TEST_RULE, List.of("/one")));
            AiSummaryDisplay leftSummary = leftTableSummaries.get(TEST_RULE).get("/one");

            assertAll(
                    () -> assertTrue(updateOnEdt.get(), "table model update should fire on the EDT"),
                    () -> assertTrue(context.store().summaryQueryCount() >= 1, "AI summaries should be loaded for the left Datatable"),
                    () -> assertEquals(List.of("message-1"), context.store().lastPageSummaryMessageIds()),
                    () -> assertEquals(jsonQueriesBeforeRendering, context.store().jsonQueryCount(), "table cell rendering must not load full AI JSON"),
                    () -> assertEquals(6, model.getColumnCount()),
                    () -> assertEquals("Method", model.getColumnName(0)),
                    () -> assertEquals("URL", model.getColumnName(1)),
                    () -> assertEquals("Comment", model.getColumnName(2)),
                    () -> assertEquals("Status", model.getColumnName(3)),
                    () -> assertEquals("Length", model.getColumnName(4)),
                    () -> assertEquals("Color", model.getColumnName(5)),
                    () -> assertEquals("GET", model.getValueAt(0, 0)),
                    () -> assertEquals("https://ai.example.test/one", model.getValueAt(0, 1)),
                    () -> assertEquals("Token (1)", model.getValueAt(0, 2)),
                    () -> assertEquals("200", model.getValueAt(0, 3)),
                    () -> assertEquals("42", model.getValueAt(0, 4)),
                    () -> assertEquals("yellow", model.getValueAt(0, 5)),
                    () -> assertEquals("", model.getColumnName(6)),
                    () -> assertEquals("", model.getValueAt(0, 6)),
                    () -> assertEquals("DONE", leftSummary.getAiStatus()),
                    () -> assertEquals("疑似敏感信息", leftSummary.getAiVerdict()),
                    () -> assertEquals("0.81", leftSummary.getAiConfidence())
            );
        } finally {
            model.clearAllDataOnShutdown();
        }
    }

    @Test
    void selectionLoadsFullJson() throws Exception {
        BoardContext context = createBoardContext(tempDirectory.resolve("selection-json"));
        saveMessagesAndAiResults(context.store());
        MessageTableModel model = newTestModel(context);
        try {
            loadPersistedMessagesAndCaptureEdt(model, 1, TEST_RULE, "/one");
            renderAllCells(model);
            assertEquals(0, context.store().jsonQueryCount(), "rendering before selection should not load full AI JSON");

            AtomicReference<String> selectedJson = new AtomicReference<>();
            onEdt(() -> {
                model.getMessageTable().changeSelection(0, 0, false, false);
                selectedJson.set(model.loadSelectedAiResultJson());
            });
            waitUntil(() -> model.getAiDetailTextForTest().contains("AI 已给出英文摘要"), "AI detail panel should load selected result");
            String aiDetailText = model.getAiDetailTextForTest();

            assertAll(
                    () -> assertEquals(FIRST_JSON, selectedJson.get()),
                    () -> assertTrue(aiDetailText.contains("AI状态：DONE")),
                    () -> assertTrue(aiDetailText.contains("AI结论：疑似敏感信息")),
                    () -> assertTrue(aiDetailText.contains("AI风险：高")),
                    () -> assertTrue(aiDetailText.contains("摘要：AI 已给出英文摘要，请查看原始 AI 结果。")),
                    () -> assertTrue(aiDetailText.contains("原始AI结果")),
                    () -> assertTrue(aiDetailText.contains("\"id\": \"message-1\"")),
                    () -> assertEquals("message-1", context.store().lastJsonMessageId())
            );
        } finally {
            model.clearAllDataOnShutdown();
        }
    }

    @Test
    void firstPageLoadShowsAiDetailForFirstRow() throws Exception {
        BoardContext context = createBoardContext(tempDirectory.resolve("auto-detail"));
        saveMessagesAndAiResults(context.store());
        MessageTableModel model = newTestModel(context);
        try {
            loadPersistedMessagesAndCaptureEdt(model, 1, TEST_RULE, "/one");

            waitUntil(() -> model.getAiDetailTextForTest().contains("AI 已给出英文摘要"), "AI detail panel should auto-load first row result");

            assertAll(
                    () -> assertEquals(0, model.getMessageTable().getSelectedRow()),
                    () -> assertTrue(model.getAiDetailTextForTest().contains("AI状态：DONE")),
                    () -> assertTrue(model.getAiDetailTextForTest().contains("\"id\": \"message-1\""))
            );
        } finally {
            model.clearAllDataOnShutdown();
        }
    }

    @Test
    void legacyEmptyAiResultShowsRetryPromptInsteadOfNormalDone() throws Exception {
        BoardContext context = createBoardContext(tempDirectory.resolve("legacy-empty-detail"));
        saveMessage(context.store(), "message-1", "/one", "Token (1)", "yellow", "hash-1");
        insertLegacyEmptyAiResult(context.store().getDatabasePath(), "message-1", "hash-1");
        MessageTableModel model = newTestModel(context);
        try {
            loadPersistedMessagesAndCaptureEdt(model, 1, TEST_RULE, "/one");
            waitUntil(() -> model.getAiDetailTextForTest().contains("建议重试"),
                    "empty legacy AI result should show retry guidance");

            String aiDetailText = model.getAiDetailTextForTest();
            AiSummaryDisplay leftSummary = model.loadAiSummariesByRuleValue("*", Map.of(TEST_RULE, List.of("/one")))
                    .get(TEST_RULE)
                    .get("/one");
            assertAll(
                    () -> assertEquals("", model.getValueAt(0, 6)),
                    () -> assertEquals("无有效结论", leftSummary.getAiStatus()),
                    () -> assertEquals("建议重试", leftSummary.getAiVerdict()),
                    () -> assertEquals("0.00", leftSummary.getAiConfidence()),
                    () -> assertTrue(aiDetailText.contains("AI状态：无有效结论")),
                    () -> assertTrue(aiDetailText.contains("AI结论：建议重试")),
                    () -> assertTrue(aiDetailText.contains("AI 返回了空结论"))
            );
        } finally {
            model.clearAllDataOnShutdown();
        }
    }

    @Test
    void legacyLowQualityAiResultShowsRetryPromptInsteadOfNormalDone() throws Exception {
        BoardContext context = createBoardContext(tempDirectory.resolve("legacy-low-quality-detail"));
        saveMessage(context.store(), "message-1", "/one", "Password (1)", "orange", "hash-1");
        insertLegacyLowQualityAiResult(context.store().getDatabasePath(), "message-1", "hash-1");
        MessageTableModel model = newTestModel(context);
        try {
            loadPersistedMessagesAndCaptureEdt(model, 1, TEST_RULE, "/one");
            waitUntil(() -> model.getAiDetailTextForTest().contains("低质量结论"),
                    "low-quality legacy AI result should show retry guidance");

            String aiDetailText = model.getAiDetailTextForTest();
            AiSummaryDisplay leftSummary = model.loadAiSummariesByRuleValue("*", Map.of(TEST_RULE, List.of("/one")))
                    .get(TEST_RULE)
                    .get("/one");
            assertAll(
                    () -> assertEquals("", model.getValueAt(0, 6)),
                    () -> assertEquals("低质量结论", leftSummary.getAiStatus()),
                    () -> assertEquals("建议重试", leftSummary.getAiVerdict()),
                    () -> assertEquals("0.00", leftSummary.getAiConfidence()),
                    () -> assertTrue(aiDetailText.contains("AI状态：低质量结论")),
                    () -> assertTrue(aiDetailText.contains("AI结论：建议重试")),
                    () -> assertTrue(aiDetailText.contains("AI 返回了低质量结论")),
                    () -> assertTrue(aiDetailText.contains("\n  \"items\": ["), "raw JSON should be formatted for readability")
            );
        } finally {
            model.clearAllDataOnShutdown();
        }
    }

    @Test
    void falsePositiveUnknownRiskDisplaysChineseInfoRiskAndSummary() throws Exception {
        BoardContext context = createBoardContext(tempDirectory.resolve("false-positive-chinese-detail"));
        saveMessage(context.store(), "message-1", "/one", "用户名 (1)", "orange", "hash-1");
        insertLegacyFalsePositiveUnknownRiskResult(context.store().getDatabasePath(), "message-1", "hash-1");
        MessageTableModel model = newTestModel(context);
        try {
            loadPersistedMessagesAndCaptureEdt(model, 1, TEST_RULE, "/one");
            waitUntil(() -> model.getAiDetailTextForTest().contains("AI结论：误报"),
                    "false positive detail should use Chinese verdict");

            String aiDetailText = model.getAiDetailTextForTest();
            AiSummaryDisplay leftSummary = model.loadAiSummariesByRuleValue("*", Map.of(TEST_RULE, List.of("/one")))
                    .get(TEST_RULE)
                    .get("/one");
            assertAll(
                    () -> assertEquals("", model.getValueAt(0, 6)),
                    () -> assertEquals("DONE", leftSummary.getAiStatus()),
                    () -> assertEquals("误报", leftSummary.getAiVerdict()),
                    () -> assertEquals("0.96", leftSummary.getAiConfidence()),
                    () -> assertTrue(aiDetailText.contains("AI结论：误报")),
                    () -> assertTrue(aiDetailText.contains("AI风险：信息")),
                    () -> assertTrue(aiDetailText.contains("摘要：误报：命中文本不像真实敏感信息。")),
                    () -> assertFalse(aiDetailText.contains("AI风险：unknown")),
                    () -> assertFalse(aiDetailText.contains("AI结论：false_positive"))
            );
        } finally {
            model.clearAllDataOnShutdown();
        }
    }

    @Test
    void aiDetailUsesSelectedRuleValueTargetInsteadOfOtherMessageTargets() throws Exception {
        BoardContext context = createBoardContext(tempDirectory.resolve("target-specific-detail"));
        CountingSqliteMessageStore store = context.store();
        store.saveMessage(
                "message-target",
                httpRequestResponse("ai.example.test", "/target", 200),
                "https://ai.example.test/target",
                "GET",
                "200",
                "42",
                "GET 明文id (1), 用户名 (1)",
                "yellow",
                "hash-target",
                Map.of(
                        "GET 明文id", List.of("conwid=840"),
                        "用户名", List.of("<!--sid=673ba57c253d625b-->")
                )
        );
        insertAiResult(
                store.getDatabasePath(),
                "message-target",
                "hash-target",
                "GET 明文id",
                "conwid=840",
                "not_sensitive",
                "info",
                0.91,
                "这是 GET 明文id/conwid=840 的 AI 结果。",
                "{\"target\":\"plain-id\"}",
                40_000L
        );
        insertAiResult(
                store.getDatabasePath(),
                "message-target",
                "hash-target",
                "用户名",
                "<!--sid=673ba57c253d625b-->",
                "false_positive",
                "info",
                0.99,
                "这是用户名目标的 AI 结果。",
                "{\"target\":\"username\"}",
                41_000L
        );
        MessageTableModel model = newTestModel(context);
        try {
            loadPersistedMessagesAndCaptureEdt(model, 1, "GET 明文id", "conwid=840");
            waitUntil(() -> model.getAiDetailTextForTest().contains("GET 明文id/conwid=840"),
                    "AI detail should load selected GET 明文id target");
            String plainIdDetail = model.getAiDetailTextForTest();
            AiSummaryDisplay plainIdSummary = model.loadAiSummariesByRuleValue("*", Map.of("GET 明文id", List.of("conwid=840")))
                    .get("GET 明文id")
                    .get("conwid=840");

            assertAll(
                    () -> assertEquals("", model.getValueAt(0, 6)),
                    () -> assertEquals("DONE", plainIdSummary.getAiStatus()),
                    () -> assertEquals("未发现敏感信息", plainIdSummary.getAiVerdict()),
                    () -> assertTrue(plainIdDetail.contains("AI目标：GET 明文id / conwid=840")),
                    () -> assertTrue(plainIdDetail.contains("\"target\": \"plain-id\"")),
                    () -> assertFalse(plainIdDetail.contains("用户名目标")),
                    () -> assertFalse(plainIdDetail.contains("\"target\": \"username\""))
            );

            model.applyMessageFilter("用户名", "<!--sid=673ba57c253d625b-->");
            waitUntil(() -> model.getAiDetailTextForTest().contains("用户名目标"),
                    "AI detail should switch to selected 用户名 target");
            String usernameDetail = model.getAiDetailTextForTest();
            AiSummaryDisplay usernameSummary = model.loadAiSummariesByRuleValue("*", Map.of("用户名", List.of("<!--sid=673ba57c253d625b-->")))
                    .get("用户名")
                    .get("<!--sid=673ba57c253d625b-->");

            assertAll(
                    () -> assertEquals("误报", usernameSummary.getAiVerdict()),
                    () -> assertTrue(usernameDetail.contains("AI目标：用户名 / <!--sid=673ba57c253d625b-->")),
                    () -> assertTrue(usernameDetail.contains("\"target\": \"username\"")),
                    () -> assertFalse(usernameDetail.contains("GET 明文id/conwid=840")),
                    () -> assertFalse(usernameDetail.contains("\"target\": \"plain-id\""))
            );
        } finally {
            model.clearAllDataOnShutdown();
        }
    }

    @Test
    void databoardLeftDatatableShowsTargetSpecificAiColumns() throws Exception {
        BoardContext context = createBoardContext(tempDirectory.resolve("left-datatable-ai-columns"));
        CountingSqliteMessageStore store = context.store();
        store.saveMessage(
                "message-target",
                httpRequestResponse("ai.example.test", "/target", 200),
                "https://ai.example.test/target",
                "GET",
                "200",
                "42",
                "GET 明文id (1), 用户名 (1)",
                "yellow",
                "hash-target",
                Map.of(
                        "GET 明文id", List.of("conwid=840"),
                        "用户名", List.of("<!--sid=673ba57c253d625b-->")
                )
        );
        insertAiResult(
                store.getDatabasePath(),
                "message-target",
                "hash-target",
                "GET 明文id",
                "conwid=840",
                "not_sensitive",
                "info",
                0.91,
                "这是 GET 明文id/conwid=840 的 AI 结果。",
                "{\"target\":\"plain-id\"}",
                40_000L
        );
        insertAiResult(
                store.getDatabasePath(),
                "message-target",
                "hash-target",
                "用户名",
                "<!--sid=673ba57c253d625b-->",
                "false_positive",
                "info",
                0.99,
                "这是用户名目标的 AI 结果。",
                "{\"target\":\"username\"}",
                41_000L
        );
        MessageTableModel model = newTestModel(context);
        try {
            AtomicReference<Databoard> databoardReference = new AtomicReference<>();
            onEdt(() -> databoardReference.set(new Databoard(context.api(), context.configLoader(), model)));
            Databoard databoard = databoardReference.get();

            onEdt(() -> {
                databoard.getHostTextFieldForTest().setText("*");
                databoard.loadHostFromInputForTest();
            });

            waitUntil(() -> databoard.getDataTabCountForTest() == 2, "left Datatable tabs should load both target rules");
            AtomicReference<Datatable> plainIdDatatable = new AtomicReference<>();
            onEdt(() -> {
                for (int index = 0; index < databoard.getDataTabCountForTest(); index++) {
                    Datatable datatable = databoard.getDataTabComponentForTest(index);
                    if (datatable != null && datatable.getDataTable().getRowCount() == 1
                            && "conwid=840".equals(datatable.getDataTable().getValueAt(0, 1))) {
                        plainIdDatatable.set(datatable);
                    }
                }
            });
            Datatable datatable = plainIdDatatable.get();
            JTable table = datatable == null ? null : datatable.getDataTable();
            int jsonQueriesBeforeRendering = store.jsonQueryCount();
            if (table != null) {
                renderAllCells(table);
            }

            assertAll(
                    () -> assertTrue(store.summaryQueryCount() >= 2, "left Datatable should use lightweight AI summaries"),
                    () -> assertEquals(jsonQueriesBeforeRendering, store.jsonQueryCount(), "left Datatable rendering must not load full AI JSON"),
                    () -> assertTrue(table != null, "GET 明文id Datatable should be present"),
                    () -> assertEquals(5, table.getColumnCount()),
                    () -> assertEquals("#", table.getColumnName(0)),
                    () -> assertEquals("Information", table.getColumnName(1)),
                    () -> assertEquals("AI状态", table.getColumnName(2)),
                    () -> assertEquals("AI结论", table.getColumnName(3)),
                    () -> assertEquals("AI置信度", table.getColumnName(4)),
                    () -> assertEquals("conwid=840", table.getValueAt(0, 1)),
                    () -> assertEquals("DONE", table.getValueAt(0, 2)),
                    () -> assertEquals("未发现敏感信息", table.getValueAt(0, 3)),
                    () -> assertEquals("0.91", table.getValueAt(0, 4))
            );
        } finally {
            model.clearAllDataOnShutdown();
        }
    }

    @Test
    void selectingRuleTabRefreshesLeftAiColumnsFromLatestStoredResults() throws Exception {
        BoardContext context = createBoardContext(tempDirectory.resolve("left-datatable-refresh-on-rule-click"));
        context.configLoader().setAIWhitelist(List.of(new AiWhitelistRule("Test", List.of(TEST_RULE))));
        CountingSqliteMessageStore store = context.store();
        saveMessage(store, "message-1", "/one", "AiTestRule (1)", "yellow", "hash-1");
        MessageTableModel model = newTestModel(context);
        try {
            AtomicReference<Databoard> databoardReference = new AtomicReference<>();
            onEdt(() -> databoardReference.set(new Databoard(context.api(), context.configLoader(), model)));
            Databoard databoard = databoardReference.get();

            onEdt(() -> {
                databoard.getHostTextFieldForTest().setText("*");
                databoard.loadHostFromInputForTest();
            });
            waitUntil(() -> databoard.getDataTabCountForTest() == 1, "left Datatable tab should load before AI result exists");

            AtomicReference<Datatable> datatableReference = new AtomicReference<>();
            onEdt(() -> datatableReference.set(databoard.getDataTabComponentForTest(0)));
            Datatable datatable = datatableReference.get();
            JTable table = datatable.getDataTable();
            assertEquals("", table.getValueAt(0, 2));

            insertAiResult(
                    store.getDatabasePath(),
                    "message-1",
                    "hash-1",
                    TEST_RULE,
                    "/one",
                    "false_positive",
                    "info",
                    0.93,
                    "后来写入的 AI 结果。",
                    "{\"target\":\"late-result\"}",
                    50_000L
            );

            onEdt(() -> databoard.selectDataTabForTest(0));
            waitUntil(() -> "DONE".equals(String.valueOf(table.getValueAt(0, 2))), "clicking rule tab should refresh left AI status");

            assertAll(
                    () -> assertEquals("DONE", table.getValueAt(0, 2)),
                    () -> assertEquals("误报", table.getValueAt(0, 3)),
                    () -> assertEquals("0.93", table.getValueAt(0, 4))
            );
        } finally {
            model.clearAllDataOnShutdown();
        }
    }

    @Test
    void leftSummaryShowsDisallowedRuleWhenRuleIsOutsideAiWhitelist() throws Exception {
        BoardContext context = createBoardContext(tempDirectory.resolve("left-disallowed-rule-summary"));
        context.store().saveMessage(
                "message-linkfinder",
                httpRequestResponse("ai.example.test", "/asset.js", 200),
                "https://ai.example.test/asset.js",
                "GET",
                "200",
                "42",
                "Linkfinder (1)",
                "yellow",
                "hash-linkfinder",
                Map.of("Linkfinder", List.of("/asset.js"))
        );
        MessageTableModel model = newTestModel(context);
        try {
            AiSummaryDisplay summary = model.loadAiSummariesByRuleValue("*", Map.of("Linkfinder", List.of("/asset.js")))
                    .get("Linkfinder")
                    .get("/asset.js");

            assertAll(
                    () -> assertEquals("不分析", summary.getAiStatus()),
                    () -> assertEquals("白名单外规则", summary.getAiVerdict()),
                    () -> assertEquals("-", summary.getAiConfidence())
            );
        } finally {
            model.clearAllDataOnShutdown();
        }
    }

    @Test
    void leftSummaryShowsTaskStatesForWhitelistedRuleWithoutResult() throws Exception {
        BoardContext context = createBoardContext(tempDirectory.resolve("left-task-state-summary"));
        context.configLoader().setAIWhitelist(List.of(new AiWhitelistRule("Test", List.of(TEST_RULE))));
        CountingSqliteMessageStore store = context.store();
        saveMessage(store, "message-pending", "/pending", "AiTestRule (1)", "yellow", "hash-pending");
        saveMessage(store, "message-running", "/running", "AiTestRule (1)", "yellow", "hash-running");
        saveMessage(store, "message-failed", "/failed", "AiTestRule (1)", "yellow", "hash-failed");
        enqueueAiTask(store, "task-pending", "message-pending", "hash-pending", TEST_RULE, "/pending");
        enqueueAiTask(store, "task-running", "message-running", "hash-running", TEST_RULE, "/running");
        enqueueAiTask(store, "task-failed", "message-failed", "hash-failed", TEST_RULE, "/failed");
        updateTaskStatus(store.getDatabasePath(), "task-running", "LEASED", "", 0L);
        updateTaskStatus(store.getDatabasePath(), "task-failed", "FAILED", "PARSE_FAILED", Long.MAX_VALUE);
        MessageTableModel model = newTestModel(context);
        try {
            Map<String, AiSummaryDisplay> summaries = model.loadAiSummariesByRuleValue("*",
                    Map.of(TEST_RULE, List.of("/pending", "/running", "/failed")))
                    .get(TEST_RULE);

            assertAll(
                    () -> assertEquals("PENDING", summaries.get("/pending").getAiStatus()),
                    () -> assertEquals("排队中", summaries.get("/pending").getAiVerdict()),
                    () -> assertEquals("RUNNING", summaries.get("/running").getAiStatus()),
                    () -> assertEquals("运行中", summaries.get("/running").getAiVerdict()),
                    () -> assertEquals("FAILED", summaries.get("/failed").getAiStatus()),
                    () -> assertEquals("解析失败", summaries.get("/failed").getAiVerdict())
            );
        } finally {
            model.clearAllDataOnShutdown();
        }
    }

    @Test
    void legacyMissingOverallWithStrongItemShowsRetryPromptInsteadOfDoneUnknown() throws Exception {
        BoardContext context = createBoardContext(tempDirectory.resolve("legacy-missing-overall-strong-item"));
        saveMessage(context.store(), "message-1", "/one", "用户名 (1)", "orange", "hash-1");
        insertLegacyStrongItemMissingOverallAiResult(context.store().getDatabasePath(), "message-1", "hash-1");
        MessageTableModel model = newTestModel(context);
        try {
            loadPersistedMessagesAndCaptureEdt(model, 1, TEST_RULE, "/one");
            waitUntil(() -> model.getAiDetailTextForTest().contains("低质量结论"),
                    "missing overall legacy AI result should show retry guidance");

            String aiDetailText = model.getAiDetailTextForTest();
            AiSummaryDisplay leftSummary = model.loadAiSummariesByRuleValue("*", Map.of(TEST_RULE, List.of("/one")))
                    .get(TEST_RULE)
                    .get("/one");
            assertAll(
                    () -> assertEquals("", model.getValueAt(0, 6)),
                    () -> assertEquals("低质量结论", leftSummary.getAiStatus()),
                    () -> assertEquals("建议重试", leftSummary.getAiVerdict()),
                    () -> assertEquals("0.00", leftSummary.getAiConfidence()),
                    () -> assertTrue(aiDetailText.contains("AI状态：低质量结论")),
                    () -> assertTrue(aiDetailText.contains("AI结论：建议重试")),
                    () -> assertTrue(aiDetailText.contains("AI 返回了低质量结论")),
                    () -> assertTrue(aiDetailText.contains("原始AI结果")),
                    () -> assertFalse(aiDetailText.contains("AI状态：DONE\nAI结论：unknown"))
            );
        } finally {
            model.clearAllDataOnShutdown();
        }
    }

    @Test
    void aiDetailPanelIsRightmostComponentInMessageSplitPane() throws Exception {
        BoardContext context = createBoardContext(tempDirectory.resolve("ai-detail-layout"));
        MessageTableModel model = newTestModel(context);
        try {
            JSplitPane splitPane = model.getSplitPane();

            assertAll(
                    () -> assertEquals(JSplitPane.HORIZONTAL_SPLIT, splitPane.getOrientation()),
                    () -> assertTrue(splitPane.getLeftComponent() instanceof JSplitPane),
                    () -> assertTrue(splitPane.getRightComponent() instanceof JScrollPane),
                    () -> assertEquals(0.86, splitPane.getResizeWeight())
            );

            JScrollPane detailScrollPane = (JScrollPane) splitPane.getRightComponent();
            assertTrue(detailScrollPane.getBorder() instanceof TitledBorder);
            TitledBorder border = (TitledBorder) detailScrollPane.getBorder();
            assertEquals("AI 详情", border.getTitle());

            JSplitPane tableAndMessagePane = (JSplitPane) splitPane.getLeftComponent();
            assertAll(
                    () -> assertEquals(JSplitPane.VERTICAL_SPLIT, tableAndMessagePane.getOrientation()),
                    () -> assertEquals(0.50, tableAndMessagePane.getResizeWeight()),
                    () -> assertEquals(180, tableAndMessagePane.getLeftComponent().getMinimumSize().height),
                    () -> assertEquals(220, tableAndMessagePane.getRightComponent().getMinimumSize().height)
            );
        } finally {
            model.clearAllDataOnShutdown();
        }
    }

    @Test
    void messageTableAndEditorsUseBalancedInitialHeightRatio() throws Exception {
        BoardContext context = createBoardContext(tempDirectory.resolve("message-editor-ratio"));
        MessageTableModel model = newTestModel(context);
        try {
            JSplitPane tableAndMessagePane = model.getTableAndMessageSplitPaneForTest();
            tableAndMessagePane.setSize(1000, 800);
            tableAndMessagePane.doLayout();

            assertAll(
                    () -> assertEquals(JSplitPane.VERTICAL_SPLIT, tableAndMessagePane.getOrientation()),
                    () -> assertEquals(0.50, tableAndMessagePane.getResizeWeight()),
                    () -> assertTrue(tableAndMessagePane.getDividerLocation() >= 350),
                    () -> assertTrue(tableAndMessagePane.getDividerLocation() <= 450)
            );
        } finally {
            model.clearAllDataOnShutdown();
        }
    }

    @Test
    void staleAiDetailLoadDoesNotOverwriteAfterPageRefreshReusesSameRowIndex() throws Exception {
        BoardContext context = createBoardContext(tempDirectory.resolve("stale-detail"));
        saveMessagesAndAiResults(context.store());
        context.store().blockJsonLoadFor("message-1");
        MessageTableModel model = newTestModel(context);
        try {
            loadPersistedMessagesAndCaptureEdt(model, 1, TEST_RULE, "/one");
            assertTrue(context.store().awaitBlockedJsonLoad(), "first-row AI detail JSON load should be blocked");

            model.applyMessageFilter("AiTestRule", "/two");
            waitUntil(() -> model.getRowCount() == 1 && String.valueOf(model.getValueAt(0, 1)).contains("/two"),
                    "filter should replace row 0 with message-2");

            context.store().releaseBlockedJsonLoad();
            waitUntil(() -> model.getAiDetailTextForTest().contains("未发现敏感信息暴露。"),
                    "current message-2 AI detail should win after stale message-1 load completes");
            String aiDetailText = model.getAiDetailTextForTest();

            assertAll(
                    () -> assertEquals(0, model.getMessageTable().getSelectedRow()),
                    () -> assertTrue(aiDetailText.contains("\"id\": \"message-2\"")),
                    () -> assertFalse(aiDetailText.contains("\"id\": \"message-1\"")),
                    () -> assertFalse(aiDetailText.contains("\"id\": \"message-1\""))
            );
        } finally {
            context.store().releaseBlockedJsonLoad();
            model.clearAllDataOnShutdown();
        }
    }

    private static MessageTableModel newTestModel(BoardContext context) {
        CountingSqliteMessageStore store = context.store();
        return new MessageTableModel(
                context.api(),
                context.configLoader(),
                store,
                store,
                store,
                store
        );
    }

    private static AtomicBoolean loadPersistedMessagesAndCaptureEdt(MessageTableModel model,
                                                                    int expectedRows,
                                                                    String targetRule,
                                                                    String targetValue) throws Exception {
        CountDownLatch tableChanged = new CountDownLatch(1);
        AtomicBoolean updateOnEdt = new AtomicBoolean(false);
        onEdt(() -> model.addTableModelListener(event -> {
            updateOnEdt.set(SwingUtilities.isEventDispatchThread());
            tableChanged.countDown();
        }));

        model.loadPersistedMessages();
        if (targetRule != null && targetValue != null) {
            model.applyMessageFilter(targetRule, targetValue);
        }

        assertTrue(tableChanged.await(5, TimeUnit.SECONDS), "table model should publish page rows");
        waitUntil(() -> model.getRowCount() == expectedRows, "message table should contain the saved page rows");
        return updateOnEdt;
    }

    private static void renderAllCells(MessageTableModel model) {
        for (int row = 0; row < model.getRowCount(); row++) {
            for (int column = 0; column < model.getColumnCount(); column++) {
                model.getValueAt(row, column);
            }
        }
    }

    private static void renderAllCells(JTable table) {
        for (int row = 0; row < table.getRowCount(); row++) {
            for (int column = 0; column < table.getColumnCount(); column++) {
                table.getValueAt(row, column);
            }
        }
    }

    private static void saveMessagesAndAiResults(CountingSqliteMessageStore store) {
        saveMessage(store, "message-1", "/one", "Token (1)", "yellow", "hash-1");
        saveMessage(store, "message-2", "/two", "Password (1)", "orange", "hash-2");
        saveAiResult(
                store,
                "message-1",
                "hash-1",
                TEST_RULE,
                "/one",
                AiTriageVerdict.POSSIBLE_SENSITIVE.getWireValue(),
                AiTriageRiskLevel.HIGH.getWireValue(),
                0.81,
                "Token likely sensitive.",
                FIRST_JSON,
                20_000L
        );
        saveAiResult(
                store,
                "message-2",
                "hash-2",
                TEST_RULE,
                "/two",
                AiTriageVerdict.NOT_SENSITIVE.getWireValue(),
                AiTriageRiskLevel.INFO.getWireValue(),
                0.97,
                "No sensitive material detected.",
                SECOND_JSON,
                21_000L
        );
    }

    private static void saveMessage(CountingSqliteMessageStore store,
                                    String messageId,
                                    String path,
                                    String comment,
                                    String color,
                                    String contentHash) {
        store.saveMessage(
                messageId,
                httpRequestResponse("ai.example.test", path, 200),
                "https://ai.example.test" + path,
                "GET",
                "200",
                "42",
                comment,
                color,
                contentHash,
                Map.of("AiTestRule", List.of(path))
        );
    }

    private static void saveAiResult(CountingSqliteMessageStore store,
                                     String messageId,
                                     String contentHash,
                                     String ruleName,
                                     String value,
                                     String verdict,
                                     String riskLevel,
                                     double confidence,
                                     String summary,
                                     String resultJson,
                                     long analyzedAt) {
        insertAiResult(store.getDatabasePath(), messageId, contentHash, ruleName, value, verdict, riskLevel, confidence, summary, resultJson, analyzedAt);
    }

    private static void enqueueAiTask(CountingSqliteMessageStore store,
                                      String taskId,
                                      String messageId,
                                      String contentHash,
                                      String ruleName,
                                      String value) {
        String signature = AiTriageTargetSignature.matchSignatureHash(ruleName, value);
        assertTrue(store.enqueueAiTriageTask(
                taskId,
                messageId,
                contentHash,
                "analysis-" + signature,
                signature,
                AiTriageSchema.SCHEMA_VERSION,
                AiTriageSchema.PROMPT_VERSION,
                "gpt-test",
                "config-test",
                0,
                3,
                0L
        ));
    }

    private static void updateTaskStatus(String databasePath,
                                         String taskId,
                                         String status,
                                         String lastErrorCode,
                                         long nextAttemptAt) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE ai_triage_task
                     SET status = ?, last_error_code = ?, next_attempt_at = ?, updated_at = ?
                     WHERE task_id = ?
                     """)) {
            statement.setString(1, status);
            statement.setString(2, lastErrorCode == null ? "" : lastErrorCode);
            statement.setLong(3, nextAttemptAt);
            statement.setLong(4, System.currentTimeMillis());
            statement.setString(5, taskId);
            statement.executeUpdate();
        }
    }

    private static void insertAiResult(String databasePath,
                                       String messageId,
                                       String contentHash,
                                       String ruleName,
                                       String value,
                                       String verdict,
                                       String riskLevel,
                                       double confidence,
                                       String summary,
                                       String resultJson,
                                       long analyzedAt) {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO ai_triage_result (
                         message_id, content_hash, analysis_key, match_signature_hash, status, overall_verdict,
                         overall_severity, confidence, summary, result_json, analyzed_at,
                         schema_version, prompt_version, model, config_hash
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            String targetSignature = AiTriageTargetSignature.matchSignatureHash(ruleName, value);
            statement.setString(1, messageId);
            statement.setString(2, contentHash);
            statement.setString(3, "analysis-" + targetSignature);
            statement.setString(4, targetSignature);
            statement.setString(5, "DONE");
            statement.setString(6, verdict);
            statement.setString(7, riskLevel);
            statement.setDouble(8, confidence);
            statement.setString(9, summary);
            statement.setString(10, resultJson);
            statement.setLong(11, analyzedAt);
            statement.setString(12, AiTriageSchema.SCHEMA_VERSION);
            statement.setString(13, AiTriageSchema.PROMPT_VERSION);
            statement.setString(14, "model-a");
            statement.setString(15, "config-a");
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
    }

    private static void insertLegacyEmptyAiResult(String databasePath, String messageId, String contentHash) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO ai_triage_result (
                         message_id, content_hash, analysis_key, match_signature_hash, status, overall_verdict,
                         overall_severity, confidence, summary, result_json, analyzed_at,
                         schema_version, prompt_version, model, config_hash
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, messageId);
            statement.setString(2, contentHash);
            statement.setString(3, "analysis-empty");
            statement.setString(4, AiTriageTargetSignature.matchSignatureHash(TEST_RULE, "/one"));
            statement.setString(5, "DONE");
            statement.setString(6, "unknown");
            statement.setString(7, "unknown");
            statement.setDouble(8, 0.0);
            statement.setString(9, "");
            statement.setString(10, "{\"overall_verdict\":\"unknown\",\"overall_severity\":\"unknown\",\"confidence\":0.0,\"summary\":\"\",\"items\":[]}");
            statement.setLong(11, 30_000L);
            statement.setString(12, AiTriageSchema.SCHEMA_VERSION);
            statement.setString(13, AiTriageSchema.PROMPT_VERSION);
            statement.setString(14, "model-a");
            statement.setString(15, "config-empty");
            statement.executeUpdate();
        }
    }

    private static void insertLegacyLowQualityAiResult(String databasePath, String messageId, String contentHash) throws SQLException {
        insertAiResult(databasePath, messageId, contentHash, TEST_RULE, "/one", "false_positive", "unknown", 0.0, "", lowQualityFalsePositiveJson(), 31_000L);
    }

    private static void insertLegacyStrongItemMissingOverallAiResult(String databasePath, String messageId, String contentHash) throws SQLException {
        insertAiResult(databasePath, messageId, contentHash, TEST_RULE, "/one", "unknown", "unknown", 0.0, "", missingOverallStrongItemJson(), 32_000L);
    }

    private static void insertLegacyFalsePositiveUnknownRiskResult(String databasePath, String messageId, String contentHash) throws SQLException {
        String summary = "The matched text is a JavaScript module/resource key and static asset path, not an exposed username.";
        insertAiResult(databasePath, messageId, contentHash, TEST_RULE, "/one", "false_positive", "unknown", 0.96, summary,
                "{\"overall_verdict\":\"false_positive\",\"overall_severity\":\"unknown\",\"confidence\":0.96,\"summary\":\"" + summary + "\",\"items\":[]}",
                33_000L);
    }

    private static String lowQualityFalsePositiveJson() {
        return "{\"overall_verdict\":\"false_positive\",\"overall_severity\":\"unknown\",\"confidence\":0.0,\"summary\":\"\"," +
                "\"items\":[{\"rule_group\":\"敏感信息\",\"rule_name\":\"密码\",\"rule_hash\":\"hash-1\"," +
                "\"matched_value_redacted\":\"accSetPwd:\\\"/[redacted]\\\"\",\"match_location\":\"match context pending\"," +
                "\"verdict\":\"false_positive\",\"is_sensitive\":false,\"is_exposed\":false," +
                "\"confidence\":0.0,\"severity\":\"unknown\",\"reason\":\"static asset\",\"recommended_actions\":[]}] }";
    }

    private static String missingOverallStrongItemJson() {
        return "{\"overall_verdict\":\"unknown\",\"overall_severity\":\"unknown\",\"confidence\":0.0,\"summary\":\"," +
                "\"items_truncated\":false,\"omitted_item_count\":0," +
                "\"items\":[{\"rule_group\":\"敏感信息\",\"rule_name\":\"用户名\",\"rule_hash\":\"hash-1\"," +
                "\"matched_value_redacted\":\"\",\"match_location\":\"\",\"verdict\":\"false_positive\"," +
                "\"is_sensitive\":false,\"is_exposed\":false,\"confidence\":0.98,\"severity\":\"info\"," +
                "\"reason\":\"Static JavaScript route name, no account identifier present\",\"recommended_actions\":[]}] }";
    }

    private static BoardContext createBoardContext(Path home) throws Exception {
        Files.createDirectories(home.resolve(".config").resolve("HaE"));
        String originalHome = System.getProperty("user.home");
        MontoyaApi api = montoyaApiProxy();
        try {
            System.setProperty("user.home", home.toString());
            ConfigLoader configLoader = new ConfigLoader(api);
            CountingSqliteMessageStore store = new CountingSqliteMessageStore(api, configLoader);
            return new BoardContext(api, configLoader, store);
        } finally {
            if (originalHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", originalHome);
            }
        }
    }

    private static HttpRequestResponse httpRequestResponse(String host, String path, int statusCode) {
        HttpService service = httpServiceProxy(host);
        HttpRequest request = httpRequestProxy(service, host, path);
        HttpResponse response = httpResponseProxy(statusCode);
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "request" -> request;
            case "response" -> response;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpRequestResponse.class, handler);
    }

    private static HttpService httpServiceProxy(String host) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "host" -> host;
            case "port" -> 443;
            case "secure" -> true;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpService.class, handler);
    }

    private static HttpRequest httpRequestProxy(HttpService service, String host, String path) {
        byte[] requestBytes = ("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        ByteArray byteArray = byteArrayProxy(requestBytes);
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "httpService" -> service;
            case "url" -> "https://" + host + path;
            case "path" -> path;
            case "method" -> "GET";
            case "fileExtension" -> "";
            case "toByteArray" -> byteArray;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpRequest.class, handler);
    }

    private static HttpResponse httpResponseProxy(int statusCode) {
        byte[] responseBytes = ("HTTP/1.1 " + statusCode + " OK\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        ByteArray byteArray = byteArrayProxy(responseBytes);
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "statusCode" -> primitiveNumber(method, statusCode);
            case "toByteArray" -> byteArray;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpResponse.class, handler);
    }

    private static ByteArray byteArrayProxy(byte[] bytes) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getBytes" -> bytes;
            case "length" -> bytes.length;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(ByteArray.class, handler);
    }

    private static MontoyaApi montoyaApiProxy() {
        UserInterface userInterface = userInterfaceProxy();
        Logging logging = loggingProxy();
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "userInterface" -> userInterface;
            case "logging" -> logging;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(MontoyaApi.class, handler);
    }

    private static UserInterface userInterfaceProxy() {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "createHttpRequestEditor" -> httpRequestEditorProxy();
            case "createHttpResponseEditor" -> httpResponseEditorProxy();
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(UserInterface.class, handler);
    }

    private static HttpRequestEditor httpRequestEditorProxy() {
        JPanel panel = new JPanel();
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "uiComponent" -> panel;
            case "setRequest" -> null;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpRequestEditor.class, handler);
    }

    private static HttpResponseEditor httpResponseEditorProxy() {
        JPanel panel = new JPanel();
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "uiComponent" -> panel;
            case "setResponse" -> null;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpResponseEditor.class, handler);
    }

    private static Logging loggingProxy() {
        InvocationHandler handler = (proxy, method, args) -> null;
        return proxyFor(Logging.class, handler);
    }

    private static Object primitiveNumber(Method method, int value) {
        Class<?> returnType = method.getReturnType();
        if (returnType == Short.TYPE || returnType == Short.class) {
            return (short) value;
        }
        if (returnType == Long.TYPE || returnType == Long.class) {
            return (long) value;
        }
        return value;
    }

    private static <T> T proxyFor(Class<T> type) {
        return proxyFor(type, new DefaultProxyHandler());
    }

    private static <T> T proxyFor(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static Object defaultProxyValue(Object proxy, Method method, Object[] args) {
        if (method.getDeclaringClass() == Object.class) {
            return objectMethod(proxy, method, args);
        }

        Class<?> returnType = method.getReturnType();
        if (returnType == Void.TYPE) {
            return null;
        }
        if (returnType == Boolean.TYPE) {
            return false;
        }
        if (returnType == Integer.TYPE) {
            return 0;
        }
        if (returnType == Short.TYPE) {
            return (short) 0;
        }
        if (returnType == Long.TYPE) {
            return 0L;
        }
        if (returnType == String.class) {
            return "";
        }
        if (returnType == List.class) {
            return Collections.emptyList();
        }
        if (returnType.isInterface()) {
            return proxyFor(returnType);
        }
        return null;
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "test proxy for " + proxy.getClass().getInterfaces()[0].getName();
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> args != null && args.length > 0 && proxy == args[0];
            default -> null;
        };
    }

    private static void onEdt(Runnable runnable) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
        } else {
            SwingUtilities.invokeAndWait(runnable);
        }
    }

    private static void waitUntil(BooleanSupplier condition, String message) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        assertTrue(condition.getAsBoolean(), message);
    }

    private record BoardContext(MontoyaApi api, ConfigLoader configLoader, CountingSqliteMessageStore store) {
    }

    private static class CountingSqliteMessageStore extends SqliteMessageStore {
        private final AtomicInteger summaryQueryCount = new AtomicInteger(0);
        private final AtomicInteger jsonQueryCount = new AtomicInteger(0);
        private volatile String blockedJsonMessageId = "";
        private volatile CountDownLatch blockedJsonLoadStarted = new CountDownLatch(0);
        private volatile CountDownLatch releaseBlockedJsonLoad = new CountDownLatch(0);
        private volatile List<String> lastSummaryMessageIds = Collections.emptyList();
        private volatile String lastJsonMessageId = "";

        private CountingSqliteMessageStore(MontoyaApi api, ConfigLoader configLoader) {
            super(api, configLoader);
        }

        @Override
        public List<AiTriageResultSummary> loadAiTriageResultSummaries(List<String> messageIds) {
            summaryQueryCount.incrementAndGet();
            lastSummaryMessageIds = messageIds == null ? Collections.emptyList() : new ArrayList<>(messageIds);
            return super.loadAiTriageResultSummaries(messageIds);
        }

        @Override
        public List<AiTriageResultSummary> loadAiTriageResultSummaries(List<String> messageIds, String matchSignatureHash) {
            summaryQueryCount.incrementAndGet();
            lastSummaryMessageIds = messageIds == null ? Collections.emptyList() : new ArrayList<>(messageIds);
            return super.loadAiTriageResultSummaries(messageIds, matchSignatureHash);
        }

        @Override
        public String loadAiTriageResultJson(String messageId) {
            jsonQueryCount.incrementAndGet();
            lastJsonMessageId = messageId;
            if (Objects.equals(blockedJsonMessageId, messageId)) {
                blockedJsonLoadStarted.countDown();
                try {
                    releaseBlockedJsonLoad.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.loadAiTriageResultJson(messageId);
        }

        @Override
        public String loadAiTriageResultJson(String messageId, String matchSignatureHash) {
            jsonQueryCount.incrementAndGet();
            lastJsonMessageId = messageId;
            if (Objects.equals(blockedJsonMessageId, messageId)) {
                blockedJsonLoadStarted.countDown();
                try {
                    releaseBlockedJsonLoad.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.loadAiTriageResultJson(messageId, matchSignatureHash);
        }

        void blockJsonLoadFor(String messageId) {
            blockedJsonMessageId = messageId == null ? "" : messageId;
            blockedJsonLoadStarted = new CountDownLatch(1);
            releaseBlockedJsonLoad = new CountDownLatch(1);
        }

        boolean awaitBlockedJsonLoad() throws InterruptedException {
            return blockedJsonLoadStarted.await(5, TimeUnit.SECONDS);
        }

        void releaseBlockedJsonLoad() {
            releaseBlockedJsonLoad.countDown();
        }

        int summaryQueryCount() {
            return summaryQueryCount.get();
        }

        int jsonQueryCount() {
            return jsonQueryCount.get();
        }

        List<String> lastPageSummaryMessageIds() {
            return lastSummaryMessageIds;
        }

        String lastJsonMessageId() {
            return lastJsonMessageId;
        }
    }

    private static class DefaultProxyHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return defaultProxyValue(proxy, method, args);
        }
    }
}
