package com.redsocial.messaging;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ChatTickets {
    private static final long TICKET_SECONDS = 30;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Ticket> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Peer> connected = new ConcurrentHashMap<>();

    @Inject
    PresenceService presence;

    public record Ticket(String userId, String otherUserId, String conversationId,
                         Instant expiresAt, Instant authExpiresAt) {}
    public record IssuedTicket(String conversationId, String ticket) {}

    public static final class Peer {
        public final String userId;
        public final String otherUserId;
        public final String conversationId;
        public final Instant authExpiresAt;
        private long windowStart = System.currentTimeMillis();
        private int sentInWindow;

        Peer(Ticket ticket) {
            userId = ticket.userId();
            otherUserId = ticket.otherUserId();
            conversationId = ticket.conversationId();
            authExpiresAt = ticket.authExpiresAt();
        }

        public boolean authValid() { return authExpiresAt.isAfter(Instant.now()); }

        synchronized boolean allowSend() {
            long now = System.currentTimeMillis();
            if (now - windowStart >= 10_000) {
                windowStart = now;
                sentInWindow = 0;
            }
            return ++sentInWindow <= 10;
        }
    }

    public IssuedTicket issue(String userId, String otherUserId, Instant authExpiresAt) {
        pending.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(Instant.now()));
        if (pending.size() >= 10_000) {
            throw new WebApplicationException("Too many pending connections", Response.Status.TOO_MANY_REQUESTS);
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String conversationId = ConversationId.of(userId, otherUserId);
        pending.put(value, new Ticket(userId, otherUserId, conversationId,
                Instant.now().plusSeconds(TICKET_SECONDS), authExpiresAt));
        return new IssuedTicket(conversationId, value);
    }

    public Ticket consume(String value, String conversationId) {
        Ticket ticket = pending.remove(value);
        if (ticket == null || ticket.expiresAt().isBefore(Instant.now())
                || !ticket.authExpiresAt().isAfter(Instant.now())
                || !ticket.conversationId().equals(conversationId)) return null;
        return ticket;
    }

    public void connected(String connectionId, Ticket ticket) {
        presence.registerConnection(ticket.userId(), connectionId);
        connected.put(connectionId, new Peer(ticket));
    }

    public Peer peer(String connectionId) {
        Peer peer = connected.get(connectionId);
        if (peer != null) {
            // Update heartbeat on activity
            presence.heartbeat(peer.userId, connectionId);
        }
        return peer;
    }

    public void disconnected(String connectionId) {
        Peer peer = connected.remove(connectionId);
        if (peer != null) {
            presence.unregisterConnection(peer.userId, connectionId);
        }
    }
}
