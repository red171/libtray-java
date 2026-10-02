package io.github.red171.libtray.linux;

import io.github.red171.libtray.Tray;
import io.github.red171.libtray.TrayBuilder;
import io.github.red171.libtray.TrayEvent;
import io.github.red171.libtray.TrayMenu;
import io.github.red171.libtray.TrayMenuItem;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static io.github.red171.libtray.linux.DBusCodec.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "libtray.nativeTests", matches = "true")
@Timeout(20)
class SniTrayTest {
    private static final String NAME = "io.github.red171.libtray.Test.StatusNotifierItem";
    private static final String ITEM = "org.kde.StatusNotifierItem";
    private static final String PROPERTIES = "org.freedesktop.DBus.Properties";
    private static final String MENU = "com.canonical.dbusmenu";

    private Tray create() throws Exception {
        var image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0x80442211);
        var bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "PNG", bytes));
        var menu = new TrayMenu(List.of(new TrayMenuItem.Standard("show", "Show"),
                new TrayMenuItem.Standard("disabled", "Disabled", false),
                TrayMenuItem.Separator.INSTANCE,
                new TrayMenuItem.Submenu("more", "More", List.of(new TrayMenuItem.Standard("quit", "Quit")))));
        Tray tray = Tray.create(new TrayBuilder("Test App", bytes.toByteArray(), "Initial tooltip", menu, 256, NAME));
        assertNotNull(tray);
        return tray;
    }

    @Test
    void registersAndReregistersAfterWatcherRestart() throws Exception {
        try (Tray tray = create()) {
            try (var watcher = new TestWatcher()) {
                assertEquals(NAME, watcher.registrations.poll(3, TimeUnit.SECONDS));
            }
            try (var watcher = new TestWatcher()) {
                assertEquals(NAME, watcher.registrations.poll(3, TimeUnit.SECONDS));
            }
            assertTrue(tray.isOpen());
        }
    }

    @Test
    void exportsTypedPropertiesAndNetworkOrderIcon() throws Exception {
        try (Tray tray = create(); var client = new TestBus()) {
            var response = client.request(NAME, "/StatusNotifierItem", PROPERTIES, "GetAll", text(ITEM));
            assertNull(response.error());
            assertEquals("a{sv}", response.signature());
            Map<String, Object> properties = dictionary(response.values().getFirst());
            assertEquals("testapp", properties.get("Id"));
            assertEquals("Initial tooltip", properties.get("Title"));
            assertEquals("/MenuBar", properties.get("Menu"));
            assertEquals(false, properties.get("ItemIsMenu"));
            List<?> pixmap = (List<?>) ((List<?>) properties.get("IconPixmap")).getFirst();
            assertEquals(16, pixmap.get(0));
            assertEquals(16, pixmap.get(1));
            assertEquals(List.of((byte) 0x80, (byte) 0x44, (byte) 0x22, (byte) 0x11),
                    ((List<?>) pixmap.get(2)).subList(0, 4));
            assertTrue(tray.setTooltip("Changed tooltip"));
            var changed = client.request(NAME, "/StatusNotifierItem", PROPERTIES, "Get", text(ITEM), text("Title"));
            assertNull(changed.error());
            assertEquals(List.of("Changed tooltip"), changed.values());
        }
    }

    @Test
    void exportsMenusSupportsDepthAndClearingWithoutChangingPath() throws Exception {
        try (Tray tray = create(); var client = new TestBus()) {
            var response = client.request(NAME, "/MenuBar", MENU, "GetLayout", integer(0), integer(-1), array("s", List.of()));
            assertNull(response.error());
            assertEquals("u(ia{sv}av)", response.signature());
            List<?> root = (List<?>) response.values().get(1);
            assertEquals(4, ((List<?>) root.get(2)).size());
            List<?> disabled = (List<?>) ((List<?>) root.get(2)).get(1);
            assertEquals(false, dictionary(disabled.get(1)).get("enabled"));
            List<?> submenu = (List<?>) ((List<?>) root.get(2)).get(3);
            assertEquals(1, ((List<?>) submenu.get(2)).size());
            var shallow = client.request(NAME, "/MenuBar", MENU, "GetLayout", integer(0), integer(0), array("s", List.of()));
            assertTrue(((List<?>) ((List<?>) shallow.values().get(1)).get(2)).isEmpty());
            assertTrue(tray.setMenu(null));
            var empty = client.request(NAME, "/MenuBar", MENU, "GetLayout", integer(0), integer(-1), array("s", List.of()));
            assertTrue(((List<?>) ((List<?>) empty.values().get(1)).get(2)).isEmpty());
            assertEquals(2, empty.values().getFirst());
            var path = client.request(NAME, "/StatusNotifierItem", PROPERTIES, "Get", text(ITEM), text("Menu"));
            assertEquals(List.of("/MenuBar"), path.values());
        }
    }

    @Test
    void routesClicksAndOnlyEnabledStandardMenuItems() throws Exception {
        try (Tray tray = create(); var client = new TestBus()) {
            var events = new LinkedBlockingQueue<TrayEvent>();
            Runnable unsubscribe = tray.onEvent(events::add);
            assertNull(client.request(NAME, "/StatusNotifierItem", ITEM, "Activate", integer(0), integer(0)).error());
            assertSame(TrayEvent.Activated.INSTANCE, events.poll(2, TimeUnit.SECONDS));
            assertNull(client.request(NAME, "/StatusNotifierItem", ITEM, "ContextMenu", integer(0), integer(0)).error());
            assertSame(TrayEvent.MenuRequested.INSTANCE, events.poll(2, TimeUnit.SECONDS));
            assertNull(client.request(NAME, "/MenuBar", MENU, "Event", integer(1), text("clicked"), variant(integer(0)), unsigned(0)).error());
            assertEquals(new TrayEvent.MenuItemSelected("show"), events.poll(2, TimeUnit.SECONDS));
            for (int id : List.of(2, 3, 4)) {
                assertNull(client.request(NAME, "/MenuBar", MENU, "Event", integer(id), text("clicked"), variant(integer(0)), unsigned(0)).error());
            }
            assertNull(events.poll(150, TimeUnit.MILLISECONDS));
            unsubscribe.run();
            unsubscribe.run();
            client.request(NAME, "/StatusNotifierItem", ITEM, "Activate", integer(0), integer(0));
            assertNull(events.poll(150, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void rejectsMalformedRequestsAndExportsIntrospection() throws Exception {
        try (Tray tray = create(); var client = new TestBus()) {
            assertEquals("org.freedesktop.DBus.Error.InvalidArgs",
                    client.request(NAME, "/StatusNotifierItem", ITEM, "Activate", text("bad")).error());
            assertEquals("org.freedesktop.DBus.Error.UnknownProperty",
                    client.request(NAME, "/StatusNotifierItem", PROPERTIES, "Get", text(ITEM), text("Missing")).error());
            assertEquals("org.freedesktop.DBus.Error.UnknownObject",
                    client.request(NAME, "/Missing", ITEM, "Activate", integer(0), integer(0)).error());
            var xml = client.request(NAME, "/MenuBar", "org.freedesktop.DBus.Introspectable", "Introspect");
            assertNull(xml.error());
            assertTrue(((String) xml.values().getFirst()).contains("GetLayout"));
        }
    }

    @Test
    void closesFromHandlerAndCanReuseBusName() throws Exception {
        try (Tray tray = create(); var client = new TestBus()) {
            tray.onEvent(event -> tray.close());
            client.request(NAME, "/StatusNotifierItem", ITEM, "Activate", integer(0), integer(0));
            assertFalse(tray.isOpen());
            assertFalse(tray.setTooltip("closed"));
        }
        try (Tray next = create()) {
            assertTrue(next.isOpen());
        }
    }

    private Map<String, Object> dictionary(Object raw) {
        var result = new HashMap<String, Object>();
        for (Object entry : (List<?>) raw) {
            List<?> pair = (List<?>) entry;
            result.put((String) pair.get(0), pair.get(1));
        }
        return result;
    }
}
