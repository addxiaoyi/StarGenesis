import java.util.Map;
import dev.stargenesis.internal.jackson.databind.ObjectMapper;
class ReplayCounterexample {
  public static void main(String[] args) throws Exception {
    Class<?> worker = Class.forName("EcologyRobustnessStudy");
    var defaults = worker.getDeclaredMethod("defaults"); defaults.setAccessible(true);
    var run = worker.getDeclaredMethod("run", String.class, long.class, String.class, Map.class);
    run.setAccessible(true);
    Object result = run.invoke(null, "ecology-v2-robustness-9", 8108471355311695579L, "default", defaults.invoke(null));
    System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
  }
}
