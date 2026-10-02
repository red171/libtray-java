package io.github.red171.libtray.macos;

import io.github.red171.libtray.internal.NativeLibrary;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class ObjcBindings extends NativeLibrary {
    final MemorySegment mainQueue;
    private final Map<String, MemorySegment> classes = new ConcurrentHashMap<>();
    private final Map<String, MemorySegment> selectors = new ConcurrentHashMap<>();

    private ObjcBindings(Arena arena, Map<String, MethodHandle> handles, MemorySegment mainQueue) {
        super(arena, handles);
        this.mainQueue = mainQueue;
    }

    static ObjcBindings load() {
        Arena arena = Arena.ofShared();
        try {
            List<SymbolLookup> lookups = List.of(
                    SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", arena),
                    SymbolLookup.libraryLookup("/System/Library/Frameworks/AppKit.framework/AppKit", arena),
                    SymbolLookup.libraryLookup("/System/Library/Frameworks/Foundation.framework/Foundation", arena),
                    SymbolLookup.libraryLookup("/usr/lib/libSystem.B.dylib", arena));
            var handles = new HashMap<String, MethodHandle>();
            bind(handles, lookups, "objc_getClass", "objc_getClass",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookups, "sel_registerName", "sel_registerName",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookups, "objc_retain", "objc_retain",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookups, "objc_release", "objc_release",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            bind(handles, lookups, "objc_autoreleasePoolPush", "objc_autoreleasePoolPush",
                    FunctionDescriptor.of(ValueLayout.ADDRESS));
            bind(handles, lookups, "objc_autoreleasePoolPop", "objc_autoreleasePoolPop",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            bind(handles, lookups, "objc_allocateClassPair", "objc_allocateClassPair",
                    FunctionDescriptor.of( ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            bind(handles, lookups, "class_addMethod", "class_addMethod",
                    FunctionDescriptor.of( ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookups, "objc_registerClassPair", "objc_registerClassPair",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            bind(handles, lookups, "class_createInstance", "class_createInstance",
                    FunctionDescriptor.of( ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            bind(handles, lookups, "objc_msgSend_id", "objc_msgSend",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookups, "objc_msgSend_id_id", "objc_msgSend",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookups, "objc_msgSend_id_double", "objc_msgSend",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_DOUBLE));
            bind(handles, lookups, "objc_msgSend_id_ptr_long", "objc_msgSend",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            bind(handles, lookups, "objc_msgSend_id_id_sel_id", "objc_msgSend",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookups, "objc_msgSend_void_long", "objc_msgSend",
                    FunctionDescriptor.ofVoid( ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            bind(handles, lookups, "objc_msgSend_void_id", "objc_msgSend",
                    FunctionDescriptor.ofVoid( ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookups, "objc_msgSend_void_sel", "objc_msgSend",
                    FunctionDescriptor.ofVoid( ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookups, "objc_msgSend_void", "objc_msgSend",
                    FunctionDescriptor.ofVoid( ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookups, "objc_msgSend_long", "objc_msgSend",
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookups, "dispatch_async_f", "dispatch_async_f",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookups, "objc_msgSend_bool", "objc_msgSend",
                    FunctionDescriptor.of(ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookups, "objc_msgSend_void_bool", "objc_msgSend",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE));
            bind(handles, lookups, "objc_msgSend_long_long", "objc_msgSend",
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            MemorySegment queue = lookups.stream().flatMap(lookup -> lookup.find("_dispatch_main_q").stream())
                    .findFirst().orElseThrow(() -> new UnsatisfiedLinkError("_dispatch_main_q"));
            return new ObjcBindings(arena, handles, queue);
        } catch (RuntimeException | Error failure) {
            arena.close();
            throw failure;
        }
    }

    private static void bind(Map<String, MethodHandle> handles, List<SymbolLookup> lookups,
                             String alias, String name, FunctionDescriptor descriptor) {
        MemorySegment symbol = lookups.stream().flatMap(lookup -> lookup.find(name).stream()).findFirst()
                .orElseThrow(() -> new UnsatisfiedLinkError(name));
        handles.put(alias, Linker.nativeLinker().downcallHandle(symbol, descriptor));
    }

    MemorySegment cls(String name) {
        return classes.computeIfAbsent(name, key -> {
            try (Arena temporary = Arena.ofConfined()) {
                MemorySegment result = pointer("objc_getClass", temporary.allocateFrom(key));
                if (result.address() == 0) {
                    throw new IllegalStateException("Objective-C class missing: " + key);
                }
                return result;
            }
        });
    }

    MemorySegment sel(String name) {
        return selectors.computeIfAbsent(name, key -> {
            try (Arena temporary = Arena.ofConfined()) {
                return pointer("sel_registerName", temporary.allocateFrom(key));
            }
        });
    }

    MemorySegment text(String text) {
        try (Arena temporary = Arena.ofConfined()) {
            return pointer("objc_msgSend_id_id", cls("NSString"), sel("stringWithUTF8String:"), temporary.allocateFrom(text));
        }
    }

    MemorySegment data(byte[] bytes) {
        try (Arena temporary = Arena.ofConfined()) {
            MemorySegment buffer = temporary.allocateFrom(ValueLayout.JAVA_BYTE, bytes);
            return pointer("objc_msgSend_id_ptr_long", cls("NSData"), sel("dataWithBytes:length:"), buffer, (long) bytes.length);
        }
    }

    MemorySegment object(MemorySegment receiver, String selector) {
        return pointer("objc_msgSend_id", receiver, sel(selector));
    }

    MemorySegment object(MemorySegment receiver, String selector, MemorySegment argument) {
        return pointer("objc_msgSend_id_id", receiver, sel(selector), argument);
    }

    void setObject(MemorySegment receiver, String selector, MemorySegment argument) {
        call("objc_msgSend_void_id", receiver, sel(selector), argument);
    }

    void setBool(MemorySegment receiver, String selector, boolean value) {
        call("objc_msgSend_void_bool", receiver, sel(selector), (byte) (value ? 1 : 0));
    }

    void release(MemorySegment object) {
        if (object.address() != 0) {
            call("objc_release", object);
        }
    }

    boolean isMainThread() {
        return number("objc_msgSend_bool", cls("NSThread"), sel("isMainThread")) != 0;
    }

    void pool(Runnable action) {
        MemorySegment pool = pointer("objc_autoreleasePoolPush");
        try {
            action.run();
        } finally {
            call("objc_autoreleasePoolPop", pool);
        }
    }
}
