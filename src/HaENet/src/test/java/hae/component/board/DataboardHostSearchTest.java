package hae.component.board;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.logging.Logging;
import burp.api.montoya.ui.UserInterface;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import hae.storage.SqliteMessageStore;
import hae.component.board.message.MessageTableModel;
import hae.utils.ConfigLoader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.awt.GridBagConstraints;
import java.util.concurrent.locks.LockSupport;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataboardHostSearchTest {
    @TempDir
    Path tempDirectory;

    @Test
    void firstStarInputLoadsAllDataWithoutCachedHostSelection() throws Exception {
        BoardContext context = createBoardContext(tempDirectory.resolve("first-star"));
        MessageTableModel model = newTestModel(context);
        try {
            context.store().saveMessage(
                    "message-1",
                    httpRequestResponse("www.baidu.com", "/?token=abc123", 200),
                    "https://www.baidu.com/?token=abc123",
                    "GET",
                    "200",
                    "42",
                    "All URL (1), Token (1)",
                    "yellow",
                    "hash-message-1",
                    Map.of("Token", List.of("abc123"))
            );

            AtomicReference<Databoard> databoardReference = new AtomicReference<>();
            onEdt(() -> databoardReference.set(new Databoard(context.api(), context.configLoader(), model)));
            Databoard databoard = databoardReference.get();

            onEdt(() -> {
                databoard.getHostTextFieldForTest().setText("*");
                databoard.loadHostFromInputForTest();
            });

            waitUntil(() -> databoard.getDataTabCountForTest() > 0, "first * search should create data tabs");
            waitUntil(() -> databoard.getMessageTableRowCountForTest() == 1, "first * search should load message table rows");

            assertAll(
                    () -> assertTrue(databoard.isSplitPaneVisibleForTest()),
                    () -> assertEquals(1, databoard.getDataTabCountForTest()),
                    () -> assertEquals(1, databoard.getMessageTableRowCountForTest())
            );
        } finally {
            model.clearAllDataOnShutdown();
        }
    }

    @Test
    void aiSettingsEntryIsVisibleBeforeLoadingHostData() throws Exception {
        BoardContext context = createBoardContext(tempDirectory.resolve("ai-entry-visible"));
        MessageTableModel model = newTestModel(context);
        try {
            AtomicReference<Databoard> databoardReference = new AtomicReference<>();
            onEdt(() -> databoardReference.set(new Databoard(context.api(), context.configLoader(), model)));
            Databoard databoard = databoardReference.get();

            assertAll(
                    () -> assertTrue(databoard.isAiSettingsToolbarVisibleForTest()),
                    () -> assertEquals("AI：未启用", databoard.getAiStatusTextForTest()),
                    () -> assertEquals(0, databoard.getDataTabCountForTest())
            );
        } finally {
            model.clearAllDataOnShutdown();
        }
    }

    @Test
    void mainSplitPaneExpandsAcrossRightSideOfDataboard() throws Exception {
        BoardContext context = createBoardContext(tempDirectory.resolve("main-split-layout"));
        MessageTableModel model = newTestModel(context);
        try {
            AtomicReference<Databoard> databoardReference = new AtomicReference<>();
            onEdt(() -> databoardReference.set(new Databoard(context.api(), context.configLoader(), model)));
            Databoard databoard = databoardReference.get();
            GridBagConstraints constraints = databoard.getMainSplitPaneConstraintsForTest();

            assertAll(
                    () -> assertEquals(1, constraints.gridx),
                    () -> assertEquals(1, constraints.gridy),
                    () -> assertEquals(4, constraints.gridwidth),
                    () -> assertEquals(GridBagConstraints.BOTH, constraints.fill),
                    () -> assertEquals(1.0, constraints.weightx),
                    () -> assertEquals(1.0, constraints.weighty)
            );
        } finally {
            model.clearAllDataOnShutdown();
        }
    }

    private static MessageTableModel newTestModel(BoardContext context) {
        SqliteMessageStore store = context.store();
        return new MessageTableModel(
                context.api(),
                context.configLoader(),
                store,
                store,
                store,
                store
        );
    }

    private static BoardContext createBoardContext(Path home) throws Exception {
        Files.createDirectories(home.resolve(".config").resolve("HaE"));
        String originalHome = System.getProperty("user.home");
        MontoyaApi api = montoyaApiProxy();
        try {
            System.setProperty("user.home", home.toString());
            ConfigLoader configLoader = new ConfigLoader(api);
            SqliteMessageStore store = new SqliteMessageStore(api, configLoader);
            return new BoardContext(api, configLoader, store);
        } finally {
            if (originalHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", originalHome);
            }
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
        Logging logging = loggingProxy();
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "userInterface" -> userInterface;
            case "logging" -> logging;
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

    private static void onEdt(Runnable runnable) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
        } else {
            SwingUtilities.invokeAndWait(runnable);
        }
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

    private record BoardContext(MontoyaApi api, ConfigLoader configLoader, SqliteMessageStore store) {
    }

    private static class DefaultProxyHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return defaultProxyValue(proxy, method, args);
        }
    }
}
