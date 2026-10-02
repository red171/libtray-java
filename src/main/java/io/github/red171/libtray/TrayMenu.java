package io.github.red171.libtray;

import java.util.List;

public record TrayMenu(List<TrayMenuItem> items) {
    public TrayMenu {
        items = List.copyOf(items);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("menu must have at least one item; use null to remove it");
        }
    }
}
