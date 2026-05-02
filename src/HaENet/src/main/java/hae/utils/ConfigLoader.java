package hae.utils;

import burp.api.montoya.MontoyaApi;
import hae.Config;
import hae.ai.AiConfig;
import hae.ai.AiWhitelistRule;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ConfigLoader {
    private final MontoyaApi api;
    private final Yaml yaml;
    private final String configFilePath;
    private final String rulesFilePath;

    public ConfigLoader(MontoyaApi api) {
        this.api = api;
        this.yaml = createSecureYaml();

        String configPath = determineConfigPath();
        this.configFilePath = String.format("%s/%s", configPath, "Config.yml");
        this.rulesFilePath = String.format("%s/%s", configPath, "Rules.yml");

        // 构造函数，初始化配置
        File HaEConfigPathFile = new File(configPath);
        if (!(HaEConfigPathFile.exists() && HaEConfigPathFile.isDirectory())) {
            HaEConfigPathFile.mkdirs();
        }

        File configFilePath = new File(this.configFilePath);
        if (!(configFilePath.exists() && configFilePath.isFile())) {
            initConfig();
        }

        File rulesFilePath = new File(this.rulesFilePath);
        if (!(rulesFilePath.exists() && rulesFilePath.isFile())) {
            initRules();
        }

        Config.globalRules = getRules();
    }

    private static boolean isValidConfigPath(String configPath) {
        File configPathFile = new File(configPath);
        return configPathFile.exists() && configPathFile.isDirectory();
    }

    private Yaml createSecureYaml() {
        // 配置 LoaderOptions 进行安全限制
        LoaderOptions loaderOptions = new LoaderOptions();
        // 禁用注释处理
        loaderOptions.setProcessComments(false);
        // 禁止递归键
        loaderOptions.setAllowRecursiveKeys(false);

        // 配置 DumperOptions 控制输出格式
        DumperOptions dop = new DumperOptions();
        dop.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);

        // 创建 Representer
        Representer representer = new Representer(dop);

        // 使用 SafeConstructor创建安全的 YAML 实例
        return new Yaml(new SafeConstructor(loaderOptions), representer, dop);
    }

    private String determineConfigPath() {
        // 优先级1：用户根目录
        String userConfigPath = String.format("%s/.config/HaE", System.getProperty("user.home"));
        if (isValidConfigPath(userConfigPath)) {
            return userConfigPath;
        }

        // 优先级2：Jar包所在目录
        String jarPath = api.extension().filename();
        String jarDirectory = new File(jarPath).getParent();
        String jarConfigPath = String.format("%s/.config/HaE", jarDirectory);
        if (isValidConfigPath(jarConfigPath)) {
            return jarConfigPath;
        }

        return userConfigPath;
    }

    public void initConfig() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ExcludeSuffix", getExcludeSuffix());
        r.put("BlockHost", getBlockHost());
        r.put("ExcludeStatus", getExcludeStatus());
        r.put("LimitSize", getLimitSize());
        r.put("HaEScope", getScope());
        r.put("DynamicHeader", getDynamicHeader());
        r.put("AIEnabled", getAIEnabled());
        r.put("AIUseBurpProxy", getAIUseBurpProxy());
        r.put("AIProviderType", getAIProviderType());
        r.put("AIBaseUrl", getAIBaseUrl());
        r.put("AIModel", getAIModel());
        r.put("AIApiKey", getAIApiKey());
        r.put("AIRequestTimeoutSeconds", getAIRequestTimeoutSeconds());
        r.put("AIConcurrency", getAIConcurrency());
        r.put("AIMaxConcurrency", getAIMaxConcurrency());
        r.put("AIMaxInFlightChars", getAIMaxInFlightChars());
        r.put("AIMaxTotalChars", getAIMaxTotalChars());
        r.put("AIMaxRequestChars", getAIMaxRequestChars());
        r.put("AIMaxResponseChars", getAIMaxResponseChars());
        r.put("AIMaxItemsPerMessage", getAIMaxItemsPerMessage());
        r.put("AIAnalyzeOncePerMessage", getAIAnalyzeOncePerMessage());
        r.put("AISendFullRequest", getAISendFullRequest());
        r.put("AISendFullResponse", getAISendFullResponse());
        r.put("AISkipBinary", getAISkipBinary());
        r.put("AISkipStaticResources", getAISkipStaticResources());
        r.put("AIMaxQueueSize", getAIMaxQueueSize());
        r.put("AISaveFullPrompt", getAISaveFullPrompt());
        r.put("AIWhitelist", getAIWhitelistConfig());

        try {
            Writer ws = new OutputStreamWriter(Files.newOutputStream(Paths.get(configFilePath)), StandardCharsets.UTF_8);
            yaml.dump(r, ws);
            ws.close();
        } catch (Exception ignored) {
        }
    }

    public String getRulesFilePath() {
        return rulesFilePath;
    }

    // 获取规则配置
    public Map<String, Object[][]> getRules() {
        Map<String, Object[][]> rules = new ConcurrentHashMap<>();

        try {
            InputStream inputStream = Files.newInputStream(Paths.get(getRulesFilePath()));
            Map<String, Object> rulesMap = yaml.load(inputStream);

            Object rulesObj = rulesMap.get("rules");
            if (rulesObj instanceof List) {
                List<Map<String, Object>> groupData = (List<Map<String, Object>>) rulesObj;
                for (Map<String, Object> groupFields : groupData) {
                    ArrayList<Object[]> data = new ArrayList<>();

                    Object ruleObj = groupFields.get("rule");
                    if (ruleObj instanceof List) {
                        List<Map<String, Object>> ruleData = (List<Map<String, Object>>) ruleObj;
                        for (Map<String, Object> ruleFields : ruleData) {
                            Object[] valuesArray = new Object[Config.ruleFields.length];
                            for (int i = 0; i < Config.ruleFields.length; i++) {
                                valuesArray[i] = ruleFields.get(Config.ruleFields[i].toLowerCase().replace("-", "_"));
                            }
                            data.add(valuesArray);
                        }
                    }

                    Object[][] dataArray = data.toArray(new Object[data.size()][]);
                    rules.put(groupFields.get("group").toString(), dataArray);
                }
            }

            return rules;
        } catch (Exception ignored) {
        }

        return rules;
    }

    public String getBlockHost() {
        return getValueFromConfig("BlockHost", Config.host);
    }

    public void setBlockHost(String blockHost) {
        setValueToConfig("BlockHost", blockHost);
    }

    public String getExcludeSuffix() {
        return getValueFromConfig("ExcludeSuffix", Config.suffix);
    }

    public void setExcludeSuffix(String excludeSuffix) {
        setValueToConfig("ExcludeSuffix", excludeSuffix);
    }

    public String getExcludeStatus() {
        return getValueFromConfig("ExcludeStatus", Config.status);
    }

    public void setExcludeStatus(String status) {
        setValueToConfig("ExcludeStatus", status);
    }

    public String getDynamicHeader() {
        return getValueFromConfig("DynamicHeader", Config.header);
    }

    public void setDynamicHeader(String header) {
        setValueToConfig("DynamicHeader", header);
    }

    public String getLimitSize() {
        return getValueFromConfig("LimitSize", Config.size);
    }

    public void setLimitSize(String size) {
        setValueToConfig("LimitSize", size);
    }

    public String getScope() {
        return getValueFromConfig("HaEScope", Config.scopeOptions);
    }

    public void setScope(String scope) {
        setValueToConfig("HaEScope", scope);
    }

    public boolean getMode() {
        return getValueFromConfig("HaEModeStatus", Config.modeStatus).equals("true");
    }

    public void setMode(String mode) {
        setValueToConfig("HaEModeStatus", mode);
    }

    public AiConfig getAiConfig() {
        return new AiConfig(
                getAIEnabled(),
                getAIUseBurpProxy(),
                getAIProviderType(),
                getAIBaseUrl(),
                getAIModel(),
                getAIApiKey(),
                getAIRequestTimeoutSeconds(),
                getAIConcurrency(),
                getAIMaxConcurrency(),
                getAIMaxInFlightChars(),
                getAIMaxTotalChars(),
                getAIMaxRequestChars(),
                getAIMaxResponseChars(),
                getAIMaxItemsPerMessage(),
                getAIAnalyzeOncePerMessage(),
                getAISendFullRequest(),
                getAISendFullResponse(),
                getAISkipBinary(),
                getAISkipStaticResources(),
                getAIMaxQueueSize(),
                getAISaveFullPrompt(),
                getAIWhitelist()
        );
    }

    public boolean getAIEnabled() {
        return getBooleanFromConfig("AIEnabled", Config.AIEnabled);
    }

    public void setAIEnabled(boolean enabled) {
        setValueToConfig("AIEnabled", enabled);
    }

    public boolean getAIUseBurpProxy() {
        return getBooleanFromConfig("AIUseBurpProxy", Config.AIUseBurpProxy);
    }

    public void setAIUseBurpProxy(boolean useBurpProxy) {
        setValueToConfig("AIUseBurpProxy", useBurpProxy);
    }

    public String getAIProviderType() {
        return getValueFromConfig("AIProviderType", Config.AIProviderType);
    }

    public void setAIProviderType(String providerType) {
        setValueToConfig("AIProviderType", providerType);
    }

    public String getAIBaseUrl() {
        return getValueFromConfig("AIBaseUrl", Config.AIBaseUrl);
    }

    public void setAIBaseUrl(String baseUrl) {
        setValueToConfig("AIBaseUrl", baseUrl);
    }

    public String getAIModel() {
        return getValueFromConfig("AIModel", Config.AIModel);
    }

    public void setAIModel(String model) {
        setValueToConfig("AIModel", model);
    }

    public String getAIApiKey() {
        return getValueFromConfig("AIApiKey", Config.AIApiKey);
    }

    public void setAIApiKey(String apiKey) {
        setValueToConfig("AIApiKey", apiKey);
    }

    public int getAIRequestTimeoutSeconds() {
        return getIntFromConfig("AIRequestTimeoutSeconds", Config.AIRequestTimeoutSeconds);
    }

    public void setAIRequestTimeoutSeconds(int requestTimeoutSeconds) {
        setValueToConfig("AIRequestTimeoutSeconds", requestTimeoutSeconds);
    }

    public int getAIConcurrency() {
        return getIntFromConfig("AIConcurrency", Config.AIConcurrency);
    }

    public void setAIConcurrency(int concurrency) {
        setValueToConfig("AIConcurrency", concurrency);
    }

    public int getAIMaxConcurrency() {
        return getIntFromConfig("AIMaxConcurrency", Config.AIMaxConcurrency);
    }

    public void setAIMaxConcurrency(int maxConcurrency) {
        setValueToConfig("AIMaxConcurrency", maxConcurrency);
    }

    public int getAIMaxInFlightChars() {
        return getIntFromConfig("AIMaxInFlightChars", Config.AIMaxInFlightChars);
    }

    public void setAIMaxInFlightChars(int maxInFlightChars) {
        setValueToConfig("AIMaxInFlightChars", maxInFlightChars);
    }

    public int getAIMaxTotalChars() {
        return getIntFromConfig("AIMaxTotalChars", Config.AIMaxTotalChars);
    }

    public void setAIMaxTotalChars(int maxTotalChars) {
        setValueToConfig("AIMaxTotalChars", maxTotalChars);
    }

    public int getAIMaxRequestChars() {
        return getIntFromConfig("AIMaxRequestChars", Config.AIMaxRequestChars);
    }

    public void setAIMaxRequestChars(int maxRequestChars) {
        setValueToConfig("AIMaxRequestChars", maxRequestChars);
    }

    public int getAIMaxResponseChars() {
        return getIntFromConfig("AIMaxResponseChars", Config.AIMaxResponseChars);
    }

    public void setAIMaxResponseChars(int maxResponseChars) {
        setValueToConfig("AIMaxResponseChars", maxResponseChars);
    }

    public int getAIMaxItemsPerMessage() {
        return getIntFromConfig("AIMaxItemsPerMessage", Config.AIMaxItemsPerMessage);
    }

    public void setAIMaxItemsPerMessage(int maxItemsPerMessage) {
        setValueToConfig("AIMaxItemsPerMessage", maxItemsPerMessage);
    }

    public boolean getAIAnalyzeOncePerMessage() {
        return getBooleanFromConfig("AIAnalyzeOncePerMessage", Config.AIAnalyzeOncePerMessage);
    }

    public void setAIAnalyzeOncePerMessage(boolean analyzeOncePerMessage) {
        setValueToConfig("AIAnalyzeOncePerMessage", analyzeOncePerMessage);
    }

    public boolean getAISendFullRequest() {
        return getBooleanFromConfig("AISendFullRequest", Config.AISendFullRequest);
    }

    public void setAISendFullRequest(boolean sendFullRequest) {
        setValueToConfig("AISendFullRequest", sendFullRequest);
    }

    public boolean getAISendFullResponse() {
        return getBooleanFromConfig("AISendFullResponse", Config.AISendFullResponse);
    }

    public void setAISendFullResponse(boolean sendFullResponse) {
        setValueToConfig("AISendFullResponse", sendFullResponse);
    }

    public boolean getAISkipBinary() {
        return getBooleanFromConfig("AISkipBinary", Config.AISkipBinary);
    }

    public void setAISkipBinary(boolean skipBinary) {
        setValueToConfig("AISkipBinary", skipBinary);
    }

    public boolean getAISkipStaticResources() {
        return getBooleanFromConfig("AISkipStaticResources", Config.AISkipStaticResources);
    }

    public void setAISkipStaticResources(boolean skipStaticResources) {
        setValueToConfig("AISkipStaticResources", skipStaticResources);
    }

    public int getAIMaxQueueSize() {
        return getIntFromConfig("AIMaxQueueSize", Config.AIMaxQueueSize);
    }

    public void setAIMaxQueueSize(int maxQueueSize) {
        setValueToConfig("AIMaxQueueSize", maxQueueSize);
    }

    public boolean getAISaveFullPrompt() {
        return getBooleanFromConfig("AISaveFullPrompt", Config.AISaveFullPrompt);
    }

    public void setAISaveFullPrompt(boolean saveFullPrompt) {
        setValueToConfig("AISaveFullPrompt", saveFullPrompt);
    }

    public List<AiWhitelistRule> getAIWhitelist() {
        Object configuredWhitelist = getObjectFromConfig("AIWhitelist", getAIWhitelistConfig());
        if (!(configuredWhitelist instanceof List<?> rawRules)) {
            return List.of(defaultAIWhitelistRule());
        }

        List<AiWhitelistRule> parsedRules = new ArrayList<>();
        for (Object rawRule : rawRules) {
            AiWhitelistRule rule = AiWhitelistRule.fromObject(rawRule);
            if (!rule.getGroup().isBlank() || !rule.getNames().isEmpty()) {
                parsedRules.add(rule);
            }
        }
        return parsedRules.isEmpty() ? List.of(defaultAIWhitelistRule()) : parsedRules;
    }

    public void setAIWhitelist(List<AiWhitelistRule> whitelist) {
        List<Map<String, Object>> yamlWhitelist = new ArrayList<>();
        for (AiWhitelistRule rule : whitelist) {
            yamlWhitelist.add(aiWhitelistRuleToMap(rule));
        }
        setValueToConfig("AIWhitelist", yamlWhitelist);
    }

    private String getValueFromConfig(String name, String defaultValue) {
        Object value = getObjectFromConfig(name, defaultValue);
        return value == null ? defaultValue : value.toString();
    }

    private boolean getBooleanFromConfig(String name, boolean defaultValue) {
        Object value = getObjectFromConfig(name, defaultValue);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value != null) {
            return Boolean.parseBoolean(value.toString());
        }
        return defaultValue;
    }

    private int getIntFromConfig(String name, int defaultValue) {
        Object value = getObjectFromConfig(name, defaultValue);
        if (value instanceof Number numberValue) {
            int parsedValue = numberValue.intValue();
            return parsedValue > 0 ? parsedValue : defaultValue;
        }
        if (value != null) {
            try {
                int parsedValue = Integer.parseInt(value.toString());
                return parsedValue > 0 ? parsedValue : defaultValue;
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private Object getObjectFromConfig(String name, Object defaultValue) {
        File yamlSetting = new File(configFilePath);
        if (!yamlSetting.exists() || !yamlSetting.isFile()) {
            return defaultValue;
        }

        try (InputStream inorder = Files.newInputStream(Paths.get(configFilePath))) {
            Map<String, Object> r = yaml.load(inorder);

            if (r != null && r.containsKey(name)) {
                return r.get(name);
            }
        } catch (Exception ignored) {
        }

        return defaultValue;
    }

    private void setValueToConfig(String name, Object value) {
        Map<String, Object> currentConfig = loadCurrentConfig();
        currentConfig.put(name, value);

        try (Writer ws = new OutputStreamWriter(Files.newOutputStream(Paths.get(configFilePath)), StandardCharsets.UTF_8)) {
            yaml.dump(currentConfig, ws);
        } catch (Exception ignored) {
        }
    }

    private Map<String, Object> loadCurrentConfig() {
        Path path = Paths.get(configFilePath);
        if (!Files.exists(path)) {
            return new LinkedHashMap<>(); // 返回空的Map，表示没有当前配置
        }

        try (InputStream in = Files.newInputStream(path)) {
            Map<String, Object> currentConfig = yaml.load(in);
            return currentConfig == null ? new LinkedHashMap<>() : currentConfig;
        } catch (Exception e) {
            return new LinkedHashMap<>(); // 读取失败时也返回空的Map
        }
    }

    private List<Map<String, Object>> getAIWhitelistConfig() {
        return List.of(aiWhitelistRuleToMap(defaultAIWhitelistRule()));
    }

    private AiWhitelistRule defaultAIWhitelistRule() {
        return new AiWhitelistRule(Config.AIWhitelistGroup, Config.AIWhitelistNames);
    }

    private Map<String, Object> aiWhitelistRuleToMap(AiWhitelistRule rule) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("group", rule.getGroup());
        entry.put("names", new ArrayList<>(rule.getNames()));
        return entry;
    }

    public boolean initRules() {
        boolean ret = copyRulesToFile(this.rulesFilePath);
        if (!ret) {
            api.extension().unload();
        }
        return ret;
    }

    private boolean copyRulesToFile(String targetFilePath) {
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream("rules/Rules.yml");
        File targetFile = new File(targetFilePath);

        try (inputStream; OutputStream outputStream = new FileOutputStream(targetFile)) {
            if (inputStream != null) {
                byte[] buffer = new byte[1024];
                int length;

                while ((length = inputStream.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, length);
                }

                return true;
            }
        } catch (Exception ignored) {
        }

        return false;
    }
}
