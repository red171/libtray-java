package io.github.red171.libtray.linux;

import io.github.red171.libtray.IconScaling;
import io.github.red171.libtray.Tray;
import io.github.red171.libtray.TrayBuilder;
import io.github.red171.libtray.TrayEvent;
import io.github.red171.libtray.TrayMenu;
import io.github.red171.libtray.internal.AbstractTray;
import io.github.red171.libtray.internal.NativeLibrary;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;

import static io.github.red171.libtray.linux.DBusCodec.*;

public final class SniTray extends AbstractTray {
    private static final String ITEM_PATH = "/StatusNotifierItem";
    private static final String MENU_PATH = "/MenuBar";
    private static final String ITEM_IFACE = "org.kde.StatusNotifierItem";
    private static final String MENU_IFACE = "com.canonical.dbusmenu";
    private static final List<String> WATCHERS = List.of("org.kde.StatusNotifierWatcher",
            "org.freedesktop.StatusNotifierWatcher");
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private final DBusBindings bindings;
    private final DBusCodec codec;
    private final MemorySegment connection;
    private final String busName;
    private final String title;
    private final String appId;
    private final Integer maxIconSize;
    private final ConcurrentLinkedQueue<Runnable> outgoing = new ConcurrentLinkedQueue<>();
    private final Thread ioThread;
    private volatile String tooltip;
    private volatile Value pixmap;
    private volatile MenuState menuState;

    private SniTray(DBusBindings bindings, MemorySegment connection, String busName, TrayBuilder builder) {
        this.bindings = bindings;
        this.codec = new DBusCodec(bindings);
        this.connection = connection;
        this.busName = busName;
        title = builder.title();
        String slug = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        appId = slug.isEmpty() ? "tray" : slug;
        maxIconSize = builder.maxIconSize();
        tooltip = builder.tooltip() == null ? "" : builder.tooltip();
        pixmap = decodeIcon(builder.iconBytes());
        menuState = new MenuState(builder.menu() == null ? null : new DBusMenuLayout(builder.menu()), 1);
        ioThread = new Thread(this::dispatchLoop, "libtray-java-sni");
        ioThread.setDaemon(true);
    }

    public static Tray create(TrayBuilder builder) {
        DBusBindings bindings = DBusBindings.load();
        MemorySegment connection = MemorySegment.NULL;
        try (Arena arena = Arena.ofConfined()) {
            if (bindings.number("dbus_threads_init_default") == 0) {
                throw new IllegalStateException("D-Bus thread initialization failed");
            }
            MemorySegment error = arena.allocate(DBusBindings.ERROR_LAYOUT);
            bindings.call("dbus_error_init", error);
            String busName = builder.linuxBusName() != null ? builder.linuxBusName()
                    : "org.kde.StatusNotifierItem-" + ProcessHandle.current().pid() + "-" + COUNTER.incrementAndGet();
            try {
                connection = bindings.pointer("dbus_bus_get_private", DBusBindings.BUS_SESSION, error);
                if (connection.address() == 0) {
                    throw new IllegalStateException("No D-Bus session connection");
                }
                bindings.call("dbus_connection_set_exit_on_disconnect", connection, 0);
                int result = bindings.number("dbus_bus_request_name", connection, arena.allocateFrom(busName),
                        DBusBindings.NAME_FLAG_DO_NOT_QUEUE, error);
                if (result != DBusBindings.REQUEST_NAME_REPLY_PRIMARY_OWNER) {
                    throw new IllegalStateException("D-Bus name unavailable: " + busName);
                }
                for (String watcher : WATCHERS) {
                    String match = "type='signal',sender='org.freedesktop.DBus',interface='org.freedesktop.DBus',"
                            + "member='NameOwnerChanged',arg0='" + watcher + "'";
                    bindings.call("dbus_bus_add_match", connection, arena.allocateFrom(match), error);
                    if (bindings.number("dbus_error_is_set", error) != 0) {
                        throw new IllegalStateException("D-Bus watcher subscription failed");
                    }
                }
            } finally {
                bindings.call("dbus_error_free", error);
            }
            var tray = new SniTray(bindings, connection, busName, builder);
            for (String watcher : WATCHERS) {
                tray.outgoing.add(() -> tray.register(watcher));
            }
            tray.ioThread.start();
            return tray;
        } catch (RuntimeException | Error failure) {
            if (connection.address() != 0) {
                bindings.call("dbus_connection_close", connection);
                bindings.call("dbus_connection_unref", connection);
            }
            bindings.close();
            throw failure;
        }
    }

