package dev.stargenesis.sim;

/** A finite hereditary program controlling an organism's resource conversions. */
public record EcologyGenome(int energyPerFood, int maintenanceEnergy, int lifespanTicks, int maturityTicks) {
    public EcologyGenome {
        if (energyPerFood < 1 || energyPerFood > 4
                || maintenanceEnergy < 1 || maintenanceEnergy > 4
                || lifespanTicks < 2 || lifespanTicks > 10_000
                || maturityTicks < 1 || maturityTicks >= lifespanTicks) {
            throw new IllegalArgumentException("Ecology genome is outside the finite trait bounds");
        }
    }

    public EcologyGenome withAdjustedTrait(int trait, int direction) {
        if (direction != -1 && direction != 1) throw new IllegalArgumentException("Trait direction must be -1 or 1");
        return switch (trait) {
            case 0 -> new EcologyGenome(adjustClamped(energyPerFood, direction, 1, 4), maintenanceEnergy,
                    lifespanTicks, maturityTicks);
            case 1 -> new EcologyGenome(energyPerFood, adjustClamped(maintenanceEnergy, direction, 1, 4),
                    lifespanTicks, maturityTicks);
            case 2 -> new EcologyGenome(energyPerFood, maintenanceEnergy,
                    adjustClamped(lifespanTicks, direction, maturityTicks + 1, 10_000), maturityTicks);
            case 3 -> new EcologyGenome(energyPerFood, maintenanceEnergy, lifespanTicks,
                    adjustClamped(maturityTicks, direction, 1, lifespanTicks - 1));
            default -> throw new IllegalArgumentException("Unknown ecology gene");
        };
    }

    EcologyGenome mutate(int trait, int direction) {
        return switch (trait) {
            case 0 -> new EcologyGenome(adjust(energyPerFood, direction, 1, 4), maintenanceEnergy,
                    lifespanTicks, maturityTicks);
            case 1 -> new EcologyGenome(energyPerFood, adjust(maintenanceEnergy, direction, 1, 4),
                    lifespanTicks, maturityTicks);
            case 2 -> new EcologyGenome(energyPerFood, maintenanceEnergy,
                    adjust(lifespanTicks, direction, maturityTicks + 1, 10_000), maturityTicks);
            case 3 -> new EcologyGenome(energyPerFood, maintenanceEnergy, lifespanTicks,
                    adjust(maturityTicks, direction, 1, lifespanTicks - 1));
            default -> throw new IllegalArgumentException("Unknown ecology gene");
        };
    }

    private static int adjust(int value, int direction, int minimum, int maximum) {
        if (direction != -1 && direction != 1) throw new IllegalArgumentException("Invalid mutation direction");
        if (minimum == maximum) return value;
        if (value + direction < minimum || value + direction > maximum) direction = -direction;
        return value + direction;
    }

    private static int adjustClamped(int value, int direction, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value + direction));
    }
}
