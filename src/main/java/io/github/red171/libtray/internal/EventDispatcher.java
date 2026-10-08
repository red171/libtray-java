package io.github.red171.libtray.internal;

import io.github.red171.libtray.TrayEvent;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Hands events from a backend to listeners on a thread owned by the tray.
 * {@link #fire} only enqueues, so a blocking listener delays later events of
 * its tray and nothing else. The thread starts with the first event and only
 * {@link #close} ends it.
 */
final class EventDispatcher {
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final Object STOP = new Object();

    private static final class Registration {
        final Consumer<TrayEvent> listener;
        final Executor executor;
        final AtomicBoolean active = new AtomicBoolean(true);

        Registration(Consumer<TrayEvent> listener, Executor executor) {
            this.listener = listener;
            this.executor = executor;
        }
    }

    private final System.Logger log = System.getLogger("libtray-java");
    private final String threadName;
    private final CopyOnWriteArrayList<Registration> registrations = new CopyOnWriteArrayList<>();
    private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean open = new AtomicBoolean(true);
    private Thread thread;

    EventDispatcher(String threadNamePrefix) {
        threadName = threadNamePrefix + "-" + COUNTER.incrementAndGet();
    }

    Runnable subscribe(Consumer<TrayEvent> listener, Executor executor) {
        var registration = new Registration(listener, executor);
        registrations.add(registration);
        return () -> {
            if (registration.active.compareAndSet(true, false)) {
                registrations.remove(registration);
            }
        };
    }

    void fire(TrayEvent event) {
        if (!open.get()) {
            return;
        }
        ensureStarted();
        queue.add(event);
    }

    /** Stops delivery and drops queued events. Does not wait for a running listener. */
    void close() {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        registrations.clear();
        queue.clear();
        queue.add(STOP);
    }

    private synchronized void ensureStarted() {
        if (thread != null) {
            return;
        }
        thread = new Thread(this::run, threadName);
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        while (true) {
            Object next;
            try {
                next = queue.take();
            } catch (InterruptedException interrupted) {
                if (open.get()) {
                    continue;
                }
                return;
            }
            if (next == STOP) {
                return;
            }
            deliver((TrayEvent) next);
            // A listener that restored an interrupt would make the next take() throw.
            Thread.interrupted();
        }
    }

    private void deliver(TrayEvent event) {
        for (Registration registration : registrations) {
            if (!open.get()) {
                return;
            }
            if (registration.executor == null) {
                invoke(registration, event);
                continue;
            }
            try {
                registration.executor.execute(() -> {
                    if (open.get()) {
                        invoke(registration, event);
                    }
                });
            } catch (RejectedExecutionException rejected) {
                log.log(System.Logger.Level.WARNING, "Executor rejected a tray event, dropping " + event, rejected);
            } catch (Throwable failure) {
                log.log(System.Logger.Level.WARNING, "Executor threw on a tray event, dropping " + event, failure);
            }
        }
    }

    private void invoke(Registration registration, TrayEvent event) {
        if (!registration.active.get()) {
            return;
        }
        try {
            registration.listener.accept(event);
        } catch (VirtualMachineError failure) {
            log.log(System.Logger.Level.ERROR, "Tray event handler hit " + failure.getClass().getSimpleName(), failure);
        } catch (Throwable failure) {
            log.log(System.Logger.Level.DEBUG, "Tray event handler failed", failure);
        }
    }
}
