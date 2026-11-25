package dev.stargenesis.sim;

import dev.stargenesis.api.sim.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * P1 bounded integer simulation. Algorithm genesis-sandbox/1 uses a fixed splitmix64 seed
 * mixer, UUID-ASCII agent order and N/E/S/W BFS order. No wall clock, random globals or IO.
 */
public final class ResourceSandboxWorld implements SimulationWorld {
    public static final String SANDBOX_ENGINE_VERSION = "genesis-sandbox/1";
    private static final String ZERO_HASH = "0".repeat(64);
    private static final int[][] DIRECTIONS = {{0,-1},{1,0},{0,1},{-1,0}};
    private final OwnerThread owner = new OwnerThread();
    private final String worldId;
    private final long seed;
    private final SandboxConfig config;
    private final List<Terrain> terrain;
    private final GridPoint base;
    private final List<WorldSnapshot.NodeState> nodes;
    private final TreeMap<AgentId, Agent> agents = new TreeMap<>();
    private final List<ReplayLog.Frame> history = new ArrayList<>();
    private final ConservingResourceLedger ledger;
    private long tick;
    private boolean terminal;
    private boolean closed;
    private String reason = "RUNNING";
    private String ledgerHistoryHash = ZERO_HASH;
    private String eventHistoryHash = ZERO_HASH;
    private int sequence;
    private int pathRequests;
    private List<EventRecord> stepEvents = new ArrayList<>();

    public ResourceSandboxWorld(String worldId, long seed, SandboxConfig config) {
        new EvidenceId(worldId, 0, 0);
        this.worldId = worldId; this.seed = seed; this.config = Objects.requireNonNull(config);
        this.base = new GridPoint(1, config.height()/2);
        List<Terrain> cells = new ArrayList<>(Collections.nCopies(config.width()*config.height(), Terrain.GROUND));
        int gap = 1 + Math.floorMod(mix64(seed), config.height()-2);
        for (int y=0; y<config.height(); y++) if (y != gap) cells.set(y*config.width()+config.width()/2, Terrain.BLOCKED);
        this.terrain = List.copyOf(cells);
        this.nodes = List.of(new WorldSnapshot.NodeState("node-ore", new GridPoint(config.width()-2,1), ResourceType.ORE),
                new WorldSnapshot.NodeState("node-biomass", new GridPoint(config.width()-2,config.height()-2), ResourceType.BIOMASS));
        Map<String,Map<String,Long>> initial = new TreeMap<>();
        initial.put("base", Map.of("ore",0L,"biomass",0L)); initial.put("spent",Map.of("energy",0L));
        initial.put("node-ore",Map.of("ore",config.nodeStock())); initial.put("node-biomass",Map.of("biomass",config.nodeStock()));
        for (int i=0; i<config.agentCount(); i++) {
            AgentId id = new AgentId(UUID.nameUUIDFromBytes((worldId+":"+i).getBytes(StandardCharsets.UTF_8)));
            GridPoint point = new GridPoint(i<2 ? 1 : 2, i%2==0 ? 1 : config.height()-2);
            Agent agent = new Agent(id,point); agents.put(id,agent);
            initial.put(account(id),Map.of("ore",0L,"biomass",0L,"energy",config.energyPerAgent()));
        }
        ledger = new ConservingResourceLedger(initial,100_000);
        validateConservation();
    }

