package io.github.red171.libtray;

import java.util.function.Consumer;
import java.util.Locale;
import java.util.Objects;

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

    Runnable onEvent(Consumer<TrayEvent> handler);

    @Override
    void close();
}
