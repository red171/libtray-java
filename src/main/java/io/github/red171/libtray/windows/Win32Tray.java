package io.github.red171.libtray.windows;

import io.github.red171.libtray.IconScaling;
import io.github.red171.libtray.Tray;
import io.github.red171.libtray.TrayBuilder;
import io.github.red171.libtray.TrayEvent;
import io.github.red171.libtray.TrayMenu;
import io.github.red171.libtray.TrayMenuItem;
import io.github.red171.libtray.internal.AbstractTray;
import io.github.red171.libtray.internal.NativeLibrary;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;

public final class Win32Tray extends AbstractTray {
    private static final int CALLBACK = 0x0401;
    private static final int ICON_FLAGS = 1 | 2 | 4 | 0x80;
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final Map<Long, Win32Tray> WINDOWS = new ConcurrentHashMap<>();
    private final Win32Bindings bindings;
    private final Arena arena = Arena.ofShared();
    private final MemorySegment iconData = arena.allocate(Win32Bindings.NOTIFY_ICON);
    private final MemorySegment instance;
    private final MemorySegment className;
    private final Integer maxIconSize;
    private final String title;
    private final Thread pump;
    private final CountDownLatch ready = new CountDownLatch(1);
    private final ConcurrentLinkedQueue<Runnable> pending = new ConcurrentLinkedQueue<>();
    private final Pixels initialIcon;
    private volatile MemorySegment window = MemorySegment.NULL;
    private volatile TrayMenu menu;
    private volatile String tooltip;
    private volatile boolean created;
    private volatile RuntimeException creationFailure;
    private MemorySegment icon = MemorySegment.NULL;
    private boolean version4;

    private Win32Tray(TrayBuilder builder, Pixels initialIcon) {
        bindings = RuntimeState.BINDINGS;
        instance = bindings.pointer("GetModuleHandleW", MemorySegment.NULL);
        className = NativeLibrary.wideString(arena, "LibtrayJava-" + ProcessHandle.current().pid() + "-" + COUNTER.incrementAndGet());
        title = builder.title();
        tooltip = builder.tooltip() == null ? "" : builder.tooltip();
        menu = builder.menu();
        maxIconSize = builder.maxIconSize();
        this.initialIcon = initialIcon;
        pump = new Thread(this::pumpLoop, "libtray-java-win32");
        pump.setDaemon(true);
    }

