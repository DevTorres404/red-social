package com.redsocial.messaging;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/**
 * Tracks online presence of users based on active WebSocket connections.
 *
 * Semantics:
 *   - A user is "online" if they have at least ONE active WebSocket connection.
 *   - Connections are tracked per-user with a count (multi-tab support).
 *   - Stale connections are expired via a background cleanup (heartbeat timeout).
 *   - For a single backend instance, this uses in-memory state.
 *   - For multi-replica deployments, this would need distributed state (Redis/TTL + pub-sub).
 *
 * Thread-safe: uses ConcurrentHashMap for all state.
 */
@ApplicationScoped
public class PresenceService {

    private static final Logger LOG = Logger.getLogger(PresenceService.class);

    // Heartbeat timeout: if no ping received for this duration, connection is stale
    private static final long HEARTBEAT_TIMEOUT_MS = 45_000; // 45 seconds
    // Cleanup interval
    private static final int MAX_CONNECTIONS_PER_USER = 32;

    // userId -> Map of connectionId -> lastHeartbeat
    private final Map<String, Map<String, Long>> userConnections = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public PresenceService() { this(System::currentTimeMillis); }

    PresenceService(LongSupplier clock) { this.clock = clock; }

    /**
     * Register a new WebSocket connection for a user.
     * Increments the user's connection count.
     */
    public void registerConnection(String userId, String connectionId) {
        long now = clock.getAsLong();
        userConnections.compute(userId, (id, current) -> {
            Map<String, Long> connections = current == null ? new ConcurrentHashMap<>() : current;
            connections.entrySet().removeIf(entry -> now - entry.getValue() >= HEARTBEAT_TIMEOUT_MS);
            if (!connections.containsKey(connectionId) && connections.size() >= MAX_CONNECTIONS_PER_USER) {
                throw new WebApplicationException("Too many active sessions", Response.Status.TOO_MANY_REQUESTS);
            }
            connections.put(connectionId, now);
            return connections;
        });
        LOG.debugf("Presence: user %s connected (connection %s), total connections: %d",
                userId, connectionId, getConnectionCount(userId));
    }

    /**
     * Unregister a WebSocket connection for a user.
     * Decrements the user's connection count; if zero, user goes offline.
     */
    public void unregisterConnection(String userId, String connectionId) {
        userConnections.computeIfPresent(userId, (id, connections) -> {
            connections.remove(connectionId);
            return connections.isEmpty() ? null : connections;
        });
    }

    /**
     * Update heartbeat for a connection (called on ping/pong or message activity).
     */
    public void heartbeat(String userId, String connectionId) {
        userConnections.computeIfPresent(userId, (id, connections) -> {
            connections.replace(connectionId, clock.getAsLong());
            return connections;
        });
    }

    /**
     * Check if a user is currently online (has at least one active connection).
     */
    public boolean isOnline(String userId) {
        Map<String, Long> conns = userConnections.get(userId);
        if (conns == null || conns.isEmpty()) return false;
        // Verify at least one connection is not stale
        long now = clock.getAsLong();
        return conns.values().stream().anyMatch(ts -> now - ts < HEARTBEAT_TIMEOUT_MS);
    }

    /**
     * Get the number of active (non-stale) connections for a user.
     */
    public int getConnectionCount(String userId) {
        Map<String, Long> conns = userConnections.get(userId);
        if (conns == null || conns.isEmpty()) return 0;
        long now = clock.getAsLong();
        return (int) conns.values().stream().filter(ts -> now - ts < HEARTBEAT_TIMEOUT_MS).count();
    }

    /**
     * Get online status for multiple users (batch query for followers/conversation participants).
     */
    public Map<String, Boolean> getOnlineStatus(Collection<String> userIds) {
        return userIds.stream()
                .collect(Collectors.toMap(
                        id -> id,
                        this::isOnline
                ));
    }

    /**
     * Get all currently online user IDs.
     */
    public Collection<String> getOnlineUserIds() {
        long now = clock.getAsLong();
        return userConnections.entrySet().stream()
                .filter(e -> e.getValue().values().stream().anyMatch(ts -> now - ts < HEARTBEAT_TIMEOUT_MS))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    /**
     * Background cleanup of stale connections.
     * Called periodically by a scheduler.
     */
    public void cleanupStaleConnections() {
        long now = clock.getAsLong();
        AtomicInteger removedUsers = new AtomicInteger();
        AtomicInteger removedConnections = new AtomicInteger();

        for (String userId : userConnections.keySet()) {
            userConnections.computeIfPresent(userId, (id, connections) -> {
                int before = connections.size();
                connections.entrySet().removeIf(entry -> now - entry.getValue() >= HEARTBEAT_TIMEOUT_MS);
                removedConnections.addAndGet(before - connections.size());
                if (connections.isEmpty()) {
                    removedUsers.incrementAndGet();
                    return null;
                }
                return connections;
            });
        }

        if (removedUsers.get() > 0 || removedConnections.get() > 0) {
            LOG.debugf("Presence cleanup: removed %d users, %d stale connections",
                    removedUsers.get(), removedConnections.get());
        }
    }
}
