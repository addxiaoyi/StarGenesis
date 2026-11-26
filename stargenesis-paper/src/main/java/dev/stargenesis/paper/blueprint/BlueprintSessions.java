package dev.stargenesis.paper.blueprint;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.design.Blueprint;
import dev.stargenesis.design.BlueprintWorkspace;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Serializes admission and storage with retirement; edits remain on each player's command thread. */
final class BlueprintSessions implements AutoCloseable {
    static final int MAX_OPEN_WORKSPACES = 64;
    private final BlueprintStore store;
    private final Map<UUID, EditSession> sessions = new HashMap<>();
    private boolean closed;

    BlueprintSessions(BlueprintStore store) { this.store = Objects.requireNonNull(store); }

    synchronized Blueprint create(UUID owner, DesignId id) throws IOException {
        requireNoOpenSession(owner);
        if (store.exists(owner, id)) throw new IllegalArgumentException("蓝图已存在，请使用 open");
        Blueprint initial = store.newBlueprint(owner, id);
        Blueprint saved = store.save(owner, initial, "");
        put(owner, saved);
        return saved;
    }

    synchronized Blueprint open(UUID reader, UUID author, DesignId id) throws IOException {
        requireNoOpenSession(reader);
        Blueprint blueprint = store.loadFor(reader, author, id)
                .orElseThrow(() -> new IllegalArgumentException("找不到蓝图 " + id.value()));
        put(reader, blueprint);
        return blueprint;
    }

    synchronized Blueprint cloneBlueprint(UUID actor, UUID author, DesignId source, DesignId target) throws IOException {
        requireNoOpenSession(actor);
        Blueprint copy = store.cloneBlueprint(actor, author, source, target);
        put(actor, copy);
        return copy;
    }

    synchronized Blueprint save(UUID actor) throws IOException {
        EditSession session = require(actor);
        Blueprint saved = store.save(actor, session.workspace.current(), session.baseHash);
        session.commit(saved);
        return saved;
    }

    synchronized boolean closeSession(UUID owner, boolean discard) {
        EditSession session = require(owner);
        if (session.dirty && !discard)
            throw new IllegalArgumentException("有未保存修改；先 save，或使用 close discard 丢弃。");
        sessions.remove(owner);
        return session.dirty;
    }

    synchronized void disconnected(UUID owner) { sessions.remove(owner); }
    @Override public synchronized void close() { closed = true; sessions.clear(); }
    synchronized boolean isClosed() { return closed; }

    synchronized EditSession require(UUID owner) {
        requireOpen();
        EditSession session = sessions.get(owner);
        if (session == null) throw new IllegalArgumentException("先 create 或 open 一个蓝图");
        return session;
    }

    synchronized EditSession requireEditable(UUID actor) {
        EditSession session = require(actor);
        Blueprint saved = session.saved;
        if (!saved.metadata().permissions().mayEdit(actor, saved.metadata().author()))
            throw new SecurityException("此蓝图仅可查看；需要原作者授权编辑或允许克隆。");
        return session;
    }

    synchronized int size() { return sessions.size(); }

    private void requireNoOpenSession(UUID owner) {
        requireOpen();
        Objects.requireNonNull(owner, "owner");
        if (sessions.containsKey(owner)) throw new IllegalArgumentException("请先保存并 close 当前蓝图工作区");
        if (sessions.size() >= MAX_OPEN_WORKSPACES)
            throw new IllegalArgumentException("当前打开的蓝图工作区已达服务器上限");
    }

    private void requireOpen() {
        if (closed) throw new IllegalArgumentException("蓝图工作区服务已停止。");
    }

    private void put(UUID owner, Blueprint document) { sessions.put(owner, new EditSession(document)); }

    static final class EditSession {
        BlueprintWorkspace workspace;
        Blueprint saved;
        String baseHash;
        boolean dirty;

        EditSession(Blueprint document) { commit(document); }

        private void commit(Blueprint document) {
            workspace = new BlueprintWorkspace(document);
            saved = document;
            baseHash = document.metadata().hash();
            dirty = false;
        }
    }
}
