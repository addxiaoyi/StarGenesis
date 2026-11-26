import dev.stargenesis.paper.settlement.FileSettlementSagaRepository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Child process for test_settlement_process_lock.py; all state belongs to its temporary test directory. */
public final class SettlementLockProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !(args[0].equals("hold") || args[0].equals("acquire")))
            throw new IllegalArgumentException("Usage: SettlementLockProbe <hold|acquire> <temporary-directory>");
        boolean hold = args[0].equals("hold");
        Path root = Path.of(args[1]);
        var repository = new FileSettlementSagaRepository(root.resolve("repository"));
        if (!hold) Files.writeString(root.resolve("attempting"), "ready");
        repository.withExclusiveSaga(new UUID(0, 1301), () -> {
            try {
                Files.writeString(root.resolve(hold ? "holding" : "acquired"), "locked");
                if (hold) {
                    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
                    while (!Files.exists(root.resolve("release"))) {
                        if (System.nanoTime() >= deadline) throw new IllegalStateException("Probe release timed out");
                        Thread.sleep(20);
                    }
                }
                return null;
            } catch (IOException failure) { throw new UncheckedIOException(failure); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        });
    }
}
