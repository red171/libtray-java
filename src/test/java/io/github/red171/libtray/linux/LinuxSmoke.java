package io.github.red171.libtray.linux;

import io.github.red171.libtray.Tray;
import io.github.red171.libtray.TrayBuilder;
import io.github.red171.libtray.TrayMenu;
import io.github.red171.libtray.TrayMenuItem;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import javax.imageio.ImageIO;

public final class LinuxSmoke {
    public static void main(String[] args) throws Exception {
        String appId = System.getenv().getOrDefault("FLATPAK_ID", "io.github.red171.libtray");
        String name = appId + ".StatusNotifierItem.PortSmoke";
        var image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int row = 0; row < 16; row++) {
            for (int column = 0; column < 16; column++) {
                image.setRGB(column, row, 0xff28a745);
            }
        }
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", bytes);
        var menu = new TrayMenu(List.of(new TrayMenuItem.Standard("smoke", "libtray-java smoke test")));
        try (Tray tray = Tray.create(new TrayBuilder("libtray-java smoke", bytes.toByteArray(),
                "libtray-java smoke", menu, 256, name))) {
            if (tray == null) {
                throw new IllegalStateException("Linux tray creation failed");
            }
            tray.onEvent(System.out::println);
            System.out.println("READY " + name);
            Thread.sleep(10000);
        }
        System.out.println("CLOSED");
    }
}
