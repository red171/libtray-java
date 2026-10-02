package io.github.red171.libtray.macos;

import io.github.red171.libtray.Tray;
import io.github.red171.libtray.TrayBuilder;
import io.github.red171.libtray.TrayEvent;
import io.github.red171.libtray.TrayMenu;
import io.github.red171.libtray.TrayMenuItem;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import javax.imageio.ImageIO;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;

public final class MacSmoke {
    public static void main(String[] args) throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), "PNG", bytes);
        for (int attempt = 0; attempt < 2; attempt++) {
            var menu = new TrayMenu(List.of(new TrayMenuItem.Standard("show", "Show"),
                    TrayMenuItem.Separator.INSTANCE,
                    new TrayMenuItem.Submenu("more", "More", List.of(new TrayMenuItem.Standard("quit", "Quit", false)))));
            try (Tray tray = Tray.create(new TrayBuilder("Mac port test", bytes.toByteArray(), "Initial", menu));
                 ObjcBindings bindings = ObjcBindings.load()) {
                if (tray == null || !tray.isOpen()) {
                    throw new IllegalStateException("AppKit tray creation failed");
                }
                if (!tray.setTooltip("Changed") || !tray.setIcon(bytes.toByteArray()) || !tray.setMenu(menu)
                        || !tray.setMenu(null) || !tray.setMenu(menu)) {
                    throw new IllegalStateException("AppKit tray update failed");
                }
                var events = new ArrayList<TrayEvent>();
                tray.onEvent(events::add);
                var nativeTray = (AppKitTray) tray;
                bindings.setObject(nativeTray.buttonHandle(), "performClick:", MemorySegment.NULL);
                if (!events.contains(TrayEvent.Activated.INSTANCE)) {
                    throw new IllegalStateException("AppKit primary click callback failed");
                }
                MemorySegment selected = bindings.pointer("objc_msgSend_id_long", nativeTray.menuHandle(),
                        bindings.sel("itemAtIndex:"), 0L);
                MemorySegment target = bindings.object(selected, "target");
                bindings.setObject(target, "onMenuItem:", selected);
                if (!events.contains(new TrayEvent.MenuItemSelected("show"))) {
                    throw new IllegalStateException("AppKit menu callback failed");
                }
                tray.close();
                if (tray.isOpen() || tray.setTooltip("closed")) {
                    throw new IllegalStateException("AppKit tray shutdown failed");
                }
            }
        }
        System.out.println("AppKit: create, updates, callbacks, menu, close and recreate passed");
    }
}