    private ResourceSandboxWorld(WorldSnapshot snapshot) {
        if (!SANDBOX_ENGINE_VERSION.equals(snapshot.engineVersion())) throw new IllegalArgumentException("Unsupported sandbox engine");
        new EvidenceId(snapshot.worldId(),0,0);
        worldId=snapshot.worldId(); seed=snapshot.seed(); config=snapshot.config(); terrain=snapshot.terrain(); base=snapshot.base(); nodes=snapshot.nodes();
        tick=snapshot.tick(); terminal=snapshot.terminal(); reason=snapshot.reason(); ledger=ConservingResourceLedger.restore(snapshot.ledger());
        ledgerHistoryHash=snapshot.ledgerHistoryHash(); eventHistoryHash=snapshot.eventHistoryHash(); history.addAll(snapshot.history());
        ResourceSandboxWorld initial = new ResourceSandboxWorld(worldId,seed,config);
        if (!terrain.equals(initial.terrain) || !base.equals(initial.base) || !nodes.equals(initial.nodes)) throw new IllegalArgumentException("Snapshot geography differs from seed");
        for (WorldSnapshot.AgentState state : snapshot.agents()) {
            if (!initial.agents.containsKey(state.id()) || agents.containsKey(state.id()) || !walkable(state.position()))
                throw new IllegalArgumentException("Invalid snapshot agent");
            Agent a=new Agent(state.id(),state.position()); a.target=state.targetNode(); a.preferred=state.preferredResource(); a.paused=state.paused(); a.lastSequence=state.lastSequence(); a.status=state.status(); agents.put(a.id,a);
        }
        if (agents.values().stream().map(a->a.position).distinct().count()!=agents.size()) throw new IllegalArgumentException("Snapshot agents overlap");
        if (!ledger.snapshot().keySet().equals(initial.ledger.snapshot().keySet())) throw new IllegalArgumentException("Unknown snapshot ledger account");
        String ledgerChain=ZERO_HASH;
        for (ConservingResourceLedger.Attempt attempt : snapshot.ledger().attempts()) {
            if (!attempt.key().world().equals(worldId) || attempt.key().tick()>=tick) throw new IllegalArgumentException("Invalid ledger evidence time");
            ledgerChain=chainAttempt(ledgerChain,attempt);
        }
        if (!ledgerChain.equals(ledgerHistoryHash)) throw new IllegalArgumentException("Ledger history commitment mismatch");
        String eventChain=ZERO_HASH;
        for(int i=0;i<history.size();i++) {
            ReplayLog.Frame frame=history.get(i);
            if(frame.input().tick()!=i) throw new IllegalArgumentException("Nonsequential snapshot replay");
            for(EventRecord event:frame.events()) eventChain=chainEvent(eventChain,event);
        }
        if (!eventChain.equals(eventHistoryHash)) throw new IllegalArgumentException("Event history commitment mismatch");
        validateConservation();
        if (!stateHash().equals(snapshot.stateHash())) throw new IllegalArgumentException("Snapshot state hash mismatch");
        if (!history.isEmpty() && !history.getLast().stateHash().equals(snapshot.stateHash())) throw new IllegalArgumentException("Snapshot replay tail mismatch");
        initial.close();
    }

    public static ResourceSandboxWorld restore(WorldSnapshot snapshot) { return new ResourceSandboxWorld(Objects.requireNonNull(snapshot)); }
    @Override public String worldId() { owner.check(); return worldId; }
    @Override public long seed() { owner.check(); return seed; }
    @Override public long tick() { owner.check(); return tick; }
    public SandboxConfig config() { owner.check(); return config; }

    @Override public StepResult step(StepInput input) {
        owner.check(); requireOpen(); Objects.requireNonNull(input);
        if (terminal) throw new IllegalStateException("Sandbox already ended: "+reason);
        if (input.tick()!=tick) throw new IllegalArgumentException("Expected input tick "+tick);
        validateCommands(input);
        sequence=0; pathRequests=0; stepEvents=new ArrayList<>();
        for(Agent agent:agents.values()) agent.usedNodes=0;
        for (StepInput.Command command:input.commands()) apply(command);
        if (!terminal && tick%config.actionIntervalSteps()==0) {
            try { for(Agent agent:agents.values()) act(agent); }
            catch (BudgetExceeded budget) { finish(budget.getMessage()); }
            catch (IllegalStateException full) {
                if(full.getMessage()!=null && full.getMessage().contains("budget exhausted")) finish("LEDGER_EVIDENCE_BUDGET"); else throw full;
            }
        }
        tick++;
        if(!terminal && tick>=config.budget().maxSteps()) finish("RUN_DURATION_BUDGET");
        if(!terminal && tick>=config.durationSteps()) finish("DURATION_COMPLETE");
        if(!terminal && nodes.stream().allMatch(n->ledger.balance(n.id(),n.resource().ledgerKey())==0)
                && agents.values().stream().allMatch(a->cargo(a)==0)) finish("ALL_RESOURCES_DELIVERED");
        if(!terminal && agents.values().stream().allMatch(a->energy(a)==0)) finish("ENERGY_EXHAUSTED");
        validateConservation();
        String hash=stateHash(); List<EventRecord> events=List.copyOf(stepEvents);
        history.add(new ReplayLog.Frame(input,hash,events));
        return new StepResult(tick,events,hash,terminal,reason);
    }

