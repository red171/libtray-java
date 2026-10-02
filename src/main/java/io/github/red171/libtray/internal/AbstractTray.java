package io.github.red171.libtray.internal;

import io.github.red171.libtray.Tray;
import io.github.red171.libtray.TrayEvent;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public abstract class AbstractTray implements Tray {
    protected final AtomicBoolean open = new AtomicBoolean(true);
    private final CopyOnWriteArrayList<Consumer<TrayEvent>> handlers = new CopyOnWriteArrayList<>();

    @Override
    public final boolean isOpen() {
        return open.get();
    }

    @Override
    public final Runnable onEvent(Consumer<TrayEvent> handler) {
        Objects.requireNonNull(handler, "handler");
        if (!open.get()) {
            return () -> {};
        }
        handlers.add(handler);
        if (!open.get()) {
            handlers.remove(handler);
        }
        return () -> handlers.remove(handler);
    }

    protected final void fire(TrayEvent event) {
        if (!open.get()) {
            return;
        }
        for (Consumer<TrayEvent> handler : handlers) {
            try {
                handler.accept(event);
            } catch (RuntimeException failure) {
                System.getLogger("libtray-java").log(System.Logger.Level.DEBUG, "Tray event handler failed", failure);
            }
        }
    }

    protected final void clearHandlers() {
        handlers.clear();
    }
}
