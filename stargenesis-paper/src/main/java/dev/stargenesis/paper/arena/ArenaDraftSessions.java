package dev.stargenesis.paper.arena;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.design.ArenaRuleset;
import dev.stargenesis.design.ArenaRulesetWorkspace;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded player-owned draft workspaces. Admission and persistence finish before close/disconnect
 * returns; they cannot republish a retired session. Workspace edits stay on the player's command
 * thread and cannot write through the store without passing this lifecycle guard.
 */
final class ArenaDraftSessions implements AutoCloseable {
    static final int MAX_OPEN_WORKSPACES = 64;
    private final ArenaRulesetStore store;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private boolean closed;

    ArenaDraftSessions(ArenaRulesetStore store) { this.store = Objects.requireNonNull(store); }

    synchronized ArenaRuleset create(UUID owner, DesignId id) throws IOException {
        requireNoOpenSession(owner);
        ArenaRuleset draft = store.create(owner, id);
        put(owner, draft);
        return draft;
    }

    synchronized ArenaRuleset clonePublished(UUID owner, DesignId source, DesignId target) throws IOException {
        requireNoOpenSession(owner);
        ArenaRuleset draft = store.clonePublished(owner, source, target);
        put(owner, draft);
        return draft;
    }

    synchronized ArenaRuleset open(UUID owner, DesignId id) throws IOException {
        requireNoOpenSession(owner);
        ArenaRuleset draft = store.loadDraft(owner, id)
                .orElseThrow(() -> new IllegalArgumentException("找不到可编辑的 ArenaRuleset 草稿 " + id));
        put(owner, draft);
        return draft;
    }

    synchronized ArenaRuleset save(UUID owner) throws IOException {
        Session session = require(owner);
        ArenaRuleset saved = store.saveDraft(owner, session.workspace.current(), session.baseHash);
        put(owner, saved);
        return saved;
    }

    synchronized ArenaRulesetStore.ReviewRecord submit(UUID owner) throws IOException {
        Session session = require(owner);
        if (session.dirty()) throw new IllegalArgumentException("先保存草稿再发布。");
        return store.submitForReview(owner, session.workspace.current().metadata().id(), session.baseHash);
    }

    synchronized boolean closeSession(UUID owner, boolean discard) {
        Session session = require(owner);
        boolean dirty = session.dirty();
        if (dirty && !discard) throw new IllegalArgumentException("草稿尚未保存；先 save 或 close discard。");
        sessions.remove(owner);
        return dirty;
    }

    synchronized void disconnected(UUID owner) { sessions.remove(owner); }

    @Override public synchronized void close() { closed = true; sessions.clear(); }

    synchronized Session require(UUID owner) {
        requireOpen();
        Session session = sessions.get(owner);
        if (session == null) throw new IllegalArgumentException("没有打开的 ArenaRuleset 草稿");
        return session;
    }

    synchronized int size() { return sessions.size(); }

    private void requireNoOpenSession(UUID owner) {
        requireOpen();
        Objects.requireNonNull(owner, "owner");
        if (sessions.containsKey(owner)) throw new IllegalArgumentException("先关闭当前 ArenaRuleset 草稿。");
        // Reject before create/clone writes a document or makes a new author directory.
        if (sessions.size() >= MAX_OPEN_WORKSPACES)
            throw new IllegalArgumentException("当前打开的 ArenaRuleset 草稿已达服务器上限（64）。");
    }

    private void requireOpen() {
        if (closed) throw new IllegalArgumentException("Arena 草稿服务已停止。");
    }

    private void put(UUID owner, ArenaRuleset document) { sessions.put(owner, new Session(document)); }

    static final class Session {
        final ArenaRulesetWorkspace workspace;
        final String baseHash;
        final ArenaRuleset saved;

        Session(ArenaRuleset saved) {
            this.saved = saved;
            baseHash = saved.metadata().hash();
            workspace = new ArenaRulesetWorkspace(saved);
        }

        boolean dirty() { return !workspace.current().equals(saved); }
    }
}