    private void validateCommands(StepInput input) {
        if(input.commands().size()>16) throw new IllegalArgumentException("Sandbox input command budget exceeded");
        Map<AgentId,Long> sequences=new HashMap<>();
        Map<AgentId,Integer> programNodes=new HashMap<>();
        if(input.commands().stream().anyMatch(command->command.action().equals("stop")) && input.commands().size()!=1)
            throw new IllegalArgumentException("stop must be the only command in its simulation step");
        for(StepInput.Command command:input.commands()) {
            Agent a=agents.get(command.agent());
            if(a==null || command.sequence()<=sequences.getOrDefault(a.id,a.lastSequence)) throw new IllegalArgumentException("Unknown agent or replayed command sequence");
            sequences.put(a.id,command.sequence());
            switch(command.action()) {
                case "pause","resume","stop" -> { if(!command.arguments().isEmpty()) throw new IllegalArgumentException("Unexpected command arguments"); }
                case "prefer_resource" -> {
                    Long resource=command.arguments().get("resource");
                    if(command.arguments().size()!=1 || resource==null || resource<0 || resource>1) throw new IllegalArgumentException("resource must be 0 (ORE) or 1 (BIOMASS)");
                }
                case "program_move" -> {
                    Long x=command.arguments().get("x"), y=command.arguments().get("y"), z=command.arguments().get("z");
                    if(command.arguments().size()!=3 || x==null || y==null || z==null || z!=0
                            || x<0 || x>=config.width() || y<0 || y>=config.height())
                        throw new IllegalArgumentException("program_move requires an in-bounds x,y,0 position");
                    if(!walkable(new GridPoint(x.intValue(),y.intValue()))) throw new IllegalArgumentException("Program move targets blocked terrain");
                }
                case "program_harvest", "program_deposit" -> {
                    Long resource=command.arguments().get("resource");
                    if(command.arguments().size()!=1 || resource==null || resource<0 || resource>1)
                        throw new IllegalArgumentException(command.action()+" requires resource 0 (ORE) or 1 (BIOMASS)");
                }
                case "program_wait" -> {
                    if(!command.arguments().isEmpty()) throw new IllegalArgumentException("Unexpected program_wait arguments");
                }
                default -> throw new IllegalArgumentException("Sandbox action is not whitelisted: "+command.action());
            }
            if(command.action().startsWith("program_")) {
                int count=programNodes.merge(command.agent(),1,Integer::sum);
                if(count>config.budget().nodesPerAgentStep())
                    throw new IllegalArgumentException("Program node budget exceeded before input application");
            }
        }
    }

    private void apply(StepInput.Command command) {
        Agent a=agents.get(command.agent()); a.lastSequence=command.sequence();
        switch(command.action()) {
            case "pause" -> { a.paused=true; a.status="PAUSED"; }
            case "resume" -> { a.paused=false; a.status="READY"; }
            case "prefer_resource" -> { a.preferred=command.arguments().get("resource").intValue(); a.target=-1; }
            case "stop" -> finish("MANUAL_STOP");
            case "program_move" -> programMove(a, command.arguments());
            case "program_harvest" -> programHarvest(a, command.arguments().get("resource").intValue());
            case "program_deposit" -> programDeposit(a, command.arguments().get("resource").intValue());
            case "program_wait" -> { instruction(a); a.status="PROGRAM_WAITING"; emit("program.wait",Map.of("agent",a.id.value().toString())); }
            default -> throw new IllegalStateException("Validated action disappeared");
        }
        emit("input.applied",Map.of("agent",a.id.value().toString(),"action",command.action(),"sequence",Long.toString(command.sequence())));
    }

    private void programMove(Agent agent, Map<String,Long> arguments) {
        instruction(agent);
        GridPoint destination=new GridPoint(arguments.get("x").intValue(),arguments.get("y").intValue());
        if(agent.position.equals(destination)) { agent.status="PROGRAM_MOVE_NOOP"; return; }
        if(agent.position.distance(destination)!=1 || agents.values().stream().anyMatch(other->other!=agent && other.position.equals(destination))) {
            agent.status="PROGRAM_MOVE_BLOCKED";
            emit("program.move_blocked",Map.of("agent",agent.id.value().toString()));
            return;
        }
        if(!pay(agent,1)) return;
        agent.position=destination; agent.target=-1; agent.status="PROGRAM_MOVED";
        emit("program.move",Map.of("agent",agent.id.value().toString(),"x",Integer.toString(destination.x()),"y",Integer.toString(destination.y())));
    }

