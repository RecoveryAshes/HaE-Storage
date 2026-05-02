package hae;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.BurpSuiteEdition;
import burp.api.montoya.core.Registration;
import burp.api.montoya.core.Version;
import burp.api.montoya.extension.Extension;
import burp.api.montoya.extension.ExtensionUnloadingHandler;
import burp.api.montoya.http.Http;
import burp.api.montoya.logging.Logging;
import burp.api.montoya.persistence.PersistedList;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.proxy.Proxy;
import burp.api.montoya.scanner.Scanner;
import burp.api.montoya.ui.UserInterface;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import java.awt.Component;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HaELifecycleTest {
    @TempDir
    Path tempDirectory;

    @Test
    void repeatedInitializeKeepsSingleContextMenuAndHttpRegistrationAndUnloadDeregistersMenuOnce() throws Exception {
        Path home = tempDirectory.resolve("lifecycle");
        Files.createDirectories(home.resolve(".config").resolve("HaE"));
        String originalHome = System.getProperty("user.home");
        RecordingMontoyaApi recordingApi = new RecordingMontoyaApi(home);
        try {
            System.setProperty("user.home", home.toString());
            HaE extension = new HaE();

            extension.initialize(recordingApi.api());
            extension.initialize(recordingApi.api());
            recordingApi.fireUnload();

            assertAll(
                    () -> assertEquals(1, recordingApi.httpHandlerRegistrations(), "HTTP handler should not be duplicated by repeated initialize"),
                    () -> assertEquals(1, recordingApi.contextMenuRegistrations(), "context menu provider should not be duplicated by repeated initialize"),
                    () -> assertEquals(1, recordingApi.activeContextMenuRegistrations(), "only one context menu registration should ever be active before unload cleanup"),
                    () -> assertEquals(1, recordingApi.contextMenuDeregistrations(), "unload cleanup should deregister context menu once"),
                    () -> assertEquals(0, recordingApi.activeContextMenuRegistrationsAfterUnload(), "context menu registration should be inactive after unload"),
                    () -> assertEquals(1, recordingApi.unloadingHandlerRegistrations(), "unloading handler should not be duplicated by repeated initialize")
            );
        } finally {
            if (originalHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", originalHome);
            }
        }
    }

    private static class RecordingMontoyaApi {
        private final Path home;
        private final AtomicInteger httpHandlerRegistrations = new AtomicInteger();
        private final AtomicInteger contextMenuRegistrations = new AtomicInteger();
        private final AtomicInteger unloadingHandlerRegistrations = new AtomicInteger();
        private final AtomicInteger contextMenuDeregistrations = new AtomicInteger();
        private final Set<CountingRegistration> contextMenuRegistrationSet = new LinkedHashSet<>();
        private ExtensionUnloadingHandler unloadingHandler;

        private RecordingMontoyaApi(Path home) {
            this.home = home;
        }

        private MontoyaApi api() {
            Extension extension = extensionProxy();
            UserInterface userInterface = userInterfaceProxy();
            Http http = httpProxy();
            Scanner scanner = scannerProxy();
            Proxy proxy = burpProxyProxy();
            Persistence persistence = persistenceProxy();
            Logging logging = proxyFor(Logging.class, (proxyInstance, method, args) -> null);
            burp.api.montoya.burpsuite.BurpSuite burpSuite = burpSuiteProxy();

            InvocationHandler handler = (proxyInstance, method, args) -> switch (method.getName()) {
                case "extension" -> extension;
                case "userInterface" -> userInterface;
                case "http" -> http;
                case "scanner" -> scanner;
                case "proxy" -> proxy;
                case "persistence" -> persistence;
                case "logging" -> logging;
                case "burpSuite" -> burpSuite;
                default -> defaultProxyValue(proxyInstance, method, args);
            };
            return proxyFor(MontoyaApi.class, handler);
        }

        private Extension extensionProxy() {
            InvocationHandler handler = (proxyInstance, method, args) -> switch (method.getName()) {
                case "setName" -> null;
                case "filename" -> home.resolve("HaE.jar").toString();
                case "registerUnloadingHandler" -> {
                    unloadingHandlerRegistrations.incrementAndGet();
                    unloadingHandler = (ExtensionUnloadingHandler) args[0];
                    yield new CountingRegistration();
                }
                default -> defaultProxyValue(proxyInstance, method, args);
            };
            return proxyFor(Extension.class, handler);
        }

        private UserInterface userInterfaceProxy() {
            HttpRequestEditor requestEditor = httpRequestEditorProxy();
            HttpResponseEditor responseEditor = httpResponseEditorProxy();
            InvocationHandler handler = (proxyInstance, method, args) -> switch (method.getName()) {
                case "registerSuiteTab" -> new CountingRegistration();
                case "registerContextMenuItemsProvider" -> {
                    contextMenuRegistrations.incrementAndGet();
                    CountingRegistration registration = new CountingRegistration(contextMenuDeregistrations);
                    contextMenuRegistrationSet.add(registration);
                    yield registration;
                }
                case "registerHttpRequestEditorProvider", "registerHttpResponseEditorProvider", "registerWebSocketMessageEditorProvider" -> new CountingRegistration();
                case "createHttpRequestEditor" -> requestEditor;
                case "createHttpResponseEditor" -> responseEditor;
                default -> defaultProxyValue(proxyInstance, method, args);
            };
            return proxyFor(UserInterface.class, handler);
        }

        private Http httpProxy() {
            InvocationHandler handler = (proxyInstance, method, args) -> switch (method.getName()) {
                case "registerHttpHandler" -> {
                    httpHandlerRegistrations.incrementAndGet();
                    yield new CountingRegistration();
                }
                default -> defaultProxyValue(proxyInstance, method, args);
            };
            return proxyFor(Http.class, handler);
        }

        private Scanner scannerProxy() {
            InvocationHandler handler = (proxyInstance, method, args) -> switch (method.getName()) {
                case "registerScanCheck" -> new CountingRegistration();
                default -> defaultProxyValue(proxyInstance, method, args);
            };
            return proxyFor(Scanner.class, handler);
        }

        private Proxy burpProxyProxy() {
            InvocationHandler handler = (proxyInstance, method, args) -> switch (method.getName()) {
                case "registerWebSocketCreationHandler" -> new CountingRegistration();
                default -> defaultProxyValue(proxyInstance, method, args);
            };
            return proxyFor(Proxy.class, handler);
        }

        private Persistence persistenceProxy() {
            PersistedObject extensionData = persistedObjectProxy();
            InvocationHandler handler = (proxyInstance, method, args) -> switch (method.getName()) {
                case "extensionData" -> extensionData;
                default -> defaultProxyValue(proxyInstance, method, args);
            };
            return proxyFor(Persistence.class, handler);
        }

        private PersistedObject persistedObjectProxy() {
            InvocationHandler handler = (proxyInstance, method, args) -> switch (method.getName()) {
                case "getStringList" -> emptyPersistedStringList();
                case "childObjectKeys", "stringKeys", "booleanKeys", "byteKeys", "shortKeys", "integerKeys", "longKeys",
                        "byteArrayKeys", "httpRequestKeys", "httpResponseKeys", "httpRequestResponseKeys", "stringListKeys",
                        "booleanListKeys", "shortListKeys", "integerListKeys", "longListKeys", "byteArrayListKeys",
                        "httpRequestListKeys", "httpResponseListKeys", "httpRequestResponseListKeys" -> Collections.emptySet();
                default -> defaultProxyValue(proxyInstance, method, args);
            };
            return proxyFor(PersistedObject.class, handler);
        }

        private burp.api.montoya.burpsuite.BurpSuite burpSuiteProxy() {
            Version version = versionProxy();
            InvocationHandler handler = (proxyInstance, method, args) -> switch (method.getName()) {
                case "version" -> version;
                default -> defaultProxyValue(proxyInstance, method, args);
            };
            return proxyFor(burp.api.montoya.burpsuite.BurpSuite.class, handler);
        }

        private Version versionProxy() {
            InvocationHandler handler = (proxyInstance, method, args) -> switch (method.getName()) {
                case "edition" -> BurpSuiteEdition.PROFESSIONAL;
                default -> defaultProxyValue(proxyInstance, method, args);
            };
            return proxyFor(Version.class, handler);
        }

        private HttpRequestEditor httpRequestEditorProxy() {
            JPanel panel = new JPanel();
            InvocationHandler handler = (proxyInstance, method, args) -> switch (method.getName()) {
                case "uiComponent" -> panel;
                default -> defaultProxyValue(proxyInstance, method, args);
            };
            return proxyFor(HttpRequestEditor.class, handler);
        }

        private HttpResponseEditor httpResponseEditorProxy() {
            JPanel panel = new JPanel();
            InvocationHandler handler = (proxyInstance, method, args) -> switch (method.getName()) {
                case "uiComponent" -> panel;
                default -> defaultProxyValue(proxyInstance, method, args);
            };
            return proxyFor(HttpResponseEditor.class, handler);
        }

        private void fireUnload() {
            if (unloadingHandler != null) {
                unloadingHandler.extensionUnloaded();
            }
        }

        private int httpHandlerRegistrations() {
            return httpHandlerRegistrations.get();
        }

        private int contextMenuRegistrations() {
            return contextMenuRegistrations.get();
        }

        private int unloadingHandlerRegistrations() {
            return unloadingHandlerRegistrations.get();
        }

        private int contextMenuDeregistrations() {
            return contextMenuDeregistrations.get();
        }

        private long activeContextMenuRegistrations() {
            return contextMenuRegistrationSet.stream().filter(CountingRegistration::wasEverRegistered).count();
        }

        private long activeContextMenuRegistrationsAfterUnload() {
            return contextMenuRegistrationSet.stream().filter(CountingRegistration::isRegistered).count();
        }
    }

    private static class CountingRegistration implements Registration {
        private final AtomicInteger deregistrationCounter;
        private boolean registered = true;
        private boolean everRegistered = true;

        private CountingRegistration() {
            this(null);
        }

        private CountingRegistration(AtomicInteger deregistrationCounter) {
            this.deregistrationCounter = deregistrationCounter;
        }

        @Override
        public boolean isRegistered() {
            return registered;
        }

        @Override
        public void deregister() {
            if (!registered) {
                return;
            }
            registered = false;
            if (deregistrationCounter != null) {
                deregistrationCounter.incrementAndGet();
            }
        }

        private boolean wasEverRegistered() {
            return everRegistered;
        }
    }

    private static <T> T proxyFor(Class<T> type, InvocationHandler handler) {
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    @SuppressWarnings("unchecked")
    private static PersistedList<String> emptyPersistedStringList() {
        List<String> delegate = new ArrayList<>();
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, method, args);
            }
            try {
                Method listMethod = List.class.getMethod(method.getName(), method.getParameterTypes());
                return listMethod.invoke(delegate, args);
            } catch (NoSuchMethodException ignored) {
                return defaultProxyValue(proxy, method, args);
            }
        };
        Class<PersistedList<String>> type = (Class<PersistedList<String>>) (Class<?>) PersistedList.class;
        return proxyFor(type, handler);
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
        if (returnType == Component.class) {
            return new JPanel();
        }
        if (returnType == Set.class) {
            return Collections.emptySet();
        }
        if (returnType.isInterface()) {
            return proxyFor(returnType, HaELifecycleTest::defaultProxyValue);
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
