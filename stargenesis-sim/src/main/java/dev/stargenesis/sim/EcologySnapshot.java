package dev.stargenesis.sim;

import dev.stargenesis.api.design.DesignId;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

public record EcologySnapshot(String engineVersion, String worldId, long seed, long tick,
                              long nutrient, long waste, List<EcologyOrganism> organisms,
                              Map<DesignId, Long> reactionInventory,
                              List<EcologyHabitatCell> habitatCells,
                              EcologyStatistics statistics, String stateHash) {
    public EcologySnapshot(String engineVersion, String worldId, long seed, long tick,
                           long nutrient, long waste, List<EcologyOrganism> organisms,
                           Map<DesignId, Long> reactionInventory,
                           EcologyStatistics statistics, String stateHash) {
        this(engineVersion, worldId, seed, tick, nutrient, waste, organisms, reactionInventory,
                defaultHabitat(nutrient, waste), statistics, stateHash);
    }

    public EcologySnapshot(String engineVersion, String worldId, long seed, long tick,
                           long nutrient, long waste, List<EcologyOrganism> organisms,
                           EcologyStatistics statistics, String stateHash) {
        this(engineVersion, worldId, seed, tick, nutrient, waste, organisms, Map.of(),
                defaultHabitat(nutrient, waste), statistics, stateHash);
    }

    public EcologySnapshot {
        if (!EcologyWorld.ENGINE_VERSION.equals(engineVersion)
                || worldId == null || !worldId.matches("[a-z0-9][a-z0-9_-]{0,63}")
                || tick < 0 || nutrient < 0 || waste < 0
                || stateHash == null || !stateHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid ecology snapshot metadata");
        }
        organisms = List.copyOf(Objects.requireNonNull(organisms, "organisms"));
        Map<DesignId, Long> inventory = new TreeMap<>();
        Objects.requireNonNull(reactionInventory, "reactionInventory").forEach((id, amount) -> {
            Objects.requireNonNull(id, "reaction species");
            if (amount == null || amount < 0 || amount > MatterReactionNetwork.MAX_UNITS_PER_SPECIES) {
                throw new IllegalArgumentException("Invalid reaction matter inventory");
            }
            inventory.put(id, amount);
        });
        reactionInventory = Collections.unmodifiableMap(inventory);
        habitatCells = List.copyOf(Objects.requireNonNull(habitatCells, "habitatCells"));
        if (habitatCells.size() != EcologyWorld.HABITAT_CELL_COUNT) {
            throw new IllegalArgumentException("Ecology habitat must contain every cell");
        }
        long habitatNutrient = 0;
        long habitatWaste = 0;
        for (int index = 0; index < habitatCells.size(); index++) {
            EcologyHabitatCell cell = Objects.requireNonNull(habitatCells.get(index), "habitat cell");
            if (cell.id() != index) throw new IllegalArgumentException("Ecology habitat cells are not canonical");
            habitatNutrient = Math.addExact(habitatNutrient, cell.nutrient());
            habitatWaste = Math.addExact(habitatWaste, cell.waste());
        }
        if (habitatNutrient != nutrient || habitatWaste != waste) {
            throw new IllegalArgumentException("Ecology habitat does not match its matter inventory");
        }
        Objects.requireNonNull(statistics, "statistics");
        Set<Long> ids = new HashSet<>();
        long previousId = -1;
        for (EcologyOrganism organism : organisms) {
            Objects.requireNonNull(organism, "organism");
            if (organism.id() <= previousId || !ids.add(organism.id())) {
                throw new IllegalArgumentException("Organisms must use strictly increasing IDs");
            }
            previousId = organism.id();
        }
        if (statistics.population() != organisms.size()) {
            throw new IllegalArgumentException("Ecology statistics do not match the population");
        }
    }

    private static List<EcologyHabitatCell> defaultHabitat(long nutrient, long waste) {
        List<EcologyHabitatCell> cells = new java.util.ArrayList<>(EcologyWorld.HABITAT_CELL_COUNT);
        for (int id = 0; id < EcologyWorld.HABITAT_CELL_COUNT; id++) {
            cells.add(new EcologyHabitatCell(id, id == 0 ? nutrient : 0, id == 0 ? waste : 0));
        }
        return cells;
    }
}
