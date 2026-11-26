package dev.stargenesis.sim;

import java.util.Objects;

/** Executes the same online engine and stops at the first state/event mismatch. */
public final class ReplayVerifier {
    private ReplayVerifier() { }
    public static Verification verify(ReplayLog log) {
        Objects.requireNonNull(log);
        if(!ResourceSandboxWorld.SANDBOX_ENGINE_VERSION.equals(log.engineVersion())) return new Verification(false,0,"","","UNSUPPORTED_ENGINE");
        long currentTick=0;
        try(ResourceSandboxWorld world=new ResourceSandboxWorld(log.worldId(),log.seed(),log.config())) {
            for(ReplayLog.Frame frame:log.frames()) {
                currentTick=frame.input().tick();
                var actual=world.step(frame.input());
                if(!actual.stateHash().equals(frame.stateHash())) return new Verification(false,frame.input().tick(),frame.stateHash(),actual.stateHash(),"STATE_HASH_MISMATCH");
                if(!actual.events().equals(frame.events())) return new Verification(false,frame.input().tick(),frame.stateHash(),actual.stateHash(),"EVENT_LOG_MISMATCH");
            }
            return new Verification(true,-1,world.stateHash(),world.stateHash(),"MATCH");
        } catch(IllegalArgumentException | IllegalStateException failure) {
            return new Verification(false,currentTick,"","","INVALID_REPLAY: "+failure.getMessage());
        }
    }
    public record Verification(boolean passed,long firstDivergentTick,String expectedHash,String actualHash,String reason) { }
}
