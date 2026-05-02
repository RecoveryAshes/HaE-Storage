package hae.instances.editor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import hae.Config;
import hae.component.board.table.Datatable;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.util.List;
import java.util.Map;

class EditorUtilsTest {
    @Test
    void emptyAndNullMarkInfoResultsCreateNoTabsWithoutException() {
        JTabbedPane tabbedPane = new JTabbedPane();

        assertDoesNotThrow(() -> EditorUtils.generateTabbedPaneFromResultMap(null, null, tabbedPane, null));
        assertEquals(0, tabbedPane.getTabCount());

        assertDoesNotThrow(() -> EditorUtils.generateTabbedPaneFromResultMap(null, null, tabbedPane, List.of()));
        assertEquals(0, tabbedPane.getTabCount());

        assertDoesNotThrow(() -> EditorUtils.generateTabbedPaneFromResultMap(null, null, tabbedPane, List.of(Map.of())));
        assertEquals(0, tabbedPane.getTabCount());
    }

    @Test
    void boundarySeparatedResultCreatesNamedTabAndRows() {
        JTabbedPane tabbedPane = new JTabbedPane();
        String tabName = "Token (2)";

        EditorUtils.generateTabbedPaneFromResultMap(
                null,
                null,
                tabbedPane,
                List.of(Map.of(tabName, "a" + Config.boundary + "b"))
        );

        assertEquals(1, tabbedPane.getTabCount());
        assertEquals(tabName, tabbedPane.getTitleAt(0));

        Datatable dataPanel = assertInstanceOf(Datatable.class, tabbedPane.getComponentAt(0));
        JTable table = dataPanel.getDataTable();
        assertEquals(2, table.getRowCount());
        assertEquals("a", table.getValueAt(0, 1));
        assertEquals("b", table.getValueAt(1, 1));
    }
}
