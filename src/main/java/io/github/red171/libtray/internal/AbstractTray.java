package io.github.red171.libtray.internal;

import io.github.red171.libtray.Tray;
import io.github.red171.libtray.TrayEvent;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public abstract class AbstractTray implements Tray {
    protected final AtomicBoolean open = new AtomicBoolean(true);
    private final EventDispatcher dispatcher = new EventDispatcher("libtray-events");

    @Override
    public final boolean isOpen() {
        return open.get();
    }

    @Override
    public final Runnable onEvent(Consumer<TrayEvent> handler) {
        return subscribe(null, handler);
    }

    @Override
    public final Runnable onEvent(Executor executor, Consumer<TrayEvent> handler) {
        Objects.requireNonNull(executor, "executor");
        return subscribe(executor, handler);
    }

    private Runnable subscribe(Executor executor, Consumer<TrayEvent> handler) {
        Objects.requireNonNull(handler, "handler");
        if (!open.get()) {
            return () -> {};
        }
        Runnable unsubscribe = dispatcher.subscribe(handler, executor);
        if (!open.get()) {
            unsubscribe.run();
        }
        return unsubscribe;
    }

    protected final void fire(TrayEvent event) {
        if (open.get()) {
            dispatcher.fire(event);
        }
    }

    /** Stops event delivery and drops queued events. */
    protected final void clearHandlers() {
        dispatcher.close();
    }
}
