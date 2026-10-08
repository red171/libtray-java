package io.github.red171.libtray.macos;

import io.github.red171.libtray.IconScaling;
import io.github.red171.libtray.Tray;
import io.github.red171.libtray.TrayBuilder;
import io.github.red171.libtray.TrayEvent;
import io.github.red171.libtray.TrayMenu;
import io.github.red171.libtray.TrayMenuItem;
import io.github.red171.libtray.internal.AbstractTray;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class AppKitTray extends AbstractTray {
    private static final AtomicLong IDS = new AtomicLong();
    private static final Map<Long, Runnable> PENDING = new ConcurrentHashMap<>();
    private static final Map<Long, AppKitTray> BUTTONS = new ConcurrentHashMap<>();
    private static final Map<Long, Selection> SELECTIONS = new ConcurrentHashMap<>();
    private final ObjcBindings bindings;
    private final MemorySegment statusBar;
    private final MemorySegment statusItem;
    private final MemorySegment button;
    private final Integer maxIconSize;
    private final Set<Long> tags = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean tornDown = new AtomicBoolean();
    private MemorySegment nativeMenu = MemorySegment.NULL;
    /** True while popUp() is inside menu tracking. Cocoa main thread only. */
    private boolean menuOpen;

    private AppKitTray(ObjcBindings bindings, MemorySegment statusBar, MemorySegment statusItem,
                       MemorySegment button, TrayBuilder builder) {
        this.bindings = bindings;
        this.statusBar = statusBar;
        this.statusItem = statusItem;
        this.button = button;
        maxIconSize = builder.maxIconSize();
    }

    public static Tray create(TrayBuilder builder) {
        ObjcBindings bindings = RuntimeState.BINDINGS;
        if (bindings.isMainThread()) {
            return createMain(builder);
        }
        if (GraphicsEnvironment.isHeadless()) {
            return null;
        }
        Toolkit.getDefaultToolkit();
        var result = new CompletableFuture<Tray>();
        long id = enqueue(() -> {
            if (result.isCancelled()) {
                return;
            }
            try {
                Tray tray = createMain(builder);
                if (!result.complete(tray) && tray != null) {
                    tray.close();
                }
            } catch (RuntimeException | Error failure) {
                result.completeExceptionally(failure);
            }
        });
        try {
            return result.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException failure) {
            debug(failure);
        }
        result.cancel(false);
        PENDING.remove(id);
        return null;
    }

    private static Tray createMain(TrayBuilder builder) {
        ObjcBindings bindings = RuntimeState.BINDINGS;
        var result = new AppKitTray[1];
        bindings.pool(() -> {
            MemorySegment application = bindings.object(bindings.cls("NSApplication"), "sharedApplication");
            if (bindings.number("objc_msgSend_bool", application, bindings.sel("isRunning")) == 0) {
                bindings.number("objc_msgSend_bool_long", application, bindings.sel("setActivationPolicy:"), 1L);
                bindings.call("objc_msgSend_void", application, bindings.sel("finishLaunching"));
            }
            MemorySegment statusBar = bindings.object(bindings.cls("NSStatusBar"), "systemStatusBar");
            MemorySegment statusItem = bindings.pointer("objc_msgSend_id_double", statusBar,
                    bindings.sel("statusItemWithLength:"), -2.0);
            if (statusItem.address() == 0) {
                return;
            }
            bindings.pointer("objc_retain", statusItem);
            MemorySegment button = bindings.object(statusItem, "button");
            var tray = new AppKitTray(bindings, statusBar, statusItem, button, builder);
            try {
                if (button.address() == 0) {
                    throw new IllegalStateException("Status bar button unavailable");
                }
                BUTTONS.put(button.address(), tray);
                bindings.setObject(button, "setTarget:", RuntimeState.TARGET);
                bindings.setObject(button, "setAction:", bindings.sel("onTrayClick:"));
                bindings.longNumber("objc_msgSend_long_long", button, bindings.sel("sendActionOn:"),
                        (1L << 2) | (1L << 4) | (1L << 26));
                tray.applyIcon(IconScaling.fit(builder.iconBytes(), builder.maxIconSize()));
                bindings.setObject(button, "setToolTip:", bindings.text(builder.tooltip() == null ? "" : builder.tooltip()));
                tray.applyMenu(builder.menu());
                result[0] = tray;
            } catch (RuntimeException | Error failure) {
                tray.open.set(false);
                tray.teardown();
                throw failure;
            }
        });
        return result[0];
    }

    @Override
    public boolean setTooltip(String text) {
        Objects.requireNonNull(text, "text");
        return update(() -> bindings.setObject(button, "setToolTip:", bindings.text(text)));
    }

    @Override
    public boolean setIcon(byte[] bytes) {
        Objects.requireNonNull(bytes, "iconBytes");
        if (bytes.length == 0) {
            return false;
        }
        byte[] icon = IconScaling.fit(bytes.clone(), maxIconSize);
        return update(() -> applyIcon(icon));
    }

    @Override
    public boolean setMenu(TrayMenu menu) {
        return update(() -> applyMenu(menu));
    }

    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        clearHandlers();
        BUTTONS.remove(button.address());
        if (bindings.isMainThread()) {
            bindings.pool(this::teardown);
            return;
        }
        // Run the teardown on the Cocoa main queue and wait, bounded, so the icon is gone
        // when close() returns. It queues behind any update already running, so none of
        // them can message a released status item. On timeout tear down on this thread
        // instead; the tornDown guard keeps the queued copy from running a second time.
        var done = new CountDownLatch(1);
        try {
            enqueue(() -> {
                try {
                    bindings.pool(this::teardown);
                } finally {
                    done.countDown();
                }
            });
            if (done.await(2, TimeUnit.SECONDS)) {
                return;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            debug(failure);
        }
        bindings.pool(this::teardown);
    }

    private boolean update(Runnable action) {
        if (!open.get()) {
            return false;
        }
        Runnable guarded = () -> {
            if (open.get()) {
                bindings.pool(action);
            }
        };
        try {
            if (bindings.isMainThread()) {
                guarded.run();
            } else {
                enqueue(guarded);
            }
            return true;
        } catch (RuntimeException failure) {
            debug(failure);
            return false;
        }
    }

    MemorySegment buttonHandle() {
        return button;
    }

    MemorySegment menuHandle() {
        return nativeMenu;
    }

    private void applyIcon(byte[] bytes) {
        MemorySegment allocated = bindings.object(bindings.cls("NSImage"), "alloc");
        MemorySegment image = bindings.object(allocated, "initWithData:", bindings.data(bytes));
        if (image.address() == 0) {
            throw new IllegalArgumentException("Icon cannot be decoded");
        }
        try {
            bindings.setBool(image, "setTemplate:", false);
            bindings.setObject(button, "setImage:", image);
        } finally {
            bindings.release(image);
        }
    }

    private MemorySegment newMenu(String title) {
        MemorySegment menu = bindings.object(bindings.object(bindings.cls("NSMenu"), "alloc"),
                "initWithTitle:", bindings.text(title));
        bindings.setBool(menu, "setAutoenablesItems:", false);
        return menu;
    }

    private void applyMenu(TrayMenu menu) {
        tags.forEach(SELECTIONS::remove);
        tags.clear();
        MemorySegment replacement = menu == null ? MemorySegment.NULL : newMenu("");
        try {
            if (menu != null) {
                appendItems(replacement, menu.items(), true);
            }
        } catch (RuntimeException | Error failure) {
            bindings.release(replacement);
            throw failure;
        }
        MemorySegment previous = nativeMenu;
        nativeMenu = replacement;
        bindings.release(previous);
    }

    private void appendItems(MemorySegment parent, List<TrayMenuItem> items, boolean parentEnabled) {
        for (TrayMenuItem item : items) {
            if (item instanceof TrayMenuItem.Separator) {
                bindings.setObject(parent, "addItem:", bindings.object(bindings.cls("NSMenuItem"), "separatorItem"));
                continue;
            }
            MemorySegment action = item instanceof TrayMenuItem.Standard ? bindings.sel("onMenuItem:") : MemorySegment.NULL;
            MemorySegment menuItem = bindings.pointer("objc_msgSend_id_id_sel_id",
                    bindings.object(bindings.cls("NSMenuItem"), "alloc"), bindings.sel("initWithTitle:action:keyEquivalent:"),
                    bindings.text(item.label()), action, bindings.text(""));
            try {
                bindings.setBool(menuItem, "setEnabled:", item.enabled());
                if (item instanceof TrayMenuItem.Standard standard) {
                    long tag = IDS.incrementAndGet();
                    tags.add(tag);
                    SELECTIONS.put(tag, new Selection(this, standard.id(), parentEnabled && item.enabled()));
                    bindings.call("objc_msgSend_void_long", menuItem, bindings.sel("setTag:"), tag);
                    bindings.setObject(menuItem, "setTarget:", RuntimeState.TARGET);
                } else if (item instanceof TrayMenuItem.Submenu submenu) {
                    MemorySegment child = newMenu(item.label());
                    try {
                        appendItems(child, submenu.items(), parentEnabled && item.enabled());
                        bindings.setObject(menuItem, "setSubmenu:", child);
                    } finally {
                        bindings.release(child);
                    }
                }
                bindings.setObject(parent, "addItem:", menuItem);
            } finally {
                bindings.release(menuItem);
            }
        }
    }

    private void teardown() {
        if (!tornDown.compareAndSet(false, true)) {
            return;
        }
        // close() from inside the open menu's tracking loop: end the tracking first so
        // AppKit is not left showing a menu of a removed item.
        if (menuOpen && nativeMenu.address() != 0) {
            bindings.call("objc_msgSend_void", nativeMenu, bindings.sel("cancelTracking"));
        }
        BUTTONS.remove(button.address());
        tags.forEach(SELECTIONS::remove);
        tags.clear();
        if (button.address() != 0) {
            bindings.setObject(button, "setTarget:", MemorySegment.NULL);
            bindings.setObject(button, "setAction:", MemorySegment.NULL);
        }
        bindings.setObject(statusBar, "removeStatusItem:", statusItem);
        // With the menu open, popUp() still holds its own references to the menu and the
        // status item and releases them once tracking has unwound.
        bindings.release(nativeMenu);
        nativeMenu = MemorySegment.NULL;
        bindings.release(statusItem);
    }

    private void popUp() {
        MemorySegment menu = nativeMenu;
        // Extra references cover a setMenu or close() that releases ours while the menu
        // is open. Their release is deferred to a later main-queue turn, after the
        // event dispatch of the status bar window has unwound.
        bindings.pointer("objc_retain", menu);
        bindings.pointer("objc_retain", statusItem);
        menuOpen = true;
        try {
            bindings.setObject(statusItem, "popUpStatusItemMenu:", menu);
        } finally {
            menuOpen = false;
            Runnable releaseLater = () -> {
                bindings.release(menu);
                bindings.release(statusItem);
            };
            try {
                enqueue(() -> bindings.pool(releaseLater));
            } catch (RuntimeException failure) {
                releaseLater.run();
            }
        }
    }

    private static long enqueue(Runnable task) {
        long id = IDS.incrementAndGet();
        PENDING.put(id, task);
        try {
            RuntimeState.BINDINGS.call("dispatch_async_f", RuntimeState.BINDINGS.mainQueue,
                    MemorySegment.ofAddress(id), RuntimeState.TRAMPOLINE);
        } catch (RuntimeException | Error failure) {
            PENDING.remove(id);
            throw failure;
        }
        return id;
    }

    private static void dispatch(MemorySegment context) {
        try {
            Runnable task = PENDING.remove(context.address());
            if (task != null) {
                task.run();
            }
        } catch (Throwable failure) {
            debug(failure);
        }
    }

    private static void menuAction(MemorySegment self, MemorySegment selector, MemorySegment sender) {
        try {
            ObjcBindings bindings = RuntimeState.BINDINGS;
            long tag = bindings.longNumber("objc_msgSend_long", sender, bindings.sel("tag"));
            Selection selection = SELECTIONS.get(tag);
            if (selection != null && selection.enabled()) {
                selection.tray().fire(new TrayEvent.MenuItemSelected(selection.id()));
            }
        } catch (Throwable failure) {
            debug(failure);
        }
    }

    private static void buttonAction(MemorySegment self, MemorySegment selector, MemorySegment sender) {
        try {
            AppKitTray tray = BUTTONS.get(sender.address());
            if (tray == null || !tray.open.get()) {
                return;
            }
            ObjcBindings bindings = tray.bindings;
            MemorySegment application = bindings.object(bindings.cls("NSApplication"), "sharedApplication");
            MemorySegment event = bindings.object(application, "currentEvent");
            long type = event.address() == 0 ? 2 : bindings.longNumber("objc_msgSend_long", event, bindings.sel("type"));
            if (type == 4) {
                tray.fire(TrayEvent.MenuRequested.INSTANCE);
                if (tray.open.get() && tray.nativeMenu.address() != 0) {
                    tray.popUp();
                }
            } else if (type == 26) {
                tray.fire(TrayEvent.MiddleActivated.INSTANCE);
            } else {
                tray.fire(TrayEvent.Activated.INSTANCE);
            }
        } catch (Throwable failure) {
            debug(failure);
        }
    }

    private static void debug(Throwable failure) {
        System.getLogger("libtray-java").log(System.Logger.Level.DEBUG, "macOS tray operation failed", failure);
    }

    private record Selection(AppKitTray tray, String id, boolean enabled) {
    }

    private static final class RuntimeState {
        static final ObjcBindings BINDINGS = ObjcBindings.load();
        static final MemorySegment TARGET;
        static final MemorySegment TRAMPOLINE;

        static {
            try {
                var lookup = MethodHandles.lookup();
                var dispatch = lookup.findStatic(AppKitTray.class, "dispatch",
                        MethodType.methodType(void.class, MemorySegment.class));
                TRAMPOLINE = Linker.nativeLinker().upcallStub(dispatch,
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS), BINDINGS.arena);
                MemorySegment targetClass = BINDINGS.pointer("objc_allocateClassPair", BINDINGS.cls("NSObject"),
                        BINDINGS.arena.allocateFrom("LibtrayJavaTarget_" + ProcessHandle.current().pid()), 0L);
                if (targetClass.address() == 0) {
                    throw new IllegalStateException("Cannot register Objective-C tray target");
                }
                FunctionDescriptor actionDescriptor = FunctionDescriptor.ofVoid(
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
                for (String name : List.of("menuAction", "buttonAction")) {
                    var action = lookup.findStatic(AppKitTray.class, name,
                            MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class));
                    MemorySegment stub = Linker.nativeLinker().upcallStub(action, actionDescriptor, BINDINGS.arena);
                    String selector = name.equals("menuAction") ? "onMenuItem:" : "onTrayClick:";
                    boolean added = (boolean) BINDINGS.call("class_addMethod", targetClass, BINDINGS.sel(selector),
                            stub, BINDINGS.arena.allocateFrom("v@:@"));
                    if (!added) {
                        throw new IllegalStateException("Cannot register Objective-C tray action");
                    }
                }
                BINDINGS.call("objc_registerClassPair", targetClass);
                TARGET = BINDINGS.object(BINDINGS.object(targetClass, "alloc"), "init");
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
    }
}
