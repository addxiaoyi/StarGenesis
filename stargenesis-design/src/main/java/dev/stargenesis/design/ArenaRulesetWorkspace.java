package dev.stargenesis.design;

import dev.stargenesis.api.design.DesignDependency;
import dev.stargenesis.api.design.DesignMetadata;
import dev.stargenesis.api.design.DesignPermissions;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Bounded immutable editor for the numeric ArenaRuleset envelope. */
public final class ArenaRulesetWorkspace {
    public static final int MAX_UNDO_STEPS = 32;
    private final ArrayDeque<ArenaRuleset> undo = new ArrayDeque<>();
    private ArenaRuleset current;

    public ArenaRulesetWorkspace(ArenaRuleset initial) {
        current = Objects.requireNonNull(initial, "initial");
        new DesignCodec().write(initial);
    }

    public ArenaRuleset current() { return current; }
    public int undoDepth() { return undo.size(); }

    public void resize(int width, int height) {
        update(value -> new ArenaRuleset(unsigned(value.metadata()), width, height,
                value.durationSteps(), value.unitBudget()));
    }

    public void duration(long durationSteps) {
        update(value -> new ArenaRuleset(unsigned(value.metadata()), value.width(), value.height(),
                durationSteps, value.unitBudget()));
    }

    public void unitBudget(int unitBudget) {
        update(value -> new ArenaRuleset(unsigned(value.metadata()), value.width(), value.height(),
                value.durationSteps(), unitBudget));
    }

    public void version(String version) {
        DesignMetadata.requireVersion(version);
        update(value -> {
            DesignMetadata metadata = unsigned(value.metadata());
            metadata = new DesignMetadata(metadata.id(), metadata.schemaVersion(), version, metadata.author(),
                    metadata.source(), metadata.dependencies(), "", metadata.permissions());
            return new ArenaRuleset(metadata, value.width(), value.height(), value.durationSteps(), value.unitBudget());
        });
    }

    public void publicRead(boolean allowed) {
        update(value -> withPermissions(value, new DesignPermissions(allowed,
                value.metadata().permissions().allowClone(), value.metadata().permissions().editors())));
    }

    public void allowClone(boolean allowed) {
        update(value -> withPermissions(value, new DesignPermissions(value.metadata().permissions().publicRead(),
                allowed, value.metadata().permissions().editors())));
    }

    public void editor(UUID editor, boolean allowed) {
        Objects.requireNonNull(editor, "editor");
        update(value -> {
            Set<UUID> editors = new HashSet<>(value.metadata().permissions().editors());
            if (allowed) {
                if (!editors.contains(editor) && editors.size() >= 64)
                    throw new IllegalStateException("Arena editor budget exhausted");
                editors.add(editor);
            } else editors.remove(editor);
            return withPermissions(value, new DesignPermissions(value.metadata().permissions().publicRead(),
                    value.metadata().permissions().allowClone(), editors));
        });
    }

    public void addDependency(DesignDependency dependency) {
        Objects.requireNonNull(dependency, "dependency");
        if (current.metadata().dependencies().stream().anyMatch(item -> item.id().equals(dependency.id())))
            throw new IllegalArgumentException("Arena dependency ID already exists");
        if (current.metadata().dependencies().size() >= 64) throw new IllegalStateException("Arena dependency budget exhausted");
        update(value -> {
            List<DesignDependency> dependencies = new ArrayList<>(value.metadata().dependencies());
            dependencies.add(dependency);
            dependencies.sort(Comparator.comparing(item -> item.id().value()));
            DesignMetadata metadata = unsigned(value.metadata());
            metadata = new DesignMetadata(metadata.id(), metadata.schemaVersion(), metadata.version(), metadata.author(),
                    metadata.source(), dependencies, "", metadata.permissions());
            return new ArenaRuleset(metadata, value.width(), value.height(), value.durationSteps(), value.unitBudget());
        });
    }

    public void removeDependency(String id) {
        if (current.metadata().dependencies().stream().noneMatch(item -> item.id().value().equals(id)))
            throw new IllegalArgumentException("Arena dependency does not exist");
        update(value -> {
            List<DesignDependency> dependencies = value.metadata().dependencies().stream()
                    .filter(item -> !item.id().value().equals(id)).toList();
            DesignMetadata metadata = unsigned(value.metadata());
            metadata = new DesignMetadata(metadata.id(), metadata.schemaVersion(), metadata.version(), metadata.author(),
                    metadata.source(), dependencies, "", metadata.permissions());
            return new ArenaRuleset(metadata, value.width(), value.height(), value.durationSteps(), value.unitBudget());
        });
    }

    public boolean undo() {
        ArenaRuleset previous = undo.pollLast();
        if (previous == null) return false;
        current = previous;
        return true;
    }

    private void update(java.util.function.Function<ArenaRuleset, ArenaRuleset> change) {
        ArenaRuleset next = change.apply(current);
        if (next.equals(current)) return;
        if (undo.size() == MAX_UNDO_STEPS) undo.removeFirst();
        undo.addLast(current);
        current = next;
    }

    private static DesignMetadata unsigned(DesignMetadata metadata) {
        return new DesignMetadata(metadata.id(), metadata.schemaVersion(), metadata.version(), metadata.author(),
                metadata.source(), metadata.dependencies(), "", metadata.permissions());
    }

    private ArenaRuleset withPermissions(ArenaRuleset value, DesignPermissions permissions) {
        DesignMetadata metadata = unsigned(value.metadata());
        metadata = new DesignMetadata(metadata.id(), metadata.schemaVersion(), metadata.version(), metadata.author(),
                metadata.source(), metadata.dependencies(), "", permissions);
        return new ArenaRuleset(metadata, value.width(), value.height(), value.durationSteps(), value.unitBudget());
    }
}
