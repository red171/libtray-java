package io.github.red171.libtray.linux;

import io.github.red171.libtray.internal.NativeLibrary;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class DBusCodec {
    private final DBusBindings bindings;

    DBusCodec(DBusBindings bindings) {
        this.bindings = bindings;
    }

    static Value text(String value) {
        return new Value("s", value);
    }

    static Value integer(int value) {
        return new Value("i", value);
    }

    static Value unsigned(int value) {
        return new Value("u", value);
    }

    static Value bool(boolean value) {
        return new Value("b", value);
    }

    static Value variant(Value value) {
        return new Value("v", value);
    }

    static Value array(String signature, List<Value> values) {
        return new Value("a" + signature, values);
    }

    static Value dict(Map<String, Value> properties) {
        return array("{sv}", properties.entrySet().stream()
                .map(entry -> new Value("{sv}", List.of(text(entry.getKey()), variant(entry.getValue())))).toList());
    }

    void append(MemorySegment message, List<Value> values) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment iterator = arena.allocate(DBusBindings.ITER_LAYOUT);
            bindings.call("dbus_message_iter_init_append", message, iterator);
            for (Value value : values) {
                appendValue(arena, iterator, value);
            }
        }
    }

    private void appendValue(Arena arena, MemorySegment parent, Value value) {
        int type = value.signature().charAt(0);
        if (type == 'a' || type == '(' || type == '{' || type == 'v') {
            MemorySegment child = arena.allocate(DBusBindings.ITER_LAYOUT);
            MemorySegment signature = switch (type) {
                case 'a' -> arena.allocateUtf8String(value.signature().substring(1));
                case 'v' -> arena.allocateUtf8String(((Value) value.value()).signature());
                default -> MemorySegment.NULL;
            };
            int containerType = type == '(' ? 'r' : type == '{' ? 'e' : type;
            check(bindings.number("dbus_message_iter_open_container", parent, containerType, signature, child));
            if (value.value() instanceof byte[] bytes) {
                if (bytes.length > 0) {
                    MemorySegment buffer = arena.allocateArray(ValueLayout.JAVA_BYTE, bytes);
                    MemorySegment pointer = arena.allocate(ValueLayout.ADDRESS, buffer);
                    check(bindings.number("dbus_message_iter_append_fixed_array", child, (int) 'y', pointer, bytes.length));
                }
            } else if (type == 'v') {
                appendValue(arena, child, (Value) value.value());
            } else {
                for (Object element : (List<?>) value.value()) {
                    appendValue(arena, child, (Value) element);
                }
            }
            check(bindings.number("dbus_message_iter_close_container", parent, child));
            return;
        }
        MemorySegment buffer = switch (type) {
            case 's', 'o', 'g' -> arena.allocate(ValueLayout.ADDRESS,
                    arena.allocateUtf8String((String) value.value()));
            case 'b' -> arena.allocate(ValueLayout.JAVA_INT, (boolean) value.value() ? 1 : 0);
            case 'i', 'u' -> arena.allocate(ValueLayout.JAVA_INT, ((Number) value.value()).intValue());
            case 'x', 't' -> arena.allocate(ValueLayout.JAVA_LONG, ((Number) value.value()).longValue());
            case 'n', 'q' -> arena.allocate(ValueLayout.JAVA_SHORT, ((Number) value.value()).shortValue());
            case 'y' -> arena.allocate(ValueLayout.JAVA_BYTE, ((Number) value.value()).byteValue());
            case 'd' -> arena.allocate(ValueLayout.JAVA_DOUBLE, ((Number) value.value()).doubleValue());
            default -> throw new IllegalArgumentException("Unsupported D-Bus signature: " + value.signature());
        };
        check(bindings.number("dbus_message_iter_append_basic", parent, type, buffer));
    }

    List<Object> read(MemorySegment message) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment iterator = arena.allocate(DBusBindings.ITER_LAYOUT);
            if (bindings.number("dbus_message_iter_init", message, iterator) == 0) {
                return List.of();
            }
            return readSequence(arena, iterator, 0);
        }
    }

    private List<Object> readSequence(Arena arena, MemorySegment iterator, int depth) {
        var values = new ArrayList<Object>();
        if (bindings.number("dbus_message_iter_get_arg_type", iterator) == 0) {
            return values;
        }
        do {
            values.add(readValue(arena, iterator, depth));
        } while (bindings.number("dbus_message_iter_next", iterator) != 0);
        return values;
    }

    private Object readValue(Arena arena, MemorySegment iterator, int depth) {
        if (depth > 64) {
            throw new IllegalArgumentException("D-Bus value nesting too deep");
        }
        int type = bindings.number("dbus_message_iter_get_arg_type", iterator);
        if (type == 'a' || type == 'r' || type == 'e' || type == 'v') {
            MemorySegment child = arena.allocate(DBusBindings.ITER_LAYOUT);
            bindings.call("dbus_message_iter_recurse", iterator, child);
            List<Object> values = readSequence(arena, child, depth + 1);
            return type == 'v' ? values.getFirst() : values;
        }
        MemorySegment buffer = arena.allocate(8, 8);
        bindings.call("dbus_message_iter_get_basic", iterator, buffer);
        return switch (type) {
            case 's', 'o', 'g' -> NativeLibrary.string(buffer.get(ValueLayout.ADDRESS, 0));
            case 'b' -> buffer.get(ValueLayout.JAVA_INT, 0) != 0;
            case 'i', 'u' -> buffer.get(ValueLayout.JAVA_INT, 0);
            case 'x', 't' -> buffer.get(ValueLayout.JAVA_LONG, 0);
            case 'n', 'q' -> buffer.get(ValueLayout.JAVA_SHORT, 0);
            case 'y' -> buffer.get(ValueLayout.JAVA_BYTE, 0);
            case 'd' -> buffer.get(ValueLayout.JAVA_DOUBLE, 0);
            default -> throw new IllegalArgumentException("Unsupported D-Bus argument type: " + type);
        };
    }

    private void check(int success) {
        if (success == 0) {
            throw new IllegalStateException("D-Bus message allocation failed");
        }
    }

    record Value(String signature, Object value) {
    }
}
