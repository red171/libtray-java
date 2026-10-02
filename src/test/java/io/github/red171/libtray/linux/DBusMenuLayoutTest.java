package io.github.red171.libtray.linux;

import io.github.red171.libtray.TrayMenu;
import io.github.red171.libtray.TrayMenuItem;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DBusMenuLayoutTest {
    private DBusMenuLayout layout() {
        return new DBusMenuLayout(new TrayMenu(List.of(
                new TrayMenuItem.Standard("show", "Show"),
                TrayMenuItem.Separator.INSTANCE,
                new TrayMenuItem.Submenu("more", "More", false, List.of(
                        new TrayMenuItem.Standard("quit", "Quit", false))))));
    }

    @Test
    void mapsHierarchyToSequentialIds() {
        DBusMenuLayout layout = layout();
        assertEquals(List.of(1, 2, 3), layout.childrenOf(0));
        assertEquals(List.of(4), layout.childrenOf(3));
        assertEquals(List.of(), layout.childrenOf(1));
        assertEquals("quit", layout.nodeOf(4).originalId());
        assertEquals(DBusMenuLayout.Kind.SUBMENU, layout.nodeOf(0).kind());
        assertEquals(DBusMenuLayout.Kind.STANDARD, layout.nodeOf(1).kind());
        assertEquals(DBusMenuLayout.Kind.SEPARATOR, layout.nodeOf(2).kind());
        assertFalse(layout.nodeOf(4).enabled());
    }

    @Test
    void exposesProtocolPropertiesAndDefaults() {
        DBusMenuLayout layout = layout();
        assertEquals(Map.of("children-display", new DBusMenuLayout.PropertyValue.Str("submenu")),
                layout.propertiesOf(0));
        assertEquals(Map.of("label", new DBusMenuLayout.PropertyValue.Str("Show")), layout.propertiesOf(1));
        assertEquals(Map.of("type", new DBusMenuLayout.PropertyValue.Str("separator")), layout.propertiesOf(2));
        assertEquals(Map.of("label", new DBusMenuLayout.PropertyValue.Str("More"),
                "enabled", new DBusMenuLayout.PropertyValue.Bool(false),
                "children-display", new DBusMenuLayout.PropertyValue.Str("submenu")), layout.propertiesOf(3));
        assertEquals(Map.of("label", new DBusMenuLayout.PropertyValue.Str("Quit"),
                "enabled", new DBusMenuLayout.PropertyValue.Bool(false)), layout.propertiesOf(4));
    }

    @Test
    void unknownIdsAreEmpty() {
        DBusMenuLayout layout = layout();
        assertNull(layout.nodeOf(99));
        assertTrue(layout.childrenOf(99).isEmpty());
        assertTrue(layout.propertiesOf(99).isEmpty());
    }

    @Test
    void disabledParentsPreventChildSelection() {
        var layout = new DBusMenuLayout(new TrayMenu(List.of(
                new TrayMenuItem.Submenu("more", "More", false,
                        List.of(new TrayMenuItem.Standard("quit", "Quit"))))));
        assertFalse(layout.selectable(0));
        assertFalse(layout.selectable(1));
        assertFalse(layout.selectable(2));
        assertTrue(layout.nodeOf(2).enabled());
    }

    @Test
    void returnedLayoutsAreImmutable() {
        DBusMenuLayout layout = layout();
        assertThrows(UnsupportedOperationException.class, () -> layout.childrenOf(0).clear());
        assertThrows(UnsupportedOperationException.class, () -> layout.propertiesOf(1).clear());
    }

    @Test
    void duplicateStringIdsStillGetDistinctProtocolIds() {
        var layout = new DBusMenuLayout(new TrayMenu(List.of(
                new TrayMenuItem.Standard("show", "First"),
                new TrayMenuItem.Standard("show", "Second"))));
        assertEquals(List.of(1, 2), layout.childrenOf(0));
        assertEquals("show", layout.nodeOf(1).originalId());
        assertEquals("show", layout.nodeOf(2).originalId());
    }
}
