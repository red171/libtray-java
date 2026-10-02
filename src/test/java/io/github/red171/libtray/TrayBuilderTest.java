package io.github.red171.libtray;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class TrayBuilderTest {
    @Test
    void defaultsMatchOriginalBuilder() {
        var builder = new TrayBuilder("App", new byte[]{1});
        assertEquals("App", builder.title());
        assertEquals(256, builder.maxIconSize());
        assertNull(builder.tooltip());
        assertNull(builder.menu());
        assertNull(builder.linuxBusName());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsMissingTitle(String title) {
        assertThrows(RuntimeException.class, () -> new TrayBuilder(title, new byte[]{1}));
    }

    @Test
    void rejectsMissingIcon() {
        assertThrows(NullPointerException.class, () -> new TrayBuilder("App", null));
        assertThrows(IllegalArgumentException.class, () -> new TrayBuilder("App", new byte[0]));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void rejectsInvalidIconSize(int size) {
        assertThrows(IllegalArgumentException.class,
                () -> new TrayBuilder("App", new byte[]{1}, null, null, size));
    }

    @Test
    void allowsUnscaledIconsAndCustomNamespace() {
        var builder = new TrayBuilder("App", new byte[]{1}, null, null, null,
                "io.github.applejuicenetz.core.StatusNotifierItem");
        assertNull(builder.maxIconSize());
        assertEquals("io.github.applejuicenetz.core.StatusNotifierItem", builder.linuxBusName());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "single", ":1.20", "1.app", "app.1name", "app..name",
            "app.name.", "app name.item", "app/invalid.item"})
    void rejectsInvalidBusNames(String busName) {
        assertThrows(IllegalArgumentException.class,
                () -> new TrayBuilder("App", new byte[]{1}, null, null, 256, busName));
    }

    @Test
    void enforcesBusNameLengthLimit() {
        String prefix = "a.";
        assertDoesNotThrow(() -> new TrayBuilder("App", new byte[]{1}, null, null, 256,
                prefix + "b".repeat(253)));
        assertThrows(IllegalArgumentException.class,
                () -> new TrayBuilder("App", new byte[]{1}, null, null, 256,
                        prefix + "b".repeat(254)));
    }

    @Test
    void copiesIconOnInputAndOutput() {
        byte[] icon = {1, 2};
        var builder = new TrayBuilder("App", icon);
        icon[0] = 9;
        byte[] returned = builder.iconBytes();
        returned[1] = 9;
        assertArrayEquals(new byte[]{1, 2}, builder.iconBytes());
    }

    @Test
    void comparesIconContentAndConfiguration() {
        var menu = new TrayMenu(List.of(new TrayMenuItem.Standard("quit", "Quit")));
        var first = new TrayBuilder("App", new byte[]{1, 2}, "tip", menu, 128, "app.tray");
        var equal = new TrayBuilder("App", new byte[]{1, 2}, "tip", menu, 128, "app.tray");
        assertEquals(first, equal);
        assertEquals(first.hashCode(), equal.hashCode());
        assertNotEquals(first, new TrayBuilder("App", new byte[]{2, 1}, "tip", menu, 128, "app.tray"));
        assertNotEquals(first, new TrayBuilder("App", new byte[]{1, 2}, "tip", menu, 128, "app.other"));
        assertNotEquals(first, null);
    }
}
