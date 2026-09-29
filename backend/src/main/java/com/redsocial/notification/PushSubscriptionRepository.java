package com.redsocial.notification;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.neo4j.driver.Driver;
import org.neo4j.driver.types.Node;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * All Neo4j queries for (:PushSubscription) nodes.
 *
 * A subscription is a browser (or device) endpoint, not something a user
 * creates by hand: it arrives from the browser's Push API payload and belongs
 * to the user that was logged in when the browser registered it.
 *
 * Ownership is (:Usuario)-[:HAS_SUBSCRIPTION]->(:PushSubscription).
 *
 * The MERGE key is 'endpoint', not 'id': the same browser re-registering after
 * a token refresh must update the existing node instead of piling up dead ones,
 * and Neo4j rejects duplicates on a UNIQUE constraint. 'id' is therefore only
 * assigned on creation, and it stays stable for the life of the subscription.
 */
@ApplicationScoped
public class PushSubscriptionRepository {

    @Inject
    Driver driver;

    // ── Create / refresh ──────────────────────────────────────────────────────

    /**
     * Register (or refresh) a push subscription for a user.
     *
     * If the endpoint was previously attached to a different user — the same
     * browser logging in as somebody else — the old edge is removed first, so a
     * subscription never fans out notifications to two accounts.
     *
     * @return the persisted subscription, with its id and creation date
     */
    public PushSubscription subscribe(String userId, String endpoint, String p256dh,
                                      String auth, String userAgent) {
        String subscriptionId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        try (var session = driver.session()) {
            return session.executeWrite(tx -> {
                // 1. Desvincular el endpoint de cualquier dueño anterior.
                tx.run("""
                        MATCH (:Usuario)-[r:HAS_SUBSCRIPTION]->(s:PushSubscription {endpoint: $endpoint})
                        DELETE r
                        """,
                        Map.of("endpoint", endpoint)
                );

                // 2. Crear o actualizar la suscripción y vincularla al usuario.
                var result = tx.run("""
                        MATCH (u:Usuario {id: $userId})
                        MERGE (s:PushSubscription {endpoint: $endpoint})
                          ON CREATE SET s.id = $subscriptionId,
                                        s.createdAt = $now
                        SET s.p256dh    = $p256dh,
                            s.auth       = $auth,
                            s.userAgent  = $userAgent
                        MERGE (u)-[:HAS_SUBSCRIPTION]->(s)
                        RETURN s
                        """,
                        Map.of(
                                "userId", userId,
                                "endpoint", endpoint,
                                "subscriptionId", subscriptionId,
                                "p256dh", p256dh,
                                "auth", auth,
                                "userAgent", userAgent,
                                "now", now.toString()
                        )
                );
                return mapRow(result.single().get("s").asNode());
            });
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /** All push subscriptions of a user, newest first. */
    public List<PushSubscription> findByUser(String userId) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                        MATCH (:Usuario {id: $userId})-[:HAS_SUBSCRIPTION]->(s:PushSubscription)
                        RETURN s
                        ORDER BY s.createdAt DESC, s.id ASC
                        LIMIT 200
                        """,
                        Map.of("userId", userId)
                );
                return result.list(row -> mapRow(row.get("s").asNode()));
            });
        }
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Remove one endpoint from a user.
     *
     * @return true if a subscription was actually detached
     */
    public boolean unsubscribe(String userId, String endpoint) {
        try (var session = driver.session()) {
            return session.executeWrite(tx -> {
                var result = tx.run("""
                        MATCH (:Usuario {id: $userId})-[r:HAS_SUBSCRIPTION]->(s:PushSubscription {endpoint: $endpoint})
                        WITH s, r, s.id AS id
                        DELETE r
                        DETACH DELETE s
                        RETURN id
                        """, Map.of("userId", userId, "endpoint", endpoint));
                if (!result.hasNext()) return false;
                String subscriptionId = result.single().get("id").asString();
                tx.run("MATCH (d:PushDelivery {subscriptionId: $id}) DELETE d", Map.of("id", subscriptionId));
                return true;
            });
        }
    }

    /**
     * Drop every push subscription of a user (account deletion / logout-all).
     *
     * @return how many relationships were removed
     */
    public long deleteAllForUser(String userId) {
        try (var session = driver.session()) {
            var summary = session.executeWrite(tx -> tx.run("""
                    MATCH (:Usuario {id: $userId})-[r:HAS_SUBSCRIPTION]->(s:PushSubscription)
                    DELETE r
                    WITH s
                    DETACH DELETE s
                    """,
                    Map.of("userId", userId)
            ).consume());
            return summary.counters().relationshipsDeleted();
        }
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private PushSubscription mapRow(Node node) {
        return new PushSubscription(
                node.get("id").asString(),
                node.get("endpoint").asString(),
                node.get("p256dh").asString(null),
                node.get("auth").asString(null),
                node.get("userAgent").asString(null),
                Instant.parse(node.get("createdAt").asString())
        );
    }
}
