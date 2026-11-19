package dev.stargenesis.api.sim;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
/** Input order is (agent UUID ASCII, sequence). Duplicate sequence per agent is rejected. */
public record StepInput(long tick, List<Command> commands) {
    public StepInput {
        if (tick < 0) throw new IllegalArgumentException("Negative tick");
        if (commands.size() > 1024) throw new IllegalArgumentException("Too many inputs");
        commands = commands.stream().sorted(Comparator.comparing(Command::agent).thenComparingLong(Command::sequence)).toList();
        for (int i = 1; i < commands.size(); i++) {
            Command a = commands.get(i - 1), b = commands.get(i);
            if (a.agent().equals(b.agent()) && a.sequence() == b.sequence()) throw new IllegalArgumentException("Duplicate input sequence");
        }
    }
    public record Command(AgentId agent, long sequence, String action, Map<String, Long> arguments) {
        public Command {
            Objects.requireNonNull(agent);
            if (sequence < 0 || action == null || !action.matches("[a-z][a-z0-9_.-]{0,63}")) throw new IllegalArgumentException("Invalid command");
            arguments = Map.copyOf(arguments);
            if (arguments.size() > 16 || arguments.keySet().stream().anyMatch(k -> !k.matches("[a-z][a-z0-9_]{0,31}")))
                throw new IllegalArgumentException("Invalid command arguments");
        }
    }
}
