package dev.stargenesis.sim;

import dev.stargenesis.api.design.DesignId;

import java.util.Objects;

/** Fixed, mass-balanced production recipe available to virtual factory workstations. */
public record FactoryRecipe(DesignId id, long orePerBatch, long polymerPerBatch,
                            long componentsPerBatch, int ticksPerBatch, int maxBatches) {
    public FactoryRecipe {
        Objects.requireNonNull(id, "id");
        if (orePerBatch < 0 || polymerPerBatch < 0 || orePerBatch + polymerPerBatch == 0
                || componentsPerBatch < 1 || ticksPerBatch < 1 || maxBatches < 1 || maxBatches > 8
                || Math.addExact(Math.multiplyExact(orePerBatch, 2), polymerPerBatch) != componentsPerBatch) {
            throw new IllegalArgumentException("Factory recipe must be bounded and conserve material mass");
        }
    }

    public long oreFor(int batches) {
        requireBatches(batches);
        return Math.multiplyExact(orePerBatch, batches);
    }

    public long polymerFor(int batches) {
        requireBatches(batches);
        return Math.multiplyExact(polymerPerBatch, batches);
    }

    public long componentsFor(int batches) {
        requireBatches(batches);
        return Math.multiplyExact(componentsPerBatch, batches);
    }

    public long durationFor(int batches) {
        requireBatches(batches);
        return Math.multiplyExact((long) ticksPerBatch, batches);
    }

    private void requireBatches(int batches) {
        if (batches < 1 || batches > maxBatches) {
            throw new IllegalArgumentException("Factory recipe batch count is outside its finite bounds");
        }
    }
}