    private void programHarvest(Agent agent, int resourceIndex) {
        instruction(agent);
        ResourceType resource=ResourceType.values()[resourceIndex];
        if(cargo(agent)>=config.carryCapacity()) { agent.status="PROGRAM_CARRY_FULL"; return; }
        WorldSnapshot.NodeState node=nodes.stream().filter(candidate->candidate.resource()==resource).findFirst().orElseThrow();
        if(agent.position.distance(node.position())>1 || ledger.balance(node.id(),resource.ledgerKey())==0) {
            agent.status="PROGRAM_RESOURCE_UNAVAILABLE";
            emit("program.harvest_unavailable",Map.of("agent",agent.id.value().toString(),"resource",resource.ledgerKey()));
            return;
        }
        if(!pay(agent,2)) return;
        if(transfer(node.id(),account(agent.id),resource.ledgerKey(),1)) {
            agent.status="PROGRAM_HARVESTED_"+resource.name();
            emit("program.harvest",Map.of("agent",agent.id.value().toString(),"resource",resource.ledgerKey(),"amount","1"));
        }
    }

    private void programDeposit(Agent agent, int resourceIndex) {
        instruction(agent);
        ResourceType resource=ResourceType.values()[resourceIndex];
        if(agent.position.distance(base)>1) { agent.status="PROGRAM_BASE_UNAVAILABLE"; return; }
        long amount=ledger.balance(account(agent.id),resource.ledgerKey());
        if(amount==0) { agent.status="PROGRAM_NO_CARGO"; return; }
        if(!pay(agent,1)) return;
        transfer(account(agent.id),"base",resource.ledgerKey(),amount);
        agent.target=-1; agent.status="PROGRAM_DEPOSITED_"+resource.name();
        emit("program.deposit",Map.of("agent",agent.id.value().toString(),"resource",resource.ledgerKey(),"amount",Long.toString(amount)));
    }

    private void act(Agent a) {
        if(a.paused) return;
        if(energy(a)==0) { a.status="NO_ENERGY"; return; }
        if(cargo(a)>=config.carryCapacity() || (cargo(a)>0 && remaining()==0)) { deliver(a); return; }
        if(a.target<0 || ledger.balance(nodes.get(a.target).id(),nodes.get(a.target).resource().ledgerKey())==0) find(a);
        if(a.target<0) { if(cargo(a)>0) deliver(a); else a.status="NO_TARGET_IN_SENSOR_RANGE"; return; }
        WorldSnapshot.NodeState target=nodes.get(a.target);
        if(a.position.distance(target.position())<=1) gather(a,target); else move(a,target.position(),"RESOURCE");
    }

    private void find(Agent a) {
        instruction(a); if(!pay(a,1)) return;
        int best=-1; long bestRank=Long.MAX_VALUE;
        for(int i=0;i<nodes.size();i++) {
            var n=nodes.get(i); int distance=a.position.distance(n.position());
            if(distance>config.budget().sensorRadius() || ledger.balance(n.id(),n.resource().ledgerKey())==0) continue;
            long rank=distance*4L+(a.preferred<0 ? i : (i==a.preferred ? -10000:10000));
            if(rank<bestRank) { best=i; bestRank=rank; }
        }
        a.target=best; a.status=best<0?"NO_TARGET_IN_SENSOR_RANGE":"TARGET_FOUND";
        emit("agent.find",Map.of("agent",a.id.value().toString(),"target",best<0?"none":nodes.get(best).id()));
    }

    private void gather(Agent a, WorldSnapshot.NodeState node) {
        instruction(a); if(!pay(a,2)) return;
        if(transfer(node.id(),account(a.id),node.resource().ledgerKey(),1)) {
            a.status="GATHERING_"+node.resource().name();
            emit("agent.gather",Map.of("agent",a.id.value().toString(),"resource",node.resource().ledgerKey(),"amount","1"));
        }
    }

    private void deliver(Agent a) {
        if(a.position.distance(base)>1) { move(a,base,"BASE"); return; }
        instruction(a); if(!pay(a,1)) return;
        for(ResourceType type:ResourceType.values()) {
            long amount=ledger.balance(account(a.id),type.ledgerKey());
            if(amount>0) transfer(account(a.id),"base",type.ledgerKey(),amount);
        }
        a.target=-1; a.status="DELIVERED";
        emit("agent.deliver",Map.of("agent",a.id.value().toString(),"score",Long.toString(score())));
    }

