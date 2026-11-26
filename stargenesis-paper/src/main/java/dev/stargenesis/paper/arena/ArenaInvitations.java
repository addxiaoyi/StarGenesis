package dev.stargenesis.paper.arena;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** In-memory Arena invitations; team membership is committed by the match service. */
final class ArenaInvitations implements AutoCloseable {
    static final int MAX_INVITATIONS = 64;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private boolean closed;

    synchronized void invite(UUID inviter, UUID invited, String team) {
        requireOpen();
        Objects.requireNonNull(inviter, "inviter");
        Objects.requireNonNull(invited, "invited");
        if (inviter.equals(invited) || team == null || !team.matches("[a-z][a-z0-9_-]{0,31}"))
            throw new IllegalArgumentException("需要不同玩家和有效的 Arena team ID。");
        if (pending.containsKey(inviter) || pending.containsKey(invited) || pending.values().stream()
                .anyMatch(existing -> existing.invitation.inviter().equals(inviter) || existing.invitation.inviter().equals(invited)))
            throw new IllegalStateException("你或受邀玩家已有待处理的 Arena 邀请。");
        if (pending.size() >= MAX_INVITATIONS) throw new IllegalStateException("Arena 邀请数量已达上限。");
        pending.put(invited, new Pending(new Invitation(inviter, team)));
    }

    Invitation accept(UUID invited, TeamJoiner joiner) throws IOException {
        Objects.requireNonNull(joiner, "joiner");
        Pending admitted;
        synchronized (this) {
            requireOpen();
            admitted = pending.get(invited);
            if (admitted == null || admitted.accepting)
                throw new IllegalStateException("没有可接受的有效 Arena 邀请。");
            // Keep both player reservations and the capacity slot until this admitted operation finishes.
            admitted.accepting = true;
        }
        boolean retryable = false;
        try {
            joiner.join(admitted.invitation.inviter(), invited, admitted.invitation.team());
            return admitted.invitation;
        } catch (IOException failure) {
            retryable = true;
            throw failure;
        } finally {
            synchronized (this) {
                // Cancellation, shutdown or a new invitation must never be undone by this older completion.
                if (pending.get(invited) == admitted) {
                    if (retryable) admitted.accepting = false;
                    else pending.remove(invited);
                }
            }
        }
    }

    synchronized void cancel(UUID player) {
        pending.remove(player);
        pending.entrySet().removeIf(entry -> entry.getValue().invitation.inviter().equals(player));
    }

    @Override public synchronized void close() { closed = true; pending.clear(); }

    private void requireOpen() { if (closed) throw new IllegalStateException("Arena 邀请已停止。"); }

    private static final class Pending {
        private final Invitation invitation;
        private boolean accepting;
        private Pending(Invitation invitation) { this.invitation = invitation; }
    }

    record Invitation(UUID inviter, String team) { }
    @FunctionalInterface interface TeamJoiner { void join(UUID inviter, UUID invited, String team) throws IOException; }
}
