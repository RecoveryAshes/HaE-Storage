package hae.utils.http;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import hae.utils.ConfigLoader;
import hae.utils.string.StringProcessor;

import java.util.Arrays;
import java.util.List;

public class HttpUtils {
    private final MontoyaApi api;
    private final ConfigLoader configLoader;

    public HttpUtils(MontoyaApi api, ConfigLoader configLoader) {
        this.api = api;
        this.configLoader = configLoader;
    }

    public boolean verifyHttpRequestResponse(HttpRequestResponse requestResponse, String toolType) {
        return !getFilterReason(requestResponse, toolType).isBlank();
    }

    public String getFilterReason(HttpRequestResponse requestResponse, String toolType) {
        HttpRequest request = requestResponse.request();
        HttpResponse response = requestResponse.response();
        StringBuilder reason = new StringBuilder();
        try {
            String host = getSafeHost(request);

            boolean isBlockHost = false;
            String blockHost = configLoader.getBlockHost();
            if (!blockHost.isBlank()) {
                String[] hostList = configLoader.getBlockHost().split("\\|");
                isBlockHost = isBlockHost(hostList, host);
                appendReason(reason, isBlockHost, "BlockHost");
            }

            boolean isExcludeSuffix = false;
            String suffix = configLoader.getExcludeSuffix();
            if (!suffix.isBlank()) {
                List<String> suffixList = Arrays.asList(configLoader.getExcludeSuffix().split("\\|"));
                isExcludeSuffix = suffixList.contains(getSafeFileExtension(request));
                appendReason(reason, isExcludeSuffix, "ExcludeSuffix");
            }

            boolean isToolScope = !configLoader.getScope().contains(toolType);
            appendReason(reason, isToolScope, "HaEScope");

            boolean isExcludeStatus = false;
            String status = configLoader.getExcludeStatus();
            if (!status.isBlank()) {
                List<String> statusList = Arrays.asList(configLoader.getExcludeStatus().split("\\|"));
                isExcludeStatus = statusList.contains(getSafeStatus(response));
                appendReason(reason, isExcludeStatus, "ExcludeStatus");
            }
        } catch (Exception e) {
            api.logging().logToError("getFilterReason: " + e.getMessage());
        }

        return reason.toString();
    }

    private void appendReason(StringBuilder reason, boolean matched, String reasonName) {
        if (!matched) {
            return;
        }
        if (!reason.isEmpty()) {
            reason.append(",");
        }
        reason.append(reasonName);
    }

    private String getSafeHost(HttpRequest request) {
        try {
            String host = StringProcessor.getHostByUrl(request.url());
            if (host != null && !host.isBlank()) {
                return host;
            }
        } catch (Exception e) {
            return getServiceHost(request);
        }

        return getServiceHost(request);
    }

    private String getServiceHost(HttpRequest request) {
        HttpService service = request.httpService();
        return service == null ? "" : service.host();
    }

    private String getSafeFileExtension(HttpRequest request) {
        try {
            String fileExtension = request.fileExtension();
            return fileExtension == null ? "" : fileExtension.toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }

    private String getSafeStatus(HttpResponse response) {
        try {
            return String.valueOf(response.statusCode());
        } catch (Exception e) {
            return "";
        }
    }

    private boolean isBlockHost(String[] hostList, String host) {
        boolean isBlockHost = false;
        for (String hostName : hostList) {
            String cleanedHost = StringProcessor.replaceFirstOccurrence(hostName, "*.", "");
            if (hostName.contains("*.") && StringProcessor.matchFromEnd(host, cleanedHost)) {
                isBlockHost = true;
            } else if (host.equals(hostName) || hostName.equals("*")) {
                isBlockHost = true;
            }
        }
        return isBlockHost;
    }
}
