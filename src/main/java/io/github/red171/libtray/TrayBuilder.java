package io.github.red171.libtray;

import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

public record TrayBuilder(String title, byte[] iconBytes, String tooltip, TrayMenu menu,
                          Integer maxIconSize, String linuxBusName) {
    private static final Pattern BUS_NAME = Pattern.compile(
            "[A-Za-z_-][A-Za-z0-9_-]*(\\.[A-Za-z_-][A-Za-z0-9_-]*)+");

    public TrayBuilder {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(iconBytes, "iconBytes");
        if (title.isBlank()) {
            throw new IllegalArgumentException("title must be non-blank");
        }
        if (iconBytes.length == 0) {
            throw new IllegalArgumentException("iconBytes must be non-empty");
        }
        if (maxIconSize != null && maxIconSize <= 0) {
            throw new IllegalArgumentException("maxIconSize must be positive or null");
        }
        if (linuxBusName != null
                && (linuxBusName.length() > 255 || !BUS_NAME.matcher(linuxBusName).matches())) {
            throw new IllegalArgumentException("linuxBusName must be a valid well-known D-Bus name");
        }
        iconBytes = iconBytes.clone();
    }

    public TrayBuilder(String title, byte[] iconBytes) {
        this(title, iconBytes, null, null, 256, null);
    }

    public TrayBuilder(String title, byte[] iconBytes, String tooltip, TrayMenu menu) {
        this(title, iconBytes, tooltip, menu, 256, null);
    }

    public TrayBuilder(String title, byte[] iconBytes, String tooltip, TrayMenu menu, Integer maxIconSize) {
        this(title, iconBytes, tooltip, menu, maxIconSize, null);
    }

    @Override
    public byte[] iconBytes() {
        return iconBytes.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TrayBuilder builder
                && title.equals(builder.title)
                && Arrays.equals(iconBytes, builder.iconBytes)
                && Objects.equals(tooltip, builder.tooltip)
                && Objects.equals(menu, builder.menu)
                && Objects.equals(maxIconSize, builder.maxIconSize)
                && Objects.equals(linuxBusName, builder.linuxBusName);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(title, tooltip, menu, maxIconSize, linuxBusName)
                + Arrays.hashCode(iconBytes);
    }
}
