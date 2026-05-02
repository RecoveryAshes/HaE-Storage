package hae.instances.menu;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import org.junit.jupiter.api.Test;

import javax.swing.JMenuItem;
import java.awt.Component;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataboardContextMenuProviderTest {
    @Test
    void emptySelectedRequestResponsesReturnsNoMenuItems() {
        RecordingLauncher launcher = new RecordingLauncher();
        DataboardContextMenuProvider provider = new DataboardContextMenuProvider(launcher);

        List<Component> menuItems = provider.provideMenuItems(contextMenuEvent(Collections.emptyList()));

        assertAll(
                () -> assertTrue(menuItems.isEmpty(), "empty selected HTTP messages should not show Databoard action"),
                () -> assertEquals(0, launcher.launchCount())
        );
    }

    @Test
    void selectedRequestResponsesReturnsDataboardMenuItemAndDelegatesAction() {
        RecordingLauncher launcher = new RecordingLauncher();
        DataboardContextMenuProvider provider = new DataboardContextMenuProvider(launcher);
        HttpRequestResponse firstMessage = httpRequestResponseProxy();
        HttpRequestResponse secondMessage = httpRequestResponseProxy();
        List<HttpRequestResponse> selectedMessages = List.of(firstMessage, secondMessage);

        List<Component> menuItems = provider.provideMenuItems(contextMenuEvent(selectedMessages));

        assertEquals(1, menuItems.size(), "selected HTTP messages should expose one Databoard action");
        assertTrue(menuItems.get(0) instanceof JMenuItem, "Databoard action should be a Swing menu item");
        JMenuItem menuItem = (JMenuItem) menuItems.get(0);
        menuItem.doClick();

        assertAll(
                () -> assertEquals("View in Databoard", menuItem.getText()),
                () -> assertEquals("View in Databoard", menuItem.getName()),
                () -> assertEquals(1, launcher.launchCount()),
                () -> assertEquals(2, launcher.launchedMessages().size()),
                () -> assertSame(firstMessage, launcher.launchedMessages().get(0)),
                () -> assertSame(secondMessage, launcher.launchedMessages().get(1))
        );
    }

    private static ContextMenuEvent contextMenuEvent(List<HttpRequestResponse> selectedMessages) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "selectedRequestResponses" -> selectedMessages;
            default -> defaultProxyValue(proxy, method, args);
        };
        return proxyFor(ContextMenuEvent.class, handler);
    }

    private static HttpRequestResponse httpRequestResponseProxy() {
        return proxyFor(HttpRequestResponse.class);
    }

    private static <T> T proxyFor(Class<T> type) {
        return proxyFor(type, DataboardContextMenuProviderTest::defaultProxyValue);
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

    private static class RecordingLauncher implements ScopedDataboardLauncher {
        private int launchCount;
        private final List<HttpRequestResponse> launchedMessages = new ArrayList<>();

        @Override
        public void openScopedDataboard(List<HttpRequestResponse> selectedMessages) {
            launchCount++;
            launchedMessages.clear();
            launchedMessages.addAll(selectedMessages);
        }

        int launchCount() {
            return launchCount;
        }

        List<HttpRequestResponse> launchedMessages() {
            return launchedMessages;
        }
    }
}
