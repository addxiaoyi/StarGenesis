package dev.stargenesis.sim;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Immutable dashboard projection. It contains simulation values, never real inventory values. */
public record EcologyView(String worldId, long seed, long tick, long totalMatter, long soilMatter,
                          long detritusMatter, long organismMatter, int population,
                          Map<String, Integer> populationBySpecies, Map<String, Long> matterBySpecies,
                          int maxGeneration, long births, long deaths, long processedMatter,
                          int evolvedOrganisms, Map<String, MatterProgram> programs,
                          String stateHash, boolean terminal, String reason) {
    public EcologyView {
        populationBySpecies = Collections.unmodifiableMap(new TreeMap<>(populationBySpecies));
        matterBySpecies = Collections.unmodifiableMap(new TreeMap<>(matterBySpecies));
        programs = Collections.unmodifiableMap(new TreeMap<>(programs));
    }
}