    public static Tray create(TrayBuilder builder) {
        Pixels icon = pixels(builder.iconBytes(), builder.maxIconSize());
        Win32Tray tray = new Win32Tray(builder, icon);
        tray.pump.start();
        try {
            if (tray.ready.await(5, TimeUnit.SECONDS) && tray.created && tray.open.get()) {
                return tray;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        tray.close();
        if (tray.creationFailure != null) {
            throw tray.creationFailure;
        }
        return null;
    }

    @Override
    public synchronized boolean setTooltip(String text) {
        Objects.requireNonNull(text, "text");
        if (!open.get()) {
            return false;
        }
        tooltip = text;
        pending.add(() -> {
            writeTip(text);
            notifyIcon(1);
        });
        return true;
    }

    @Override
    public synchronized boolean setIcon(byte[] bytes) {
        Objects.requireNonNull(bytes, "iconBytes");
        if (!open.get()) {
            return false;
        }
        try {
            Pixels pixels = pixels(bytes, maxIconSize);
            pending.add(() -> replaceIcon(pixels));
            return true;
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    @Override
    public synchronized boolean setMenu(TrayMenu menu) {
        if (!open.get()) {
            return false;
        }
        this.menu = menu;
        return true;
    }

    @Override
    public void close() {
        synchronized (this) {
            if (open.compareAndSet(true, false)) {
                clearHandlers();
                pending.clear();
            }
        }
        MemorySegment handle = window;
        if (handle.address() != 0) {
            bindings.number("PostMessageW", handle, 0x001f, 0L, 0L);
        }
        if (Thread.currentThread() != pump) {
            try {
                pump.join(2000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    MemorySegment windowHandle() {
        return window;
    }

    private void pumpLoop() {
        boolean registered = false;
        try {
            MemorySegment windowClass = arena.allocate(Win32Bindings.WINDOW_CLASS);
            windowClass.set(ValueLayout.JAVA_INT, offset(Win32Bindings.WINDOW_CLASS, "cbSize"), (int) Win32Bindings.WINDOW_CLASS.byteSize());
            windowClass.set(ValueLayout.ADDRESS, offset(Win32Bindings.WINDOW_CLASS, "lpfnWndProc"), RuntimeState.WINDOW_PROC);
            windowClass.set(ValueLayout.ADDRESS, offset(Win32Bindings.WINDOW_CLASS, "hInstance"), instance);
            windowClass.set(ValueLayout.ADDRESS, offset(Win32Bindings.WINDOW_CLASS, "lpszClassName"), className);
            if (bindings.number("RegisterClassExW", windowClass) == 0) {
                throw nativeFailure("RegisterClassExW");
            }
            registered = true;
            window = bindings.pointer("CreateWindowExW", 0, className, NativeLibrary.wideString(arena, title),
                    0, 0, 0, 0, 0, MemorySegment.NULL, MemorySegment.NULL, instance, MemorySegment.NULL);
            if (window.address() == 0) {
                throw nativeFailure("CreateWindowExW");
            }
            WINDOWS.put(window.address(), this);
            icon = createIcon(initialIcon);
            iconData.set(ValueLayout.JAVA_INT, notifyOffset("cbSize"), (int) Win32Bindings.NOTIFY_ICON.byteSize());
            iconData.set(ValueLayout.ADDRESS, notifyOffset("hWnd"), window);
            iconData.set(ValueLayout.JAVA_INT, notifyOffset("uID"), 1);
            iconData.set(ValueLayout.JAVA_INT, notifyOffset("uCallbackMessage"), CALLBACK);
            iconData.set(ValueLayout.JAVA_INT, notifyOffset("uTimeoutOrVersion"), 4);
            iconData.set(ValueLayout.ADDRESS, notifyOffset("hIcon"), icon);
            writeTip(tooltip);
            created = icon.address() != 0 && notifyIcon(0);
            if (!created) {
                throw nativeFailure(icon.address() == 0 ? "CreateIcon" : "Shell_NotifyIconW NIM_ADD");
            }
            version4 = notifyIcon(4);
            ready.countDown();
            MemorySegment message = arena.allocate(Win32Bindings.MESSAGE);
            while (open.get()) {
                Runnable update;
                while (open.get() && (update = pending.poll()) != null) {
                    update.run();
                }
                if (bindings.number("PeekMessageW", message, MemorySegment.NULL, 0, 0, 1) == 0) {
                    Thread.sleep(16);
                    continue;
                }
                bindings.number("TranslateMessage", message);
                bindings.longNumber("DispatchMessageW", message);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            creationFailure = failure;
            debug(failure);
        } finally {
            open.set(false);
            ready.countDown();
            pending.clear();
            clearHandlers();
            try {
                if (window.address() != 0) {
                    notifyIcon(2);
                    bindings.number("DestroyWindow", window);
                    WINDOWS.remove(window.address());
                    window = MemorySegment.NULL;
                }
                if (icon.address() != 0) {
                    bindings.number("DestroyIcon", icon);
                }
                if (registered) {
                    bindings.number("UnregisterClassW", className, instance);
                }
            } finally {
                arena.close();
            }
        }
    }

    private boolean notifyIcon(int operation) {
        iconData.set(ValueLayout.JAVA_INT, notifyOffset("uFlags"), ICON_FLAGS);
        return bindings.number("Shell_NotifyIconW", operation, iconData) != 0;
    }

    private IllegalStateException nativeFailure(String operation) {
        return new IllegalStateException(operation + " failed (GetLastError=" + bindings.number("GetLastError") + ")");
    }

    private void writeTip(String text) {
        MemorySegment field = iconData.asSlice(notifyOffset("szTip"), 256);
        field.fill((byte) 0);
        int length = Math.min(text.length(), 127);
        if (length > 0 && Character.isHighSurrogate(text.charAt(length - 1))) {
            length--;
        }
        for (int index = 0; index < length; index++) {
            field.set(ValueLayout.JAVA_CHAR, index * 2L, text.charAt(index));
        }
    }

    private void replaceIcon(Pixels pixels) {
        MemorySegment replacement = createIcon(pixels);
        if (replacement.address() == 0) {
            return;
        }
        MemorySegment previous = icon;
        iconData.set(ValueLayout.ADDRESS, notifyOffset("hIcon"), replacement);
        if (notifyIcon(1)) {
            icon = replacement;
            bindings.number("DestroyIcon", previous);
        } else {
            iconData.set(ValueLayout.ADDRESS, notifyOffset("hIcon"), previous);
            bindings.number("DestroyIcon", replacement);
        }
    }

    private MemorySegment createIcon(Pixels pixels) {
        try (Arena temporary = Arena.ofConfined()) {
            MemorySegment color = temporary.allocateArray(ValueLayout.JAVA_BYTE, pixels.bgra());
            MemorySegment mask = temporary.allocate(((pixels.width() + 31L) / 32) * 4 * pixels.height());
            return bindings.pointer("CreateIcon", instance, pixels.width(), pixels.height(), (byte) 1, (byte) 32, mask, color);
        }
    }

    private static Pixels pixels(byte[] bytes, Integer maxIconSize) {
        try {
            var image = ImageIO.read(new ByteArrayInputStream(IconScaling.fit(bytes, maxIconSize)));
            if (image == null || image.getWidth() > 256 || image.getHeight() > 256) {
                throw new IllegalArgumentException("Windows icon must decode to 1..256 pixels");
            }
            int width = image.getWidth();
            int height = image.getHeight();
            byte[] bgra = new byte[width * height * 4];
            for (int row = 0; row < height; row++) {
                for (int column = 0; column < width; column++) {
                    int pixel = image.getRGB(column, row);
                    int offset = (row * width + column) * 4;
                    bgra[offset] = (byte) pixel;
                    bgra[offset + 1] = (byte) (pixel >>> 8);
                    bgra[offset + 2] = (byte) (pixel >>> 16);
                    bgra[offset + 3] = (byte) (pixel >>> 24);
                }
            }
            return new Pixels(width, height, bgra);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Icon cannot be decoded", failure);
        }
    }

    private long message(int message, long word, long parameter) {
        if (RuntimeState.TASKBAR_CREATED != 0 && message == RuntimeState.TASKBAR_CREATED && open.get()) {
            if (notifyIcon(0)) {
                version4 = notifyIcon(4);
            }
            return 0;
        }
        if (message != CALLBACK) {
            return bindings.longNumber("DefWindowProcW", window, message, word, parameter);
        }
        int event = (int) parameter & 0xffff;
        switch (event) {
            case 0x0202, 0x0400, 0x0401 -> fire(TrayEvent.Activated.INSTANCE);
            case 0x0208 -> fire(TrayEvent.MiddleActivated.INSTANCE);
            case 0x0205, 0x007b -> {
                fire(TrayEvent.MenuRequested.INSTANCE);
                showMenu(word);
            }
            default -> {}
        }
        return 0;
    }

    private void showMenu(long position) {
        TrayMenu current = menu;
        if (current == null || !open.get()) {
            return;
        }
        try (Arena temporary = Arena.ofConfined()) {
            int horizontal = (short) position;
            int vertical = (short) (position >>> 16);
            if (!version4 || horizontal == -1 && vertical == -1) {
                MemorySegment point = temporary.allocate(8, 4);
                bindings.number("GetCursorPos", point);
                horizontal = point.get(ValueLayout.JAVA_INT, 0);
                vertical = point.get(ValueLayout.JAVA_INT, 4);
            }
            MemorySegment popup = bindings.pointer("CreatePopupMenu");
            if (popup.address() == 0) {
                return;
            }
            try {
                var commands = new HashMap<Integer, String>();
                appendMenu(popup, current.items(), commands, new AtomicInteger(0x1000), temporary);
                bindings.number("SetForegroundWindow", window);
                int selected = bindings.number("TrackPopupMenu", popup, 0x0100 | 0x0002,
                        horizontal, vertical, 0, window, MemorySegment.NULL);
                String id = commands.get(selected);
                if (id != null) {
                    fire(new TrayEvent.MenuItemSelected(id));
                }
            } finally {
                bindings.number("DestroyMenu", popup);
                bindings.number("PostMessageW", window, 0, 0L, 0L);
            }
        }
    }

    private void appendMenu(MemorySegment parent, List<TrayMenuItem> items, Map<Integer, String> commands,
                            AtomicInteger next, Arena temporary) {
        for (TrayMenuItem item : items) {
            int flags = item.enabled() ? 0 : 1;
            switch (item) {
                case TrayMenuItem.Separator separator -> bindings.number("AppendMenuW", parent, 0x0800, 0L, MemorySegment.NULL);
                case TrayMenuItem.Standard standard -> {
                    int command = next.getAndIncrement();
                    if (standard.enabled()) {
                        commands.put(command, standard.id());
                    }
                    bindings.number("AppendMenuW", parent, flags, (long) command, NativeLibrary.wideString(temporary, item.label()));
                }
                case TrayMenuItem.Submenu submenu -> {
                    MemorySegment child = bindings.pointer("CreatePopupMenu");
                    if (child.address() != 0) {
                        appendMenu(child, submenu.items(), commands, next, temporary);
                        if (bindings.number("AppendMenuW", parent, flags | 0x10, child.address(),
                                NativeLibrary.wideString(temporary, item.label())) == 0) {
                            bindings.number("DestroyMenu", child);
                        }
                    }
                }
            }
        }
    }

    private static long offset(MemoryLayout layout, String field) {
        return layout.byteOffset(MemoryLayout.PathElement.groupElement(field));
    }

    private static long notifyOffset(String field) {
        return offset(Win32Bindings.NOTIFY_ICON, field);
    }

    private static long windowProc(MemorySegment window, int message, long word, long parameter) {
        try {
            Win32Tray tray = WINDOWS.get(window.address());
            return tray == null ? RuntimeState.BINDINGS.longNumber("DefWindowProcW", window, message, word, parameter)
                    : tray.message(message, word, parameter);
        } catch (Throwable failure) {
            return 0;
        }
    }

    private void debug(RuntimeException failure) {
        System.getLogger("libtray-java").log(System.Logger.Level.DEBUG, "Windows tray operation failed", failure);
    }

    private record Pixels(int width, int height, byte[] bgra) {
    }

    private static final class RuntimeState {
        static final Win32Bindings BINDINGS = Win32Bindings.load();
        static final MemorySegment WINDOW_PROC;
        static final int TASKBAR_CREATED;

        static {
            try {
                var handle = MethodHandles.lookup().findStatic(Win32Tray.class, "windowProc",
                        MethodType.methodType(long.class, MemorySegment.class, int.class, long.class, long.class));
                WINDOW_PROC = Linker.nativeLinker().upcallStub(handle, Win32Bindings.WINDOW_PROC, BINDINGS.arena);
                TASKBAR_CREATED = BINDINGS.number("RegisterWindowMessageW", NativeLibrary.wideString(BINDINGS.arena, "TaskbarCreated"));
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
    }
}
