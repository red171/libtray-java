package io.github.red171.libtray;

import java.util.List;
import java.util.Objects;

public sealed interface TrayMenuItem {
    String id();

    String label();

    boolean enabled();

    record Standard(String id, String label, boolean enabled) implements TrayMenuItem {
        public Standard {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
        }

        public Standard(String id, String label) {
            this(id, label, true);
        }
    }

    record Submenu(String id, String label, boolean enabled, List<TrayMenuItem> items)
            implements TrayMenuItem {
        public Submenu {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
            items = List.copyOf(items);
            if (items.isEmpty()) {
                throw new IllegalArgumentException("submenu must have at least one item");
            }
        }

        public Submenu(String id, String label, List<TrayMenuItem> items) {
            this(id, label, true, items);
        }
    }

    enum Separator implements TrayMenuItem {
        INSTANCE;

        @Override
        public String id() {
            return "---";
        }

        @Override
        public String label() {
            return "";
        }

        @Override
        public boolean enabled() {
            return false;
        }
    }
}