    private void move(Agent a,GridPoint target,String purpose) {
        instruction(a);
        if(++pathRequests>config.budget().pathRequestsPerStep()) throw new BudgetExceeded("PATH_REQUEST_BUDGET");
        GridPoint next=nextStep(a,target);
        if(next==null) { a.status="PATH_BLOCKED"; emit("agent.blocked",Map.of("agent",a.id.value().toString())); return; }
        if(!pay(a,1)) return;
        a.position=next; a.status="MOVING_TO_"+purpose;
        emit("agent.move",Map.of("agent",a.id.value().toString(),"x",Integer.toString(next.x()),"y",Integer.toString(next.y())));
    }

    private GridPoint nextStep(Agent a,GridPoint target) {
        int size=config.width()*config.height(); int[] previous=new int[size]; Arrays.fill(previous,-1);
        int start=index(a.position); previous[start]=start; ArrayDeque<Integer> queue=new ArrayDeque<>(); queue.add(start);
        Set<GridPoint> occupied=new HashSet<>(); agents.values().stream().filter(other->other!=a).forEach(other->occupied.add(other.position));
        int found=-1;
        while(!queue.isEmpty()) {
            int here=queue.removeFirst(); GridPoint p=point(here);
            if(p.distance(target)<=1) { found=here; break; }
            for(int[] delta:DIRECTIONS) {
                int x=p.x()+delta[0],y=p.y()+delta[1];
                if(x<0 || x>=config.width() || y<0 || y>=config.height()) continue;
                GridPoint next=new GridPoint(x,y); int nextIndex=index(next);
                if(previous[nextIndex]>=0 || !walkable(next) || occupied.contains(next)) continue;
                previous[nextIndex]=here; queue.addLast(nextIndex);
            }
        }
        if(found<0 || found==start) return null;
        while(previous[found]!=start) found=previous[found];
        return point(found);
    }

    private void instruction(Agent a) {
        if(++a.usedNodes>config.budget().nodesPerAgentStep()) throw new BudgetExceeded("NODE_INSTRUCTION_BUDGET");
    }
    private boolean pay(Agent a,long cost) {
        if(energy(a)<cost) { a.status="INSUFFICIENT_ENERGY"; return false; }
        return transfer(account(a.id),"spent","energy",cost);
    }
    private boolean transfer(String from,String to,String resource,long amount) {
        EvidenceId key=new EvidenceId(worldId,tick,sequence++);
        ResourceLedger.TransferResult result=ledger.transfer(key,from,to,resource,amount);
        ledgerHistoryHash=chainAttempt(ledgerHistoryHash,new ConservingResourceLedger.Attempt(key,from,to,resource,amount,result));
        record(new EventRecord(key,"resource.transferred",Map.of("from",from,"to",to,"resource",resource,"amount",Long.toString(amount),"result",result.name())));
        return result==ResourceLedger.TransferResult.APPLIED;
    }
    private void emit(String type,Map<String,String> data) { record(new EventRecord(new EvidenceId(worldId,tick,sequence++),type,data)); }
    private void record(EventRecord event) { stepEvents.add(event); eventHistoryHash=chainEvent(eventHistoryHash,event); }
    private void finish(String why) {
        if(terminal) return; terminal=true; reason=why;
        emit("world.ended",Map.of("reason",why,"score",Long.toString(score())));
    }

