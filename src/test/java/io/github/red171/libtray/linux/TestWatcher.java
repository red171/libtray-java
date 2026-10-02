package io.github.red171.libtray.linux;

import io.github.red171.libtray.internal.NativeLibrary;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

final class TestWatcher implements AutoCloseable {
    private final TestBus bus = new TestBus();
    private final AtomicBoolean running = new AtomicBoolean(true);
    final BlockingQueue<String> registrations = new LinkedBlockingQueue<>();
    private final Thread thread;

    TestWatcher() {
        bus.claim("org.kde.StatusNotifierWatcher");
        thread = new Thread(this::run, "test-status-notifier-watcher");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        try {
            while (running.get()) {
                bus.bindings.number("dbus_connection_read_write", bus.connection, 20);
                MemorySegment message;
                while ((message = bus.bindings.pointer("dbus_connection_pop_message", bus.connection)).address() != 0) {
                    try {
                        if (bus.bindings.number("dbus_message_get_type", message) != 1) {
                            continue;
                        }
                        String member = NativeLibrary.string(bus.bindings.pointer("dbus_message_get_member", message));
                        if (member.equals("RegisterStatusNotifierItem")) {
                            registrations.add((String) bus.codec.read(message).getFirst());
                            MemorySegment reply = bus.bindings.pointer("dbus_message_new_method_return", message);
                            try {
                                bus.bindings.number("dbus_connection_send", bus.connection, reply, MemorySegment.NULL);
                                bus.bindings.call("dbus_connection_flush", bus.connection);
                            } finally {
                                bus.bindings.call("dbus_message_unref", reply);
                            }
                        }
                    } finally {
                        bus.bindings.call("dbus_message_unref", message);
                    }
                }
            }
        } finally {
            bus.close();
        }
    }

    @Override
    public void close() throws InterruptedException {
        running.set(false);
        thread.join(3000);
        if (thread.isAlive()) {
            throw new IllegalStateException("Test watcher did not stop");
        }
    }
}
