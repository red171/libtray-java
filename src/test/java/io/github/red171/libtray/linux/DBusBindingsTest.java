package io.github.red171.libtray.linux;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.*;

class DBusBindingsTest {
    @Test
    void nativeStorageHasRequiredSizeAndAlignment() {
        assertEquals(32, DBusBindings.ERROR_LAYOUT.byteSize());
        assertEquals(8, DBusBindings.ERROR_LAYOUT.byteAlignment());
        assertEquals(80, DBusBindings.ITER_LAYOUT.byteSize());
        assertEquals(8, DBusBindings.ITER_LAYOUT.byteAlignment());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    @EnabledIfSystemProperty(named = "libtray.nativeTests", matches = "true")
    void loadsBindingsAndClaimsFlatpakStyleName() throws Throwable {
        try (DBusBindings bindings = DBusBindings.load(); Arena arena = Arena.ofConfined()) {
            assertEquals(1, (int) bindings.handle("dbus_threads_init_default").invokeExact());
            assertThrows(IllegalArgumentException.class, () -> bindings.handle("not_a_symbol"));
            MemorySegment error = arena.allocate(DBusBindings.ERROR_LAYOUT);
            bindings.handle("dbus_error_init").invokeExact(error);
            try {
                MemorySegment connection = (MemorySegment) bindings.handle("dbus_bus_get_private")
                        .invokeExact(DBusBindings.BUS_SESSION, error);
                assertNotEquals(0L, connection.address(), "native-tests require a session bus");
                try {
                    bindings.handle("dbus_connection_set_exit_on_disconnect").invokeExact(connection, 0);
                    MemorySegment name = arena.allocateUtf8String("io.github.red171.libtray.PortTest");
                    int result = (int) bindings.handle("dbus_bus_request_name")
                            .invokeExact(connection, name, DBusBindings.NAME_FLAG_DO_NOT_QUEUE, error);
                    assertEquals(DBusBindings.REQUEST_NAME_REPLY_PRIMARY_OWNER, result);
                    assertEquals(0, (int) bindings.handle("dbus_error_is_set").invokeExact(error));
                } finally {
                    bindings.handle("dbus_connection_close").invokeExact(connection);
                    bindings.handle("dbus_connection_unref").invokeExact(connection);
                }
            } finally {
                bindings.handle("dbus_error_free").invokeExact(error);
            }
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    @EnabledIfSystemProperty(named = "libtray.nativeTests", matches = "true")
    void roundTripsNestedNativeMessageIterators() throws Throwable {
        try (DBusBindings bindings = DBusBindings.load(); Arena arena = Arena.ofConfined()) {
            assertEquals(1, (int) bindings.handle("dbus_threads_init_default").invokeExact());
            MemorySegment message = (MemorySegment) bindings.handle("dbus_message_new_signal")
                    .invokeExact(arena.allocateUtf8String("/PortTest"), arena.allocateUtf8String("io.github.red171.libtray"),
                            arena.allocateUtf8String("PortTest"));
            assertNotEquals(0L, message.address());
            try {
                MemorySegment root = arena.allocate(DBusBindings.ITER_LAYOUT);
                MemorySegment array = arena.allocate(DBusBindings.ITER_LAYOUT);
                MemorySegment struct = arena.allocate(DBusBindings.ITER_LAYOUT);
                bindings.handle("dbus_message_iter_init_append").invokeExact(message, root);
                assertEquals(1, (int) bindings.handle("dbus_message_iter_open_container")
                        .invokeExact(root, (int) 'a', arena.allocateUtf8String("(is)"), array));
                assertEquals(1, (int) bindings.handle("dbus_message_iter_open_container")
                        .invokeExact(array, (int) 'r', MemorySegment.NULL, struct));
                MemorySegment number = arena.allocate(ValueLayout.JAVA_INT, 42);
                MemorySegment label = arena.allocate(ValueLayout.ADDRESS, arena.allocateUtf8String("Quit ä"));
                assertEquals(1, (int) bindings.handle("dbus_message_iter_append_basic")
                        .invokeExact(struct, (int) 'i', number));
                assertEquals(1, (int) bindings.handle("dbus_message_iter_append_basic")
                        .invokeExact(struct, (int) 's', label));
                assertEquals(1, (int) bindings.handle("dbus_message_iter_close_container")
                        .invokeExact(array, struct));
                assertEquals(1, (int) bindings.handle("dbus_message_iter_close_container")
                        .invokeExact(root, array));

                assertEquals(1, (int) bindings.handle("dbus_message_iter_init").invokeExact(message, root));
                assertEquals((int) 'a', (int) bindings.handle("dbus_message_iter_get_arg_type").invokeExact(root));
                bindings.handle("dbus_message_iter_recurse").invokeExact(root, array);
                assertEquals((int) 'r', (int) bindings.handle("dbus_message_iter_get_arg_type").invokeExact(array));
                bindings.handle("dbus_message_iter_recurse").invokeExact(array, struct);
                bindings.handle("dbus_message_iter_get_basic").invokeExact(struct, number);
                assertEquals(42, number.get(ValueLayout.JAVA_INT, 0));
                assertEquals(1, (int) bindings.handle("dbus_message_iter_next").invokeExact(struct));
                bindings.handle("dbus_message_iter_get_basic").invokeExact(struct, label);
                assertEquals("Quit ä", label.get(ValueLayout.ADDRESS, 0).reinterpret(64).getUtf8String(0));
                assertEquals(0, (int) bindings.handle("dbus_message_iter_next").invokeExact(struct));
            } finally {
                bindings.handle("dbus_message_unref").invokeExact(message);
            }
        }
    }
}
