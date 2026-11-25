package dev.stargenesis.design;

import dev.stargenesis.api.design.DesignDependency;
import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.api.design.DesignMetadata;
import dev.stargenesis.api.design.DesignPermissions;
import dev.stargenesis.design.ArenaRuleset.Cell;
import dev.stargenesis.design.ArenaRuleset.Combination;
import dev.stargenesis.design.ArenaRuleset.Condition;
import dev.stargenesis.design.ArenaRuleset.Layout;
import dev.stargenesis.design.ArenaRuleset.Metric;
import dev.stargenesis.design.ArenaRuleset.Team;
import dev.stargenesis.design.ArenaRuleset.Victory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Authorized editor for arena rulesets and their versioned layouts. */
public final class ArenaRulesetEditorSession {
    public static final int MAX_UNDO_STEPS = 32;
    public static final int MAX_CHECKPOINT_BYTES = 1_048_576;
    private static final int CHECKPOINT_MAGIC = 0x53474131;
    private static final int CHECKPOINT_VERSION = 1;

    private final UUID actor;
    private final ArenaRulesetValidator validator = new ArenaRulesetValidator();
    private final ArrayDeque<ArenaRuleset> undo = new ArrayDeque<>();
    private ArenaRuleset saved;
    private ArenaRuleset draft;
    private String previewedDocument;

    private ArenaRulesetEditorSession(UUID actor, ArenaRuleset saved, ArenaRuleset draft,
                                     List<ArenaRuleset> history, String previewedDocument) {
        this.actor = Objects.requireNonNull(actor, "actor");
        this.saved = Objects.requireNonNull(saved, "saved");
        this.draft = Objects.requireNonNull(draft, "draft");
        requireSameDesign(saved, draft);
        requireAuthorized(saved, actor);
        history.forEach(previous -> {
            requireSameDesign(saved, previous);
            undo.addLast(previous);
        });
        if (previewedDocument != null && !previewedDocument.equals(encodeSigned(draft, new DesignCodec(), actor)))
            throw new IllegalArgumentException("Arena preview does not match editor draft");
        this.previewedDocument = previewedDocument;
    }

    public static ArenaRulesetEditorSession create(UUID actor, ArenaRuleset source) {
        return create(actor, source, null);
    }

