package io.github.red171.libtray.windows;

import io.github.red171.libtray.Tray;
import io.github.red171.libtray.TrayBuilder;
import io.github.red171.libtray.TrayEvent;
import io.github.red171.libtray.internal.NativeLibrary;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class Win32TrayTest {
    private static Win32Bindings callbackBindings;
    private static final AtomicInteger CALLBACKS = new AtomicInteger();
    @Test
    void matches64BitWindowsStructLayouts() {
        assertEquals(80, Win32Bindings.WINDOW_CLASS.byteSize());
        assertEquals(48, Win32Bindings.MESSAGE.byteSize());
        assertEquals(976, Win32Bindings.NOTIFY_ICON.byteSize());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @EnabledIfSystemProperty(named = "libtray.nativeTests", matches = "true")
    void createsReceivesNativeCallbacksAndRecreates() throws Exception {
        try (Win32Bindings bindings = Win32Bindings.load(); Arena arena = Arena.ofConfined()) {
            long shell = bindings.pointer("GetShellWindow").address();
            long taskbar = bindings.pointer("FindWindowW", NativeLibrary.wideString(arena, "Shell_TrayWnd"),
                    MemorySegment.NULL).address();
            assumeTrue(shell != 0 && taskbar != 0,
                    "Runner has no interactive shell/taskbar (shell=" + shell + ", taskbar=" + taskbar + ")");
        }
        var bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), "PNG", bytes));
        for (int attempt = 0; attempt < 2; attempt++) {
            try (Tray tray = Win32Tray.create(new TrayBuilder("Port test", bytes.toByteArray()));
                 Win32Bindings bindings = Win32Bindings.load()) {
                assertNotNull(tray, "Shell_NotifyIcon must be available");
                var events = new LinkedBlockingQueue<TrayEvent>();
                tray.onEvent(events::add);
                var nativeTray = (Win32Tray) tray;
                assertNotEquals(0, bindings.number("PostMessageW", nativeTray.windowHandle(), 0x0401, 0L, 0x0202L));
                assertSame(TrayEvent.Activated.INSTANCE, events.poll(3, TimeUnit.SECONDS));
                assertNotEquals(0, bindings.number("PostMessageW", nativeTray.windowHandle(), 0x0401, 0L, 0x007bL));
                assertSame(TrayEvent.MenuRequested.INSTANCE, events.poll(3, TimeUnit.SECONDS));
                assertTrue(tray.setTooltip("Changed"));
                assertTrue(tray.setIcon(bytes.toByteArray()));
                tray.close();
                assertFalse(tray.isOpen());
                assertFalse(tray.setTooltip("Closed"));
            }
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @EnabledIfSystemProperty(named = "libtray.nativeTests", matches = "true")
    void nativeWindowIconAndUpcallWorkWithoutExplorer() throws Exception {
        try (Win32Bindings bindings = Win32Bindings.load(); Arena arena = Arena.ofConfined()) {
            callbackBindings = bindings;
            CALLBACKS.set(0);
            var method = MethodHandles.lookup().findStatic(Win32TrayTest.class, "windowProc",
                    MethodType.methodType(long.class, MemorySegment.class, int.class, long.class, long.class));
            MemorySegment stub = Linker.nativeLinker().upcallStub(method, Win32Bindings.WINDOW_PROC, arena);
            MemorySegment instance = bindings.pointer("GetModuleHandleW", MemorySegment.NULL);
            MemorySegment name = NativeLibrary.wideString(arena, "LibtrayNativeTest-" + ProcessHandle.current().pid());
            MemorySegment windowClass = arena.allocate(Win32Bindings.WINDOW_CLASS);
            windowClass.set(ValueLayout.JAVA_INT, offset("cbSize"), (int) Win32Bindings.WINDOW_CLASS.byteSize());
            windowClass.set(ValueLayout.ADDRESS, offset("lpfnWndProc"), stub);
            windowClass.set(ValueLayout.ADDRESS, offset("hInstance"), instance);
            windowClass.set(ValueLayout.ADDRESS, offset("lpszClassName"), name);
            assertNotEquals(0, bindings.number("RegisterClassExW", windowClass));
            try {
                MemorySegment window = bindings.pointer("CreateWindowExW", 0, name, name, 0, 0, 0, 0, 0,
                        MemorySegment.NULL, MemorySegment.NULL, instance, MemorySegment.NULL);
                assertNotEquals(0L, window.address());
                try {
                    assertEquals(123L, bindings.longNumber("SendMessageW", window, 0x0402, 0L, 0L));
                    assertEquals(1, CALLBACKS.get());
                    MemorySegment mask = arena.allocate(4 * 16);
                    MemorySegment color = arena.allocate(16 * 16 * 4);
                    MemorySegment icon = bindings.pointer("CreateIcon", instance, 16, 16, (byte) 1, (byte) 32, mask, color);
                    assertNotEquals(0L, icon.address());
                    assertNotEquals(0, bindings.number("DestroyIcon", icon));
                } finally {
                    assertNotEquals(0, bindings.number("DestroyWindow", window));
                }
            } finally {
                assertNotEquals(0, bindings.number("UnregisterClassW", name, instance));
            }
            callbackBindings = null;
        }
    }

    private static long offset(String field) {
        return Win32Bindings.WINDOW_CLASS.byteOffset(MemoryLayout.PathElement.groupElement(field));
    }

    private static long windowProc(MemorySegment window, int message, long word, long parameter) {
        try {
            if (message == 0x0402) {
                CALLBACKS.incrementAndGet();
                return 123;
            }
            return callbackBindings.longNumber("DefWindowProcW", window, message, word, parameter);
        } catch (Throwable failure) {
            return 0;
        }
    }
}
