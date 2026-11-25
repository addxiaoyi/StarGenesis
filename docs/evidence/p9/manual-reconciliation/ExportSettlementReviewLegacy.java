import dev.stargenesis.integrations.SettlementSaga;
import dev.stargenesis.paper.settlement.FileSettlementSagaRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

class ExportSettlementReviewLegacy {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        Files.createDirectories(directory);
        UUID sagaId = new UUID(0, 1103);
        Path file = directory.resolve(sagaId + ".settlement.json");
        Files.copy(Path.of("stargenesis-paper/src/test/resources/settlement-v1-paid.json"), file);
        var repository = new FileSettlementSagaRepository(directory);
        var paid = repository.load(sagaId).orElseThrow();
        var stopped = new SettlementSaga(1, paid.request(), paid.permit(), SettlementSaga.State.MANUAL_REVIEW,
                2, "EXTERNAL_OUTCOME_UNKNOWN");
        repository.replace(stopped, 1);
        Files.copy(file, Path.of("stargenesis-paper/src/test/resources/settlement-v1-manual.json"));
        System.out.println("Exported legacy v1 paid-to-manual snapshot through the unchanged RC3 repository");
    }
}
