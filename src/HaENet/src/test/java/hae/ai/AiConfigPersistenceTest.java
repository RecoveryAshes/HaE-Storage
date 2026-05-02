package hae.ai;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.extension.Extension;
import hae.ai.client.AiClientException;
import hae.ai.client.AiClientFailureCategory;
import hae.ai.client.OpenAiCompatibleAiClient;
import hae.ai.worker.AiTriageWorkerConfig;
import hae.component.board.DataboardAiSettingsModel;
import hae.utils.ConfigLoader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class AiConfigPersistenceTest {
    @TempDir
    Path tempDirectory;

    @Test
    void defaultValues() throws Exception {
        Path home = tempDirectory.resolve("defaults-home");
        ConfigLoader configLoader = createConfigLoader(home);
        Map<String, Object> yamlConfig = readConfigYaml(home);
        AiConfig aiConfig = configLoader.getAiConfig();

        assertAll(
                () -> assertEquals(false, yamlConfig.get("AIEnabled")),
                () -> assertEquals(false, yamlConfig.get("AIUseBurpProxy")),
                () -> assertEquals("openai-compatible", yamlConfig.get("AIProviderType")),
                () -> assertEquals("", yamlConfig.get("AIBaseUrl")),
                () -> assertEquals("", yamlConfig.get("AIModel")),
                () -> assertEquals("", yamlConfig.get("AIApiKey")),
                () -> assertEquals(180, yamlConfig.get("AIRequestTimeoutSeconds")),
                () -> assertEquals(2, yamlConfig.get("AIConcurrency")),
                () -> assertEquals(8, yamlConfig.get("AIMaxConcurrency")),
                () -> assertEquals(2000000, yamlConfig.get("AIMaxInFlightChars")),
                () -> assertEquals(800000, yamlConfig.get("AIMaxTotalChars")),
                () -> assertEquals(200000, yamlConfig.get("AIMaxRequestChars")),
                () -> assertEquals(600000, yamlConfig.get("AIMaxResponseChars")),
                () -> assertEquals(50, yamlConfig.get("AIMaxItemsPerMessage")),
                () -> assertEquals(true, yamlConfig.get("AIAnalyzeOncePerMessage")),
                () -> assertEquals(true, yamlConfig.get("AISendFullRequest")),
                () -> assertEquals(true, yamlConfig.get("AISendFullResponse")),
                () -> assertEquals(true, yamlConfig.get("AISkipBinary")),
                () -> assertEquals(true, yamlConfig.get("AISkipStaticResources")),
                () -> assertEquals(10000, yamlConfig.get("AIMaxQueueSize")),
                () -> assertEquals(false, yamlConfig.get("AISaveFullPrompt")),
                () -> assertFalse(aiConfig.isEnabled()),
                () -> assertFalse(aiConfig.isUseBurpProxy()),
                () -> assertEquals("openai-compatible", aiConfig.getProviderType()),
                () -> assertEquals("", aiConfig.getBaseUrl()),
                () -> assertEquals("", aiConfig.getModel()),
                () -> assertEquals("", aiConfig.getApiKey()),
                () -> assertEquals(180, aiConfig.getRequestTimeoutSeconds()),
                () -> assertEquals(2, aiConfig.getConcurrency()),
                () -> assertEquals(8, aiConfig.getMaxConcurrency()),
                () -> assertEquals(2000000, aiConfig.getMaxInFlightChars()),
                () -> assertEquals(800000, aiConfig.getMaxTotalChars()),
                () -> assertEquals(200000, aiConfig.getMaxRequestChars()),
                () -> assertEquals(600000, aiConfig.getMaxResponseChars()),
                () -> assertEquals(50, aiConfig.getMaxItemsPerMessage()),
                () -> assertTrue(aiConfig.isAnalyzeOncePerMessage()),
                () -> assertTrue(aiConfig.isSendFullRequest()),
                () -> assertTrue(aiConfig.isSendFullResponse()),
                () -> assertTrue(aiConfig.isSkipBinary()),
                () -> assertTrue(aiConfig.isSkipStaticResources()),
                () -> assertEquals(10000, aiConfig.getMaxQueueSize()),
                () -> assertFalse(aiConfig.isSaveFullPrompt())
        );

        assertDefaultWhitelist(yamlConfig, aiConfig);
    }

    @Test
    void envKeyReferencePersistsWithoutExpansion() throws Exception {
        Path home = tempDirectory.resolve("env-home");
        ConfigLoader configLoader = createConfigLoader(home);

        configLoader.setAIApiKey("literal-secret-value");
        assertEquals("literal-secret-value", readConfigYaml(home).get("AIApiKey"));

        configLoader.setAIApiKey("env:HAE_AI_API_KEY");
        Map<String, Object> yamlConfig = readConfigYaml(home);
        AiConfig aiConfig = configLoader.getAiConfig();

        assertAll(
                () -> assertEquals("env:HAE_AI_API_KEY", yamlConfig.get("AIApiKey")),
                () -> assertEquals("env:HAE_AI_API_KEY", aiConfig.getApiKey()),
                () -> assertTrue(aiConfig.isApiKeyEnvironmentReference()),
                () -> assertEquals("HAE_AI_API_KEY", aiConfig.getApiKeyEnvironmentVariableName())
        );
    }

    @Test
    void invalidBaseUrlPersistsAndFailsAsPermanentConfigIssue() throws Exception {
        Path home = tempDirectory.resolve("invalid-base-url-home");
        ConfigLoader configLoader = createConfigLoader(home);
        configLoader.setAIBaseUrl("file:///tmp/not-http");
        configLoader.setAIModel("gpt-test");
        configLoader.setAIApiKey("env:HAE_AI_API_KEY");

        AiConfig aiConfig = configLoader.getAiConfig();
        AiClientException exception = assertThrows(
                AiClientException.class,
                () -> OpenAiCompatibleAiClient.fromConfig(aiConfig, name -> "fake-env-value")
        );
        Map<String, Object> yamlConfig = readConfigYaml(home);

        assertAll(
                () -> assertEquals("file:///tmp/not-http", yamlConfig.get("AIBaseUrl")),
                () -> assertEquals("file:///tmp/not-http", aiConfig.getBaseUrl()),
                () -> assertEquals(AiClientFailureCategory.PERMANENT_CONFIG, exception.getCategory()),
                () -> assertTrue(exception.isPermanent()),
                () -> assertFalse(exception.isRetryable()),
                () -> assertFalse(exception.getMessage().contains("fake-env-value"))
        );
    }

    @Test
    void plaintextApiKeyRequiresWarningAcknowledgementAndMasksMetadata() throws Exception {
        Path home = tempDirectory.resolve("plaintext-key-home");
        ConfigLoader configLoader = createConfigLoader(home);
        String plaintextKey = "sk-test-value-" + "1234567890";
        DataboardAiSettingsModel model = DataboardAiSettingsModel.from(configLoader, AiQueueCounts.zero());
        model.setEnabled(true);
        model.setBaseUrl("https://ai.example.test/v1");
        model.setModel("gpt-test");
        model.setApiKey(plaintextKey);

        DataboardAiSettingsModel.SaveResult blocked = model.saveTo(configLoader, false);
        boolean enabledAfterBlockedSave = configLoader.getAIEnabled();
        DataboardAiSettingsModel.SaveResult saved = model.saveTo(configLoader, true);
        Map<String, Object> yamlConfig = readConfigYaml(home);
        DataboardAiSettingsModel reloaded = DataboardAiSettingsModel.from(configLoader, AiQueueCounts.zero());
        String maskedKey = reloaded.getMaskedApiKey();
        String queueStatusText = reloaded.getQueueStatusText();

        assertAll(
                () -> assertFalse(blocked.isSaved()),
                () -> assertTrue(blocked.getMessage().contains("Cookie")),
                () -> assertTrue(blocked.getMessage().contains("Authorization")),
                () -> assertTrue(blocked.getMessage().contains("个人信息")),
                () -> assertFalse(enabledAfterBlockedSave),
                () -> assertTrue(saved.isSaved()),
                () -> assertTrue(plaintextKey.equals(yamlConfig.get("AIApiKey"))),
                () -> assertEquals("sk****7890", maskedKey),
                () -> assertTrue(saved.getMessage().contains("API key 显示：sk****7890")),
                () -> assertFalse(saved.getMessage().contains(plaintextKey)),
                () -> assertTrue(queueStatusText.contains("API key=sk****7890")),
                () -> assertFalse(queueStatusText.contains(plaintextKey))
        );
    }

    @Test
    void normalizesInvalidAiIntegerValuesToDefaults() throws Exception {
        Path home = tempDirectory.resolve("invalid-integers-home");
        ConfigLoader configLoader = createConfigLoader(home);
        Map<String, Object> yamlConfig = readConfigYaml(home);

        yamlConfig.put("AIRequestTimeoutSeconds", "not-a-number");
        yamlConfig.put("AIConcurrency", 0);
        yamlConfig.put("AIMaxConcurrency", -8);
        yamlConfig.put("AIMaxInFlightChars", "0");
        yamlConfig.put("AIMaxTotalChars", "-1");
        yamlConfig.put("AIMaxRequestChars", "invalid");
        yamlConfig.put("AIMaxResponseChars", 0);
        yamlConfig.put("AIMaxItemsPerMessage", -50);
        yamlConfig.put("AIMaxQueueSize", "NaN");
        writeConfigYaml(home, yamlConfig);

        AiConfig aiConfig = configLoader.getAiConfig();
        AiTriageWorkerConfig workerConfig = AiTriageWorkerConfig.fromAiConfig(aiConfig);

        assertAll(
                () -> assertEquals(180, aiConfig.getRequestTimeoutSeconds()),
                () -> assertEquals(2, aiConfig.getConcurrency()),
                () -> assertEquals(8, aiConfig.getMaxConcurrency()),
                () -> assertEquals(2000000, aiConfig.getMaxInFlightChars()),
                () -> assertEquals(800000, aiConfig.getMaxTotalChars()),
                () -> assertEquals(200000, aiConfig.getMaxRequestChars()),
                () -> assertEquals(600000, aiConfig.getMaxResponseChars()),
                () -> assertEquals(50, aiConfig.getMaxItemsPerMessage()),
                () -> assertEquals(10000, aiConfig.getMaxQueueSize()),
                () -> assertEquals(2, workerConfig.getConcurrency())
        );
    }

    @Test
    void maxConcurrencyNormalizationCapsEffectiveWorkerConcurrency() throws Exception {
        Path home = tempDirectory.resolve("oversized-concurrency-home");
        ConfigLoader configLoader = createConfigLoader(home);
        configLoader.setAIConcurrency(99);
        configLoader.setAIMaxConcurrency(99);

        AiConfig aiConfig = configLoader.getAiConfig();
        AiTriageWorkerConfig workerConfig = AiTriageWorkerConfig.fromAiConfig(aiConfig);
        Map<String, Object> yamlConfig = readConfigYaml(home);

        assertAll(
                () -> assertEquals(99, yamlConfig.get("AIConcurrency")),
                () -> assertEquals(99, yamlConfig.get("AIMaxConcurrency")),
                () -> assertEquals(99, aiConfig.getConcurrency()),
                () -> assertEquals(99, aiConfig.getMaxConcurrency()),
                () -> assertEquals(8, workerConfig.getConcurrency())
        );
    }

    private ConfigLoader createConfigLoader(Path home) throws Exception {
        Files.createDirectories(home.resolve(".config").resolve("HaE"));
        String originalHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", home.toString());
            return new ConfigLoader(montoyaApiProxy(home));
        } finally {
            if (originalHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", originalHome);
            }
        }
    }

    private Map<String, Object> readConfigYaml(Path home) throws Exception {
        Path configPath = home.resolve(".config").resolve("HaE").resolve("Config.yml");
        try (var inputStream = Files.newInputStream(configPath)) {
            return new Yaml().loadAs(inputStream, Map.class);
        }
    }

    private void writeConfigYaml(Path home, Map<String, Object> yamlConfig) throws Exception {
        Path configPath = home.resolve(".config").resolve("HaE").resolve("Config.yml");
        Files.writeString(configPath, new Yaml().dump(yamlConfig));
    }

    private void assertDefaultWhitelist(Map<String, Object> yamlConfig, AiConfig aiConfig) {
        Object yamlWhitelistValue = yamlConfig.get("AIWhitelist");
        assertTrue(yamlWhitelistValue instanceof List<?>);

        List<?> yamlWhitelist = (List<?>) yamlWhitelistValue;
        assertEquals(1, yamlWhitelist.size());
        assertTrue(yamlWhitelist.get(0) instanceof Map<?, ?>);

        Map<?, ?> yamlWhitelistRule = (Map<?, ?>) yamlWhitelist.get(0);
        assertEquals("敏感信息", yamlWhitelistRule.get("group"));
        assertEquals(List.of("JSON Web Token", "JWT", "idCard", "身份证"), yamlWhitelistRule.get("names"));

        List<AiWhitelistRule> modelWhitelist = aiConfig.getWhitelist();
        assertEquals(1, modelWhitelist.size());
        assertEquals("敏感信息", modelWhitelist.get(0).getGroup());
        assertEquals(List.of("JSON Web Token", "JWT", "idCard", "身份证"), modelWhitelist.get(0).getNames());
    }

    private MontoyaApi montoyaApiProxy(Path home) {
        Extension extension = extensionProxy(home);
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "extension" -> extension;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(MontoyaApi.class, handler);
    }

    private Extension extensionProxy(Path home) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "filename" -> home.resolve("HaE.jar").toString();
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(Extension.class, handler);
    }

    private static <T> T proxyFor(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static Object defaultProxyValue(Object proxy, Method method, Object[] args) {
        if (method.getDeclaringClass() == Object.class) {
            return objectMethod(proxy, method, args);
        }

        Class<?> returnType = method.getReturnType();
        if (returnType == Void.TYPE) {
            return null;
        }
        if (returnType == Boolean.TYPE) {
            return false;
        }
        if (returnType == Integer.TYPE) {
            return 0;
        }
        if (returnType == Long.TYPE) {
            return 0L;
        }
        if (returnType == String.class) {
            return "";
        }
        if (returnType.isInterface()) {
            return proxyFor(returnType, AiConfigPersistenceTest::defaultProxyValue);
        }
        return null;
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "test proxy for " + proxy.getClass().getInterfaces()[0].getName();
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> args != null && args.length > 0 && proxy == args[0];
            default -> null;
        };
    }
}
