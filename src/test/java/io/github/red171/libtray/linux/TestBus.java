package io.github.red171.libtray.linux;

import io.github.red171.libtray.internal.NativeLibrary;
import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;

final class TestBus implements AutoCloseable {
    final DBusBindings bindings = DBusBindings.load();
    final DBusCodec codec = new DBusCodec(bindings);
    final MemorySegment connection;

    TestBus() {
        bindings.number("dbus_threads_init_default");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment error = arena.allocate(DBusBindings.ERROR_LAYOUT);
            bindings.call("dbus_error_init", error);
            try {
                connection = bindings.pointer("dbus_bus_get_private", DBusBindings.BUS_SESSION, error);
                if (connection.address() == 0) {
                    throw new IllegalStateException("No isolated test bus");
                }
                bindings.call("dbus_connection_set_exit_on_disconnect", connection, 0);
            } finally {
                bindings.call("dbus_error_free", error);
            }
        }
    }

    void claim(String name) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment error = arena.allocate(DBusBindings.ERROR_LAYOUT);
            bindings.call("dbus_error_init", error);
            try {
                int result = bindings.number("dbus_bus_request_name", connection, arena.allocateFrom(name), 4, error);
                if (result != 1) {
                    throw new IllegalStateException("Cannot claim test bus name: " + name);
                }
            } finally {
                bindings.call("dbus_error_free", error);
            }
        }
    }

    Response request(String destination, String path, String iface, String method, DBusCodec.Value... values) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment message = bindings.pointer("dbus_message_new_method_call", arena.allocateFrom(destination),
                    arena.allocateFrom(path), arena.allocateFrom(iface), arena.allocateFrom(method));
            MemorySegment error = arena.allocate(DBusBindings.ERROR_LAYOUT);
            bindings.call("dbus_error_init", error);
            try {
                codec.append(message, List.of(values));
                MemorySegment reply = bindings.pointer("dbus_connection_send_with_reply_and_block",
                        connection, message, 3000, error);
                if (reply.address() == 0) {
                    long offset = DBusBindings.ERROR_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("name"));
                    return new Response(NativeLibrary.string(error.get(ValueLayout.ADDRESS, offset)), "", List.of());
                }
                try {
                    String signature = NativeLibrary.string(bindings.pointer("dbus_message_get_signature", reply));
                    return new Response(null, signature, codec.read(reply));
                } finally {
                    bindings.call("dbus_message_unref", reply);
                }
            } finally {
                bindings.call("dbus_message_unref", message);
                bindings.call("dbus_error_free", error);
            }
        }
    }

    @Override
    public void close() {
        bindings.call("dbus_connection_close", connection);
        bindings.call("dbus_connection_unref", connection);
        bindings.close();
    }

    record Response(String error, String signature, List<Object> values) {
    }
}
