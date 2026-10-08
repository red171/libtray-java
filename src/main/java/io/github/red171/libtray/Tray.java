package io.github.red171.libtray;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public interface Tray extends AutoCloseable {
    static Tray create(TrayBuilder builder) {
        Objects.requireNonNull(builder, "builder");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("mac") || os.contains("darwin")) {
                return io.github.red171.libtray.macos.AppKitTray.create(builder);
            }
            if (os.contains("windows")) {
                return io.github.red171.libtray.windows.Win32Tray.create(builder);
            }
            if (os.contains("linux") || os.contains("bsd")) {
                return io.github.red171.libtray.linux.SniTray.create(builder);
            }
            return null;
        } catch (RuntimeException | LinkageError failure) {
            System.getLogger("libtray-java").log(System.Logger.Level.DEBUG, "Tray unavailable", failure);
            return null;
        }
    }

    boolean isOpen();

    boolean setTooltip(String text);

    boolean setIcon(byte[] iconBytes);

    boolean setMenu(TrayMenu menu);

    /**
     * Registers a handler that runs on a thread owned by this tray, in firing
     * order. A blocking handler delays later events of this tray only.
     *
     * @return a Runnable that unsubscribes the handler
     */
    Runnable onEvent(Consumer<TrayEvent> handler);

    /**
     * Like {@link #onEvent(Consumer)}, but each event is submitted to
     * {@code executor}, e.g. {@code SwingUtilities::invokeLater} or
     * {@code Platform::runLater}.
     */
    Runnable onEvent(Executor executor, Consumer<TrayEvent> handler);

    @Override
    void close();
}
