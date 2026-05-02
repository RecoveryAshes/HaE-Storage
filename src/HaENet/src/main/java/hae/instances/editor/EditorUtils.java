package hae.instances.editor;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.core.Range;
import burp.api.montoya.ui.Selection;
import hae.Config;
import hae.component.board.table.Datatable;
import hae.utils.ConfigLoader;

import javax.swing.*;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class EditorUtils {
    private EditorUtils() {
    }

    public static boolean isListHasData(List<Map<String, String>> dataList) {
        if (dataList != null && !dataList.isEmpty()) {
            Map<String, String> dataMap = dataList.get(0);
            return dataMap != null && !dataMap.isEmpty();
        }
        return false;
    }

    public static void generateTabbedPaneFromResultMap(MontoyaApi api, ConfigLoader configLoader, JTabbedPane tabbedPane, List<Map<String, String>> result) {
        tabbedPane.removeAll();
        if (result != null && !result.isEmpty()) {
            Map<String, String> dataMap = result.get(0);
            if (dataMap != null && !dataMap.isEmpty()) {
                dataMap.keySet().forEach(i -> {
                    String[] extractData = dataMap.get(i).split(Config.boundary);
                    Datatable dataPanel = new Datatable(api, configLoader, i, Arrays.asList(extractData));
                    tabbedPane.addTab(i, dataPanel);
                });
            }
        }
    }

    public static Selection selectedDataFrom(JTabbedPane tabbedPane) {
        return new Selection() {
            @Override
            public ByteArray contents() {
                Datatable dataTable = (Datatable) tabbedPane.getSelectedComponent();
                return ByteArray.byteArray(dataTable.getSelectedDataAtTable(dataTable.getDataTable()));
            }

            @Override
            public Range offsets() {
                return null;
            }
        };
    }
}
