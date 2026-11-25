package dev.stargenesis.design;

import dev.stargenesis.api.design.DesignDependency;
import dev.stargenesis.api.design.DesignDocument;
import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.api.design.DesignMetadata;
import dev.stargenesis.api.design.DesignPermissions;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class BlueprintEditorSession {
    public static final int MAX_UNDO_STEPS = 32;
    private static final int CHECKPOINT_MAGIC = 0x53474531;
    private static final int MAX_CHECKPOINT_BYTES = 1_048_576;
    private final UUID actor;
    private final ModuleCatalog catalog;
    private final BlueprintAnalyzer analyzer;
    private final ArrayDeque<Blueprint> undo = new ArrayDeque<>();
    private Blueprint saved;
    private Blueprint draft;
    private String previewedShapeHash;

    private BlueprintEditorSession(UUID actor, Blueprint source, ModuleCatalog catalog, BlueprintBudget budget) {
        this.actor = java.util.Objects.requireNonNull(actor, "actor");
        this.catalog = java.util.Objects.requireNonNull(catalog, "catalog");
        analyzer = new BlueprintAnalyzer(catalog, java.util.Objects.requireNonNull(budget, "budget"));
        if (!source.metadata().permissions().mayEdit(actor, source.metadata().author()))
            throw new SecurityException("Actor is not authorized to edit this blueprint");
        analyzer.requireValid(source); saved = source; draft = source;
    }
    public static BlueprintEditorSession cloneFor(UUID actor, Blueprint source, ModuleCatalog catalog, BlueprintBudget budget) {
        java.util.Objects.requireNonNull(actor, "actor");
        boolean canRead = source.metadata().author().equals(actor) || source.metadata().permissions().publicRead()
                || source.metadata().permissions().editors().contains(actor);
        if (!canRead || !source.metadata().permissions().allowClone())
            throw new SecurityException("Blueprint is not available for cloning");
        DesignMetadata metadata = source.metadata();
        String path = "player/" + actor.toString().replace("-", "");
        DesignMetadata copy = new DesignMetadata(new DesignId("genesis:" + path), 1, "1.0.0", actor,
                "genesis:blueprint-editor", List.of(new DesignDependency(metadata.id(), metadata.version(), metadata.hash())), "",
                new DesignPermissions(false, false, Set.of()));
        return new BlueprintEditorSession(actor, new Blueprint(copy, source.components(), source.connections()), catalog, budget);
    }
    public static BlueprintEditorSession edit(UUID actor, Blueprint source, ModuleCatalog catalog, BlueprintBudget budget) {
        return new BlueprintEditorSession(actor, source, catalog, budget);
    }
    public static BlueprintEditorSession restoreCheckpoint(UUID actor, byte[] encoded, DesignCodec codec,
                                                            ModuleCatalog catalog, BlueprintBudget budget) {
        java.util.Objects.requireNonNull(actor, "actor");
        java.util.Objects.requireNonNull(encoded, "encoded");
        java.util.Objects.requireNonNull(codec, "codec");
        if (encoded.length < Integer.BYTES || encoded.length > MAX_CHECKPOINT_BYTES)
            throw new IllegalArgumentException("Blueprint editor checkpoint exceeds its byte budget");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != CHECKPOINT_MAGIC) throw new IllegalArgumentException("Unknown blueprint editor checkpoint");
            UUID storedActor = UUID.fromString(readText(input, 36));
            if (!actor.equals(storedActor)) throw new SecurityException("Blueprint editor checkpoint belongs to another player");
            int undoCount = input.readInt();
            if (undoCount < 0 || undoCount > MAX_UNDO_STEPS) throw new IllegalArgumentException("Invalid blueprint editor undo history");
            Blueprint saved = readBlueprint(input, codec);
            Blueprint draft = readBlueprint(input, codec);
            String previewHash = input.readBoolean() ? readText(input, 64) : null;
            List<Blueprint> history = new ArrayList<>(undoCount);
            for (int index = 0; index < undoCount; index++) history.add(readBlueprint(input, codec));
            if (input.available() != 0) throw new IllegalArgumentException("Trailing blueprint editor checkpoint data");
            requireSameDocument(saved, draft);
            history.forEach(candidate -> requireSameDocument(saved, candidate));
            BlueprintEditorSession restored = new BlueprintEditorSession(actor, saved, catalog, budget);
            restored.draft = draft;
            for (Blueprint previous : history) restored.undo.addLast(previous);
            if (previewHash != null && !restored.analyzer.analyze(draft).shapeHash().equals(previewHash))
                throw new IllegalArgumentException("Preview does not match checkpoint draft");
            restored.previewedShapeHash = previewHash;
            return restored;
        } catch (IOException | IllegalArgumentException failure) {
            if (failure instanceof IllegalArgumentException argument) throw argument;
            throw new IllegalArgumentException("Invalid blueprint editor checkpoint", failure);
        }
    }
    public UUID actor() { return actor; }
    public Blueprint saved() { return saved; }
    public Blueprint draft() { return draft; }
    public boolean hasUnsavedChanges() { return !draft.equals(saved); }
    public boolean canUndo() { return !undo.isEmpty(); }
    public BlueprintPreview preview() {
        requireEditPermission();
        BlueprintPreview preview = analyzer.preview(saved, draft);
        previewedShapeHash = preview.after().shapeHash();
        return preview;
    }
    public Blueprint.Component place(String type, int x, int y, int z) {
        requireEditPermission(); ModuleDefinition definition = catalog.require(type);
        if (definition.isCore()) throw new IllegalArgumentException("The core cannot be duplicated");
        if (draft.components().stream().anyMatch(c -> c.x() == x && c.y() == y && c.z() == z))
            throw new IllegalArgumentException("A module already occupies that position");
        Blueprint.Component placed = new Blueprint.Component(nextComponentId(), type, x, y, z, 0);
        Blueprint.Component nearest = draft.components().stream().min(Comparator.comparingInt(c -> distance(c, placed))).orElseThrow();
        accept(replace(draft, append(draft.components(), placed), append(draft.connections(), new Blueprint.Connection(nearest.id(), placed.id()))));
        return placed;
    }
    public void remove(String componentId) {
        requireEditPermission(); Blueprint.Component component = requireComponent(componentId);
        if (catalog.require(component.type()).isCore()) throw new IllegalArgumentException("The core cannot be removed");
        List<Blueprint.Component> components = draft.components().stream().filter(c -> !c.id().equals(componentId)).toList();
        List<Blueprint.Connection> connections = draft.connections().stream()
                .filter(c -> !c.from().equals(componentId) && !c.to().equals(componentId)).toList();
        accept(replace(draft, components, connections));
    }
    public void move(String componentId, int x, int y, int z) {
        requireEditPermission(); Blueprint.Component component = requireComponent(componentId);
        if (draft.components().stream().anyMatch(c -> !c.id().equals(componentId) && c.x() == x && c.y() == y && c.z() == z))
            throw new IllegalArgumentException("A module already occupies that position");
        Blueprint.Component moved = new Blueprint.Component(component.id(), component.type(), x, y, z, component.rotation());
        accept(replace(draft, replaceComponent(componentId, moved), draft.connections()));
    }
    public void rotate(String componentId) {
        requireEditPermission(); Blueprint.Component component = requireComponent(componentId);
        Blueprint.Component rotated = new Blueprint.Component(component.id(), component.type(), component.x(), component.y(),
                component.z(), (component.rotation() + 1) % 4);
        accept(replace(draft, replaceComponent(componentId, rotated), draft.connections()));
    }
    public void connect(String firstId, String secondId) {
        requireEditPermission(); requireComponent(firstId); requireComponent(secondId);
        accept(replace(draft, draft.components(), append(draft.connections(), new Blueprint.Connection(firstId, secondId))));
    }
    public void disconnect(String firstId, String secondId) {
        requireEditPermission();
        List<Blueprint.Connection> connections = draft.connections().stream()
                .filter(c -> !(c.from().equals(firstId) && c.to().equals(secondId))
                        && !(c.from().equals(secondId) && c.to().equals(firstId))).toList();
        accept(replace(draft, draft.components(), connections));
    }
    public boolean undo() {
        requireEditPermission();
        if (undo.isEmpty()) return false;
        draft = undo.pop();
        previewedShapeHash = null;
        return true;
    }
    public String save(DesignCodec codec) {
        requireEditPermission(); analyzer.requireValid(draft);
        if (!analyzer.analyze(draft).shapeHash().equals(previewedShapeHash))
            throw new IllegalStateException("Preview the current blueprint before saving");
        String json = java.util.Objects.requireNonNull(codec, "codec").writeEdited(draft, actor);
        saved = (Blueprint) codec.load(json); draft = saved; undo.clear(); previewedShapeHash = null; return json;
    }
    public byte[] encodeCheckpoint(DesignCodec codec) {
        requireEditPermission();
        java.util.Objects.requireNonNull(codec, "codec");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(CHECKPOINT_MAGIC);
                writeText(output, actor.toString());
                output.writeInt(undo.size());
                writeBlueprint(output, saved, actor, codec);
                writeBlueprint(output, draft, actor, codec);
                output.writeBoolean(previewedShapeHash != null);
                if (previewedShapeHash != null) writeText(output, previewedShapeHash);
                for (Blueprint previous : undo) writeBlueprint(output, previous, actor, codec);
            }
            byte[] checkpoint = bytes.toByteArray();
            if (checkpoint.length > MAX_CHECKPOINT_BYTES)
                throw new IllegalStateException("Blueprint editor checkpoint exceeds its byte budget");
            return checkpoint;
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot encode blueprint editor checkpoint", impossible);
        }
    }
    private void requireEditPermission() {
        if (!draft.metadata().permissions().mayEdit(actor, draft.metadata().author()))
            throw new SecurityException("Actor is not authorized to edit this blueprint");
    }
    private void accept(Blueprint candidate) {
        if (candidate.equals(draft)) return;
        if (undo.size() == MAX_UNDO_STEPS) undo.removeLast();
        undo.push(draft); draft = candidate; previewedShapeHash = null;
    }
    private String nextComponentId() {
        Set<String> existing = new HashSet<>(); draft.components().forEach(c -> existing.add(c.id()));
        int index = 1; while (existing.contains("module_" + index)) index++; return "module_" + index;
    }
    private Blueprint.Component requireComponent(String id) {
        return draft.components().stream().filter(c -> c.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown component " + id));
    }
    private List<Blueprint.Component> replaceComponent(String id, Blueprint.Component replacement) {
        return draft.components().stream().map(c -> c.id().equals(id) ? replacement : c).toList();
    }
    private static Blueprint replace(Blueprint source, List<Blueprint.Component> components, List<Blueprint.Connection> connections) {
        return new Blueprint(source.metadata(), components, connections);
    }
    private static <T> List<T> append(List<T> values, T value) {
        List<T> result = new ArrayList<>(values); result.add(value); return List.copyOf(result);
    }
    private static int distance(Blueprint.Component first, Blueprint.Component second) {
        return Math.abs(first.x() - second.x()) + Math.abs(first.y() - second.y()) + Math.abs(first.z() - second.z());
    }
    private static Blueprint readBlueprint(DataInputStream input, DesignCodec codec) throws IOException {
        DesignDocument document = codec.load(readText(input, DesignCodec.MAX_DOCUMENT_BYTES));
        if (!(document instanceof Blueprint blueprint)) throw new IllegalArgumentException("Checkpoint contains a non-blueprint design");
        return blueprint;
    }
    private static void writeBlueprint(DataOutputStream output, Blueprint blueprint, UUID actor, DesignCodec codec) throws IOException {
        writeText(output, codec.writeEdited(blueprint, actor));
    }
    private static String readText(DataInputStream input, int maximumBytes) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximumBytes || length > input.available())
            throw new IllegalArgumentException("Invalid blueprint editor checkpoint field length");
        byte[] encoded = input.readNBytes(length);
        String value = new String(encoded, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(encoded, value.getBytes(StandardCharsets.UTF_8)))
            throw new IllegalArgumentException("Invalid UTF-8 in blueprint editor checkpoint");
        return value;
    }
    private static void writeText(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }
    private static void requireSameDocument(Blueprint saved, Blueprint candidate) {
        DesignMetadata expected = saved.metadata();
        DesignMetadata actual = candidate.metadata();
        if (!expected.id().equals(actual.id()) || !expected.author().equals(actual.author())
                || !expected.permissions().equals(actual.permissions()) || !expected.dependencies().equals(actual.dependencies())
                || !expected.source().equals(actual.source()) || !expected.version().equals(actual.version()))
            throw new IllegalArgumentException("Checkpoint designs do not belong to the same edit session");
    }
}
