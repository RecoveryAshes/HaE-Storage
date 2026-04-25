package hae.component.board.message;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.UserInterface;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import hae.TestFixtures;
import hae.storage.SqliteMessageStore;
import hae.utils.ConfigLoader;
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
import java.util.List;
import java.util.Map;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessageTableModelRepositoryTest {
    private static final String MESSAGE_HISTORY_TABLE = "message_history";
    private static final String MESSAGE_MATCH_TABLE = "message_match";
    private static final String SCOPED_SCOPE_TABLE = "scoped_databoard_scope";
    private static final String SCOPED_MESSAGE_TABLE = "scoped_databoard_message";
    private static final String SCOPED_MATCH_TABLE = "scoped_databoard_match";

    @TempDir
    Path tempDirectory;

    @Test
    void allowedMessageSavesPendingRowAndQueuesOnlyMessageId() throws Exception {
        ModelContext context = createModelContext(tempDirectory.resolve("allowed"));
        context.configLoader().setBlockHost("");
        context.configLoader().setExcludeSuffix("");
        context.configLoader().setExcludeStatus("");
        context.configLoader().setScope("Proxy");

        MessageTableModel model = newTestModel(context);
        try {
            model.add(httpRequestResponse("allowed.example", "/queued", 200), true, null, "Proxy");

            String messageId = singleString(context.databasePath(), "SELECT message_id FROM message_history");
            assertNotNull(messageId);
            assertAll(
                    () -> TestFixtures.assertSqlCount(context.databasePath(), MESSAGE_HISTORY_TABLE, 1),
                    () -> assertEquals("PENDING", singleString(context.databasePath(), "SELECT regex_status FROM message_history")),
                    () -> assertEquals(1, model.queuedRegexMessageIdCount()),
                    () -> assertTrue(model.hasQueuedRegexMessageId(messageId))
            );
        } finally {
            model.clearAllDataOnShutdown();
        }
    }

    @Test
    void wildcardBlockHostStopsMessageBeforeStorage() throws Exception {
        ModelContext context = createModelContext(tempDirectory.resolve("blocked"));
        context.configLoader().setBlockHost("*.example.com");
        context.configLoader().setExcludeSuffix("");
        context.configLoader().setExcludeStatus("");
        context.configLoader().setScope("Proxy");

        MessageTableModel model = newTestModel(context);
        try {
            model.add(httpRequestResponse("api.example.com", "/blocked", 200), true, null, "Proxy");

            assertAll(
                    () -> TestFixtures.assertSqlCount(context.databasePath(), MESSAGE_HISTORY_TABLE, 0),
                    () -> assertEquals(0, model.queuedRegexMessageIdCount())
            );
        } finally {
            model.clearAllDataOnShutdown();
        }
    }

    @Test
    void clearStorageHistoryDeletesMainAndScopedSqliteRows() throws Exception {
        ModelContext context = createModelContext(tempDirectory.resolve("clear-storage"));
        SqliteMessageStore store = context.store();
        MessageTableModel model = newTestModel(context);
        try {
            store.saveMessage(
                    "main-clear-1",
                    httpRequestResponse("clear.example", "/main?token=abc", 200),
                    "https://clear.example/main?token=abc",
                    "GET",
                    "200",
                    "42",
                    "main comment",
                    "yellow",
                    "main-hash-clear-1",
                    Map.of("MainRule", List.of("main-value"))
            );
            String scopeId = store.createScopedDataboardScope("task-10", "clear scoped rows");
            store.saveScopedMessage(
                    scopeId,
                    "scoped-clear-1",
                    httpRequestResponse("scoped.example", "/scoped?token=def", 200),
                    "https://scoped.example/scoped?token=def",
                    "scoped.example",
                    "GET",
                    "200",
                    "42",
                    "scoped comment",
                    "blue",
                    "scoped-hash-clear-1"
            );
            store.saveScopedMatches(scopeId, "scoped-clear-1", Map.of("ScopedRule", List.of("scoped-value")));

            try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(context.databasePath()))) {
                assertAll(
                        () -> TestFixtures.assertSqlCount(connection, MESSAGE_HISTORY_TABLE, 1),
                        () -> TestFixtures.assertSqlCount(connection, MESSAGE_MATCH_TABLE, 1),
                        () -> TestFixtures.assertSqlCount(connection, SCOPED_SCOPE_TABLE, 1),
                        () -> TestFixtures.assertSqlCount(connection, SCOPED_MESSAGE_TABLE, 1),
                        () -> TestFixtures.assertSqlCount(connection, SCOPED_MATCH_TABLE, 1)
                );
            }

            int deletedMainRows = model.clearStorageHistory();

            try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(context.databasePath()))) {
                assertAll(
                        () -> assertEquals(1, deletedMainRows),
                        () -> TestFixtures.assertSqlCount(connection, MESSAGE_HISTORY_TABLE, 0),
                        () -> TestFixtures.assertSqlCount(connection, MESSAGE_MATCH_TABLE, 0),
                        () -> TestFixtures.assertSqlCount(connection, SCOPED_SCOPE_TABLE, 0),
                        () -> TestFixtures.assertSqlCount(connection, SCOPED_MESSAGE_TABLE, 0),
                        () -> TestFixtures.assertSqlCount(connection, SCOPED_MATCH_TABLE, 0),
                        () -> assertEquals(0, model.queuedRegexMessageIdCount())
                );
            }
        } finally {
            model.clearAllDataOnShutdown();
        }
    }

    private static MessageTableModel newTestModel(ModelContext context) {
        SqliteMessageStore store = context.store();
        return new MessageTableModel(
                context.api(),
                context.configLoader(),
                store,
                store,
                store,
                store,
                false
        );
    }

    private static ModelContext createModelContext(Path home) throws Exception {
        Files.createDirectories(home.resolve(".config").resolve("HaE"));
        String originalHome = System.getProperty("user.home");
        MontoyaApi api = montoyaApiProxy();
        try {
            System.setProperty("user.home", home.toString());
            ConfigLoader configLoader = new ConfigLoader(api);
            SqliteMessageStore store = new SqliteMessageStore(api, configLoader);
            return new ModelContext(api, configLoader, store, haeDatabasePath(home));
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

    private static String singleString(Path databasePath, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(TestFixtures.sqliteJdbcUrl(databasePath));
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
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
        byte[] requestBytes = ("GET " + safePath + " HTTP/1.1\r\nHost: " + host + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        ByteArray byteArray = byteArrayProxy(requestBytes);
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "httpService" -> service;
            case "url" -> "https://" + host + safePath;
            case "path" -> safePath;
            case "method" -> "GET";
            case "fileExtension" -> "";
            case "toByteArray" -> byteArray;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpRequest.class, handler);
    }

    private static HttpResponse httpResponseProxy(int statusCode) {
        byte[] responseBytes = ("HTTP/1.1 " + statusCode + " OK\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        ByteArray byteArray = byteArrayProxy(responseBytes);
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "statusCode" -> primitiveNumber(method, statusCode);
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
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "userInterface" -> userInterface;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(MontoyaApi.class, handler);
    }

    private static UserInterface userInterfaceProxy() {
        HttpRequestEditor requestEditor = httpRequestEditorProxy();
        HttpResponseEditor responseEditor = httpResponseEditorProxy();
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "createHttpRequestEditor" -> requestEditor;
            case "createHttpResponseEditor" -> responseEditor;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(UserInterface.class, handler);
    }

    private static HttpRequestEditor httpRequestEditorProxy() {
        JPanel panel = new JPanel();
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "uiComponent" -> panel;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpRequestEditor.class, handler);
    }

    private static HttpResponseEditor httpResponseEditorProxy() {
        JPanel panel = new JPanel();
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "uiComponent" -> panel;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(HttpResponseEditor.class, handler);
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

    private record ModelContext(MontoyaApi api, ConfigLoader configLoader, SqliteMessageStore store, Path databasePath) {
    }

    private static class DefaultProxyHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return defaultProxyValue(proxy, method, args);
        }
    }
}
