package hae.component.board;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.logging.Logging;
import burp.api.montoya.ui.UserInterface;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import hae.Config;
import hae.TestFixtures;
import hae.repository.ScopedDataboardRepository;
import hae.storage.SqliteMessageStore;
import hae.utils.ConfigLoader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScopedDataboardDialogTest {
    private static final String MAIN_MESSAGE_TABLE = "message_history";
    private static final String MAIN_MATCH_TABLE = "message_match";
    private static final String SCOPED_SCOPE_TABLE = "scoped_databoard_scope";
    private static final String SCOPED_MESSAGE_TABLE = "scoped_databoard_message";
    private static final String SCOPED_MATCH_TABLE = "scoped_databoard_match";

    @TempDir
    Path tempDirectory;

    @Test
    void oneSelectedRequestResponseCreatesScopedScopeAndLeavesMainTablesUnchanged() throws Exception {
        withTokenRule(() -> {
            StoreContext context = createStoreContext(tempDirectory.resolve("one-message"));
            ScopedDataboardDialog.ScopedAnalysisService service = new ScopedDataboardDialog.ScopedAnalysisService(
                    context.api(),
                    context.configLoader(),
                    context.store()
            );

            ScopedDataboardDialog.ScopedAnalysisResult result = service.analyzeSelectedMessages(
                    List.of(httpRequestResponse("example.test", "/one?token=abc123", 200)),
                    ScopedDataboardDialog.SOURCE_SELECTED_MESSAGES,
                    "one selected message"
            );

            assertNotNull(result.getScopeId());
            try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(context.databasePath()))) {
                assertAll(
                        () -> TestFixtures.assertSqlCount(connection, MAIN_MESSAGE_TABLE, 0),
                        () -> TestFixtures.assertSqlCount(connection, MAIN_MATCH_TABLE, 0),
                        () -> TestFixtures.assertSqlCount(connection, SCOPED_SCOPE_TABLE, 1),
                        () -> TestFixtures.assertSqlCount(connection, SCOPED_MESSAGE_TABLE, 1),
                        () -> TestFixtures.assertSqlCount(connection, SCOPED_MATCH_TABLE, 1),
                        () -> assertEquals("abc123", singleString(connection,
                                "SELECT extracted_value FROM scoped_databoard_match WHERE scope_id = ?",
                                result.getScopeId())),
                        () -> assertEquals(List.of("abc123"), result.getExtractedDataByRule().get("Token")),
                        () -> assertTrue(Config.globalDataMap.isEmpty(), "Scoped non-persistent extraction must not populate globalDataMap")
                );
            }
        });
    }

    @Test
    void scopedValueFilterReturnsOnlyMessageContainingSelectedValue() throws Exception {
        withTokenRule(() -> {
            StoreContext context = createStoreContext(tempDirectory.resolve("multi-filter"));
            ScopedDataboardDialog.ScopedAnalysisService service = new ScopedDataboardDialog.ScopedAnalysisService(
                    context.api(),
                    context.configLoader(),
                    context.store()
            );

            ScopedDataboardDialog.ScopedAnalysisResult result = service.analyzeSelectedMessages(
                    List.of(
                            httpRequestResponse("example.test", "/first?token=abc123", 200),
                            httpRequestResponse("example.test", "/second?token=def456", 200)
                    ),
                    ScopedDataboardDialog.SOURCE_SELECTED_MESSAGES,
                    "two selected messages"
            );

            ScopedDataboardDialog.ScopedMessageTableModel model = new ScopedDataboardDialog.ScopedMessageTableModel(
                    context.api(),
                    context.configLoader(),
                    context.store(),
                    result.getScopeId()
            );
            try {
                model.applyMessageFilter("Token", "abc123");
                List<SqliteMessageStore.MessageMetadata> filteredMetadata = model.loadCurrentMetadataForTest();

                assertAll(
                        () -> assertEquals(List.of("abc123", "def456"), result.getExtractedDataByRule().get("Token")),
                        () -> TestFixtures.assertSqlCount(context.databasePath(), MAIN_MESSAGE_TABLE, 0),
                        () -> TestFixtures.assertSqlCount(context.databasePath(), MAIN_MATCH_TABLE, 0),
                        () -> assertEquals(2, countRows(context.databasePath(), SCOPED_MESSAGE_TABLE)),
                        () -> assertEquals(2, countRows(context.databasePath(), SCOPED_MATCH_TABLE)),
                        () -> assertEquals(1, filteredMetadata.size()),
                        () -> assertTrue(filteredMetadata.get(0).getUrl().contains("abc123")),
                        () -> assertEquals("abc123", singleString(context.databasePath(),
                                "SELECT extracted_value FROM scoped_databoard_match WHERE scope_id = ? AND scoped_message_id = ?",
                                result.getScopeId(),
                                filteredMetadata.get(0).getMessageId()))
                );
            } finally {
                model.shutdown();
                assertTrue(model.getMessageTable().isViewerShutdownForTest());
                assertNoNonDaemonScopedViewerThread();
            }
        });
    }

    @Test
    void closeScopedResourcesCancelsRunningDialogAnalysisWorker() throws Exception {
        withTokenRule(() -> {
            StoreContext context = createStoreContext(tempDirectory.resolve("dialog-close"));
            BlockingScopedRepository repository = new BlockingScopedRepository();
            ScopedDataboardDialog dialog = headlessDialogFor(context, repository);

            try {
                invokeStartAnalysis(dialog);

                assertTrue(repository.awaitScopeCreation(), "analysis worker should enter scoped scope creation");
                assertTrue(dialog.isAnalysisWorkerRunningForTest(), "analysis worker should be running before close");

                dialog.closeScopedResources();

                assertTrue(dialog.isScopedResourcesClosedForTest());
                waitUntil(() -> !dialog.isAnalysisWorkerRunningForTest(), "analysis worker should stop after closeScopedResources");
                waitUntil(repository::wasInterrupted, "blocked repository should observe worker interruption");

                assertAll(
                        () -> assertEquals(0, repository.savedScopedMessages()),
                        () -> assertEquals(0, repository.savedScopedMatches()),
                        () -> assertEquals(1, repository.deletedScopes()),
                        () -> TestFixtures.assertSqlCount(context.databasePath(), MAIN_MESSAGE_TABLE, 0),
                        () -> TestFixtures.assertSqlCount(context.databasePath(), MAIN_MATCH_TABLE, 0)
                );
            } finally {
                repository.release();
                dialog.closeScopedResources();
            }
        });
    }

    @Test
    void closeScopedResourcesDeletesCompletedScopeWithoutTouchingMainHistory() throws Exception {
        withTokenRule(() -> {
            StoreContext context = createStoreContext(tempDirectory.resolve("dialog-close-cleanup"));
            context.store().saveMessage(
                    "main-message-1",
                    httpRequestResponse("main.example.test", "/main?token=main123", 200),
                    "https://main.example.test/main?token=main123",
                    "GET",
                    "200",
                    "42",
                    "main comment",
                    "red",
                    "main-content-hash",
                    Map.of("MainRule", List.of("main123"))
            );

            ScopedDataboardDialog.ScopedAnalysisService service = new ScopedDataboardDialog.ScopedAnalysisService(
                    context.api(),
                    context.configLoader(),
                    context.store()
            );
            ScopedDataboardDialog.ScopedAnalysisResult result = service.analyzeSelectedMessages(
                    List.of(httpRequestResponse("example.test", "/close?token=abc123", 200)),
                    ScopedDataboardDialog.SOURCE_SELECTED_MESSAGES,
                    "completed close cleanup"
            );
            assertNotNull(result.getScopeId());
            assertEquals(1, countRows(context.databasePath(), SCOPED_SCOPE_TABLE));
            assertEquals(1, countRows(context.databasePath(), SCOPED_MESSAGE_TABLE));
            assertEquals(1, countRows(context.databasePath(), SCOPED_MATCH_TABLE));

            ScopedDataboardDialog dialog = headlessDialogFor(context, context.store());
            setField(dialog, "scopeId", result.getScopeId());
            setField(dialog, "scopedScopeDeleted", false);

            dialog.closeScopedResources();
            dialog.closeScopedResources();

            assertAll(
                    () -> assertTrue(dialog.isScopedResourcesClosedForTest()),
                    () -> TestFixtures.assertSqlCount(context.databasePath(), MAIN_MESSAGE_TABLE, 1),
                    () -> TestFixtures.assertSqlCount(context.databasePath(), MAIN_MATCH_TABLE, 1),
                    () -> assertEquals(0, countRows(context.databasePath(), SCOPED_SCOPE_TABLE)),
                    () -> assertEquals(0, countRows(context.databasePath(), SCOPED_MESSAGE_TABLE)),
                    () -> assertEquals(0, countRows(context.databasePath(), SCOPED_MATCH_TABLE))
            );
        });
    }

    private StoreContext createStoreContext(Path home) throws Exception {
        Files.createDirectories(home.resolve(".config").resolve("HaE"));
        String originalHome = System.getProperty("user.home");
        MontoyaApi api = montoyaApiProxy();
        try {
            System.setProperty("user.home", home.toString());
            ConfigLoader configLoader = new ConfigLoader(api);
            installTokenRule();
            configLoader.setDynamicHeader("");
            configLoader.setLimitSize("0");
            SqliteMessageStore store = new SqliteMessageStore(api, configLoader);
            return new StoreContext(api, configLoader, store, haeDatabasePath(home));
        } finally {
            if (originalHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", originalHome);
            }
        }
    }

    private static Path haeDatabasePath(Path home) {
        return home.resolve(".config").resolve("HaE").resolve("History.db");
    }

    private static ScopedDataboardDialog headlessDialogFor(StoreContext context, ScopedDataboardRepository repository) throws Exception {
        ScopedDataboardDialog dialog = allocateDialogInstance();
        setField(dialog, "api", context.api());
        setField(dialog, "configLoader", context.configLoader());
        setField(dialog, "scopedRepository", repository);
        setField(dialog, "analysisService", new ScopedDataboardDialog.ScopedAnalysisService(context.api(), context.configLoader(), repository));
        setField(dialog, "selectedMessages", List.of(httpRequestResponse("example.test", "/cancel?token=abc123", 200)));
        setField(dialog, "label", "cancel cleanup");
        setField(dialog, "progressBar", new JProgressBar());
        setField(dialog, "statusLabel", new JLabel());
        setField(dialog, "scopedResourcesClosed", false);
        return dialog;
    }

    private static ScopedDataboardDialog allocateDialogInstance() throws Exception {
        Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        Method allocateInstance = unsafe.getClass().getMethod("allocateInstance", Class.class);
        return ScopedDataboardDialog.class.cast(allocateInstance.invoke(unsafe, ScopedDataboardDialog.class));
    }

    private static void setField(ScopedDataboardDialog dialog, String fieldName, Object value) throws Exception {
        Field field = ScopedDataboardDialog.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(dialog, value);
    }

    private static void invokeStartAnalysis(ScopedDataboardDialog dialog) throws Exception {
        Method startAnalysis = ScopedDataboardDialog.class.getDeclaredMethod("startAnalysis");
        startAnalysis.setAccessible(true);
        startAnalysis.invoke(dialog);
    }

    private static void waitUntil(BooleanSupplier condition, String message) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        assertTrue(condition.getAsBoolean(), message);
    }

    private static void assertNoNonDaemonScopedViewerThread() {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            boolean scopedViewer = thread.getName().startsWith("hae-scoped-databoard-viewer");
            assertTrue(!scopedViewer || thread.isDaemon(), () -> "Non-daemon scoped viewer thread remains: " + thread.getName());
        }
    }

    private static void withTokenRule(ThrowingRunnable runnable) throws Exception {
        Map<String, Object[][]> originalRules = Config.globalRules;
        ConcurrentHashMap<String, Map<String, List<String>>> originalDataMap = Config.globalDataMap;
        Config.globalRules = new ConcurrentHashMap<>();
        installTokenRule();
        Config.globalDataMap = new ConcurrentHashMap<>();
        try {
            runnable.run();
        } finally {
            Config.globalRules = originalRules;
            Config.globalDataMap = originalDataMap;
        }
    }

    private static void installTokenRule() {
        Config.globalRules.put("ScopedTest", new Object[][]{
                {true, "Token", "token=([A-Za-z0-9]+)", "", "{0}", "yellow", "any", "nfa", false}
        });
    }

    private static HttpRequestResponse httpRequestResponse(String host, String path, int statusCode) {
        HttpService service = httpServiceProxy(host);
        HttpRequest request = httpRequestProxy(service, host, path);
        HttpResponse response = httpResponseProxy(statusCode);
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "request" -> request;
            case "response" -> response;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpRequestResponse.class, handler);
    }

    private static HttpService httpServiceProxy(String host) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "host" -> host;
            case "port" -> 443;
            case "secure" -> true;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpService.class, handler);
    }

    private static HttpRequest httpRequestProxy(HttpService service, String host, String path) {
        String safePath = path == null || path.isBlank() ? "/" : path;
        byte[] requestBytes = ("GET " + safePath + " HTTP/1.1\r\nHost: " + host + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
        ByteArray byteArray = byteArrayProxy(requestBytes);
        ByteArray emptyBody = byteArrayProxy(new byte[0]);
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "httpService" -> service;
            case "url" -> "https://" + host + safePath;
            case "path" -> safePath;
            case "method" -> "GET";
            case "headers" -> List.<HttpHeader>of();
            case "body" -> emptyBody;
            case "toByteArray" -> byteArray;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpRequest.class, handler);
    }

    private static HttpResponse httpResponseProxy(int statusCode) {
        byte[] responseBytes = ("HTTP/1.1 " + statusCode + " OK\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
        ByteArray byteArray = byteArrayProxy(responseBytes);
        ByteArray emptyBody = byteArrayProxy(new byte[0]);
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "statusCode" -> primitiveNumber(method, statusCode);
            case "headers" -> List.<HttpHeader>of();
            case "body" -> emptyBody;
            case "toByteArray" -> byteArray;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpResponse.class, handler);
    }

    private static ByteArray byteArrayProxy(byte[] bytes) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getBytes" -> bytes;
            case "length" -> bytes.length;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(ByteArray.class, handler);
    }

    private static MontoyaApi montoyaApiProxy() {
        UserInterface userInterface = userInterfaceProxy();
        Logging logging = loggingProxy();
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "userInterface" -> userInterface;
            case "logging" -> logging;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(MontoyaApi.class, handler);
    }

    private static UserInterface userInterfaceProxy() {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "createHttpRequestEditor" -> httpRequestEditorProxy();
            case "createHttpResponseEditor" -> httpResponseEditorProxy();
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(UserInterface.class, handler);
    }

    private static HttpRequestEditor httpRequestEditorProxy() {
        JPanel panel = new JPanel();
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "uiComponent" -> panel;
            case "setRequest" -> null;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpRequestEditor.class, handler);
    }

    private static HttpResponseEditor httpResponseEditorProxy() {
        JPanel panel = new JPanel();
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "uiComponent" -> panel;
            case "setResponse" -> null;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpResponseEditor.class, handler);
    }

    private static Logging loggingProxy() {
        InvocationHandler handler = (proxy, method, args) -> null;
        return proxyFor(Logging.class, handler);
    }

    private static long countRows(Path databasePath, String tableName) throws Exception {
        try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(databasePath))) {
            return TestFixtures.countRows(connection, tableName);
        }
    }

    private static String singleString(Path databasePath, String sql, String firstParameter, String secondParameter) throws Exception {
        try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(databasePath))) {
            return singleString(connection, sql, firstParameter, secondParameter);
        }
    }

    private static String singleString(Connection connection, String sql, String firstParameter) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, firstParameter);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private static String singleString(Connection connection, String sql, String firstParameter, String secondParameter) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, firstParameter);
            statement.setString(2, secondParameter);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private static Object primitiveNumber(Method method, int value) {
        Class<?> returnType = method.getReturnType();
        if (returnType == Short.TYPE || returnType == Short.class) {
            return (short) value;
        }
        if (returnType == Long.TYPE || returnType == Long.class) {
            return (long) value;
        }
        return value;
    }

    private static <T> T proxyFor(Class<T> type) {
        return proxyFor(type, new DefaultProxyHandler());
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
        if (returnType == Short.TYPE) {
            return (short) 0;
        }
        if (returnType == Long.TYPE) {
            return 0L;
        }
        if (returnType == String.class) {
            return "";
        }
        if (returnType == List.class) {
            return Collections.emptyList();
        }
        if (returnType.isInterface()) {
            return proxyFor(returnType);
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

    private record StoreContext(MontoyaApi api, ConfigLoader configLoader, SqliteMessageStore store, Path databasePath) {
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static class DefaultProxyHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return defaultProxyValue(proxy, method, args);
        }
    }

    private static class BlockingScopedRepository implements ScopedDataboardRepository {
        private final CountDownLatch scopeCreationEntered = new CountDownLatch(1);
        private final CountDownLatch releaseScopeCreation = new CountDownLatch(1);
        private final AtomicBoolean interrupted = new AtomicBoolean(false);
        private final AtomicInteger savedScopedMessages = new AtomicInteger(0);
        private final AtomicInteger savedScopedMatches = new AtomicInteger(0);
        private final AtomicInteger deletedScopes = new AtomicInteger(0);

        @Override
        public String createScopedDataboardScope(String source, String label) {
            scopeCreationEntered.countDown();
            try {
                releaseScopeCreation.await();
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return "blocked-scope";
        }

        boolean awaitScopeCreation() throws InterruptedException {
            return scopeCreationEntered.await(5, TimeUnit.SECONDS);
        }

        void release() {
            releaseScopeCreation.countDown();
        }

        boolean wasInterrupted() {
            return interrupted.get();
        }

        int savedScopedMessages() {
            return savedScopedMessages.get();
        }

        int savedScopedMatches() {
            return savedScopedMatches.get();
        }

        int deletedScopes() {
            return deletedScopes.get();
        }

        @Override
        public void saveScopedMessage(String scopeId, String scopedMessageId, HttpRequestResponse messageInfo,
                                      String url, String host, String method, String status, String length,
                                      String comment, String color, String contentHash) {
            savedScopedMessages.incrementAndGet();
        }

        @Override
        public void saveScopedMatches(String scopeId, String scopedMessageId, Map<String, List<String>> extractedDataByRule) {
            savedScopedMatches.incrementAndGet();
        }

        @Override
        public int countScopedMessageMetadata(String scopeId, String hostPattern, String commentKeyword,
                                              String messageTableName, String messageFilterValue) {
            return 0;
        }

        @Override
        public List<SqliteMessageStore.MessageMetadata> loadScopedMessageMetadataPage(String scopeId, String hostPattern,
                                                                                      String commentKeyword,
                                                                                      String messageTableName,
                                                                                      String messageFilterValue,
                                                                                      int limit, int offset) {
            return Collections.emptyList();
        }

        @Override
        public List<SqliteMessageStore.MessageMetadata> loadScopedMessageMetadataByFilter(String scopeId,
                                                                                          String hostPattern,
                                                                                          String commentKeyword,
                                                                                          String messageTableName,
                                                                                          String messageFilterValue) {
            return Collections.emptyList();
        }

        @Override
        public Map<String, List<String>> loadScopedExtractedData(String scopeId, String hostPattern,
                                                                 String ruleName, String extractedValue) {
            return Collections.emptyMap();
        }

        @Override
        public List<String> loadScopedMatchedHosts(String scopeId) {
            return Collections.emptyList();
        }

        @Override
        public HttpRequestResponse loadScopedMessage(String scopeId, String scopedMessageId) {
            return null;
        }

        @Override
        public int deleteScopedDataboardScope(String scopeId) {
            return deletedScopes.incrementAndGet();
        }

        @Override
        public int deleteAllScopedDataboardScopes() {
            return 0;
        }
    }
}
