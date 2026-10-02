package io.github.red171.libtray.macos;

import io.github.red171.libtray.Tray;
import io.github.red171.libtray.TrayBuilder;
import io.github.red171.libtray.TrayMenu;
import io.github.red171.libtray.TrayMenuItem;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import javax.imageio.ImageIO;

public final class MacSmoke {
    public static void main(String[] args) throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), "PNG", bytes);
        for (int attempt = 0; attempt < 2; attempt++) {
            var menu = new TrayMenu(List.of(new TrayMenuItem.Standard("show", "Show"),
                    TrayMenuItem.Separator.INSTANCE,
                    new TrayMenuItem.Submenu("more", "More", List.of(new TrayMenuItem.Standard("quit", "Quit", false)))));
            try (Tray tray = Tray.create(new TrayBuilder("Mac port test", bytes.toByteArray(), "Initial", menu))) {
                if (tray == null || !tray.isOpen()) {
                    throw new IllegalStateException("AppKit tray creation failed");
                }
                if (!tray.setTooltip("Changed") || !tray.setIcon(bytes.toByteArray()) || !tray.setMenu(menu)
                        || !tray.setMenu(null) || !tray.setMenu(menu)) {
                    throw new IllegalStateException("AppKit tray update failed");
                }
                tray.close();
                if (tray.isOpen() || tray.setTooltip("closed")) {
                    throw new IllegalStateException("AppKit tray shutdown failed");
                }
            }
        }
        System.out.println("AppKit: create, updates, menu, close and recreate passed");
    }
}
