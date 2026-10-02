package io.github.red171.libtray.linux;

import io.github.red171.libtray.TrayMenu;
import io.github.red171.libtray.TrayMenuItem;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

final class DBusMenuLayout {
    private final Map<Integer, Node> nodes;
    private final Map<Integer, List<Integer>> children;
    private final Set<Integer> selectable;

    DBusMenuLayout(TrayMenu menu) {
        var nodeMap = new LinkedHashMap<Integer, Node>();
        var childMap = new LinkedHashMap<Integer, List<Integer>>();
        nodeMap.put(0, new Node(0, "<root>", "", false, Kind.SUBMENU));
        var selectableIds = new HashSet<Integer>();
        walk(menu.items(), 0, nodeMap, childMap, selectableIds, true);
        childMap.replaceAll((parent, ids) -> List.copyOf(ids));
        nodes = Collections.unmodifiableMap(nodeMap);
        children = Collections.unmodifiableMap(childMap);
        selectable = Set.copyOf(selectableIds);
    }

    private void walk(List<TrayMenuItem> items, int parent, Map<Integer, Node> nodeMap,
                      Map<Integer, List<Integer>> childMap, Set<Integer> selectableIds, boolean parentEnabled) {
        for (TrayMenuItem item : items) {
            int id = nodeMap.size();
            Kind kind = switch (item) {
                case TrayMenuItem.Standard standard -> Kind.STANDARD;
                case TrayMenuItem.Submenu submenu -> Kind.SUBMENU;
                case TrayMenuItem.Separator separator -> Kind.SEPARATOR;
            };
            nodeMap.put(id, new Node(id, item.id(), item.label(), item.enabled(), kind));
            childMap.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(id);
            if (kind == Kind.STANDARD && item.enabled() && parentEnabled) {
                selectableIds.add(id);
            }
            if (item instanceof TrayMenuItem.Submenu submenu) {
                walk(submenu.items(), id, nodeMap, childMap, selectableIds, parentEnabled && item.enabled());
            }
        }
    }

    Node nodeOf(int id) {
        return nodes.get(id);
    }

    boolean selectable(int id) {
        return selectable.contains(id);
    }

    List<Integer> nodeIds() {
        return List.copyOf(nodes.keySet());
    }

    List<Integer> childrenOf(int parent) {
        return children.getOrDefault(parent, List.of());
    }

    Map<String, PropertyValue> propertiesOf(int id) {
        Node node = nodes.get(id);
        if (node == null) {
            return Map.of();
        }
        var properties = new LinkedHashMap<String, PropertyValue>();
        switch (node.kind()) {
            case STANDARD -> addLabel(properties, node);
            case SUBMENU -> {
                if (id != 0) {
                    addLabel(properties, node);
                }
                properties.put("children-display", new PropertyValue.Str("submenu"));
            }
            case SEPARATOR -> properties.put("type", new PropertyValue.Str("separator"));
        }
        return Collections.unmodifiableMap(properties);
    }

    private void addLabel(Map<String, PropertyValue> properties, Node node) {
        properties.put("label", new PropertyValue.Str(node.label()));
        if (!node.enabled()) {
            properties.put("enabled", new PropertyValue.Bool(false));
        }
    }

    record Node(int id, String originalId, String label, boolean enabled, Kind kind) {
    }

    enum Kind {
        STANDARD, SUBMENU, SEPARATOR
    }

    sealed interface PropertyValue {
        record Str(String value) implements PropertyValue {
        }

        record Bool(boolean value) implements PropertyValue {
        }
    }
}