    public static ArenaRulesetEditorSession create(UUID actor, ArenaRuleset source, ArenaRuleset previous) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(source, "source");
        boolean canRead = source.metadata().author().equals(actor) || source.metadata().permissions().publicRead()
                || source.metadata().permissions().editors().contains(actor);
        if (!canRead || !source.metadata().permissions().allowClone())
            throw new SecurityException("Arena ruleset is not available for cloning");
        String player = actor.toString().replace("-", "");
        String version = "1.0.0";
        if (previous != null) {
            requireAuthorized(previous, actor);
            if (!previous.metadata().id().equals(new DesignId("genesis:arena/player/" + player)))
                throw new IllegalArgumentException("Previous arena belongs to another design identity");
            version = nextPatch(previous.metadata().version());
        }
        DesignMetadata sourceMetadata = source.metadata();
        DesignMetadata metadata = new DesignMetadata(new DesignId("genesis:arena/player/" + player),
                sourceMetadata.schemaVersion(), version, actor, "genesis:arena-editor",
                List.of(new DesignDependency(sourceMetadata.id(), sourceMetadata.version(), sourceMetadata.hash())), "",
                new DesignPermissions(false, false, Set.of()));
        ArenaRuleset initial = source.withMetadata(metadata);
        return new ArenaRulesetEditorSession(actor, initial, initial, List.of(), null);
    }

    public static ArenaRulesetEditorSession edit(UUID actor, ArenaRuleset source) {
        return new ArenaRulesetEditorSession(actor, source, source, List.of(), null);
    }

    public static ArenaRulesetEditorSession resume(UUID actor, byte[] encoded, DesignCodec codec) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(codec, "codec");
        if (encoded == null || encoded.length < 20 || encoded.length > MAX_CHECKPOINT_BYTES)
            throw new IllegalArgumentException("Arena editor checkpoint exceeds its byte budget");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != CHECKPOINT_MAGIC || input.readInt() != CHECKPOINT_VERSION)
                throw new IllegalArgumentException("Unknown arena editor checkpoint");
            UUID owner = new UUID(input.readLong(), input.readLong());
            if (!owner.equals(actor)) throw new SecurityException("Arena editor checkpoint belongs to another player");
            int undoCount = input.readInt();
            if (undoCount < 0 || undoCount > MAX_UNDO_STEPS)
                throw new IllegalArgumentException("Invalid arena editor undo history");
            ArenaRuleset saved = readRuleset(input, codec);
            ArenaRuleset draft = readRuleset(input, codec);
            String preview = input.readBoolean() ? readText(input) : null;
            List<ArenaRuleset> history = new ArrayList<>(undoCount);
            for (int index = 0; index < undoCount; index++) history.add(readRuleset(input, codec));
            if (input.available() != 0) throw new IllegalArgumentException("Trailing arena editor checkpoint data");
            return new ArenaRulesetEditorSession(actor, saved, draft, history, preview);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid arena editor checkpoint", failure);
        }
    }

    public UUID actor() { return actor; }
    public ArenaRuleset saved() { return saved; }
    public ArenaRuleset draft() { return draft; }
    public boolean hasUnsavedChanges() { return !draft.equals(saved); }
    public boolean canUndo() { return !undo.isEmpty(); }

    public void adjustWidth(int delta) {
        updateLimits(Math.addExact(draft.width(), delta), draft.height(), draft.durationSteps(), draft.unitBudget());
    }

    public void adjustHeight(int delta) {
        updateLimits(draft.width(), Math.addExact(draft.height(), delta), draft.durationSteps(), draft.unitBudget());
    }

    public void adjustDuration(long delta) {
        updateLimits(draft.width(), draft.height(), Math.addExact(draft.durationSteps(), delta), draft.unitBudget());
    }

    public void adjustUnitBudget(int delta) {
        updateLimits(draft.width(), draft.height(), draft.durationSteps(), Math.addExact(draft.unitBudget(), delta));
    }

    public void upgrade(DesignCodec codec) {
        requireEditPermission();
        if (draft.layout() != null) throw new IllegalStateException("Arena layout is already using schema 2");
        var upgraded = (ArenaRuleset) codec.migrateArenaV1(codec.writeEdited(draft, actor)).document();
        accept(upgraded);
    }

    public void bumpPatchVersion() { accept(nextVersion(draft)); }

    public void toggleObstacle(Cell cell) {
        Layout layout = requireLayout();
        acceptLayout(new Layout(toggleCell(layout.obstacles(), cell), layout.teams(), layout.objectives()));
    }

    public void toggleObjective(Cell cell) {
        Layout layout = requireLayout();
        acceptLayout(new Layout(layout.obstacles(), layout.teams(), toggleCell(layout.objectives(), cell)));
    }

    /** Replaces or appends the one-based spawn slot, creating a team at slot 1. */
    public void putSpawn(String teamId, int slot, Cell cell) {
        Layout layout = requireLayout();
        requireInBounds(cell);
        List<Team> teams = new ArrayList<>(layout.teams());
        int index = teamIndex(teams, teamId);
        List<Cell> spawns = index < 0 ? new ArrayList<>() : new ArrayList<>(teams.get(index).spawns());
        Checks.range(slot, 1, spawns.size() + 1, "spawn slot");
        if (slot == spawns.size() + 1) spawns.add(cell);
        else spawns.set(slot - 1, cell);
        Team updated = new Team(teamId, spawns);
        if (index < 0) teams.add(updated);
        else teams.set(index, updated);
        acceptLayout(new Layout(layout.obstacles(), teams, layout.objectives()));
    }

    public void removeSpawn(String teamId, int slot) {
        Layout layout = requireLayout();
        List<Team> teams = new ArrayList<>(layout.teams());
        int index = teamIndex(teams, teamId);
        if (index < 0) throw new IllegalArgumentException("Unknown arena team");
        List<Cell> spawns = new ArrayList<>(teams.get(index).spawns());
        Checks.range(slot, 1, spawns.size(), "spawn slot");
        spawns.remove(slot - 1);
        if (spawns.isEmpty()) teams.remove(index);
        else teams.set(index, new Team(teamId, spawns));
        acceptLayout(new Layout(layout.obstacles(), teams, layout.objectives()));
    }

    public void setVictoryMode(Combination combination) {
        requireLayout();
        acceptVictory(new Victory(combination, draft.victory().conditions()));
    }

    public void setVictoryThreshold(Metric metric, int threshold) {
        requireLayout();
        List<Condition> conditions = new ArrayList<>(draft.victory().conditions());
        conditions.removeIf(condition -> condition.metric() == metric);
        if (threshold != 0) conditions.add(new Condition(metric, threshold));
        acceptVictory(new Victory(draft.victory().combination(), conditions));
    }

    public void grantEditor(UUID editor) {
        requireAuthor();
        Objects.requireNonNull(editor, "editor");
        Set<UUID> editors = new LinkedHashSet<>(draft.metadata().permissions().editors());
        if (!editors.add(editor)) throw new IllegalArgumentException("Player is already an authorized editor");
        updatePermissions(new DesignPermissions(draft.metadata().permissions().publicRead(),
                draft.metadata().permissions().allowClone(), editors));
    }

    public void revokeEditor(UUID editor) {
        requireAuthor();
        Objects.requireNonNull(editor, "editor");
        Set<UUID> editors = new LinkedHashSet<>(draft.metadata().permissions().editors());
        if (!editors.remove(editor)) throw new IllegalArgumentException("Player is not an authorized editor");
        updatePermissions(new DesignPermissions(draft.metadata().permissions().publicRead(),
                draft.metadata().permissions().allowClone(), editors));
    }

    public void setSharing(boolean publicRead, boolean allowClone) {
        requireAuthor();
        updatePermissions(new DesignPermissions(publicRead, allowClone, draft.metadata().permissions().editors()));
    }

    public boolean undo() {
        requireEditPermission();
        if (undo.isEmpty()) return false;
        draft = undo.pop();
        previewedDocument = null;
        return true;
    }

    public ArenaRulesetValidator.Analysis preview() {
        requireEditPermission();
        ArenaRulesetValidator.Analysis analysis = validator.analyze(draft);
        previewedDocument = encodeSigned(draft, new DesignCodec(), actor);
        return analysis;
    }

    public String save(DesignCodec codec) {
        requireEditPermission();
        Objects.requireNonNull(codec, "codec");
        validator.requireValid(draft);
        String encoded = codec.writeEdited(draft, actor);
        if (!encoded.equals(previewedDocument))
            throw new IllegalStateException("Preview the current arena ruleset before saving");
        saved = (ArenaRuleset) codec.load(encoded);
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
                writeText(output, codec.writeEdited(saved, actor));
                writeText(output, codec.writeEdited(draft, actor));
                output.writeBoolean(previewedDocument != null);
                if (previewedDocument != null) writeText(output, previewedDocument);
                for (ArenaRuleset previous : undo) writeText(output, codec.writeEdited(previous, actor));
            }
            byte[] checkpoint = bytes.toByteArray();
            if (checkpoint.length > MAX_CHECKPOINT_BYTES)
                throw new IllegalStateException("Arena editor checkpoint exceeds its byte budget");
            return checkpoint;
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot encode arena editor checkpoint", impossible);
        }
    }

    private void accept(ArenaRuleset next) {
        requireEditPermission();
        requireSameDesign(saved, next);
        undo.push(draft);
        while (undo.size() > MAX_UNDO_STEPS) undo.removeLast();
        draft = next;
        previewedDocument = null;
    }

    private void requireEditPermission() { requireAuthorized(saved, actor); }

    private void requireAuthor() {
        if (!saved.metadata().author().equals(actor))
            throw new SecurityException("Only the arena author can change permissions");
        requireEditPermission();
    }

    private void updatePermissions(DesignPermissions permissions) {
        DesignMetadata current = draft.metadata();
        if (current.permissions().equals(permissions)) return;
        DesignMetadata updated = new DesignMetadata(current.id(), current.schemaVersion(), nextPatch(current.version()),
                current.author(), current.source(), current.dependencies(), "", permissions);
        accept(draft.withMetadata(updated));
    }

    private void updateLimits(int width, int height, long steps, int units) {
        accept(new ArenaRuleset(draft.metadata(), width, height, steps, units, draft.layout(), draft.victory()));
    }

    private Layout requireLayout() {
        requireEditPermission();
        if (draft.layout() == null) throw new IllegalStateException("Upgrade the arena to schema 2 before editing its map");
        return draft.layout();
    }

    private void acceptLayout(Layout layout) {
        accept(new ArenaRuleset(draft.metadata(), draft.width(), draft.height(), draft.durationSteps(),
                draft.unitBudget(), layout, draft.victory()));
    }

    private void acceptVictory(Victory victory) {
        accept(new ArenaRuleset(draft.metadata(), draft.width(), draft.height(), draft.durationSteps(),
                draft.unitBudget(), draft.layout(), victory));
    }

    private List<Cell> toggleCell(List<Cell> cells, Cell cell) {
        List<Cell> updated = new ArrayList<>(cells);
        if (!updated.remove(cell)) {
            requireInBounds(cell);
            updated.add(cell);
        }
        return updated;
    }

    private void requireInBounds(Cell cell) {
        if (cell.x() >= draft.width() || cell.y() >= draft.height())
            throw new IllegalArgumentException("Cell is outside the arena");
    }

    private static int teamIndex(List<Team> teams, String id) {
        for (int i = 0; i < teams.size(); i++) {
            if (teams.get(i).id().equals(id)) return i;
        }
        return -1;
    }

    private static ArenaRuleset nextVersion(ArenaRuleset ruleset) {
        DesignMetadata current = ruleset.metadata();
        return ruleset.withMetadata(new DesignMetadata(current.id(), current.schemaVersion(), nextPatch(current.version()),
                current.author(), current.source(), current.dependencies(), "", current.permissions()));
    }

    private static void requireAuthorized(ArenaRuleset ruleset, UUID actor) {
        if (!ruleset.metadata().permissions().mayEdit(actor, ruleset.metadata().author()))
            throw new SecurityException("Actor is not authorized to edit this arena ruleset");
    }

    private static void requireSameDesign(ArenaRuleset first, ArenaRuleset second) {
        if (!first.metadata().id().equals(second.metadata().id())
                || !first.metadata().author().equals(second.metadata().author()))
            throw new IllegalArgumentException("Arena editor cannot change design identity or author");
    }

    private static String encodeSigned(ArenaRuleset ruleset, DesignCodec codec, UUID actor) {
        return codec.writeEdited(ruleset, actor);
    }

    private static String nextPatch(String version) {
        String[] parts = version.split("\\.");
        int patch = Integer.parseInt(parts[2]);
        if (patch >= 999_999) throw new IllegalStateException("Arena patch version is exhausted");
        return parts[0] + "." + parts[1] + "." + (patch + 1);
    }

    private static ArenaRuleset readRuleset(DataInputStream input, DesignCodec codec) throws IOException {
        var document = codec.load(readText(input));
        if (!(document instanceof ArenaRuleset ruleset)) throw new IllegalArgumentException("Checkpoint is not an arena ruleset");
        return ruleset;
    }

    private static String readText(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > DesignCodec.MAX_DOCUMENT_BYTES || length > input.available())
            throw new IllegalArgumentException("Invalid arena checkpoint document length");
        byte[] encoded = input.readNBytes(length);
        String text = new String(encoded, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(encoded, text.getBytes(StandardCharsets.UTF_8)))
            throw new IllegalArgumentException("Arena checkpoint contains invalid UTF-8");
        return text;
    }

    private static void writeText(DataOutputStream output, String text) throws IOException {
        byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
        if (encoded.length < 1 || encoded.length > DesignCodec.MAX_DOCUMENT_BYTES)
            throw new IllegalArgumentException("Arena checkpoint document exceeds its byte budget");
        output.writeInt(encoded.length);
        output.write(encoded);
    }
}
