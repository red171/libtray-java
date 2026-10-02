package io.github.red171.libtray.windows;

import io.github.red171.libtray.internal.NativeLibrary;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class Win32Bindings extends NativeLibrary {
    static final MemoryLayout WINDOW_CLASS = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("cbSize"),
        ValueLayout.JAVA_INT.withName("style"),
        ValueLayout.ADDRESS.withName("lpfnWndProc"),
        ValueLayout.JAVA_INT.withName("cbClsExtra"),
        ValueLayout.JAVA_INT.withName("cbWndExtra"),
        ValueLayout.ADDRESS.withName("hInstance"),
        ValueLayout.ADDRESS.withName("hIcon"),
        ValueLayout.ADDRESS.withName("hCursor"),
        ValueLayout.ADDRESS.withName("hbrBackground"),
        ValueLayout.ADDRESS.withName("lpszMenuName"),
        ValueLayout.ADDRESS.withName("lpszClassName"),
        ValueLayout.ADDRESS.withName("hIconSm"));
    static final MemoryLayout MESSAGE = MemoryLayout.structLayout(
        ValueLayout.ADDRESS.withName("hwnd"),
        ValueLayout.JAVA_INT.withName("message"),
        MemoryLayout.paddingLayout(4),
        ValueLayout.JAVA_LONG.withName("wParam"),
        ValueLayout.JAVA_LONG.withName("lParam"),
        ValueLayout.JAVA_INT.withName("time"),
        ValueLayout.JAVA_INT.withName("pt_x"),
        ValueLayout.JAVA_INT.withName("pt_y"),
        ValueLayout.JAVA_INT.withName("lPrivate"));
    static final MemoryLayout NOTIFY_ICON = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("cbSize"),
        MemoryLayout.paddingLayout(4),
        ValueLayout.ADDRESS.withName("hWnd"),
        ValueLayout.JAVA_INT.withName("uID"),
        ValueLayout.JAVA_INT.withName("uFlags"),
        ValueLayout.JAVA_INT.withName("uCallbackMessage"),
        MemoryLayout.paddingLayout(4),
        ValueLayout.ADDRESS.withName("hIcon"),
        MemoryLayout.sequenceLayout(128, ValueLayout.JAVA_SHORT).withName("szTip"),
        ValueLayout.JAVA_INT.withName("dwState"),
        ValueLayout.JAVA_INT.withName("dwStateMask"),
        MemoryLayout.sequenceLayout(256, ValueLayout.JAVA_SHORT).withName("szInfo"),
        ValueLayout.JAVA_INT.withName("uTimeoutOrVersion"),
        MemoryLayout.sequenceLayout(64, ValueLayout.JAVA_SHORT).withName("szInfoTitle"),
        ValueLayout.JAVA_INT.withName("dwInfoFlags"),
        MemoryLayout.sequenceLayout(16, ValueLayout.JAVA_BYTE).withName("guidItem"),
        ValueLayout.ADDRESS.withName("hBalloonIcon"));
    static final FunctionDescriptor WINDOW_PROC = FunctionDescriptor.of(ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG);
    private Win32Bindings(Arena arena, Map<String, MethodHandle> handles) {
        super(arena, handles);
    }
    static Win32Bindings load() {
        Arena arena = Arena.ofShared();
        try {
            List<SymbolLookup> lookups = List.of(
                    SymbolLookup.libraryLookup("kernel32", arena),
                    SymbolLookup.libraryLookup("user32", arena),
                    SymbolLookup.libraryLookup("shell32", arena),
                    SymbolLookup.libraryLookup("gdi32", arena));
            var handles = new HashMap<String, MethodHandle>();
            bind(handles, lookups, "GetShellWindow", FunctionDescriptor.of(ValueLayout.ADDRESS));
            bind(handles, lookups, "FindWindowW",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookups, "SendMessageW",
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
            bind(handles, lookups, "RegisterWindowMessageW",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            bind(handles, lookups, "GetCursorPos",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            bind(handles, lookups, "GetModuleHandleW",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookups, "GetLastError",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT));
            bind(handles, lookups, "RegisterClassExW",
                    FunctionDescriptor.of(ValueLayout.JAVA_SHORT, ValueLayout.ADDRESS));
            bind(handles, lookups, "UnregisterClassW",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookups, "CreateWindowExW",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookups, "DestroyWindow",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            bind(handles, lookups, "DefWindowProcW",
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
            bind(handles, lookups, "PeekMessageW",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            bind(handles, lookups, "TranslateMessage",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            bind(handles, lookups, "DispatchMessageW",
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
            bind(handles, lookups, "PostMessageW",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
            bind(handles, lookups, "PostQuitMessage",
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT));
            bind(handles, lookups, "Shell_NotifyIconW",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            bind(handles, lookups, "CreateIcon",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookups, "DestroyIcon",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            bind(handles, lookups, "CreatePopupMenu",
                    FunctionDescriptor.of(ValueLayout.ADDRESS));
            bind(handles, lookups, "DestroyMenu",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            bind(handles, lookups, "AppendMenuW",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
            bind(handles, lookups, "TrackPopupMenu",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            bind(handles, lookups, "SetForegroundWindow",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            bind(handles, lookups, "UnregisterClassW",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            return new Win32Bindings(arena, handles);
        } catch (RuntimeException | Error failure) {
            arena.close();
            throw failure;
        }
    }
    private static void bind(Map<String, MethodHandle> handles, List<SymbolLookup> lookups,
                             String name, FunctionDescriptor descriptor) {
        MemorySegment symbol = lookups.stream().flatMap(lookup -> lookup.find(name).stream()).findFirst()
                .orElseThrow(() -> new UnsatisfiedLinkError(name));
        handles.put(name, Linker.nativeLinker().downcallHandle(symbol, descriptor));
    }
}
