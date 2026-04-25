package hae.instances.http;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Annotations;
import burp.api.montoya.core.HighlightColor;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.handler.*;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import hae.component.board.message.MessageTableModel;
import hae.instances.http.utils.MessageProcessor;
import hae.utils.ConfigLoader;
import hae.utils.http.HttpUtils;
import hae.utils.string.StringProcessor;

public class HttpMessageActiveHandler implements HttpHandler {
    private final MontoyaApi api;
    private final HttpUtils httpUtils;
    private final MessageProcessor messageProcessor;
    private final MessageTableModel messageTableModel;

    public HttpMessageActiveHandler(MontoyaApi api, ConfigLoader configLoader, MessageTableModel messageTableModel) {
        this.api = api;
        this.httpUtils = new HttpUtils(api, configLoader);
        this.messageProcessor = new MessageProcessor(api, configLoader);
        this.messageTableModel = messageTableModel;
    }

    @Override
    public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent httpRequestToBeSent) {
        Annotations annotations = httpRequestToBeSent.annotations();
        return RequestToBeSentAction.continueWith(httpRequestToBeSent, annotations);
    }

    @Override
    public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived httpResponseReceived) {
        Annotations annotations = httpResponseReceived.annotations();
        HttpRequest request = httpResponseReceived.initiatingRequest();
        HttpRequestResponse requestResponse = HttpRequestResponse.httpRequestResponse(request, httpResponseReceived);
        String toolType = httpResponseReceived.toolSource().toolType().toolName();

        try {
            if (!httpUtils.verifyHttpRequestResponse(requestResponse, toolType)) {
                messageTableModel.add(requestResponse, true, annotations, toolType);
                applyImmediateAnnotations(requestResponse, annotations);
            }
        } catch (Exception e) {
            api.logging().logToError("handleHttpResponseReceived: " + e.getMessage());
        }

        return ResponseReceivedAction.continueWith(httpResponseReceived, annotations);
    }

    private void applyImmediateAnnotations(HttpRequestResponse requestResponse, Annotations annotations) {
        MessageProcessor.ProcessedMessage processedMessage = messageProcessor.processRequestResponse(
                resolveHost(requestResponse.request()),
                requestResponse.request(),
                requestResponse.response()
        );
        if (!processedMessage.hasMatches()) {
            return;
        }

        annotations.setHighlightColor(HighlightColor.highlightColor(processedMessage.getColor()));
        annotations.setNotes(processedMessage.getComment());
    }

    private String resolveHost(HttpRequest request) {
        try {
            String host = StringProcessor.getHostByUrl(request.url());
            if (host != null && !host.isBlank()) {
                return host;
            }
        } catch (Exception ignored) {
        }

        HttpService service = request.httpService();
        return service == null ? "" : service.host();
    }
}
