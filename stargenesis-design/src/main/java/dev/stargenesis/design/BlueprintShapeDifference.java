package dev.stargenesis.design;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

public record BlueprintShapeDifference(String beforeShapeHash, String afterShapeHash, List<String> addedComponents,
        List<String> removedComponents, List<ComponentChange> changedComponents, List<String> addedConnections,
        List<String> removedConnections) {
    public BlueprintShapeDifference {
        addedComponents = List.copyOf(addedComponents); removedComponents = List.copyOf(removedComponents);
        changedComponents = List.copyOf(changedComponents); addedConnections = List.copyOf(addedConnections);
        removedConnections = List.copyOf(removedConnections);
    }
    public static BlueprintShapeDifference between(Blueprint before, Blueprint after, String beforeHash, String afterHash) {
        Map<String, Blueprint.Component> oldComponents = index(before);
        Map<String, Blueprint.Component> newComponents = index(after);
        List<String> added = new ArrayList<>(), removed = new ArrayList<>();
        List<ComponentChange> changed = new ArrayList<>();
        for (String id : new TreeSet<>(newComponents.keySet())) {
            Blueprint.Component previous = oldComponents.get(id);
            if (previous == null) added.add(id);
            else if (!previous.equals(newComponents.get(id))) changed.add(new ComponentChange(id, previous, newComponents.get(id)));
        }
        for (String id : new TreeSet<>(oldComponents.keySet())) if (!newComponents.containsKey(id)) removed.add(id);
        TreeSet<String> oldConnections = connectionKeys(before), newConnections = connectionKeys(after);
        TreeSet<String> addedConnections = new TreeSet<>(newConnections); addedConnections.removeAll(oldConnections);
        TreeSet<String> removedConnections = new TreeSet<>(oldConnections); removedConnections.removeAll(newConnections);
        return new BlueprintShapeDifference(beforeHash, afterHash, added, removed, changed,
                List.copyOf(addedConnections), List.copyOf(removedConnections));
    }
    private static Map<String, Blueprint.Component> index(Blueprint blueprint) {
        Map<String, Blueprint.Component> result = new TreeMap<>();
        blueprint.components().forEach(component -> result.put(component.id(), component));
        return result;
    }
    private static TreeSet<String> connectionKeys(Blueprint blueprint) {
        TreeSet<String> result = new TreeSet<>();
        blueprint.connections().forEach(connection -> result.add(connectionKey(connection)));
        return result;
    }
    static String connectionKey(Blueprint.Connection connection) {
        return connection.from().compareTo(connection.to()) < 0 ? connection.from() + "->" + connection.to()
                : connection.to() + "->" + connection.from();
    }
    public record ComponentChange(String id, Blueprint.Component before, Blueprint.Component after) { }
}