    @Override
    public synchronized boolean setTooltip(String text) {
        Objects.requireNonNull(text, "text");
        if (!open.get()) {
            return false;
        }
        tooltip = text;
        outgoing.add(() -> signal(ITEM_PATH, ITEM_IFACE, "NewTitle", List.of()));
        outgoing.add(() -> signal(ITEM_PATH, ITEM_IFACE, "NewToolTip", List.of()));
        return true;
    }

    @Override
    public synchronized boolean setIcon(byte[] iconBytes) {
        Objects.requireNonNull(iconBytes, "iconBytes");
        if (!open.get()) {
            return false;
        }
        try {
            pixmap = decodeIcon(iconBytes);
            outgoing.add(() -> signal(ITEM_PATH, ITEM_IFACE, "NewIcon", List.of()));
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
        menuState = new MenuState(menu == null ? null : new DBusMenuLayout(menu), menuState.revision() + 1);
        int revision = menuState.revision();
        outgoing.add(() -> signal(MENU_PATH, MENU_IFACE, "LayoutUpdated", List.of(unsigned(revision), integer(0))));
        return true;
    }

    @Override
    public void close() {
        synchronized (this) {
            if (open.compareAndSet(true, false)) {
                clearHandlers();
                outgoing.clear();
            }
        }
        if (Thread.currentThread() != ioThread) {
            try {
                ioThread.join(2000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void dispatchLoop() {
        try {
            while (open.get()) {
                try {
                    Runnable task;
                    while (open.get() && (task = outgoing.poll()) != null) {
                        task.run();
                    }
                    if (!open.get()) {
                        break;
                    }
                    if (bindings.number("dbus_connection_read_write", connection, 100) == 0) {
                        open.set(false);
                        break;
                    }
                    MemorySegment message;
                    while (open.get() && (message = bindings.pointer("dbus_connection_pop_message", connection)).address() != 0) {
                        try {
                            dispatch(message);
                        } catch (RuntimeException failure) {
                            debug(failure);
                        } finally {
                            bindings.call("dbus_message_unref", message);
                        }
                    }
                } catch (RuntimeException failure) {
                    debug(failure);
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } finally {
            open.set(false);
            outgoing.clear();
            clearHandlers();
            try {
                bindings.call("dbus_connection_close", connection);
                bindings.call("dbus_connection_unref", connection);
            } finally {
                bindings.close();
            }
        }
    }

    private void dispatch(MemorySegment message) {
        int type = bindings.number("dbus_message_get_type", message);
        String iface = messageString("dbus_message_get_interface", message);
        String member = messageString("dbus_message_get_member", message);
        String path = messageString("dbus_message_get_path", message);
        if (type == 4 && iface.equals("org.freedesktop.DBus") && member.equals("NameOwnerChanged")) {
            List<Object> arguments = codec.read(message);
            if (arguments.size() == 3 && WATCHERS.contains(arguments.get(0)) && !arguments.get(2).equals("")) {
                register((String) arguments.get(0));
            }
            return;
        }
        if (type != 1) {
            return;
        }
        if (!path.equals(ITEM_PATH) && !path.equals(MENU_PATH)) {
            error(message, "UnknownObject", "Unknown object path");
            return;
        }
        String signature = messageString("dbus_message_get_signature", message);
        List<Object> arguments = codec.read(message);
        try {
            if (iface.equals("org.freedesktop.DBus.Introspectable") && member.equals("Introspect")) {
                requireSignature(signature, "");
                reply(message, List.of(text(introspection(path))));
            } else if (iface.equals("org.freedesktop.DBus.Peer") && member.equals("Ping")) {
                requireSignature(signature, "");
                reply(message, List.of());
            } else if (iface.equals("org.freedesktop.DBus.Properties")) {
                properties(message, path, member, signature, arguments);
            } else if (iface.equals(ITEM_IFACE) && path.equals(ITEM_PATH)) {
                switch (member) {
                    case "Activate", "SecondaryActivate", "ContextMenu" -> {
                        requireSignature(signature, "ii");
                        fire(switch (member) {
                            case "Activate" -> TrayEvent.Activated.INSTANCE;
                            case "SecondaryActivate" -> TrayEvent.MiddleActivated.INSTANCE;
                            default -> TrayEvent.MenuRequested.INSTANCE;
                        });
                        reply(message, List.of());
                    }
                    case "Scroll" -> {
                        requireSignature(signature, "is");
                        reply(message, List.of());
                    }
                    default -> error(message, "UnknownMethod", "Unknown StatusNotifierItem method");
                }
            } else if (iface.equals(MENU_IFACE) && path.equals(MENU_PATH)) {
                menu(message, member, signature, arguments);
            } else {
                error(message, "UnknownMethod", "Unknown interface or method");
            }
        } catch (IllegalArgumentException failure) {
            error(message, "InvalidArgs", failure.getMessage());
        }
    }

    private void properties(MemorySegment message, String path, String member, String signature, List<Object> arguments) {
        if (member.equals("Set")) {
            error(message, "PropertyReadOnly", "Tray properties are read-only");
            return;
        }
        if (!member.equals("Get") && !member.equals("GetAll")) {
            error(message, "UnknownMethod", "Unknown property method");
            return;
        }
        requireSignature(signature, member.equals("Get") ? "ss" : "s");
        String expected = path.equals(ITEM_PATH) ? ITEM_IFACE : MENU_IFACE;
        if (!arguments.getFirst().equals(expected)) {
            error(message, "UnknownInterface", "Unknown property interface");
            return;
        }
        Map<String, Value> properties = path.equals(ITEM_PATH) ? itemProperties() : Map.of(
                "Version", unsigned(3), "TextDirection", text("ltr"), "Status", text("normal"),
                "IconThemePath", array("s", List.of()));
        if (member.equals("GetAll")) {
            reply(message, List.of(dict(properties)));
        } else {
            Value property = properties.get(arguments.get(1));
            if (property == null) {
                error(message, "UnknownProperty", "Unknown tray property");
            } else {
                reply(message, List.of(variant(property)));
            }
        }
    }

    private Map<String, Value> itemProperties() {
        var properties = new LinkedHashMap<String, Value>();
        properties.put("Category", text("ApplicationStatus"));
        properties.put("Id", text(appId));
        properties.put("Title", text(tooltip.isEmpty() ? title : tooltip));
        properties.put("Status", text("Active"));
        properties.put("WindowId", unsigned(0));
        properties.put("IconName", text(""));
        properties.put("IconPixmap", pixmap);
        properties.put("OverlayIconName", text(""));
        properties.put("OverlayIconPixmap", array("(iiay)", List.of()));
        properties.put("AttentionIconName", text(""));
        properties.put("AttentionIconPixmap", array("(iiay)", List.of()));
        properties.put("AttentionMovieName", text(""));
        properties.put("ToolTip", new Value("(sa(iiay)ss)", List.of(text(""), array("(iiay)", List.of()), text(""), text(""))));
        properties.put("Menu", new Value("o", MENU_PATH));
        properties.put("ItemIsMenu", bool(false));
        return properties;
    }

    private void menu(MemorySegment message, String member, String signature, List<Object> arguments) {
        MenuState state = menuState;
        DBusMenuLayout layout = state.layout();
        switch (member) {
            case "GetLayout" -> {
                requireSignature(signature, "iias");
                int parent = (int) arguments.get(0);
                if (parent != 0 && (layout == null || layout.nodeOf(parent) == null)) {
                    throw new IllegalArgumentException("Unknown menu item");
                }
                reply(message, List.of(unsigned(state.revision()),
                        layoutValue(layout, parent, (int) arguments.get(1), (List<?>) arguments.get(2))));
            }
            case "GetGroupProperties" -> {
                requireSignature(signature, "aias");
                List<?> requested = (List<?>) arguments.get(0);
                List<?> ids = requested.isEmpty() ? layout == null ? List.of(0) : layout.nodeIds() : requested;
                var result = new ArrayList<Value>();
                for (Object rawId : ids) {
                    int id = (int) rawId;
                    if (id == 0 || layout != null && layout.nodeOf(id) != null) {
                        result.add(new Value("(ia{sv})", List.of(integer(id),
                                menuProperties(layout, id, (List<?>) arguments.get(1)))));
                    }
                }
                reply(message, List.of(array("(ia{sv})", result)));
            }
            case "GetProperty" -> {
                requireSignature(signature, "is");
                int id = (int) arguments.get(0);
                Value property = menuProperty(layout, id, (String) arguments.get(1));
                if (property == null) {
                    error(message, "UnknownProperty", "Unknown menu property");
                } else {
                    reply(message, List.of(variant(property)));
                }
            }
            case "Event" -> {
                requireSignature(signature, "isvu");
                menuEvent(layout, (int) arguments.get(0), (String) arguments.get(1));
                reply(message, List.of());
            }
            case "EventGroup" -> {
                requireSignature(signature, "a(isvu)");
                var failed = new ArrayList<Value>();
                for (Object rawEvent : (List<?>) arguments.getFirst()) {
                    List<?> event = (List<?>) rawEvent;
                    int id = (int) event.get(0);
                    if (layout == null || layout.nodeOf(id) == null) {
                        failed.add(integer(id));
                    } else {
                        menuEvent(layout, id, (String) event.get(1));
                    }
                }
                reply(message, List.of(array("i", failed)));
            }
            case "AboutToShow" -> {
                requireSignature(signature, "i");
                reply(message, List.of(bool(false)));
            }
            case "AboutToShowGroup" -> {
                requireSignature(signature, "ai");
                var failed = new ArrayList<Value>();
                for (Object rawId : (List<?>) arguments.getFirst()) {
                    int id = (int) rawId;
                    if (id != 0 && (layout == null || layout.nodeOf(id) == null)) {
                        failed.add(integer(id));
                    }
                }
                reply(message, List.of(array("i", List.of()), array("i", failed)));
            }
            default -> error(message, "UnknownMethod", "Unknown menu method");
        }
    }

    private void menuEvent(DBusMenuLayout layout, int id, String event) {
        DBusMenuLayout.Node node = layout == null ? null : layout.nodeOf(id);
        if (node != null && event.equals("clicked") && layout.selectable(id)) {
            fire(new TrayEvent.MenuItemSelected(node.originalId()));
        }
    }

    private Value layoutValue(DBusMenuLayout layout, int id, int depth, List<?> names) {
        List<Value> children = layout == null || depth == 0 ? List.of() : layout.childrenOf(id).stream()
                .map(child -> variant(layoutValue(layout, child, depth < 0 ? -1 : depth - 1, names))).toList();
        return new Value("(ia{sv}av)", List.of(integer(id), menuProperties(layout, id, names), array("v", children)));
    }

    private Value menuProperties(DBusMenuLayout layout, int id, List<?> names) {
        var properties = new LinkedHashMap<String, Value>();
        if (layout == null && id == 0) {
            properties.put("children-display", text("submenu"));
        } else if (layout != null) {
            layout.propertiesOf(id).forEach((name, property) -> properties.put(name, switch (property) {
                case DBusMenuLayout.PropertyValue.Str value -> text(value.value());
                case DBusMenuLayout.PropertyValue.Bool value -> bool(value.value());
            }));
        }
        if (!names.isEmpty()) {
            properties.keySet().retainAll(names);
        }
        return dict(properties);
    }

    private Value menuProperty(DBusMenuLayout layout, int id, String name) {
        DBusMenuLayout.PropertyValue property = layout == null ? null : layout.propertiesOf(id).get(name);
        if (property != null) {
            return switch (property) {
                case DBusMenuLayout.PropertyValue.Str value -> text(value.value());
                case DBusMenuLayout.PropertyValue.Bool value -> bool(value.value());
            };
        }
        if (id != 0 && (layout == null || layout.nodeOf(id) == null)) {
            return null;
        }
        return switch (name) {
            case "enabled", "visible" -> bool(true);
            case "label", "children-display" -> text("");
            case "type" -> text("standard");
            default -> null;
        };
    }

    private Value decodeIcon(byte[] bytes) {
        if (bytes.length == 0) {
            throw new IllegalArgumentException("iconBytes must be non-empty");
        }
        try {
            var image = ImageIO.read(new ByteArrayInputStream(IconScaling.fit(bytes, maxIconSize)));
            if (image == null) {
                throw new IllegalArgumentException("Icon cannot be decoded");
            }
            int width = image.getWidth();
            int height = image.getHeight();
            byte[] pixels = new byte[Math.multiplyExact(Math.multiplyExact(width, height), 4)];
            for (int row = 0; row < height; row++) {
                for (int column = 0; column < width; column++) {
                    int argb = image.getRGB(column, row);
                    int offset = (row * width + column) * 4;
                    pixels[offset] = (byte) (argb >>> 24);
                    pixels[offset + 1] = (byte) (argb >>> 16);
                    pixels[offset + 2] = (byte) (argb >>> 8);
                    pixels[offset + 3] = (byte) argb;
                }
            }
            return array("(iiay)", List.of(new Value("(iiay)", List.of(integer(width), integer(height), new Value("ay", pixels)))));
        } catch (IOException failure) {
            throw new IllegalArgumentException("Icon cannot be decoded", failure);
        }
    }

    private void register(String watcher) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment message = bindings.pointer("dbus_message_new_method_call", arena.allocateFrom(watcher),
                    arena.allocateFrom("/StatusNotifierWatcher"), arena.allocateFrom("org.kde.StatusNotifierWatcher"),
                    arena.allocateFrom("RegisterStatusNotifierItem"));
            sendMessage(message, List.of(text(busName)));
        }
    }

    private void signal(String path, String iface, String member, List<Value> values) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment message = bindings.pointer("dbus_message_new_signal", arena.allocateFrom(path),
                    arena.allocateFrom(iface), arena.allocateFrom(member));
            sendMessage(message, values);
        }
    }

    private void reply(MemorySegment request, List<Value> values) {
        if (bindings.number("dbus_message_get_no_reply", request) == 0) {
            sendMessage(bindings.pointer("dbus_message_new_method_return", request), values);
        }
    }

    private void error(MemorySegment request, String name, String detail) {
        if (bindings.number("dbus_message_get_no_reply", request) != 0) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment message = bindings.pointer("dbus_message_new_error", request,
                    arena.allocateFrom("org.freedesktop.DBus.Error." + name), arena.allocateFrom(detail));
            sendMessage(message, List.of());
        }
    }

    private void sendMessage(MemorySegment message, List<Value> values) {
        if (message.address() == 0) {
            throw new IllegalStateException("D-Bus message allocation failed");
        }
        try {
            codec.append(message, values);
            if (bindings.number("dbus_connection_send", connection, message, MemorySegment.NULL) == 0) {
                throw new IllegalStateException("D-Bus send failed");
            }
        } finally {
            bindings.call("dbus_message_unref", message);
        }
    }

    private String messageString(String method, MemorySegment message) {
        return NativeLibrary.string(bindings.pointer(method, message));
    }

    private String introspection(String path) {
        String resource = path.equals(ITEM_PATH) ? "status-notifier-item.xml" : "dbus-menu.xml";
        try (var stream = SniTray.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing introspection resource: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void requireSignature(String actual, String expected) {
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException("Expected D-Bus signature: " + expected);
        }
    }

    private void debug(RuntimeException failure) {
        System.getLogger("libtray-java").log(System.Logger.Level.DEBUG, "D-Bus tray operation failed", failure);
    }

    private record MenuState(DBusMenuLayout layout, int revision) {
    }
}
