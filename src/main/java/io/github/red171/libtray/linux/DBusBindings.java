package io.github.red171.libtray.linux;

import io.github.red171.libtray.internal.NativeLibrary;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.HashMap;
import java.util.Map;

final class DBusBindings extends NativeLibrary {
    static final int BUS_SESSION = 0;
    static final int NAME_FLAG_DO_NOT_QUEUE = 4;
    static final int REQUEST_NAME_REPLY_PRIMARY_OWNER = 1;
    static final MemoryLayout ERROR_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("name"),
            ValueLayout.ADDRESS.withName("message"),
            ValueLayout.JAVA_INT.withName("flags"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("padding1"));
    static final MemoryLayout ITER_LAYOUT = MemoryLayout.sequenceLayout(80, ValueLayout.JAVA_BYTE)
            .withByteAlignment(8);

    private DBusBindings(Arena arena, Map<String, MethodHandle> handles) {
        super(arena, handles);
    }

    static DBusBindings load() {
        if (ValueLayout.ADDRESS.byteSize() != 8) {
            throw new UnsupportedOperationException("D-Bus bindings require a 64-bit JVM");
        }
        Arena arena = Arena.ofShared();
        try {
            SymbolLookup lookup = SymbolLookup.libraryLookup("libdbus-1.so.3", arena);
            Linker linker = Linker.nativeLinker();
            var handles = new HashMap<String, MethodHandle>();
            bind(handles, lookup, linker, "dbus_threads_init_default",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT));
            bind(handles, lookup, linker, "dbus_message_get_signature",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookup, linker, "dbus_bus_get_private",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            bind(handles, lookup, linker, "dbus_bus_request_name",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            bind(handles, lookup, linker, "dbus_connection_close",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            bind(handles, lookup, linker, "dbus_connection_unref",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            bind(handles, lookup, linker, "dbus_connection_set_exit_on_disconnect",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            bind(handles, lookup, linker, "dbus_connection_read_write",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            bind(handles, lookup, linker, "dbus_connection_pop_message",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookup, linker, "dbus_connection_send",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookup, linker, "dbus_connection_send_with_reply_and_block",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            bind(handles, lookup, linker, "dbus_connection_flush",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            bind(handles, lookup, linker, "dbus_bus_add_match",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookup, linker, "dbus_message_new_method_call",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookup, linker, "dbus_message_new_method_return",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookup, linker, "dbus_message_new_signal",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookup, linker, "dbus_message_new_error",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookup, linker, "dbus_message_unref",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            bind(handles, lookup, linker, "dbus_message_get_type",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            bind(handles, lookup, linker, "dbus_message_get_member",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookup, linker, "dbus_message_get_interface",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookup, linker, "dbus_message_get_path",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookup, linker, "dbus_message_get_destination",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookup, linker, "dbus_message_get_sender",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookup, linker, "dbus_message_get_serial",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            bind(handles, lookup, linker, "dbus_message_get_no_reply",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            bind(handles, lookup, linker, "dbus_message_iter_init",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookup, linker, "dbus_message_iter_init_append",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookup, linker, "dbus_message_iter_append_basic",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            bind(handles, lookup, linker, "dbus_message_iter_append_fixed_array",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            bind(handles, lookup, linker, "dbus_message_iter_open_container",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookup, linker, "dbus_message_iter_close_container",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookup, linker, "dbus_message_iter_recurse",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookup, linker, "dbus_message_iter_next",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            bind(handles, lookup, linker, "dbus_message_iter_get_arg_type",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            bind(handles, lookup, linker, "dbus_message_iter_get_basic",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookup, linker, "dbus_error_init",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            bind(handles, lookup, linker, "dbus_error_is_set",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            bind(handles, lookup, linker, "dbus_error_free",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            return new DBusBindings(arena, handles);
        } catch (RuntimeException | Error failure) {
            arena.close();
            throw failure;
        }
    }

    private static void bind(Map<String, MethodHandle> handles, SymbolLookup lookup,
                             Linker linker, String name, FunctionDescriptor descriptor) {
        var symbol = lookup.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name));
        handles.put(name, linker.downcallHandle(symbol, descriptor));
    }

}
