package hae.component.board.table;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hae.component.board.message.AiSummaryDisplay;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.table.TableColumn;
import org.junit.jupiter.api.Test;

class DatatableTest {
    @Test
    void invalidRegexModeShowsStatusWithoutThrowing() {
        Datatable datatable = newDatatable(List.of("abc", "def", "abc123"));

        assertDoesNotThrow(() -> onEdt(() -> {
            datatable.getRegexModeCheckBox().setSelected(false);
            datatable.getRegexModeCheckBox().setSelected(true);
            setUserText(datatable.getSearchField(), "[");
        }));

        assertTrue(datatable.getStatusLabel().getText().contains("Invalid regex"));
        assertEquals(3, datatable.getDataTable().getRowCount());
    }

    @Test
    void reverseSearchUpdatesVisibleCountStatus() {
        Datatable datatable = newDatatable(List.of("abc", "def", "abc123"));

        onEdt(() -> setUserText(datatable.getSearchField(), "abc"));
        assertEquals(2, datatable.getDataTable().getRowCount());
        assertTrue(datatable.getStatusLabel().getText().contains("Showing 2 of 3"));

        onEdt(() -> datatable.getReverseSearchCheckBox().setSelected(true));
        assertEquals(1, datatable.getDataTable().getRowCount());
        assertTrue(datatable.getStatusLabel().getText().contains("Showing 1 of 3"));
    }

    @Test
    void selectingRowNotifiesMessageFilterWithRuleAndValue() {
        Datatable datatable = newDatatable(List.of("abc", "def"));
        List<String> calls = new ArrayList<>();
        onEdt(() -> {
            datatable.setTableListener((tableName, filterText) -> calls.add(tableName + "=" + filterText));
            datatable.getDataTable().setRowSelectionInterval(1, 1);
        });

        assertEquals(List.of("Test=def"), calls);
    }

    @Test
    void aiColumnsUseRuleNameAndInformationValueProvider() {
        List<String> providerCalls = new ArrayList<>();
        Datatable datatable = newDatatable(List.of("abc", "def"), (ruleName, value) -> {
            providerCalls.add(ruleName + "=" + value);
            if ("abc".equals(value)) {
                return new AiSummaryDisplay("DONE", "疑似敏感信息", "0.81");
            }
            return AiSummaryDisplay.empty();
        });

        assertEquals(5, datatable.getDataTable().getColumnCount());
        assertEquals("#", datatable.getDataTable().getColumnName(0));
        assertEquals("Information", datatable.getDataTable().getColumnName(1));
        assertEquals("AI状态", datatable.getDataTable().getColumnName(2));
        assertEquals("AI结论", datatable.getDataTable().getColumnName(3));
        assertEquals("AI置信度", datatable.getDataTable().getColumnName(4));
        assertEquals(List.of("Test=abc", "Test=def"), providerCalls);
        assertEquals("DONE", datatable.getDataTable().getValueAt(0, 2));
        assertEquals("疑似敏感信息", datatable.getDataTable().getValueAt(0, 3));
        assertEquals("0.81", datatable.getDataTable().getValueAt(0, 4));
        assertEquals("", datatable.getDataTable().getValueAt(1, 2));
        assertEquals("", datatable.getDataTable().getValueAt(1, 3));
        assertEquals("", datatable.getDataTable().getValueAt(1, 4));
    }

    @Test
    void compactColumnsUseMeasuredPreferredWidthAndInformationKeepsRemainingSpace() {
        Datatable datatable = newDatatable(List.of("very-long-information-value-that-should-use-the-spare-table-width"),
                (ruleName, value) -> new AiSummaryDisplay("DONE", "疑似敏感信息", "0.93"));
        JTable table = datatable.getDataTable();

        assertMeasuredPreferredWidth(table, 0);
        assertMeasuredPreferredWidth(table, 2);
        assertMeasuredPreferredWidth(table, 3);
        assertMeasuredPreferredWidth(table, 4);

        TableColumn informationColumn = table.getColumnModel().getColumn(1);
        assertEquals(Integer.MAX_VALUE, informationColumn.getMaxWidth());
        assertTrue(informationColumn.getPreferredWidth() > table.getColumnModel().getColumn(3).getPreferredWidth());
        assertTrue(table.getColumnModel().getColumn(2).getPreferredWidth() >= 112);
        assertTrue(table.getColumnModel().getColumn(3).getPreferredWidth() >= 140);
        assertTrue(table.getColumnModel().getColumn(4).getPreferredWidth() >= 122);
    }

    @Test
    void refreshAiSummariesUpdatesExistingRowsWithoutRebuildingTable() {
        Datatable datatable = newDatatable(List.of("abc", "def"));

        onEdt(() -> datatable.refreshAiSummaries((ruleName, value) -> "def".equals(value)
                ? new AiSummaryDisplay("DONE", "误报", "0.93")
                : AiSummaryDisplay.empty()));

        assertEquals("", datatable.getDataTable().getValueAt(0, 2));
        assertEquals("DONE", datatable.getDataTable().getValueAt(1, 2));
        assertEquals("误报", datatable.getDataTable().getValueAt(1, 3));
        assertEquals("0.93", datatable.getDataTable().getValueAt(1, 4));
    }

    private static Datatable newDatatable(List<String> rows) {
        return newDatatable(rows, null);
    }

    private static Datatable newDatatable(List<String> rows, Datatable.AiSummaryProvider aiSummaryProvider) {
        AtomicReference<Datatable> reference = new AtomicReference<>();
        onEdt(() -> reference.set(new Datatable(null, null, "Test", rows, aiSummaryProvider)));
        return reference.get();
    }

    private static void setUserText(JTextField field, String text) {
        field.putClientProperty("isPlaceholder", false);
        field.setText(text);
    }

    private static void assertMeasuredPreferredWidth(JTable table, int columnIndex) {
        TableColumn column = table.getColumnModel().getColumn(columnIndex);
        int expectedWidth = table.getTableHeader().getFontMetrics(table.getTableHeader().getFont()).stringWidth(table.getColumnName(columnIndex));
        for (int row = 0; row < table.getRowCount(); row++) {
            Object value = table.getValueAt(row, columnIndex);
            expectedWidth = Math.max(expectedWidth, table.getFontMetrics(table.getFont()).stringWidth(value == null ? "" : value.toString()));
        }
        expectedWidth += 28;

        assertTrue(column.getPreferredWidth() >= expectedWidth);
        assertTrue(column.getMinWidth() < column.getPreferredWidth());
        assertTrue(column.getMaxWidth() > column.getPreferredWidth());
    }

    private static void onEdt(Runnable runnable) {
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                runnable.run();
            } else {
                SwingUtilities.invokeAndWait(runnable);
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
