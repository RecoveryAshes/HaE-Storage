package hae.instances.http.utils;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import hae.Config;
import hae.utils.ConfigLoader;
import hae.utils.string.StringProcessor;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class MessageProcessor {
    private final MontoyaApi api;
    private final RegularMatcher regularMatcher;

    public static class ProcessedMessage {
        private final String comment;
        private final String color;
        private final Map<String, List<String>> extractedDataByRule;

        private ProcessedMessage(String comment, String color, Map<String, List<String>> extractedDataByRule) {
            this.comment = comment;
            this.color = color;
            this.extractedDataByRule = extractedDataByRule;
        }

        public String getComment() {
            return comment;
        }

        public String getColor() {
            return color;
        }

        public Map<String, List<String>> getExtractedDataByRule() {
            return extractedDataByRule;
        }

        public boolean hasMatches() {
            return comment != null && !comment.isBlank() && color != null && !color.isBlank();
        }
    }

    public MessageProcessor(MontoyaApi api, ConfigLoader configLoader) {
        this.api = api;
        this.regularMatcher = new RegularMatcher(api, configLoader);
    }

    public List<Map<String, String>> processMessage(String host, String message, boolean flag) {
        Map<String, Map<String, Object>> obj = null;

        try {
            obj = regularMatcher.performRegexMatching(host, "any", message, message, message);
        } catch (Exception e) {
            logProcessingError("processMessage", e);
        }

        return getDataList(obj, flag);
    }

    public List<Map<String, String>> processResponse(String host, HttpResponse httpResponse, boolean flag) {
        Map<String, Map<String, Object>> obj = null;

        try {
            obj = matchResponse(host, httpResponse);
        } catch (Exception e) {
            logProcessingError("processResponse", e);
        }

        return getDataList(obj, flag);
    }

    public List<Map<String, String>> processRequest(String host, HttpRequest httpRequest, boolean flag) {
        Map<String, Map<String, Object>> obj = null;

        try {
            obj = matchRequest(host, httpRequest);
        } catch (Exception e) {
            logProcessingError("processRequest", e);
        }

        return getDataList(obj, flag);
    }

    public ProcessedMessage processRequestResponse(String host, HttpRequest httpRequest, HttpResponse httpResponse) {
        List<Map<String, Map<String, Object>>> matchResults = new ArrayList<>(2);
        matchResults.add(matchRequest(host, httpRequest, false));
        matchResults.add(matchResponse(host, httpResponse, false));
        return buildProcessedMessage(matchResults);
    }

    private Map<String, Map<String, Object>> matchRequest(String host, HttpRequest httpRequest) {
        return matchRequest(host, httpRequest, true);
    }

    private Map<String, Map<String, Object>> matchRequest(String host, HttpRequest httpRequest, boolean cacheAndPersistMatches) {
        String request = decodeBytePreserving(httpRequest.toByteArray().getBytes());
        String body = decodeBytePreserving(httpRequest.body().getBytes());
        String header = httpRequest.headers().stream()
                .map(HttpHeader::toString)
                .collect(Collectors.joining("\r\n"));

        return regularMatcher.performRegexMatching(host, "request", request, header, body, cacheAndPersistMatches);
    }

    private Map<String, Map<String, Object>> matchResponse(String host, HttpResponse httpResponse) {
        return matchResponse(host, httpResponse, true);
    }

    private Map<String, Map<String, Object>> matchResponse(String host, HttpResponse httpResponse, boolean cacheAndPersistMatches) {
        String response = decodeBytePreserving(httpResponse.toByteArray().getBytes());
        String body = decodeBytePreserving(httpResponse.body().getBytes());
        String header = httpResponse.headers().stream()
                .map(HttpHeader::toString)
                .collect(Collectors.joining("\r\n"));

        return regularMatcher.performRegexMatching(host, "response", response, header, body, cacheAndPersistMatches);
    }

    private String decodeBytePreserving(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private ProcessedMessage buildProcessedMessage(List<Map<String, Map<String, Object>>> matchResults) {
        List<String> colorList = new ArrayList<>();
        List<String> commentList = new ArrayList<>();
        Map<String, Set<String>> mergedExtractedData = new LinkedHashMap<>();

        for (Map<String, Map<String, Object>> matchResult : matchResults) {
            if (matchResult == null || matchResult.isEmpty()) {
                continue;
            }

            extractColorsAndComments(matchResult, colorList, commentList);
            appendExtractedData(mergedExtractedData, matchResult);
        }

        String color = colorList.isEmpty() ? "none" : retrieveFinalColor(retrieveColorIndices(colorList));
        String comment = commentList.isEmpty() ? "" : StringProcessor.mergeComment(String.join(", ", commentList));
        Map<String, List<String>> normalizedExtractedData = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : mergedExtractedData.entrySet()) {
            normalizedExtractedData.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }

        return new ProcessedMessage(comment, color, normalizedExtractedData);
    }

    private void extractColorsAndComments(Map<String, Map<String, Object>> inputData, List<String> colorList, List<String> commentList) {
        inputData.keySet().forEach(key -> {
            Map<String, Object> tempMap = inputData.get(key);
            Object color = tempMap.get("color");
            if (color != null) {
                colorList.add(color.toString());
            }
            commentList.add(key);
        });
    }

    private void appendExtractedData(Map<String, Set<String>> mergedMap, Map<String, Map<String, Object>> inputData) {
        for (Map.Entry<String, Map<String, Object>> entry : inputData.entrySet()) {
            String ruleName = StringProcessor.extractItemName(entry.getKey());
            if (ruleName == null || ruleName.isBlank()) {
                continue;
            }

            Map<String, Object> dataMap = entry.getValue();
            Object data = dataMap.get("data");
            if (data == null || data.toString().isBlank()) {
                continue;
            }

            Set<String> valueSet = mergedMap.computeIfAbsent(ruleName, ignored -> new LinkedHashSet<>());
            String[] values = data.toString().split(Pattern.quote(Config.boundary));
            for (String value : values) {
                if (value != null) {
                    String normalizedValue = value.trim();
                    if (!normalizedValue.isEmpty()) {
                        valueSet.add(normalizedValue);
                    }
                }
            }
        }
    }

    private void logProcessingError(String methodName, Exception exception) {
        String message = exception == null ? "unknown" : exception.getMessage();
        api.logging().logToError(methodName + ": " + message);
    }

    private List<Map<String, String>> getDataList(Map<String, Map<String, Object>> obj, boolean actionFlag) {
        List<Map<String, String>> highlightList = new ArrayList<>();
        List<Map<String, String>> extractList = new ArrayList<>();

        if (obj != null && !obj.isEmpty()) {
            if (actionFlag) {
                List<List<String>> resultList = extractColorsAndComments(obj);
                List<String> colorList = resultList.get(0);
                List<String> commentList = resultList.get(1);
                if (!colorList.isEmpty() && !commentList.isEmpty()) {
                    String color = retrieveFinalColor(retrieveColorIndices(colorList));
                    Map<String, String> colorMap = new HashMap<>() {{
                        put("color", color);
                    }};
                    Map<String, String> commentMap = new HashMap<>() {{
                        put("comment", String.join(", ", commentList));
                    }};
                    highlightList.add(colorMap);
                    highlightList.add(commentMap);
                }
            } else {
                extractList.add(extractDataFromMap(obj));
            }
        }

        return actionFlag ? highlightList : extractList;
    }

    private Map<String, String> extractDataFromMap(Map<String, Map<String, Object>> inputData) {
        Map<String, String> extractedData = new HashMap<>();
        inputData.keySet().forEach(key -> {
            Map<String, Object> tempMap = inputData.get(key);
            String data = tempMap.get("data").toString();
            extractedData.put(key, data);
        });

        return extractedData;
    }

    private List<List<String>> extractColorsAndComments(Map<String, Map<String, Object>> inputData) {
        List<String> colorList = new ArrayList<>();
        List<String> commentList = new ArrayList<>();
        inputData.keySet().forEach(key -> {
            Map<String, Object> tempMap = inputData.get(key);
            String color = tempMap.get("color").toString();
            colorList.add(color);
            commentList.add(key);
        });
        List<List<String>> result = new ArrayList<>();
        result.add(colorList);
        result.add(commentList);

        return result;
    }

    public List<Integer> retrieveColorIndices(List<String> colors) {
        List<Integer> indices = new ArrayList<>();
        String[] colorArray = Config.color;
        int size = colorArray.length;

        for (String color : colors) {
            for (int i = 0; i < size; i++) {
                if (colorArray[i].equals(color)) {
                    indices.add(i);
                }
            }
        }

        return indices;
    }

    private String upgradeColors(List<Integer> colorList) {
        if (colorList == null || colorList.isEmpty()) {
            return Config.color[0];
        }

        // 创建副本避免修改原始数据
        List<Integer> indices = new ArrayList<>(colorList);
        indices.sort(Comparator.comparingInt(Integer::intValue));

        // 处理颜色升级
        for (int i = 1; i < indices.size(); i++) {
            if (indices.get(i).equals(indices.get(i - 1))) {
                // 如果发现重复的颜色索引，将当前索引降级
                indices.set(i - 1, indices.get(i - 1) - 1);
            }
        }

        // 获取最终的颜色索引
        int finalIndex = indices.stream()
                .min(Integer::compareTo)
                .orElse(0);

        // 处理负数索引情况
        if (finalIndex < 0) {
            return Config.color[0];
        }

        return Config.color[finalIndex];
    }

    public String retrieveFinalColor(List<Integer> colorList) {
        return upgradeColors(colorList);
    }

}
