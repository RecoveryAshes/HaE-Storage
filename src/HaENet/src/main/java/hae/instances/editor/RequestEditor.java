package hae.instances.editor;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.ui.Selection;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpRequestEditor;
import burp.api.montoya.ui.editor.extension.HttpRequestEditorProvider;
import hae.instances.http.utils.MessageProcessor;
import hae.utils.ConfigLoader;
import hae.utils.http.HttpUtils;
import hae.utils.string.StringProcessor;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Map;

public class RequestEditor implements HttpRequestEditorProvider {
    private final MontoyaApi api;
    private final ConfigLoader configLoader;

    public RequestEditor(MontoyaApi api, ConfigLoader configLoader) {
        this.api = api;
        this.configLoader = configLoader;
    }

    public static boolean isListHasData(List<Map<String, String>> dataList) {
        return EditorUtils.isListHasData(dataList);
    }

    public static void generateTabbedPaneFromResultMap(MontoyaApi api, ConfigLoader configLoader, JTabbedPane tabbedPane, List<Map<String, String>> result) {
        EditorUtils.generateTabbedPaneFromResultMap(api, configLoader, tabbedPane, result);
    }

    @Override
    public ExtensionProvidedHttpRequestEditor provideHttpRequestEditor(EditorCreationContext editorCreationContext) {
        return new Editor(api, configLoader, editorCreationContext);
    }

    private static class Editor implements ExtensionProvidedHttpRequestEditor {
        private final MontoyaApi api;
        private final ConfigLoader configLoader;
        private final HttpUtils httpUtils;
        private final EditorCreationContext creationContext;
        private final MessageProcessor messageProcessor;
        private final JTabbedPane jTabbedPane = new JTabbedPane();
        private HttpRequestResponse requestResponse;
        private List<Map<String, String>> dataList;

        public Editor(MontoyaApi api, ConfigLoader configLoader, EditorCreationContext creationContext) {
            this.api = api;
            this.configLoader = configLoader;
            this.httpUtils = new HttpUtils(api, configLoader);
            this.creationContext = creationContext;
            this.messageProcessor = new MessageProcessor(api, configLoader);
        }

        @Override
        public HttpRequest getRequest() {
            return requestResponse.request();
        }

        @Override
        public void setRequestResponse(HttpRequestResponse requestResponse) {
            this.requestResponse = requestResponse;
            EditorUtils.generateTabbedPaneFromResultMap(api, configLoader, jTabbedPane, this.dataList);
        }

        @Override
        public synchronized boolean isEnabledFor(HttpRequestResponse requestResponse) {
            HttpRequest request = requestResponse.request();
            if (request != null) {
                try {
                    String host = StringProcessor.getHostByUrl(request.url());
                    if (!host.isEmpty()) {
                        String toolType = creationContext.toolSource().toolType().toolName();
                        boolean matches = httpUtils.verifyHttpRequestResponse(requestResponse, toolType);

                        if (!matches) {
                            this.dataList = messageProcessor.processRequest("", request, false);
                            return EditorUtils.isListHasData(this.dataList);
                        }
                    }
                } catch (Exception ignored) {
                }
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
