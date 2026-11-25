package dev.stargenesis.design;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.api.design.DesignMetadata;
import dev.stargenesis.api.design.DesignPermissions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Player-owned graph editor that only creates programs accepted by the typed compiler. */
public final class ProgramEditorSession {
    public static final int MAX_UNDO_STEPS = 32;
    public static final int MAX_EDITOR_NODES = 42;
    public static final int MAX_CHECKPOINT_BYTES = 1_048_576;
    private static final int CHECKPOINT_MAGIC = 0x53475031;
    private static final int CHECKPOINT_VERSION = 1;
    private static final List<String> SENSORS = List.of("resource_here", "inventory", "shared_stock", "x", "y", "tick",
            "blocked_here", "resource_north", "resource_east", "resource_south", "resource_west",
            "blocked_north", "blocked_east", "blocked_south", "blocked_west");

    private final UUID actor;
    private final ArrayDeque<Program> undo = new ArrayDeque<>();
    private Program saved;
    private Program draft;
    private String previewedDocument;
    private int parameterChoice;

    private ProgramEditorSession(UUID actor, Program saved, Program draft, List<Program> history,
                                 String previewedDocument) {
        this.actor = Objects.requireNonNull(actor, "actor");
        this.saved = Objects.requireNonNull(saved, "saved");
        this.draft = Objects.requireNonNull(draft, "draft");
        requireAuthorized(saved, actor);
        requireSameDocument(saved, draft);
        compile(draft);
        history.forEach(previous -> {
            requireSameDocument(saved, previous);
            compile(previous);
            undo.addLast(previous);
        });
        if (previewedDocument != null && !previewedDocument.equals(encodeSigned(draft, new DesignCodec())))
            throw new IllegalArgumentException("Program preview does not match editor draft");
        this.previewedDocument = previewedDocument;
    }

    public static ProgramEditorSession create(UUID actor) {
        Objects.requireNonNull(actor, "actor");
        String player = actor.toString().replace("-", "");
        DesignMetadata metadata = new DesignMetadata(new DesignId("genesis:player/" + player), 1, "1.0.0", actor,
                "genesis:program-editor", List.of(), "0".repeat(64), new DesignPermissions(false, false, Set.of()));
        Program initial = new Program(metadata, List.of(
                node("sense_resource", Program.Operation.SENSE, Program.ValueType.NONE, Program.ValueType.RESOURCE,
                        Map.of("field", "resource_here")),
                node("harvest", Program.Operation.HARVEST, Program.ValueType.RESOURCE, Program.ValueType.NONE, Map.of())),
                List.of(new Program.Edge("sense_resource", "harvest")), 64);
        return new ProgramEditorSession(actor, initial, initial, List.of(), null);
    }

