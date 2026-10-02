package io.github.red171.libtray;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TrayMenuTest {
    @Test
    void rejectsEmptyMenus() {
        assertThrows(IllegalArgumentException.class, () -> new TrayMenu(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new TrayMenuItem.Submenu("more", "More", List.of()));
    }

    @Test
    void rejectsNullItemsAndLabels() {
        var items = new ArrayList<TrayMenuItem>();
        items.add(null);
        assertThrows(NullPointerException.class, () -> new TrayMenu(items));
        assertThrows(NullPointerException.class, () -> new TrayMenuItem.Standard(null, "Quit"));
        assertThrows(NullPointerException.class, () -> new TrayMenuItem.Standard("quit", null));
    }

    @Test
    void menusAreSnapshots() {
        var items = new ArrayList<TrayMenuItem>();
        items.add(new TrayMenuItem.Standard("quit", "Quit"));
        var menu = new TrayMenu(items);
        var submenu = new TrayMenuItem.Submenu("more", "More", items);
        items.clear();
        assertEquals(1, menu.items().size());
        assertEquals(1, submenu.items().size());
        assertThrows(UnsupportedOperationException.class, () -> menu.items().clear());
        assertThrows(UnsupportedOperationException.class, () -> submenu.items().clear());
    }

    @Test
    void preservesDefaultsAndDuplicateIds() {
        var first = new TrayMenuItem.Standard("show", "Show");
        var second = new TrayMenuItem.Standard("show", "Other");
        assertTrue(first.enabled());
        assertDoesNotThrow(() -> new TrayMenu(List.of(first, second)));
        assertFalse(TrayMenuItem.Separator.INSTANCE.enabled());
        assertEquals("---", TrayMenuItem.Separator.INSTANCE.id());
        assertEquals("", TrayMenuItem.Separator.INSTANCE.label());
    }

    @Test
    void eventsNeedNoKotlinTypes() {
        TrayEvent activated = TrayEvent.Activated.INSTANCE;
        TrayEvent selected = new TrayEvent.MenuItemSelected("show");
        assertSame(TrayEvent.Activated.INSTANCE, activated);
        assertEquals(new TrayEvent.MenuItemSelected("show"), selected);
        assertThrows(NullPointerException.class, () -> new TrayEvent.MenuItemSelected(null));
    }
}
