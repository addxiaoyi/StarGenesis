package dev.stargenesis.design;

import dev.stargenesis.api.design.*;
import java.util.*;

/** Bounded immutable blueprint interchange contract. */
public record Blueprint(DesignMetadata metadata, List<Component> components,
                        List<Connection> connections) implements DesignDocument {
    public Blueprint {
        Checks.metadata(metadata);
        components = List.copyOf(components); connections = List.copyOf(connections);
        Checks.range(components.size(), 1, 128, "components");
        Checks.range(connections.size(), 0, 256, "connections");
        Checks.unique(components, Component::id, "component ID");
        Set<String> ids = new HashSet<>(); Set<String> positions = new HashSet<>();
        for (Component c : components) {
            ids.add(c.id());
            if (!positions.add(c.x() + ":" + c.y() + ":" + c.z())) Checks.fail("Overlapping components");
        }
        Set<String> pairs = new HashSet<>();
        for (Connection c : connections) {
            if (!ids.contains(c.from()) || !ids.contains(c.to())) Checks.fail("Unknown connection endpoint");
            String pair = c.from().compareTo(c.to()) < 0 ? c.from()+"/"+c.to() : c.to()+"/"+c.from();
            if (!pairs.add(pair)) Checks.fail("Duplicate connection");
        }
        Set<String> reached = new HashSet<>(); reached.add(components.getFirst().id());
        for (int pass = 0; pass < components.size(); pass++) {
            for (Connection c : connections) {
                if (reached.contains(c.from())) reached.add(c.to());
                if (reached.contains(c.to())) reached.add(c.from());
            }
        }
        if (reached.size() != components.size()) Checks.fail("Disconnected components");
    }
    @Override public DesignKind kind() { return DesignKind.BLUEPRINT; }
    public record Component(String id, String type, int x, int y, int z, int rotation) {
        public Component {
            Checks.token(id); Checks.token(type);
            Checks.range(x, -16, 16, "x"); Checks.range(y, -16, 16, "y"); Checks.range(z, -16, 16, "z");
            Checks.range(rotation, 0, 3, "rotation");
        }
    }
    public record Connection(String from, String to) {
        public Connection {
            Checks.token(from); Checks.token(to);
            if (from.equals(to)) Checks.fail("Self connection");
        }
    }
}
