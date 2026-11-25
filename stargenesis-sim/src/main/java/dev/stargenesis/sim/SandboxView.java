package dev.stargenesis.sim;

import dev.stargenesis.api.sim.AgentId;
import java.util.List;

/** Immutable projection data. Coordinates are simulation cells, not Minecraft locations. */
public record SandboxView(String worldId, long seed, long tick, int width, int height,
                          List<Terrain> terrain, GridPoint base, List<AgentView> agents,
                          List<ResourceNodeView> nodes, long score, boolean terminal,
                          String reason, String stateHash) {
    public SandboxView { terrain = List.copyOf(terrain); agents = List.copyOf(agents); nodes = List.copyOf(nodes); }
    public record AgentView(AgentId id, GridPoint position, long energy, long capacity,
                            long ore, long biomass, GridPoint target, String status) { }
    public record ResourceNodeView(String id, GridPoint position, ResourceType resource, long remaining) { }
}
