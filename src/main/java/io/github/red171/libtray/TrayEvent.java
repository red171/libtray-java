package io.github.red171.libtray;

import java.util.Objects;

public sealed interface TrayEvent {
    enum Activated implements TrayEvent {
        INSTANCE
    }

    enum MiddleActivated implements TrayEvent {
        INSTANCE
    }

    enum MenuRequested implements TrayEvent {
        INSTANCE
    }

    record MenuItemSelected(String id) implements TrayEvent {
        public MenuItemSelected {
            Objects.requireNonNull(id, "id");
        }
    }
}
