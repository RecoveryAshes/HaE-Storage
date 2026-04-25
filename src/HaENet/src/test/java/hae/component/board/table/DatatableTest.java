package hae.component.board.table;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
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

    private static Datatable newDatatable(List<String> rows) {
        AtomicReference<Datatable> reference = new AtomicReference<>();
        onEdt(() -> reference.set(new Datatable(null, null, "Test", rows)));
        return reference.get();
    }

    private static void setUserText(JTextField field, String text) {
        field.putClientProperty("isPlaceholder", false);
        field.setText(text);
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
