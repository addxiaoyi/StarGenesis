package dev.stargenesis.paper.program;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.api.sim.SimulationBudget;
import dev.stargenesis.design.PrimitiveCatalog;
import dev.stargenesis.design.ProgramWorkspace;
import dev.stargenesis.design.TypedProgramDocument;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Serializes admission and storage with retirement; debugger work stays on the player's command thread. */
final class ProgramSessions implements AutoCloseable {
    static final int MAX_OPEN_WORKSPACES = 64;
    private static final int MAX_INSTRUCTIONS_PER_STEP = 256;
    private final ProgramStore store;
    private final SimulationBudget budget;
    private final Map<UUID, ProgramSession> sessions = new HashMap<>();
    private boolean closed;

    ProgramSessions(ProgramStore store, SimulationBudget budget) {
        this.store = Objects.requireNonNull(store);
        this.budget = Objects.requireNonNull(budget);
    }

    synchronized ProgramSession create(UUID owner, DesignId id) throws IOException {
        requireNoOpenSession(owner);
        if (store.exists(owner, id)) throw new IllegalArgumentException("程序已存在，请使用 open");
        ProgramWorkspace workspace = new ProgramWorkspace(MAX_INSTRUCTIONS_PER_STEP);
        workspace.append("start", PrimitiveCatalog.Operation.WAIT, List.of(), Map.of());
        ProgramSession session = new ProgramSession(owner, id, workspace, null, budget);
        session.save(store);
        sessions.put(owner, session);
        return session;
    }

    synchronized ProgramSession open(UUID owner, DesignId id) throws IOException {
        requireNoOpenSession(owner);
        TypedProgramDocument document = store.load(owner, id)
                .orElseThrow(() -> new IllegalArgumentException("找不到个人程序 " + id));
        ProgramSession session = new ProgramSession(owner, id, new ProgramWorkspace(document.program()), document, budget);
        sessions.put(owner, session);
        return session;
    }

    synchronized ProgramSession save(UUID owner) throws IOException {
        ProgramSession session = require(owner);
        session.save(store);
        return session;
    }

    synchronized boolean closeSession(UUID owner, boolean discard) {
        ProgramSession session = require(owner);
        if (session.dirty && !discard)
            throw new IllegalArgumentException("有未保存修改；先 save，或使用 close discard 丢弃。");
        sessions.remove(owner, session);
        return session.dirty;
    }

    synchronized void disconnected(UUID owner) { sessions.remove(owner); }
    @Override public synchronized void close() { closed = true; sessions.clear(); }
    synchronized boolean isClosed() { return closed; }

    synchronized ProgramSession require(UUID owner) {
        requireOpen();
        ProgramSession session = sessions.get(owner);
        if (session == null) throw new IllegalArgumentException("先 create 或 open 一个程序");
        return session;
    }

    synchronized int size() { return sessions.size(); }

    private void requireNoOpenSession(UUID owner) {
        requireOpen();
        Objects.requireNonNull(owner, "owner");
        if (sessions.containsKey(owner)) throw new IllegalArgumentException("请先保存并 close 当前程序工作区");
        if (sessions.size() >= MAX_OPEN_WORKSPACES)
            throw new IllegalArgumentException("当前打开的程序工作区已达服务器上限");
    }

    private void requireOpen() {
        if (closed) throw new IllegalArgumentException("程序工作区服务已停止。");
    }
}
