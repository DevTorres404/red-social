package com.redsocial.feed;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-use, short-lived tickets that authorize a browser to open the
 * broadcast-only feed WebSocket channel (/ws/feed).
 *
 * Mirrors ChatTickets minus the conversation binding: a ticket is bound to a
 * user and to the auth window of the JWT that issued it, not to a peer.
 */
@ApplicationScoped
public class FeedTickets {
    private static final long TICKET_SECONDS = 30;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, FeedTicket> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Viewer> connected = new ConcurrentHashMap<>();

    public record FeedTicket(String userId, Instant expiresAt, Instant authExpiresAt) {}

    public static final class Viewer {
        public final String userId;
        public final Instant authExpiresAt;
        /** Null means "all post events"; otherwise only these post IDs are delivered. */
        private volatile Set<String> filter;

        Viewer(FeedTicket ticket) {
            userId = ticket.userId();
            authExpiresAt = ticket.authExpiresAt();
        }

        public boolean authValid() { return authExpiresAt.isAfter(Instant.now()); }

        public boolean wantsPost(String postId) {
            return filter == null || filter.contains(postId);
        }

        public void subscribeTo(Set<String> postIds) {
            filter = postIds;
        }
    }

    public FeedResource.TicketResponse issue(String userId, Instant authExpiresAt) {
        pending.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(Instant.now()));
        if (pending.size() >= 10_000) {
            throw new WebApplicationException("Too many pending connections", Response.Status.TOO_MANY_REQUESTS);
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        pending.put(value, new FeedTicket(userId, Instant.now().plusSeconds(TICKET_SECONDS), authExpiresAt));
        return new FeedResource.TicketResponse("feed", value);
    }

    public FeedTicket consume(String value) {
        FeedTicket ticket = pending.remove(value);
        if (ticket == null || ticket.expiresAt().isBefore(Instant.now())
                || !ticket.authExpiresAt().isAfter(Instant.now())) return null;
        return ticket;
    }

    public void connected(String connectionId, FeedTicket ticket) {
        connected.put(connectionId, new Viewer(ticket));
    }

    public Viewer viewer(String connectionId) {
        return connected.get(connectionId);
    }

    public void disconnected(String connectionId) {
        connected.remove(connectionId);
    }
}