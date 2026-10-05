package io.github.red171.libtray.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Map;

public class NativeLibrary implements AutoCloseable {
    public final Arena arena;
    private final Map<String, MethodHandle> handles;

    protected NativeLibrary(Arena arena, Map<String, MethodHandle> handles) {
        this.arena = arena;
        this.handles = Map.copyOf(handles);
    }

    public MethodHandle handle(String name) {
        MethodHandle handle = handles.get(name);
        if (handle == null) {
            throw new IllegalArgumentException("Native handle not loaded: " + name);
        }
        return handle;
    }

    public Object call(String name, Object... arguments) {
        try {
            return handle(name).invokeWithArguments(arguments);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Native call failed: " + name, failure);
        }
    }

    public int number(String name, Object... arguments) {
        return ((Number) call(name, arguments)).intValue();
    }

    public long longNumber(String name, Object... arguments) {
        return ((Number) call(name, arguments)).longValue();
    }

    public MemorySegment pointer(String name, Object... arguments) {
        return (MemorySegment) call(name, arguments);
    }

    public static String string(MemorySegment pointer) {
        return pointer.address() == 0 ? "" : pointer.reinterpret(Long.MAX_VALUE).getUtf8String(0);
    }

    public static MemorySegment wideString(Arena arena, String text) {
        MemorySegment result = arena.allocate((text.length() + 1L) * 2, 2);
        for (int index = 0; index < text.length(); index++) {
            result.set(ValueLayout.JAVA_CHAR, index * 2L, text.charAt(index));
        }
        return result;
    }

    @Override
    public void close() {
        arena.close();
    }
}
