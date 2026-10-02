package io.github.red171.libtray.windows;

import io.github.red171.libtray.Tray;
import io.github.red171.libtray.TrayBuilder;
import io.github.red171.libtray.TrayEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.*;

class Win32TrayTest {
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
}
