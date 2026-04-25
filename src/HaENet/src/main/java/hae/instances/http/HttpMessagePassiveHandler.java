package hae.instances.http;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.scanner.AuditResult;
import burp.api.montoya.scanner.ConsolidationAction;
import burp.api.montoya.scanner.ScanCheck;
import burp.api.montoya.scanner.audit.insertionpoint.AuditInsertionPoint;
import burp.api.montoya.scanner.audit.issues.AuditIssue;
import hae.component.board.message.MessageTableModel;
import hae.utils.ConfigLoader;

import static burp.api.montoya.scanner.AuditResult.auditResult;
import static burp.api.montoya.scanner.ConsolidationAction.KEEP_BOTH;
import static burp.api.montoya.scanner.ConsolidationAction.KEEP_EXISTING;
import static java.util.Collections.emptyList;

public class HttpMessagePassiveHandler implements ScanCheck {
    private final MontoyaApi api;
    private final MessageTableModel messageTableModel;

    public HttpMessagePassiveHandler(MontoyaApi api, ConfigLoader configLoader, MessageTableModel messageTableModel) {
        this.api = api;
        this.messageTableModel = messageTableModel;
    }

    @Override
    public AuditResult activeAudit(HttpRequestResponse httpRequestResponse, AuditInsertionPoint auditInsertionPoint) {
        return auditResult(emptyList());
    }

    @Override
    public AuditResult passiveAudit(HttpRequestResponse httpRequestResponse) {
        try {
            messageTableModel.add(httpRequestResponse, true, null, "Scanner");
        } catch (Exception e) {
            api.logging().logToError("passiveAudit: " + e.getMessage());
        }

        return auditResult(emptyList());
    }

    @Override
    public ConsolidationAction consolidateIssues(AuditIssue newIssue, AuditIssue existingIssue) {
        return existingIssue.name().equals(newIssue.name()) ? KEEP_EXISTING : KEEP_BOTH;
    }
}