    public static ProgramEditorSession edit(UUID actor, Program saved) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(saved, "saved");
        if (!saved.metadata().author().equals(actor))
            throw new SecurityException("Only the program owner may edit this saved program");
        if (saved.nodes().size() > MAX_EDITOR_NODES)
            throw new IllegalArgumentException("Saved program exceeds the player editor node limit");
        return new ProgramEditorSession(actor, saved, saved, List.of(), null);
    }

    public static ProgramEditorSession resume(UUID actor, byte[] encoded, DesignCodec codec) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(encoded, "encoded");
        Objects.requireNonNull(codec, "codec");
        if (encoded.length < 20 || encoded.length > MAX_CHECKPOINT_BYTES)
            throw new IllegalArgumentException("Program editor checkpoint exceeds its byte budget");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != CHECKPOINT_MAGIC || input.readInt() != CHECKPOINT_VERSION)
                throw new IllegalArgumentException("Unknown program editor checkpoint");
            UUID owner = new UUID(input.readLong(), input.readLong());
            if (!owner.equals(actor)) throw new SecurityException("Program editor checkpoint belongs to another player");
            int undoCount = input.readInt();
            if (undoCount < 0 || undoCount > MAX_UNDO_STEPS)
                throw new IllegalArgumentException("Invalid program editor undo history");
            int parameterChoice = input.readInt();
            if (parameterChoice < 0 || parameterChoice >= 24)
                throw new IllegalArgumentException("Invalid program editor parameter choice");
            Program saved = readProgram(input, codec);
            Program draft = readProgram(input, codec);
            String preview = input.readBoolean() ? readText(input, DesignCodec.MAX_DOCUMENT_BYTES) : null;
            List<Program> history = new ArrayList<>(undoCount);
            for (int index = 0; index < undoCount; index++) history.add(readProgram(input, codec));
            if (input.available() != 0) throw new IllegalArgumentException("Trailing program editor checkpoint data");
            ProgramEditorSession restored = new ProgramEditorSession(actor, saved, draft, history, preview);
            restored.parameterChoice = parameterChoice;
            return restored;
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid program editor checkpoint", failure);
        }
    }

    public UUID actor() { return actor; }
    public Program saved() { return saved; }
    public Program draft() { return draft; }
    public boolean hasUnsavedChanges() { return !draft.equals(saved); }
    public boolean canUndo() { return !undo.isEmpty(); }
    public int parameterChoice() { return parameterChoice; }

    public int inputCount(Program.Operation operation) {
        return switch (operation) {
            case SENSE, CONSTANT, VECTOR, WAIT -> 0;
            case AND, OR, XOR, EQUAL, ADD, SUBTRACT, MULTIPLY, DIVIDE, REMAINDER, MIN, MAX -> 2;
            case NOT, COMPARE, MOVE, HARVEST, DEPOSIT, ABS, NEGATE, CLAMP, SELECT, VECTOR_SELECT -> 1;
        };
    }

    public List<Program.Operation> operations() { return List.of(Program.Operation.values()); }

    public void cycleParameters() {
        parameterChoice = (parameterChoice + 1) % 24;
        previewedDocument = null;
    }

    public Program.Node append(Program.Operation operation, List<String> inputIds) {
        requireEditPermission();
        Objects.requireNonNull(operation, "operation");
        inputIds = List.copyOf(inputIds);
        if (draft.nodes().size() >= Math.min(MAX_EDITOR_NODES, draft.maxInstructionsPerStep()))
            throw new IllegalStateException("Program editor node budget exhausted");
        NodeSpec spec = spec(operation, parameterChoice);
        if (inputIds.size() != inputCount(operation))
            throw new IllegalArgumentException("Select the exact number of typed input nodes for " + operation);
        Map<String, Program.Node> existing = index(draft);
        List<Program.Edge> edges = new ArrayList<>(draft.edges());
        for (String inputId : inputIds) {
            Program.Node input = existing.get(inputId);
            if (input == null || input.outputType() != spec.inputType)
                throw new IllegalArgumentException("Selected node output does not match " + operation + " input type");
        }
        String id = nextNodeId(existing.keySet());
        Program.Node added = node(id, operation, spec.inputType, spec.outputType, spec.parameters);
        inputIds.forEach(inputId -> edges.add(new Program.Edge(inputId, id)));
        accept(new Program(draft.metadata(), append(draft.nodes(), added), edges, draft.maxInstructionsPerStep()));
        return added;
    }

    public void remove(String nodeId) {
        requireEditPermission();
        Map<String, Program.Node> existing = index(draft);
        if (!existing.containsKey(nodeId)) throw new IllegalArgumentException("Unknown program node " + nodeId);
        Set<String> removed = new LinkedHashSet<>();
        removed.add(nodeId);
        boolean changed;
        do {
            changed = false;
            for (Program.Edge edge : draft.edges()) {
                if (removed.contains(edge.from()) && removed.add(edge.to())) changed = true;
            }
        } while (changed);
        if (removed.size() == draft.nodes().size())
            throw new IllegalArgumentException("The editor cannot remove every node");
        List<Program.Node> nodes = draft.nodes().stream().filter(node -> !removed.contains(node.id())).toList();
        List<Program.Edge> edges = draft.edges().stream()
                .filter(edge -> !removed.contains(edge.from()) && !removed.contains(edge.to())).toList();
        accept(new Program(draft.metadata(), nodes, edges, draft.maxInstructionsPerStep()));
    }

    public boolean undo() {
        requireEditPermission();
        if (undo.isEmpty()) return false;
        draft = undo.pop();
        previewedDocument = null;
        return true;
    }

    public TypedProgram preview() {
        requireEditPermission();
        TypedProgram compiled = compile(draft);
        previewedDocument = encodeSigned(draft, new DesignCodec());
        return compiled;
    }

    public String save(DesignCodec codec) {
        requireEditPermission();
        Objects.requireNonNull(codec, "codec");
        compile(draft);
        String encoded = encodeSigned(draft, codec);
        if (!encoded.equals(previewedDocument)) throw new IllegalStateException("Compile preview the current program before saving");
        saved = (Program) codec.load(encoded);
        draft = saved;
        undo.clear();
        previewedDocument = null;
        return encoded;
    }

    public byte[] encodeCheckpoint(DesignCodec codec) {
        requireEditPermission();
        Objects.requireNonNull(codec, "codec");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(CHECKPOINT_MAGIC);
                output.writeInt(CHECKPOINT_VERSION);
                output.writeLong(actor.getMostSignificantBits());
                output.writeLong(actor.getLeastSignificantBits());
                output.writeInt(undo.size());
                output.writeInt(parameterChoice);
                writeText(output, encodeSigned(saved, codec));
                writeText(output, encodeSigned(draft, codec));
                output.writeBoolean(previewedDocument != null);
                if (previewedDocument != null) writeText(output, previewedDocument);
                for (Program previous : undo) writeText(output, encodeSigned(previous, codec));
            }
            byte[] checkpoint = bytes.toByteArray();
            if (checkpoint.length > MAX_CHECKPOINT_BYTES)
                throw new IllegalStateException("Program editor checkpoint exceeds its byte budget");
            return checkpoint;
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot encode program editor checkpoint", impossible);
        }
    }

    private static NodeSpec spec(Program.Operation operation, int choice) {
        return switch (operation) {
            case SENSE -> {
                String field = SENSORS.get(Math.floorMod(choice, SENSORS.size()));
                Program.ValueType output = switch (field) {
                    case "x", "y", "tick" -> Program.ValueType.INTEGER;
                    case "blocked_here", "blocked_north", "blocked_east", "blocked_south", "blocked_west" -> Program.ValueType.BOOLEAN;
                    default -> Program.ValueType.RESOURCE;
                };
                yield new NodeSpec(Program.ValueType.NONE, output, Map.of("field", field));
            }
            case CONSTANT -> switch (Math.floorMod(choice, 3)) {
                case 0 -> new NodeSpec(Program.ValueType.NONE, Program.ValueType.INTEGER, Map.of("value", "1"));
                case 1 -> new NodeSpec(Program.ValueType.NONE, Program.ValueType.RESOURCE, Map.of("value", "1"));
                default -> new NodeSpec(Program.ValueType.NONE, Program.ValueType.BOOLEAN, Map.of("value", "true"));
            };
            case COMPARE -> new NodeSpec(Program.ValueType.INTEGER, Program.ValueType.BOOLEAN,
                    Map.of("operator", List.of("EQ", "NE", "LT", "LE", "GT", "GE").get(Math.floorMod(choice, 6)), "value", "1"));
            case AND, OR, XOR -> new NodeSpec(Program.ValueType.BOOLEAN, Program.ValueType.BOOLEAN, Map.of());
            case NOT -> new NodeSpec(Program.ValueType.BOOLEAN, Program.ValueType.BOOLEAN, Map.of());
            case EQUAL -> new NodeSpec(Program.ValueType.INTEGER, Program.ValueType.BOOLEAN, Map.of());
            case VECTOR -> new NodeSpec(Program.ValueType.NONE, Program.ValueType.VECTOR,
                    Map.of("dx", List.of("0", "1", "0", "-1").get(Math.floorMod(choice, 4)),
                            "dy", List.of("-1", "0", "1", "0").get(Math.floorMod(choice, 4))));
            case ADD, SUBTRACT, MULTIPLY, DIVIDE, REMAINDER, MIN, MAX ->
                    new NodeSpec(Program.ValueType.INTEGER, Program.ValueType.INTEGER, Map.of());
            case ABS, NEGATE -> new NodeSpec(Program.ValueType.INTEGER, Program.ValueType.INTEGER, Map.of());
            case CLAMP -> new NodeSpec(Program.ValueType.INTEGER, Program.ValueType.INTEGER, Map.of("min", "0", "max", "64"));
            case SELECT -> new NodeSpec(Program.ValueType.BOOLEAN, Program.ValueType.INTEGER,
                    Map.of("true_value", "1", "false_value", "0"));
            case VECTOR_SELECT -> new NodeSpec(Program.ValueType.BOOLEAN, Program.ValueType.VECTOR,
                    Map.of("true_dx", "1", "true_dy", "0", "false_dx", "0", "false_dy", "1"));
            case MOVE -> new NodeSpec(Program.ValueType.VECTOR, Program.ValueType.NONE, Map.of());
            case HARVEST, DEPOSIT -> new NodeSpec(Program.ValueType.RESOURCE, Program.ValueType.NONE, Map.of());
            case WAIT -> new NodeSpec(Program.ValueType.NONE, Program.ValueType.NONE, Map.of());
        };
    }

    private void accept(Program candidate) {
        if (candidate.equals(draft)) return;
        if (undo.size() == MAX_UNDO_STEPS) undo.removeLast();
        undo.push(draft);
        draft = candidate;
        previewedDocument = null;
    }

    private void requireEditPermission() { requireAuthorized(draft, actor); }

    private static void requireAuthorized(Program program, UUID actor) {
        if (!program.metadata().permissions().mayEdit(actor, program.metadata().author()))
            throw new SecurityException("Player is not authorized to edit this program");
    }

    private static TypedProgram compile(Program program) { return new ProgramCompiler().compile(program); }

    private static String encodeSigned(Program program, DesignCodec codec) { return codec.writeEdited(program, program.metadata().author()); }

    private static Program readProgram(DataInputStream input, DesignCodec codec) throws IOException {
        var document = codec.load(readText(input, DesignCodec.MAX_DOCUMENT_BYTES));
        if (!(document instanceof Program program)) throw new IllegalArgumentException("Checkpoint contains a non-program design");
        return program;
    }

    private static String readText(DataInputStream input, int maximumBytes) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximumBytes || length > input.available())
            throw new IllegalArgumentException("Invalid program editor checkpoint field length");
        byte[] bytes = input.readNBytes(length);
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(bytes, text.getBytes(StandardCharsets.UTF_8)))
            throw new IllegalArgumentException("Invalid UTF-8 in program editor checkpoint");
        return text;
    }

    private static void writeText(DataOutputStream output, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static Map<String, Program.Node> index(Program program) {
        Map<String, Program.Node> result = new LinkedHashMap<>();
        program.nodes().forEach(node -> result.put(node.id(), node));
        return result;
    }

    private static String nextNodeId(Set<String> existing) {
        int index = 1;
        while (existing.contains("node_" + index)) index++;
        return "node_" + index;
    }

    private static Program.Node node(String id, Program.Operation operation, Program.ValueType input,
                                     Program.ValueType output, Map<String, String> parameters) {
        return new Program.Node(id, operation, input, output, parameters);
    }

    private static List<Program.Node> append(List<Program.Node> source, Program.Node value) {
        List<Program.Node> result = new ArrayList<>(source);
        result.add(value);
        return List.copyOf(result);
    }

    private static void requireSameDocument(Program first, Program second) {
        DesignMetadata expected = first.metadata();
        DesignMetadata actual = second.metadata();
        if (!expected.id().equals(actual.id()) || !expected.author().equals(actual.author())
                || !expected.permissions().equals(actual.permissions()) || !expected.dependencies().equals(actual.dependencies())
                || !expected.source().equals(actual.source()) || !expected.version().equals(actual.version()))
            throw new IllegalArgumentException("Checkpoint programs do not belong to the same edit session");
    }

    private record NodeSpec(Program.ValueType inputType, Program.ValueType outputType, Map<String, String> parameters) { }
}
