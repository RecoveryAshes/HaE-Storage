package hae.instances.menu;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import hae.component.board.ScopedDataboardDialog;
import hae.repository.ScopedDataboardRepository;
import hae.utils.ConfigLoader;

import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class DataboardContextMenuProvider implements ContextMenuItemsProvider {
    static final String MENU_TEXT = "View in Databoard";

    private final ScopedDataboardLauncher launcher;

    public DataboardContextMenuProvider(MontoyaApi api,
                                        ConfigLoader configLoader,
                                        ScopedDataboardRepository scopedRepository) {
        this(new SwingScopedDataboardLauncher(api, configLoader, scopedRepository));
    }

    DataboardContextMenuProvider(ScopedDataboardLauncher launcher) {
        this.launcher = Objects.requireNonNull(launcher, "launcher");
    }

    @Override
    public List<Component> provideMenuItems(ContextMenuEvent event) {
        List<HttpRequestResponse> selectedMessages = selectedMessagesFrom(event);
        if (selectedMessages.isEmpty()) {
            return Collections.emptyList();
        }

        JMenuItem menuItem = new JMenuItem(MENU_TEXT);
        menuItem.setName(MENU_TEXT);
        menuItem.addActionListener(e -> launcher.openScopedDataboard(selectedMessages));
        return List.of(menuItem);
    }

    private static List<HttpRequestResponse> selectedMessagesFrom(ContextMenuEvent event) {
        if (event == null) {
            return Collections.emptyList();
        }

        return normalizeMessages(event.selectedRequestResponses());
    }

    private static List<HttpRequestResponse> normalizeMessages(List<HttpRequestResponse> selectedMessages) {
        if (selectedMessages == null || selectedMessages.isEmpty()) {
            return Collections.emptyList();
        }

        List<HttpRequestResponse> normalizedMessages = new ArrayList<>();
        for (HttpRequestResponse selectedMessage : selectedMessages) {
            if (selectedMessage != null) {
                normalizedMessages.add(selectedMessage);
            }
        }

        if (normalizedMessages.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(normalizedMessages);
    }

    private static class SwingScopedDataboardLauncher implements ScopedDataboardLauncher {
        private static final String SCOPED_LABEL = "Selected HTTP messages";

        private final MontoyaApi api;
        private final ConfigLoader configLoader;
        private final ScopedDataboardRepository scopedRepository;

        SwingScopedDataboardLauncher(MontoyaApi api,
                                     ConfigLoader configLoader,
                                     ScopedDataboardRepository scopedRepository) {
            this.api = Objects.requireNonNull(api, "api");
            this.configLoader = Objects.requireNonNull(configLoader, "configLoader");
            this.scopedRepository = Objects.requireNonNull(scopedRepository, "scopedRepository");
        }

        @Override
        public void openScopedDataboard(List<HttpRequestResponse> selectedMessages) {
            List<HttpRequestResponse> messages = normalizeMessages(selectedMessages);
            if (messages.isEmpty()) {
                return;
            }

            SwingWorker<Void, Void> launchWorker = new SwingWorker<>() {
                @Override
                protected Void doInBackground() {
                    return null;
                }

                @Override
                protected void done() {
                    openDialogOnEdt(messages);
                }
            };
            launchWorker.execute();
        }

        private void openDialogOnEdt(List<HttpRequestResponse> messages) {
            if (SwingUtilities.isEventDispatchThread()) {
                openDialog(messages);
            } else {
                SwingUtilities.invokeLater(() -> openDialog(messages));
            }
        }

        private void openDialog(List<HttpRequestResponse> messages) {
            try {
                Window owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
                ScopedDataboardDialog dialog = ScopedDataboardDialog.fromSelectedMessages(
                        owner,
                        api,
                        configLoader,
                        scopedRepository,
                        messages,
                        SCOPED_LABEL
                );
                dialog.setVisible(true);
            } catch (Exception e) {
                logLaunchError(e);
            }
        }

        private void logLaunchError(Exception e) {
            try {
                api.logging().logToError("Databoard context menu launch failed: " + e.getMessage());
            } catch (Exception ignored) {
            }
        }
    }
}

@FunctionalInterface
interface ScopedDataboardLauncher {
    void openScopedDataboard(List<HttpRequestResponse> selectedMessages);
}
