package dev.stargenesis.design;

import dev.stargenesis.api.design.DesignMetadata;
import dev.stargenesis.api.design.DesignPermissions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/** Bounded, in-memory editor for one immutable blueprint. */
public final class BlueprintWorkspace {
    public static final int MAX_UNDO_STEPS = 32;
    private final Deque<Blueprint> history = new ArrayDeque<>();
    private Blueprint current;

    public BlueprintWorkspace(Blueprint initial) {
        current = Objects.requireNonNull(initial, "initial");
    }

    public Blueprint current() { return current; }
    public int undoDepth() { return history.size(); }

    public void permissions(DesignPermissions permissions) {
        Objects.requireNonNull(permissions, "permissions");
        edit(blueprint -> {
            DesignMetadata old = blueprint.metadata();
            if (old.permissions().equals(permissions)) return blueprint;
            DesignMetadata metadata = new DesignMetadata(old.id(), old.schemaVersion(), old.version(), old.author(),
                    old.source(), old.dependencies(), "", permissions);
            return new Blueprint(metadata, blueprint.components(), blueprint.connections());
        });
    }

    public void version(String version) {
        DesignMetadata.requireVersion(version);
        edit(blueprint -> {
            DesignMetadata old = blueprint.metadata();
            if (old.version().equals(version)) return blueprint;
            DesignMetadata metadata = new DesignMetadata(old.id(), old.schemaVersion(), version, old.author(),
                    old.source(), old.dependencies(), "", old.permissions());
            return new Blueprint(metadata, blueprint.components(), blueprint.connections());
        });
    }

    public void add(Blueprint.Component component, String connectTo) {
        Objects.requireNonNull(component, "component");
        edit(blueprint -> {
            if (blueprint.components().stream().anyMatch(existing -> existing.id().equals(component.id())))
                throw new IllegalArgumentException("Duplicate component ID");
            if (blueprint.components().stream().noneMatch(existing -> existing.id().equals(connectTo)))
                throw new IllegalArgumentException("Unknown connection endpoint");
            List<Blueprint.Component> components = new ArrayList<>(blueprint.components());
            components.add(component);
            List<Blueprint.Connection> connections = new ArrayList<>(blueprint.connections());
            connections.add(new Blueprint.Connection(component.id(), connectTo));
            return new Blueprint(blueprint.metadata(), components, connections);
        });
    }

    public void move(String id, int x, int y, int z) {
        updateComponent(id, component -> new Blueprint.Component(component.id(), component.type(), x, y, z, component.rotation()));
    }

    public void rotate(String id, int rotation) {
        updateComponent(id, component -> new Blueprint.Component(component.id(), component.type(),
                component.x(), component.y(), component.z(), rotation));
    }

    public void connect(String from, String to) {
        edit(blueprint -> {
            List<Blueprint.Connection> connections = new ArrayList<>(blueprint.connections());
            connections.add(new Blueprint.Connection(from, to));
            return new Blueprint(blueprint.metadata(), blueprint.components(), connections);
        });
    }

    public void disconnect(String first, String second) {
        edit(blueprint -> {
            List<Blueprint.Connection> connections = new ArrayList<>(blueprint.connections());
            boolean removed = connections.removeIf(connection ->
                    connection.from().equals(first) && connection.to().equals(second)
                            || connection.from().equals(second) && connection.to().equals(first));
            if (!removed) throw new IllegalArgumentException("Connection does not exist");
            return new Blueprint(blueprint.metadata(), blueprint.components(), connections);
        });
    }

    public void remove(String id) {
        edit(blueprint -> {
            List<Blueprint.Component> components = blueprint.components().stream()
                    .filter(component -> !component.id().equals(id)).toList();
            if (components.size() == blueprint.components().size()) throw new IllegalArgumentException("Unknown component");
            List<Blueprint.Connection> connections = blueprint.connections().stream()
                    .filter(connection -> !connection.from().equals(id) && !connection.to().equals(id)).toList();
            return new Blueprint(blueprint.metadata(), components, connections);
        });
    }

    public boolean undo() {
        if (history.isEmpty()) return false;
        current = history.removeLast();
        return true;
    }

    private void updateComponent(String id, java.util.function.UnaryOperator<Blueprint.Component> update) {
        edit(blueprint -> {
            List<Blueprint.Component> components = new ArrayList<>(blueprint.components());
            int index = -1;
            for (int i = 0; i < components.size(); i++) {
                if (components.get(i).id().equals(id)) { index = i; break; }
            }
            if (index < 0) throw new IllegalArgumentException("Unknown component");
            components.set(index, update.apply(components.get(index)));
            return new Blueprint(blueprint.metadata(), components, blueprint.connections());
        });
    }

    private void edit(java.util.function.UnaryOperator<Blueprint> operation) {
        Blueprint updated = operation.apply(current);
        if (updated.equals(current)) return;
        history.addLast(current);
        if (history.size() > MAX_UNDO_STEPS) history.removeFirst();
        current = updated;
    }
}
