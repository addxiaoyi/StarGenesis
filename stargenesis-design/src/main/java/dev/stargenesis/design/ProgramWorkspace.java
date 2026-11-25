package dev.stargenesis.design;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded transactional editing surface for a typed IR graph. */
public final class ProgramWorkspace {
    public static final int MAX_UNDO_STEPS = 32;
    private final Deque<ProgramIR> history = new ArrayDeque<>();
    private final int maxInstructionsPerStep;
    private List<ProgramIR.Instruction> instructions;

    public ProgramWorkspace(int maxInstructionsPerStep) {
        if (maxInstructionsPerStep < 1 || maxInstructionsPerStep > ProgramIR.MAX_BUDGET)
            throw new IllegalArgumentException("Program instruction budget outside supported limits");
        this.maxInstructionsPerStep = maxInstructionsPerStep;
        instructions = List.of();
    }

    public ProgramWorkspace(ProgramIR initial) {
        Objects.requireNonNull(initial, "initial");
        maxInstructionsPerStep = initial.maxInstructionsPerStep();
        instructions = initial.instructions();
    }

    public List<ProgramIR.Instruction> instructions() { return instructions; }
    public int undoDepth() { return history.size(); }

    public ProgramIR current() {
        return new ProgramIR(instructions, maxInstructionsPerStep);
    }

    public void append(String id, PrimitiveCatalog.Operation operation, List<String> inputs,
                       Map<String, String> parameters) {
        List<ProgramIR.Instruction> updated = new ArrayList<>(instructions);
        updated.add(new ProgramIR.Instruction(id, operation, inputs, parameters));
        replace(updated);
    }

    public void replace(String id, PrimitiveCatalog.Operation operation, List<String> inputs,
                        Map<String, String> parameters) {
        List<ProgramIR.Instruction> updated = new ArrayList<>(instructions);
        int index = indexOf(id);
        updated.set(index, new ProgramIR.Instruction(id, operation, inputs, parameters));
        replace(updated);
    }

    public void remove(String id) {
        List<ProgramIR.Instruction> updated = new ArrayList<>(instructions);
        int index = indexOf(id);
        if (updated.stream().anyMatch(instruction -> instruction.inputs().contains(id)))
            throw new IllegalArgumentException("Remove dependent instructions first");
        updated.remove(index);
        replace(updated);
    }

    public boolean undo() {
        if (history.isEmpty()) return false;
        instructions = history.removeLast().instructions();
        return true;
    }

    private int indexOf(String id) {
        for (int index = 0; index < instructions.size(); index++)
            if (instructions.get(index).id().equals(id)) return index;
        throw new IllegalArgumentException("Unknown instruction " + id);
    }

    private void replace(List<ProgramIR.Instruction> updated) {
        if (updated.isEmpty()) throw new IllegalArgumentException("A program must contain at least one instruction");
        ProgramIR validated = new ProgramIR(updated, maxInstructionsPerStep);
        if (validated.instructions().equals(instructions)) return;
        if (!instructions.isEmpty()) {
            history.addLast(new ProgramIR(instructions, maxInstructionsPerStep));
            if (history.size() > MAX_UNDO_STEPS) history.removeFirst();
        }
        instructions = validated.instructions();
    }
}