    public SandboxView view() {
        owner.check();
        List<SandboxView.AgentView> av=agents.values().stream().map(a->new SandboxView.AgentView(a.id,a.position,energy(a),config.carryCapacity(),
                ledger.balance(account(a.id),"ore"),ledger.balance(account(a.id),"biomass"),a.target<0?null:nodes.get(a.target).position(),a.status)).toList();
        List<SandboxView.ResourceNodeView> nv=nodes.stream().map(n->new SandboxView.ResourceNodeView(n.id(),n.position(),n.resource(),ledger.balance(n.id(),n.resource().ledgerKey()))).toList();
        return new SandboxView(worldId,seed,tick,config.width(),config.height(),terrain,base,av,nv,score(),terminal,reason,stateHash());
    }
    public WorldSnapshot snapshot() {
        owner.check();
        return new WorldSnapshot(SANDBOX_ENGINE_VERSION,1,worldId,seed,config,tick,terrain,base,nodes,
                agents.values().stream().map(a->new WorldSnapshot.AgentState(a.id,a.position,a.target,a.preferred,a.paused,a.lastSequence,a.status)).toList(),
                ledger.checkpoint(),ledgerHistoryHash,eventHistoryHash,terminal,reason,history,stateHash());
    }
    public ReplayLog replayLog() { owner.check(); return new ReplayLog(SANDBOX_ENGINE_VERSION,1,worldId,seed,config,history); }
    @Override public String stateHash() {
        owner.check(); StringBuilder s=new StringBuilder(4096);
        token(s,SANDBOX_ENGINE_VERSION);token(s,worldId);token(s,seed);token(s,config.toString());token(s,tick);token(s,terminal);token(s,reason);
        token(s,base); terrain.forEach(t->token(s,t)); nodes.forEach(n->token(s,n));
        for(Agent a:agents.values()) { token(s,a.id.value());token(s,a.position);token(s,a.target);token(s,a.preferred);token(s,a.paused);token(s,a.lastSequence);token(s,a.status); }
        ledger.snapshot().forEach((account,resources)->{token(s,account);resources.forEach((r,n)->{token(s,r);token(s,n);});});
        token(s,ledgerHistoryHash);token(s,eventHistoryHash); return sha(s.toString());
    }
    private void validateConservation() {
        Map<String,Long> total=new TreeMap<>();
        ledger.snapshot().values().forEach(resources->resources.forEach((r,n)->total.merge(r,n,Math::addExact)));
        if(!total.equals(Map.of("ore",config.nodeStock(),"biomass",config.nodeStock(),"energy",Math.multiplyExact(config.energyPerAgent(),config.agentCount()))))
            throw new IllegalStateException("Simulation resource conservation failed");
        for(Agent a:agents.values()) if(cargo(a)>config.carryCapacity()) throw new IllegalArgumentException("Agent cargo exceeds capacity");
    }
    private long score() { return Math.addExact(Math.multiplyExact(ledger.balance("base","ore"),2),ledger.balance("base","biomass")); }
    private long energy(Agent a) { return ledger.balance(account(a.id),"energy"); }
    private long cargo(Agent a) { return Math.addExact(ledger.balance(account(a.id),"ore"),ledger.balance(account(a.id),"biomass")); }
    private long remaining() { return nodes.stream().mapToLong(n->ledger.balance(n.id(),n.resource().ledgerKey())).sum(); }
    private boolean walkable(GridPoint point) { return point.x()<config.width() && point.y()<config.height() && terrain.get(index(point))==Terrain.GROUND; }
    private int index(GridPoint point) { return point.y()*config.width()+point.x(); }
    private GridPoint point(int index) { return new GridPoint(index%config.width(),index/config.width()); }
    private static String account(AgentId id) { return "agent-"+id.value(); }
    private static long mix64(long x) { x+=0x9e3779b97f4a7c15L; x=(x^(x>>>30))*0xbf58476d1ce4e5b9L; x=(x^(x>>>27))*0x94d049bb133111ebL; return x^(x>>>31); }
    private static void token(StringBuilder s,Object value) { String text=String.valueOf(value);s.append(text.length()).append(':').append(text).append(';'); }
    private static String chainAttempt(String previous,ConservingResourceLedger.Attempt a) {
        StringBuilder s=new StringBuilder(previous);token(s,a.key());token(s,a.source());token(s,a.target());token(s,a.resource());token(s,a.amount());token(s,a.result());return sha(s.toString());
    }
    private static String chainEvent(String previous,EventRecord event) {
        StringBuilder s=new StringBuilder(previous);token(s,event.evidenceId());token(s,event.type());new TreeMap<>(event.data()).forEach((k,v)->{token(s,k);token(s,v);});return sha(s.toString());
    }
    private static String sha(String text) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
    private void requireOpen() { if(closed) throw new IllegalStateException("Sandbox closed"); }
    @Override public void close() { owner.check(); closed=true; }
    private static final class Agent {
        final AgentId id; GridPoint position; int target=-1,preferred=-1,usedNodes; boolean paused; long lastSequence=-1; String status="READY";
        Agent(AgentId id,GridPoint position) { this.id=id;this.position=position; }
    }
    private static final class BudgetExceeded extends RuntimeException { BudgetExceeded(String reason) { super(reason); } }
}
