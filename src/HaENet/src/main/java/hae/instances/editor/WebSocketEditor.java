package hae.instances.editor;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.ui.Selection;
import burp.api.montoya.ui.contextmenu.WebSocketMessage;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedWebSocketMessageEditor;
import burp.api.montoya.ui.editor.extension.WebSocketMessageEditorProvider;
import hae.instances.http.utils.MessageProcessor;
import hae.utils.ConfigLoader;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Map;

public class WebSocketEditor implements WebSocketMessageEditorProvider {
    private final MontoyaApi api;
    private final ConfigLoader configLoader;

    public WebSocketEditor(MontoyaApi api, ConfigLoader configLoader) {
        this.api = api;
        this.configLoader = configLoader;
    }

    @Override
    public ExtensionProvidedWebSocketMessageEditor provideMessageEditor(EditorCreationContext editorCreationContext) {
        return new Editor(api, configLoader, editorCreationContext);
    }

    private static class Editor implements ExtensionProvidedWebSocketMessageEditor {
        private final MontoyaApi api;
        private final ConfigLoader configLoader;
        private final EditorCreationContext creationContext;
        private final MessageProcessor messageProcessor;
        private final JTabbedPane jTabbedPane = new JTabbedPane();
        private ByteArray message;
        private List<Map<String, String>> dataList;

        public Editor(MontoyaApi api, ConfigLoader configLoader, EditorCreationContext creationContext) {
            this.api = api;
            this.configLoader = configLoader;
            this.creationContext = creationContext;
            this.messageProcessor = new MessageProcessor(api, configLoader);
        }

        @Override
        public ByteArray getMessage() {
            return message;
        }

        @Override
        public void setMessage(WebSocketMessage webSocketMessage) {
            this.message = webSocketMessage.payload();
            EditorUtils.generateTabbedPaneFromResultMap(api, configLoader, jTabbedPane, this.dataList);
        }

        @Override
        public boolean isEnabledFor(WebSocketMessage webSocketMessage) {
            String websocketMessage = webSocketMessage.payload().toString();
            if (!websocketMessage.isEmpty()) {
                this.dataList = messageProcessor.processMessage("", websocketMessage, false);
                return EditorUtils.isListHasData(this.dataList);
            }
            return false;
        }

        @Override
        public String caption() {
            return "MarkInfo";
        }

        @Override
        public Component uiComponent() {
            return jTabbedPane;
        }

        @Override
        public Selection selectedData() {
            return EditorUtils.selectedDataFrom(jTabbedPane);
        }

        @Override
        public boolean isModified() {
            return false;
        }
    }
}
